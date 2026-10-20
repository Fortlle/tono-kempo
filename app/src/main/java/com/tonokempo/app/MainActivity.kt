package com.tonokempo.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedOutputStream
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQ_OPEN = 1
        const val REQ_SAVE = 2
        const val TONE_CENTER = 24 // SeekBar 0..48 -> -12.0 .. +12.0 semitonos, pasos de 0.5
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: PcmPlayer

    private var audio: DecodedAudio? = null
    private var fileName = "audio"
    private var cacheKey: String? = null
    private var cachePcm: ByteArray? = null
    private var busy = false
    private var dragging = false

    private lateinit var btnOpen: Button
    private lateinit var txtFile: TextView
    private lateinit var txtTone: TextView
    private lateinit var seekTone: SeekBar
    private lateinit var radioRb: RadioButton
    private lateinit var radioKempo: RadioButton
    private lateinit var checkFormant: CheckBox
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var seekPos: SeekBar
    private lateinit var txtPos: TextView
    private lateinit var btnSave: Button
    private lateinit var txtStatus: TextView

    private val ticker = object : Runnable {
        override fun run() {
            if (player.isActive && !dragging) {
                val dur = player.durationMs.toInt()
                val pos = player.positionMs().toInt().coerceIn(0, dur)
                seekPos.max = dur
                seekPos.progress = pos
                txtPos.text = "${fmtTime(pos)} / ${fmtTime(dur)}"
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = PcmPlayer(onFinished = {
            ui.post {
                player.stop()
                seekPos.progress = 0
                txtPos.text = "0:00 / ${fmtTime(audio?.durationMs?.toInt() ?: 0)}"
                refreshButtons()
            }
        })
        buildUi()
        refreshButtons()
        ui.post(ticker)

        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { loadUri(it) }
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        player.stop()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }

        btnOpen = Button(this).apply {
            text = "Elegir audio"
            setOnClickListener { pickFile() }
        }
        root.addView(btnOpen, lp)

        txtFile = TextView(this).apply { text = "Ningún archivo cargado"; textSize = 15f }
        root.addView(txtFile, lp)

        txtTone = TextView(this).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        root.addView(txtTone, lp)

        seekTone = SeekBar(this).apply {
            max = 48
            progress = TONE_CENTER
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateToneLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seekTone, lp)
        updateToneLabel()

        root.addView(TextView(this).apply { text = "Motor de audio"; typeface = Typeface.DEFAULT_BOLD }, lp)
        radioRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Rubber Band R3 (más calidad, más lento)"
        }
        radioKempo = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Kempo (más rápido)"
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(radioRb)
            addView(radioKempo)
            check(radioRb.id)
        }
        root.addView(group, lp)

        checkFormant = CheckBox(this).apply { text = "Mover el timbre (formantes) junto con el tono" }
        root.addView(checkFormant, lp)

        btnPlay = Button(this).apply {
            text = "Aplicar y reproducir"
            setOnClickListener { applyAndPlay() }
        }
        root.addView(btnPlay, lp)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnPause = Button(this).apply {
            text = "Pausa"
            setOnClickListener {
                if (player.isPaused) player.resume() else player.pause()
                refreshButtons()
            }
        }
        btnStop = Button(this).apply {
            text = "Detener"
            setOnClickListener {
                player.stop()
                seekPos.progress = 0
                txtPos.text = "0:00 / ${fmtTime(audio?.durationMs?.toInt() ?: 0)}"
                refreshButtons()
            }
        }
        row.addView(btnPause, half)
        row.addView(btnStop, half)
        root.addView(row, lp)

        seekPos = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) txtPos.text = "${fmtTime(p)} / ${fmtTime(max)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    dragging = false
                    player.seekToMs(progress.toLong())
                }
            })
        }
        root.addView(seekPos, lp)

        txtPos = TextView(this).apply { text = "0:00 / 0:00" }
        root.addView(txtPos, lp)

        btnSave = Button(this).apply {
            text = "Guardar como WAV"
            setOnClickListener { pickSaveLocation() }
        }
        root.addView(btnSave, lp)

        txtStatus = TextView(this).apply { textSize = 14f }
        root.addView(txtStatus, lp)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateToneLabel() {
        val st = semitones()
        txtTone.text = String.format(Locale.getDefault(), "%+.1f semitonos", st)
    }

    private fun semitones(): Float = (seekTone.progress - TONE_CENTER) / 2f

    private fun engine(): Engine = if (radioRb.isChecked) Engine.RUBBERBAND else Engine.KEMPO

    private fun refreshButtons() {
        val loaded = audio != null
        btnOpen.isEnabled = !busy
        btnPlay.isEnabled = loaded && !busy
        btnSave.isEnabled = loaded && !busy
        btnPause.isEnabled = player.isActive
        btnStop.isEnabled = player.isActive
        seekPos.isEnabled = player.isActive
        btnPause.text = if (player.isPaused) "Reanudar" else "Pausa"
    }

    private fun setBusy(value: Boolean, status: String) {
        busy = value
        txtStatus.text = status
        refreshButtons()
    }

    private fun fmtTime(ms: Int): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ---------- Archivos ----------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    private fun pickSaveLocation() {
        val st = semitones()
        val tag = String.format(Locale.US, "%+.1f", st).replace(".0", "")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "${fileName}_${tag}st.wav")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SAVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> loadUri(uri)
            REQ_SAVE -> saveTo(uri)
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "audio"
    }

    private fun loadUri(uri: Uri) {
        player.stop()
        audio = null
        cacheKey = null
        cachePcm = null
        val name = displayName(uri)
        setBusy(true, "Decodificando…")
        Thread {
            try {
                val decoded = AudioDecoder.decode(this, uri) { p ->
                    ui.post { txtStatus.text = "Decodificando… ${(p * 100).toInt()}%" }
                }
                if (decoded.channels > 2) {
                    throw IllegalArgumentException("Solo se admiten audios mono o estéreo")
                }
                ui.post {
                    audio = decoded
                    fileName = name.substringBeforeLast('.')
                    txtFile.text = "$name\n${decoded.sampleRate} Hz · ${if (decoded.channels == 1) "mono" else "estéreo"} · ${fmtTime(decoded.durationMs.toInt())}"
                    seekPos.progress = 0
                    txtPos.text = "0:00 / ${fmtTime(decoded.durationMs.toInt())}"
                    setBusy(false, "Listo. Elige los semitonos y pulsa Aplicar y reproducir.")
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al abrir: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun saveTo(uri: Uri) {
        process { pcm ->
            val a = audio ?: return@process
            setBusy(true, "Guardando…")
            Thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { raw ->
                        BufferedOutputStream(raw, 1 shl 16).use { out ->
                            WavWriter.write(out, pcm, a.sampleRate, a.channels)
                        }
                    }
                    ui.post { setBusy(false, "Guardado correctamente") }
                } catch (e: Throwable) {
                    ui.post { setBusy(false, "Error al guardar: ${e.message ?: e.javaClass.simpleName}") }
                }
            }.start()
        }
    }

    // ---------- Procesamiento ----------

    /** Procesa con los ajustes actuales (o reutiliza el resultado en caché) y entrega el PCM. */
    private fun process(then: (ByteArray) -> Unit) {
        val a = audio ?: return
        val st = semitones()
        val fm = checkFormant.isChecked
        val eng = engine()
        val key = "$st|$fm|$eng"

        val cached = cachePcm
        if (key == cacheKey && cached != null) {
            then(cached)
            return
        }

        val engineName = if (eng == Engine.RUBBERBAND) "Rubber Band" else "Kempo"
        setBusy(true, "Procesando con $engineName…")
        Thread {
            try {
                val out = PitchProcessor.shift(a, st, fm, eng) { p ->
                    ui.post { txtStatus.text = "Procesando con $engineName… ${(p * 100).toInt()}%" }
                }
                ui.post {
                    cacheKey = key
                    cachePcm = out
                    setBusy(false, "Listo")
                    then(out)
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al procesar: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun applyAndPlay() {
        process { pcm ->
            val a = audio ?: return@process
            val startMs = if (player.isActive) player.positionMs() else 0L
            player.start(pcm, a.sampleRate, a.channels, startMs)
            refreshButtons()
        }
    }
}
