package io.github.teamclouday.androidMic.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.github.teamclouday.androidMic.AndroidMicApp
import io.github.teamclouday.androidMic.domain.service.ForegroundService
import io.github.teamclouday.androidMic.ui.home.HomeScreen
import io.github.teamclouday.androidMic.ui.home.openAppSettings
import io.github.teamclouday.androidMic.ui.theme.AndroidMicTheme
import io.github.teamclouday.androidMic.ui.utils.rememberWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"
class MainActivity : ComponentActivity() {

    val vm: MainViewModel by viewModels()
    var mBound = false

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            try {
                AndroidMicApp.service = android.os.Messenger(service)
                AndroidMicApp.isBound = true
                mBound = true
                Log.d(TAG, "Service BOUND! Attempting connect...")
                vm.handlerServiceResponse()
                vm.refreshAppVariables()
                
                lifecycleScope.launch {
                    delay(800)
                    Log.d(TAG, "Calling autoConnect...")
                    vm.autoConnectIfNeeded()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Connect error: ${e.message}")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            AndroidMicApp.service = null
            AndroidMicApp.isBound = false
            mBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== onCreate ===")

        // 启动服务
        val intent = Intent(this, ForegroundService::class.java)
        try {
            Log.d(TAG, "Starting ForegroundService...")
            startService(intent)
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.d(TAG, "Start error: ${e.message}")
        }

        // UI
        setContent {
            AndroidMicTheme(
                theme = vm.prefs.theme.getAsState().value,
                dynamicColor = vm.prefs.dynamicColor.getAsState().value
            ) {
                HomeScreen(vm, rememberWindowInfo(), openAppSettings = ::openAppSettings)
            }
        }
        
        // 备用连接 - 如果服务绑定失败了
        lifecycleScope.launch {
            delay(3000)
            if (!mBound) {
                Log.d(TAG, "Fallback: retry connection...")
                vm.autoConnectIfNeeded()
            }
        }
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        val autoConnect = intent.getBooleanExtra("auto_connect", false)
        val ipOverride = intent.getStringExtra("ip")
        val portOverride = intent.getStringExtra("port")

        if (ipOverride != null || portOverride != null) {
            Log.d(TAG, "Override detected: IP=$ipOverride, Port=$portOverride")
            lifecycleScope.launch {
                if (ipOverride != null) vm.prefs.ip.update(ipOverride)
                if (portOverride != null) vm.prefs.port.update(portOverride)
                delay(500)
                if (autoConnect) {
                    Log.d(TAG, "Triggering connection after override...")
                    vm.onConnectButton()
                }
            }
        } else if (autoConnect) {
            Log.d(TAG, "Intent auto_connect detected, triggering connection...")
            lifecycleScope.launch {
                delay(1000) // 等待 UI 初始化
                vm.onConnectButton()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mBound) {
            try { unbindService(mConnection) } catch (e: Exception) { }
        }
    }
}