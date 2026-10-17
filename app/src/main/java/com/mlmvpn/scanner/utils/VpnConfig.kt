package com.mlmvpn.scanner.utils

import android.net.Uri

data class VpnConfig(
    var protocol: String = "vless",
    var name: String = "",
    var address: String = "",
    var port: Int = 443,
    var uuid: String = "",
    var flow: String = "",
    var network: String = "tcp",
    var wsHost: String = "",
    var wsPath: String = "",
    var xhttpPath: String = "",
    var xhttpHost: String = "",
    var xhttpMode: String = "auto",
    var xhttpExtra: String = "",
    var serviceName: String = "",
    var tls: String = "tls",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = "",
    // ── vmess / shadowsocks ────────────────────────────────────────────────────────────────
    // Both protocols carry their credentials differently from vless/trojan, which put
    // everything in the URI's userinfo. vmess ships a base64 JSON blob and shadowsocks a
    // method:password pair, so they need fields of their own rather than being squeezed into
    // `uuid`. Empty for every other protocol.
    var method: String = "",          // ss cipher, e.g. "aes-256-gcm"
    var password: String = "",        // ss password
    var alterId: Int = 0,             // vmess aid — 0 on everything modern
    var vmessSecurity: String = "auto",
    /**
     * The share link exactly as it arrived.
     *
     * vmess and ss links are not reconstructable from these fields: a vmess link is a base64
     * JSON object whose key order and extra keys we do not model, and an ss link has two
     * incompatible spellings in the wild. [toUriString] hands this back verbatim for those,
     * so a config that came in runnable goes back out runnable instead of being re-spelled
     * into something the core rejects.
     */
    var rawUri: String = ""
) {
    companion object {
        fun parseUri(uriString: String): VpnConfig? {
            try {
                val config = VpnConfig()
                val uri = Uri.parse(uriString)
                config.protocol = uri.scheme?.lowercase() ?: return null
                // vmess and ss are not URIs in the same sense -- neither has a parseable
                // authority -- so they get their own readers before the generic path below.
                if (config.protocol == "vmess") return parseVmess(uriString)
                if (config.protocol == "ss") return parseShadowsocks(uriString)
                if (config.protocol != "vless" && config.protocol != "trojan") return null
                config.rawUri = uriString

                val userInfo = uri.userInfo
                if (userInfo != null) config.uuid = userInfo
                
                config.address = uri.host ?: ""
                config.port = if (uri.port > 0) uri.port else 443
                
                config.name = uri.fragment?.let { android.net.Uri.decode(it) } ?: ""
                
                config.network = uri.getQueryParameter("type") ?: "tcp"
                config.wsHost = uri.getQueryParameter("host") ?: ""
                config.wsPath = uri.getQueryParameter("path") ?: ""
                config.xhttpPath = uri.getQueryParameter("path") ?: ""
                config.xhttpHost = uri.getQueryParameter("host") ?: ""
                config.xhttpMode = uri.getQueryParameter("mode") ?: "auto"
                config.xhttpExtra = uri.getQueryParameter("extra") ?: ""
                config.serviceName = uri.getQueryParameter("serviceName") ?: ""
                config.tls = uri.getQueryParameter("security") ?: ""
                config.sni = uri.getQueryParameter("sni") ?: ""
                config.alpn = uri.getQueryParameter("alpn") ?: ""
                config.fingerprint = uri.getQueryParameter("fp") ?: ""
                config.flow = uri.getQueryParameter("flow") ?: ""
                config.publicKey = uri.getQueryParameter("pbk") ?: ""
                config.shortId = uri.getQueryParameter("sid") ?: ""
                config.spiderX = uri.getQueryParameter("spx") ?: ""

                return config
            } catch (e: Exception) {
                return null
            }
        }

        /** Anything with a `#remark`, decoded, or "" when there is none. */
        private fun fragmentOf(uriString: String): String {
            val hash = uriString.indexOf('#')
            if (hash == -1) return ""
            return try {
                Uri.decode(uriString.substring(hash + 1))
            } catch (e: Exception) {
                uriString.substring(hash + 1)
            }
        }

        /** Tolerant base64: these links appear both padded and unpadded, URL-safe and not. */
        private fun decodeB64(raw: String): String? {
            val cleaned = raw.trim().replace("-", "+").replace("_", "/").replace(Regex("\\s"), "")
            val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
            return try {
                String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * `vmess://<base64 of a JSON object>`.
         *
         * The JSON uses one-or-two-letter keys (`add`, `id`, `net`, `tls`, ...) and every value
         * may arrive as a string or a number depending on which client wrote it, so ports and
         * alterId are read as strings and coerced.
         */
        private fun parseVmess(uriString: String): VpnConfig? {
            return try {
                val body = uriString.substringAfter("vmess://").substringBefore('#')
                val json = org.json.JSONObject(decodeB64(body) ?: return null)
                val config = VpnConfig()
                config.protocol = "vmess"
                config.rawUri = uriString
                config.address = json.optString("add").ifBlank { return null }
                config.port = json.optString("port").toIntOrNull() ?: json.optInt("port", 0)
                if (config.port <= 0) return null
                config.uuid = json.optString("id").ifBlank { return null }
                config.alterId = json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0)
                config.vmessSecurity = json.optString("scy").ifBlank { "auto" }
                config.network = json.optString("net").ifBlank { "tcp" }
                config.wsHost = json.optString("host")
                config.wsPath = json.optString("path")
                config.xhttpHost = config.wsHost
                config.xhttpPath = config.wsPath
                config.serviceName = json.optString("path")   // grpc puts serviceName in `path`
                // `tls` is "tls" or empty; a few writers use "none" explicitly.
                config.tls = json.optString("tls").let { if (it == "none") "" else it }
                config.sni = json.optString("sni").ifBlank { config.wsHost }
                config.alpn = json.optString("alpn")
                config.fingerprint = json.optString("fp")
                config.name = fragmentOf(uriString).ifBlank { json.optString("ps") }
                config
            } catch (e: Exception) {
                null
            }
        }

        /**
         * `ss://` in both spellings found in the wild:
         *   - legacy: the whole `method:password@host:port` is one base64 blob
         *   - SIP002: only `method:password` is base64, the host is plain
         */
        private fun parseShadowsocks(uriString: String): VpnConfig? {
            return try {
                val body = uriString.substringAfter("ss://").substringBefore('#')
                val config = VpnConfig()
                config.protocol = "ss"
                config.rawUri = uriString
                config.name = fragmentOf(uriString)

                val at = body.lastIndexOf('@')
                val credentials: String
                val endpoint: String
                if (at == -1) {
                    // Legacy: everything is in one blob.
                    val decoded = decodeB64(body.substringBefore('?')) ?: return null
                    val split = decoded.lastIndexOf('@')
                    if (split == -1) return null
                    credentials = decoded.substring(0, split)
                    endpoint = decoded.substring(split + 1)
                } else {
                    // SIP002: userinfo may be base64 or, rarely, already plain.
                    val userInfo = body.substring(0, at)
                    credentials = decodeB64(userInfo) ?: userInfo
                    endpoint = body.substring(at + 1).substringBefore('?')
                }

                val colon = credentials.indexOf(':')
                if (colon == -1) return null
                config.method = credentials.substring(0, colon)
                config.password = credentials.substring(colon + 1)

                val portSep = endpoint.lastIndexOf(':')
                if (portSep == -1) return null
                config.address = endpoint.substring(0, portSep).trim('[', ']')
                config.port = endpoint.substring(portSep + 1).toIntOrNull() ?: return null
                if (config.address.isBlank() || config.port <= 0) return null

                // Plugins (obfs, v2ray-plugin) need a transport this app does not build here;
                // accepting them would produce a config the core starts and cannot use.
                if (body.contains("plugin=")) return null

                config.network = "tcp"
                config.tls = ""
                config
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun toUriString(): String {
        // vmess and ss cannot be rebuilt from these fields without risking a link the core
        // rejects -- see [rawUri]. Hand back what came in.
        if ((protocol == "vmess" || protocol == "ss") && rawUri.isNotEmpty()) return rawUri

        val builder = Uri.Builder()
            .scheme(protocol)
            .encodedAuthority("$uuid@$address:$port")
        
        if (network.isNotEmpty()) builder.appendQueryParameter("type", network)
        if (network == "xhttp") {
            if (xhttpHost.isNotEmpty()) builder.appendQueryParameter("host", xhttpHost)
            if (xhttpPath.isNotEmpty()) builder.appendQueryParameter("path", xhttpPath)
            if (xhttpMode != "auto" && xhttpMode.isNotEmpty()) builder.appendQueryParameter("mode", xhttpMode)
            if (xhttpExtra.isNotEmpty()) builder.appendQueryParameter("extra", xhttpExtra)
        } else if (network == "grpc") {
            if (serviceName.isNotEmpty()) builder.appendQueryParameter("serviceName", serviceName)
        } else {
            if (wsHost.isNotEmpty()) builder.appendQueryParameter("host", wsHost)
            if (wsPath.isNotEmpty()) builder.appendQueryParameter("path", wsPath)
        }
        if (tls.isNotEmpty()) builder.appendQueryParameter("security", tls)
        if (sni.isNotEmpty()) builder.appendQueryParameter("sni", sni)
        if (alpn.isNotEmpty()) builder.appendQueryParameter("alpn", alpn)
        if (fingerprint.isNotEmpty()) builder.appendQueryParameter("fp", fingerprint)
        if (protocol == "vless" && flow.isNotEmpty()) builder.appendQueryParameter("flow", flow)
        if (tls == "reality") {
            if (publicKey.isNotEmpty()) builder.appendQueryParameter("pbk", publicKey)
            if (shortId.isNotEmpty()) builder.appendQueryParameter("sid", shortId)
            if (spiderX.isNotEmpty()) builder.appendQueryParameter("spx", spiderX)
        }
        if (protocol == "vless") builder.appendQueryParameter("encryption", "none")
        
        builder.fragment(name)
        
        return builder.build().toString().replace("%40", "@")
    }
}
