package com.example.music.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import com.example.music.data.PlayMode
import com.example.music.utils.MusicUtils
import com.example.music.viewmodel.MusicViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import java.io.File

private const val TAG = "MusicPlayerScreen"

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(viewModel: MusicViewModel) {
    Log.d(TAG, "MusicPlayerScreen: Composing")
    val context = LocalContext.current
    val songPaths by viewModel.songPaths.collectAsState()
    val currentMusicItem by viewModel.currentMusicItem.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val nextNewPlaylistMode by viewModel.nextNewPlaylistMode.collectAsState()
    val currentFolder by viewModel.currentFolder.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasSavedState by viewModel.hasSavedState.collectAsState()
    
    // 缓存歌曲标题，避免切歌时闪烁
    var cachedTitle by remember { mutableStateOf("无音乐, 请选择文件夹") }
    
    // 从路径中提取歌曲标题（仅用于文件路径格式）
    fun getTitleFromPath(path: String): String {
        return try {
            if (path.startsWith("content://") || path.startsWith("file://")) {
                // URI格式无法直接提取，返回加载中提示
                return "加载中..."
            } else {
                // 文件路径
                File(path).nameWithoutExtension
            }
        } catch (e: Exception) {
            // 路径解析异常时显示加载中，因为错误文件会被自动跳过机制处理
            "加载中..."
        }
    }
    
    // 统一处理标题显示逻辑
    LaunchedEffect(currentIndex, songPaths, currentMusicItem, isLoading) {
        if (isLoading) {
            // 加载中时显示加载中
            cachedTitle = "加载中..."
        } else if (songPaths.isEmpty()) {
            // 无歌曲且不在加载状态时重置标题
            cachedTitle = "无音乐, 请选择文件夹"
        } else if (currentIndex < songPaths.size) {
            val path = songPaths[currentIndex]
            val musicItem = currentMusicItem  // 将委托属性赋值给局部变量以启用智能转换
            
            // 优先使用MusicItem的标题（已加载完成）
            if (musicItem != null) {
                cachedTitle = musicItem.title
            } else if (!path.startsWith("content://") && !path.startsWith("file://")) {
                // 文件路径可以立即提取标题
                cachedTitle = getTitleFromPath(path)
            } else {
                // content:// 格式显示加载中（等待MusicItem加载）
                cachedTitle = "加载中..."
            }
        }
    }

    // 权限处理
    val permissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val notificationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
        notificationPermissionState?.let {
            if (!it.status.isGranted) {
                it.launchPermissionRequest()
            }
        }
    }

    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        Log.d(TAG, "folderPickerLauncher: Received URI = $uri")
        uri?.let {
            try {
                Log.d(TAG, "folderPickerLauncher: Taking persistable URI permission")
                // 获取持久化权限
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                
                // 尝试将 URI 转换为文件路径（适用于旧版 Android）
                Log.d(TAG, "folderPickerLauncher: Attempting to convert URI to path")
                val folderPath = MusicUtils.getPathFromUri(context, it)
                
                if (folderPath != null) {
                    Log.d(TAG, "folderPickerLauncher: Using file path: $folderPath")
                    // 可以直接访问文件路径
                    viewModel.loadFolder(folderPath)
                } else {
                    Log.d(TAG, "folderPickerLauncher: Using DocumentFile API")
                    // Android 10+ 使用 DocumentFile API
                    viewModel.loadFolderFromUri(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "folderPickerLauncher: Error", e)
                e.printStackTrace()
                errorMessage = "加载文件夹失败: ${e.message}"
                showErrorDialog = true
            }
        }
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("错误") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "网易云音乐",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // 歌曲标题显示区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    // 使用缓存的标题，避免闪烁且显示正确的歌名
                    val songTitle = when {
                        isLoading -> "加载中..."
                        songPaths.isNotEmpty() -> cachedTitle
                        else -> "无音乐, 请选择文件夹"
                    }
                    val scrollState = rememberScrollState()
                    var isUserInteracting by remember { mutableStateOf(false) }
                    var isAutoScrolling by remember { mutableStateOf(false) }
                    var lastScrollValue by remember { mutableStateOf(0) }
                    var lastInteractionTime by remember { mutableStateOf(0L) }
                    
                    // 监听用户手动滚动
                    LaunchedEffect(scrollState.value) {
                        val currentValue = scrollState.value
                        val currentTime = System.currentTimeMillis()
                        // 如果滚动值变化且不是自动滚动导致的，则认为是用户交互
                        if (currentValue != lastScrollValue && !isAutoScrolling) {
                            isUserInteracting = true
                            lastInteractionTime = currentTime
                        }
                        lastScrollValue = currentValue
                    }
                    
                    // 用户交互后3秒自动恢复滚动
                    LaunchedEffect(lastInteractionTime) {
                        if (lastInteractionTime > 0) {
                            delay(3000) // 等待3秒
                            // 检查最后一次交互是否已经过了3秒
                            val elapsed = System.currentTimeMillis() - lastInteractionTime
                            if (elapsed >= 3000) {
                                isUserInteracting = false
                            }
                        }
                    }
                    
                    // 使用 LaunchedEffect 自动滚动（当文本超出宽度时）
                    LaunchedEffect(songTitle) {
                        // 等待布局完成
                        delay(1000)
                        while (scrollState.maxValue > 0) {
                            // 如果用户正在交互，等待交互结束
                            while (isUserInteracting) {
                                delay(100) // 等待用户交互结束
                            }
                            
                            // 先滚动到末尾，使用慢速动画
                            isAutoScrolling = true
                            try {
                                scrollState.animateScrollTo(
                                    scrollState.maxValue,
                                    animationSpec = tween(durationMillis = 3000)
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 如果被取消，说明用户手动滚动了
                                isUserInteracting = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                            isAutoScrolling = false
                            
                            // 如果用户在此期间交互了，等待交互结束
                            while (isUserInteracting) {
                                delay(100)
                            }
                            
                            delay(3000) // 停留3秒
                            
                            // 如果用户在此期间交互了，等待交互结束
                            while (isUserInteracting) {
                                delay(100)
                            }
                            
                            // 再滚动回开头，使用慢速动画
                            isAutoScrolling = true
                            try {
                                scrollState.animateScrollTo(
                                    0,
                                    animationSpec = tween(durationMillis = 3000)
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 如果被取消，说明用户手动滚动了
                                isUserInteracting = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                            isAutoScrolling = false
                            
                            // 如果用户在此期间交互了，等待交互结束
                            while (isUserInteracting) {
                                delay(100)
                            }
                            
                            delay(3000) // 停留3秒后重新开始
                        }
                    }
                    
                    // 只有文本内容滑动，容器不滑动
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                            .pointerInput(Unit) {
                                // 检测用户点击，设置交互标志
                                detectTapGestures {
                                    isUserInteracting = true
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = songTitle,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 状态显示
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 20.dp)
                ) {
                    val statusText = when {
                        songPaths.isEmpty() -> "空闲"
                        isPlaying -> "正在播放"
                        else -> "已暂停"
                    }
                    
                    Text(
                        text = statusText,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isLoading || songPaths.isEmpty()) {
                                "0 / 0"
                            } else {
                                "${currentIndex + 1} / ${songPaths.size}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        
                        Text(
                            text = "•",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        
                        Text(
                            text = if (isLoading) {
                                "00:00 / 00:00"
                            } else {
                                "${MusicUtils.formatTime(currentPosition)} / ${MusicUtils.formatTime(duration)}"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                // 记住用户拖动/点击的目标位置
                var targetSeekPosition by remember { mutableStateOf(0L) }
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 进度条轨道（底层）
                    val interactionSource = remember { MutableInteractionSource() }
                    Slider(
                        value = if (isLoading) {
                            0f
                        } else if (duration > 0) {
                            currentPosition.toFloat() / duration.toFloat()
                        } else {
                            0f
                        },
                        onValueChange = { progress ->
                            // 拖动/点击时只预览，不真正 seek，不影响播放
                            if (!isLoading && duration > 0) {
                                val seekPosition = (progress * duration).toLong()
                                targetSeekPosition = seekPosition
                                viewModel.previewSeek(seekPosition)
                            }
                        },
                        onValueChangeFinished = {
                            // 松手/点击完成时才真正执行 seek
                            // 使用保存的目标位置，而不是当前位置
                            if (!isLoading && duration > 0) {
                                viewModel.commitSeek(targetSeekPosition)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(0f),
                        enabled = songPaths.isNotEmpty() && duration > 0 && !isLoading,
                        interactionSource = interactionSource,
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .indication(interactionSource, null)
                                    .zIndex(1f)
                                    .clip(CircleShape)
                                    .shadow(
                                        elevation = 4.dp,
                                        shape = CircleShape,
                                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    )
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            color = Color.White,
                                            shape = CircleShape
                                        )
                                )
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Transparent, // 使用自定义 thumb，所以设为透明
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            disabledThumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            disabledInactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 播放控制按钮
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.rewind() },
                        enabled = songPaths.isNotEmpty() && !isLoading,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.FastRewind, 
                            "快退", 
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (songPaths.isNotEmpty()) 0.9f else 0.4f
                            )
                        )
                    }

                    IconButton(
                        onClick = { viewModel.playPrevious() },
                        enabled = songPaths.isNotEmpty() && !isLoading,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious, 
                            "上一首", 
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (songPaths.isNotEmpty()) 0.9f else 0.4f
                            )
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            if (!isLoading) {
                                if (isPlaying) viewModel.pause() else viewModel.play()
                            }
                        },
                        modifier = Modifier
                            .size(76.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    IconButton(
                        onClick = { viewModel.playNext() },
                        enabled = songPaths.isNotEmpty() && !isLoading,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.SkipNext, 
                            "下一首", 
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (songPaths.isNotEmpty()) 0.9f else 0.4f
                            )
                        )
                    }

                    IconButton(
                        onClick = { viewModel.fastForward() },
                        enabled = songPaths.isNotEmpty() && !isLoading,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.FastForward, 
                            "快进", 
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (songPaths.isNotEmpty()) 0.9f else 0.4f
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 音量控制
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (volume < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "音量",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "音量: ${(volume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(start = 6.dp, top = 14.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(3.dp))
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(x = (-8).dp)
                        ) {
                            val volumeInteractionSource = remember { MutableInteractionSource() }
                            Slider(
                                value = volume,
                                onValueChange = { viewModel.setVolume(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(0f),
                                enabled = !isLoading,
                                interactionSource = volumeInteractionSource,
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .indication(volumeInteractionSource, null)
                                            .zIndex(1f)
                                            .clip(CircleShape)
                                            .shadow(
                                                elevation = 3.dp,
                                                shape = CircleShape,
                                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            )
                                            .background(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(
                                                    color = Color.White,
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Transparent,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.toggleMute() },
                        enabled = !isLoading,
                        modifier = Modifier
                            .height(36.dp)
                            .widthIn(min = 70.dp, max = 70.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (volume < 0.01f) "取消静音" else "静音",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 文件夹控制
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "文件夹",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                        Text(
                            "本地文件夹",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 使用自定义的可滚动文本，边框保持不动
                    val folderScrollState = rememberScrollState()
                    var isUserInteracting by remember { mutableStateOf(false) }
                    var isAutoScrolling by remember { mutableStateOf(false) }
                    var lastScrollValue by remember { mutableStateOf(0) }
                    var lastInteractionTime by remember { mutableStateOf(0L) }
                    
                    // 监听用户手动滚动
                    LaunchedEffect(folderScrollState.value) {
                        val currentValue = folderScrollState.value
                        val currentTime = System.currentTimeMillis()
                        // 如果滚动值变化且不是自动滚动导致的，则认为是用户交互
                        if (currentValue != lastScrollValue && !isAutoScrolling) {
                            isUserInteracting = true
                            lastInteractionTime = currentTime
                        }
                        lastScrollValue = currentValue
                    }
                    
                    // 用户交互后3秒自动恢复滚动
                    LaunchedEffect(lastInteractionTime) {
                        if (lastInteractionTime > 0) {
                            delay(3000) // 等待3秒
                            // 检查最后一次交互是否已经过了3秒
                            val elapsed = System.currentTimeMillis() - lastInteractionTime
                            if (elapsed >= 3000) {
                                isUserInteracting = false
                            }
                        }
                    }
                    
                    // 自动滚动文件夹路径（当路径超出宽度时）
                    LaunchedEffect(currentFolder) {
                        // 等待布局完成
                        delay(1000)
                        while (folderScrollState.maxValue > 0) {
                            // 如果用户正在交互，等待交互结束
                            while (isUserInteracting) {
                                delay(100) // 等待用户交互结束
                            }
                            
                            // 先滚动到末尾，使用慢速动画
                            isAutoScrolling = true
                            try {
                                folderScrollState.animateScrollTo(
                                    folderScrollState.maxValue,
                                    animationSpec = tween(durationMillis = 4000)
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 如果被取消，说明用户手动滚动了
                                isUserInteracting = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                            isAutoScrolling = false
                            
                            // 如果用户在此期间交互了，等待交互结束
                            while (isUserInteracting) {
                                delay(100)
                            }
                            
                            delay(3000) // 停留3秒
                            
                            // 如果用户在此期间交互了，等待交互结束
                            while (isUserInteracting) {
                                delay(100)
                            }
                            
                            // 再滚动回开头，使用慢速动画
                            isAutoScrolling = true
                            try {
                                folderScrollState.animateScrollTo(
                                    0,
                                    animationSpec = tween(durationMillis = 4000)
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 如果被取消，说明用户手动滚动了
                                isUserInteracting = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                            isAutoScrolling = false
                            
                            // 如果用户在此期间交互了，等待交互结束
                            while (isUserInteracting) {
                                delay(100)
                            }
                            
                            delay(3000) // 停留3秒后重新开始
                        }
                    }
                    
                    // 外层 Card 作为边框容器（无点击回调，只用于显示）
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        onClick = {} // 空回调，禁用点击反馈
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp)
                                .clipToBounds(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // 内部可滚动的文本
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(
                                        state = folderScrollState,
                                        enabled = true
                                    )
                                    .pointerInput(Unit) {
                                        // 检测用户点击，设置交互标志
                                        detectTapGestures {
                                            isUserInteracting = true
                                            lastInteractionTime = System.currentTimeMillis()
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentFolder.ifEmpty { "请选择音乐文件夹..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (currentFolder.isEmpty()) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Visible
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("浏览", fontSize = 11.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.togglePlayMode() },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (nextNewPlaylistMode == PlayMode.RANDOM) Icons.Default.Shuffle 
                                    else Icons.Default.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(if (nextNewPlaylistMode == PlayMode.RANDOM) "随机" else "顺序", fontSize = 11.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.resetPlaylist() },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("重置", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderSelectionDialog(
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    val commonFolders = remember {
        listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Music"),
            File(Environment.getExternalStorageDirectory(), "音乐")
        ).filter { it.exists() && it.isDirectory }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音乐文件夹") },
        text = {
            Column {
                Text("请选择一个文件夹：", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                
                commonFolders.forEach { folder ->
                    OutlinedButton(
                        onClick = { onFolderSelected(folder.absolutePath) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = folder.absolutePath,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
  }
}

