package com.example.music.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.music.data.MusicItem
import com.example.music.data.PlayMode
import com.example.music.data.PlaylistState
import com.example.music.data.PreferencesManager
import com.example.music.service.MusicService
import com.example.music.utils.MusicUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MusicViewModel(private val context: Context) : ViewModel() {
    
    companion object {
        private const val TAG = "MusicViewModel"
    }

    private val preferencesManager = PreferencesManager(context)
    private var musicService: MusicService? = null
    private var bound = false
    private var positionUpdateJob: Job? = null

    // 轻量级：只保存路径列表
    private val _songPaths = MutableStateFlow<List<String>>(emptyList())
    val songPaths: StateFlow<List<String>> = _songPaths.asStateFlow()
    
    // 当前播放的MusicItem（懒加载，只在播放时创建）
    private val _currentMusicItem = MutableStateFlow<MusicItem?>(null)
    val currentMusicItem: StateFlow<MusicItem?> = _currentMusicItem.asStateFlow()

    // 为了兼容UI，提供一个虚拟的播放列表（只包含路径信息的MusicItem）
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

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.RANDOM)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val _nextNewPlaylistMode = MutableStateFlow(PlayMode.RANDOM)
    val nextNewPlaylistMode: StateFlow<PlayMode> = _nextNewPlaylistMode.asStateFlow()

    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 进度条拖动状态
    private val _isDraggingProgressBar = MutableStateFlow(false)
    val isDraggingProgressBar: StateFlow<Boolean> = _isDraggingProgressBar.asStateFlow()
    
    // 拖动时的预览位置（不影响实际播放）
    private val _previewPosition = MutableStateFlow(0L)
    
    // 切歌状态标志（防止状态闪烁）
    private var isSwitchingSong = false
    
    // 切歌状态重置Job（用于管理延迟重置，防止快速切歌时的状态闪烁）
    private var songSwitchResetJob: Job? = null
    
    // 进度条拖动状态重置Job（防止快速点击时状态闪烁）
    private var seekResetJob: Job? = null
    
    // 从持久化文件实时检查当前文件夹是否有保存的状态
    val hasSavedState: StateFlow<Boolean> = _currentFolder.flatMapLatest { folder ->
        if (folder.isEmpty()) {
            flowOf(false)
        } else {
            flow {
                val state = preferencesManager.getPlaylistState(folder)
                emit(state != null)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            bound = true

            viewModelScope.launch {
                musicService?.currentPlaylist?.collect { playlist ->
                    _currentPlaylist.value = playlist
                }
            }

            viewModelScope.launch {
                musicService?.currentIndex?.collect { index ->
                    _currentIndex.value = index
                }
            }

            viewModelScope.launch {
                musicService?.isPlaying?.collect { playing ->
                    // 在切歌期间或拖动进度条期间忽略播放状态变化
                    if (!isSwitchingSong && !_isDraggingProgressBar.value) {
                        _isPlaying.value = playing
                        if (playing) {
                            startPositionUpdates()
                        } else {
                            stopPositionUpdates()
                        }
                    } else {
                        if (isSwitchingSong) {
                            Log.d(TAG, "serviceConnection: Ignoring isPlaying change during song switch: $playing")
                        }
                        if (_isDraggingProgressBar.value) {
                            Log.d(TAG, "serviceConnection: Ignoring isPlaying change during progress bar drag: $playing")
                        }
                    }
                }
            }

            viewModelScope.launch {
                musicService?.duration?.collect { dur ->
                    _duration.value = dur
                }
            }

            viewModelScope.launch {
                musicService?.playMode?.collect { mode ->
                    _playMode.value = mode
                }
            }
            
            // 监听播放错误，自动跳过问题文件（仿照 main.py 的鲁棒处理）
            viewModelScope.launch {
                musicService?.playbackError?.collect { error ->
                    if (error != null) {
                        Log.e(TAG, "serviceConnection: Playback error detected: ${error.message}, skipping to next")
                        // 自动播放下一首（会自动跳过无效文件）
                        playNext()
                    }
                }
            }
            
            // 监听播放结束，自动播放下一首
            viewModelScope.launch {
                musicService?.playbackEnded?.collect { timestamp ->
                    if (timestamp > 0) {
                        Log.d(TAG, "serviceConnection: Playback ended at $timestamp, playing next")
                        // 自动播放下一首
                        playNext()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            bound = false
        }
    }

    init {
        viewModelScope.launch {
            _volume.value = preferencesManager.volume.first()
            _nextNewPlaylistMode.value = preferencesManager.nextNewPlaylistMode.first()
            val lastFolder = preferencesManager.lastFolder.first()
            _currentFolder.value = lastFolder
            
            if (lastFolder.isNotEmpty()) {
                // 检查是否是 URI 格式
                if (lastFolder.startsWith("content://")) {
                    try {
                        val uri = Uri.parse(lastFolder)
                        loadFolderFromUri(uri, restoreState = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "init: Failed to parse URI", e)
                    }
                } else {
                    loadFolder(lastFolder, restoreState = true)
                }
            }
        }
    }

    fun bindService() {
        Intent(context, MusicService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            context.startService(intent)
        }
    }

    fun unbindService() {
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            var updateCounter = 0
            while (isActive) {
                // 拖动进度条时不更新位置，避免冲突
                if (!_isDraggingProgressBar.value) {
                    musicService?.let {
                        _currentPosition.value = it.getCurrentPosition()
                    }
                }
                
                // 每5秒自动保存一次播放进度（50次 * 100ms = 5000ms）
                updateCounter++
                if (updateCounter >= 50) {
                    updateCounter = 0
                    saveCurrentState()
                }
                
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    fun loadFolder(folderPath: String, restoreState: Boolean = false) {
        viewModelScope.launch {
            try {
                // 切换列表时：暂停播放并保存当前位置
                if (_isPlaying.value) {
                    musicService?.pause()
                }
                saveCurrentState()
                
                _isLoading.value = true
                
                // 切换歌单时清空当前MusicItem，避免显示旧歌名
                _currentMusicItem.value = null
                
                // 快速扫描：只获取路径列表，不创建MusicItem
                val paths = withContext(Dispatchers.IO) {
                    MusicUtils.getMusicPathsFromFolder(folderPath)
                }
                
                if (paths.isEmpty()) {
                    _isLoading.value = false
                    return@launch
                }

                _currentFolder.value = folderPath
                preferencesManager.saveLastFolder(folderPath)

                // 总是先检查是否有保存的状态（忽略restoreState参数）
                val savedState = preferencesManager.getPlaylistState(folderPath)
                if (savedState != null) {
                    // 有保存的状态：直接使用保存的状态，忽略按钮状态
                    Log.d(TAG, "loadFolder: Found saved state, ignoring button mode")
                    
                    // 使用轻量级路径比对
                    val (updatedPaths, updatedIndex) = MusicUtils.compareAndUpdatePathPlaylist(
                        savedState.songPaths,
                        paths,
                        savedState.currentIndex,
                        savedState.playMode
                    )
                    
                    _songPaths.value = updatedPaths
                    _currentIndex.value = updatedIndex
                    _playMode.value = savedState.playMode
                    _currentPosition.value = savedState.position
                    
                    // 同步保存的play_mode到UI按钮显示（不修改按钮点击逻辑）
                    _nextNewPlaylistMode.value = savedState.playMode
                    
                    // 懒加载：只创建当前播放的MusicItem
                    if (updatedPaths.isNotEmpty() && updatedIndex < updatedPaths.size) {
                        loadCurrentMusicItem(updatedPaths[updatedIndex])
                    }
                    
                    _isLoading.value = false
                    Log.d(TAG, "loadFolder: Restored state with ${updatedPaths.size} paths, position ${savedState.position}ms")
                    return@launch
                }

                // 新文件夹（没有保存的状态）- 使用 nextNewPlaylistMode（当前按钮状态）
                Log.d(TAG, "loadFolder: No saved state, using button mode: ${_nextNewPlaylistMode.value}")
                val orderedPaths = MusicUtils.createOrderedPathPlaylist(paths, _nextNewPlaylistMode.value)
                _songPaths.value = orderedPaths
                _currentIndex.value = 0
                _playMode.value = _nextNewPlaylistMode.value
                
                // 懒加载：加载第一首歌的信息，这样UI可以显示歌名而不是"加载中..."
                if (orderedPaths.isNotEmpty()) {
                    loadCurrentMusicItem(orderedPaths[0])
                }
                
                // 保存新创建的播放列表状态
                saveCurrentState()
                
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "loadFolder: Error", e)
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 懒加载当前播放的MusicItem
     * 用于启动恢复时，保留原来的播放位置
     * 仿照 main.py 的鲁棒处理：自动跳过不存在的文件
     * 
     * @param path 音乐文件路径
     * @param skipCount 跳过次数（防止无限递归）
     * @param originalLength 原始列表长度
     * @param direction 播放方向（用于确定跳过文件时的查找方向）
     */
    private fun loadCurrentMusicItem(
        path: String, 
        skipCount: Int = 0, 
        originalLength: Int? = null,
        direction: PlayDirection = PlayDirection.NONE
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPaths = _songPaths.value
            if (currentPaths.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _currentMusicItem.value = null
                    _currentPosition.value = 0L
                }
                return@launch
            }
            
            // 防止无限递归
            val totalLength = originalLength ?: currentPaths.size
            if (skipCount >= totalLength) {
                Log.w(TAG, "loadCurrentMusicItem: All songs in playlist are invalid")
                withContext(Dispatchers.Main) {
                    _songPaths.value = emptyList()
                    _currentMusicItem.value = null
                    _currentIndex.value = 0
                    _currentPosition.value = 0L
                }
                return@launch
            }
            
            val musicItem = if (path.startsWith("content://") || path.startsWith("file://")) {
                MusicUtils.createMusicItemFromUriPath(context, path)
            } else {
                MusicUtils.createMusicItemFromPath(path)
            }
            
            withContext(Dispatchers.Main) {
                if (musicItem == null) {
                    // 文件不存在，从列表中移除并根据direction跳到下一首
                    Log.w(TAG, "loadCurrentMusicItem: File not found: $path, direction=$direction, skipping")
                    val oldIndex = _currentIndex.value
                    val mutablePaths = _songPaths.value.toMutableList()
                    
                    // 重置播放位置（文件不存在，切换到新文件时位置应该清零）
                    _currentPosition.value = 0L
                    
                    val pathIndex = mutablePaths.indexOf(path)
                    if (pathIndex >= 0) {
                        mutablePaths.removeAt(pathIndex)
                        _songPaths.value = mutablePaths
                        
                        if (mutablePaths.isEmpty()) {
                            _currentMusicItem.value = null
                            _currentIndex.value = 0
                            return@withContext
                        }
                        
                        // 根据direction计算下一个索引（统一到Python版本的逻辑）
                        val nextIdx = when (direction) {
                            PlayDirection.PREV -> {
                                // 向前查找：(old_index - 1) % len(current_playlist)
                                val idx = oldIndex - 1
                                if (idx < 0) mutablePaths.size - 1 else idx
                            }
                            PlayDirection.NEXT -> {
                                // 向后查找：删除后保持索引（指向下一个）
                                oldIndex % mutablePaths.size
                            }
                            PlayDirection.NONE -> {
                                // 无方向：默认处理
                                if (oldIndex >= mutablePaths.size) {
                                    0
                                } else {
                                    oldIndex % mutablePaths.size
                                }
                            }
                        }
                        
                        _currentIndex.value = nextIdx.coerceIn(0, mutablePaths.size - 1)
                        
                        // 递归加载下一首（保持direction）
                        val nextPath = mutablePaths[_currentIndex.value]
                        loadCurrentMusicItem(nextPath, skipCount + 1, totalLength, direction)
                    } else {
                        // 路径不在列表中，尝试加载当前索引的文件
                        if (_currentIndex.value < mutablePaths.size) {
                            val nextPath = mutablePaths[_currentIndex.value]
                            loadCurrentMusicItem(nextPath, skipCount + 1, totalLength, direction)
                        }
                    }
                } else {
                    // 文件有效
                    _currentMusicItem.value = musicItem
                    
                    // 设置到Service，保留当前position（用于恢复播放）
                    musicService?.setCurrentTrack(musicItem, _currentPosition.value)
                }
            }
        }
    }
    
    fun loadFolderFromUri(uri: Uri, restoreState: Boolean = false) {
        Log.d(TAG, "loadFolderFromUri: URI = $uri, restoreState = $restoreState")
        viewModelScope.launch {
            try {
                // 切换列表时：暂停播放并保存当前位置
                if (_isPlaying.value) {
                    musicService?.pause()
                }
                saveCurrentState()
                
                _isLoading.value = true
                
                // 切换歌单时清空当前MusicItem，避免显示旧歌名
                _currentMusicItem.value = null
                
                // 快速扫描：只获取路径列表
                val paths = withContext(Dispatchers.IO) {
                    MusicUtils.getMusicPathsFromDocumentUri(context, uri)
                }
                
                Log.d(TAG, "loadFolderFromUri: Found ${paths.size} paths")
                
                if (paths.isEmpty()) {
                    Log.w(TAG, "loadFolderFromUri: No music files found")
                    _isLoading.value = false
                    return@launch
                }

                val folderPath = uri.toString()
                _currentFolder.value = folderPath
                preferencesManager.saveLastFolder(folderPath)

                // 总是先检查是否有保存的状态（忽略restoreState参数）
                val savedState = preferencesManager.getPlaylistState(folderPath)
                if (savedState != null) {
                    // 有保存的状态：直接使用保存的状态，忽略按钮状态
                    Log.d(TAG, "loadFolderFromUri: Found saved state, ignoring button mode")
                    
                    // 使用轻量级路径比对
                    val (updatedPaths, updatedIndex) = MusicUtils.compareAndUpdatePathPlaylist(
                        savedState.songPaths,
                        paths,
                        savedState.currentIndex,
                        savedState.playMode
                    )
                    
                    _songPaths.value = updatedPaths
                    _currentIndex.value = updatedIndex
                    _playMode.value = savedState.playMode
                    _currentPosition.value = savedState.position
                    
                    // 同步保存的play_mode到UI按钮显示（不修改按钮点击逻辑）
                    _nextNewPlaylistMode.value = savedState.playMode
                    
                    // 懒加载：只创建当前播放的MusicItem
                    if (updatedPaths.isNotEmpty() && updatedIndex < updatedPaths.size) {
                        loadCurrentMusicItem(updatedPaths[updatedIndex])
                    }
                    
                    _isLoading.value = false
                    Log.d(TAG, "loadFolderFromUri: Restored state with position ${savedState.position}ms")
                    return@launch
                }

                // 新文件夹（没有保存的状态）- 使用 nextNewPlaylistMode（当前按钮状态）
                Log.d(TAG, "loadFolderFromUri: No saved state, using button mode: ${_nextNewPlaylistMode.value}")
                val orderedPaths = MusicUtils.createOrderedPathPlaylist(paths, _nextNewPlaylistMode.value)
                _songPaths.value = orderedPaths
                _currentIndex.value = 0
                _playMode.value = _nextNewPlaylistMode.value
                
                Log.d(TAG, "loadFolderFromUri: Set ${orderedPaths.size} paths")
                
                // 懒加载：加载第一首歌的信息，这样UI可以显示歌名而不是"加载中..."
                if (orderedPaths.isNotEmpty()) {
                    loadCurrentMusicItem(orderedPaths[0])
                }
                
                // 保存新创建的播放列表状态
                saveCurrentState()
                
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "loadFolderFromUri: Error", e)
                _isLoading.value = false
            }
        }
    }

    fun play() {
        // 懒加载：如果当前没有加载MusicItem，先加载
        if (_currentMusicItem.value == null && _songPaths.value.isNotEmpty()) {
            val currentPath = _songPaths.value.getOrNull(_currentIndex.value)
            if (currentPath != null) {
                loadAndPlay(currentPath)
                return
            }
        }
        
        musicService?.play()
        saveCurrentState()
    }

    fun pause() {
        musicService?.pause()
        saveCurrentState()
        
        // 暂停后可以考虑销毁MusicItem以节省内存
        // 但为了更好的用户体验，暂停时不销毁
    }

    fun playNext() {
        val wasPlaying = _isPlaying.value
        
        // 开始切歌，防止状态闪烁（在 ViewModel 和 Service 两层都设置）
        isSwitchingSong = true
        musicService?.startSongSwitch()
        
        // 取消之前的切歌状态重置Job（防止快速切歌时状态闪烁）
        songSwitchResetJob?.cancel()
        
        // 如果正在拖动，不要暂停播放（让音乐继续，用户松手后会 seek）
        if (wasPlaying && !_isDraggingProgressBar.value) {
            musicService?.pause()
        }
        
        // 更新索引
        if (_songPaths.value.isNotEmpty()) {
            val nextIndex = (_currentIndex.value + 1) % _songPaths.value.size
            _currentIndex.value = nextIndex
            
            // 重置position为0（新歌从头开始）
            // 即使在拖动也要重置，因为新歌时长可能不同
            _currentPosition.value = 0L
            
            val nextPath = _songPaths.value[nextIndex]
            if (wasPlaying) {
                // 传递 NEXT 方向，确保跳过文件时继续向后查找
                loadAndPlay(nextPath, direction = PlayDirection.NEXT)
            } else {
                // 暂停状态，只加载不播放
                loadCurrentMusicItem(nextPath, direction = PlayDirection.NEXT)
            }
            
            // 延迟结束切歌状态（等待新歌加载完成）
            // 使用Job管理，这样快速切歌时可以取消之前的重置
            songSwitchResetJob = viewModelScope.launch {
                delay(300)
                isSwitchingSong = false
                musicService?.endSongSwitch()
                // 同步实际的播放状态
                musicService?.let { service ->
                    val actualPlaying = service.isPlaying.value
                    if (_isPlaying.value != actualPlaying) {
                        _isPlaying.value = actualPlaying
                        if (actualPlaying) {
                            startPositionUpdates()
                        } else {
                            stopPositionUpdates()
                        }
                    }
                }
            }
            
            // 销毁前一首的MusicItem（延迟销毁，避免UI闪烁）
            viewModelScope.launch {
                delay(100)
                // 这里不设为null，保持引用以便UI显示
                // GC会在需要时自动回收
            }
        }
        
        saveCurrentState()
    }

    fun playPrevious() {
        val wasPlaying = _isPlaying.value
        
        // 开始切歌，防止状态闪烁（在 ViewModel 和 Service 两层都设置）
        isSwitchingSong = true
        musicService?.startSongSwitch()
        
        // 取消之前的切歌状态重置Job（防止快速切歌时状态闪烁）
        songSwitchResetJob?.cancel()
        
        // 如果正在拖动，不要暂停播放（让音乐继续，用户松手后会 seek）
        if (wasPlaying && !_isDraggingProgressBar.value) {
            musicService?.pause()
        }
        
        // 更新索引
        if (_songPaths.value.isNotEmpty()) {
            val prevIndex = if (_currentIndex.value > 0) {
                _currentIndex.value - 1
            } else {
                _songPaths.value.size - 1
            }
            _currentIndex.value = prevIndex
            
            // 重置position为0（新歌从头开始）
            // 即使在拖动也要重置，因为新歌时长可能不同
            _currentPosition.value = 0L
            
            val prevPath = _songPaths.value[prevIndex]
            if (wasPlaying) {
                // 传递 PREV 方向，确保跳过文件时继续向前查找
                loadAndPlay(prevPath, direction = PlayDirection.PREV)
            } else {
                // 暂停状态，只加载不播放
                loadCurrentMusicItem(prevPath, direction = PlayDirection.PREV)
            }
            
            // 延迟结束切歌状态（等待新歌加载完成）
            // 使用Job管理，这样快速切歌时可以取消之前的重置
            songSwitchResetJob = viewModelScope.launch {
                delay(300)
                isSwitchingSong = false
                musicService?.endSongSwitch()
                // 同步实际的播放状态
                musicService?.let { service ->
                    val actualPlaying = service.isPlaying.value
                    if (_isPlaying.value != actualPlaying) {
                        _isPlaying.value = actualPlaying
                        if (actualPlaying) {
                            startPositionUpdates()
                        } else {
                            stopPositionUpdates()
                        }
                    }
                }
            }
            
            // 延迟销毁
            viewModelScope.launch {
                delay(100)
            }
        }
        
        saveCurrentState()
    }
    
    /**
     * 播放方向枚举
     */
    private enum class PlayDirection {
        PREV,  // 向前（上一首）
        NEXT,  // 向后（下一首）
        NONE   // 无方向（默认）
    }
    
    /**
     * 加载并播放指定路径的音乐
     * 切歌时从头开始播放（startPosition = 0）
     * 仿照 main.py 的鲁棒处理：自动跳过不存在的文件
     * 
     * @param path 音乐文件路径
     * @param skipCount 跳过次数（防止无限递归）
     * @param originalLength 原始列表长度
     * @param direction 播放方向（用于确定跳过文件时的查找方向）
     */
    private fun loadAndPlay(
        path: String, 
        skipCount: Int = 0, 
        originalLength: Int? = null,
        direction: PlayDirection = PlayDirection.NONE
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPaths = _songPaths.value
            if (currentPaths.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _currentMusicItem.value = null
                    _isPlaying.value = false
                    _currentPosition.value = 0L
                }
                return@launch
            }
            
            // 防止无限递归：如果跳过次数超过原始列表长度，说明所有文件都有问题
            val totalLength = originalLength ?: currentPaths.size
            if (skipCount >= totalLength) {
                Log.w(TAG, "loadAndPlay: All songs in playlist are invalid, stopping")
                withContext(Dispatchers.Main) {
                    _songPaths.value = emptyList()
                    _currentMusicItem.value = null
                    _currentIndex.value = 0
                    _isPlaying.value = false
                    _currentPosition.value = 0L
                }
                return@launch
            }
            
            val musicItem = if (path.startsWith("content://") || path.startsWith("file://")) {
                MusicUtils.createMusicItemFromUriPath(context, path)
            } else {
                MusicUtils.createMusicItemFromPath(path)
            }
            
            withContext(Dispatchers.Main) {
                if (musicItem == null) {
                    // 文件不存在或无法加载，从列表中移除并根据direction跳到下一首
                    Log.w(TAG, "loadAndPlay: File not found or invalid: $path, direction=$direction, skipping")
                    val oldIndex = _currentIndex.value
                    val mutablePaths = _songPaths.value.toMutableList()
                    
                    // 重置播放位置（文件不存在，切换到新文件时位置应该清零）
                    _currentPosition.value = 0L
                    
                    // 从列表中移除无效文件
                    val pathIndex = mutablePaths.indexOf(path)
                    if (pathIndex >= 0) {
                        mutablePaths.removeAt(pathIndex)
                        _songPaths.value = mutablePaths
                        
                        if (mutablePaths.isEmpty()) {
                            Log.w(TAG, "loadAndPlay: Playlist is now empty")
                            _currentMusicItem.value = null
                            _currentIndex.value = 0
                            _isPlaying.value = false
                            return@withContext
                        }
                        
                        // 根据direction计算下一个索引（统一到Python版本的逻辑）
                        val nextIdx = when (direction) {
                            PlayDirection.PREV -> {
                                // 向前查找：(old_index - 1) % len(current_playlist)
                                val idx = oldIndex - 1
                                if (idx < 0) mutablePaths.size - 1 else idx
                            }
                            PlayDirection.NEXT -> {
                                // 向后查找：删除后保持索引（指向下一个）
                                oldIndex % mutablePaths.size
                            }
                            PlayDirection.NONE -> {
                                // 无方向：默认处理
                                if (oldIndex >= mutablePaths.size) {
                                    0
                                } else {
                                    oldIndex % mutablePaths.size
                                }
                            }
                        }
                        
                        _currentIndex.value = nextIdx.coerceIn(0, mutablePaths.size - 1)
                        
                        // 递归加载下一首（保持direction）
                        val nextPath = mutablePaths[_currentIndex.value]
                        loadAndPlay(nextPath, skipCount + 1, totalLength, direction)
                    } else {
                        // 路径不在列表中，尝试加载当前索引的文件
                        if (_currentIndex.value < mutablePaths.size) {
                            val nextPath = mutablePaths[_currentIndex.value]
                            loadAndPlay(nextPath, skipCount + 1, totalLength, direction)
                        }
                    }
                } else {
                    // 文件有效，正常播放
                    _currentMusicItem.value = musicItem
                    
                    // 设置到Service并播放（从头开始）
                    musicService?.setCurrentTrack(musicItem, 0L)
                    musicService?.play()
                }
            }
        }
    }

    /**
     * 预览拖动位置（拖动期间调用）
     * 只更新 UI 显示，不影响实际播放
     * 严格禁止任何播放状态变化
     */
    fun previewSeek(position: Long) {
        // 取消之前的重置Job，防止快速操作时状态闪烁
        seekResetJob?.cancel()
        
        // 立即设置拖动标志，阻止任何播放状态变化
        _isDraggingProgressBar.value = true
        _previewPosition.value = position
        _currentPosition.value = position
        
        Log.d(TAG, "previewSeek: position=$position, dragging=true, 不影响播放状态")
    }
    
    /**
     * 提交拖动位置（松手时调用）
     * 真正执行 seek 操作
     * 严格禁止任何播放状态变化 - 只 seek 位置，不改变播放/暂停状态
     */
    fun commitSeek(position: Long) {
        // 取消之前的重置Job，防止快速操作时状态闪烁
        seekResetJob?.cancel()
        
        // 检查目标位置是否超出当前歌曲时长（可能已经切歌了）
        val currentDuration = _duration.value
        val safePosition = if (currentDuration > 0 && position >= currentDuration) {
            // 超出时长，限制在时长范围内
            Log.d(TAG, "commitSeek: Position $position exceeds duration $currentDuration, clamping")
            (currentDuration - 1000).coerceAtLeast(0)
        } else {
            position
        }
        
        // 执行真正的 seek - 只改变位置，不影响播放状态
        musicService?.seekTo(safePosition)
        _currentPosition.value = safePosition
        
        Log.d(TAG, "commitSeek: Seeked to $safePosition, 播放状态保持不变")
        
        // 延迟重置拖动标志，避免状态闪烁，确保整个过程中播放状态不受影响
        seekResetJob = viewModelScope.launch {
            delay(200)
            _isDraggingProgressBar.value = false
        }
    }
    
    /**
     * 直接跳转（用于快进快退等操作）
     */
    fun seekTo(position: Long) {
        // 只调用 Service，由 Service 负责更新状态
        musicService?.seekTo(position)
        // 立即同步位置，确保 UI 响应快速
        _currentPosition.value = position
    }

    fun fastForward() {
        // 取消之前的重置Job，防止快速操作时状态闪烁
        seekResetJob?.cancel()
        
        // 设置拖动标志，阻止播放状态更新
        _isDraggingProgressBar.value = true
        
        musicService?.fastForward()
        // 立即同步当前位置到 ViewModel，确保 UI 实时更新
        musicService?.let {
            _currentPosition.value = it.getCurrentPosition()
        }
        
        // 延迟重置拖动标志，避免状态闪烁
        seekResetJob = viewModelScope.launch {
            delay(200)
            _isDraggingProgressBar.value = false
        }
    }

    fun rewind() {
        // 取消之前的重置Job，防止快速操作时状态闪烁
        seekResetJob?.cancel()
        
        // 设置拖动标志，阻止播放状态更新
        _isDraggingProgressBar.value = true
        
        musicService?.rewind()
        // 立即同步当前位置到 ViewModel，确保 UI 实时更新
        musicService?.let {
            _currentPosition.value = it.getCurrentPosition()
        }
        
        // 延迟重置拖动标志，避免状态闪烁
        seekResetJob = viewModelScope.launch {
            delay(200)
            _isDraggingProgressBar.value = false
        }
    }

    fun setVolume(volume: Float) {
        _volume.value = volume
        musicService?.setVolume(volume)
        viewModelScope.launch {
            preferencesManager.saveVolume(volume)
        }
    }

    fun toggleMute() {
        if (_volume.value > 0.01f) {
            setVolume(0f)
        } else {
            setVolume(1.0f)
        }
    }

    fun togglePlayMode() {
        // 只切换 nextNewPlaylistMode，不影响当前播放列表
        val newMode = if (_nextNewPlaylistMode.value == PlayMode.RANDOM) {
            PlayMode.SEQUENTIAL
        } else {
            PlayMode.RANDOM
        }
        _nextNewPlaylistMode.value = newMode
        
        viewModelScope.launch {
            preferencesManager.saveNextNewPlaylistMode(newMode)
        }
    }

    fun resetPlaylist() {
        musicService?.pause()
        
        // 保存当前文件夹路径，用于删除其保存的状态
        val currentFolderPath = _currentFolder.value
        
        // 清理所有数据
        _songPaths.value = emptyList()
        _currentPlaylist.value = emptyList()
        _currentMusicItem.value = null
        _currentIndex.value = 0
        _currentFolder.value = ""
        _isPlaying.value = false
        _nextNewPlaylistMode.value = PlayMode.RANDOM
        
        viewModelScope.launch {
            // 删除当前文件夹保存的播放列表状态
            if (currentFolderPath.isNotEmpty()) {
                preferencesManager.deletePlaylistState(currentFolderPath)
                Log.d(TAG, "resetPlaylist: Deleted saved state for folder: $currentFolderPath")
            }
            
            preferencesManager.saveLastFolder("")
            preferencesManager.saveNextNewPlaylistMode(PlayMode.RANDOM)
        }
    }

    fun saveCurrentState() {
        if (_currentFolder.value.isEmpty() || _songPaths.value.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                // 获取实时的播放位置
                val currentPosition = musicService?.getCurrentPosition() ?: _currentPosition.value
                
                // 更新 Service 中的播放列表信息
                musicService?.updatePlaylistInfo(
                    _currentFolder.value,
                    _songPaths.value,
                    _currentIndex.value,
                    _playMode.value
                )
                
                // 只保存路径列表
                val state = PlaylistState(
                    songPaths = _songPaths.value,
                    currentIndex = _currentIndex.value,
                    position = currentPosition,
                    playMode = _playMode.value
                )
                
                preferencesManager.savePlaylistState(_currentFolder.value, state)
                Log.d(TAG, "saveCurrentState: Saved playlist with ${state.songPaths.size} paths, index ${state.currentIndex}, position ${currentPosition}ms")
            } catch (e: Exception) {
                Log.e(TAG, "saveCurrentState: Error", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveCurrentState()
        stopPositionUpdates()
        unbindService()
    }
}

