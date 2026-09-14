package com.bridge.app

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object XrayConfigBuilder {

    fun buildTunnel(rawUri: String): String {
        val uri = Uri.parse(rawUri.trim())

        val inbound = JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "dokodemo-door")
            put("listen", "0.0.0.0")
            put("port", 0)
            put("settings", JSONObject().apply {
                put("network", "tcp,udp")
                put("followRedirect", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        }

        val root = JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            // اجازه به entrypoint بدون TLS (VLESS/plain)
            put("policy", JSONObject().put("system", JSONObject().apply {
                put("allowInsecureEntrypoint", true)
                put("statsOutboundUplink", false)
                put("statsOutboundDownlink", false)
            }))
            put("inbounds", JSONArray().put(inbound))
            put("outbounds", JSONArray().apply {
                put(outboundFor(uri))
                put(JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
                put(JSONObject().apply {
                    put("tag", "block")
                    put("protocol", "blackhole")
                })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                }))
            })
        }
        return root.toString()
    }

    // -------------------------------------------------------------- outbounds

    private fun outboundFor(u: Uri): JSONObject = when (u.scheme?.lowercase()) {
        "vless" -> buildVless(u)
        "vmess" -> buildVmess(u)
        "trojan" -> buildTrojan(u)
        "ss" -> buildShadowsocks(u)
        else -> throw IllegalArgumentException("Unsupported scheme: ${u.scheme}")
    }

    private fun buildVless(u: Uri): JSONObject {
        val q = query(u)
        val id = decode(u.userInfo ?: throw IllegalArgumentException("Missing VLESS uuid"))
        val host = u.host ?: throw IllegalArgumentException("Missing host")
        val port = if (u.port > 0) u.port else 443

        val user = JSONObject().apply {
            put("id", id)
            put("encryption", "none")
            q["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().apply {
                    put("address", host)
                    put("port", port)
                    put("users", JSONArray().put(user))
                }
            )))
            put("streamSettings", stream(q, host))
        }
    }

    private fun buildTrojan(u: Uri): JSONObject {
        val q = query(u)
        val password = decode(u.userInfo ?: throw IllegalArgumentException("Missing password"))
        val host = u.host ?: throw IllegalArgumentException("Missing host")
        val port = if (u.port > 0) u.port else 443
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().apply {
                    put("address", host)
                    put("port", port)
                    put("password", password)
                }
            )))
            put("streamSettings", stream(q, host))
        }
    }

    private fun buildVmess(u: Uri): JSONObject {
        val payload = (u.schemeSpecificPart ?: "").removePrefix("//").trim()
        val json = JSONObject(String(ProxyTools.robustBase64Decode(payload)))
        val host = json.optString("add")
        val port = json.optString("port", "443").toIntOrNull() ?: 443

        val q = HashMap<String, String>()
        json.optString("net").takeIf { it.isNotBlank() }?.let { q["type"] = it }
        json.optString("tls").takeIf { it.isNotBlank() }?.let { q["security"] = it }
        json.optString("sni").takeIf { it.isNotBlank() }?.let { q["sni"] = it }
        json.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
        json.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
        json.optString("fp").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
        json.optString("type").takeIf { it.isNotBlank() }?.let { q["headerType"] = it }

        val user = JSONObject().apply {
            put("id", json.optString("id"))
            put("alterId", json.optString("aid", "0").toIntOrNull() ?: 0)
            put("security", json.optString("scy").ifBlank { "auto" })
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().apply {
                    put("address", host)
                    put("port", port)
                    put("users", JSONArray().put(user))
                }
            )))
            put("streamSettings", stream(q, host))
        }
    }

    private fun buildShadowsocks(u: Uri): JSONObject {
        val host = u.host ?: throw IllegalArgumentException("Missing host")
        val port = if (u.port > 0) u.port else 443
        val info = u.userInfo ?: throw IllegalArgumentException("Missing SS credentials")
        val decoded = if (info.contains(":")) decode(info)
        else String(ProxyTools.robustBase64Decode(info))
        val method = decoded.substringBefore(":")
        val password = decoded.substringAfter(":")
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().apply {
                    put("address", host)
                    put("port", port)
                    put("method", method)
                    put("password", password)
                }
            )))
            put("streamSettings", stream(query(u), host))
        }
    }

    // ----------------------------------------------------------------- stream

    private fun stream(q: Map<String, String>, defaultSni: String): JSONObject {
        val network = q["type"]?.takeIf { it.isNotBlank() }
            ?: q["network"]?.takeIf { it.isNotBlank() }
            ?: "tcp"

        // اصلاح مهم: security خالی («security=») باید none شود، نه رشته خالی
        val rawSecurity = q["security"]?.takeIf { it.isNotBlank() }?.lowercase() ?: "none"
        val security = if (rawSecurity == "tls" || rawSecurity == "reality") rawSecurity else "none"

        val host = q["host"]?.takeIf { it.isNotBlank() } ?: q["hostHeader"]?.takeIf { it.isNotBlank() }
        val path = q["path"]?.takeIf { it.isNotBlank() } ?: "/"
        val sni = q["sni"]?.takeIf { it.isNotBlank() } ?: host ?: defaultSni

        val s = JSONObject().apply {
            put("network", network)
            put("security", security)
        }

        when (network.lowercase()) {
            "ws" -> s.put("wsSettings", JSONObject().apply {
                put("path", path)
                host?.let { put("headers", JSONObject().put("Host", it)) }
            })
            "grpc" -> s.put("grpcSettings", JSONObject().apply {
                put("serviceName", q["serviceName"] ?: "")
                put("multiMode", q["mode"] == "multi")
            })
            "http", "h2" -> s.put("httpSettings", JSONObject().apply {
                put("path", path)
                host?.let { put("host", JSONArray().put(it)) }
            })
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().apply {
                put("path", path)
                host?.let { put("host", it) }
            })
            "xhttp" -> s.put("xhttpSettings", JSONObject().apply {
                put("path", path)
                host?.let { put("host", it) }
                q["mode"]?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                q["extra"]?.takeIf { it.isNotBlank() }?.let { put("extra", it) }
            })
            "tcp" -> {
                // اصلاح: obfs فقط وقتی headerType واقعاً http است
                val headerType = q["headerType"]?.lowercase()
                if (headerType == "http" && host != null) {
                    s.put("tcpSettings", JSONObject().put("header", JSONObject().apply {
                        put("type", "http")
                        put("request", JSONObject().apply {
                            put("path", JSONArray().put(path))
                            put("headers", JSONObject().put("Host", JSONArray().put(host)))
                        })
                    }))
                } else {
                    s.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "none")))
                }
            }
        }

        if (security == "tls") {
            s.put("tlsSettings", JSONObject().apply {
                put("serverName", sni)
                put("allowInsecure", q["allowInsecure"] == "1" || q["allowInsecure"] == "true")
                q["fp"]?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                    put("alpn", JSONArray().apply { alpn.split(",").forEach { put(it.trim()) } })
                }
            })
        } else if (security == "reality") {
            s.put("realitySettings", JSONObject().apply {
                put("serverName", sni)
                put("fingerprint", q["fp"]?.takeIf { it.isNotBlank() } ?: "chrome")
                put("publicKey", q["pbk"] ?: "")
                put("shortId", q["sid"] ?: "")
                put("spiderX", q["spx"] ?: "")
            })
        }
        return s
    }

    // ---------------------------------------------------------------- helpers

    private fun query(u: Uri): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            for (name in u.queryParameterNames) {
                u.getQueryParameter(name)?.let { map[name] = it }
            }
        } catch (_: Throwable) {
        }
        return map
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }
}
