package com.example.music.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.music.MainActivity
import com.example.music.MusicApplication
import com.example.music.R
import com.example.music.data.MusicItem
import com.example.music.data.PlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MusicService : Service() {
    
    companion object {
        private const val TAG = "MusicService"
    }

    private lateinit var player: ExoPlayer
    private val binder = MusicBinder()

    private val _currentPlaylist = MutableStateFlow<List<MusicItem>>(emptyList())
    val currentPlaylist: StateFlow<List<MusicItem>> = _currentPlaylist.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.RANDOM)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()
    
    // 播放错误标记（用于通知 ViewModel 自动跳过）
    private val _playbackError = MutableStateFlow<PlaybackException?>(null)
    val playbackError: StateFlow<PlaybackException?> = _playbackError.asStateFlow()
    
    // 播放结束标记（用于通知 ViewModel 自动播放下一首）
    private val _playbackEnded = MutableStateFlow<Long>(0L)
    val playbackEnded: StateFlow<Long> = _playbackEnded.asStateFlow()
    
    // 防止 seek 操作期间的播放状态闪烁
    private var isSeeking = false
    
    // 懒加载模式：存储当前播放的曲目标题
    private var currentTrackTitle: String = "无音乐"
    
    // 播放错误异常类
    data class PlaybackException(
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind: Service bound")
        return binder
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate: Service creating")
        super.onCreate()
        try {
            player = ExoPlayer.Builder(this).build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        Log.d(TAG, "onIsPlayingChanged: $playing, isSeeking=$isSeeking")
                        // 在 seek 操作期间，忽略播放状态变化（避免闪烁）
                        if (isSeeking) {
                            Log.d(TAG, "onIsPlayingChanged: Ignoring state change during seek")
                            return
                        }
                        _isPlaying.value = playing
                        if (playing) {
                            startForegroundService()
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        Log.d(TAG, "onMediaItemTransition: reason = $reason")
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            playNext()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "onPlaybackStateChanged: $playbackState")
                        when (playbackState) {
                            Player.STATE_READY -> {
                                _duration.value = player.duration
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "onPlaybackStateChanged: Playback ended, notifying ViewModel")
                                // 通知 ViewModel 播放结束，使用时间戳确保每次都能触发
                                _playbackEnded.value = System.currentTimeMillis()
                            }
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "onPlaybackStateChanged: Player is idle")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "onPlayerError: Playback error occurred", error)
                        // 播放错误时通知 ViewModel 跳过当前歌曲
                        // 通过 Flow 传递错误信息，让 ViewModel 处理列表更新和跳过逻辑
                        _isPlaying.value = false
                        _playbackError.value = PlaybackException(
                            message = error.message ?: "Unknown playback error"
                        )
                    }
                })
            }
            Log.d(TAG, "onCreate: Service created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Error creating service", e)
            throw e
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MusicApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在播放")
            .setContentText(currentTrackTitle)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(MusicApplication.NOTIFICATION_ID, notification)
    }

    fun setPlaylist(playlist: List<MusicItem>, mode: PlayMode) {
        _currentPlaylist.value = playlist
        _playMode.value = mode
        _currentIndex.value = 0
    }

    fun playAt(index: Int, startPosition: Long = 0L) {
        if (_currentPlaylist.value.isEmpty() || index !in _currentPlaylist.value.indices) {
            return
        }

        _currentIndex.value = index
        val musicItem = _currentPlaylist.value[index]

        Log.d(TAG, "playAt: Playing song at index $index, URI = ${musicItem.uri}, path = ${musicItem.path}")

        try {
            player.stop()
            val mediaItem = MediaItem.fromUri(musicItem.uri)
            Log.d(TAG, "playAt: Created MediaItem with URI = ${mediaItem.localConfiguration?.uri ?: mediaItem.mediaId}")
            player.setMediaItem(mediaItem)
            player.prepare()
            if (startPosition > 0) {
                player.seekTo(startPosition)
            }
            player.play()
        } catch (e: Exception) {
            Log.e(TAG, "playAt: Error playing music", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 设置当前播放曲目（用于懒加载模式）
     * 只加载一首歌，不需要整个播放列表
     */
    fun setCurrentTrack(musicItem: MusicItem, startPosition: Long = 0L) {
        Log.d(TAG, "setCurrentTrack: Setting track, URI = ${musicItem.uri}, path = ${musicItem.path}")
        
        try {
            // 保存曲目标题用于通知
            currentTrackTitle = musicItem.title
            
            player.stop()
            val mediaItem = MediaItem.fromUri(musicItem.uri)
            Log.d(TAG, "setCurrentTrack: Created MediaItem with URI = ${mediaItem.localConfiguration?.uri ?: mediaItem.mediaId}")
            player.setMediaItem(mediaItem)
            player.prepare()
            if (startPosition > 0) {
                player.seekTo(startPosition)
            }
            // 不自动播放，等待外部调用play()
        } catch (e: Exception) {
            Log.e(TAG, "setCurrentTrack: Error setting track", e)
            e.printStackTrace()
        }
    }

    fun play() {
        // 懒加载模式：不检查playlist，直接播放已加载的MediaItem
        if (player.currentMediaItem == null) {
            // 没有加载任何媒体，无法播放
            Log.w(TAG, "play: No media item loaded")
            return
        }
        player.play()
    }

    fun pause() {
        _currentPosition.value = player.currentPosition
        player.pause()
    }

    fun playNext() {
        // 懒加载模式：由ViewModel管理切歌逻辑，Service不再处理
        Log.w(TAG, "playNext: Should be handled by ViewModel in lazy loading mode")
    }

    fun playPrevious() {
        // 懒加载模式：由ViewModel管理切歌逻辑，Service不再处理
        Log.w(TAG, "playPrevious: Should be handled by ViewModel in lazy loading mode")
    }

    fun seekTo(position: Long) {
        // 设置标志，防止 seek 期间的播放状态闪烁
        isSeeking = true
        
        // 先更新状态，再执行 seekTo，确保 UI 立即响应
        _currentPosition.value = position
        player.seekTo(position)
        
        // 使用 Handler 在 seek 完成后重置标志（100ms 足够 ExoPlayer 完成 seek）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isSeeking = false
            // 确保播放状态与实际状态同步
            _isPlaying.value = player.isPlaying
            Log.d(TAG, "seekTo: Reset isSeeking flag, isPlaying=${player.isPlaying}")
        }, 100)
    }

    fun fastForward() {
        val currentPos = player.currentPosition
        val duration = player.duration
        
        // 如果duration无效，直接返回
        if (duration <= 0) {
            return
        }
        
        val remaining = duration - currentPos
        
        // 如果剩余时间小于等于10秒，直接跳到结尾触发切换逻辑
        val newPosition = if (remaining <= 10000) {
            duration
        } else {
            currentPos + 10000
        }
        
        // 直接 seekTo，不改变播放状态（避免闪烁）
        seekTo(newPosition)
    }

    fun rewind() {
        // 快退10秒，最小到0
        val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
        
        // 直接 seekTo，不改变播放状态
        seekTo(newPosition)
    }

    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    fun getCurrentPosition(): Long = player.currentPosition

    fun togglePlayMode(): PlayMode {
        _playMode.value = if (_playMode.value == PlayMode.RANDOM) {
            PlayMode.SEQUENTIAL
        } else {
            PlayMode.RANDOM
        }
        return _playMode.value
    }
}

