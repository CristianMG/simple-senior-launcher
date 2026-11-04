package es.papa.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Phase { IDLE, RINGING, IN_CALL }

data class CallInfo(val phase: Phase = Phase.IDLE, val number: String = "", val since: Long = 0L)

data class CallEntry(val number: String, val name: String?, val type: Int, val date: Long)

data class Caller(val name: String?, val contactId: Long)

object CallState {
    val state = MutableStateFlow(CallInfo())
    private var listener: PhoneStateListener? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Called from [GuardService] (always running) so the big call screen can show over everything. */
    @Suppress("DEPRECATION")
    fun start(ctx: Context) {
        if (listener != null) return
        if (!granted(ctx, Manifest.permission.READ_PHONE_STATE)) return
        val app = ctx.applicationContext
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(s: Int, number: String?) {
                val prev = state.value
                val num = number?.takeIf { it.isNotBlank() } ?: prev.number
                when (s) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        state.value = CallInfo(Phase.RINGING, num, System.currentTimeMillis())
                        showScreen(app, 0, 700, 1600)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        state.value = CallInfo(Phase.IN_CALL, num, System.currentTimeMillis())
                        showScreen(app, 300, 1200)
                    }
                    else -> state.value = CallInfo()
                }
            }
        }
        (app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        listener = l
    }

    /** Re-launches a few times so it stays above the stock dialer screen. */
    private fun showScreen(ctx: Context, vararg delays: Long) {
        for (d in delays) handler.postDelayed({
            if (state.value.phase == Phase.IDLE) return@postDelayed
            runCatching {
                ctx.startActivity(
                    Intent(ctx, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, d)
    }

    fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun answer(ctx: Context) {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) runCatching { tm.acceptRingingCall() }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun hangUp(ctx: Context) {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (Build.VERSION.SDK_INT >= 28 && granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
            runCatching { tm.endCall() }
        }
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = on
    }

    @Suppress("DEPRECATION")
    fun isSpeaker(ctx: Context) = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn

    fun lookup(ctx: Context, number: String): Caller {
        if (number.isBlank() || !granted(ctx, Manifest.permission.READ_CONTACTS)) return Caller(null, 0)
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup._ID), null, null, null,
            )?.use { c -> if (c.moveToFirst()) Caller(c.getString(0), c.getLong(1)) else null }
        }.getOrNull() ?: Caller(null, 0)
    }
}

object CallLogRepo {

    fun recent(ctx: Context, limit: Int = 40): List<CallEntry> = query(ctx, null, null, limit)

    fun missedNew(ctx: Context): List<CallEntry> = query(
        ctx, "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1",
        arrayOf(CallLog.Calls.MISSED_TYPE.toString()), 5,
    )

    private fun query(ctx: Context, sel: String?, args: Array<String>?, limit: Int): List<CallEntry> {
        if (!CallState.granted(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<CallEntry>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                sel, args, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += CallEntry(c.getString(0) ?: "", c.getString(1)?.takeIf { it.isNotBlank() }, c.getInt(2), c.getLong(3))
                }
            }
        }
        return out
    }

    /** Marks missed calls as seen and clears the red dot. */
    fun markMissedSeen(ctx: Context) {
        if (CallState.granted(ctx, Manifest.permission.WRITE_CALL_LOG)) runCatching {
            ctx.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                ContentValues().apply { put(CallLog.Calls.NEW, 0); put(CallLog.Calls.IS_READ, 1) },
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            )
        }
        NotifService.dismissMissedCalls()
    }

    fun whenText(ts: Long): String {
        val es = Locale("es", "ES")
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val today = Calendar.getInstance()
        val hm = SimpleDateFormat("HH:mm", es).format(Date(ts))
        val dayDiff = (startOfDay(today) - startOfDay(c)) / 86_400_000L
        return when (dayDiff) {
            0L -> "Hoy $hm"
            1L -> "Ayer $hm"
            else -> SimpleDateFormat("EEEE d MMM, HH:mm", es).format(Date(ts)).replaceFirstChar { it.uppercase() }
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
