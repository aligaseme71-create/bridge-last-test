package com.bridge.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object XrayConfigBuilder {

    fun buildTunnel(uri: String): String {
        val profile = ProxyTools.parseUri(uri)
        
        val config = JSONObject()
        
        // Log
        config.put("log", JSONObject().apply {
            put("loglevel", "debug")
        })
        
        // Inbounds
        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "tun-in")
                put("protocol", "dokodemo-door")
                put("settings", JSONObject().apply {
                    put("network", "tcp,udp")
                    put("followRedirect", true)
                })
            })
        })
        
        // Outbounds
        val outbounds = JSONArray()
        
        when (profile.type.lowercase()) {
            "vmess" -> outbounds.put(buildVmessOutbound(profile))
            "vless" -> outbounds.put(buildVlessOutbound(profile))
            "trojan" -> outbounds.put(buildTrojanOutbound(profile))
            "shadowsocks", "ss" -> outbounds.put(buildShadowsocksOutbound(profile))
            else -> throw IllegalArgumentException("Unsupported protocol: ${profile.type}")
        }
        
        config.put("outbounds", outbounds)
        
        // Routing
        config.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray())
        })
        
        return config.toString()
    }

    private fun buildVmessOutbound(p: ServerProfile): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", p.address)
                        put("port", p.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", p.id)
                                put("alterId", p.alterId)
                                put("security", if (p.security.isNullOrBlank()) "auto" else p.security)
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStreamSettings(p))
        }
    }

    private fun buildVlessOutbound(p: ServerProfile): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", p.address)
                        put("port", p.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", p.id)
                                put("encryption", "none")
                                p.flow?.let { if (it.isNotBlank()) put("flow", it) }
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStreamSettings(p))
        }
    }

    private fun buildTrojanOutbound(p: ServerProfile): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", p.address)
                        put("port", p.port)
                        put("password", p.id)
                    })
                })
            })
            put("streamSettings", buildStreamSettings(p))
        }
    }

    private fun buildShadowsocksOutbound(p: ServerProfile): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", p.address)
                        put("port", p.port)
                        put("method", p.security ?: "aes-256-gcm")
                        put("password", p.id)
                    })
                })
            })
            put("streamSettings", buildStreamSettings(p))
        }
    }

    private fun buildStreamSettings(p: ServerProfile): JSONObject {
        val stream = JSONObject()
        
        stream.put("network", p.network ?: "tcp")
        
        val securityValue = when {
            p.tls == true -> "tls"
            p.security?.lowercase() == "tls" -> "tls"
            p.security?.lowercase() == "reality" -> "reality"
            else -> "none"
        }
        stream.put("security", securityValue)
        
        if (securityValue == "tls") {
            stream.put("tlsSettings", JSONObject().apply {
                p.sni?.let { if (it.isNotBlank()) put("serverName", it) }
                p.alpn?.let { if (it.isNotBlank()) put("alpn", JSONArray(it.split(","))) }
                p.fp?.let { if (it.isNotBlank()) put("fingerprint", it) }
                put("allowInsecure", p.allowInsecure ?: false)
            })
        }
        
        if (securityValue == "reality") {
            stream.put("realitySettings", JSONObject().apply {
                p.sni?.let { if (it.isNotBlank()) put("serverName", it) }
                p.pbk?.let { if (it.isNotBlank()) put("publicKey", it) }
                p.sid?.let { if (it.isNotBlank()) put("shortId", it) }
                p.fp?.let { if (it.isNotBlank()) put("fingerprint", it) }
            })
        }
        
        when (p.network?.lowercase()) {
            "ws" -> {
                stream.put("wsSettings", JSONObject().apply {
                    p.path?.let { if (it.isNotBlank()) put("path", it) }
                    p.host?.let { host ->
                        if (host.isNotBlank()) {
                            put("headers", JSONObject().apply {
                                put("Host", host)
                            })
                        }
                    }
                })
            }
            "grpc" -> {
                stream.put("grpcSettings", JSONObject().apply {
                    p.path?.let { if (it.isNotBlank()) put("serviceName", it) }
                    put("multiMode", p.mode == "multi")
                })
            }
            "h2", "http" -> {
                stream.put("httpSettings", JSONObject().apply {
                    p.path?.let { if (it.isNotBlank()) put("path", it) }
                    p.host?.let { host ->
                        if (host.isNotBlank()) {
                            put("host", JSONArray(host.split(",")))
                        }
                    }
                })
            }
            "tcp" -> {
                val hasValidHost = !p.host.isNullOrBlank()
                if (p.headerType == "http" && hasValidHost) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("version", "1.1")
                                put("method", "GET")
                                put("path", JSONArray().apply {
                                    put(p.path ?: "/")
                                })
                                put("headers", JSONObject().apply {
                                    put("Host", JSONArray(p.host!!.split(",")))
                                    put("User-Agent", JSONArray().apply {
                                        put("Mozilla/5.0")
                                    })
                                    put("Accept-Encoding", JSONArray().apply {
                                        put("gzip, deflate")
                                    })
                                    put("Connection", JSONArray().apply {
                                        put("keep-alive")
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }
        
        return stream
    }
}
