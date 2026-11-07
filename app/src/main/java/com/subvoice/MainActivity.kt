package com.subvoice

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : android.app.Activity() {
    private lateinit var p: Prefs
    private lateinit var status: TextView
    private lateinit var dbgView: TextView
    private lateinit var xtts: EditText
    private lateinit var cb: EditText
    private lateinit var lang: EditText
    private lateinit var local: EditText
    private lateinit var femAd: ArrayAdapter<String>
    private lateinit var maleAd: ArrayAdapter<String>
    private lateinit var enabledSw: Switch
    private lateinit var femSp: Spinner
    private lateinit var maleSp: Spinner
    private val ui = Handler(Looper.getMainLooper())
    private var voiceNames: List<String> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = Prefs(this)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, 20, 0, 4) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun edit(v: String) = EditText(this).apply { setText(v); setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        fun spin(items: List<String>, cur: String, on: (String) -> Unit) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(items.indexOf(cur).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, i: Int, id: Long) { on(items[i]) }
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
        }
        fun voiceSpin(ad: ArrayAdapter<String>, gender: String) = Spinner(this).apply {
            adapter = ad
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, i: Int, id: Long) {
                    val name = voiceNames.getOrNull(i) ?: return
                    if (name == DeviceTts.resolveName(p, gender)) return       // no change (also ignores the automatic call after loading)
                    if (gender == "male") p.devMale = name else p.devFemale = name
                    DeviceTts.preview(this@MainActivity, name)
                }
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
        }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        dbgView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        root.addView(dbgView)

        root.addView(btn("1. Enable caption reader (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. Start screen + audio capture (needed for OCR, video-voice clone, gender)") {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 2)
        })
        root.addView(btn("Stop capture") { stopEverything() })

        enabledSw = Switch(this).apply {
            text = "Speak captions"; isChecked = p.enabled
            setOnCheckedChangeListener { _, c -> p.enabled = c; if (!c) { Speaker.stopAll(this@MainActivity); Pipeline.reset() } }
        }
        root.addView(enabledSw)
        root.addView(Switch(this).apply {
            text = "Read Hindi subtitles from the screen (OCR; use fullscreen) - restart capture after changing"; isChecked = p.ocr
            setOnCheckedChangeListener { _, c -> p.ocr = c }
        })
        root.addView(Switch(this).apply {
            text = "Clone the video's own speaker automatically"; isChecked = p.autoRef
            setOnCheckedChangeListener { _, c -> p.autoRef = c }
        })
        root.addView(Switch(this).apply {
            text = "Pause the video while the Hindi voice is generated"; isChecked = p.pauseVideo
            setOnCheckedChangeListener { _, c -> p.pauseVideo = c }
        })
        root.addView(Switch(this).apply {
            text = "If server is unreachable, use on-device voice"; isChecked = p.fallback
            setOnCheckedChangeListener { _, c -> p.fallback = c }
        })

        root.addView(label("Voice engine:  local = tablet Hindi voice + cloning of the video speaker (needs Termux server, near real-time) | fast = Kokoro voices from Termux (no cloning) | device = plain tablet voice | xtts / chatterbox = cloud GPU"))
        root.addView(spin(listOf("local", "fast", "device", "xtts", "chatterbox"), p.engine) { p.engine = it })
        root.addView(label("Voice gender"))
        root.addView(spin(listOf("auto", "female", "male"), p.genderMode) { p.genderMode = it })

        root.addView(label("On-device Hindi voices (offline engine)"))
        femAd = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf("(loading voices...)"))
        maleAd = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf("(loading voices...)"))
        root.addView(label("Female voice - Voice I or II (tap one to hear it; the app measures each voice and never uses a male-sounding voice here)")); femSp = voiceSpin(femAd, "female"); root.addView(femSp)
        root.addView(label("Male voice - Voice III or IV (tap one to hear it)")); maleSp = voiceSpin(maleAd, "male"); root.addView(maleSp)
        root.addView(btn("Install / update Hindi voice data") {
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
            catch (e: Exception) { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        })
        root.addView(btn("Reload voice list") { loadVoices() })

        root.addView(label("Local (Termux) server URL"))
        local = edit(p.localUrl); root.addView(local)
        root.addView(label("XTTS server URL"))
        xtts = edit(p.xttsUrl); root.addView(xtts)
        root.addView(label("Chatterbox server URL (paste the Colab link)"))
        cb = edit(p.cbUrl); root.addView(cb)
        root.addView(label("Language code for xtts/device (Chatterbox is fixed to Hindi: hi)"))
        lang = edit(p.lang); root.addView(lang)

        root.addView(btn("Choose reference voice to clone (Hindi, 10–20 s)") {
            save()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 3)
        })
        root.addView(btn("Test speak") {
            save()
            Speaker.enqueue(this, if (p.lang == "hi" || p.engine == "chatterbox" || p.engine == "local" || p.engine == "fast") "नमस्ते, यह एक परीक्षण है।" else "Hello, this is a test.")
        })

        setContentView(ScrollView(this).apply { addView(root) })
        loadVoices()

        ui.post(object : Runnable {
            override fun run() {
                val cl = when {
                    p.engine != "local" && p.engine != "chatterbox" -> "off (engine ${p.engine})"
                    p.autoRef && State.created.isNotEmpty() -> State.speaker.ifEmpty { "video voice" }
                    p.voiceId.isNotEmpty() -> p.voiceId
                    else -> "waiting for reference"
                }
                status.text = "Capture: ${if (State.capturing) "RUNNING" else "stopped"}\nStatus: ${State.status}\nDetected voice: ${State.gender}\nCloned voice: $cl\nLast caption: ${State.lastCaption}"
                dbgView.text = "Audio capture: frames=${State.audioFrames} loud=${State.audioLoud} speaker=${State.gender}\n" +
                        "Current voice: ${State.speaker.ifEmpty { "?" }} (${State.gender})  clone refs: ${State.created.keys.sorted().joinToString()}\n" +
                        "Captions app: ${State.pkg} events=${State.events}\n${State.debug}\nOCR: ${State.ocrText}"
                if (enabledSw.isChecked != p.enabled) enabledSw.isChecked = p.enabled
                ui.postDelayed(this, 1000)
            }
        })
    }

    private fun fillVoices() {
        val vs = DeviceTts.installedVoices("hi")
        voiceNames = vs.map { it.name }
        val labels = if (vs.isEmpty()) listOf("(no Hindi voice installed)") else vs.mapIndexed { i, v -> DeviceTts.label(i, v) }
        for ((ad, sp, g) in listOf(Triple(femAd, femSp, "female"), Triple(maleAd, maleSp, "male"))) {
            ad.clear(); ad.addAll(labels); ad.notifyDataSetChanged()
            sp.setSelection(voiceNames.indexOf(DeviceTts.resolveName(p, g)).coerceAtLeast(0))
        }
    }

    private fun loadVoices() = DeviceTts.init(this) {
        runOnUiThread { fillVoices() }
        DeviceTts.calibrate(this) { runOnUiThread { fillVoices() } }     // shows [female/male ~Hz] once measured
    }

    private fun save() { p.xttsUrl = xtts.text.toString(); p.cbUrl = cb.text.toString(); p.localUrl = local.text.toString(); p.lang = lang.text.toString() }
    override fun onPause() { save(); State.appVisible = false; super.onPause() }
    override fun onResume() { super.onResume(); State.appVisible = true }

    @Deprecated("simple")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            2 -> {
                p.enabled = true; enabledSw.isChecked = true
                startForegroundService(Intent(this, AudioCaptureService::class.java)
                    .putExtra("code", res).putExtra("data", data))
            }
            3 -> data.data?.let { upload(it) }
        }
    }

    /** Stop: end capture, silence all speech, clear queues, switch caption speaking off. */
    private fun stopEverything() {
        stopService(Intent(this, AudioCaptureService::class.java))
        Speaker.stopAll(this)
        Pipeline.reset()
        p.enabled = false
        enabledSw.isChecked = false
        State.resetCapture(this)
        toast("Stopped. Turn on 'Speak captions' and start capture again to resume.")
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun upload(uri: Uri) {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val id = UUID.randomUUID().toString().take(8)
        java.io.File(filesDir, "ref.audio").writeBytes(bytes)
        thread {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("voice_id", id)
                    .addFormDataPart("file", "ref.audio", bytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                Net.client.newCall(Request.Builder().url(Speaker.serverFor(p, if (p.engine == "device") "local" else p.engine) + "/voice").post(body).build()).execute().use {
                    if (it.isSuccessful) { p.voiceId = id; p.uploadedTo = Speaker.serverFor(p, if (p.engine == "device") "local" else p.engine); toast("Voice saved. Select it by setting the engine (local or chatterbox).") }
                    else toast("Upload failed: HTTP ${it.code}")
                }
            } catch (e: Exception) { toast("Upload error: ${e.message}") }
        }
    }
}
