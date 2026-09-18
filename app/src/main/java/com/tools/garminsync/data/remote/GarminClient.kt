package com.tools.garminsync.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Garmin Connect 客户端，协议与 @gooin/garmin-connect 对齐：
 * 登录：SSO 表单(3步) -> ticket -> OAuth1.0a preauthorize -> exchange OAuth2
 * API：Bearer OAuth2 access_token；401 时用 OAuth1 重新 exchange
 */
class GarminClient(private val region: Region) {

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val http = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                cookieStore.entries
                    .filter { url.host.endsWith(it.key.removePrefix(".")) }
                    .flatMap { it.value }
                    .filter { it.matches(url) }
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var tokens: GarminTokens? = null

    private var consumerKey: String? = null
    private var consumerSecret: String? = null

    fun restore(saved: GarminTokens) {
        tokens = saved
    }

    // ---------------- 登录 ----------------

    fun login(username: String, password: String) {
        // Step1: 访问 sso embed 设置 cookie
        exec(
            Request.Builder()
                .url(region.ssoEmbed.toHttpUrl().newBuilder()
                    .addQueryParameter("clientId", "GarminConnect")
                    .addQueryParameter("locale", "en")
                    .addQueryParameter("service", region.connectModern)
                    .build())
                .header("User-Agent", BROWSER_UA)
                .build(),
        ).close()

        // Step2: 取 _csrf
        val step2Url = region.signinUrl.toHttpUrl().newBuilder()
            .addQueryParameter("id", "gauth-widget")
            .addQueryParameter("embedWidget", "true")
            .addQueryParameter("locale", "en")
            .addQueryParameter("gauthHost", region.ssoEmbed)
            .build()
        val step2Html = bodyOf(exec(Request.Builder().url(step2Url).header("User-Agent", BROWSER_UA).build()))
        val csrf = CSRF_RE.find(step2Html)?.groupValues?.get(1)
            ?: throw GarminAuthException("登录页解析失败：未找到 CSRF token")

        // Step3: 提交账号密码，取 ticket
        val step3Url = region.signinUrl.toHttpUrl().newBuilder()
            .addQueryParameter("id", "gauth-widget")
            .addQueryParameter("embedWidget", "true")
            .addQueryParameter("clientId", "GarminConnect")
            .addQueryParameter("locale", "en")
            .addQueryParameter("gauthHost", region.ssoEmbed)
            .addQueryParameter("service", region.ssoEmbed)
            .addQueryParameter("source", region.ssoEmbed)
            .addQueryParameter("redirectAfterAccountLoginUrl", region.ssoEmbed)
            .addQueryParameter("redirectAfterAccountCreationUrl", region.ssoEmbed)
            .build()
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("embed", "true")
            .add("_csrf", csrf)
            .build()
        val step3Html = bodyOf(
            exec(
                Request.Builder()
                    .url(step3Url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Origin", region.ssoOrigin)
                    .header("Referer", region.signinUrl)
                    .post(form)
                    .build(),
            ),
        )
        ACCOUNT_LOCKED_RE.find(step3Html)?.let {
            throw GarminAuthException("登录失败（账号状态异常/被锁定）：${it.groupValues[1]}")
        }
        val ticket = TICKET_RE.find(step3Html)?.groupValues?.get(1)
            ?: throw GarminAuthException("登录失败：未获取到 ticket（请检查账号密码，或账号开启了 MFA）")

        // Step4: ticket -> OAuth1
        fetchConsumer()
        val ck = consumerKey!!
        val cs = consumerSecret!!
        val preUrl = "${region.api}/oauth-service/oauth/preauthorized".toHttpUrl().newBuilder()
            .addQueryParameter("ticket", ticket)
            .addQueryParameter("login-url", region.ssoEmbed)
            .addQueryParameter("accepts-mfa-tokens", "true")
            .build()
        val preBody = bodyOf(
            exec(
                Request.Builder()
                    .url(preUrl)
                    .header(
                        "Authorization",
                        OAuth1.header("GET", preUrl.toString(), ck, cs),
                    )
                    .header("User-Agent", MOBILE_UA)
                    .build(),
            ),
        )
        val o1 = parseUrlEncoded(preBody)
        val oauth1Token = o1["oauth_token"]
            ?: throw GarminAuthException("OAuth1 换取失败：$preBody")
        val oauth1Secret = o1["oauth_token_secret"]
            ?: throw GarminAuthException("OAuth1 换取失败：$preBody")

        // Step5: OAuth1 -> OAuth2
        val oauth2 = exchange(oauth1Token, oauth1Secret)
        tokens = GarminTokens(
            oauth1 = JSONObject().put("oauth_token", oauth1Token).put("oauth_token_secret", oauth1Secret),
            oauth2 = oauth2,
        )
    }

    /** 用保存的 OAuth1 重新换取 OAuth2（token 过期自动续期） */
    fun refreshOauth2() {
        val current = tokens ?: throw GarminAuthException("未登录，无法刷新令牌")
        fetchConsumer()
        val oauth2 = exchange(
            current.oauth1.getString("oauth_token"),
            current.oauth1.getString("oauth_token_secret"),
        )
        tokens = GarminTokens(current.oauth1, oauth2)
    }

    private fun exchange(oauth1Token: String, oauth1Secret: String): JSONObject {
        val baseUrl = "${region.api}/oauth-service/oauth/exchange/user/2.0"
        val signed = OAuth1.signedParams("POST", baseUrl, consumerKey!!, consumerSecret!!, oauth1Token, oauth1Secret)
        val url = "$baseUrl?${OAuth1.urlEncodedParams(signed)}"
        val resp = exec(
            Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                // 与 Node 库/garth 一致：空 body 也必须带 form Content-Type，否则服务端 415
                .post("".toRequestBody(FORM_URL_ENCODED.toMediaType()))
                .build(),
        )
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw GarminAuthException("OAuth2 exchange 失败 HTTP ${it.code}: ${text.take(300)}")
            val json = JSONObject(text)
            json.put("expires_at", System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600L))
            return json
        }
    }

    private fun fetchConsumer() {
        if (consumerKey != null && consumerSecret != null) return
        val json = JSONObject(bodyOf(exec(Request.Builder().url(OAUTH_CONSUMER_URL).build())))
        consumerKey = json.getString("consumer_key")
        consumerSecret = json.getString("consumer_secret")
    }

    // ---------------- API ----------------

    fun getUserProfile(): JSONObject = authorized { token ->
        Request.Builder()
            .url("${region.api}/userprofile-service/socialProfile")
            .header("Authorization", "Bearer $token")
            .build()
    }.use { JSONObject(it.body!!.string()) }

    fun getActivities(start: Int, limit: Int): List<GarminActivity> = authorized { token ->
        Request.Builder()
            .url("${region.api}/activitylist-service/activities/search/activities?start=$start&limit=$limit")
            .header("Authorization", "Bearer $token")
            .build()
    }.use { resp ->
        val arr = JSONArray(resp.body!!.string())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GarminActivity(
                activityId = o.getString("activityId"),
                activityName = o.optString("activityName", "(未命名活动)"),
                startTimeLocal = o.optString("startTimeLocal", ""),
                distanceMeters = o.optDouble("distance", 0.0),
                durationSeconds = o.optDouble("duration", 0.0),
                typeKey = o.optJSONObject("activityType")?.optString("typeKey"),
            )
        }
    }

    fun downloadActivityZip(activityId: String, destFile: File) {
        authorized { token ->
            Request.Builder()
                .url("${region.api}/download-service/files/activity/$activityId")
                .header("Authorization", "Bearer $token")
                .build()
        }.use { resp ->
            destFile.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
    }

    fun uploadFit(file: File): UploadResult {
        val mt = "application/octet-stream".toMediaType()
        fun buildRequest(token: String): Request {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("userfile", file.name, file.asRequestBody(mt))
                .build()
            return Request.Builder()
                .url("${region.api}/upload-service/upload/.fit")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        }

        var resp = exec(buildRequest(tokens!!.oauth2.getString("access_token")))
        if (resp.code == 401) {
            resp.close()
            refreshOauth2()
            resp = exec(buildRequest(tokens!!.oauth2.getString("access_token")))
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            return when {
                it.code == 409 -> UploadResult.Duplicate
                it.isSuccessful -> {
                    val failures = runCatching {
                        JSONObject(text).optJSONObject("detailedImportResult")?.optJSONArray("failures")
                    }.getOrNull()
                    if (failures != null && failures.length() > 0) {
                        UploadResult.Failed("导入失败：${failures.getJSONObject(0).optString("messages")}")
                    } else {
                        UploadResult.Success
                    }
                }
                else -> UploadResult.Failed("HTTP ${it.code}: ${text.take(300)}")
            }
        }
    }

    // ---------------- 内部 ----------------

    /** 带自动续期的已授权请求：401 -> refreshOauth2 -> 重试一次。失败时抛异常，成功时返回未关闭的响应（调用方负责 use） */
    private fun authorized(buildRequest: (String) -> Request): Response {
        val current = tokens ?: throw GarminAuthException("未登录")
        var resp = exec(buildRequest(current.oauth2.getString("access_token")))
        if (resp.code == 401) {
            resp.close()
            refreshOauth2()
            resp = exec(buildRequest(tokens!!.oauth2.getString("access_token")))
        }
        if (!resp.isSuccessful) {
            val text = resp.use { it.body?.string().orEmpty() }
            throw RuntimeException("HTTP ${resp.code}: ${text.take(300)}")
        }
        return resp
    }

    private fun exec(request: Request): Response =
        http.newCall(request).execute()

    private fun bodyOf(resp: Response): String = resp.use { it.body?.string().orEmpty() }

    private fun parseUrlEncoded(text: String): Map<String, String> =
        text.split("&").filter { it.contains('=') }.associate {
            val i = it.indexOf('=')
            it.substring(0, i) to it.substring(i + 1)
        }

    companion object {
        private val CSRF_RE = Regex("name=\"_csrf\"\\s+value=\"(.+?)\"")
        private val TICKET_RE = Regex("ticket=([^\"&]+)")
        private val ACCOUNT_LOCKED_RE = Regex("var status\\s*=\\s*\"([^\"]*)\"")
        private const val OAUTH_CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"
        private const val FORM_URL_ENCODED = "application/x-www-form-urlencoded"
        private const val BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val MOBILE_UA = "com.garmin.android.apps.connectmobile"
    }
}
