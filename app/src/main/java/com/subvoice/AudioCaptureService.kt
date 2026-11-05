package com.subvoice

import android.app.Notification
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
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * One screen-capture permission powers three things:
 *  1. playback-audio capture -> speaker gender guess + automatic voice-clone reference (8 s of the video's speaker)
 *  2. screen OCR (on-device ML Kit, Hindi/Devanagari) -> subtitles from ANY player
 */
class AudioCaptureService : Service() {
    private var proj: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private val recognizer by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null || i.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val ch = "cap"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "SubVoice capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("SubVoice is reading subtitles and audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {}, null)
        }
        running = true
        startAudio()
        if (Prefs(this).ocr) startOcr()
        State.status = "capture running"
        return START_NOT_STICKY
    }

    // ---------------- audio: gender + automatic clone reference ----------------
    private fun startAudio() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        rec = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(16000 * 2).build()
        rec!!.startRecording()
        thread(name = "audio") { audioLoop() }
    }

    private fun audioLoop() {
        val buf = ShortArray(1024)
        val f0s = ArrayDeque<Float>()
        val voiced = ByteArrayOutputStream()
        var voicedSamples = 0
        var lastRefAt = 0L
        val prefs = Prefs(this)
        while (running) {
            val n = rec?.read(buf, 0, buf.size) ?: break
            if (n < buf.size) continue
            val now = System.currentTimeMillis()
            if (now < State.muteUntil) continue            // our own on-device TTS is speaking
            var e = 0.0
            for (s in buf) e += s.toDouble() * s
            if (sqrt(e / buf.size) < 400) continue         // silence
            val f = f0(buf, e)
            if (f > 0f) {
                f0s.addLast(f); if (f0s.size > 40) f0s.removeFirst()
                if (f0s.size >= 8) State.gender = if (f0s.sorted()[f0s.size / 2] > 165f) "female" else "male"
            }
            if (prefs.autoRef && now - lastRefAt > 90_000) {
                val bb = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in buf) bb.putShort(s)
                voiced.write(bb.array()); voicedSamples += buf.size
                if (voicedSamples >= 16000 * 8) {
                    saveRef(voiced.toByteArray())
                    voiced.reset(); voicedSamples = 0; lastRefAt = now
                }
            }
        }
    }

    private fun saveRef(pcm: ByteArray) {
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray()); hdr.putInt(36 + pcm.size); hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray()); hdr.putInt(16); hdr.putShort(1); hdr.putShort(1)
        hdr.putInt(16000); hdr.putInt(32000); hdr.putShort(2); hdr.putShort(16)
        hdr.put("data".toByteArray()); hdr.putInt(pcm.size)
        File(filesDir, "auto_ref.wav").outputStream().use { it.write(hdr.array()); it.write(pcm) }
        State.autoCreated = System.currentTimeMillis()
        State.status = "voice reference captured from the video"
        thread {
            try { Speaker.uploadAuto(applicationContext) }
            catch (e: Exception) { State.status = "reference upload: ${e.message}" }
        }
    }

    /** Normalised autocorrelation pitch estimate, 70-400 Hz. 0 = unvoiced. */
    private fun f0(x: ShortArray, e: Double): Float {
        var best = 0.0; var bl = 0
        for (lag in 40..228) {
            var c = 0.0; var i = 0
            while (i < x.size - lag) { c += x[i].toDouble() * x[i + lag]; i++ }
            val r = c / e
            if (r > best) { best = r; bl = lag }
        }
        return if (best > 0.45 && bl > 0) 16000f / bl else 0f
    }

    // ---------------- screen OCR for subtitles ----------------
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
        setupDisplay()
        thread(name = "ocr") { ocrLoop() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (vd != null) setupDisplay()        // rotate to fullscreen landscape -> follow it
    }

    private fun ocrLoop() {
        while (running) {
            try {
                val img = reader?.acquireLatestImage()
                if (img != null) {
                    val w = img.width; val h = img.height
                    val pl = img.planes[0]
                    val rowPad = pl.rowStride - pl.pixelStride * w
                    val full = Bitmap.createBitmap(w + rowPad / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(pl.buffer)
                    img.close()
                    val top = (h * 0.6).toInt()                       // subtitles live in the lower part
                    val crop = Bitmap.createBitmap(full, 0, top, w, h - top)
                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)))
                        .text.replace('\n', ' ').trim()
                    handleOcr(text)
                }
            } catch (e: Exception) { State.status = "OCR: ${e.message}" }
            Thread.sleep(800)
        }
    }

    private fun handleOcr(text: String) {
        State.ocrText = text
        val letters = text.filter { !it.isWhitespace() }
        if (letters.length < 3) return
        val dev = letters.count { it.code in 0x0900..0x097F }
        if (dev * 2 < letters.length) return                   // only mostly-Hindi lines count as subtitles
        Pipeline.onCaption(applicationContext, text)
    }

    override fun onDestroy() {
        running = false
        try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        try { vd?.release(); reader?.close(); recognizer.close() } catch (_: Exception) {}
        proj?.stop()
        super.onDestroy()
    }
}
