package com.bridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BridgeVpnService : VpnService() {

    private val worker: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val xrayRunning = AtomicBoolean(false)
    private var stopping = false

    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: XrayController? = null

    companion object {
        private const val CHANNEL_ID = "BridgeVpnChannel"
        private const val NOTIFICATION_ID = 1

        // Changed probe URLs to avoid blocked domains
        private val PROBE_URLS = listOf(
            "http://cp.cloudflare.com/generate_204"
        )
        
        // Increased timeout for slower connections
        private const val VERIFY_TIMEOUT_MS = 35_000
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i("BRIDGE_VPN", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("BRIDGE_VPN", "onStartCommand received")
        
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val uri = intent?.getStringExtra("uri") ?: run {
            setError("No server URI provided")
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startVpn(uri)
        
        return START_STICKY
    }

    private fun startVpn(uri: String) {
        Log.i("BRIDGE_VPN", "Starting VPN with URI")
        
        stopping = false
        BridgeVpnState.stage = BridgeVpnState.Stage.CONNECTING
        broadcastState()

        try {
            val builder = Builder()
                .setSession("BridgeVPN")
                .addAddress("10.233.233.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(false)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                setError("Failed to establish VPN interface")
                return
            }

            controller = XrayController(this)
            startXrayLoop(uri)
            scheduleConnectionVerifier()

        } catch (e: Exception) {
            Log.e("BRIDGE_VPN", "Failed to start VPN", e)
            setError("Startup failed: ${e.message}")
        }
    }

    private fun startXrayLoop(uri: String) {
        worker.execute {
            val pfdLocal = vpnInterface ?: run {
                handler.post { setError("tunFd is null before startLoop") }
                return@execute
            }
            val coreLocal = controller ?: run {
                handler.post { setError("core is null before startLoop") }
                return@execute
            }
            val configLocal = try {
                XrayConfigBuilder.buildTunnel(uri)
            } catch (t: Throwable) {
                handler.post { setError("Config build failed: ${t.message}") }
                return@execute
            }

            val fd = pfdLocal.fd
            Log.i("BRIDGE_LOOP", "fd=$fd valid=${fd > 0}")
            Log.i("BRIDGE_LOOP", "config_len=${configLocal.length}")
            
            try {
                xrayRunning.set(true)
                Log.i("BRIDGE_LOOP", "calling startLoop...")
                coreLocal.startLoop(configLocal, fd)
                Log.i("BRIDGE_LOOP", "startLoop returned (core exited)")
            } catch (t: Throwable) {
                Log.e("BRIDGE_LOOP", "startLoop threw: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                xrayRunning.set(false)
                if (!stopping) closeTunFd()
            }
            
            if (!stopping && BridgeVpnState.stage != BridgeVpnState.Stage.CONNECTED) {
                handler.post { setError("Core exited immediately | ${lastCoreLog()}") }
            }
        }
    }

    private fun scheduleConnectionVerifier() {
        worker.schedule({
            if (xrayRunning.get() && !stopping) {
                verifyConnection()
            }
        }, 3, TimeUnit.SECONDS)
    }

    private fun verifyConnection() {
        Log.i("BRIDGE_VPN", "Verifying connection")
        
        worker.execute {
            val reachable = PROBE_URLS.any { probeUrl ->
                try {
                    val conn = URL(probeUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = VERIFY_TIMEOUT_MS
                    conn.readTimeout = VERIFY_TIMEOUT_MS
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    Log.i("BRIDGE_VPN", "Probe $probeUrl returned $code")
                    code in 200..299 || code == 204
                } catch (e: IOException) {
                    Log.w("BRIDGE_VPN", "Probe $probeUrl failed: ${e.message}")
                    false
                }
            }

            handler.post {
                when {
                    reachable -> {
                        Log.i("BRIDGE_VPN", "Connection verified")
                        BridgeVpnState.stage = BridgeVpnState.Stage.CONNECTED
                        updateNotification("Connected")
                        broadcastState()
                    }
                    xrayRunning.get() -> {
                        Log.w("BRIDGE_VPN", "Probe failed but core alive - marking connected (unverified)")
                        BridgeVpnState.stage = BridgeVpnState.Stage.CONNECTED
                        updateNotification("Connected (unverified)")
                        broadcastState()
                    }
                    else -> {
                        Log.e("BRIDGE_VPN", "Connection dead")
                        setError("Connection verification failed")
                    }
                }
            }
        }
    }

    private fun stopVpn() {
        Log.i("BRIDGE_VPN", "Stopping VPN")
        stopping = true
        xrayRunning.set(false)
        
        controller?.stopLoop()
        controller = null
        
        closeTunFd()
        
        BridgeVpnState.stage = BridgeVpnState.Stage.DISCONNECTED
        broadcastState()
        
        stopForeground(true)
        stopSelf()
    }

    private fun closeTunFd() {
        vpnInterface?.let {
            try {
                it.close()
                Log.i("BRIDGE_VPN", "TUN fd closed")
            } catch (e: Exception) {
                Log.w("BRIDGE_VPN", "Error closing TUN fd", e)
            }
        }
        vpnInterface = null
    }

    private fun setError(message: String) {
        Log.e("BRIDGE_VPN", "Error: $message")
        BridgeVpnState.stage = BridgeVpnState.Stage.ERROR
        BridgeVpnState.errorMessage = message
        updateNotification("Error: $message")
        broadcastState()
        stopVpn()
    }

    private fun lastCoreLog(): String {
        return controller?.getLastLog() ?: "no log"
    }

    private fun broadcastState() {
        sendBroadcast(Intent("com.bridge.app.VPN_STATE_CHANGED"))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
                PendingIntent.FLAG_IMMUTABLE 
            else 
                0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bridge VPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_bridge_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        worker.shutdownNow()
        Log.i("BRIDGE_VPN", "Service destroyed")
    }
}
