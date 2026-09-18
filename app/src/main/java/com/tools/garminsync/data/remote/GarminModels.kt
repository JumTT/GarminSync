package com.tools.garminsync.data.remote

import org.json.JSONObject

/** 两个区域的域名差异：登录/API 协议完全相同 */
enum class Region(val domain: String, val label: String) {
    CN("garmin.cn", "国区"),
    GLOBAL("garmin.com", "国际区");

    val ssoOrigin: String get() = "https://sso.$domain"
    val ssoEmbed: String get() = "$ssoOrigin/sso/embed"
    val signinUrl: String get() = "$ssoOrigin/sso/signin"
    val connectModern: String get() = "https://connect.$domain/modern"
    val api: String get() = "https://connectapi.$domain"
}

data class GarminActivity(
    val activityId: String,
    val activityName: String,
    val startTimeLocal: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val typeKey: String?,
)

/** OAuth1 + OAuth2 双令牌，与 Node 库 exportToken() 的结构对齐 */
data class GarminTokens(val oauth1: JSONObject, val oauth2: JSONObject) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("oauth1", oauth1)
        obj.put("oauth2", oauth2)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): GarminTokens {
            val obj = JSONObject(json)
            return GarminTokens(obj.getJSONObject("oauth1"), obj.getJSONObject("oauth2"))
        }
    }
}

sealed class UploadResult {
    data object Success : UploadResult()
    data object Duplicate : UploadResult()
    data class Failed(val message: String) : UploadResult()
}

class GarminAuthException(message: String) : Exception(message)
