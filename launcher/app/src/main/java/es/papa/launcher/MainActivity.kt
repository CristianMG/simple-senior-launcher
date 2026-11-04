package es.papa.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Navy = Color(0xFF1F3A5F)
val Cream = Color(0xFFF4F1EA)
val Green = Color(0xFF1E8E3E)
val WaGreen = Color(0xFF25D366)

class MainActivity : ComponentActivity() {

    private val screen = mutableStateOf<Screen>(Screen.Home)
    private val homeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        screen.value = screenFrom(intent)
        val missing = arrayOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG, android.Manifest.permission.ANSWER_PHONE_CALLS)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                screen.value = when (val s = screen.value) {
                    is Screen.AddContact -> if (s.phone.isEmpty()) Screen.Contacts else Screen.Keypad
                    else -> Screen.Home
                }
            }
        })
        setContent {
            // Fixed font scale: sizes below are already large; system 1.5x would break layout.
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Navy, background = Cream)) {
                    val home = { screen.value = Screen.Home }
                    when (val s = screen.value) {
                        Screen.Admin ->
                            if (Prefs.isAdmin) AdminScreen(onExit = { Prefs.lockAdmin(); home() }) else home()
                        Screen.Keypad -> KeypadScreen(
                            onBack = home, onSave = { screen.value = Screen.AddContact(it) },
                            onRecent = { screen.value = Screen.Recent },
                        )
                        Screen.Recent -> RecentCallsScreen(onBack = { screen.value = Screen.Keypad }, onSave = { screen.value = Screen.AddContact(it) })
                        Screen.Contacts -> ContactsScreen(onBack = home, onAdd = { screen.value = Screen.AddContact("") })
                        is Screen.AddContact -> AddContactScreen(
                            prefillPhone = s.phone,
                            onBack = { screen.value = if (s.phone.isEmpty()) Screen.Contacts else Screen.Keypad },
                            onSaved = { screen.value = Screen.Contacts },
                        )
                        Screen.Home -> HomeScreen(
                            homeTick.intValue,
                            onAdmin = { screen.value = Screen.Admin },
                            onOpen = { pkg ->
                                screen.value = when (pkg) {
                                    Prefs.KEYPAD -> Screen.Keypad
                                    Prefs.CONTACTS -> Screen.Contacts
                                    else -> Screen.Home
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** Debug/QA: `am start ... --es open keypad|contacts|add`. */
    private fun screenFrom(i: Intent?): Screen = when (i?.getStringExtra("open")) {
        "keypad" -> Screen.Keypad
        "contacts" -> Screen.Contacts
        "recent" -> Screen.Recent
        "add" -> Screen.AddContact(i.getStringExtra("phone").orEmpty())
        else -> Screen.Home
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while already home: reset scroll / close admin.
        screen.value = screenFrom(intent)
        homeTick.intValue++
    }
}

@Composable
fun HomeScreen(tick: Int, onAdmin: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.config.collectAsState()
    val badges by Badges.counts.collectAsState()
    var askPin by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(tick) { scroll.scrollTo(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ClockHeader(onLongPress = { askPin = true })
        val call by CallState.state.collectAsState()
        if (call.phase != Phase.IDLE) {
            BigButton("Volver a la llamada", Icons.Filled.Call, Green, Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val tiles = remember(cfg.tiles) { cfg.tiles.filter { Actions.isInstalled(ctx, it.pkg) } }
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    AppTile(t, badges[t.pkg] ?: 0, Modifier.weight(1f), onOpen)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (cfg.favorites.isNotEmpty()) {
            SectionTitle("Llamar a")
            cfg.favorites.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { FavoriteCard(it, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionTitle("Buscar")
        SearchBar()

        Spacer(Modifier.height(24.dp))
    }

    if (askPin) PinDialog(
        expected = cfg.pin,
        onDismiss = { askPin = false },
        onOk = { askPin = false; Prefs.unlockAdmin(15); onAdmin() },
    )
}

@Composable
fun ClockHeader(onLongPress: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    val battery = rememberBattery(ctx)
    val es = Locale("es", "ES")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Navy)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .padding(vertical = 10.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(SimpleDateFormat("HH:mm", es).format(now), color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Bold)
        Text(
            SimpleDateFormat("EEEE, d 'de' MMMM", es).format(now).replaceFirstChar { it.uppercase() },
            color = Color.White, fontSize = 24.sp,
        )
        val (level, charging) = battery
        val low = level in 0..20 && !charging
        Text(
            when {
                charging -> "🔌 Cargando · $level%"
                low -> "⚠ Batería baja · $level% · ¡Pon a cargar!"
                else -> "🔋 Batería $level%"
            },
            color = if (low) Color(0xFFFFC107) else Color(0xFFD0DCEB),
            fontSize = 22.sp, fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun rememberBattery(ctx: Context): Pair<Int, Boolean> {
    var state by remember { mutableStateOf(-1 to false) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                state = (lvl * 100 / scale.coerceAtLeast(1)) to
                    (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { ctx.unregisterReceiver(r) }
    }
    return state
}

@Composable
fun SearchBar() {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var q by remember { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) Actions.search(ctx, text)
    }
    fun go() { focus.clearFocus(); Actions.search(ctx, q); q = "" }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Buscar en Google", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Escribe aquí…", fontSize = 22.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 24.sp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Buscar", Icons.Filled.Search, Navy, Modifier.weight(1f)) { go() }
            BigButton("Hablar", Icons.Filled.Mic, Green, Modifier.weight(1f)) {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Di lo que quieres buscar")
                runCatching { voice.launch(i) }.onFailure { Actions.toast(ctx, "Búsqueda por voz no disponible") }
            }
        }
    }
}

@Composable
fun BigButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier, fontSize: Int = 22, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Icon(icon, null, Modifier.size(30.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

@Composable
fun FavoriteCard(f: Favorite, modifier: Modifier) {
    val ctx = LocalContext.current
    val photo = remember(f.contactId) { Actions.contactPhoto(ctx, f.contactId)?.asImageBitmap() }
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Navy).clickable { Actions.call(ctx, f.phone) },
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(f.name.take(1).uppercase(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Text(f.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        BigButton("Llamar", Icons.Filled.Call, Green, Modifier.fillMaxWidth()) { Actions.call(ctx, f.phone) }
        BigButton("WhatsApp", Icons.Filled.Chat, WaGreen, Modifier.fillMaxWidth(), fontSize = 19) { Actions.whatsapp(ctx, f.phone) }
    }
}

@Composable
fun AppTile(t: Tile, badge: Int, modifier: Modifier, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
    val icon = remember(t.pkg) { Actions.icon(ctx, t.pkg, px)?.asImageBitmap() }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(24.dp)).background(Color.White)
                .clickable { if (t.pkg in Prefs.INTERNAL) onOpen(t.pkg) else Actions.launch(ctx, t.pkg) }.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (t.pkg) {
                Prefs.KEYPAD -> InternalIcon(Icons.Filled.Dialpad, Green)
                Prefs.CONTACTS -> InternalIcon(Icons.Filled.Contacts, Navy)
                else -> if (icon != null) Image(icon, null, Modifier.size(72.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(t.label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp).clip(CircleShape).background(Color(0xFFD32F2F)),
                contentAlignment = Alignment.Center,
            ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun PinDialog(expected: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Código de configuración") },
        text = {
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); err = false },
                isError = err, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { if (err) Text("Código incorrecto") },
            )
        },
        confirmButton = { TextButton({ if (pin == expected) onOk() else err = true }) { Text("Entrar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

sealed interface Screen {
    data object Home : Screen
    data object Admin : Screen
    data object Keypad : Screen
    data object Contacts : Screen
    data object Recent : Screen
    data class AddContact(val phone: String) : Screen
}

@Composable
fun InternalIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) = Box(
    Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(color),
    contentAlignment = Alignment.Center,
) { Icon(icon, null, Modifier.size(46.dp), tint = Color.White) }
