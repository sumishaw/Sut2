package com.subvoice

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap 
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One screen-capture permission powers:
 *  1. playback-audio capture -> pitch-based speaker gender + SEPARATE female/male clone references (8 s each)
 *  2. screen OCR (on-device ML Kit, Devanagari) -> centred subtitle lines only, while a video is playing
 */
class AudioCaptureService : Service() {
    companion object { const val RING = 16000 * 8 }
    private var proj: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private var recognizer: TextRecognizer? = null
    private var audioT: Thread? = null
    private var ocrT: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null || i.action == "stop") { fullStop(); return START_NOT_STICKY }
        cleanup()                                   // a second Start must never leave the first capture running
        val ch = "cap"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "SubVoice capture", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 7,
            Intent(this, AudioCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch).setContentTitle("SubVoice is reading subtitles and audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null, "STOP", stopPi).build()).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {}, null)
        }
        running = true; State.capturing = true
        State.audioFrames = 0; State.audioLoud = 0
        startAudio()
        if (Prefs(this).ocr) startOcr()
        State.status = "capture running"
        return START_NOT_STICKY
    }

    // ---------------- audio: gender + per-gender clone references ----------------
    private fun startAudio() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .excludeUid(Process.myUid())
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        rec = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(16000 * 2).build()
        rec!!.startRecording()
        audioT = thread(name = "audio") { audioLoop() }
    }

    private class Prof(val id: String, var f0: Float) {
        val ring = ShortArray(RING)
        var pos = 0
        var filled = 0
        var lastSave = 0L
        var lastUsed = 0L
    }

    /**
     * Tracks WHO is speaking, and only by gender: exactly two voice profiles exist, "spk_f" (female) and "spk_m" (male).
     * Normal changes of intonation inside one person's speech never switch anything. The profile changes only when the
     * pitch has clearly stayed on the other side of the gender border for about a second (hysteresis: >185 Hz female,
     * <150 Hz male, in between = undecided and nothing changes).
     * Every profile keeps a rolling buffer of its own latest 8 s of audio, saved (and re-saved every >=20 s) as its clone reference.
     */
    private fun audioLoop() {
        val buf = ShortArray(1024)
        val recent = ArrayDeque<Float>()
        val profs = HashMap<String, Prof>()
        var cur: Prof? = null
        var against = 0
        val prefs = Prefs(this)

        fun profFor(female: Boolean, med: Float): Prof {
            val id = if (female) "spk_f" else "spk_m"
            return profs.getOrPut(id) { Prof(id, med) }
        }

        while (running) {
            val n = rec?.read(buf, 0, buf.size) ?: break
            if (n < 0) break
            if (n < buf.size) continue
            State.audioFrames++
            val now = System.currentTimeMillis()
            if (now < State.muteUntil) continue            // on-device TTS is speaking
            var e = 0.0
            for (s in buf) e += s.toDouble() * s
            if (sqrt(e / buf.size) < 400) continue         // silence
            State.audioLoud++
            val f = f0(buf, e)
            if (f > 0f) {
                recent.addLast(f); if (recent.size > 16) recent.removeFirst()
                if (recent.size >= 8) {
                    val med = recent.sorted()[recent.size / 2]
                    val vote = when { med > 185f -> 1; med < 150f -> -1; else -> 0 }      // 1 = female, -1 = male
                    val c0 = cur
                    if (c0 == null) {
                        cur = profFor(if (vote != 0) vote > 0 else med > 165f, med)
                    } else if (vote != 0) {
                        val curFemale = c0.id == "spk_f"
                        if ((vote > 0) != curFemale) {
                            if (++against >= 15) { cur = profFor(vote > 0, med); against = 0 }   // ~1 s of the other gender
                        } else {
                            against = 0; c0.f0 = c0.f0 * 0.95f + med * 0.05f
                        }
                    }
                    cur?.let {
                        State.speaker = it.id
                        State.gender = if (it.id == "spk_f") "female" else "male"
                        State.profileF0[it.id] = it.f0; it.lastUsed = now
                    }
                }
            }
            val c = cur ?: continue
            if (!prefs.autoRef) continue
            for (s in buf) { c.ring[c.pos] = s; c.pos = (c.pos + 1) % RING }
            c.filled = minOf(RING, c.filled + buf.size)
            val need = if (c.lastSave == 0L) 16000 * 5 else RING      // first reference after 5 s, then 8 s windows
            if (c.filled >= need && now - c.lastSave > 20_000) { saveRef(c, need); c.lastSave = now }
        }
    }

    private fun saveRef(p: Prof, samples: Int) {
        val total = minOf(samples, p.filled)
        val pcm = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)
        val start = (p.pos - total + RING) % RING
        for (i in 0 until total) pcm.putShort(p.ring[(start + i) % RING])
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray()); hdr.putInt(36 + pcm.capacity()); hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray()); hdr.putInt(16); hdr.putShort(1); hdr.putShort(1)
        hdr.putInt(16000); hdr.putInt(32000); hdr.putShort(2); hdr.putShort(16)
        hdr.put("data".toByteArray()); hdr.putInt(pcm.capacity())
        File(filesDir, "${p.id}.wav").outputStream().use { it.write(hdr.array()); it.write(pcm.array()) }
        State.created[p.id] = System.currentTimeMillis()
        State.status = "updated voice reference ${p.id}"
        thread {
            try { Speaker.uploadAuto(applicationContext, p.id) }
            catch (e: Exception) { State.status = "reference upload: ${e.message}" }
        }
    }

    /** Normalised autocorrelation pitch estimate, 75-350 Hz, picking the SHORTEST strong peak (no octave-low errors). 0 = unvoiced. */
    private fun f0(x: ShortArray, e: Double): Float {
        val r = DoubleArray(230)
        var best = 0.0
        for (lag in 46..213) {
            var c = 0.0; var i = 0
            while (i < x.size - lag) { c += x[i].toDouble() * x[i + lag]; i++ }
            r[lag] = c / e
            if (r[lag] > best) best = r[lag]
        }
        if (best < 0.45) return 0f
        for (lag in 47..212) {
            if (r[lag] >= 0.88 * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) return 16000f / lag
        }
        return 0f
    }

    // ---------------- screen OCR (centred subtitle lines only) ----------------
    @Suppress("DEPRECATION")
    @Synchronized
    private fun setupDisplay() {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels / 2; val h = dm.heightPixels / 2
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        if (vd == null) {
            vd = proj!!.createVirtualDisplay("subvoice-ocr", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        } else {
            vd!!.resize(w, h, dm.densityDpi); vd!!.surface = r.surface
        }
        reader = r
        old?.close()
    }

    private fun startOcr() {
        recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        setupDisplay()
        ocrT = thread(name = "ocr") { ocrLoop() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (vd != null) setupDisplay()
    }

    private fun ocrLoop() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val rc = recognizer ?: return
        while (running) {
            try {
                val img = reader?.acquireLatestImage()
                // read only while a video is playing and SubVoice's own screen is not in front
                if (img != null && (State.appVisible || !am.isMusicActive)) { img.close(); State.ocrText = "(idle)" }
                else if (img != null) {
                    val w = img.width; val h = img.height
                    val pl = img.planes[0]
                    val rowPad = pl.rowStride - pl.pixelStride * w
                    val full = Bitmap.createBitmap(w + rowPad / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(pl.buffer)
                    img.close()
                    val top = (h * 0.6).toInt()
                    val crop = Bitmap.createBitmap(full, 0, top, w, h - top)
                    val cw = crop.width
                    val res = Tasks.await(rc.process(InputImage.fromBitmap(crop, 0)))
                    // subtitles are CENTRED; titles, comments and menus are left/right aligned
                    val lines = res.textBlocks.flatMap { it.lines }.filter { l ->
                        val b = l.boundingBox ?: return@filter false
                        abs((b.left + b.right) / 2f - cw / 2f) < cw * 0.18f && b.width() < cw * 0.95f
                    }.sortedBy { it.boundingBox!!.top }
                    handleOcr(lines.joinToString(" ") { it.text })
                }
            } catch (e: InterruptedException) { break
            } catch (e: Exception) { if (running) State.status = "OCR: ${e.message}" }
            try { Thread.sleep(120) } catch (e: InterruptedException) { break }
        }
    }

    private fun handleOcr(text0: String) {
        val text = text0.replace('\n', ' ').trim()
        State.ocrText = text
        val letters = text.filter { !it.isWhitespace() }
        if (letters.length < 3) return
        val dev = letters.count { it.code in 0x0900..0x097F }
        if (dev * 2 < letters.length) return                   // only mostly-Hindi lines count as subtitles
        Pipeline.onCaption(applicationContext, text)
    }

    /** Stops threads and releases every capture resource. Safe to call repeatedly. */
    private fun cleanup() {
        running = false
        try { rec?.stop() } catch (_: Exception) {}           // unblocks the audio thread
        audioT?.interrupt(); ocrT?.interrupt()
        try { audioT?.join(600); ocrT?.join(600) } catch (_: Exception) {}
        audioT = null; ocrT = null
        try { rec?.release() } catch (_: Exception) {}; rec = null
        try { vd?.release() } catch (_: Exception) {}; vd = null
        try { reader?.close() } catch (_: Exception) {}; reader = null
        try { recognizer?.close() } catch (_: Exception) {}; recognizer = null
        try { proj?.stop() } catch (_: Exception) {}; proj = null
        State.capturing = false
    }

    /** Stop button / notification: capture off, speech off, queues empty, display reset. */
    private fun fullStop() {
        cleanup()
        Speaker.stopAll(this)
        Pipeline.reset()
        Prefs(this).enabled = false
        State.resetCapture(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
