package com.subvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads subtitle text exposed by the video app itself (ids containing "caption"/"subtitle"). No general screen reading. */
class CaptionService : AccessibilityService() {
    private var last = ""
    private var lastDbg = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.packageName == packageName) return
        State.pkg = e.packageName?.toString() ?: ""
        State.events++
        if (!Prefs(this).enabled) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!am.isMusicActive) return                      // only while a video/audio is playing
        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()
        val dbg: MutableList<String>? = if (now - lastDbg > 1500) { lastDbg = now; mutableListOf() } else null
        val sb = StringBuilder()
        collect(root, sb, dbg)
        if (dbg != null) State.debug = dbg.joinToString("\n")
        val text = sb.toString().trim()
        if (text.isNotEmpty() && text != last) {
            last = text
            Pipeline.onCaption(applicationContext, text)
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: StringBuilder, dbg: MutableList<String>?) {
        if (n == null) return
        val id = n.viewIdResourceName?.lowercase() ?: ""
        val txt = n.text?.toString()
        if (!txt.isNullOrBlank()) {
            if (dbg != null && dbg.size < 8) dbg.add(id.substringAfterLast('/') + ": " + txt.take(40))
            if ((id.contains("caption") || id.contains("subtitle")) && !n.isClickable) out.append(txt).append(' ')
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, dbg)
    }

    override fun onInterrupt() {}
}

fun similar(a: String, b: String): Boolean {
    fun grams(s: String) = s.filter { !it.isWhitespace() }.windowed(2).toSet()
    val x = grams(a); val y = grams(b)
    if (x.isEmpty() || y.isEmpty()) return a == b
    return x.intersect(y).size.toDouble() / x.union(y).size >= 0.85
}

/**
 * Debounces rolling/jittery captions and speaks only new text. Thread-safe entry point.
 * A caption that is replaced by a DIFFERENT line before its timer fires is spoken immediately instead of being lost.
 */
object Pipeline {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var pending = ""
    private var done = true
    private var spoken = ""
    private var run: Runnable? = null

    fun reset() {
        run?.let { h.removeCallbacks(it) }
        pending = ""; spoken = ""; done = true
    }

    /** What is new in [cur] compared with the line spoken before (handles rolling 2-line captions). */
    private fun newPart(cur: String): String {
        if (spoken.isEmpty()) return cur
        if (cur.startsWith(spoken)) return cur.removePrefix(spoken)
        if (similar(cur, spoken)) return ""
        for (k in minOf(spoken.length, cur.length - 1) downTo 6) {      // old tail == start of new text
            if (cur.startsWith(spoken.takeLast(k))) return cur.drop(k)
        }
        return cur
    }

    private fun commit(ctx: Context) {
        if (done) return
        done = true
        val cur = pending
        val delta = newPart(cur).trim()
        spoken = cur
        if (delta.length > 1) {
            State.lastCaption = delta
            Speaker.enqueue(ctx.applicationContext, delta, State.speaker)
        }
    }

    fun onCaption(ctx: Context, text: String) {
        h.post {
            if (text == pending) return@post                 // same text re-read (OCR polls often)
            run?.let { h.removeCallbacks(it) }
            if (!done && pending.isNotEmpty() && !text.startsWith(pending) && !similar(text, pending)) {
                commit(ctx)                                  // a different line replaced it: speak the old one now
            }
            pending = text; done = false
            run = Runnable { commit(ctx) }
            h.postDelayed(run!!, 180)
        }
    }
}
