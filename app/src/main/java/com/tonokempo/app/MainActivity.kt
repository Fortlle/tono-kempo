package com.tonokempo.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.min
import com.google.android.material.R as MR

/** Audio ya procesado, listo para reproducir o guardar. */
class Rendered(val pcm: ByteArray, val sampleRate: Int, val channels: Int, val rangeKey: String = "")

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_OPEN = 1
        const val REQ_SAVE = 2
        const val REQ_MODEL = 3
        const val TIME_EMPTY = -1L
        const val TIME_INVALID = -2L
        const val PREVIEW_MS = 15_000L
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: PcmPlayer

    // Estado de la canción
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

    // Estado de los controles
    private var stemsMode = true
    private var engineKempo = false
    private var clickFixed = false
    private var barChoice = 0      // 0 = automático, si no: tiempos por compás
    private var accentShift = 0    // cuántos golpes se mueve el acento

    // Vistas
    private lateinit var btnOpen: MaterialButton
    private lateinit var txtFile: TextView
    private lateinit var txtTone: TextView
    private lateinit var sliderTone: Slider
    private lateinit var switchPreserve: MaterialSwitch
    private lateinit var switchDrums: MaterialSwitch
    private lateinit var modelBanner: LinearLayout
    private lateinit var btnModel: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var volSliders: List<Slider>
    private lateinit var editStart: TextInputEditText
    private lateinit var editEnd: TextInputEditText
    private lateinit var switchClick: MaterialSwitch
    private lateinit var txtTempo: TextView
    private lateinit var clickPanel: LinearLayout
    private lateinit var bpmRow: LinearLayout
    private lateinit var editBpm: TextInputEditText
    private lateinit var sliderClick: Slider
    private lateinit var txtShift: TextView
    private lateinit var btnPreview: MaterialButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var sliderPos: Slider
    private lateinit var txtPos: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var txtStatus: TextView

    private val stemsOnly = ArrayList<View>()
    private val wholeOnly = ArrayList<View>()

    private val ticker = object : Runnable {
        override fun run() {
            if (player.isActive && !dragging) {
                val dur = player.durationMs.toInt()
                val pos = player.positionMs().toInt().coerceIn(0, dur)
                setPosRange(dur, pos)
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
                setPosRange(1, 0)
                txtPos.text = "0:00 / ${fmtTime(audio?.durationMs?.toInt() ?: 0)}"
                refreshButtons()
            }
        })
        setContentView(buildUi())
        updateToneLabel()
        updateModeUi()
        updateClickPanel()
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

    // =====================================================================
    // Interfaz
    // =====================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 8) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun themeColor(view: View, attr: Int): Int = MaterialColors.getColor(view, attr)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun text(str: String, appearance: Int = MR.style.TextAppearance_Material3_BodyMedium): TextView =
        TextView(this).apply {
            text = str
            setTextAppearance(appearance)
        }

    private fun muted(str: String): TextView = text(str, MR.style.TextAppearance_Material3_BodySmall).also {
        it.setTextColor(themeColor(it, MR.attr.colorOnSurfaceVariant))
    }

    private fun card(title: String?, body: LinearLayout.() -> Unit): MaterialCardView {
        val card = MaterialCardView(this, null, MR.attr.materialCardViewOutlinedStyle).apply {
            radius = dp(16).toFloat()
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        if (title != null) inner.addView(text(title, MR.style.TextAppearance_Material3_TitleMedium))
        inner.body()
        card.addView(inner)
        return card
    }

    private fun stateList(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled)
    )

    private fun tonal(b: MaterialButton): MaterialButton {
        val bg = themeColor(b, MR.attr.colorSecondaryContainer)
        val fg = themeColor(b, MR.attr.colorOnSecondaryContainer)
        val off = themeColor(b, MR.attr.colorOnSurface)
        b.backgroundTintList = stateList(bg, withAlpha(off, 31))
        b.setTextColor(stateList(fg, withAlpha(off, 97)))
        b.iconTint = stateList(fg, withAlpha(off, 97))
        return b
    }

    private fun filled(label: String, icon: Int? = null, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            if (icon != null) setIconResource(icon)
            setOnClickListener { onClick() }
        }

    private fun tonalButton(label: String, icon: Int? = null, onClick: () -> Unit): MaterialButton =
        tonal(filled(label, icon, onClick))

    private fun outlinedButton(label: String, icon: Int? = null, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = label
            if (icon != null) setIconResource(icon)
            setOnClickListener { onClick() }
        }

    private fun iconOnly(icon: Int, desc: String, onClick: () -> Unit): MaterialButton =
        tonal(MaterialButton(this).apply {
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            contentDescription = desc
            minimumWidth = dp(48)
            setOnClickListener { onClick() }
        })

    /** Selector segmentado de una sola opción. */
    private fun toggle(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): MaterialButtonToggleGroup {
        val g = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = labels.map { View.generateViewId() }
        labels.forEachIndexed { i, label ->
            g.addView(
                MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
                    id = ids[i]
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
        }
        g.check(ids[selected])
        g.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) onSelect(ids.indexOf(checkedId))
        }
        return g
    }

    private fun slider(from: Float, to: Float, step: Float, value: Float, format: (Float) -> String): Slider =
        Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            this.value = value
            isTickVisible = false
            setLabelFormatter(LabelFormatter { v -> format(v) })
        }

    private fun timeField(hint: String, placeholder: String): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            placeholderText = placeholder
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        layout.addView(edit)
        return layout to edit
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        root.addView(text("Tono Kempo", MR.style.TextAppearance_Material3_HeadlineMedium))
        root.addView(muted("Cambia el tono de una canción sin cambiar su velocidad"))

        // ---- Canción ----
        root.addView(card("Canción") {
            btnOpen = filled("Elegir audio") { pickFile() }
            addView(btnOpen, lp(10))
            txtFile = muted("Ningún archivo cargado")
            addView(txtFile, lp(10))
        }, lp(16))

        // ---- Tono ----
        root.addView(card("Tono") {
            txtTone = text("", MR.style.TextAppearance_Material3_DisplaySmall)
            txtTone.gravity = Gravity.CENTER_HORIZONTAL
            addView(txtTone, lp(4))
            addView(muted("semitonos").also { it.gravity = Gravity.CENTER_HORIZONTAL })

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            sliderTone = slider(-12f, 12f, 0.5f, 0f) { String.format(Locale.US, "%+.1f", it) }
            sliderTone.addOnChangeListener { _, _, _ -> updateToneLabel() }
            row.addView(iconOnly(R.drawable.ic_remove, "Bajar un semitono") {
                sliderTone.value = (sliderTone.value - 1f).coerceIn(-12f, 12f)
            })
            row.addView(sliderTone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(iconOnly(R.drawable.ic_add, "Subir un semitono") {
                sliderTone.value = (sliderTone.value + 1f).coerceIn(-12f, 12f)
            })
            addView(row, lp(8))
        }, lp(12))

        // ---- Calidad ----
        root.addView(card("Calidad") {
            addView(toggle(listOf("Pistas (mejor)", "Completa (rápida)"), 0) { idx ->
                stemsMode = idx == 0
                updateModeUi()
                refreshButtons()
            }, lp(10))

            val noteStems = muted(
                "Separa voz, batería, bajo y otros, transpone cada pista por separado y las vuelve a unir. " +
                    "Tarda más, pero suena mucho mejor en canciones completas."
            )
            addView(noteStems, lp(8))
            stemsOnly.add(noteStems)

            val noteWhole = muted("Procesa la canción entera de una vez. Rápido, pero con mezclas complejas puede sonar peor.")
            addView(noteWhole, lp(8))
            wholeOnly.add(noteWhole)

            val engineToggle = toggle(listOf("Rubber Band", "Kempo"), 0) { idx -> engineKempo = idx == 1 }
            addView(engineToggle, lp(8))
            wholeOnly.add(engineToggle)

            switchPreserve = MaterialSwitch(this@MainActivity).apply {
                text = "Conservar el timbre natural"
                isChecked = true
            }
            addView(switchPreserve, lp(12))
            addView(muted("Recomendado. Apagado, la voz suena más grave o más aguda según el tono, como en una cinta acelerada."))

            switchDrums = MaterialSwitch(this@MainActivity).apply { text = "Transponer también la batería" }
            addView(switchDrums, lp(12))
            stemsOnly.add(switchDrums)

            modelBanner = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            modelBanner.addView(
                text("Falta el modelo de separación (316 MB, se descarga una sola vez).", MR.style.TextAppearance_Material3_BodyMedium),
                lp(12)
            )
            btnModel = filled("Descargar modelo") { downloadModel() }
            modelBanner.addView(btnModel, lp(8))
            btnImport = outlinedButton("Importar desde un archivo .onnx") { pickModelFile() }
            modelBanner.addView(btnImport, lp(4))
            addView(modelBanner)
        }, lp(12))

        // ---- Mezcla ----
        root.addView(card("Mezcla") {
            addView(muted("Se aplica al escuchar y al guardar."), lp(2))
            volSliders = listOf("Voz", "Batería", "Bajo", "Otros").map { name ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = text(name, MR.style.TextAppearance_Material3_BodyMedium)
                row.addView(label, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
                val s = slider(0f, 200f, 5f, 100f) { "${it.toInt()}%" }
                row.addView(s, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(row, lp(2))
                s
            }
        }.also { stemsOnly.add(it) }, lp(12))

        // ---- Fragmento ----
        root.addView(card("Fragmento") {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val (startLayout, startEdit) = timeField("Inicio", "0:00")
            val (endLayout, endEdit) = timeField("Fin", "final")
            editStart = startEdit
            editEnd = endEdit
            val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(startLayout, LinearLayout.LayoutParams(half).apply { marginEnd = dp(8) })
            row.addView(endLayout, LinearLayout.LayoutParams(half).apply { marginStart = dp(8) })
            addView(row, lp(10))
            addView(muted("Escribe los tiempos como minutos:segundos, por ejemplo 1:20 y 3:04. Vacío = toda la canción."), lp(6))
        }.also { stemsOnly.add(it) }, lp(12))

        // ---- Metrónomo ----
        root.addView(card("Metrónomo") {
            switchClick = MaterialSwitch(this@MainActivity).apply { text = "Agregar metrónomo" }
            switchClick.setOnCheckedChangeListener { _, checked ->
                if (checked && beatAnalysis == null) detectTempo()
                updateClickPanel()
            }
            addView(switchClick, lp(8))
            txtTempo = muted("Al activarlo se detecta el tempo de la canción.")
            addView(txtTempo, lp(2))

            clickPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

            clickPanel.addView(text("Cómo suena", MR.style.TextAppearance_Material3_LabelLarge), lp(14))
            clickPanel.addView(toggle(listOf("Sigue la canción", "Tempo fijo"), 0) { idx ->
                clickFixed = idx == 1
                bpmRow.visibility = if (clickFixed) View.VISIBLE else View.GONE
            }, lp(6))
            clickPanel.addView(
                muted("«Sigue la canción» marca cada golpe detectado y acompaña si la música acelera o frena. «Tempo fijo» marca un pulso perfectamente parejo, útil para canciones grabadas con metrónomo."),
                lp(6)
            )

            bpmRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            val bpmLayout = TextInputLayout(this@MainActivity, null, MR.attr.textInputOutlinedStyle).apply { hint = "BPM" }
            editBpm = TextInputEditText(bpmLayout.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            bpmLayout.addView(editBpm)
            bpmRow.addView(tonalButton("½×") { scaleBpm(0.5) })
            bpmRow.addView(iconOnly(R.drawable.ic_remove, "Bajar un BPM") { addBpm(-1.0) })
            bpmRow.addView(bpmLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bpmRow.addView(iconOnly(R.drawable.ic_add, "Subir un BPM") { addBpm(1.0) })
            bpmRow.addView(tonalButton("2×") { scaleBpm(2.0) })
            clickPanel.addView(bpmRow, lp(8))

            clickPanel.addView(text("Compás", MR.style.TextAppearance_Material3_LabelLarge), lp(14))
            clickPanel.addView(toggle(listOf("Auto", "2", "3", "4", "6"), 0) { idx ->
                barChoice = intArrayOf(0, 2, 3, 4, 6)[idx]
                redecodeBeats()
            }, lp(6))

            clickPanel.addView(text("Si el acento (el clic más agudo) cae en el golpe equivocado, muévelo", MR.style.TextAppearance_Material3_LabelLarge), lp(14))
            val shiftRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            txtShift = text("0", MR.style.TextAppearance_Material3_TitleMedium)
            txtShift.gravity = Gravity.CENTER
            shiftRow.addView(iconOnly(R.drawable.ic_chevron_left, "Mover el acento antes") { shiftAccent(-1) })
            shiftRow.addView(txtShift, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            shiftRow.addView(iconOnly(R.drawable.ic_chevron_right, "Mover el acento después") { shiftAccent(1) })
            clickPanel.addView(shiftRow, lp(6))

            clickPanel.addView(text("Volumen del metrónomo", MR.style.TextAppearance_Material3_LabelLarge), lp(14))
            sliderClick = slider(0f, 100f, 5f, 60f) { "${it.toInt()}%" }
            clickPanel.addView(sliderClick)

            btnPreview = tonalButton("Escuchar 15 segundos") { applyAndPlay(PREVIEW_MS) }
            clickPanel.addView(btnPreview, lp(10))

            addView(clickPanel)
        }.also { stemsOnly.add(it) }, lp(12))

        // ---- Reproducir y guardar ----
        root.addView(card("Escuchar y guardar") {
            btnPlay = filled("Aplicar y reproducir", R.drawable.ic_play) { applyAndPlay(null) }
            addView(btnPlay, lp(10))

            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            btnPause = tonalButton("Pausa", R.drawable.ic_pause) {
                if (player.isPaused) player.resume() else player.pause()
                refreshButtons()
            }
            btnStop = tonalButton("Detener", R.drawable.ic_stop) {
                player.stop()
                setPosRange(1, 0)
                txtPos.text = "0:00 / ${fmtTime(audio?.durationMs?.toInt() ?: 0)}"
                refreshButtons()
            }
            row.addView(btnPause, LinearLayout.LayoutParams(half).apply { marginEnd = dp(6) })
            row.addView(btnStop, LinearLayout.LayoutParams(half).apply { marginStart = dp(6) })
            addView(row, lp(8))

            sliderPos = Slider(this@MainActivity).apply {
                valueFrom = 0f
                valueTo = 1f
                value = 0f
                isEnabled = false
                setLabelFormatter(LabelFormatter { v -> fmtTime(v.toInt()) })
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) { dragging = true }
                    override fun onStopTrackingTouch(slider: Slider) {
                        dragging = false
                        player.seekToMs(slider.value.toLong())
                    }
                })
                addOnChangeListener { s, v, fromUser ->
                    if (fromUser) txtPos.text = "${fmtTime(v.toInt())} / ${fmtTime(s.valueTo.toInt())}"
                }
            }
            addView(sliderPos, lp(4))
            txtPos = muted("0:00 / 0:00")
            txtPos.gravity = Gravity.END
            addView(txtPos)

            btnSave = outlinedButton("Guardar como WAV") { pickSaveLocation() }
            addView(btnSave, lp(10))

            progress = LinearProgressIndicator(this@MainActivity).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            addView(progress, lp(14))
            txtStatus = muted("")
            addView(txtStatus, lp(6))
            btnCancel = outlinedButton("Cancelar") {
                cancelFlag.set(true)
                setStatus("Cancelando…")
            }
            addView(btnCancel, lp(6))
        }, lp(12))

        return ScrollView(this).apply { addView(root) }
    }

    // =====================================================================
    // Estado de la interfaz
    // =====================================================================

    private fun semitones(): Float = sliderTone.value

    private fun updateToneLabel() {
        txtTone.text = String.format(Locale.getDefault(), "%+.1f", semitones())
    }

    private fun updateModeUi() {
        val needModel = stemsMode && !StemSeparator.hasModel(this)
        stemsOnly.forEach { it.visibility = if (stemsMode) View.VISIBLE else View.GONE }
        wholeOnly.forEach { it.visibility = if (stemsMode) View.GONE else View.VISIBLE }
        modelBanner.visibility = if (needModel) View.VISIBLE else View.GONE
    }

    private fun updateClickPanel() {
        val ready = beatAnalysis?.beats?.isNotEmpty() == true
        clickPanel.visibility = if (switchClick.isChecked && ready) View.VISIBLE else View.GONE
        btnPreview.visibility = if (switchClick.isChecked && ready) View.VISIBLE else View.GONE
    }

    private fun refreshButtons() {
        val loaded = audio != null
        btnOpen.isEnabled = !busy
        btnPlay.isEnabled = loaded && !busy
        btnPreview.isEnabled = loaded && !busy
        btnSave.isEnabled = loaded && !busy
        btnModel.isEnabled = !busy
        btnImport.isEnabled = !busy
        switchClick.isEnabled = loaded && !busy
        btnPause.isEnabled = player.isActive
        btnStop.isEnabled = player.isActive
        sliderPos.isEnabled = player.isActive
        btnPause.text = if (player.isPaused) "Reanudar" else "Pausa"
        btnPause.setIconResource(if (player.isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        btnCancel.visibility = if (busy && cancellable) View.VISIBLE else View.GONE
    }

    private fun setBusy(value: Boolean, status: String, canCancel: Boolean = false) {
        busy = value
        cancellable = value && canCancel
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (value) progress.isIndeterminate = true
        txtStatus.text = status
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshButtons()
    }

    /** Actualiza el texto de estado y, si se conoce el avance (0..1), la barra de progreso. */
    private fun setStatus(status: String, p: Float? = null) {
        txtStatus.text = status
        if (busy) {
            if (p == null) {
                progress.isIndeterminate = true
            } else {
                progress.isIndeterminate = false
                progress.setProgressCompat((p * 100).toInt().coerceIn(0, 100), true)
            }
        }
    }

    private fun setPosRange(maxMs: Int, posMs: Int) {
        val newMax = maxOf(1, maxMs).toFloat()
        val pos = posMs.coerceIn(0, maxOf(1, maxMs)).toFloat()
        if (newMax < sliderPos.value) sliderPos.value = 0f
        sliderPos.valueTo = newMax
        sliderPos.value = pos
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

    /** "1:20", "01:20", "1:02:03" o "80" (segundos). TIME_EMPTY si está vacío y TIME_INVALID si no se entiende. */
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

    private fun currentBpm(): Double? =
        editBpm.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun setBpm(v: Double) {
        editBpm.setText(String.format(Locale.US, "%.1f", v.coerceIn(30.0, 300.0)))
    }

    private fun scaleBpm(f: Double) {
        currentBpm()?.let { setBpm(it * f) }
    }

    private fun addBpm(d: Double) {
        currentBpm()?.let { setBpm(it + d) }
    }

    private fun shiftAccent(d: Int) {
        accentShift += d
        txtShift.text = if (accentShift > 0) "+$accentShift" else accentShift.toString()
    }

    // =====================================================================
    // Archivos
    // =====================================================================

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
        accentShift = 0
        txtShift.text = "0"
        txtTempo.text = "Al activarlo se detecta el tempo de la canción."
        switchClick.isChecked = false
        updateClickPanel()
        stemsDir().deleteRecursively()
        val name = displayName(uri)
        setBusy(true, "Decodificando…")
        Thread {
            try {
                val decoded = AudioDecoder.decode(this, uri) { p ->
                    ui.post { setStatus("Decodificando… ${(p * 100).toInt()}%", p) }
                }
                if (decoded.channels > 2) {
                    throw IllegalArgumentException("Solo se admiten audios mono o estéreo")
                }
                ui.post {
                    audio = decoded
                    fileName = name.substringBeforeLast('.')
                    txtFile.text = "$name\n${decoded.sampleRate} Hz · ${if (decoded.channels == 1) "mono" else "estéreo"} · ${fmtTime(decoded.durationMs.toInt())}"
                    setPosRange(1, 0)
                    txtPos.text = "0:00 / ${fmtTime(decoded.durationMs.toInt())}"
                    setBusy(false, "Listo. Elige el tono y pulsa Aplicar y reproducir.")
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al abrir: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun saveTo(uri: Uri) {
        process(null) { r ->
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

    // =====================================================================
    // Modelo de separación
    // =====================================================================

    private fun downloadModel() {
        setBusy(true, "Descargando modelo… 0%")
        Thread {
            try {
                StemSeparator.downloadModel(this) { p ->
                    ui.post { setStatus("Descargando modelo… ${(p * 100).toInt()}%", p) }
                }
                ui.post {
                    setBusy(false, "Modelo listo. Ya puedes usar la separación de pistas.")
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

    // =====================================================================
    // Tempo y metrónomo
    // =====================================================================

    private fun detectTempo() {
        val a = audio
        if (a == null) {
            switchClick.isChecked = false
            setStatus("Primero elige un audio.")
            return
        }
        if (busy) return
        cancelFlag.set(false)
        setBusy(true, "Preparando…", canCancel = true)
        val ctx = this
        Thread {
            try {
                if (!BeatTracker.hasModel(ctx)) {
                    ui.post { setStatus("Descargando modelo de tempo… 0%", 0f) }
                    BeatTracker.downloadModel(ctx) { p ->
                        ui.post { setStatus("Descargando modelo de tempo… ${(p * 100).toInt()}%", p) }
                    }
                }
                val an = BeatTracker.analyze(ctx, a, meters(), cancelFlag) { p ->
                    ui.post { setStatus("Detectando tempo… ${(p * 100).toInt()}%", p) }
                }
                ui.post {
                    beatAnalysis = an
                    if (an.beats.isEmpty() || an.bpm <= 0.0) {
                        txtTempo.text = "No se detectaron golpes claros en esta canción."
                        switchClick.isChecked = false
                        setBusy(false, "Sin resultado de tempo")
                    } else {
                        txtTempo.text = tempoText(an)
                        setBpm(an.bpm)
                        setBusy(false, "Tempo detectado")
                    }
                    updateClickPanel()
                }
            } catch (e: CancelledException) {
                ui.post {
                    switchClick.isChecked = false
                    setBusy(false, "Cancelado")
                    updateClickPanel()
                }
            } catch (e: Throwable) {
                ui.post {
                    switchClick.isChecked = false
                    setBusy(false, "Error al detectar tempo: ${e.message ?: e.javaClass.simpleName}")
                    updateClickPanel()
                }
            }
        }.start()
    }

    private fun meters(): IntArray = if (barChoice == 0) intArrayOf(3, 4) else intArrayOf(barChoice)

    private fun tempoText(an: BeatAnalysis): String = String.format(
        Locale.getDefault(),
        "Tempo detectado: %.1f BPM, compás de %d tiempos",
        an.bpm, an.meter
    )

    /** Vuelve a calcular compases con los tiempos por compás elegidos (sin repetir el modelo). */
    private fun redecodeBeats() {
        val an = beatAnalysis ?: return
        if (busy) return
        setBusy(true, "Recalculando compás…")
        Thread {
            try {
                val re = BeatTracker.decode(an.beatProb, an.downProb, meters())
                ui.post {
                    beatAnalysis = re
                    accentShift = 0
                    txtShift.text = "0"
                    txtTempo.text = tempoText(re)
                    setBusy(false, "Compás actualizado")
                    updateClickPanel()
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al recalcular: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun buildClick(a: DecodedAudio): ClickSpec? {
        if (!switchClick.isChecked) return null
        val an = beatAnalysis ?: return null
        val n = an.beats.size
        if (n == 0) return null
        val gain = sliderClick.value / 100f
        val meter = an.meter

        val bpm = currentBpm()
        if (clickFixed && bpm != null && bpm in 30.0..300.0) {
            // Pulso parejo, con la fase del primer "primer tiempo" detectado
            val step = 60.0 / bpm
            val firstDown = an.beatInBar.indexOfFirst { it == 1 }.let { if (it < 0) 0 else it }
            val t0 = an.beats[firstDown]
            val duration = a.durationMs / 1000.0
            val times = ArrayList<Double>()
            val accent = ArrayList<Boolean>()
            var k = -floor(t0 / step).toInt()
            while (true) {
                val t = t0 + k * step
                if (t >= duration) break
                if (t >= 0.0) {
                    times.add(t)
                    accent.add(Math.floorMod(k - accentShift, meter) == 0)
                }
                k++
            }
            return ClickSpec(times.toDoubleArray(), accent.toBooleanArray(), gain)
        }

        // Sigue los golpes detectados; el acento va en el golpe 1 de cada compás (movible con ◀ ▶)
        val accent = BooleanArray(n) { i -> Math.floorMod(an.beatInBar[i] - 1 - accentShift, meter) == 0 }
        return ClickSpec(an.beats, accent, gain)
    }

    private fun buildMixSettings(a: DecodedAudio, previewMs: Long?): MixSettings? {
        val s = parseTimeMs(editStart.text.toString())
        val e = parseTimeMs(editEnd.text.toString())
        if (s == TIME_INVALID || e == TIME_INVALID) {
            setStatus("Tiempo no válido. Usa minutos:segundos, por ejemplo 1:20.")
            return null
        }
        val startMs = if (s == TIME_EMPTY) 0L else s
        var endMs = if (e == TIME_EMPTY) -1L else e
        if (endMs in 0..startMs) {
            setStatus("El fin debe ser mayor que el inicio.")
            return null
        }
        if (startMs >= a.durationMs) {
            setStatus("El inicio está después del final de la canción.")
            return null
        }
        if (previewMs != null) {
            val cap = startMs + previewMs
            endMs = if (endMs < 0) cap else min(endMs, cap)
        }
        // Orden interno: batería, bajo, otros, voz. En pantalla: voz, batería, bajo, otros.
        val v = volSliders.map { it.value / 100f }
        val gains = floatArrayOf(v[1], v[2], v[3], v[0])
        return MixSettings(gains, startMs, endMs, buildClick(a))
    }

    // =====================================================================
    // Procesamiento
    // =====================================================================

    private fun process(previewMs: Long?, then: (Rendered) -> Unit) {
        val a = audio ?: return
        if (stemsMode) processStems(a, previewMs, then) else processWhole(a, then)
    }

    private fun processWhole(a: DecodedAudio, then: (Rendered) -> Unit) {
        val st = semitones()
        val moveFormants = !switchPreserve.isChecked
        val eng = if (engineKempo) Engine.KEMPO else Engine.RUBBERBAND
        val key = "whole|$st|$moveFormants|$eng"

        val cached = rendered
        if (key == cacheKey && cached != null) {
            then(cached)
            return
        }

        val engineName = if (eng == Engine.RUBBERBAND) "Rubber Band" else "Kempo"
        setBusy(true, "Procesando con $engineName…")
        Thread {
            try {
                val out = PitchProcessor.shift(a, st, moveFormants, eng) { p ->
                    ui.post { setStatus("Procesando con $engineName… ${(p * 100).toInt()}%", p) }
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

    private fun processStems(a: DecodedAudio, previewMs: Long?, then: (Rendered) -> Unit) {
        if (!StemSeparator.hasModel(this)) {
            setStatus("Falta el modelo de separación. Usa el botón Descargar modelo.")
            updateModeUi()
            return
        }
        val settings = buildMixSettings(a, previewMs) ?: return
        val rangeKey = "${editStart.text.toString().trim()}|${editEnd.text.toString().trim()}|${previewMs ?: 0}"

        val st = semitones()
        val moveFormants = !switchPreserve.isChecked
        val drums = switchDrums.isChecked
        val key = "$st|$moveFormants|$drums"

        cancelFlag.set(false)
        setBusy(true, "Preparando…", canCancel = true)
        val t0 = SystemClock.elapsedRealtime()
        val ctx = this

        Thread {
            try {
                // 1) Separar (solo una vez por canción)
                val sep: SeparatedStems = stems ?: run {
                    ui.post { setStatus("Cargando modelo de separación…") }
                    StemSeparator.separate(ctx, a, stemsDir(), cancelFlag) { p ->
                        ui.post { setStatus("Separando pistas… ${(p * 100).toInt()}%${eta(t0, p)}", p) }
                    }.also { s -> stems = s }
                }

                // 2) Transponer cada pista (solo si cambió el tono, el timbre o la batería)
                val cachedFiles = procFiles
                val ready: List<File> = if (procKey == key && cachedFiles != null) {
                    cachedFiles
                } else {
                    ui.post { setStatus("Procesando pistas con Rubber Band…", 0f) }
                    StemMixer.prepare(sep, stemsDir(), st, moveFormants, drums, cancelFlag) { p ->
                        ui.post { setStatus("Procesando pistas… ${(p * 100).toInt()}%", p) }
                    }.also { files ->
                        procFiles = files
                        procKey = key
                    }
                }

                // 3) Mezclar con volúmenes, fragmento y metrónomo (rápido)
                ui.post { setStatus("Mezclando…") }
                val pcm = StemMixer.mix(ready, settings)
                val r = Rendered(pcm, StemSeparator.SAMPLE_RATE, 2, rangeKey)
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

    private fun applyAndPlay(previewMs: Long?) {
        process(previewMs) { r ->
            // Si ya sonaba el mismo fragmento, continúa donde iba; si cambió, empieza desde su inicio
            val startMs = if (player.isActive && r.rangeKey == playingRangeKey) player.positionMs() else 0L
            playingRangeKey = r.rangeKey
            player.start(r.pcm, r.sampleRate, r.channels, startMs)
            refreshButtons()
        }
    }
}
