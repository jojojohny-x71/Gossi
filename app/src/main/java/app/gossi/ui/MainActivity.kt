package app.gossi.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.gossi.R
import app.gossi.TranslatorService
import app.gossi.core.Engine
import app.gossi.core.Lang
import app.gossi.core.Segment
import app.gossi.core.Settings
import app.gossi.core.TranslationMode

class MainActivity : Activity(), Engine.Listener {

    private val speakerColors = intArrayOf(
        0xFF4F7CFF.toInt(), 0xFFE8743B.toInt(), 0xFF19A979.toInt(), 0xFFED4A7B.toInt(),
        0xFF945ECF.toInt(), 0xFF13A4B4.toInt(), 0xFFC4A000.toInt(), 0xFF6C8893.toInt(),
    )

    private class Row(val root: View, val header: TextView, val original: TextView, val english: TextView)

    private lateinit var statusView: TextView
    private lateinit var bannerView: TextView
    private lateinit var langRow: LinearLayout
    private lateinit var speakerRow: LinearLayout
    private lateinit var speakerScroll: View
    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var partialView: TextView
    private lateinit var emptyView: TextView
    private lateinit var startButton: Button
    private val rows = HashMap<Long, Row>()

    private val textPrimary get() = getColor(R.color.text_primary)
    private val textSecondary get() = getColor(R.color.text_secondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        Engine.addListener(this)
        render()
    }

    override fun onStop() {
        Engine.removeListener(this)
        super.onStop()
    }

    override fun onEngineChanged() = render()

    // ---------------------------------------------------------------- layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int = 18, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(16), dp(20), dp(16), dp(12))
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Gossi"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("Clear") { confirmClear() })
        header.addView(smallButton("Settings") { showSettings() })
        root.addView(header)

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(statusView)

        bannerView = TextView(this).apply {
            textSize = 13f
            setTextColor(textPrimary)
            background = rounded(getColor(R.color.banner_bg), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(bannerView, matchWrap().apply { bottomMargin = dp(10) })

        root.addView(label("They speak"))
        langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(langRow)
        }, matchWrap().apply { bottomMargin = dp(8) })

        speakerScroll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Speakers (tap to rename, change voice or mute)"))
            speakerRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(speakerRow)
            })
        }
        root.addView(speakerScroll, matchWrap().apply { bottomMargin = dp(8) })

        scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyView = TextView(this).apply {
            text = "Translations appear here.\n\n1. Put in your earbuds and turn on noise cancellation.\n" +
                "2. Pick the language (or leave Auto).\n3. Tap Start and put the phone where it can hear people."
            textSize = 15f
            setTextColor(textSecondary)
            setPadding(dp(4), dp(24), dp(4), dp(24))
        }
        content.addView(emptyView)
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(transcript)
        partialView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(textSecondary)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        content.addView(partialView)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        startButton = Button(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            setOnClickListener { if (Engine.running) stopTranslating() else startTranslating() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply {
            topMargin = dp(8)
        })
        return root
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textSecondary)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun smallButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(getColor(R.color.accent))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun chip(t: String, selected: Boolean, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        if (selected) {
            background = rounded(color)
            setTextColor(0xFFFFFFFF.toInt())
        } else {
            background = rounded(getColor(R.color.surface), stroke = getColor(R.color.outline))
            setTextColor(textPrimary)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) }
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        if (!::statusView.isInitialized) return
        statusView.text = Engine.status
        bannerView.text = if (Engine.headphonesConnected) {
            "🎧 Earbuds connected. Set noise cancellation to High (Nothing X app or quick settings) so you only hear the translation."
        } else {
            "Connect your Nothing Ear (a). Without earbuds Gossi speaks through the phone and pauses listening while it talks."
        }
        startButton.text = if (Engine.running) "Stop" else "Start translating"
        startButton.background = rounded(
            if (Engine.running) getColor(R.color.stop) else getColor(R.color.accent), 30,
        )

        langRow.removeAllViews()
        val current = Engine.settings.language
        val accent = getColor(R.color.accent)
        langRow.addView(chip("Auto", current == null, accent) { Engine.setLanguage(null) })
        for (l in Lang.SOURCES) langRow.addView(chip(l.label, current == l, accent) { Engine.setLanguage(l) })

        speakerRow.removeAllViews()
        speakerScroll.visibility = if (Engine.speakers.isEmpty()) View.GONE else View.VISIBLE
        for (s in Engine.speakers.values) {
            val name = if (s.muted) "🔇 ${s.name}" else s.name
            speakerRow.addView(chip(name, true, speakerColor(s.colorIndex)) { showSpeakerDialog(s.id) })
        }

        renderTranscript()
    }

    private fun speakerColor(i: Int) = speakerColors[i.mod(speakerColors.size)]

    private fun renderTranscript() {
        val atBottom = scroll.getChildAt(0).let { it.bottom - (scroll.height + scroll.scrollY) < dp(120) }
        val live = Engine.segments.map { it.id }.toSet()
        rows.keys.filter { it !in live }.forEach { id -> rows.remove(id)?.let { transcript.removeView(it.root) } }
        for (seg in Engine.segments) {
            val row = rows.getOrPut(seg.id) { createRow(seg).also { transcript.addView(it.root) } }
            bindRow(row, seg)
        }
        emptyView.visibility = if (Engine.segments.isEmpty() && Engine.partial.isEmpty()) View.VISIBLE else View.GONE
        partialView.text = if (Engine.partial.isEmpty()) "" else "… ${Engine.partial}"
        partialView.visibility = if (Engine.partial.isEmpty()) View.GONE else View.VISIBLE
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createRow(seg: Segment): Row {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(getColor(R.color.surface), 14)
        }
        val color = speakerColor(Engine.speakers[seg.speakerId]?.colorIndex ?: 0)
        card.addView(View(this).apply { background = rounded(color, 3) }, LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val header = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
        }
        val english = TextView(this).apply {
            textSize = 18f
            setTextColor(textPrimary)
        }
        val original = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, 0)
        }
        texts.addView(header)
        texts.addView(english)
        texts.addView(original)
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.layoutParams = matchWrap().apply { bottomMargin = dp(8) }
        return Row(card, header, original, english)
    }

    private fun bindRow(row: Row, seg: Segment) {
        val speaker = Engine.speakers[seg.speakerId]
        row.header.text = "${speaker?.name ?: "Speaker"} · ${seg.lang.label}" + (seg.engine?.let { "  ·  $it" } ?: "")
        row.english.text = when {
            seg.english != null -> seg.english
            seg.error != null -> "⚠ ${seg.error}"
            else -> "Translating…"
        }
        row.original.text = if (seg.lang == Lang.ENGLISH) "" else seg.original
        row.original.visibility = if (seg.lang == Lang.ENGLISH) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- actions

    private fun startTranslating() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        TranslatorService.start(this)
    }

    private fun stopTranslating() {
        Engine.stop()
        TranslatorService.stop(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            TranslatorService.start(this)
        } else {
            Toast.makeText(this, "Gossi needs the microphone to hear people.", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear conversation?")
            .setMessage("Removes the transcript and forgets all speakers.")
            .setPositiveButton("Clear") { _, _ -> Engine.clearConversation() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpeakerDialog(id: Int) {
        val s = Engine.speakers[id] ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply {
            setText(s.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text.length)
        }
        box.addView(label("Name"))
        box.addView(name)
        box.addView(label("English voice"))
        val group = RadioGroup(this)
        Engine.voicePresets.forEachIndexed { i, preset ->
            group.addView(RadioButton(this).apply {
                this.id = 1000 + i
                text = preset.label
                isChecked = i == s.presetIndex
                setOnClickListener { Engine.previewVoice(i) }
            })
        }
        box.addView(group)
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                Engine.renameSpeaker(id, name.text.toString().trim())
                val checked = group.checkedRadioButtonId - 1000
                if (checked >= 0) Engine.setSpeakerVoice(id, checked)
            }
            .setNeutralButton(if (s.muted) "Unmute" else "Mute") { _, _ -> Engine.toggleMute(id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        var draft: Settings = Engine.settings
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        box.addView(label("Translation"))
        val modes = RadioGroup(this)
        TranslationMode.entries.forEachIndexed { i, mode ->
            modes.addView(RadioButton(this).apply {
                id = 2000 + i
                text = mode.label
                isChecked = draft.translationMode == mode
                setOnClickListener { draft = draft.copy(translationMode = mode) }
            })
        }
        box.addView(modes)
        box.addView(Button(this).apply {
            text = "Download offline translation (Hindi, Tamil, Kannada)"
            isAllCaps = false
            setOnClickListener {
                isEnabled = false
                text = "Downloading… (keep the app open)"
                Engine.downloadOfflinePacks { summary ->
                    text = "Download offline translation again"
                    isEnabled = true
                    AlertDialog.Builder(this@MainActivity).setTitle("Offline translation").setMessage(summary)
                        .setPositiveButton("OK", null).show()
                }
            }
        })
        box.addView(note("Malayalam has no offline translator in ML Kit, so it always uses online translation."))

        box.addView(label("Listening"))
        box.addView(switch("Detect individual speakers (own voice per person)", draft.speakerDetection) {
            draft = draft.copy(speakerDetection = it)
        })
        box.addView(note("Needs Android 13+. If your speech service doesn't support it, Gossi switches to Simple mode automatically (one voice per language)."))
        box.addView(switch("Strong noise suppression (call-quality mic)", draft.strongNoiseSuppression) {
            draft = draft.copy(strongNoiseSuppression = it)
        })
        box.addView(switch("Use on-device speech recognition", draft.onDeviceRecognition) {
            draft = draft.copy(onDeviceRecognition = it)
        })
        box.addView(note("For speech recognition without internet, open Android Settings, search \"offline speech recognition\" (Speech Services by Google) and download Hindi, Tamil, Kannada and Malayalam."))

        box.addView(label("Speaker separation: merge ←→ split"))
        box.addView(slider((draft.speakerSensitivity * 100).toInt()) {
            draft = draft.copy(speakerSensitivity = it / 100f)
        })

        box.addView(label("Voice"))
        box.addView(TextView(this).apply {
            text = "Speaking speed"
            setTextColor(textPrimary)
        })
        box.addView(slider(((draft.speechRate - 0.7f) / 1.0f * 100).toInt()) {
            draft = draft.copy(speechRate = 0.7f + it / 100f)
        })
        box.addView(switch("Also read out people speaking English", draft.speakEnglish) {
            draft = draft.copy(speakEnglish = it)
        })

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> Engine.updateSettings(draft) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(textSecondary)
        setPadding(0, 0, 0, dp(8))
    }

    @Suppress("DEPRECATION")
    private fun switch(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        isChecked = checked
        setTextColor(textPrimary)
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
    }

    private fun slider(value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = 100
        progress = value.coerceIn(0, 100)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}
