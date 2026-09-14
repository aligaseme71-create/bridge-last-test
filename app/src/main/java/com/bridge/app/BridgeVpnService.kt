package com.bridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BridgeVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.bridge.app.CONNECT"
        const val ACTION_DISCONNECT = "com.bridge.app.DISCONNECT"
        const val EXTRA_URI = "uri"

        private const val CHANNEL_ID = "bridge_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "BRIDGE_VPN"

        // افزایش تایم‌اوت: شبکه‌های محدود گاهی تا ۳۰ ثانیه لازم دارند
        private const val VERIFY_TIMEOUT_MS = 35_000L

        // چند probe به ترتیب امتحان می‌شود؛ gstatic در ایران معمولاً بلاک است
        private val PROBE_URLS = listOf(
            "http://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            "https://www.gstatic.com/generate_204"
        )
    }

    private val coreLog = ArrayDeque<String>()
    private val xrayRunning = AtomicBoolean(false)
    private val tunFdClosed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    private var core: CoreController? = null
    private var tunFd: ParcelFileDescriptor? = null

    // ---------------------------------------------------------------- core log

    private fun appendCoreLog(line: String) {
        synchronized(coreLog) {
            coreLog.addLast(line)
            while (coreLog.size > 20) coreLog.removeFirst()
        }
        Log.d(TAG, "core | $line")
    }

    private fun lastCoreLog(): String = synchronized(coreLog) {
        coreLog.toList().takeLast(5).joinToString(" | ").ifBlank { "no core log" }
    }

    // --------------------------------------------------------------- callbacks

    private val callback = object : CoreCallbackHandler {
        // شروع هسته را اینجا CONNECTED علامت نمی‌زنیم؛ اعتبارسنجی جدا انجام می‌شود
        override fun startup(): Long {
            appendCoreLog("startup callback")
            return 0L
        }

        override fun shutdown(): Long {
            appendCoreLog("shutdown callback")
            xrayRunning.set(false)
            return 0L
        }

        override fun onEmitStatus(code: Long, msg: String?): Long {
            appendCoreLog("status $code: ${msg ?: "-"}")
            return 0L
        }
    }

    // -------------------------------------------------------------- lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopTunnel()
            ACTION_CONNECT -> {
                val uri = intent.getStringExtra(EXTRA_URI)
                if (uri.isNullOrBlank()) {
                    setError("Empty server URI")
                } else {
                    worker.execute { startTunnel(uri) }
                }
            }
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onRevoke() {
        appendCoreLog("onRevoke")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(stopService = false, quiet = true)
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------- core

    private fun initializeCore(): CoreController {
        core?.let { return it }
        Seq.setContext(applicationContext)
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        val c = Libv2ray.newCoreController(callback)
        core = c
        return c
    }

    private fun startTunnel(uri: String) {
        try {
            stopTunnel(stopService = false, quiet = true)
            val c = initializeCore()

            setStage(BridgeVpnState.Stage.CONNECTING, "Connecting…")
            startBridgeForeground("Bridge connecting…")

            val config = try {
                XrayConfigBuilder.buildTunnel(uri)
            } catch (t: Throwable) {
                setError("Bad config: ${t.message}")
                return
            }
            Log.d(TAG, "config = $config")

            val builder = Builder()
                .setSession("Bridge")
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Throwable) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

            val fd = builder.establish()
            if (fd == null) {
                setError("Could not establish VPN interface")
                return
            }
            tunFd = fd
            tunFdClosed.set(false)

            Thread({
                try {
                    xrayRunning.set(true)
                    c.startLoop(config, fd.fd)
                    appendCoreLog("startLoop returned")
                } catch (t: Throwable) {
                    appendCoreLog("startLoop error: ${t.message}")
                } finally {
                    xrayRunning.set(false)
                    closeTunFd()
                }
            }, "xray-loop").start()

            // انتظار تا ۳ ثانیه برای بالا آمدن هسته
            var waited = 0
            while (!xrayRunning.get() && waited < 3000) {
                Thread.sleep(100)
                waited += 100
            }
            if (!xrayRunning.get()) {
                setError("Core did not start | ${lastCoreLog()}")
                return
            }
            Thread.sleep(2500) // warm-up

            verifyConnection(c) { xrayRunning.get() }
        } catch (t: Throwable) {
            setError("${t.message} | ${lastCoreLog()}")
        }
    }

    /**
     * اگر probe جواب داد ⇒ CONNECTED با پینگ.
     * اگر probe جواب نداد ولی هسته زنده است ⇒ CONNECTED (unverified) به جای ERROR،
     * چون در شبکه‌های فیلترشده خودِ probe ممکن است بلاک باشد.
     */
    private fun verifyConnection(c: CoreController, isRunning: () -> Boolean = { true }) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) {
                setError("Core stopped | ${lastCoreLog()}")
                return
            }
            for (url in PROBE_URLS) {
                val delay = try {
                    c.measureDelay(url)
                } catch (t: Throwable) {
                    appendCoreLog("probe $url failed: ${t.message}")
                    -1L
                }
                if (delay in 1..12_000) {
                    BridgeVpnState.latencyMs = delay
                    setStage(BridgeVpnState.Stage.CONNECTED, "Connected")
                    updateNotification("Bridge connected • $delay ms")
                    return
                }
            }
            Thread.sleep(2000)
        }

        if (isRunning()) {
            BridgeVpnState.latencyMs = -1L
            setStage(BridgeVpnState.Stage.CONNECTED, "Connected (unverified)")
            updateNotification("Bridge connected • probe blocked")
            appendCoreLog("probe failed, keeping tunnel (unverified)")
        } else {
            setError("VPN started but proxy traffic failed | ${lastCoreLog()}")
        }
    }

    // ------------------------------------------------------------------ state

    private fun setStage(stage: BridgeVpnState.Stage, message: String) {
        BridgeVpnState.stage = stage
        BridgeVpnState.message = message
    }

    private fun setError(text: String) {
        Log.e(TAG, "error: $text")
        BridgeVpnState.stage = BridgeVpnState.Stage.ERROR
        BridgeVpnState.message = text
        BridgeVpnState.latencyMs = -1L
        try {
            if (xrayRunning.get()) core?.stopLoop()
        } catch (_: Throwable) {
        }
        closeTunFd()
        Thread {
            Thread.sleep(1500)
            BridgeVpnState.reset()
        }.start()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopTunnel(stopService: Boolean = true, quiet: Boolean = false) {
        if (!quiet) setStage(BridgeVpnState.Stage.DISCONNECTING, "Disconnecting…")
        try {
            core?.stopLoop()
        } catch (t: Throwable) {
            appendCoreLog("stopLoop error: ${t.message}")
        }
        xrayRunning.set(false)
        closeTunFd()
        if (!quiet) BridgeVpnState.reset()
        if (stopService) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun closeTunFd() {
        if (tunFdClosed.compareAndSet(false, true)) {
            try {
                tunFd?.close()
            } catch (_: Throwable) {
            }
            tunFd = null
        }
    }

    // ----------------------------------------------------------- notification

    private fun startBridgeForeground(text: String) {
        ensureChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Bridge VPN",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            ensureChannel()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }
}
