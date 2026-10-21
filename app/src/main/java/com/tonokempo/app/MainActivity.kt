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
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
import kotlin.math.floor

/** Audio ya procesado, listo para reproducir o guardar. */
class Rendered(val pcm: ByteArray, val sampleRate: Int, val channels: Int, val rangeKey: String = "")

class MainActivity : Activity() {

    private companion object {
        const val REQ_OPEN = 1
        const val REQ_SAVE = 2
        const val REQ_MODEL = 3
        const val TONE_CENTER = 24 // SeekBar 0..48 -> -12.0 .. +12.0 semitonos, pasos de 0.5
        const val TIME_EMPTY = -1L
        const val TIME_INVALID = -2L
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: PcmPlayer

    private var audio: DecodedAudio? = null
    private var fileName = "audio"
    private var cacheKey: String? = null
    private var rendered: Rendered? = null
    @Volatile private var stems: SeparatedStems? = null
    @Volatile private var procFiles: List<File>? = null
    @Volatile private var procKey: String? = null
    private var beatAnalysis: BeatAnalysis? = null
    private val cancelFlag = AtomicBoolean(false)
    private var busy = false
    private var cancellable = false
    private var dragging = false
    private var playingRangeKey = ""

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
    private lateinit var exportBox: LinearLayout
    private lateinit var volSeeks: List<SeekBar>
    private lateinit var editStart: EditText
    private lateinit var editEnd: EditText
    private lateinit var btnTempo: Button
    private lateinit var txtTempo: TextView
    private lateinit var checkClick: CheckBox
    private lateinit var seekClick: SeekBar
    private lateinit var editBpm: EditText
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

        // --- Mezcla y exportación (solo modo pistas) ---
        exportBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        exportBox.addView(
            TextView(this).apply { text = "Mezcla y exportación"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD },
            lp
        )
        exportBox.addView(
            TextView(this).apply {
                textSize = 13f
                text = "Estos ajustes se usan al reproducir y al guardar."
            }
        )

        // Orden en pantalla: Voz, Batería, Bajo, Otros
        volSeeks = listOf("Voz", "Batería", "Bajo", "Otros").map { name ->
            val label = TextView(this).apply { text = "$name 100%" }
            val sb = SeekBar(this).apply {
                max = 200
                progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        label.text = "$name $p%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            exportBox.addView(label, lp)
            exportBox.addView(sb)
            sb
        }

        exportBox.addView(
            TextView(this).apply { text = "Fragmento a reproducir y exportar"; typeface = Typeface.DEFAULT_BOLD },
            lp
        )
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val halfLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        editStart = EditText(this).apply {
            hint = "Inicio (ej. 1:20)"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        editEnd = EditText(this).apply {
            hint = "Fin (ej. 3:04)"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        rangeRow.addView(editStart, halfLp)
        rangeRow.addView(editEnd, halfLp)
        exportBox.addView(rangeRow)
        exportBox.addView(
            TextView(this).apply {
                textSize = 12f
                text = "Déjalos vacíos para usar la canción completa. El fin vacío significa hasta el final."
            }
        )

        exportBox.addView(
            TextView(this).apply { text = "Metrónomo (opcional)"; typeface = Typeface.DEFAULT_BOLD },
            lp
        )
        btnTempo = Button(this).apply {
            text = "Detectar tempo"
            setOnClickListener { detectTempo() }
        }
        exportBox.addView(btnTempo, lp)
        txtTempo = TextView(this).apply { text = "Tempo sin detectar"; textSize = 14f }
        exportBox.addView(txtTempo)
        checkClick = CheckBox(this).apply {
            text = "Agregar metrónomo con el tempo detectado"
            isEnabled = false
        }
        exportBox.addView(checkClick, lp)
        val clickLabel = TextView(this).apply { text = "Volumen del metrónomo 60%" }
        exportBox.addView(clickLabel)
        seekClick = SeekBar(this).apply {
            max = 100
            progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    clickLabel.text = "Volumen del metrónomo $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        exportBox.addView(seekClick)
        editBpm = EditText(this).apply {
            hint = "BPM fijo (opcional; vacío = seguir los golpes detectados)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        exportBox.addView(editBpm, lp)
        root.addView(exportBox, lp)

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
        exportBox.visibility = if (sm) View.VISIBLE else View.GONE
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
        btnTempo.isEnabled = loaded && !busy
        checkClick.isEnabled = beatAnalysis?.beats?.isNotEmpty() == true
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

    /** "1:20", "01:20", "1:02:03" o "80" (segundos). Devuelve TIME_EMPTY si está vacío y TIME_INVALID si no se entiende. */
    private fun parseTimeMs(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return TIME_EMPTY
        return try {
            var sec = 0.0
            for (part in t.split(":")) sec = sec * 60.0 + part.trim().replace(',', '.').toDouble()
            if (sec < 0) TIME_INVALID else (sec * 1000.0).toLong()
        } catch (_: NumberFormatException) {
            TIME_INVALID
        }
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
        procFiles = null
        procKey = null
        beatAnalysis = null
        txtTempo.text = "Tempo sin detectar"
        checkClick.isChecked = false
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

    // ---------- Tempo ----------

    private fun detectTempo() {
        val a = audio ?: return
        cancelFlag.set(false)
        setBusy(true, "Preparando…", canCancel = true)
        val ctx = this
        Thread {
            try {
                if (!BeatTracker.hasModel(ctx)) {
                    ui.post { txtStatus.text = "Descargando modelo de tempo… 0%" }
                    BeatTracker.downloadModel(ctx) { p ->
                        ui.post { txtStatus.text = "Descargando modelo de tempo… ${(p * 100).toInt()}%" }
                    }
                }
                val an = BeatTracker.analyze(ctx, a, cancelFlag) { p ->
                    ui.post { txtStatus.text = "Detectando tempo… ${(p * 100).toInt()}%" }
                }
                ui.post {
                    beatAnalysis = an
                    if (an.beats.isEmpty() || an.bpm <= 0.0) {
                        txtTempo.text = "No se detectaron golpes claros"
                        setBusy(false, "Sin resultado de tempo")
                    } else {
                        txtTempo.text = String.format(
                            Locale.getDefault(),
                            "Tempo: %.1f BPM · %d tiempos por compás · %d golpes",
                            an.bpm, an.beatsPerBar, an.beats.size
                        )
                        setBusy(false, "Tempo detectado")
                    }
                }
            } catch (e: CancelledException) {
                ui.post { setBusy(false, "Cancelado") }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al detectar tempo: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun buildClick(a: DecodedAudio): ClickSpec? {
        if (!checkClick.isChecked) return null
        val an = beatAnalysis ?: return null
        val gain = seekClick.progress / 100f
        val bpm = editBpm.text.toString().trim().replace(',', '.').toDoubleOrNull()

        if (bpm != null && bpm in 30.0..300.0 && an.beats.isNotEmpty()) {
            // Rejilla fija: mantiene la fase del primer golpe detectado
            val step = 60.0 / bpm
            val first = an.beats[0]
            val duration = a.durationMs / 1000.0
            val times = ArrayList<Double>()
            val accent = ArrayList<Boolean>()
            var k = -floor(first / step).toInt()
            while (true) {
                val t = first + k * step
                if (t >= duration) break
                if (t >= 0.0) {
                    times.add(t)
                    accent.add(Math.floorMod(k, an.beatsPerBar) == 0)
                }
                k++
            }
            return ClickSpec(times.toDoubleArray(), accent.toBooleanArray(), gain)
        }
        return ClickSpec(an.beats, an.isDown, gain)
    }

    private fun buildMixSettings(a: DecodedAudio): MixSettings? {
        val s = parseTimeMs(editStart.text.toString())
        val e = parseTimeMs(editEnd.text.toString())
        if (s == TIME_INVALID || e == TIME_INVALID) {
            txtStatus.text = "Tiempo no válido. Usa el formato m:ss, por ejemplo 1:20."
            return null
        }
        val startMs = if (s == TIME_EMPTY) 0L else s
        val endMs = if (e == TIME_EMPTY) -1L else e
        if (endMs in 0..startMs) {
            txtStatus.text = "El fin debe ser mayor que el inicio."
            return null
        }
        if (startMs >= a.durationMs) {
            txtStatus.text = "El inicio está después del final de la canción."
            return null
        }
        // Orden interno: batería, bajo, otros, voz. En pantalla: voz, batería, bajo, otros.
        val v = volSeeks.map { it.progress / 100f }
        val gains = floatArrayOf(v[1], v[2], v[3], v[0])
        return MixSettings(gains, startMs, endMs, buildClick(a))
    }

    // ---------- Procesamiento ----------

    /** Procesa con los ajustes actuales y entrega el audio. */
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
        val settings = buildMixSettings(a) ?: return
        val editStartText = editStart.text.toString().trim()
        val editEndText = editEnd.text.toString().trim()

        val st = semitones()
        val fm = checkFormant.isChecked
        val drums = checkDrums.isChecked
        val key = "$st|$fm|$drums"

        cancelFlag.set(false)
        setBusy(true, "Preparando…", canCancel = true)
        val t0 = SystemClock.elapsedRealtime()
        val ctx = this

        Thread {
            try {
                // 1) Separar (solo una vez por canción)
                val sep: SeparatedStems = stems ?: run {
                    ui.post { txtStatus.text = "Cargando modelo de separación…" }
                    StemSeparator.separate(ctx, a, stemsDir(), cancelFlag) { p ->
                        ui.post { txtStatus.text = "Separando pistas… ${(p * 100).toInt()}%${eta(t0, p)}" }
                    }.also { s -> stems = s }
                }

                // 2) Transponer cada pista (solo si cambiaron los semitonos u opciones de timbre/batería)
                val cachedFiles = procFiles
                val ready: List<File> = if (procKey == key && cachedFiles != null) {
                    cachedFiles
                } else {
                    ui.post { txtStatus.text = "Procesando pistas con Rubber Band…" }
                    StemMixer.prepare(sep, stemsDir(), st, fm, drums, cancelFlag) { p ->
                        ui.post { txtStatus.text = "Procesando pistas… ${(p * 100).toInt()}%" }
                    }.also { files ->
                        procFiles = files
                        procKey = key
                    }
                }

                // 3) Mezclar con volúmenes, fragmento y metrónomo (rápido)
                ui.post { txtStatus.text = "Mezclando…" }
                val pcm = StemMixer.mix(ready, settings)
                val r = Rendered(pcm, StemSeparator.SAMPLE_RATE, 2, "${editStartText}|${editEndText}")
                ui.post {
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
            // Si ya estaba sonando el mismo fragmento, continúa donde iba; si cambió, empieza desde su inicio
            val startMs = if (player.isActive && r.rangeKey == playingRangeKey) player.positionMs() else 0L
            playingRangeKey = r.rangeKey
            player.start(r.pcm, r.sampleRate, r.channels, startMs)
            refreshButtons()
        }
    }
}
