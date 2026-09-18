package com.tools.garminsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private val OkGreen = Color(0xFF2E7D32)
private val DupAmber = Color(0xFFF9A825)

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel) {
    val checkedCount = state.activities.count { it.checked }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GarminSync", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { vm.recheck() },
                enabled = !state.loading && !state.uploading,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            AccountCard(
                label = "国区",
                domain = "garmin.cn",
                state = state.cnState,
                name = state.cnName,
                onLogin = { vm.startLogin(com.tools.garminsync.data.remote.Region.CN) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AccountCard(
                label = "国际区",
                domain = "garmin.com",
                state = state.globalState,
                name = state.globalName,
                onLogin = { vm.startLogin(com.tools.garminsync.data.remote.Region.GLOBAL) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(4.dp))
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.globalError?.let {
            Text(
                "拉取活动列表失败：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.activities, key = { it.act.activityId }) { item ->
                ActivityCard(item, enabled = !state.uploading, onToggle = { vm.toggle(item.act.activityId) })
            }
            // 列表为空时不生成该项，否则它会成为滚动锚点，数据填充后列表会跳到最底部
            if (state.activities.isNotEmpty()) {
                item(key = "load_more") {
                    LoadMoreButton(
                        loading = state.loadingMore,
                        visible = state.cnState == RegionState.OK && state.globalState == RegionState.OK,
                        enabled = !state.uploading && !state.loading && !state.loadingMore,
                        onClick = { vm.loadMore() },
                    )
                }
            }
        }

        UploadBar(
            uploading = state.uploading,
            done = state.uploadDone,
            total = state.uploadTotal,
            checkedCount = checkedCount,
            anyUnchecked = state.activities.any { !it.checked },
            onUpload = { vm.uploadSelected() },
            onToggleAll = { vm.toggleAll(it) },
        )
    }
}

@Composable
private fun AccountCard(
    label: String,
    domain: String,
    state: RegionState,
    name: String?,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                RegionState.OK -> Icon(
                    Icons.Default.Check, contentDescription = null,
                    tint = OkGreen, modifier = Modifier.size(20.dp),
                )
                RegionState.CHECKING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                RegionState.NEED_LOGIN -> Icon(
                    Icons.Default.ErrorOutline, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        state == RegionState.OK && name != null -> "$name · $domain"
                        state == RegionState.OK -> domain
                        state == RegionState.CHECKING -> "检查中…"
                        else -> "未登录 · $domain"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state == RegionState.NEED_LOGIN) {
                TextButton(onClick = onLogin) { Text("登录") }
            }
        }
    }
}

@Composable
private fun ActivityCard(item: ActivityUi, enabled: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.checked, onCheckedChange = { onToggle() }, enabled = enabled)
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    item.act.activityName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${fmtTime(item.act.startTimeLocal)} · ${fmtKm(item.act.distanceMeters)} · ${fmtDur(item.act.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (item.syncedStatus) {
                        "SUCCESS" -> Badge("已上传", OkGreen)
                        "DUPLICATE" -> Badge("服务器已存在", DupAmber, Color.Black)
                    }
                    when (item.uploadState) {
                        UploadUi.WAITING -> Badge("排队中", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        UploadUi.UPLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("上传中…", style = MaterialTheme.typography.labelSmall)
                        }
                        UploadUi.SUCCESS -> Badge("上传成功", OkGreen)
                        UploadUi.DUPLICATE -> Badge("重复（服务器已存在）", DupAmber, Color.Black)
                        else -> {}
                    }
                }
                item.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadMoreButton(loading: Boolean, visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (!visible) return
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onClick, enabled = enabled) {
                Text("加载更多")
            }
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color = Color.White) {
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun UploadBar(
    uploading: Boolean,
    done: Int,
    total: Int,
    checkedCount: Int,
    anyUnchecked: Boolean,
    onUpload: () -> Unit,
    onToggleAll: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        if (uploading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("$done / $total", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onToggleAll(anyUnchecked) }, enabled = !uploading) {
                Text(if (anyUnchecked) "全选" else "清空")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onUpload,
                enabled = !uploading && checkedCount > 0,
            ) {
                Text(if (uploading) "上传中…" else "上传所选 ($checkedCount)")
            }
        }
    }
}

// ---------------- 格式化 ----------------

/** "2026-09-16 07:30:00" -> "09-16 07:30" */
private fun fmtTime(s: String): String = if (s.length >= 16) s.substring(5, 16) else s

private fun fmtKm(meters: Double): String = String.format(Locale.US, "%.2f km", meters / 1000)

private fun fmtDur(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
