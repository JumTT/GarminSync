package com.tools.garminsync.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新流程：启动 24h 节流后台检查（失败静默）→ 发现新版本弹窗 →
 * 下载（进度条，可取消）→ 安装（首次需系统授权“安装未知应用”）。
 * 放在根级 Composable 末尾调用一次即可。
 */
@Composable
fun UpdateFlow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var available by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf(-1) } // -1 = 未在下载
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        if (Updater.shouldCheck(context)) {
            runCatching { Updater.checkForUpdate(context) }
                .onSuccess { available = it }
        }
    }

    available?.let { info ->
        AlertDialog(
            onDismissRequest = {
                if (progress < 0) {
                    available = null; apk = null; error = null
                }
            },
            title = { Text("发现新版本 v${info.versionName}") },
            text = {
                Column {
                    when {
                        apk != null -> Text("下载完成，点击“安装”开始安装。首次安装需在系统设置中允许本应用“安装未知应用”。")
                        progress >= 0 -> {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "下载中 $progress%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        else -> {
                            error?.let {
                                Text(
                                    "下载失败：$it",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (info.notes.isNotBlank()) {
                                Text(
                                    info.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    apk != null -> Button(onClick = {
                        if (Updater.canInstall(context)) {
                            Updater.installApk(context, apk!!)
                        } else {
                            Updater.requestInstallPermission(context)
                        }
                    }) { Text("安装") }

                    progress >= 0 -> {}

                    else -> Button(onClick = {
                        error = null
                        progress = 0
                        job = scope.launch {
                            try {
                                apk = Updater.downloadApk(context, info) { progress = it }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "未知错误"
                            } finally {
                                progress = -1
                            }
                        }
                    }) { Text("下载更新") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (progress >= 0) {
                        job?.cancel() // finally 中复位 progress
                    } else {
                        available = null; apk = null; error = null
                    }
                }) { Text(if (progress >= 0) "取消" else "以后再说") }
            },
        )
    }
}
