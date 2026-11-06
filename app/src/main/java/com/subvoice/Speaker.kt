package com.subvoice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

object Speaker {
    private val ex = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)

    private fun gender(p: Prefs) = if (p.genderMode == "auto") State.gender else p.genderMode

    fun serverFor(p: Prefs, engine: String) = when (engine) {
        "chatterbox" -> p.cbUrl; "local" -> p.localUrl; else -> p.xttsUrl
    }

    fun enqueue(ctx: Context, text: String) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (p.engine == "device") { DeviceTts.speak(app, text, gender(p), p.lang); return }
        if (queued.get() > 3) return            // can't keep up: drop instead of lagging further
        if (p.pauseVideo && queued.get() == 0) Media.key(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
        queued.incrementAndGet()
        ex.execute {
            try { synth(app, p, text) }
            catch (e: Exception) {
                State.status = "Server problem (${e.message}) -> device voice"
                if (p.fallback) DeviceTts.speak(app, text, gender(p), p.lang)
            } finally {
                if (queued.decrementAndGet() == 0 && p.pauseVideo) Media.key(app, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
    }

    private fun synth(ctx: Context, p: Prefs, text: String) {
        val engine = p.engine
        val cloned = engine == "local" || engine == "chatterbox"
        val g = gender(p)
        var vid = p.voiceId
        if (cloned && p.autoRef) {
            // use the reference captured from the video for the current speaker's gender (or the other one)
            val mine = "auto_$g"
            val other = if (g == "female") "auto_male" else "auto_female"
            val pick = when {
                File(ctx.filesDir, "$mine.wav").exists() -> mine
                File(ctx.filesDir, "$other.wav").exists() -> other
                else -> null
            }
            if (pick != null) { ensureAuto(ctx, pick); vid = pick } else if (vid.isNotEmpty()) ensureVoice(ctx, p)
        } else if (cloned) ensureVoice(ctx, p)
        if (cloned && vid.isEmpty()) error("no voice reference yet - play the video with capture running")
        val lang = if (cloned) "hi" else p.lang                  // cloned engines: Hindi only
        val json = JSONObject().put("text", text).put("language", lang)
            .put("gender", g).put("voice_id", vid)
        val req = Request.Builder().url("${serverFor(p, engine)}/tts/$engine")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val f = File(ctx.cacheDir, "t${System.nanoTime()}.wav")
            f.writeBytes(r.body!!.bytes())
            play(f)
            State.status = "spoke ($engine/$vid/$g)"
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

    /** Sends a voice captured from the video (auto_female / auto_male) to the local server. */
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

    private fun play(f: File) {
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        mp.setDataSource(f.path)
        mp.setOnCompletionListener { latch.countDown() }
        mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
        mp.prepare(); mp.start()
        latch.await(60, TimeUnit.SECONDS)
        mp.release(); f.delete()
    }
}
