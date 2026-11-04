package es.papa.launcher

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer

// ---------- data helpers ----------

object ContactsRepo {

    fun digits(s: String) = s.filter(Char::isDigit)

    /** Last 9 digits: matches "+34 600 11 22 33" with "600112233". */
    fun key(s: String) = digits(s).takeLast(9)

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Real callable numbers only (drops operator codes like *100#). */
    fun all(ctx: Context): List<ContactPhone> =
        Actions.contacts(ctx, "").filter { "*" !in it.phone && "#" !in it.phone && digits(it.phone).length >= 6 }
            .distinctBy { norm(it.name.trim()) to key(it.phone) }
            .sortedWith(compareBy({ if (it.name.firstOrNull()?.isLetter() == true) 0 else 1 }, { norm(it.name) }))

    fun findByNumber(list: List<ContactPhone>, number: String): ContactPhone? {
        val k = key(number)
        if (k.length < 6) return null
        return list.firstOrNull { key(it.phone) == k }
    }

    private fun googleAccount(ctx: Context): String? = runCatching {
        ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=?", arrayOf("com.google"), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Saves into the Google account (so it syncs/backs up); falls back to phone storage. */
    fun save(ctx: Context, name: String, phone: String): Boolean = runCatching {
        val acc = googleAccount(ctx)
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, acc?.let { "com.google" })
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, acc)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name.trim())
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone.trim())
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build(),
        )
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)
}

// ---------- shared UI ----------

@Composable
fun ScreenTop(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.height(56.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Navy, modifier = Modifier.size(28.dp))
            Text(" Volver", color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp))
    }
}

// ---------- keypad ----------

@Composable
fun KeypadScreen(onBack: () -> Unit, onSave: (String) -> Unit, onRecent: () -> Unit) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf("") }
    val contacts = remember { ContactsRepo.all(ctx) }
    val match = remember(number) { ContactsRepo.findByNumber(contacts, number) }
    var missed by remember { mutableStateOf(CallLogRepo.missedNew(ctx)) }
    fun seen() { CallLogRepo.markMissedSeen(ctx); missed = emptyList() }

    Column(Modifier.fillMaxSize().background(Cream)) {
        ScreenTop("Marcar número", onBack)
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (missed.isNotEmpty()) MissedCard(missed, contacts, onSave = onSave, onSeen = { seen() })
            OutlinedButton(
                onClick = onRecent, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.History, null, Modifier.size(28.dp), tint = Navy)
                Text("  Últimas llamadas", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
            }
            Box(
                Modifier.fillMaxWidth().height(if (missed.isEmpty()) 110.dp else 80.dp).clip(RoundedCornerShape(20.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        number.ifEmpty { "Escribe el número" },
                        fontSize = if (number.isEmpty()) 26.sp else 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (number.isEmpty()) Color.Gray else Color(0xFF111111),
                        maxLines = 1,
                    )
                    if (match != null) Text("Es: ${match.name}", fontSize = 24.sp, color = Green, fontWeight = FontWeight.Bold)
                }
            }
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "⌫")
            keys.chunked(3).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                .background(if (k == "⌫") Color(0xFFE8E2D6) else Color.White)
                                .clickable {
                                    number = when (k) {
                                        "⌫" -> number.dropLast(1)
                                        else -> (number + k).take(16)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == "⌫") Icon(Icons.AutoMirrored.Filled.Backspace, "Borrar", Modifier.size(40.dp), tint = Navy)
                            else Text(k, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Navy)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (number.isNotEmpty()) Actions.call(ctx, number) },
                    modifier = Modifier.weight(2f).height(84.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Filled.Call, null, Modifier.size(38.dp))
                    Text("  Llamar", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                if (match == null && ContactsRepo.digits(number).length >= 6) {
                    Button(
                        onClick = { onSave(number) },
                        modifier = Modifier.weight(1f).height(84.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text("Guardar\nnúmero", fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

// ---------- contact list ----------

@Composable
fun ContactsScreen(onBack: () -> Unit, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val all = remember { ContactsRepo.all(ctx) }
    var q by remember { mutableStateOf("") }
    val shown = remember(q, all) {
        val nq = ContactsRepo.norm(q.trim())
        val dq = ContactsRepo.digits(q)
        if (nq.isEmpty()) all
        else all.filter { ContactsRepo.norm(it.name).contains(nq) || (dq.length >= 3 && ContactsRepo.digits(it.phone).contains(dq)) }
    }

    Column(Modifier.fillMaxSize().background(Cream)) {
        ScreenTop("Contactos", onBack)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAdd, modifier = Modifier.fillMaxWidth().height(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy), shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.PersonAdd, null, Modifier.size(32.dp))
                Text("  Añadir contacto", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar nombre…", fontSize = 24.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(32.dp)) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Filled.Close, "Borrar", Modifier.size(32.dp)) } },
                textStyle = LocalTextStyle.current.copy(fontSize = 26.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            )
        }
        if (shown.isEmpty()) {
            Text(
                if (all.isEmpty()) "No hay contactos" else "No encuentro a \"$q\"",
                fontSize = 24.sp, color = Color.Gray, modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { "${it.contactId}-${it.phone}" }) { c -> ContactRow(c) }
        }
    }
}

@Composable
private fun ContactRow(c: ContactPhone) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(c.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(c.phone, fontSize = 20.sp, color = Color.DarkGray)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Llamar", Icons.Filled.Call, Green, Modifier.weight(1f)) { Actions.call(ctx, c.phone) }
            BigButton("WhatsApp", Icons.Filled.Chat, WaGreen, Modifier.weight(1f), fontSize = 20) { Actions.whatsapp(ctx, c.phone) }
        }
    }
}

// ---------- add contact ----------

@Composable
fun AddContactScreen(prefillPhone: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(prefillPhone) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun save() {
        val existing = ContactsRepo.findByNumber(ContactsRepo.all(ctx), phone)
        error = when {
            name.isBlank() -> "Falta el nombre"
            ContactsRepo.digits(phone).length < 6 -> "Falta el número de teléfono"
            existing != null -> "Ese número ya está guardado como «${existing.name}»"
            else -> null
        }
        if (error != null) return
        if (ContactsRepo.save(ctx, name, phone)) {
            Actions.toast(ctx, "✅ ${name.trim()} guardado")
            onSaved()
        } else error = "No se pudo guardar. Inténtalo otra vez."
    }

    Column(Modifier.fillMaxSize().background(Cream)) {
        ScreenTop("Contacto nuevo", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Nombre", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy)
            OutlinedTextField(
                value = name, onValueChange = { name = it; error = null },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Ej: Pepe taller", fontSize = 24.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 28.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Text("Teléfono", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy)
            OutlinedTextField(
                value = phone, onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }; error = null },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ej: 600 123 456", fontSize = 24.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 28.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            )
            error?.let { Text(it, color = Color(0xFFD32F2F), fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { save() }, modifier = Modifier.fillMaxWidth().height(80.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(38.dp))
                Text("  Guardar", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- missed + recent calls ----------

private fun displayName(e: CallEntry, contacts: List<ContactPhone>): String? =
    e.name ?: ContactsRepo.findByNumber(contacts, e.number)?.name

@Composable
private fun MissedCard(missed: List<CallEntry>, contacts: List<ContactPhone>, onSave: (String) -> Unit, onSeen: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFDECEA)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📵 Llamadas perdidas", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Red)
        missed.take(3).forEach { e ->
            val name = displayName(e, contacts)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name ?: e.number.ifBlank { "Número oculto" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(CallLogRepo.whenText(e.date), fontSize = 17.sp, color = Color.DarkGray)
                }
                if (e.number.isNotBlank()) {
                    if (name == null) TextButton({ onSeen(); onSave(e.number) }) { Text("Guardar", fontSize = 18.sp) }
                    Button(
                        onClick = { onSeen(); Actions.call(ctx, e.number) },
                        colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(14.dp),
                    ) { Icon(Icons.Filled.Call, null); Text(" Llamar", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        OutlinedButton(onClick = onSeen, modifier = Modifier.fillMaxWidth()) { Text("Vale, ya lo he visto", fontSize = 19.sp) }
    }
}

@Composable
fun RecentCallsScreen(onBack: () -> Unit, onSave: (String) -> Unit) {
    val ctx = LocalContext.current
    val contacts = remember { ContactsRepo.all(ctx) }
    val calls = remember { CallLogRepo.recent(ctx) }
    LaunchedEffect(Unit) { CallLogRepo.markMissedSeen(ctx) }
    Column(Modifier.fillMaxSize().background(Cream)) {
        ScreenTop("Últimas llamadas", onBack)
        if (calls.isEmpty()) Text("No hay llamadas", fontSize = 24.sp, color = Color.Gray, modifier = Modifier.padding(20.dp))
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(calls) { e ->
                val name = displayName(e, contacts)
                val (label, color) = when (e.type) {
                    android.provider.CallLog.Calls.MISSED_TYPE, android.provider.CallLog.Calls.REJECTED_TYPE -> "📵 Perdida" to Red
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "↗ Hecha por ti" to Navy
                    else -> "↙ Recibida" to Green
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(name ?: e.number.ifBlank { "Número oculto" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$label · ${CallLogRepo.whenText(e.date)}", fontSize = 18.sp, color = color, fontWeight = FontWeight.Bold)
                    if (e.number.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("Llamar", Icons.Filled.Call, Green, Modifier.weight(1f)) { Actions.call(ctx, e.number) }
                        if (name == null) BigButton("Guardar", Icons.Filled.PersonAdd, Navy, Modifier.weight(1f)) { onSave(e.number) }
                    }
                }
            }
        }
    }
}
