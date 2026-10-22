package com.tonokempo.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
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

/** Ajustes de salida ya expresados en el tiempo del audio resultante (después de cambiar la velocidad). */
class OutSettings(
    val gains: FloatArray,
    val master: Float,
    val startMs: Long,
    val endMs: Long,
    val click: ClickSpec?,
    val rangeKey: String
)

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_OPEN = 1
        const val REQ_SAVE = 2
        const val REQ_MODEL = 3
        const val TIME_EMPTY = -1L
        const val TIME_INVALID = -2L
        const val PREVIEW_MS = 15_000L
    }

    /** Sección que se puede plegar o desplegar. */
    private class Section(
        val container: LinearLayout,
        val content: LinearLayout,
        val arrow: ImageView,
        val summary: TextView?,
        val summaryFn: (() -> String)?
    ) {
        var expanded = false
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: PcmPlayer

    // Estado de la canción
    private var audio: DecodedAudio? = null
    private var fileName = "audio"
    private var baseKey: String? = null
    private var baseRendered: Rendered? = null
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
    private var useDbn = false
    private var clickFixed = true
    private var barChoice = 0      // 0 = automático, si no: tiempos por compás
    private var accentShift = 0    // cuántos golpes se mueve el acento

    // Vistas
    private lateinit var rootLayout: LinearLayout
    private lateinit var btnOpen: MaterialButton
    private lateinit var txtFile: TextView
    private lateinit var txtTone: TextView
    private lateinit var sliderTone: Slider
    private lateinit var switchPreserve: MaterialSwitch
    private lateinit var switchDrums: MaterialSwitch
    private lateinit var modelBanner: LinearLayout
    private lateinit var btnModel: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var sliderMaster: Slider
    private lateinit var volSliders: List<Slider>
    private lateinit var editStart: TextInputEditText
    private lateinit var editEnd: TextInputEditText
    private lateinit var btnTempo: MaterialButton
    private lateinit var txtTempo: TextView
    private lateinit var tempoPanel: LinearLayout
    private lateinit var editBpmOrig: TextInputEditText
    private lateinit var editBpmNew: TextInputEditText
    private lateinit var switchStretch: MaterialSwitch
    private lateinit var stretchPanel: LinearLayout
    private lateinit var txtRatio: TextView
    private lateinit var switchClick: MaterialSwitch
    private lateinit var clickPanel: LinearLayout
    private lateinit var sliderClick: Slider
    private lateinit var sliderPhase: Slider
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

    private lateinit var secQuality: Section
    private lateinit var secRange: Section
    private lateinit var secTempo: Section
    private val sections = ArrayList<Section>()

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
        updateTempoPanel()
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
    // Interfaz: bloques básicos
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

    private fun outlinedCard(): MaterialCardView =
        MaterialCardView(this, null, MR.attr.materialCardViewOutlinedStyle).apply {
            radius = dp(16).toFloat()
        }

    /** Tarjeta fija (siempre abierta). */
    private fun card(title: String?, body: LinearLayout.() -> Unit): MaterialCardView {
        val card = outlinedCard()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        if (title != null) inner.addView(text(title, MR.style.TextAppearance_Material3_TitleMedium))
        inner.body()
        card.addView(inner)
        return card
    }

    /**
     * Sección con encabezado que se toca para plegar o desplegar. Si hay [summaryFn], el encabezado
     * muestra ese resumen mientras la sección está plegada.
     */
    private fun section(
        title: String,
        expanded: Boolean,
        appearance: Int,
        summaryFn: (() -> String)?,
        body: LinearLayout.() -> Unit
    ): Section {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(6), 0, dp(6))
        }
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            header.setBackgroundResource(tv.resourceId)
        }

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(title, appearance))
        val summaryView = if (summaryFn != null) muted("") else null
        summaryView?.let { titles.addView(it) }
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            imageTintList = ColorStateList.valueOf(themeColor(this, MR.attr.colorOnSurfaceVariant))
            contentDescription = "Mostrar u ocultar"
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(24), dp(24)))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.body()

        container.addView(header)
        container.addView(content)

        val sec = Section(container, content, arrow, summaryView, summaryFn)
        sec.expanded = expanded
        applyExpanded(sec)
        header.setOnClickListener { setExpanded(sec, !sec.expanded, true) }
        sections.add(sec)
        return sec
    }

    private fun collapsibleCard(
        title: String,
        expanded: Boolean,
        summaryFn: (() -> String)?,
        body: LinearLayout.() -> Unit
    ): Pair<MaterialCardView, Section> {
        val card = outlinedCard()
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val sec = section(title, expanded, MR.style.TextAppearance_Material3_TitleMedium, summaryFn, body)
        pad.addView(sec.container)
        card.addView(pad)
        return card to sec
    }

    private fun applyExpanded(sec: Section) {
        sec.content.visibility = if (sec.expanded) View.VISIBLE else View.GONE
        sec.arrow.rotation = if (sec.expanded) 180f else 0f
        sec.summary?.visibility = if (sec.expanded) View.GONE else View.VISIBLE
    }

    private fun setExpanded(sec: Section, value: Boolean, animate: Boolean) {
        if (animate && ::rootLayout.isInitialized) TransitionManager.beginDelayedTransition(rootLayout)
        sec.expanded = value
        applyExpanded(sec)
        updateSummary(sec)
    }

    private fun updateSummary(sec: Section) {
        sec.summary?.text = sec.summaryFn?.invoke() ?: ""
    }

    private fun updateSummaries() {
        if (!::txtStatus.isInitialized) return // la pantalla aún se está armando
        sections.forEach { updateSummary(it) }
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
            addOnChangeListener { _, _, _ -> updateSummaries() }
        }

    private fun watch(edit: TextInputEditText, action: () -> Unit) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { action() }
        })
    }

    private fun timeField(hint: String, placeholder: String): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            placeholderText = placeholder
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        watch(edit) { updateSummaries() }
        layout.addView(edit)
        return layout to edit
    }

    /** Campo de BPM grande y editable con botones para ajustarlo. */
    private fun bpmStepper(
        hint: String,
        ops: List<Pair<String, (Double) -> Double>>
    ): Pair<LinearLayout, TextInputEditText> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val layout = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 22f
            gravity = Gravity.CENTER
        }
        layout.addView(edit)
        box.addView(layout, lp(4))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ops.forEachIndexed { i, (label, fn) ->
            val b = tonalButton(label) { parseBpm(edit)?.let { setBpmText(edit, fn(it)) } }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(4)
            })
        }
        box.addView(row, lp(6))
        watch(edit) {
            updateRatioText()
            updateSummaries()
        }
        return box to edit
    }

    // =====================================================================
    // Interfaz: pantalla completa
    // =====================================================================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        rootLayout = root

        root.addView(text("Tono Kempo", MR.style.TextAppearance_Material3_HeadlineMedium))
        root.addView(muted("Cambia el tono y la velocidad de una canción, con metrónomo opcional"))

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

        // ---- Calidad (plegada) ----
        val (qCard, qSec) = collapsibleCard("Calidad", false, { qualitySummary() }) {
            addView(toggle(listOf("Pistas (mejor)", "Completa (rápida)"), 0) { idx ->
                stemsMode = idx == 0
                updateModeUi()
                refreshButtons()
            }, lp(10))

            val noteStems = muted(
                "Separa voz, batería, bajo y otros, cambia cada pista por separado y las vuelve a unir. " +
                    "Tarda más, pero suena mucho mejor en canciones completas. " +
                    "Si no cambias tono, velocidad ni volúmenes por pista, no separa nada."
            )
            addView(noteStems, lp(8))
            stemsOnly.add(noteStems)

            val noteWhole = muted("Procesa la canción entera de una vez. Rápido, pero con mezclas complejas puede sonar peor.")
            addView(noteWhole, lp(8))
            wholeOnly.add(noteWhole)

            val engineToggle = toggle(listOf("Rubber Band", "Kempo"), 0) { idx ->
                engineKempo = idx == 1
                updateSummaries()
            }
            addView(engineToggle, lp(8))
            wholeOnly.add(engineToggle)

            switchPreserve = MaterialSwitch(this@MainActivity).apply {
                text = "Conservar el timbre natural"
                isChecked = true
                setOnCheckedChangeListener { _, _ -> updateSummaries() }
            }
            addView(switchPreserve, lp(12))
            addView(muted("Recomendado. Apagado, la voz suena más grave o más aguda según el tono, como en una cinta acelerada."))

            switchDrums = MaterialSwitch(this@MainActivity).apply {
                text = "Transponer también la batería"
                setOnCheckedChangeListener { _, _ -> updateSummaries() }
            }
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
        }
        secQuality = qSec
        root.addView(qCard, lp(12))

        // ---- Volumen (plegado) ----
        val (vCard, _) = collapsibleCard("Volumen", false, { volumeSummary() }) {
            addView(muted("Se aplica al escuchar y al guardar. Por encima de 100 % se suaviza el pico para evitar distorsión."), lp(4))

            val masterRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            masterRow.addView(
                text("General", MR.style.TextAppearance_Material3_BodyMedium),
                LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            sliderMaster = slider(0f, 200f, 5f, 100f) { "${it.toInt()}%" }
            masterRow.addView(sliderMaster, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(masterRow, lp(4))

            val stemTitle = muted("Por pista (necesita separar las pistas)")
            addView(stemTitle, lp(12))
            stemsOnly.add(stemTitle)

            volSliders = listOf("Voz", "Batería", "Bajo", "Otros").map { name ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    text(name, MR.style.TextAppearance_Material3_BodyMedium),
                    LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                val s = slider(0f, 200f, 5f, 100f) { "${it.toInt()}%" }
                row.addView(s, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(row, lp(2))
                stemsOnly.add(row)
                s
            }
        }
        root.addView(vCard, lp(12))

        // ---- Fragmento (plegado) ----
        val (rCard, rSec) = collapsibleCard("Fragmento", false, { rangeSummary() }) {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val (startLayout, startEdit) = timeField("Inicio", "0:00")
            val (endLayout, endEdit) = timeField("Fin", "final")
            editStart = startEdit
            editEnd = endEdit
            val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(startLayout, LinearLayout.LayoutParams(half).apply { marginEnd = dp(8) })
            row.addView(endLayout, LinearLayout.LayoutParams(half).apply { marginStart = dp(8) })
            addView(row, lp(10))
            addView(
                muted("Minutos:segundos de la canción original, por ejemplo 1:20 y 3:04. Vacío = toda la canción."),
                lp(6)
            )
        }
        secRange = rSec
        root.addView(rCard, lp(12))

        // ---- Tempo y metrónomo (plegado) ----
        val (tCard, tSec) = collapsibleCard("Tempo y metrónomo", false, { tempoSummary() }) {
            addView(
                muted("Detecta el tempo para cambiar la velocidad de la canción o agregar un metrónomo. No necesita separar pistas."),
                lp(6)
            )
            btnTempo = filled("Detectar tempo") { detectTempo() }
            addView(btnTempo, lp(10))
            txtTempo = muted("Sin detectar.")
            addView(txtTempo, lp(6))

            tempoPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

            // Tempo original
            tempoPanel.addView(
                text("Tempo original (corrígelo si la detección se equivocó)", MR.style.TextAppearance_Material3_LabelLarge),
                lp(14)
            )
            val (origBox, origEdit) = bpmStepper(
                "BPM originales",
                listOf<Pair<String, (Double) -> Double>>(
                    "½×" to { it * 0.5 },
                    "2×" to { it * 2.0 },
                    "−0.1" to { it - 0.1 },
                    "+0.1" to { it + 0.1 }
                )
            )
            editBpmOrig = origEdit
            tempoPanel.addView(origBox)

            // Método de detección (plegado)
            val detSec = section(
                "Método de detección",
                false,
                MR.style.TextAppearance_Material3_LabelLarge,
                { if (useDbn) "Estable" else "Picos" }
            ) {
                addView(toggle(listOf("Picos", "Estable"), 0) { idx ->
                    useDbn = idx == 1
                    updateSummaries()
                    redecodeBeats()
                }, lp(6))
                addView(
                    muted("«Picos» usa los golpes tal cual los marca el modelo. «Estable» fuerza un pulso regular y puede ayudar con canciones muy parejas. Prueba las dos."),
                    lp(4)
                )
            }
            tempoPanel.addView(detSec.container, lp(8))

            // Cambiar la velocidad
            switchStretch = MaterialSwitch(this@MainActivity).apply { text = "Cambiar la velocidad de la canción" }
            switchStretch.setOnCheckedChangeListener { _, _ -> updateTempoPanel() }
            tempoPanel.addView(switchStretch, lp(14))

            stretchPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            stretchPanel.addView(
                muted("Elige el tempo nuevo: la canción se hará más lenta o más rápida sin cambiar el tono, y el metrónomo seguirá el tempo nuevo."),
                lp(2)
            )
            val (newBox, newEdit) = bpmStepper(
                "BPM nuevos",
                listOf<Pair<String, (Double) -> Double>>(
                    "−1" to { it - 1.0 },
                    "−0.1" to { it - 0.1 },
                    "+0.1" to { it + 0.1 },
                    "+1" to { it + 1.0 }
                )
            )
            editBpmNew = newEdit
            stretchPanel.addView(newBox)
            stretchPanel.addView(tonalButton("Igual al original") {
                parseBpm(editBpmOrig)?.let { setBpmText(editBpmNew, it) }
            }, lp(2))
            txtRatio = muted("")
            stretchPanel.addView(txtRatio, lp(6))
            tempoPanel.addView(stretchPanel)

            // Metrónomo
            switchClick = MaterialSwitch(this@MainActivity).apply { text = "Agregar metrónomo" }
            switchClick.setOnCheckedChangeListener { _, _ -> updateTempoPanel() }
            tempoPanel.addView(switchClick, lp(14))

            clickPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            clickPanel.addView(text("Cómo suena", MR.style.TextAppearance_Material3_LabelLarge), lp(6))
            clickPanel.addView(toggle(listOf("Sigue la canción", "Tempo fijo"), 1) { idx ->
                clickFixed = idx == 1
            }, lp(6))
            clickPanel.addView(
                muted("«Tempo fijo» marca un pulso perfectamente parejo con los BPM de arriba: es lo más preciso para canciones grabadas con metrónomo. «Sigue la canción» marca cada golpe detectado y acompaña si la música acelera o frena."),
                lp(6)
            )

            clickPanel.addView(text("Volumen del metrónomo", MR.style.TextAppearance_Material3_LabelLarge), lp(14))
            sliderClick = slider(0f, 200f, 5f, 100f) { "${it.toInt()}%" }
            clickPanel.addView(sliderClick)

            // Acento y sincronía (plegado)
            val accSec = section(
                "Acento y sincronía",
                false,
                MR.style.TextAppearance_Material3_LabelLarge,
                { accentSummary() }
            ) {
                addView(text("Compás", MR.style.TextAppearance_Material3_LabelLarge), lp(8))
                addView(toggle(listOf("Auto", "2", "3", "4", "6"), 0) { idx ->
                    barChoice = intArrayOf(0, 2, 3, 4, 6)[idx]
                    updateSummaries()
                    redecodeBeats()
                }, lp(6))

                addView(
                    text("Si el acento (el clic más agudo) cae en el golpe equivocado, muévelo", MR.style.TextAppearance_Material3_LabelLarge),
                    lp(14)
                )
                val shiftRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                txtShift = text("0", MR.style.TextAppearance_Material3_TitleMedium)
                txtShift.gravity = Gravity.CENTER
                shiftRow.addView(iconOnly(R.drawable.ic_chevron_left, "Mover el acento antes") { shiftAccent(-1) })
                shiftRow.addView(txtShift, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
                shiftRow.addView(iconOnly(R.drawable.ic_chevron_right, "Mover el acento después") { shiftAccent(1) })
                addView(shiftRow, lp(6))

                addView(text("Adelantar o atrasar el metrónomo (milisegundos)", MR.style.TextAppearance_Material3_LabelLarge), lp(14))
                sliderPhase = slider(-200f, 200f, 5f, 0f) { String.format(Locale.US, "%+d ms", it.toInt()) }
                addView(sliderPhase)
                addView(muted("Negativo = los clics suenan antes; positivo = después."), lp(2))
            }
            clickPanel.addView(accSec.container, lp(10))
            tempoPanel.addView(clickPanel)

            btnPreview = tonalButton("Escuchar 15 segundos") { applyAndPlay(PREVIEW_MS) }
            tempoPanel.addView(btnPreview, lp(14))

            addView(tempoPanel)
        }
        secTempo = tSec
        root.addView(tCard, lp(12))

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
    // Resúmenes que se ven con las secciones plegadas
    // =====================================================================

    private fun qualitySummary(): String {
        val parts = ArrayList<String>()
        parts.add(
            if (stemsMode) "Pistas"
            else if (engineKempo) "Completa · Kempo"
            else "Completa · Rubber Band"
        )
        parts.add(if (switchPreserve.isChecked) "timbre natural" else "timbre movido")
        if (stemsMode && switchDrums.isChecked) parts.add("batería transpuesta")
        return parts.joinToString(" · ")
    }

    private fun volumeSummary(): String {
        val names = listOf("Voz", "Batería", "Bajo", "Otros")
        val parts = ArrayList<String>()
        parts.add("General ${sliderMaster.value.toInt()}%")
        if (stemsMode) {
            for (i in volSliders.indices) {
                val v = volSliders[i].value.toInt()
                if (v != 100) parts.add("${names[i]} $v%")
            }
        }
        return parts.joinToString(" · ")
    }

    private fun rangeSummary(): String {
        val s = editStart.text?.toString()?.trim().orEmpty()
        val e = editEnd.text?.toString()?.trim().orEmpty()
        return if (s.isEmpty() && e.isEmpty()) "Toda la canción"
        else "${s.ifEmpty { "0:00" }} → ${e.ifEmpty { "final" }}"
    }

    private fun tempoSummary(): String {
        val an = beatAnalysis ?: return "Sin detectar"
        if (an.beats.isEmpty()) return "Sin resultado"
        val orig = parseBpm(editBpmOrig) ?: an.bpm
        val sb = StringBuilder(String.format(Locale.US, "%.2f BPM", orig))
        if (switchStretch.isChecked) {
            parseBpm(editBpmNew)?.let { sb.append(String.format(Locale.US, " → %.2f", it)) }
        }
        if (switchClick.isChecked) sb.append(" · metrónomo")
        return sb.toString()
    }

    private fun accentSummary(): String {
        val bar = if (barChoice == 0) "auto" else barChoice.toString()
        val shift = if (accentShift > 0) "+$accentShift" else accentShift.toString()
        return "Compás $bar · acento $shift · ${sliderPhase.value.toInt()} ms"
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
        // Si falta el modelo, se abre la sección donde está el botón para descargarlo
        if (needModel && !secQuality.expanded) setExpanded(secQuality, true, false)
        updateSummaries()
    }

    private fun updateTempoPanel() {
        val ready = beatAnalysis?.beats?.isNotEmpty() == true
        tempoPanel.visibility = if (ready) View.VISIBLE else View.GONE
        stretchPanel.visibility = if (switchStretch.isChecked) View.VISIBLE else View.GONE
        clickPanel.visibility = if (switchClick.isChecked) View.VISIBLE else View.GONE
        updateRatioText()
        updateSummaries()
    }

    private fun updateRatioText() {
        if (!::txtRatio.isInitialized || !::editBpmNew.isInitialized) return
        val o = parseBpm(editBpmOrig)
        val n = parseBpm(editBpmNew)
        if (o == null || n == null) {
            txtRatio.text = "Escribe los BPM."
            return
        }
        val raw = o / n
        val ratio = raw.coerceIn(0.5, 2.0)
        val pct = Math.round(ratio * 100.0)
        val limit = if (raw != ratio) " (límite: entre 50 % y 200 % de la duración)" else ""
        txtRatio.text = when {
            ratio > 1.0005 -> "La canción pasa de %.2f a %.2f BPM: dura %d %% de lo original (más lenta).".format(Locale.US, o, n, pct) + limit
            ratio < 0.9995 -> "La canción pasa de %.2f a %.2f BPM: dura %d %% de lo original (más rápida).".format(Locale.US, o, n, pct) + limit
            else -> "Mismo tempo: la canción no cambia de velocidad."
        }
    }

    private fun refreshButtons() {
        val loaded = audio != null
        btnOpen.isEnabled = !busy
        btnPlay.isEnabled = loaded && !busy
        btnPreview.isEnabled = loaded && !busy
        btnSave.isEnabled = loaded && !busy
        btnTempo.isEnabled = loaded && !busy
        btnModel.isEnabled = !busy
        btnImport.isEnabled = !busy
        btnPause.isEnabled = player.isActive
        btnStop.isEnabled = player.isActive
        sliderPos.isEnabled = player.isActive
        btnPause.text = if (player.isPaused) "Reanudar" else "Pausa"
        btnPause.setIconResource(if (player.isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        btnCancel.visibility = if (busy && cancellable) View.VISIBLE else View.GONE
        updateSummaries()
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

    private fun parseBpm(edit: TextInputEditText): Double? =
        edit.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun setBpmText(edit: TextInputEditText, v: Double) {
        edit.setText(String.format(Locale.US, "%.2f", v.coerceIn(30.0, 300.0)))
        edit.setSelection(edit.text?.length ?: 0)
    }

    private fun shiftAccent(d: Int) {
        accentShift += d
        txtShift.text = if (accentShift > 0) "+$accentShift" else accentShift.toString()
        updateSummaries()
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
        baseKey = null
        baseRendered = null
        stems = null
        procFiles = null
        procKey = null
        beatAnalysis = null
        accentShift = 0
        txtShift.text = "0"
        txtTempo.text = "Sin detectar."
        switchStretch.isChecked = false
        switchClick.isChecked = false
        updateTempoPanel()
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

    private fun meters(): IntArray = if (barChoice == 0) intArrayOf(3, 4) else intArrayOf(barChoice)

    private fun showAnalysis(an: BeatAnalysis) {
        if (an.beats.isEmpty() || an.bpm <= 0.0) {
            txtTempo.text = "No se detectaron golpes claros en esta canción."
            return
        }
        txtTempo.text = String.format(
            Locale.getDefault(),
            "Detectado: %.2f BPM, compás de %d tiempos, %d golpes.",
            an.bpm, an.meter, an.beats.size
        )
        setBpmText(editBpmOrig, an.bpm)
        setBpmText(editBpmNew, an.bpm)
    }

    private fun detectTempo() {
        val a = audio ?: return
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
                val an = BeatTracker.analyze(ctx, a, meters(), useDbn, cancelFlag) { p ->
                    ui.post { setStatus("Detectando tempo… ${(p * 100).toInt()}%", p) }
                }
                ui.post {
                    beatAnalysis = an
                    accentShift = 0
                    txtShift.text = "0"
                    updateTempoPanel()
                    showAnalysis(an)
                    setBusy(false, if (an.beats.isEmpty()) "Sin resultado de tempo" else "Tempo detectado")
                }
            } catch (e: CancelledException) {
                ui.post { setBusy(false, "Cancelado") }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al detectar tempo: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    /** Vuelve a calcular golpes y compases con el método y los tiempos por compás elegidos (sin repetir el modelo). */
    private fun redecodeBeats() {
        val an = beatAnalysis ?: return
        if (busy) return
        setBusy(true, "Recalculando…")
        Thread {
            try {
                val re = BeatTracker.decode(an.beatProb, an.downProb, meters(), useDbn)
                ui.post {
                    beatAnalysis = re
                    accentShift = 0
                    txtShift.text = "0"
                    showAnalysis(re)
                    updateTempoPanel()
                    setBusy(false, "Listo")
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al recalcular: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    /** Razón de tiempo de la salida respecto al original (1 = igual, 1.2 = 20 % más larga y lenta). */
    private fun timeRatio(): Double {
        if (!switchStretch.isChecked || beatAnalysis == null) return 1.0
        val o = parseBpm(editBpmOrig) ?: return 1.0
        val n = parseBpm(editBpmNew) ?: return 1.0
        return (o / n).coerceIn(0.5, 2.0)
    }

    /** Metrónomo en el tiempo del audio resultante. */
    private fun buildClick(a: DecodedAudio, ratio: Double): ClickSpec? {
        if (!switchClick.isChecked) return null
        val an = beatAnalysis ?: return null
        val n = an.beats.size
        if (n == 0) return null
        val gain = sliderClick.value / 100f
        val meter = an.meter
        val phase = sliderPhase.value / 1000.0

        if (clickFixed) {
            // Pulso perfectamente parejo. BPM de la canción original, o el nuevo si se cambió la velocidad.
            val origBpm = parseBpm(editBpmOrig) ?: an.bpm
            val outBpm = if (switchStretch.isChecked) (parseBpm(editBpmNew) ?: origBpm) else origBpm
            if (outBpm > 0.0) {
                val step = 60.0 / outBpm
                val t0 = an.gridT0 * ratio + phase
                val duration = a.durationMs / 1000.0 * ratio
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
        }

        // Sigue los golpes detectados; el acento va en el golpe 1 de cada compás (movible con ◀ ▶)
        val times = DoubleArray(n) { an.beats[it] * ratio + phase }
        val accent = BooleanArray(n) { i -> Math.floorMod(an.beatInBar[i] - 1 - accentShift, meter) == 0 }
        return ClickSpec(times, accent, gain)
    }

    /** Lee fragmento, volúmenes y metrónomo; los tiempos se convierten al audio resultante. */
    private fun buildOutSettings(a: DecodedAudio, ratio: Double, previewMs: Long?): OutSettings? {
        val s = parseTimeMs(editStart.text.toString())
        val e = parseTimeMs(editEnd.text.toString())
        if (s == TIME_INVALID || e == TIME_INVALID) {
            setExpanded(secRange, true, true)
            setStatus("Tiempo no válido. Usa minutos:segundos, por ejemplo 1:20.")
            return null
        }
        val startOrig = if (s == TIME_EMPTY) 0L else s
        val endOrig = if (e == TIME_EMPTY) -1L else e
        if (endOrig in 0..startOrig) {
            setExpanded(secRange, true, true)
            setStatus("El fin debe ser mayor que el inicio.")
            return null
        }
        if (startOrig >= a.durationMs) {
            setExpanded(secRange, true, true)
            setStatus("El inicio está después del final de la canción.")
            return null
        }
        val startOut = (startOrig * ratio).toLong()
        var endOut = if (endOrig < 0) -1L else (endOrig * ratio).toLong()
        if (previewMs != null) {
            val cap = startOut + previewMs
            endOut = if (endOut < 0) cap else min(endOut, cap)
        }
        // Orden interno: batería, bajo, otros, voz. En pantalla: voz, batería, bajo, otros.
        // Las pistas solo cuentan en el modo de pistas.
        val v = volSliders.map { if (stemsMode) it.value / 100f else 1f }
        val gains = floatArrayOf(v[1], v[2], v[3], v[0])
        val master = sliderMaster.value / 100f
        val rangeKey = "${editStart.text.toString().trim()}|${editEnd.text.toString().trim()}|${previewMs ?: 0}"
        return OutSettings(gains, master, startOut, endOut, buildClick(a, ratio), rangeKey)
    }

    // =====================================================================
    // Procesamiento
    // =====================================================================

    private fun process(previewMs: Long?, then: (Rendered) -> Unit) {
        val a = audio ?: return
        val st = semitones()
        val ratio = timeRatio()
        val out = buildOutSettings(a, ratio, previewMs) ?: return

        // El volumen general no exige separar pistas; solo los volúmenes por pista
        val gainsDefault = out.gains.all { it == 1f }
        val nothingToChange = st == 0f && ratio == 1.0 && gainsDefault

        if (stemsMode && !nothingToChange) processStems(a, st, ratio, out, then)
        else processWhole(a, st, ratio, out, nothingToChange, then)
    }

    /** Procesa la canción entera (o la deja igual) y después recorta, sube o baja el volumen y agrega el metrónomo. */
    private fun processWhole(
        a: DecodedAudio,
        st: Float,
        ratio: Double,
        out: OutSettings,
        untouched: Boolean,
        then: (Rendered) -> Unit
    ) {
        val moveFormants = !switchPreserve.isChecked
        val eng = if (ratio != 1.0 || !engineKempo) Engine.RUBBERBAND else Engine.KEMPO
        val key = if (untouched) "orig" else "whole|$st|$moveFormants|$eng|$ratio"

        fun finish(base: Rendered): Rendered {
            val pcm = Finisher.cropAndClick(
                base.pcm, base.sampleRate, base.channels, out.startMs, out.endMs, out.master, out.click
            )
            return Rendered(pcm, base.sampleRate, base.channels, out.rangeKey)
        }

        val cached = baseRendered
        if (key == baseKey && cached != null) {
            try {
                then(finish(cached))
            } catch (e: Throwable) {
                setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            }
            return
        }

        val engineName = if (eng == Engine.RUBBERBAND) "Rubber Band" else "Kempo"
        setBusy(true, if (untouched) "Preparando…" else "Procesando con $engineName…")
        Thread {
            try {
                val pcm = if (untouched) a.pcm else PitchProcessor.shift(a, st, moveFormants, eng, ratio) { p ->
                    ui.post { setStatus("Procesando con $engineName… ${(p * 100).toInt()}%", p) }
                }
                val base = Rendered(pcm, a.sampleRate, a.channels)
                val result = finish(base)
                ui.post {
                    baseKey = key
                    baseRendered = base
                    setBusy(false, "Listo")
                    then(result)
                }
            } catch (e: Throwable) {
                ui.post { setBusy(false, "Error al procesar: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun processStems(
        a: DecodedAudio,
        st: Float,
        ratio: Double,
        out: OutSettings,
        then: (Rendered) -> Unit
    ) {
        if (!StemSeparator.hasModel(this)) {
            setExpanded(secQuality, true, true)
            setStatus("Falta el modelo de separación. Usa el botón Descargar modelo.")
            updateModeUi()
            return
        }
        val moveFormants = !switchPreserve.isChecked
        val drums = switchDrums.isChecked
        val key = "$st|$moveFormants|$drums|$ratio"
        val settings = MixSettings(out.gains, out.startMs, out.endMs, out.click, out.master)

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

                // 2) Transponer / cambiar la velocidad de cada pista (solo si cambió algo)
                val cachedFiles = procFiles
                val ready: List<File> = if (procKey == key && cachedFiles != null) {
                    cachedFiles
                } else {
                    ui.post { setStatus("Procesando pistas con Rubber Band…", 0f) }
                    StemMixer.prepare(sep, stemsDir(), st, ratio, moveFormants, drums, cancelFlag) { p ->
                        ui.post { setStatus("Procesando pistas… ${(p * 100).toInt()}%", p) }
                    }.also { files ->
                        procFiles = files
                        procKey = key
                    }
                }

                // 3) Mezclar con volúmenes, fragmento y metrónomo (rápido)
                ui.post { setStatus("Mezclando…") }
                val pcm = StemMixer.mix(ready, settings)
                val r = Rendered(pcm, StemSeparator.SAMPLE_RATE, 2, out.rangeKey)
                ui.post {
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
