#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <exception>
#include <thread>
#include <vector>

#include <rubberband/RubberBandStretcher.h>

using RubberBand::RubberBandStretcher;

namespace {
constexpr size_t BLOCK = 4096;
constexpr size_t RETRIEVE_BLOCK = 8192;

void throwJava(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}
}  // namespace

// PCM 16 bits little-endian entrelazado -> mismo formato, con el tono cambiado y la duración intacta.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tonokempo_app_RubberBandEngine_process(JNIEnv *env, jobject /*thiz*/,
                                                jbyteArray pcmIn, jint sampleRate, jint channelsIn,
                                                jfloat semitones, jboolean preserveFormants,
                                                jdouble timeRatioIn, jobject listener) {
    try {
        const size_t ch = static_cast<size_t>(channelsIn);
        const size_t totalFrames = static_cast<size_t>(env->GetArrayLength(pcmIn)) / (2 * ch);

        if (totalFrames == 0) return env->NewByteArray(0);

        jmethodID onProgress = nullptr;
        if (listener != nullptr) {
            jclass cls = env->GetObjectClass(listener);
            onProgress = env->GetMethodID(cls, "onProgress", "(F)V");
            if (onProgress == nullptr) env->ExceptionClear();
        }
        int lastPct = -1;
        auto report = [&](float f) {
            if (onProgress == nullptr) return;
            int pct = static_cast<int>(f * 100.0f);
            if (pct == lastPct) return;
            lastPct = pct;
            env->CallVoidMethod(listener, onProgress, static_cast<jfloat>(f));
        };

        // R3 ("Finer"), modo offline. Sin formantes preservados el timbre se mueve con el tono.
        int options = RubberBandStretcher::OptionProcessOffline |
                      RubberBandStretcher::OptionEngineFiner |
                      (preserveFormants ? RubberBandStretcher::OptionFormantPreserved
                                        : RubberBandStretcher::OptionFormantShifted);

        const double pitchScale = std::pow(2.0, static_cast<double>(semitones) / 12.0);
        // Razón de tiempo: 1.0 = misma duración; 1.2 = 20 % más larga (más lenta); 0.8 = más corta (más rápida)
        const double timeRatio = (timeRatioIn > 0.05 && timeRatioIn < 20.0) ? timeRatioIn : 1.0;

        RubberBandStretcher rb(static_cast<size_t>(sampleRate), ch, options, timeRatio, pitchScale);
        rb.setExpectedInputDuration(totalFrames);
        rb.setMaxProcessSize(BLOCK);

        std::vector<jbyte> raw(BLOCK * 2 * ch);
        std::vector<std::vector<float>> planar(ch, std::vector<float>(BLOCK));
        std::vector<const float *> inPtrs(ch);
        for (size_t c = 0; c < ch; c++) inPtrs[c] = planar[c].data();

        auto readBlock = [&](size_t startFrame, size_t n) {
            env->GetByteArrayRegion(pcmIn, static_cast<jsize>(startFrame * 2 * ch),
                                    static_cast<jsize>(n * 2 * ch), raw.data());
            const int16_t *s = reinterpret_cast<const int16_t *>(raw.data());
            for (size_t i = 0; i < n; i++)
                for (size_t c = 0; c < ch; c++)
                    planar[c][i] = static_cast<float>(s[i * ch + c]) / 32768.0f;
        };

        // Pasada de estudio (en R3 no hace nada, pero es el uso documentado del modo offline)
        for (size_t pos = 0; pos < totalFrames; pos += BLOCK) {
            size_t n = std::min(BLOCK, totalFrames - pos);
            readBlock(pos, n);
            rb.study(inPtrs.data(), n, pos + n >= totalFrames);
        }

        std::vector<int16_t> out;
        out.reserve(static_cast<size_t>(static_cast<double>(totalFrames) * timeRatio) * ch +
                    static_cast<size_t>(sampleRate) * ch);

        std::vector<std::vector<float>> outBuf(ch, std::vector<float>(RETRIEVE_BLOCK));
        std::vector<float *> outPtrs(ch);
        for (size_t c = 0; c < ch; c++) outPtrs[c] = outBuf[c].data();

        auto drain = [&]() {
            int avail;
            while ((avail = rb.available()) > 0) {
                size_t n = std::min(static_cast<size_t>(avail), RETRIEVE_BLOCK);
                size_t got = rb.retrieve(outPtrs.data(), n);
                if (got == 0) break;
                for (size_t i = 0; i < got; i++) {
                    for (size_t c = 0; c < ch; c++) {
                        float v = std::max(-1.0f, std::min(1.0f, outBuf[c][i]));
                        out.push_back(static_cast<int16_t>(std::lrintf(v * 32767.0f)));
                    }
                }
            }
        };

        for (size_t pos = 0; pos < totalFrames; pos += BLOCK) {
            size_t n = std::min(BLOCK, totalFrames - pos);
            readBlock(pos, n);
            rb.process(inPtrs.data(), n, pos + n >= totalFrames);
            drain();
            report(0.95f * static_cast<float>(pos + n) / static_cast<float>(totalFrames));
        }

        // Vaciar lo que quede hasta que Rubber Band avise que terminó (available() < 0)
        int idle = 0;
        while (true) {
            int avail = rb.available();
            if (avail < 0) break;
            if (avail == 0) {
                if (++idle > 3000) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            idle = 0;
            drain();
        }
        report(1.0f);

        const jsize outBytes = static_cast<jsize>(out.size() * 2);
        jbyteArray result = env->NewByteArray(outBytes);
        if (result == nullptr) return nullptr;  // OutOfMemoryError ya quedó pendiente
        env->SetByteArrayRegion(result, 0, outBytes, reinterpret_cast<const jbyte *>(out.data()));
        return result;
    } catch (const std::exception &e) {
        throwJava(env, e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "Error desconocido en Rubber Band");
        return nullptr;
    }
}
