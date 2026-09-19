package com.tools.garminsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tools.garminsync.ui.theme.GarminSyncTheme
import com.tools.garminsync.ui.HomeScreen
import com.tools.garminsync.ui.LoginScreen
import com.tools.garminsync.ui.MainViewModel
import com.tools.garminsync.update.UpdateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GarminSyncTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val vm: MainViewModel = viewModel()
    val state by vm.uiState.collectAsState()

    // 上传期间保持屏幕常亮（活动与健康数据任一在上传即生效）
    val view = LocalView.current
    LaunchedEffect(state.uploading, state.wellnessUploading) {
        view.keepScreenOn = state.uploading || state.wellnessUploading
    }

    if (state.loginRegion != null) {
        LoginScreen(
            state = state,
            onLogin = { user, pass -> vm.login(user, pass) },
            onRetryAuto = { vm.recheck() },
        )
    } else {
        HomeScreen(state = state, vm = vm)
    }

    // 应用内更新检测（24h 节流，失败静默）
    UpdateFlow()
}
