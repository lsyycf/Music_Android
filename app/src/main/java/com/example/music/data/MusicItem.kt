package com.example.music.data

import android.net.Uri

data class MusicItem(
    val uri: Uri,
    val title: String,
    val path: String,
    val duration: Long = 0L
)

// 轻量级路径数据，用于启动时快速加载
data class MusicPath(
    val path: String
)

enum class PlayMode {
    RANDOM,
    SEQUENTIAL
}

// 轻量级播放列表状态，只保存路径
data class PlaylistState(
    val songPaths: List<String> = emptyList(),  // 改为只保存路径
    val currentIndex: Int = 0,
    val position: Long = 0L,
    val playMode: PlayMode = PlayMode.RANDOM
)

data class AppSettings(
    val globalVolume: Float = 1.0f,
    val lastActiveFolder: String = "",
    val nextNewPlaylistMode: PlayMode = PlayMode.RANDOM,
    val playlists: Map<String, PlaylistState> = emptyMap()
)

