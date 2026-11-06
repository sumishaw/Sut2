package com.subvoice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.KeyEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
}

object Media {
    fun key(ctx: Context, code: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Exception) {}
    }
}

/**
 * Two-stage pipeline: generation thread -> play thread. The next line is generated while the
 * previous one is still playing; when lines pile up, playback speeds up (up to 1.36x) to catch up.
 */
object Speaker {
    private val genEx = Executors.newSingleThreadExecutor()
    private val playQ = LinkedBlockingQueue<File>()
    private val inFlight = AtomicInteger(0)          // lines being generated, waiting or playing
    @Volatile private var appCtx: Context? = null
    @Volatile private var playerStarted = false

    private fun genderOf(p: Prefs, vid: String?): String {
        if (p.genderMode != "auto") return p.genderMode
        val f0 = vid?.let { State.profileF0[it] }
        return if (f0 != null) (if (f0 > 165f) "female" else "male") else State.gender
    }

    fun serverFor(p: Prefs, engine: String) = when (engine) {
        "chatterbox" -> p.cbUrl; "local" -> p.localUrl; else -> p.xttsUrl
    }

    private fun finishOne() {
        val c = appCtx ?: return
        if (inFlight.decrementAndGet() <= 0) {
            inFlight.set(0)
            if (Prefs(c).pauseVideo) Media.key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    private fun startPlayer() {
        if (playerStarted) return
        playerStarted = true
        thread(name = "player", isDaemon = true) {
            while (true) {
                val f = playQ.take()
                try { play(f, 1.0f + 0.12f * (inFlight.get() - 1).coerceIn(0, 3)) } catch (_: Exception) {}
                f.delete()
                finishOne()
            }
        }
    }

    fun enqueue(ctx: Context, text: String, spk: String = State.speaker) {
        val app = ctx.applicationContext
        appCtx = app
        val p = Prefs(app)
        if (p.engine == "device") { DeviceTts.speak(app, text, genderOf(p, null), p.lang); return }
        if (inFlight.get() >= 5) return                       // hopelessly behind: skip instead of drifting further
        if (p.pauseVideo && inFlight.get() == 0) Media.key(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
        inFlight.incrementAndGet()
        startPlayer()
        genEx.execute {
            try { playQ.put(synth(app, p, text, spk)) }
            catch (e: Exception) {
                State.status = "Server problem (${e.message}) -> device voice"
                if (p.fallback) DeviceTts.speak(app, text, genderOf(p, null), p.lang)
                finishOne()
            }
        }
    }

    /** Reference of the speaker heard when the line appeared, or the closest-pitched available one. */
    private fun pickRef(spk: String): String? {
        val ready = State.created.keys.toList()
        if (ready.isEmpty()) return null
        if (spk.isNotEmpty() && spk in ready) return spk
        val f0 = State.profileF0[spk] ?: State.profileF0[State.speaker]
        return if (f0 == null) ready.first() else ready.minByOrNull { abs((State.profileF0[it] ?: 0f) - f0) }
    }

    private fun synth(ctx: Context, p: Prefs, text: String, spk: String): File {
        val engine = p.engine
        val cloned = engine == "local" || engine == "chatterbox"
        var vid = p.voiceId
        if (cloned && p.autoRef) {
            val pick = pickRef(spk)
            if (pick != null) { ensureAuto(ctx, pick); vid = pick }
            else if (vid.isNotEmpty()) ensureVoice(ctx, p)
        } else if (cloned) ensureVoice(ctx, p)
        if (cloned && vid.isEmpty()) error("no voice reference yet - play the video with capture running")
        val g = genderOf(p, vid)
        val lang = if (cloned) "hi" else p.lang                  // cloned engines: Hindi only
        val json = JSONObject().put("text", text).put("language", lang)
            .put("gender", g).put("voice_id", vid)
        val req = Request.Builder().url("${serverFor(p, engine)}/tts/$engine")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        val t0 = System.currentTimeMillis()
        return Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val f = File(ctx.cacheDir, "t${System.nanoTime()}.wav")
            f.writeBytes(r.body!!.bytes())
            State.status = "$engine/$vid/$g generated in ${(System.currentTimeMillis() - t0) / 1000}s"
            f
        }
    }

    private fun filePart(id: String, f: File) = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("voice_id", id)
        .addFormDataPart("file", "ref.audio", f.readBytes().toRequestBody("application/octet-stream".toMediaType()))
        .build()

    private fun ensureAuto(ctx: Context, id: String) {
        val up = State.uploaded[id]
        if (up == null || up < (State.created[id] ?: 0L)) uploadAuto(ctx, id)
    }

    /** Sends a voice captured from the video (spk_1, spk_2 ...) to the local server. */
    fun uploadAuto(ctx: Context, id: String) {
        val p = Prefs(ctx)
        val f = File(ctx.filesDir, "$id.wav")
        if (!f.exists() || (p.engine != "local" && p.engine != "chatterbox")) return
        Net.client.newCall(Request.Builder().url(serverFor(p, p.engine) + "/voice")
            .post(filePart(id, f)).build()).execute().use {
            if (!it.isSuccessful) error("voice upload HTTP ${it.code}")
            State.uploaded[id] = System.currentTimeMillis()
        }
    }

    /** Manually chosen reference clip: re-send when the server/URL changed. */
    fun ensureVoice(ctx: Context, p: Prefs) {
        val f = File(ctx.filesDir, "ref.audio")
        if (p.voiceId.isEmpty() || !f.exists() || p.uploadedTo == serverFor(p, p.engine)) return
        Net.client.newCall(Request.Builder().url(serverFor(p, p.engine) + "/voice")
            .post(filePart(p.voiceId, f)).build()).execute().use {
            if (!it.isSuccessful) error("voice upload HTTP ${it.code}")
            p.uploadedTo = serverFor(p, p.engine)
        }
    }

    private fun play(f: File, speed: Float) {
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setDataSource(f.path)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare(); mp.start()
            if (speed > 1.02f) try { mp.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
            latch.await(60, TimeUnit.SECONDS)
        } finally { mp.release() }
    }
}
