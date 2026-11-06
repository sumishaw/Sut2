package com.subvoice

import android.content.Context

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("subvoice", Context.MODE_PRIVATE)
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var xttsUrl: String get() = str("xtts", "http://192.168.1.10:8000"); set(v) = put("xtts", v.trim().trimEnd('/'))
    var cbUrl: String get() = str("cb", "http://192.168.1.10:8001"); set(v) = put("cb", v.trim().trimEnd('/'))
    var localUrl: String get() = str("local", "http://127.0.0.1:8002"); set(v) = put("local", v.trim().trimEnd('/'))
    var engine: String get() = str("engine", "local"); set(v) = put("engine", v)       // device | local | xtts | chatterbox
    var genderMode: String get() = str("gender", "auto"); set(v) = put("gender", v)   // auto | female | male
    var lang: String get() = str("lang", "hi"); set(v) = put("lang", v.trim())
    var voiceId: String get() = str("voice", ""); set(v) = put("voice", v)
    var uploadedTo: String get() = str("upto", ""); set(v) = put("upto", v)
    var devFemale: String get() = str("devF", ""); set(v) = put("devF", v)
    var devMale: String get() = str("devM", ""); set(v) = put("devM", v)
    var fallback: Boolean get() = bool("fb", true); set(v) = putB("fb", v)
    var enabled: Boolean get() = bool("enabled", true); set(v) = putB("enabled", v)
    var autoRef: Boolean get() = bool("auto", true); set(v) = putB("auto", v)         // clone the video's own speaker
    var ocr: Boolean get() = bool("ocr", true); set(v) = putB("ocr", v)               // read subtitles from the screen
    var pauseVideo: Boolean get() = bool("pause", false); set(v) = putB("pause", v)   // pause video while dubbing
}

object State {
    @Volatile var gender = "female"
    @Volatile var lastCaption = ""
    @Volatile var status = "idle"
    @Volatile var debug = ""
    @Volatile var events = 0
    @Volatile var pkg = ""
    @Volatile var ocrText = ""
    @Volatile var audioFrames = 0
    @Volatile var audioLoud = 0
    @Volatile var appVisible = true   // SubVoice screen in front: never OCR our own UI
    val created = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val uploaded = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var muteUntil = 0L      // ignore captured audio while the on-device TTS is speaking
}
