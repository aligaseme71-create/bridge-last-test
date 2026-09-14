package com.bridge.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit

// ---- palette --------------------------------------------------------------
private val Navy = Color(0xFF050D18)
private val DeepNavy = Color(0xFF02070F)
private val Panel = Color(0xE60C1E30)
private val PanelLight = Color(0xFFFFFFFF)
private val LightBg = Color(0xFFEEF3F9)
private val Blue = Color(0xFF2EA8FF)
private val Cyan = Color(0xFF35E4FF)
private val Green = Color(0xFF22D66B)
private val Red = Color(0xFFFF4D4D)
private val Muted = Color(0xFF8FA6BD)
private val LightText = Color(0xFF0E1F33)
private val LightMuted = Color(0xFF5A7089)

private const val APP_VERSION = "0.4.0"
private const val MIN_ANDROID = "Android 8.0 (API 26)"

private enum class Tab { HOME, SERVERS, SETTINGS }

class MainActivity : ComponentActivity() {
    private var pendingUri: String? = null

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) pendingUri?.let(::startVpn)
            else BridgeVpnState.message = "VPN permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BridgeApp(::requestVpn, ::stopVpn) }
    }

    private fun requestVpn(uri: String) {
        pendingUri = uri
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpn(uri)
    }

    private fun startVpn(uri: String) {
        val intent = Intent(this, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_CONNECT
            putExtra(BridgeVpnService.EXTRA_URI, uri)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        startService(Intent(this, BridgeVpnService::class.java)
            .setAction(BridgeVpnService.ACTION_DISCONNECT))
    }
}

@Composable
private fun BridgeApp(onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("bridge", Context.MODE_PRIVATE) }

    var stage by remember { mutableStateOf(BridgeVpnState.stage) }
    var coreMessage by remember { mutableStateOf(BridgeVpnState.message) }
    var latency by remember { mutableStateOf(BridgeVpnState.latencyMs) }

    var tab by remember { mutableStateOf(Tab.HOME) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var autoUpdate by remember { mutableStateOf(prefs.getBoolean("auto_update_sub", true)) }
    var subUrl by remember { mutableStateOf(prefs.getString("subscription_url", "").orEmpty()) }
    var servers by remember { mutableStateOf(loadSavedServers(prefs)) }
    // Selection is NOT restored on a fresh launch (per requirement).
    var selected by remember { mutableStateOf(-1) }
    var testing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val connected = stage == BridgeVpnState.Stage.CONNECTED
    val connecting = stage == BridgeVpnState.Stage.CONNECTING

    // Poll global VPN state.
    LaunchedEffect(Unit) {
        while (true) {
            stage = BridgeVpnState.stage
            coreMessage = BridgeVpnState.message
            latency = BridgeVpnState.latencyMs
            if (coreMessage.isNotBlank() && coreMessage != "Disconnected") message = coreMessage
            delay(250)
        }
    }

    // Auto-refresh subscription on launch, if enabled and a URL exists.
    LaunchedEffect(Unit) {
        if (autoUpdate && subUrl.isNotBlank()) {
            val fresh = withContext(Dispatchers.IO) { SubscriptionLoader.load(subUrl) }
            if (fresh.isNotEmpty()) {
                servers = fresh
                saveServers(prefs, fresh)
                message = "Subscription updated (${fresh.size})"
            }
        }
    }

    fun connectFastest() {
        if (servers.isEmpty() || testing) {
            if (servers.isEmpty()) { tab = Tab.SETTINGS; message = "Add a subscription first." }
            return
        }
        testing = true
        message = "Testing servers..."
        val snapshot = servers
        scope.launch(Dispatchers.IO) {
            val results = snapshot.map { p -> async { ProxyTools.tcpPing(p.uri) } }.awaitAll()
            val refreshed = snapshot.mapIndexed { i, p -> p.copy(latency = results.getOrElse(i) { -1L }) }
            val best = refreshed.indices
                .filter { refreshed[it].latency >= 0L }
                .minByOrNull { refreshed[it].latency }
            withContext(Dispatchers.Main) {
                servers = refreshed
                saveServers(prefs, refreshed)
                testing = false
                if (best != null) {
                    selected = best
                    message = "Fastest: ${refreshed[best].name} • ${refreshed[best].latency} ms"
                    onConnect(refreshed[best].uri)
                } else message = "No reachable server was found."
            }
        }
    }

    fun toggleConnection() {
        when {
            connected || connecting -> { onDisconnect(); message = "Disconnecting..." }
            else -> {
                val server = servers.getOrNull(selected)
                if (server != null) {
                    message = "Connecting to ${server.name}..."
                    onConnect(server.uri)
                } else connectFastest()
            }
        }
    }

    fun pingAll() {
        if (servers.isEmpty() || testing) return
        testing = true
        message = "Pinging all servers..."
        val snapshot = servers
        scope.launch(Dispatchers.IO) {
            val results = snapshot.map { p -> async { ProxyTools.tcpPing(p.uri) } }.awaitAll()
            val refreshed = snapshot.mapIndexed { i, p -> p.copy(latency = results.getOrElse(i) { -1L }) }
            withContext(Dispatchers.Main) {
                servers = refreshed
                saveServers(prefs, refreshed)
                testing = false
                val ok = refreshed.count { it.latency >= 0 }
                message = "$ok of ${refreshed.size} reachable"
            }
        }
    }

    fun pingOne(index: Int) {
        if (index !in servers.indices || testing) return
        testing = true
        val profile = servers[index]
        message = "Pinging ${profile.name}..."
        scope.launch(Dispatchers.IO) {
            val result = ProxyTools.tcpPing(profile.uri)
            withContext(Dispatchers.Main) {
                servers = servers.toMutableList().also { it[index] = profile.copy(latency = result) }
                saveServers(prefs, servers)
                testing = false
                message = if (result >= 0) "${profile.name}: $result ms" else "${profile.name}: unreachable"
            }
        }
    }

    fun importSubscription() {
        if (subUrl.isBlank() || importing) {
            if (subUrl.isBlank()) message = "Enter a subscription URL first."
            return
        }
        scope.launch {
            importing = true
            message = "Loading subscription..."
            val result = withContext(Dispatchers.IO) { SubscriptionLoader.load(subUrl) }
            if (result.isNotEmpty()) {
                servers = result
                selected = -1
                prefs.edit().putString("subscription_url", subUrl).apply()
                saveServers(prefs, result)
                message = "${result.size} servers imported successfully."
                tab = Tab.SERVERS
            } else message = "No supported profiles found."
            importing = false
        }
    }

    val scheme = if (darkMode)
        darkColorScheme(background = Navy, surface = Panel, primary = Blue)
    else
        lightColorScheme(background = LightBg, surface = PanelLight, primary = Blue)

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = if (darkMode) DeepNavy else PanelLight) {
                    NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME },
                        icon = { Text("⌂", fontSize = 18.sp) }, label = { Text("Home") })
                    NavigationBarItem(tab == Tab.SERVERS, { tab = Tab.SERVERS },
                        icon = { Text("≋", fontSize = 18.sp) }, label = { Text("Servers") })
                    NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS },
                        icon = { Text("⚙", fontSize = 18.sp) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        darkMode, connected, connecting, testing, message,
                        servers.getOrNull(selected), latency,
                        onToggle = ::toggleConnection,
                        onServers = { tab = Tab.SERVERS }
                    )
                    Tab.SERVERS -> ServersScreen(
                        darkMode, servers, selected, testing,
                        onSelect = { selected = it },
                        onPingOne = ::pingOne,
                        onPingAll = ::pingAll,
                        onFastestConnect = ::connectFastest
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        darkMode, subUrl, importing, message, servers.size, autoUpdate,
                        onUrl = { subUrl = it; prefs.edit().putString("subscription_url", it).apply() },
                        onImport = ::importSubscription,
                        onTheme = { darkMode = !darkMode; prefs.edit().putBoolean("dark_mode", darkMode).apply() },
                        onAutoUpdate = { autoUpdate = it; prefs.edit().putBoolean("auto_update_sub", it).apply() }
                    )
                }
            }
        }
    }
}

// ---- Home -----------------------------------------------------------------
@Composable
private fun HomeScreen(
    darkMode: Boolean,
    connected: Boolean,
    connecting: Boolean,
    testing: Boolean,
    message: String,
    server: ServerProfile?,
    latency: Long,
    onToggle: () -> Unit,
    onServers: () -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    val busy = connecting || testing

    Box(Modifier.fillMaxSize()) {
        BridgeBackdrop(darkMode)
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Bridge", color = text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(if (darkMode) "◐" else "◑", color = text, fontSize = 20.sp)
            }
            Text("SECURE • PRIVATE • GLOBAL", color = muted, fontSize = 9.sp, letterSpacing = 1.6.sp)
            Spacer(Modifier.weight(1f))
            PowerButton(connected, busy, onToggle)
            Spacer(Modifier.height(18.dp))
            Text(
                when {
                    connected -> "CONNECTED"
                    connecting -> "CONNECTING"
                    testing -> "TESTING SERVERS"
                    else -> "DISCONNECTED"
                },
                color = when { connected -> Green; busy -> Blue; else -> Red },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
            Text(
                when {
                    connected -> "Secure tunnel is active"
                    connecting -> "Establishing connection..."
                    testing -> "Finding the fastest server..."
                    else -> "Tap to connect"
                },
                color = muted, fontSize = 12.sp
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(message, color = if (connected) Green else if (busy) Blue else muted,
                    fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Card(
                Modifier.fillMaxWidth().clickable(onClick = onServers),
                colors = CardDefaults.cardColors(if (darkMode) Panel else PanelLight),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server?.name ?: "Auto Select", color = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(server?.protocol ?: "Fastest available server", color = muted, fontSize = 12.sp)
                    }
                    Text("›", color = muted, fontSize = 26.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                StatCard("PING", if (connected && latency >= 0) "$latency ms"
                    else server?.let { if (it.latency >= 0) "${it.latency} ms" else "--" } ?: "--", darkMode, Modifier.weight(1f))
                StatCard("STATUS", if (connected) "ON" else "OFF", darkMode, Modifier.weight(1f))
                StatCard("PROTO", server?.protocol ?: "--", darkMode, Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PowerButton(connected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val accent = when { connected -> Green; busy -> Blue; else -> Red }
    // Pulsing halo while busy.
    val pulse by animateFloatAsState(
        targetValue = if (busy) 1f else 0f,
        animationSpec = tween(900), label = "pulse"
    )
    Box(
        Modifier.size(230.dp).clip(CircleShape).clickable(enabled = true, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .36f
            // Outer halo (breathes when busy).
            drawCircle(accent.copy(alpha = .10f + pulse * .12f), radius * (1.35f + pulse * .12f), center)
            drawCircle(accent.copy(alpha = .16f), radius * 1.18f, center)
            drawCircle(Color(0xFF06131F), radius * .98f, center)
            drawCircle(accent, radius, style = Stroke(width = 7.dp.toPx()), center = center)
            // Power glyph.
            val p = radius * .34f
            drawArc(accent, -50f, 280f, false,
                Offset(center.x - p, center.y - p), Size(p * 2f, p * 2f),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            drawLine(accent, Offset(center.x, center.y - p * 1.15f), Offset(center.x, center.y + p * .15f),
                8.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(
            when { connected -> "TAP TO STOP"; busy -> "..."; else -> "TAP TO CONNECT" },
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp
        )
    }
}

@Composable
private fun BridgeBackdrop(darkMode: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        if (darkMode) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0A2A47), Navy, DeepNavy)))
            val center = Offset(size.width * .5f, size.height * .22f)
            drawCircle(Color(0x2200BFFF), size.width * .5f, center)
            for (i in 0..24) {
                val x = size.width * i / 24f
                drawCircle(Color(0x5528BFFF), 2f, Offset(x, size.height * (.10f + (i % 6) * .018f)))
            }
        } else {
            drawRect(Brush.verticalGradient(listOf(Color.White, LightBg)))
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, darkMode: Boolean, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(if (darkMode) Panel else PanelLight), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = if (darkMode) Muted else LightMuted, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(3.dp))
            Text(value, color = if (darkMode) Color.White else LightText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---- Servers --------------------------------------------------------------
@Composable
private fun ServersScreen(
    darkMode: Boolean,
    servers: List<ServerProfile>,
    selected: Int,
    testing: Boolean,
    onSelect: (Int) -> Unit,
    onPingOne: (Int) -> Unit,
    onPingAll: () -> Unit,
    onFastestConnect: () -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Servers", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("${servers.size} servers", color = muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            Button(onPingAll, Modifier.weight(1f), enabled = servers.isNotEmpty() && !testing) { Text("PING ALL") }
            Button(onFastestConnect, Modifier.weight(1f), enabled = servers.isNotEmpty() && !testing) { Text("FASTEST + CONNECT") }
        }
        Spacer(Modifier.height(10.dp))
        if (testing) {
            Row(Modifier.fillMaxWidth(), Arrangement.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(servers) { index, server ->
                val isSel = index == selected
                Card(
                    Modifier.fillMaxWidth().border(1.5.dp, if (isSel) Blue else Color.Transparent, RoundedCornerShape(15.dp)),
                    colors = CardDefaults.cardColors(if (darkMode) Panel else PanelLight),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onSelect(index) }) {
                            Text(server.name, color = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(server.protocol, color = muted, fontSize = 11.sp)
                            Text(
                                if (server.latency >= 0) "Ping ${server.latency} ms" else "Ping --",
                                color = if (server.latency in 0..150) Green
                                    else if (server.latency > 150) Cyan else muted,
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton({ onPingOne(index) }, enabled = !testing) { Text("PING") }
                    }
                }
            }
        }
    }
}

// ---- Settings -------------------------------------------------------------
@Composable
private fun SettingsScreen(
    darkMode: Boolean,
    url: String,
    importing: Boolean,
    message: String,
    serverCount: Int,
    autoUpdate: Boolean,
    onUrl: (String) -> Unit,
    onImport: () -> Unit,
    onTheme: () -> Unit,
    onAutoUpdate: (Boolean) -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Settings", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        Text("SUBSCRIPTION", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Subscription URL") })
        Spacer(Modifier.height(8.dp))
        Button(onImport, Modifier.fillMaxWidth().height(48.dp), enabled = !importing) {
            if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("IMPORT / UPDATE SERVERS", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text("Saved servers: $serverCount", color = muted, fontSize = 12.sp)

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-update on launch", color = text, fontSize = 14.sp)
                Text("Refresh servers when the app opens", color = muted, fontSize = 11.sp)
            }
            Switch(autoUpdate, onAutoUpdate)
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = if (message.contains("success", true) || message.contains("updated", true)) Green
                else if (message.contains("No", true)) Red else Blue, fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text("APPEARANCE", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onTheme, Modifier.fillMaxWidth()) { Text(if (darkMode) "SWITCH TO LIGHT MODE" else "SWITCH TO DARK MODE") }

        Spacer(Modifier.height(20.dp))
        Text("PREMIUM", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(if (darkMode) Panel else PanelLight), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Bridge Pro", color = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Premium servers, coming soon.", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ }, Modifier.fillMaxWidth(), enabled = false) { Text("ENTER ACTIVATION CODE") }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("ABOUT", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text("Bridge v$APP_VERSION", color = text, fontSize = 13.sp)
        Text("Supports $MIN_ANDROID and above", color = muted, fontSize = 11.sp)
        Text("Support: support@bridge.app", color = muted, fontSize = 11.sp)
    }
}

// ---- Subscription loading -------------------------------------------------
object SubscriptionLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun load(url: String): List<ServerProfile> {
        return try {
            val request = Request.Builder().url(url.trim()).header("User-Agent", "Bridge/1.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                decodeSubscription(body).lineSequence()
                    .map { it.trim() }
                    .filter {
                        it.startsWith("vless://", true) || it.startsWith("vmess://", true) ||
                        it.startsWith("trojan://", true) || it.startsWith("ss://", true)
                    }
                    .mapIndexed { index, uri ->
                        val u = try { Uri.parse(uri) } catch (_: Exception) { null }
                        val protocol = uri.substringBefore("://").uppercase()
                        val name = u?.fragment?.let { decodeText(it) }?.takeIf { it.isNotBlank() }
                            ?: "Server ${index + 1}"
                        ServerProfile(name, uri, protocol)
                    }
                    .toList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun decodeSubscription(body: String): String {
        val trimmed = body.trim()
        if (trimmed.contains("://")) return trimmed.replace("\r", "")
        val compact = trimmed.replace("\r", "").replace("\n", "")
        val padded = compact.padEnd((compact.length + 3) / 4 * 4, '=')
        val attempts = listOf<() -> String>(
            { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) },
            { String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8) },
            { String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8) },
            { String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE), Charsets.UTF_8) }
        )
        for (attempt in attempts) {
            val r = try { attempt() } catch (_: Exception) { null }
            if (r != null && r.contains("://")) return r
        }
        return trimmed
    }

    private fun decodeText(value: String): String =
        try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
}

private fun loadSavedServers(prefs: SharedPreferences): List<ServerProfile> {
    val raw = prefs.getString("servers", "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split("\n").mapNotNull { line ->
        val parts = line.split("|", limit = 4)
        if (parts.size < 4) null
        else ServerProfile(parts[0], parts[2], parts[1], parts[3].toLongOrNull() ?: -1L)
    }
}

private fun saveServers(prefs: SharedPreferences, servers: List<ServerProfile>) {
    val raw = servers.joinToString("\n") { "${it.name}|${it.protocol}|${it.uri}|${it.latency}" }
    prefs.edit().putString("servers", raw).apply()
}
