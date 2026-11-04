package es.papa.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile

/**
 * Active when this app is the default phone app: Android then shows OUR call screen only
 * (no stock dialer UI to fight with). If this service ever fails, Telecom falls back to the
 * system dialer, so calls keep working.
 */
class PapaInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        InCall.service = this
    }

    override fun onDestroy() {
        if (InCall.service === this) InCall.service = null
        InCall.clear()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        InCall.add(call)
        CallState.showScreen(this, 0)
    }

    override fun onCallRemoved(call: Call) = InCall.remove(call)

    override fun onCallAudioStateChanged(audioState: CallAudioState?) = InCall.update()
}

object InCall {
    @Volatile var service: PapaInCallService? = null
    private val calls = mutableListOf<Call>()
    private val main = Handler(Looper.getMainLooper())

    val active: Boolean get() = service != null && calls.isNotEmpty()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = update()
        override fun onDetailsChanged(call: Call, details: Call.Details) = update()
    }

    fun add(call: Call) {
        calls += call
        call.registerCallback(callback)
        update()
    }

    fun remove(call: Call) {
        call.unregisterCallback(callback)
        calls.remove(call)
        update()
    }

    fun clear() {
        calls.forEach { it.unregisterCallback(callback) }
        calls.clear()
        update()
    }

    @Suppress("DEPRECATION")
    private fun Call.st(): Int = state

    private fun current(): Call? =
        calls.firstOrNull { it.st() == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.st() != Call.STATE_DISCONNECTED && it.st() != Call.STATE_DISCONNECTING }

    fun update() {
        val c = current()
        if (c == null) {
            CallState.state.value = CallInfo()
            notifyOngoing(null)
            return
        }
        // Dual SIM without a default: pick the first SIM automatically instead of a picker.
        if (c.st() == Call.STATE_SELECT_PHONE_ACCOUNT) {
            @Suppress("DEPRECATION")
            val accounts = c.details.extras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
            accounts?.firstOrNull()?.let { c.phoneAccountSelected(it, false) }
        }
        val number = c.details.handle?.schemeSpecificPart.orEmpty()
        val speaker = service?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        val prev = CallState.state.value
        val info = when (c.st()) {
            Call.STATE_RINGING -> CallInfo(Phase.RINGING, number, System.currentTimeMillis(), speaker = speaker)
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> {
                val t = c.details.connectTimeMillis.takeIf { it > 0 }
                    ?: if (prev.phase == Phase.IN_CALL && prev.connected) prev.since else System.currentTimeMillis()
                CallInfo(Phase.IN_CALL, number, t, connected = true, speaker = speaker)
            }
            else -> CallInfo(Phase.IN_CALL, number, System.currentTimeMillis(), connected = false, speaker = speaker)
        }
        CallState.state.value = info
        notifyOngoing(info)
    }

    fun answer(): Boolean {
        val c = calls.firstOrNull { it.st() == Call.STATE_RINGING } ?: return false
        c.answer(VideoProfile.STATE_AUDIO_ONLY)
        return true
    }

    fun hangUp(): Boolean {
        val c = current() ?: return false
        if (c.st() == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
        return true
    }

    fun setSpeaker(on: Boolean): Boolean {
        val s = service ?: return false
        s.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        return true
    }

    fun dtmf(ch: Char) {
        val c = current() ?: return
        c.playDtmfTone(ch)
        main.postDelayed({ c.stopDtmfTone() }, 180)
    }

    /** Small ongoing notification: tap to get back to the call screen. */
    private fun notifyOngoing(info: CallInfo?) {
        val ctx: Context = service ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (info == null || info.phase != Phase.IN_CALL) { nm.cancel(NOTIF_ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Llamada en curso", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("En llamada")
            .setContentText("Toca para volver a la llamada")
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private const val CHANNEL = "call"
    private const val NOTIF_ID = 42
}
