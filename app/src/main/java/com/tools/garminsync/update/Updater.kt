package com.tools.garminsync.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val notes: String,
)

/**
 * 应用内更新：GitHub Releases 检查 + APK 下载 + 拉起系统安装。
 * 只匹配正式版（releases/latest 不含 prerelease），以 versionCode 数值比较大小。
 */
object Updater {

    private const val REPO = "koyoter/GarminSync"
    private const val APK_PREFIX = "GarminSync-"
    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK = "last_check_at"
    private val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 距上次成功检查是否已超过 24h（启动检测节流） */
    fun shouldCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    /** 检查更新；有新版本返回信息，否则 null。失败抛异常，由调用方决定是否静默。 */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val release = JSONObject(resp.body?.string().orEmpty())
            val tag = release.getString("tag_name").removePrefix("v")
            val code = semverToCode(tag)
            val asset = release.getJSONArray("assets").let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull {
                        it.getString("name").startsWith(APK_PREFIX) && it.getString("name").endsWith(".apk")
                    }
            } ?: error("Release v$tag 中未找到 APK 资产")
            val info = UpdateInfo(
                versionName = tag,
                versionCode = code,
                downloadUrl = asset.getString("browser_download_url"),
                notes = release.optString("body").take(300),
            )
            // 成功才记录时间戳，失败下次启动自动重试
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (code > currentVersionCode(context)) info else null
        }
    }

    /** 下载 APK 到 cacheDir/updates（先清理旧文件），onProgress 上报 0-100；协程取消即中断 */
    suspend fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val dest = File(dir, "$APK_PREFIX${info.versionName}.apk")
            val req = Request.Builder().url(info.downloadUrl).build()
            client.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body ?: error("空响应体")
                val total = body.contentLength()
                dest.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(8 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = src.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            dest
        }

    // ---------------- 安装 ----------------

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳转系统“安装未知应用”授权页，授权后需再次点击安装 */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // ---------------- 工具 ----------------

    fun currentVersionCode(context: Context): Int {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(pi).toInt()
    }

    /** "1.2.3-beta.1" -> M*10000 + m*100 + p（与 CI 公式一致，忽略预发布后缀） */
    fun semverToCode(tag: String): Int {
        val core = tag.substringBefore('-')
        val parts = core.split('.')
        require(parts.size >= 2) { "非法版本号: $tag" }
        val patch = parts.getOrNull(2)?.toInt() ?: 0
        return parts[0].toInt() * 10000 + parts[1].toInt() * 100 + patch
    }
}
