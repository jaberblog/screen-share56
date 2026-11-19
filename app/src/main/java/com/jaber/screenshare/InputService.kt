package com.jaber.screenshare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import kotlin.math.hypot

/**
 * اجرای لمس و دکمه‌ها روی گوشی. بدون روت، فقط سرویس دسترسی‌پذیری می‌تواند این کار را بکند.
 * مختصات از مرورگر به‌صورت نرمال (۰ تا ۱) می‌آید و اینجا به پیکسل واقعی تبدیل می‌شود.
 */
class InputService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: InputService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ gestures

    /** p = [[x,y],...] نرمال، d = مدت کل حرکت به میلی‌ثانیه. */
    fun gesture(p: JSONArray, durMs: Long) {
        val n = p.length()
        if (n == 0) return
        val sz = Util.realSize(this)
        val w = sz.x.toFloat()
        val h = sz.y.toFloat()

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val pt = p.getJSONArray(i)
            xs[i] = (pt.getDouble(0) * w).toFloat().coerceIn(1f, w - 2f)
            ys[i] = (pt.getDouble(1) * h).toFloat().coerceIn(1f, h - 2f)
        }

        var maxDist = 0f
        for (i in 1 until n) {
            maxDist = maxOf(maxDist, hypot(xs[i] - xs[0], ys[i] - ys[0]))
        }
        val slop = 10f * resources.displayMetrics.density

        val path = Path()
        path.moveTo(xs[0], ys[0])
        val dur: Long
        if (maxDist < slop) {
            // ضربه یا نگه‌داشتن طولانی
            dur = if (durMs < 400) 50L else durMs.coerceAtMost(1500L)
        } else {
            for (i in 1 until n) path.lineTo(xs[i], ys[i])
            dur = durMs.coerceIn(40L, 4000L)
        }

        main.post {
            try {
                val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            } catch (_: Exception) {
            }
        }
    }

    fun action(name: String) {
        main.post {
            when (name) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notif" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quick" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "wake" -> wake()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "screenshare:wake"
            )
            wl.acquire(3000)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ typing

    fun typeText(s: String) {
        if (s.isEmpty()) return
        main.post {
            val node = findInput() ?: return@post
            val cur = currentText(node)
            val a = node.textSelectionStart.let { if (it in 0..cur.length) it else cur.length }
            val b = node.textSelectionEnd.let { if (it in a..cur.length) it else a }
            setText(node, cur.substring(0, a) + s + cur.substring(b), a + s.length)
        }
    }

    fun backspace() {
        main.post {
            val node = findInput() ?: return@post
            val cur = currentText(node)
            val a = node.textSelectionStart.let { if (it in 0..cur.length) it else cur.length }
            val b = node.textSelectionEnd.let { if (it in a..cur.length) it else a }
            if (b > a) {
                setText(node, cur.substring(0, a) + cur.substring(b), a)
            } else if (a > 0) {
                setText(node, cur.substring(0, a - 1) + cur.substring(a), a - 1)
            }
        }
    }

    fun enter() {
        main.post {
            val node = findInput() ?: return@post
            if (Build.VERSION.SDK_INT >= 30) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else if (node.isMultiLine) {
                val cur = currentText(node)
                val a = node.textSelectionStart.let { if (it in 0..cur.length) it else cur.length }
                val b = node.textSelectionEnd.let { if (it in a..cur.length) it else a }
                setText(node, cur.substring(0, a) + "\n" + cur.substring(b), a + 1)
            }
        }
    }

    private fun findInput(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
    }

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, caret: Int) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }
}
