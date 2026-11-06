package com.subvoice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fully offline speech using the tablet's own TTS engine (e.g. Google Speech Services Hindi voices). */
object DeviceTts {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private class Waiter { val latch = CountDownLatch(1); @Volatile var ok = false }
    private val waiters = ConcurrentHashMap<String, Waiter>()

    fun init(ctx: Context, onReady: (() -> Unit)? = null) {
        if (tts != null) { if (ready) onReady?.invoke(); return }
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { waiters.remove(id)?.let { it.ok = true; it.latch.countDown() } }
                    @Deprecated("old callback") override fun onError(id: String?) { waiters.remove(id)?.latch?.countDown() }
                    override fun onError(id: String?, code: Int) { waiters.remove(id)?.latch?.countDown() }
                })
                onReady?.invoke()
            } else State.status = "Device TTS init failed"
        }
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }

    /** Best-guess pick of Google's Hindi voices when the user has not chosen one (hic = male, hia = female). */
    private fun autoVoice(t: TextToSpeech, gender: String): Voice? {
        val hint = if (gender == "male") "hic" else "hia"
        val l = (t.voices ?: emptySet()).filter { it.locale.language == "hi" && it.name.contains(hint) }
        return l.firstOrNull { it.name.contains("local") } ?: l.firstOrNull()
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
        val v = t.voices?.firstOrNull { it.name == name } ?: autoVoice(t, gender)
        if (v != null) t.voice = v
        t.setPitch(1f)                       // pitch-shifting is what made the male voice sound robotic
        t.setSpeechRate(1.1f)
        State.muteUntil = System.currentTimeMillis() + maxOf(2500L, text.length * 90L)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
        State.status = "spoke (device/$gender)"
    }

    private fun applyVoice(t: TextToSpeech, p: Prefs, gender: String, lang: String): Boolean {
        val tag = if (lang == "hi") "hi-IN" else lang
        val r = t.setLanguage(Locale.forLanguageTag(tag))      // must come before setVoice
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            State.status = "Install '$tag' voice data (button in app)"; return false
        }
        val name = if (gender == "male") p.devMale else p.devFemale
        val v = t.voices?.firstOrNull { it.name == name } ?: autoVoice(t, gender)
        if (v != null) t.voice = v
        t.setPitch(1f)
        t.setSpeechRate(1.15f)
        return true
    }

    /**
     * Renders the tablet's own Hindi voice into a WAV file (no sound is played) and waits for it.
     * This is near-instant and is the base that the Termux server turns into the cloned voice.
     */
    @Synchronized
    fun synthToFile(ctx: Context, text: String, gender: String, lang: String, out: File): Boolean {
        if (!ready) {
            val l = CountDownLatch(1)
            init(ctx) { l.countDown() }
            l.await(4, TimeUnit.SECONDS)
        }
        val t = tts ?: return false
        if (!ready || !applyVoice(t, Prefs(ctx), gender, lang)) return false
        val id = "f${System.nanoTime()}"
        val w = Waiter()
        waiters[id] = w
        val rc = t.synthesizeToFile(text, Bundle(), out, id)
        if (rc != TextToSpeech.SUCCESS) { waiters.remove(id); return false }
        w.latch.await(8, TimeUnit.SECONDS)
        waiters.remove(id)
        return w.ok && out.exists() && out.length() > 100
    }

    /** Says a short sample with one specific voice (used when picking a voice in the app). */
    fun preview(ctx: Context, voiceName: String) {
        val t = tts ?: return
        if (!ready) return
        t.setLanguage(Locale.forLanguageTag("hi-IN"))
        t.voices?.firstOrNull { it.name == voiceName }?.let { t.voice = it }
        t.setPitch(1f); t.setSpeechRate(1.0f)
        t.speak("नमस्ते, यह मेरी आवाज़ है।", TextToSpeech.QUEUE_FLUSH, null, "pv${System.nanoTime()}")
    }
}
