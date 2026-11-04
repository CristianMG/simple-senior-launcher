package es.papa.launcher

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(val pkg: String, val label: String)

data class ContactPhone(val contactId: Long, val name: String, val phone: String)

object Actions {

    private fun intentFor(ctx: Context, pkg: String): Intent? = when (pkg) {
        Prefs.DIALER -> Intent(Intent.ACTION_DIAL)
        Prefs.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        else -> ctx.packageManager.getLaunchIntentForPackage(pkg)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Real package behind a tile (resolves the pseudo-packages). */
    fun realPackage(ctx: Context, pkg: String): String? {
        if (!pkg.startsWith("@")) return pkg
        val i = intentFor(ctx, pkg) ?: return null
        return ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean {
        if (pkg in Prefs.INTERNAL) return true
        val real = realPackage(ctx, pkg) ?: return false
        return runCatching { ctx.packageManager.getPackageInfo(real, 0); true }.getOrDefault(false)
    }

    fun icon(ctx: Context, pkg: String, sizePx: Int): Bitmap? = runCatching {
        val real = realPackage(ctx, pkg) ?: return null
        ctx.packageManager.getApplicationIcon(real).toBitmap(sizePx, sizePx)
    }.getOrNull()

    fun launch(ctx: Context, pkg: String) {
        val i = intentFor(ctx, pkg)
        if (i == null || runCatching { ctx.startActivity(i) }.isFailure) {
            toast(ctx, "Esta aplicación no está instalada")
        }
    }

    fun call(ctx: Context, phone: String) {
        val uri = Uri.fromParts("tel", phone, null)
        if (CallState.isDefaultDialer(ctx) && ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            // Default phone app may place any call, emergency numbers included.
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (runCatching { tm.placeCall(uri, android.os.Bundle()) }.isSuccess) return
        }
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { toast(ctx, "No se pudo llamar") }
    }

    /** Opens a WhatsApp chat with [phone]. Spanish numbers without prefix get +34. */
    fun whatsapp(ctx: Context, phone: String) {
        var digits = phone.filter { it.isDigit() || it == '+' }
        digits = when {
            digits.startsWith("+") -> digits.drop(1)
            digits.startsWith("00") -> digits.drop(2)
            digits.length == 9 -> "34$digits"
            else -> digits
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits"))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { toast(ctx, "WhatsApp no está disponible") }
    }

    fun search(ctx: Context, query: String) {
        if (query.isBlank()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query.trim())))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { toast(ctx, "No se pudo abrir el buscador") }
    }

    fun openSettings(ctx: Context, action: String = Settings.ACTION_SETTINGS) {
        runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(ctx, "No disponible") }
    }

    fun openMiuiLauncher(ctx: Context) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName("com.miui.home", "com.miui.home.launcher.Launcher"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { toast(ctx, "Launcher MIUI no disponible") }
    }

    fun contactPhoto(ctx: Context, contactId: Long): Bitmap? = runCatching {
        if (contactId <= 0) return null
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        ContactsContract.Contacts.openContactPhotoInputStream(ctx.contentResolver, uri, true)
            ?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    fun launchableApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun contacts(ctx: Context, filter: String): List<ContactPhone> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val sel = if (filter.isBlank()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = if (filter.isBlank()) null else arrayOf("%$filter%")
        val out = ArrayList<ContactPhone>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, sel, args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 5000) {
                out += ContactPhone(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
            }
        }
        return out.distinctBy { it.contactId to it.phone.filter(Char::isDigit).takeLast(9) }
    }

    fun isGuardEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains("${ctx.packageName}/")
    }

    fun isNotifEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        return s.contains("${ctx.packageName}/")
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
