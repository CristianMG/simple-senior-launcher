package es.papa.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private enum class Picker { NONE, APP, CONTACT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.config.collectAsState()
    var picker by remember { mutableStateOf(Picker.NONE) }
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val guardOn = remember(resumes) { Actions.isGuardEnabled(ctx) }
    val notifOn = remember(resumes) { Actions.isNotifEnabled(ctx) }
    // Real (persistent) role, not a temporary adb override.
    val dialerOn = remember(resumes) {
        if (android.os.Build.VERSION.SDK_INT >= 29)
            ctx.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true
        else CallState.isDefaultDialer(ctx)
    }
    val roleReq = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resumes++ }

    when (picker) {
        Picker.APP -> { AppPicker(onDone = { picker = Picker.NONE }); return }
        Picker.CONTACT -> { ContactPicker(onDone = { picker = Picker.NONE }); return }
        Picker.NONE -> Unit
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Cream).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Configuración", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                Button(onExit) { Text("Salir y bloquear", fontSize = 16.sp) }
            }
            Text("Se bloquea sola en 15 minutos.", color = Color.Gray)
        }

        item { Header("Estado") }
        item {
            StatusRow("Guardián (accesibilidad)", guardOn) { Actions.openSettings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            StatusRow("App de teléfono predeterminada (pantalla de llamada)", dialerOn) {
                val rm = if (android.os.Build.VERSION.SDK_INT >= 29) ctx.getSystemService(android.app.role.RoleManager::class.java) else null
                if (rm != null && android.os.Build.VERSION.SDK_INT >= 29 && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
                    roleReq.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
                } else {
                    roleReq.launch(
                        android.content.Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                            .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
                    )
                }
            }
            StatusRow("Contador de mensajes (notificaciones)", notifOn) {
                Actions.openSettings(ctx, "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
        }
        item {
            SwitchRow("Bloquear Ajustes / Play Store", cfg.guardEnabled) { v -> Prefs.update { it.copy(guardEnabled = v) } }
            SwitchRow("Bloquear persiana superior (wifi, datos, modo avión…)", cfg.blockShade) { v ->
                Prefs.update { it.copy(blockShade = v) }
            }
        }

        item { Header("Accesos") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ Actions.openSettings(ctx) }) { Text("Ajustes Android") }
                OutlinedButton({ Actions.openSettings(ctx, Settings.ACTION_HOME_SETTINGS) }) { Text("Launcher predeterminado") }
                OutlinedButton({ Actions.openMiuiLauncher(ctx) }) { Text("Abrir launcher MIUI") }
                OutlinedButton({ Actions.launch(ctx, "com.android.vending") }) { Text("Play Store") }
            }
        }

        item { Header("Aplicaciones en pantalla") }
        itemsIndexed(cfg.tiles) { i, t ->
            EditRow(
                title = t.label,
                subtitle = if (Actions.isInstalled(ctx, t.pkg)) t.pkg else "${t.pkg} (no instalada)",
                onUp = { Prefs.update { it.copy(tiles = it.tiles.move(i, -1)) } },
                onDown = { Prefs.update { it.copy(tiles = it.tiles.move(i, 1)) } },
                onDelete = { Prefs.update { it.copy(tiles = it.tiles.filterIndexed { j, _ -> j != i }) } },
            )
        }
        item { Button({ picker = Picker.APP }) { Icon(Icons.Filled.Add, null); Text(" Añadir aplicación") } }

        item { Header("Contactos favoritos") }
        itemsIndexed(cfg.favorites) { i, f ->
            EditRow(
                title = f.name, subtitle = f.phone,
                onUp = { Prefs.update { it.copy(favorites = it.favorites.move(i, -1)) } },
                onDown = { Prefs.update { it.copy(favorites = it.favorites.move(i, 1)) } },
                onDelete = { Prefs.update { it.copy(favorites = it.favorites.filterIndexed { j, _ -> j != i }) } },
            )
        }
        item { Button({ picker = Picker.CONTACT }) { Icon(Icons.Filled.PersonAdd, null); Text(" Añadir contacto") } }

        item { Header("Código") }
        item { PinEditor(cfg.pin) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

private fun <T> List<T>.move(i: Int, d: Int): List<T> {
    val j = i + d
    if (j !in indices) return this
    return toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
}

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    list: List<T>, crossinline content: @Composable (Int, T) -> Unit,
) = items(list.size) { content(it, list[it]) }

@Composable
private fun Header(t: String) =
    Text(t, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
    content = content,
)

@Composable
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (ok) Green else Color(0xFFD32F2F))
    Text(label, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 17.sp)
    if (!ok) TextButton(onFix) { Text("Activar") }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), fontSize = 17.sp)
    Switch(value, onChange)
}

@Composable
private fun EditRow(title: String, subtitle: String, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        IconButton(onUp) { Icon(Icons.Filled.ArrowUpward, "Subir") }
        IconButton(onDown) { Icon(Icons.Filled.ArrowDownward, "Bajar") }
        IconButton(onDelete) { Icon(Icons.Filled.Delete, "Quitar", tint = Color(0xFFD32F2F)) }
    }
}

@Composable
private fun PinEditor(current: String) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    Card {
        Text("Código actual: $current", color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f),
                label = { Text("Nuevo código (4-8 cifras)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Spacer(Modifier.width(8.dp))
            Button({
                if (pin.length >= 4) { Prefs.update { it.copy(pin = pin) }; pin = ""; Actions.toast(ctx, "Código cambiado") }
            }) { Text("Guardar") }
        }
    }
}

@Composable
private fun PickerScaffold(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Cream).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
            Button(onDone) { Text("Volver") }
        }
        content()
    }
}

@Composable
private fun AppPicker(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Actions.launchableApps(ctx) }
    var q by remember { mutableStateOf("") }
    PickerScaffold("Añadir aplicación", onDone) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar…") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(apps.filter { q.isBlank() || it.label.contains(q, true) }) { a ->
                Card {
                    Column(Modifier.fillMaxWidth().clickable {
                        Prefs.update { c -> c.copy(tiles = c.tiles + Tile(a.pkg, a.label)) }
                        onDone()
                    }) {
                        Text(a.label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(a.pkg, fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactPicker(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var q by remember { mutableStateOf("") }
    val list = remember(q, granted) { if (granted) Actions.contacts(ctx, q) else emptyList() }
    PickerScaffold("Añadir contacto", onDone) {
        if (!granted) {
            Button({ req.launch(Manifest.permission.READ_CONTACTS) }) { Text("Permitir acceso a contactos") }
            return@PickerScaffold
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar nombre…") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list) { c ->
                Card {
                    Column(Modifier.fillMaxWidth().clickable {
                        Prefs.update { cf -> cf.copy(favorites = cf.favorites + Favorite(c.name, c.phone, c.contactId)) }
                        onDone()
                    }) {
                        Text(c.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(c.phone, fontSize = 15.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}
