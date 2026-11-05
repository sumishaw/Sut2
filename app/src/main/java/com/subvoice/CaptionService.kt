package com.subvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads subtitle text exposed through the accessibility tree (YouTube app, many browsers). */
class CaptionService : AccessibilityService() {
    private var last = ""
    private var lastDbg = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.packageName == packageName) return
        State.pkg = e.packageName?.toString() ?: ""
        State.events++
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()
        val dbg: MutableList<String>? = if (now - lastDbg > 1500) { lastDbg = now; mutableListOf() } else null
        val sb = StringBuilder()
        collect(root, prefs.aggressive, sb, resources.displayMetrics.heightPixels, dbg)
        if (dbg != null) State.debug = dbg.joinToString("\n")
        val text = sb.toString().trim()
        if (text.isNotEmpty() && text != last) {
            last = text
            Pipeline.onCaption(applicationContext, text)
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, aggressive: Boolean, out: StringBuilder, h: Int, dbg: MutableList<String>?) {
        if (n == null) return
        val id = n.viewIdResourceName?.lowercase() ?: ""
        val txt = (n.text ?: n.contentDescription)?.toString()
        if (!txt.isNullOrBlank()) {
            if (dbg != null && dbg.size < 12) dbg.add(id.substringAfterLast('/') + ": " + txt.take(40))
            if (id.contains("caption") || id.contains("subtitle")) {
                out.append(txt).append(' ')
            } else if (aggressive && !n.isClickable) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.top > h * 0.30 && r.bottom < h * 0.85 && txt.length in 2..140) out.append(txt).append(' ')
            }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), aggressive, out, h, dbg)
    }

    override fun onInterrupt() {}
}

fun similar(a: String, b: String): Boolean {
    fun grams(s: String) = s.filter { !it.isWhitespace() }.windowed(2).toSet()
    val x = grams(a); val y = grams(b)
    if (x.isEmpty() || y.isEmpty()) return a == b
    return x.intersect(y).size.toDouble() / x.union(y).size >= 0.75
}

/** Debounces rolling/jittery captions and speaks only new text. Thread-safe entry point. */
object Pipeline {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var pending = ""
    private var spoken = ""
    private var run: Runnable? = null

    fun onCaption(ctx: Context, text: String) {
        pending = text
        run?.let { h.removeCallbacks(it) }
        run = Runnable {
            val cur = pending
            val delta = when {
                spoken.isNotEmpty() && cur.startsWith(spoken) -> cur.removePrefix(spoken)
                spoken.isNotEmpty() && similar(cur, spoken) -> ""
                else -> cur
            }.trim()
            spoken = cur
            if (delta.length > 1) {
                State.lastCaption = delta
                Speaker.enqueue(ctx.applicationContext, delta)
            }
        }
        h.postDelayed(run!!, 600)
    }
}
