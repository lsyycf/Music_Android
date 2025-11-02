package com.example.music

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.music.ui.MusicPlayerScreen
import com.example.music.ui.theme.MusicTheme
import com.example.music.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var viewModel: MusicViewModel? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Starting")
        super.onCreate(savedInstanceState)
        
        try {
            enableEdgeToEdge()
            Log.d(TAG, "onCreate: Edge to edge enabled")
            
            setContent {
                Log.d(TAG, "setContent: Creating UI")
                MusicTheme {
                    Log.d(TAG, "MusicTheme: Applied")
                    val context = LocalContext.current
                    val vm = remember { 
                        Log.d(TAG, "Creating MusicViewModel")
                        MusicViewModel(context.applicationContext)
                    }
                    
                    // 保存ViewModel引用以便在生命周期方法中使用
                    viewModel = vm
                    
                    DisposableEffect(Unit) {
                        Log.d(TAG, "DisposableEffect: Binding service")
                        vm.bindService()
                        onDispose {
                            Log.d(TAG, "DisposableEffect: Disposing, saving state")
                            vm.saveCurrentState()
                            vm.unbindService()
                        }
                    }
                    
                    MusicPlayerScreen(viewModel = vm)
                }
            }
            Log.d(TAG, "onCreate: Completed")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Error", e)
            throw e
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Saving current state")
        // 应用进入后台时保存状态
        viewModel?.saveCurrentState()
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Saving current state")
        // 应用完全不可见时再次保存状态（双重保障）
        viewModel?.saveCurrentState()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Saving current state before destroy")
        // 应用销毁时最后一次保存状态
        viewModel?.saveCurrentState()
        super.onDestroy()
        viewModel = null
    }
}