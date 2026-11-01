package com.ski.calsync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ski.calsync.account.Accounts
import com.ski.calsync.dav.AuthException
import com.ski.calsync.dav.CalDavClient
import com.ski.calsync.dav.RemoteCalendar
import com.ski.calsync.sync.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Navy = Color(0xFF0E2A47)
private val Coral = Color(0xFFFF5A4E)
private val Blue = Color(0xFF1E7BE0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Blue, secondary = Coral, tertiary = Coral)
            else lightColorScheme(primary = Navy, secondary = Coral, tertiary = Blue)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

data class PhoneCalendar(val id: Long, val name: String, val account: String, val color: Int)

@Composable
fun App() {
    val ctx = LocalContext.current
    val perms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    var granted by remember {
        mutableStateOf(perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
    }
    var signedIn by remember { mutableStateOf(Accounts.current(ctx) != null) }

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        when {
            !granted -> PermissionScreen { launcher.launch(perms) }
            !signedIn -> LoginScreen(onDone = { signedIn = true })
            else -> HomeScreen(onSignedOut = { signedIn = false })
        }
    }
}

@Composable
private fun Header(subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Coral), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Sync, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Ski CalSync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
        Header("Samsung Calendar ⇄ Apple Calendar")
        Column(Modifier.padding(20.dp)) {
            Text("To put your iCloud events into Samsung Calendar (and send Samsung events to your iPhone), the app needs access to your phone's calendars.")
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAsk, modifier = Modifier.fillMaxWidth()) { Text("Allow calendar access") }
        }
    }
}

@Composable
private fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Connect your iCloud calendar")
        Column(Modifier.padding(horizontal = 20.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("You need an app-specific password", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Apple doesn't let other apps use your normal password. On account.apple.com go to " +
                            "Sign-In and Security → App-Specific Passwords, make one called \"Samsung\", and paste it below.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.apple.com/account/manage")))
                    }, contentPadding = PaddingValues(0.dp)) { Text("Open account.apple.com") }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = appleId, onValueChange = { appleId = it.trim() },
                label = { Text("Apple ID (email)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it.trim() },
                label = { Text("App-specific password") }, placeholder = { Text("xxxx-xxxx-xxxx-xxxx") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !busy && appleId.contains("@") && password.length >= 8,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val client = CalDavClient(appleId, password)
                                val home = client.discoverHome()
                                val cals = client.listCalendars(home)
                                Accounts.add(ctx, appleId, password, home)
                                Prefs(ctx).apply { clear(); remoteCache = cals; selectedRemote = cals.map { it.url }.toSet() }
                                Accounts.requestSync(ctx)
                            }
                        }
                        busy = false
                        result.onSuccess { onDone() }.onFailure {
                            error = if (it is AuthException) "Apple ID or app-specific password is wrong."
                            else "Couldn't reach iCloud: ${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Connect")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeScreen(onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val account = remember { Accounts.current(ctx) }

    var remotes by remember { mutableStateOf(prefs.remoteCache) }
    var selected by remember { mutableStateOf(prefs.selectedRemote) }
    var mirrorIds by remember { mutableStateOf(prefs.mirrorCalendarIds) }
    var phoneCals by remember { mutableStateOf(emptyList<PhoneCalendar>()) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(prefs.lastStatus) }
    var lastOk by remember { mutableStateOf(prefs.lastOk) }
    var lastMs by remember { mutableStateOf(prefs.lastSyncMs) }
    var showPw by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        phoneCals = withContext(Dispatchers.IO) { loadPhoneCalendars(ctx) }
        while (true) {
            syncing = Accounts.isSyncing(ctx)
            status = prefs.lastStatus; lastOk = prefs.lastOk; lastMs = prefs.lastSyncMs
            remotes = prefs.remoteCache
            if (!syncing) selected = prefs.selectedRemote
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(account?.name ?: "")

        // Status card
        Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        syncing -> Icons.Filled.Sync to MaterialTheme.colorScheme.primary
                        lastMs == 0L -> Icons.Filled.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                        lastOk -> Icons.Filled.CheckCircle to Color(0xFF2E9E5B)
                        else -> Icons.Filled.Error to MaterialTheme.colorScheme.error
                    }
                    Icon(icon, null, tint = tint)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when { syncing -> "Syncing…"; lastMs == 0L -> "Waiting for first sync"; else -> status },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (lastMs > 0) Text(
                            "Last sync " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastMs)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = { Accounts.requestSync(ctx); syncing = true }, enabled = !syncing, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sync now")
                    }
                    if (!lastOk) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { showPw = true }) { Text("New password") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Syncs automatically every 15 minutes and whenever you edit an iCloud event on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("iCloud → Samsung Calendar", "Two-way: edits on either phone sync to the other.")
        if (remotes.isEmpty()) Text("No iCloud calendars yet — tap Sync now.", Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        remotes.forEach { rc ->
            CalendarToggle(
                name = rc.name,
                sub = if (rc.readOnly) "Shared with you · view only" else "iCloud",
                color = rc.color ?: Blue.toArgb(),
                checked = rc.url in selected,
            ) { on ->
                selected = if (on) selected + rc.url else selected - rc.url
                prefs.selectedRemote = selected
                Accounts.requestSync(ctx)
            }
        }

        SectionTitle("Samsung → iPhone", "Picked calendars are copied to an iCloud calendar called \"Samsung\". Edit these on your Samsung phone.")
        val mine = phoneCals
        if (mine.isEmpty()) Text("No other calendars found on this phone.", Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        mine.forEach { pc ->
            CalendarToggle(name = pc.name, sub = pc.account, color = pc.color, checked = pc.id in mirrorIds) { on ->
                mirrorIds = if (on) mirrorIds + pc.id else mirrorIds - pc.id
                prefs.mirrorCalendarIds = mirrorIds
                Accounts.requestSync(ctx)
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { confirmOut = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text("Disconnect iCloud", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showPw) PasswordDialog(onDismiss = { showPw = false }) { pw ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { CalDavClient(account!!.name, pw).discoverHome() }.isSuccess
            }
            if (ok) { Accounts.updatePassword(ctx, account!!, pw); Accounts.requestSync(ctx); showPw = false }
            else android.widget.Toast.makeText(ctx, "That password didn't work", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false },
        title = { Text("Disconnect iCloud?") },
        text = { Text("iCloud calendars will be removed from this phone. Nothing is deleted from iCloud. The \"Samsung\" calendar on your iPhone stays as it is.") },
        confirmButton = {
            TextButton(onClick = {
                Accounts.remove(ctx); prefs.clear(); confirmOut = false; onSignedOut()
            }) { Text("Disconnect") }
        },
        dismissButton = { TextButton(onClick = { confirmOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarToggle(name: String, sub: String, color: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(color)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New app-specific password") },
        text = {
            OutlinedTextField(value = pw, onValueChange = { pw = it.trim() }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), placeholder = { Text("xxxx-xxxx-xxxx-xxxx") })
        },
        confirmButton = { TextButton(enabled = pw.length >= 8, onClick = { onSave(pw) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun loadPhoneCalendars(ctx: android.content.Context): List<PhoneCalendar> {
    val out = ArrayList<PhoneCalendar>()
    ctx.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
        ),
        "${CalendarContract.Calendars.ACCOUNT_TYPE}!=?", arrayOf(Accounts.TYPE), null,
    )?.use { c ->
        while (c.moveToNext()) {
            val type = c.getString(3) ?: ""
            val acct = c.getString(2) ?: ""
            val label = when {
                type.contains("osp", true) || type.contains("samsung", true) -> "Samsung account"
                type == "com.google" -> "Google · $acct"
                type == CalendarContract.ACCOUNT_TYPE_LOCAL || acct.isBlank() -> "On this phone"
                else -> acct
            }
            out += PhoneCalendar(c.getLong(0), c.getString(1) ?: "Calendar", label, c.getInt(4))
        }
    }
    return out
}
