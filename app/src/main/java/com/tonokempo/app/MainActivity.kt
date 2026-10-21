package com.tonokempo.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Audio ya procesado, listo para reproducir o guardar. */
class Rendered(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

class MainActivity : Activity() {

    private companion object {
        const val REQ_OPEN = 1
        const val REQ_SAVE = 2
        const val REQ_MODEL = 3
        const val TONE_CENTER = 24 // SeekBar 0..48 -> -12.0 .. +12.0 semitonos, pasos de 0.5
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: PcmPlayer

    private var audio: DecodedAudio? = null
    private var fileName = "audio"
    private var cacheKey: String? = null
    private var rendered: Rendered? = null
    @Volatile private var stems: SeparatedStems? = null
    private val cancelFlag = AtomicBoolean(false)
    private var busy = false
    private var cancellable = false
    private var dragging = false

    private lateinit var btnOpen: Button
    private lateinit var txtFile: TextView
    private lateinit var txtTone: TextView
    private lateinit var seekTone: SeekBar
    private lateinit var radioStems: RadioButton
    private lateinit var radioWhole: RadioButton
    private lateinit var txtStemNote: TextView
    private lateinit var checkDrums: CheckBox
    private lateinit var btnModel: Button
    private lateinit var btnImport: Button
    private lateinit var engineBox: LinearLayout
    private lateinit var radioRb: RadioButton
    private lateinit var radioKempo: RadioButton
    private lateinit var checkFormant: CheckBox
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var seekPos: SeekBar
    private lateinit var txtPos: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
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
        updateModeUi()
        refreshButtons()
        ui.post(ticker)

        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { loadUri(it) }
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        cancelFlag.set(true)
        player.stop()
        if (isFinishing) stemsDir().deleteRecursively()
        super.onDestroy()
    }

    private fun stemsDir() = File(cacheDir, "stems")

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

        // --- Modo ---
        root.addView(TextView(this).apply { text = "Modo"; typeface = Typeface.DEFAULT_BOLD }, lp)
        radioStems = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Separar en pistas (Demucs): más calidad, lento"
        }
        radioWhole = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Canción completa: rápido"
        }
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(radioStems)
            addView(radioWhole)
            check(radioStems.id)
            setOnCheckedChangeListener { _, _ ->
                updateModeUi()
                refreshButtons()
            }
        }
        root.addView(modeGroup, lp)

        txtStemNote = TextView(this).apply {
            textSize = 13f
            text = "Separa voz, batería, bajo y otros; cada pista se transpone por separado con Rubber Band " +
                "(timbre conservado) y se vuelven a juntar. La primera vez descarga un modelo de unos 316 MB. " +
                "Deja la app abierta mientras trabaja."
        }
        root.addView(txtStemNote, lp)

        checkDrums = CheckBox(this).apply { text = "Transponer también la batería" }
        root.addView(checkDrums, lp)

        btnModel = Button(this).apply {
            text = "Descargar modelo de separación (316 MB)"
            setOnClickListener { downloadModel() }
        }
        root.addView(btnModel, lp)

        btnImport = Button(this).apply {
            text = "Importar modelo desde un archivo .onnx"
            setOnClickListener { pickModelFile() }
        }
        root.addView(btnImport, lp)

        // --- Motor (solo modo canción completa) ---
        engineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        engineBox.addView(TextView(this).apply { text = "Motor de audio"; typeface = Typeface.DEFAULT_BOLD })
        radioRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Rubber Band R3 (más calidad, más lento)"
        }
        radioKempo = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Kempo (más rápido)"
        }
        val engineGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(radioRb)
            addView(radioKempo)
            check(radioRb.id)
        }
        engineBox.addView(engineGroup)
        root.addView(engineBox, lp)

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

        btnCancel = Button(this).apply {
            text = "Cancelar"
            setOnClickListener {
                cancelFlag.set(true)
                txtStatus.text = "Cancelando…"
            }
        }
        root.addView(btnCancel, lp)

        txtStatus = TextView(this).apply { textSize = 14f }
        root.addView(txtStatus, lp)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun stemsMode() = radioStems.isChecked

    private fun updateModeUi() {
        val sm = stemsMode()
        val needModel = sm && !StemSeparator.hasModel(this)
        txtStemNote.visibility = if (sm) View.VISIBLE else View.GONE
        checkDrums.visibility = if (sm) View.VISIBLE else View.GONE
        engineBox.visibility = if (sm) View.GONE else View.VISIBLE
        btnModel.visibility = if (needModel) View.VISIBLE else View.GONE
        btnImport.visibility = if (needModel) View.VISIBLE else View.GONE
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
        btnModel.isEnabled = !busy
        btnImport.isEnabled = !busy
        btnPause.isEnabled = player.isActive
        btnStop.isEnabled = player.isActive
        seekPos.isEnabled = player.isActive
        btnPause.text = if (player.isPaused) "Reanudar" else "Pausa"
        btnCancel.visibility = if (busy && cancellable) View.VISIBLE else View.GONE
    }

    private fun setBusy(value: Boolean, status: String, canCancel: Boolean = false) {
        busy = value
        cancellable = value && canCancel
        txtStatus.text = status
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshButtons()
    }

    private fun fmtTime(ms: Int): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun eta(startMs: Long, p: Float): String {
        if (p < 0.03f) return ""
        val elapsed = SystemClock.elapsedRealtime() - startMs
        val remainingSec = (elapsed * (1f - p) / p / 1000f).toInt()
        return " · faltan ~${fmtTime(remainingSec * 1000)}"
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

    private fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_MODEL)
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
            REQ_MODEL -> importModel(uri)
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
        rendered = null
        stems = null
        stemsDir().deleteRecursively()
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
        process { r ->
            setBusy(true, "Guardando…")
            Thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { raw ->
                        BufferedOutputStream(raw, 1 shl 16).use { out ->
                            WavWriter.write(out, r.pcm, r.sampleRate, r.channels)
                        }
                    }
                    ui.post { setBusy(false, "Guardado correctamente") }
                } catch (e: Throwable) {
                    ui.post { setBusy(false, "Error al guardar: ${e.message ?: e.javaClass.simpleName}") }
                }
            }.start()
        }
    }

    // ---------- Modelo de separación ----------

    private fun downloadModel() {
        setBusy(true, "Descargando modelo… 0%")
        Thread {
            try {
                StemSeparator.downloadModel(this) { p ->
                    ui.post { txtStatus.text = "Descargando modelo… ${(p * 100).toInt()}%" }
                }
                ui.post {
                    setBusy(false, "Modelo listo. Ya puedes aplicar con separación de pistas.")
                    updateModeUi()
                }
            } catch (e: Throwable) {
                ui.post {
                    setBusy(false, "No se pudo descargar: ${e.message ?: e.javaClass.simpleName}. Puedes importarlo desde un archivo.")
                    updateModeUi()
                }
            }
        }.start()
    }

    private fun importModel(uri: Uri) {
        setBusy(true, "Importando modelo…")
        Thread {
            try {
                StemSeparator.importModel(this, uri)
                ui.post {
                    setBusy(false, "Modelo listo.")
                    updateModeUi()
                }
            } catch (e: Throwable) {
                ui.post {
                    setBusy(false, "Error al importar: ${e.message ?: e.javaClass.simpleName}")
                    updateModeUi()
                }
            }
        }.start()
    }

    // ---------- Procesamiento ----------

    /** Procesa con los ajustes actuales (o reutiliza el resultado en caché) y entrega el audio. */
    private fun process(then: (Rendered) -> Unit) {
        val a = audio ?: return
        if (stemsMode()) processStems(a, then) else processWhole(a, then)
    }

    private fun processWhole(a: DecodedAudio, then: (Rendered) -> Unit) {
        val st = semitones()
        val fm = checkFormant.isChecked
        val eng = engine()
        val key = "whole|$st|$fm|$eng"

        val cached = rendered
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
                val r = Rendered(out, a.sampleRate, a.channels)
                ui.post {
                    cacheKey = key
                    rendered = r
                    setBusy(false, "Listo")
                    then(r)
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al procesar: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun processStems(a: DecodedAudio, then: (Rendered) -> Unit) {
        if (!StemSeparator.hasModel(this)) {
            txtStatus.text = "Falta el modelo de separación. Usa el botón Descargar modelo."
            updateModeUi()
            return
        }

        val st = semitones()
        val fm = checkFormant.isChecked
        val drums = checkDrums.isChecked
        val key = "stems|$st|$fm|$drums"

        val cached = rendered
        if (key == cacheKey && cached != null) {
            then(cached)
            return
        }

        cancelFlag.set(false)
        setBusy(true, "Preparando…", canCancel = true)
        val t0 = SystemClock.elapsedRealtime()
        val ctx = this

        Thread {
            try {
                val sep: SeparatedStems = stems ?: run {
                    ui.post { txtStatus.text = "Cargando modelo de separación…" }
                    StemSeparator.separate(ctx, a, stemsDir(), cancelFlag) { p ->
                        ui.post { txtStatus.text = "Separando pistas… ${(p * 100).toInt()}%${eta(t0, p)}" }
                    }.also { s -> ui.post { stems = s } }
                }

                ui.post { txtStatus.text = "Procesando pistas con Rubber Band…" }
                val pcm = StemMixer.render(sep, stemsDir(), st, fm, drums, cancelFlag) { p ->
                    ui.post { txtStatus.text = "Procesando pistas… ${(p * 100).toInt()}%" }
                }
                val r = Rendered(pcm, StemSeparator.SAMPLE_RATE, 2)
                ui.post {
                    cacheKey = key
                    rendered = r
                    setBusy(false, "Listo")
                    then(r)
                }
            } catch (e: CancelledException) {
                ui.post { setBusy(false, "Cancelado") }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun applyAndPlay() {
        process { r ->
            val startMs = if (player.isActive) player.positionMs() else 0L
            player.start(r.pcm, r.sampleRate, r.channels, startMs)
            refreshButtons()
        }
    }
}
