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

/**
 * Fully offline speech using the tablet's own TTS engine (Google Speech Services Hindi voices).
 *
 * Voice numbering: Google lists the installed Hindi voices as "Voice I, II, III, IV" in system settings.
 * The Android API does not expose those display names, so they are matched by order of the installed
 * local voices. Default: FEMALE = Voice II, MALE = Voice IV (can be changed in the app).
 */
object DeviceTts {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val lock = Any()
    private val pendingCb = ArrayList<() -> Unit>()

    private class Waiter { val latch = CountDownLatch(1); @Volatile var ok = false }
    private val waiters = ConcurrentHashMap<String, Waiter>()

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    private const val FEMALE_INDEX = 1      // Voice II
    private const val MALE_INDEX = 3        // Voice IV

    fun init(ctx: Context, onReady: (() -> Unit)? = null) {
        synchronized(pendingCb) {
            if (ready) { onReady?.invoke(); return }
            if (onReady != null) pendingCb.add(onReady)
            if (tts != null) return                       // init already running: callback will be called when ready
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { waiters.remove(id)?.let { it.ok = true; it.latch.countDown() } }
                        @Deprecated("old callback") override fun onError(id: String?) { waiters.remove(id)?.latch?.countDown() }
                        override fun onError(id: String?, code: Int) { waiters.remove(id)?.latch?.countDown() }
                    })
                    val cbs = synchronized(pendingCb) { val c = ArrayList(pendingCb); pendingCb.clear(); c }
                    cbs.forEach { try { it() } catch (_: Exception) {} }
                } else {
                    State.status = "Device TTS init failed"
                    synchronized(pendingCb) { pendingCb.clear() }
                    tts = null
                }
            }
        }
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }

    /** Installed, offline voices of a language in a stable order (this order = Voice I, II, III, IV ...). */
    fun installedVoices(lang: String): List<Voice> {
        if (!ready) return emptyList()
        val all = (tts?.voices ?: emptySet()).filter {
            it.locale.language == lang && !it.name.endsWith("-language") &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val local = all.filter { !it.isNetworkConnectionRequired }
        return (if (local.isNotEmpty()) local else all).sortedBy { it.name }
    }

    fun label(index: Int, v: Voice): String = "Voice ${ROMAN.getOrElse(index) { (index + 1).toString() }}  (${v.name})"

    /** The voice that will really be used: the saved one if still installed, else Voice II (female) / Voice IV (male). */
    fun resolveName(p: Prefs, gender: String): String? {
        val list = installedVoices("hi")
        if (list.isEmpty()) return null
        val saved = if (gender == "male") p.devMale else p.devFemale
        list.firstOrNull { it.name == saved }?.let { return it.name }
        val idx = if (gender == "male") MALE_INDEX else FEMALE_INDEX
        return list[minOf(idx, list.size - 1)].name
    }

    /** Must be called inside synchronized(lock). Sets language, then the exact voice, pitch and rate. */
    private fun applyVoice(t: TextToSpeech, p: Prefs, gender: String, lang: String, rate: Float): Boolean {
        val tag = if (lang == "hi") "hi-IN" else lang
        val r = t.setLanguage(Locale.forLanguageTag(tag))      // must come before setVoice
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            State.status = "Install '$tag' voice data (button in app)"; return false
        }
        if (lang == "hi") {
            val name = resolveName(p, gender)
            val v = name?.let { n -> t.voices?.firstOrNull { it.name == n } }
            if (v != null && t.setVoice(v) != TextToSpeech.SUCCESS) State.status = "could not select ${v.name}"
        }
        t.setPitch(1f)                       // pitch-shifting made the male voice sound robotic
        t.setSpeechRate(rate)
        return true
    }

    fun speak(ctx: Context, text: String, gender: String, lang: String) {
        if (!ready) { init(ctx) { speak(ctx, text, gender, lang) }; return }
        val t = tts ?: return
        val p = Prefs(ctx)
        synchronized(lock) {
            if (!applyVoice(t, p, gender, lang, 1.1f)) return
            State.muteUntil = System.currentTimeMillis() + maxOf(2500L, text.length * 90L)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
        }
        State.status = "spoke (device/$gender)"
    }

    /**
     * Renders the tablet's own Hindi voice into a WAV file (no sound is played) and waits for it.
     * This is near-instant and is the base that the Termux server turns into the cloned voice.
     */
    fun synthToFile(ctx: Context, text: String, gender: String, lang: String, out: File): Boolean {
        if (!ready) {
            val l = CountDownLatch(1)
            init(ctx) { l.countDown() }
            l.await(4, TimeUnit.SECONDS)
        }
        val t = tts ?: return false
        if (!ready) return false
        val id = "f${System.nanoTime()}"
        val w = Waiter()
        synchronized(lock) {
            if (!applyVoice(t, Prefs(ctx), gender, lang, 1.15f)) return false
            waiters[id] = w
            if (t.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) { waiters.remove(id); return false }
        }
        w.latch.await(8, TimeUnit.SECONDS)
        waiters.remove(id)
        return w.ok && out.exists() && out.length() > 100
    }

    /** Says a short sample with one specific voice (used when picking a voice in the app). */
    fun preview(ctx: Context, voiceName: String) {
        val t = tts ?: return
        if (!ready) return
        synchronized(lock) {
            t.setLanguage(Locale.forLanguageTag("hi-IN"))
            t.voices?.firstOrNull { it.name == voiceName }?.let { t.setVoice(it) }
            t.setPitch(1f); t.setSpeechRate(1.0f)
            t.speak("नमस्ते, यह मेरी आवाज़ है।", TextToSpeech.QUEUE_FLUSH, null, "pv${System.nanoTime()}")
        }
    }
}
