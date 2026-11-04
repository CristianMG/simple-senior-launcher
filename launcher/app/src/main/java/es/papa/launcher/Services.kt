package es.papa.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accessibility "guardian":
 *  - Sends the user back home if a settings-like app opens while admin mode is locked.
 *  - Optionally covers the status bar with an invisible overlay so the quick-settings
 *    shade (wifi, airplane mode, data...) can't be pulled down by accident.
 */
class GuardService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var lastBlock = 0L

    private val tick = object : Runnable {
        override fun run() {
            syncOverlay()
            CallState.start(this@GuardService) // no-op once running; picks up late permission grants
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onServiceConnected() {
        Prefs.init(this)
        scope.launch { Prefs.config.collect { syncOverlay() } }
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        syncOverlay()
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        if (!Prefs.config.value.guardEnabled || Prefs.isAdmin) return
        if (pkg in BLOCKED && cls !in ALLOWED_CLASSES) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val now = System.currentTimeMillis()
            if (now - lastBlock > 4000) {
                Toast.makeText(this, "Ajustes protegidos para evitar cambios", Toast.LENGTH_LONG).show()
                lastBlock = now
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun syncOverlay() {
        val c = Prefs.config.value
        val want = c.guardEnabled && c.blockShade && !Prefs.isAdmin
        if (want && overlay == null) addOverlay() else if (!want && overlay != null) removeOverlay()
    }

    @SuppressLint("ClickableViewAccessibility", "InternalInsetResource", "DiscouragedApi")
    private fun addOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (resId > 0) resources.getDimensionPixelSize(resId) else 72
        val v = View(this).apply { setOnTouchListener { _, _ -> true } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            sb + 8,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP }
        runCatching { wm.addView(v, lp); overlay = v }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        overlay = null
    }

    companion object {
        val BLOCKED = setOf(
            "com.android.settings",
            "com.xiaomi.misettings",
            "com.miui.securitycenter",
            "com.google.android.apps.wellbeing",
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.miui.global.packageinstaller",
        )
        /** Dialogs that must keep working (e.g. bluetooth pairing for car / headset). */
        val ALLOWED_CLASSES = setOf(
            "com.android.settings.bluetooth.BluetoothPairingDialog",
        )
    }
}

/** Counts notifications per package to show badges on tiles. */
class NotifService : NotificationListenerService() {
    override fun onListenerConnected() { instance = this; recount() }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val m = HashMap<String, Int>()
        for (n in list) {
            val f = n.notification.flags
            if (f and Notification.FLAG_GROUP_SUMMARY != 0 || f and Notification.FLAG_ONGOING_EVENT != 0) continue
            val key = if (n.packageName in DIALER_PKGS) Prefs.KEYPAD else n.packageName
            m[key] = (m[key] ?: 0) + 1
        }
        Badges.counts.value = m
    }

    companion object {
        @Volatile private var instance: NotifService? = null

        /** Removes the stock "missed call" notification so the red dot goes away once seen. */
        fun dismissMissedCalls() {
            val svc = instance ?: return
            runCatching {
                svc.activeNotifications
                    .filter { it.packageName in DIALER_PKGS && it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
                    .forEach { svc.cancelNotification(it.key) }
            }
        }

        val DIALER_PKGS = setOf("com.android.server.telecom", "com.google.android.dialer", "com.android.phone")
    }
}
