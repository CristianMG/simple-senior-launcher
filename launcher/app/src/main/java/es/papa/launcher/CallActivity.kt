package es.papa.launcher

import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Red = Color(0xFFD32F2F)

/** Huge, swipe-free incoming / ongoing call screen shown over the stock dialer and the lock screen. */
class CallActivity : ComponentActivity() {

    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {} // avoid closing it by accident
        })
        setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
                val info by CallState.state.collectAsState()
                LaunchedEffect(info.phase) {
                    when (info.phase) {
                        Phase.IDLE -> finish()
                        Phase.IN_CALL -> proximityOn()
                        Phase.RINGING -> Unit
                    }
                }
                if (info.phase != Phase.IDLE) CallScreen(info)
            }
        }
    }

    /** Screen turns off near the ear so the cheek can't press "Colgar". */
    @Suppress("WakelockTimeout")
    private fun proximityOn() {
        if (proximity != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "papa:call").apply { acquire() }
    }

    override fun onDestroy() {
        proximity?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

@Composable
private fun CallScreen(info: CallInfo) {
    val ctx = LocalContext.current
    val caller = remember(info.number) { CallState.lookup(ctx, info.number) }
    val photo = remember(caller.contactId) { Actions.contactPhoto(ctx, caller.contactId)?.asImageBitmap() }
    val ringing = info.phase == Phase.RINGING
    val title = caller.name ?: info.number.ifBlank { "Número oculto" }
    val own = InCall.active // we are the phone app: no stock heads-up, DTMF available
    var showKeys by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Navy).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Stock dialer shows a heads-up card at the top while ringing: keep that area empty.
        Spacer(Modifier.height(if (ringing && !own) 150.dp else 24.dp))
        Text(
            when {
                ringing -> "Te está llamando"
                !info.connected -> "Llamando a…"
                else -> "En llamada con"
            },
            color = Color(0xFFD0DCEB), fontSize = 30.sp,
        )
        if (showKeys && !ringing) {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(typed.ifEmpty { " " }, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            DtmfPad(Modifier.weight(1f)) { ch -> typed = (typed + ch).takeLast(14); CallState.dtmf(ch) }
        } else {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(if (ringing && !own) 130.dp else 170.dp).clip(CircleShape).background(Color(0xFF2E5283)), contentAlignment = Alignment.Center) {
            if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(title.take(1).uppercase(), color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 48.sp)
        if (caller.name != null) Text(info.number, color = Color(0xFFD0DCEB), fontSize = 26.sp)
        if (!ringing && info.connected) key(info.since) { Timer(info.since) }

        Spacer(Modifier.weight(1f))
        }

        if (ringing) {
            Button(
                onClick = { CallState.answer(ctx) },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(32.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Call, null, Modifier.size(56.dp))
                    Text("CONTESTAR", fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { CallState.hangUp(ctx) },
                modifier = Modifier.fillMaxWidth().height(96.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(Icons.Filled.CallEnd, null, Modifier.size(40.dp))
                Text("  No contestar", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        } else {
            val speaker = info.speaker
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { CallState.setSpeaker(ctx, !speaker) },
                    modifier = Modifier.weight(1f).height(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (speaker) Color(0xFFFFC107) else Color.White),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.VolumeUp, null, Modifier.size(36.dp), tint = Navy)
                        Text(if (speaker) "Altavoz SÍ" else "Altavoz NO", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy, maxLines = 1, softWrap = false)
                    }
                }
                if (own && info.connected) Button(
                    onClick = { showKeys = !showKeys },
                    modifier = Modifier.weight(1f).height(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (showKeys) Color(0xFFFFC107) else Color.White),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Dialpad, null, Modifier.size(36.dp), tint = Navy)
                        Text(if (showKeys) "Ocultar" else "Teclado", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { CallState.hangUp(ctx) },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(32.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CallEnd, null, Modifier.size(56.dp))
                    Text("COLGAR", fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Timer(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(s / 60, s % 60), color = Color.White, fontSize = 34.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun DtmfPad(modifier: Modifier, onKey: (Char) -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF2E5283)).clickable { onKey(ch) },
                        contentAlignment = Alignment.Center,
                    ) { Text(ch.toString(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
