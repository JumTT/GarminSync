package com.tools.garminsync.data

import android.content.Context
import com.tools.garminsync.data.crypto.Crypto
import com.tools.garminsync.data.db.AccountEntity
import com.tools.garminsync.data.db.AppDatabase
import com.tools.garminsync.data.db.SyncedActivityEntity
import com.tools.garminsync.data.remote.GarminActivity
import com.tools.garminsync.data.remote.GarminClient
import com.tools.garminsync.data.remote.GarminTokens
import com.tools.garminsync.data.remote.Region
import com.tools.garminsync.data.remote.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

enum class SyncOutcome { SUCCESS, DUPLICATE, SKIPPED }

/**
 * 同步引擎：登录校验（会话恢复 -> 静默重登 -> 要求手动登录）、
 * 拉取国际区最近活动、按 activityId 去重上传到国区
 */
class SyncRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val clients = ConcurrentHashMap<Region, GarminClient>()

    data class LoginCheck(
        val ok: Boolean,
        val username: String?,
        val displayName: String?,
        val error: String?,
    )

    data class ActivityWithSync(val act: GarminActivity, val syncedStatus: String?)

    /** 启动时校验某区域登录态：恢复会话 -> 失效则用已存密码静默重登 -> 仍失败返回 NeedLogin */
    suspend fun checkLogin(region: Region): LoginCheck = withContext(Dispatchers.IO) {
        val account = db.accountDao().get(region.name)
            ?: return@withContext LoginCheck(false, null, null, null)

        val client = GarminClient(region)

        // 1) 恢复已保存会话
        val sessionJson = account.sessionEnc?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        if (sessionJson != null) {
            runCatching {
                client.restore(GarminTokens.fromJson(sessionJson))
                client.getUserProfile()
            }.onSuccess { profile ->
                clients[region] = client
                return@withContext LoginCheck(true, account.username, displayName(profile), null)
            }
        }

        // 2) 会话失效，用已存密码静默重登
        val password = runCatching { Crypto.decrypt(account.passwordEnc) }.getOrNull()
            ?: return@withContext LoginCheck(false, account.username, null, "本地密码解密失败，请重新登录")
        runCatching {
            client.login(account.username, password)
            client.getUserProfile()
        }.fold(
            onSuccess = { profile ->
                clients[region] = client
                db.accountDao().upsert(
                    account.copy(
                        sessionEnc = Crypto.encrypt(client.tokens!!.toJson()),
                        displayName = displayName(profile),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                LoginCheck(true, account.username, displayName(profile), null)
            },
            onFailure = { e ->
                LoginCheck(false, account.username, null, e.message ?: "登录失效")
            },
        )
    }

    /** 手动登录并保存账号（密码加密落库） */
    suspend fun login(region: Region, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = GarminClient(region)
                client.login(username, password)
                val profile = client.getUserProfile()
                val name = displayName(profile)
                db.accountDao().upsert(
                    AccountEntity(
                        region = region.name,
                        username = username,
                        passwordEnc = Crypto.encrypt(password),
                        sessionEnc = Crypto.encrypt(client.tokens!!.toJson()),
                        displayName = name,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                clients[region] = client
                name
            }
        }

    suspend fun storedUsername(region: Region): String? =
        db.accountDao().get(region.name)?.username

    /** 国际区活动列表（分页）+ 本地已同步状态 */
    suspend fun fetchLatestActivities(start: Int = 0, limit: Int = 10): List<ActivityWithSync> =
        withContext(Dispatchers.IO) {
            val global = clients[Region.GLOBAL]
                ?: throw IllegalStateException("国际区未登录")
            global.getActivities(start, limit).map { act ->
                ActivityWithSync(act, db.syncedDao().get(act.activityId)?.status)
            }
        }

    /**
     * 下载 -> 解压 .fit -> 上传到国区 -> 落库
     * 去重规则：本地已有成功/重复记录且未强制时跳过；服务器 409 视为重复并落库
     */
    suspend fun uploadActivity(act: GarminActivity, force: Boolean): SyncOutcome =
        withContext(Dispatchers.IO) {
            val existing = db.syncedDao().get(act.activityId)
            if (existing != null && !force) return@withContext SyncOutcome.SKIPPED

            val cn = clients[Region.CN] ?: throw IllegalStateException("国区未登录")
            val global = clients[Region.GLOBAL] ?: throw IllegalStateException("国际区未登录")

            val zip = File(appContext.cacheDir, "act_${act.activityId}.zip")
            var fit: File? = null
            try {
                global.downloadActivityZip(act.activityId, zip)
                fit = unzipFirstFit(zip, appContext.cacheDir, act.activityId)
                    ?: throw IllegalStateException("活动压缩包中未找到 .fit 文件")
                when (val result = cn.uploadFit(fit)) {
                    is UploadResult.Success -> {
                        record(act, "SUCCESS")
                        SyncOutcome.SUCCESS
                    }
                    is UploadResult.Duplicate -> {
                        record(act, "DUPLICATE")
                        SyncOutcome.DUPLICATE
                    }
                    is UploadResult.Failed -> throw RuntimeException(result.message)
                }
            } finally {
                zip.delete()
                fit?.delete()
            }
        }

    private suspend fun record(act: GarminActivity, status: String) {
        db.syncedDao().upsert(
            SyncedActivityEntity(
                activityId = act.activityId,
                activityName = act.activityName,
                startTimeLocal = act.startTimeLocal,
                status = status,
                uploadedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun displayName(profile: JSONObject): String =
        profile.optString("fullName").ifEmpty { profile.optString("userName") }

    private fun unzipFirstFit(zip: File, outDir: File, activityId: String): File? =
        ZipInputStream(FileInputStream(zip).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.uppercase().endsWith(".FIT")) {
                    val out = File(outDir, "act_${activityId}.fit")
                    out.outputStream().use { zis.copyTo(it) }
                    return out
                }
                entry = zis.nextEntry
            }
            null
        }
}
