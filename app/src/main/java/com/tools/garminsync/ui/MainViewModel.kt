package com.tools.garminsync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tools.garminsync.data.SyncOutcome
import com.tools.garminsync.data.SyncRepository
import com.tools.garminsync.data.remote.GarminActivity
import com.tools.garminsync.data.remote.Region
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class RegionState { CHECKING, OK, NEED_LOGIN }
enum class UploadUi { IDLE, WAITING, UPLOADING, SUCCESS, DUPLICATE, FAILED }

data class ActivityUi(
    val act: GarminActivity,
    val syncedStatus: String?, // null | "SUCCESS" | "DUPLICATE"（来自本地去重表）
    val checked: Boolean,
    val uploadState: UploadUi,
    val error: String?,
)

data class WellnessUi(
    val date: String,          // "yyyy-MM-dd"
    val fileCount: Int?,       // 本地记录的文件数（未同步为 null）
    val syncedStatus: String?, // null | "SUCCESS" | "DUPLICATE"（来自本地去重表）
    val checked: Boolean,
    val uploadState: UploadUi,
    val error: String?,
)

data class UiState(
    val loading: Boolean = true,
    val cnState: RegionState = RegionState.CHECKING,
    val globalState: RegionState = RegionState.CHECKING,
    val cnName: String? = null,
    val globalName: String? = null,
    val loginRegion: Region? = null,
    val loginPrefill: String? = null,
    val loginError: String? = null,
    val loginBusy: Boolean = false,
    val activities: List<ActivityUi> = emptyList(),
    val globalError: String? = null,
    val loadingMore: Boolean = false,
    val uploading: Boolean = false,
    val uploadDone: Int = 0,
    val uploadTotal: Int = 0,
    val wellness: List<WellnessUi> = emptyList(),
    val wellnessError: String? = null,
    val wellnessLoadingMore: Boolean = false,
    val wellnessUploading: Boolean = false,
    val wellnessDone: Int = 0,
    val wellnessTotal: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SyncRepository(app)
    private val _ui = MutableStateFlow(UiState())
    val uiState = _ui.asStateFlow()

    /** 当前已加载的活动条数（分页游标） */
    private var loadedCount = 0

    /** 当前已加载的健康数据天数（分页游标，含今天） */
    private var loadedWellnessDays = 0

    init {
        recheck()
    }

    /** 启动/手动刷新：校验两个区域登录态，都有效则拉取最近活动列表 */
    fun recheck() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, globalError = null) }
            val cn = repo.checkLogin(Region.CN)
            val global = repo.checkLogin(Region.GLOBAL)
            _ui.update {
                it.copy(
                    loading = false,
                    cnState = if (cn.ok) RegionState.OK else RegionState.NEED_LOGIN,
                    cnName = cn.displayName,
                    globalState = if (global.ok) RegionState.OK else RegionState.NEED_LOGIN,
                    globalName = global.displayName,
                    loginRegion = when {
                        !cn.ok -> Region.CN
                        !global.ok -> Region.GLOBAL
                        else -> null
                    },
                    loginPrefill = if (!cn.ok) cn.username else if (!global.ok) global.username else null,
                    loginError = if (!cn.ok) cn.error else if (!global.ok) global.error else null,
                )
            }
            if (cn.ok && global.ok) {
                loadActivities()
                loadWellness()
            }
        }
    }

    /** 从主页手动打开某区域的登录页 */
    fun startLogin(region: Region) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    loginRegion = region,
                    loginPrefill = repo.storedUsername(region),
                    loginError = null,
                )
            }
        }
    }

    fun login(username: String, password: String) {
        val region = _ui.value.loginRegion ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loginBusy = true, loginError = null) }
            repo.login(region, username.trim(), password)
                .onSuccess { name ->
                    _ui.update { s ->
                        when (region) {
                            Region.CN -> s.copy(
                                cnState = RegionState.OK, cnName = name, loginBusy = false,
                                loginRegion = if (s.globalState != RegionState.OK) Region.GLOBAL else null,
                                loginPrefill = if (s.globalState != RegionState.OK) repo.storedUsername(Region.GLOBAL) else null,
                                loginError = null,
                            )
                            Region.GLOBAL -> s.copy(
                                globalState = RegionState.OK, globalName = name, loginBusy = false,
                                loginRegion = if (s.cnState != RegionState.OK) Region.CN else null,
                                loginPrefill = if (s.cnState != RegionState.OK) repo.storedUsername(Region.CN) else null,
                                loginError = null,
                            )
                        }
                    }
                    if (_ui.value.cnState == RegionState.OK && _ui.value.globalState == RegionState.OK) {
                        loadActivities()
                        loadWellness()
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loginBusy = false, loginError = e.message ?: "登录失败") }
                }
        }
    }

    /** 全量刷新已加载范围（启动/登录后/上传完成后） */
    private suspend fun loadActivities() {
        fetchRange(0, maxOf(loadedCount, RECENT_LIMIT))
    }

    /** 加载更多：向后追加一页 */
    fun loadMore() {
        if (_ui.value.loading || _ui.value.loadingMore) return
        viewModelScope.launch {
            _ui.update { it.copy(loadingMore = true) }
            fetchRange(loadedCount, RECENT_LIMIT)
            _ui.update { it.copy(loadingMore = false) }
        }
    }

    private suspend fun fetchRange(start: Int, limit: Int) {
        if (start == 0) _ui.update { it.copy(loading = true) }
        runCatching { repo.fetchLatestActivities(start, limit) }
            .onSuccess { fetched ->
                loadedCount = if (start == 0) maxOf(loadedCount, limit) else loadedCount + limit
                _ui.update { s ->
                    val prev = s.activities.associateBy { it.act.activityId }
                    val mapped = fetched.map { aws ->
                        val old = prev[aws.act.activityId]
                        // 上传成功/重复后自动取消勾选，避免误触强制重传
                        val oldChecked = old?.let {
                            if (it.uploadState == UploadUi.SUCCESS || it.uploadState == UploadUi.DUPLICATE) false
                            else it.checked
                        }
                        ActivityUi(
                            act = aws.act,
                            syncedStatus = aws.syncedStatus,
                            // 默认全部勾选；已上传过的默认不勾选（勾选即强制重传）
                            checked = oldChecked ?: (aws.syncedStatus == null),
                            uploadState = old?.uploadState ?: UploadUi.IDLE,
                            error = old?.error,
                        )
                    }
                    // 首屏替换列表；加载更多则去重后追加
                    val activities = if (start == 0) {
                        mapped
                    } else {
                        s.activities + mapped.filter { prev[it.act.activityId] == null }
                    }
                    s.copy(loading = false, globalError = null, activities = activities)
                }
            }
            .onFailure { e ->
                _ui.update { it.copy(loading = false, loadingMore = false, globalError = e.message ?: "拉取活动列表失败") }
            }
    }

    fun toggle(id: String) = _ui.update { s ->
        s.copy(activities = s.activities.map {
            if (it.act.activityId == id) it.copy(checked = !it.checked) else it
        })
    }

    fun toggleAll(checked: Boolean) = _ui.update { s ->
        s.copy(activities = s.activities.map { it.copy(checked = checked) })
    }

    fun uploadSelected() {
        if (_ui.value.uploading) return
        viewModelScope.launch {
            val targets = _ui.value.activities.filter { it.checked }
            if (targets.isEmpty()) return@launch
            _ui.update { it.copy(uploading = true, uploadDone = 0, uploadTotal = targets.size) }
            targets.forEach { t ->
                setUpload(t.act.activityId) { it.copy(uploadState = UploadUi.WAITING, error = null) }
            }

            var done = 0
            for (t in targets) {
                setUpload(t.act.activityId) { it.copy(uploadState = UploadUi.UPLOADING) }
                val result: Pair<UploadUi, String?> = try {
                    when (repo.uploadActivity(t.act, force = t.syncedStatus != null)) {
                        SyncOutcome.SUCCESS -> UploadUi.SUCCESS to null
                        SyncOutcome.DUPLICATE -> UploadUi.DUPLICATE to null
                        SyncOutcome.SKIPPED -> UploadUi.IDLE to "已上传过，未勾选强制重传"
                    }
                } catch (e: Exception) {
                    UploadUi.FAILED to (e.message ?: "上传失败")
                }
                setUpload(t.act.activityId) { it.copy(uploadState = result.first, error = result.second) }
                done++
                _ui.update { it.copy(uploadDone = done) }
            }

            _ui.update { it.copy(uploading = false) }
            loadActivities() // 刷新已上传标注
        }
    }

    private fun setUpload(id: String, transform: (ActivityUi) -> ActivityUi) = _ui.update { s ->
        s.copy(activities = s.activities.map {
            if (it.act.activityId == id) transform(it) else it
        })
    }

    // ---------------- 健康数据（状态机与活动完全一致） ----------------

    /** 生成最近 N 天日期列表并读取本地同步状态（首屏/上传后/登录后调用） */
    private suspend fun loadWellness(extend: Boolean = false) {
        val days = if (extend) loadedWellnessDays + WELLNESS_RECENT_LIMIT
        else maxOf(loadedWellnessDays, WELLNESS_RECENT_LIMIT)
        val dates = (0 until days).map { LocalDate.now().minusDays(it.toLong()).toString() }
        runCatching { repo.fetchWellnessStatus(dates) }
            .onSuccess { fetched ->
                loadedWellnessDays = days
                _ui.update { s ->
                    val prev = s.wellness.associateBy { it.date }
                    val mapped = fetched.map { ws ->
                        val old = prev[ws.date]
                        // 上传成功/重复后自动取消勾选，避免误触强制重传
                        val oldChecked = old?.let {
                            if (it.uploadState == UploadUi.SUCCESS || it.uploadState == UploadUi.DUPLICATE) false
                            else it.checked
                        }
                        WellnessUi(
                            date = ws.date,
                            fileCount = ws.fileCount,
                            syncedStatus = ws.syncedStatus,
                            // 默认全部勾选；已上传过的默认不勾选（勾选即强制重传）
                            checked = oldChecked ?: (ws.syncedStatus == null),
                            uploadState = old?.uploadState ?: UploadUi.IDLE,
                            error = old?.error,
                        )
                    }
                    s.copy(wellnessError = null, wellness = mapped)
                }
            }
            .onFailure { e ->
                _ui.update { it.copy(wellnessLoadingMore = false, wellnessError = e.message ?: "读取健康数据状态失败") }
            }
    }

    /** 加载更多：日期窗口向前扩展一页 */
    fun loadWellnessMore() {
        if (_ui.value.wellnessLoadingMore) return
        viewModelScope.launch {
            _ui.update { it.copy(wellnessLoadingMore = true) }
            loadWellness(extend = true)
            _ui.update { it.copy(wellnessLoadingMore = false) }
        }
    }

    fun toggleWellness(date: String) = _ui.update { s ->
        s.copy(wellness = s.wellness.map {
            if (it.date == date) it.copy(checked = !it.checked) else it
        })
    }

    fun toggleAllWellness(checked: Boolean) = _ui.update { s ->
        s.copy(wellness = s.wellness.map { it.copy(checked = checked) })
    }

    fun uploadWellnessSelected() {
        if (_ui.value.wellnessUploading) return
        viewModelScope.launch {
            val targets = _ui.value.wellness.filter { it.checked }
            if (targets.isEmpty()) return@launch
            _ui.update { it.copy(wellnessUploading = true, wellnessDone = 0, wellnessTotal = targets.size) }
            targets.forEach { t ->
                setWellnessUpload(t.date) { it.copy(uploadState = UploadUi.WAITING, error = null) }
            }

            var done = 0
            for (t in targets) {
                setWellnessUpload(t.date) { it.copy(uploadState = UploadUi.UPLOADING) }
                val result: Pair<UploadUi, String?> = try {
                    when (repo.uploadWellness(t.date, force = t.syncedStatus != null)) {
                        SyncOutcome.SUCCESS -> UploadUi.SUCCESS to null
                        SyncOutcome.DUPLICATE -> UploadUi.DUPLICATE to null
                        SyncOutcome.SKIPPED -> UploadUi.IDLE to "已上传过，未勾选强制重传"
                    }
                } catch (e: Exception) {
                    UploadUi.FAILED to (e.message ?: "上传失败")
                }
                setWellnessUpload(t.date) { it.copy(uploadState = result.first, error = result.second) }
                done++
                _ui.update { it.copy(wellnessDone = done) }
            }

            _ui.update { it.copy(wellnessUploading = false) }
            loadWellness() // 刷新已上传标注
        }
    }

    private fun setWellnessUpload(date: String, transform: (WellnessUi) -> WellnessUi) = _ui.update { s ->
        s.copy(wellness = s.wellness.map {
            if (it.date == date) transform(it) else it
        })
    }

    companion object {
        const val RECENT_LIMIT = 10
        const val WELLNESS_RECENT_LIMIT = 10
    }
}
