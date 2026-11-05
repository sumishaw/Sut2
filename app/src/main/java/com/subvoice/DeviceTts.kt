package com.subvoice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Fully offline speech using the tablet's own TTS engine (e.g. Google Speech Services Hindi voices). */
object DeviceTts {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    fun init(ctx: Context, onReady: (() -> Unit)? = null) {
        if (tts != null) { if (ready) onReady?.invoke(); return }
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            if (ready) onReady?.invoke() else State.status = "Device TTS init failed"
        }
    }

    fun voices(lang: String): List<Voice> =
        if (!ready) emptyList()
        else (tts?.voices ?: emptySet()).filter { it.locale.language == lang }.sortedBy { it.name }

    fun speak(ctx: Context, text: String, gender: String, lang: String) {
        if (!ready) { init(ctx) { speak(ctx, text, gender, lang) }; return }
        val t = tts ?: return
        val p = Prefs(ctx)
        val tag = if (lang == "hi") "hi-IN" else lang
        val r = t.setLanguage(Locale.forLanguageTag(tag))      // must come before setVoice
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            State.status = "Install '$tag' voice data (button in app)"; return
        }
        val name = if (gender == "male") p.devMale else p.devFemale
        val v = t.voices?.firstOrNull { it.name == name }
        if (v != null) { t.voice = v; t.setPitch(1f) }
        else t.setPitch(if (gender == "male") 0.85f else 1.1f)   // crude gender hint when no voice chosen
        State.muteUntil = System.currentTimeMillis() + maxOf(2500L, text.length * 90L)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
        State.status = "spoke (device/$gender)"
    }
}
