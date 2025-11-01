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
                    val viewModel = remember { 
                        Log.d(TAG, "Creating MusicViewModel")
                        MusicViewModel(context.applicationContext)
                    }
                    
                    DisposableEffect(Unit) {
                        Log.d(TAG, "DisposableEffect: Binding service")
                        viewModel.bindService()
                        onDispose {
                            Log.d(TAG, "DisposableEffect: Disposing")
                            viewModel.saveCurrentState()
                            viewModel.unbindService()
                        }
                    }
                    
                    MusicPlayerScreen(viewModel = viewModel)
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
        // 保存当前状态
    }
    
    override fun onStop() {
        super.onStop()
        // 保存当前状态
    }
}