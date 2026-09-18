package com.tools.garminsync.data.remote

import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth 1.0a HMAC-SHA1 签名（garth 协议），用于 Garmin SSO ticket 换取令牌
 */
object OAuth1 {

    /** RFC3986 percent-encode */
    private fun pct(s: String): String = URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

    private fun pctDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    /**
     * 生成一组已签名的 oauth_* 参数
     * @param url 完整请求 URL；若带 query，query 参数会参与签名
     */
    fun signedParams(
        method: String,
        url: String,
        consumerKey: String,
        consumerSecret: String,
        tokenKey: String? = null,
        tokenSecret: String? = null,
    ): Map<String, String> {
        val uri = URI(url)
        val query = uri.rawQuery ?: ""
        val queryParams = if (query.isBlank()) {
            emptyMap()
        } else {
            query.split("&").filter { it.isNotBlank() }.associate {
                val i = it.indexOf('=')
                if (i < 0) it to "" else it.substring(0, i) to it.substring(i + 1)
            }.mapValues { pctDecode(it.value) }
        }

        val oauthParams = TreeMap(
            mapOf(
                "oauth_consumer_key" to consumerKey,
                "oauth_nonce" to nonce(),
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
                "oauth_version" to "1.0",
            ),
        )
        if (tokenKey != null) oauthParams["oauth_token"] = tokenKey

        val allParams = HashMap<String, String>(queryParams).apply { putAll(oauthParams) }

        val baseUri = "${uri.scheme}://${uri.authority}${uri.rawPath ?: "/"}"
        val paramStr = allParams.entries
            .map { pct(it.key) to pct(it.value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val baseString = "${method.uppercase()}&${pct(baseUri)}&${pct(paramStr)}"

        val signingKey = "${pct(consumerSecret)}&${pct(tokenSecret ?: "")}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.encodeToString(
            mac.doFinal(baseString.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        oauthParams["oauth_signature"] = signature
        return oauthParams
    }

    /** Authorization: OAuth ... 头 */
    fun header(
        method: String,
        url: String,
        consumerKey: String,
        consumerSecret: String,
        tokenKey: String? = null,
        tokenSecret: String? = null,
    ): String {
        val params = signedParams(method, url, consumerKey, consumerSecret, tokenKey, tokenSecret)
        return "OAuth " + params.entries.joinToString(", ") {
            "${pct(it.key)}=\"${pct(it.value)}\""
        }
    }

    fun urlEncodedParams(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${pct(it.key)}=${pct(it.value)}" }

    private fun nonce(): String {
        val random = SecureRandom()
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP).replace("+", "_").replace("/", "_")
    }
}
