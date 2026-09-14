package com.bridge.app

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Lightweight helpers for testing servers WITHOUT starting a full VPN.
 *
 * Layer 1 only: TCP reachability. This proves the host:port is open. It does
 * NOT prove the proxy (TLS / Reality / auth) works. The real proxy latency
 * test lives in the VPN service. We keep the two clearly separate.
 */
object ProxyTools {
    private const val TAG = "BRIDGE_PING"

    /**
     * Decode Base64 that may be standard OR URL-safe (many real subscription
     * providers use '-' and '_' instead of '+' and '/'). Never throws for a
     * mere alphabet mismatch.
     */
    fun robustBase64Decode(value: String): ByteArray {
        val cleaned = value.trim().replace("\n", "").replace("\r", "")
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        return try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (_: Exception) {
            try {
                java.util.Base64.getUrlDecoder().decode(padded)
            } catch (_: Exception) {
                java.util.Base64.getDecoder().decode(
                    padded.replace('-', '+').replace('_', '/')
                )
            }
        }
    }

    /** Extract (host, port) from a proxy URI for all supported protocols. */
    fun endpointFor(uri: String): Pair<String, Int>? {
        return try {
            val u = Uri.parse(uri)
            when (u.scheme?.lowercase()) {
                "vless", "trojan" ->
                    (u.host ?: return null) to if (u.port > 0) u.port else 443
                "vmess" -> {
                    val raw = uri.substringAfter("vmess://", "").substringBefore("#")
                    val json = JSONObject(robustBase64Decode(raw).toString(Charsets.UTF_8))
                    val host = json.optString("add").ifBlank { json.optString("address") }
                    if (host.isBlank()) null else host to json.optInt("port", 443)
                }
                "ss" -> {
                    val raw = uri.substringAfter("ss://", "").substringBefore("#")
                    val decoded = if (raw.contains("@")) raw
                    else robustBase64Decode(raw).toString(Charsets.UTF_8)
                    val hostPort = decoded.substringAfterLast('@')
                    val colon = hostPort.lastIndexOf(':')
                    if (colon <= 0) null
                    else hostPort.substring(0, colon).trim('[', ']') to
                        (hostPort.substring(colon + 1).toIntOrNull() ?: 443)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "endpointFor failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** TCP reachability latency in ms, or -1 if unreachable. */
    fun tcpPing(uri: String, timeoutMs: Int = 3000): Long {
        val endpoint = endpointFor(uri) ?: return -1L
        val startNs = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second), timeoutMs)
            }
            (System.nanoTime() - startNs) / 1_000_000L
        } catch (_: Exception) {
            -1L
        }
    }
}
