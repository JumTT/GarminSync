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

    // 上传期间保持屏幕常亮
    val view = LocalView.current
    LaunchedEffect(state.uploading) {
        view.keepScreenOn = state.uploading
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
}
