package com.example.music.utils

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.music.data.MusicItem
import com.example.music.data.MusicPath
import com.example.music.data.PlayMode
import java.io.File

object MusicUtils {
    private const val TAG = "MusicUtils"
    
    private val SUPPORTED_FORMATS = listOf(".flac", ".mp3", ".wav", ".ogg", ".m4a")
    
    /**
     * 快速扫描：只获取音乐文件路径列表，不创建MusicItem
     * 用于启动时快速加载
     */
    fun getMusicPathsFromFolder(folderPath: String): List<String> {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }
        
        return folder.listFiles()
            ?.filter { file ->
                file.isFile && SUPPORTED_FORMATS.any { 
                    file.name.lowercase().endsWith(it) 
                }
            }
            ?.map { file -> file.absolutePath }
            ?: emptyList()
    }
    
    /**
     * 从路径创建MusicItem（懒加载）
     * 只在需要播放时调用
     */
    fun createMusicItemFromPath(path: String): MusicItem? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return null
            }
            
            MusicItem(
                uri = Uri.fromFile(file),
                title = file.nameWithoutExtension,
                path = file.absolutePath,
                duration = 0L  // 可选：需要时才读取duration
            )
        } catch (e: Exception) {
            Log.e(TAG, "createMusicItemFromPath: Error creating MusicItem from path $path", e)
            null
        }
    }
    
    /**
     * 从URI路径创建MusicItem（用于content://格式的路径）
     */
    fun createMusicItemFromUriPath(context: Context, uriPath: String): MusicItem? {
        return try {
            val uri = Uri.parse(uriPath)
            val fileName = getFileNameFromUri(context, uri) ?: "Unknown"
            
            MusicItem(
                uri = uri,
                title = fileName.substringBeforeLast('.'),
                path = uriPath,
                duration = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "createMusicItemFromUriPath: Error creating MusicItem from URI path $uriPath", e)
            null
        }
    }
    
    /**
     * 从URI获取文件名
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return try {
            if (uri.scheme == "file") {
                File(uri.path ?: "").name
            } else {
                DocumentFile.fromSingleUri(context, uri)?.name
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 从DocumentFile URI获取路径列表（适用于Android 10+）
     */
    fun getMusicPathsFromDocumentUri(context: Context, treeUri: Uri): List<String> {
        Log.d(TAG, "getMusicPathsFromDocumentUri: URI = $treeUri")
        val pathList = mutableListOf<String>()
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile == null) {
                Log.e(TAG, "getMusicPathsFromDocumentUri: DocumentFile is null")
                return emptyList()
            }
            
            if (!documentFile.isDirectory) {
                Log.w(TAG, "getMusicPathsFromDocumentUri: Not a directory")
                return emptyList()
            }
            
            scanDocumentFolderForPaths(context, documentFile, pathList)
            Log.d(TAG, "getMusicPathsFromDocumentUri: Found ${pathList.size} music paths")
            
        } catch (e: Exception) {
            Log.e(TAG, "getMusicPathsFromDocumentUri: Error", e)
            e.printStackTrace()
        }
        
        return pathList
    }
    
    /**
     * 扫描DocumentFile文件夹，只获取路径
     */
    private fun scanDocumentFolderForPaths(context: Context, folder: DocumentFile, pathList: MutableList<String>) {
        try {
            val files = folder.listFiles()
            
            files.forEach { file ->
                try {
                    if (file.isDirectory) {
                        scanDocumentFolderForPaths(context, file, pathList)
                    } else if (file.isFile) {
                        val fileName = file.name ?: ""
                        val isSupported = SUPPORTED_FORMATS.any { 
                            fileName.lowercase().endsWith(it) 
                        }
                        
                        if (isSupported) {
                            pathList.add(file.uri.toString())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "scanDocumentFolderForPaths: Error processing file ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanDocumentFolderForPaths: Error scanning folder ${folder.name}", e)
        }
    }
    
    fun getMusicFilesFromFolder(folderPath: String): List<MusicItem> {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }
        
        return folder.listFiles()
            ?.filter { file ->
                file.isFile && SUPPORTED_FORMATS.any { 
                    file.name.lowercase().endsWith(it) 
                }
            }
            ?.map { file ->
                // 快速扫描：不读取元数据，duration 设为 0
                MusicItem(
                    uri = Uri.fromFile(file),
                    title = file.nameWithoutExtension,
                    path = file.absolutePath,
                    duration = 0L  // 不读取元数据，加快扫描速度
                )
            }
            ?: emptyList()
    }
    
    /**
     * 从 DocumentFile URI 获取音乐文件列表（适用于 Android 10+）
     * 注意：这个函数比较耗时，应该在后台线程调用
     */
    fun getMusicFilesFromDocumentUri(context: Context, treeUri: Uri): List<MusicItem> {
        Log.d(TAG, "getMusicFilesFromDocumentUri: URI = $treeUri")
        val musicList = mutableListOf<MusicItem>()
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile == null) {
                Log.e(TAG, "getMusicFilesFromDocumentUri: DocumentFile is null")
                return emptyList()
            }
            
            Log.d(TAG, "getMusicFilesFromDocumentUri: DocumentFile name = ${documentFile.name}, isDirectory = ${documentFile.isDirectory}")
            
            if (!documentFile.isDirectory) {
                Log.w(TAG, "getMusicFilesFromDocumentUri: Not a directory")
                return emptyList()
            }
            
            // 第一步：快速扫描文件列表（不读取元数据）
            scanDocumentFolderFast(context, documentFile, musicList)
            Log.d(TAG, "getMusicFilesFromDocumentUri: Found ${musicList.size} music files")
            
        } catch (e: Exception) {
            Log.e(TAG, "getMusicFilesFromDocumentUri: Error", e)
            e.printStackTrace()
        }
        
        return musicList
    }
    
    /**
     * 快速扫描文件夹，不读取元数据
     */
    private fun scanDocumentFolderFast(context: Context, folder: DocumentFile, musicList: MutableList<MusicItem>) {
        try {
            Log.d(TAG, "scanDocumentFolderFast: Scanning ${folder.name}")
            val files = folder.listFiles()
            Log.d(TAG, "scanDocumentFolderFast: Found ${files.size} files/folders")
            
            files.forEach { file ->
                try {
                    if (file.isDirectory) {
                        Log.d(TAG, "scanDocumentFolderFast: Entering directory ${file.name}")
                        scanDocumentFolderFast(context, file, musicList)
                    } else if (file.isFile) {
                        val fileName = file.name ?: ""
                        val isSupported = SUPPORTED_FORMATS.any { 
                            fileName.lowercase().endsWith(it) 
                        }
                        
                        if (isSupported) {
                            Log.d(TAG, "scanDocumentFolderFast: Found music file: $fileName")
                            // 先不读取元数据，设置为 0，稍后可以异步加载
                            musicList.add(
                                MusicItem(
                                    uri = file.uri,
                                    title = fileName.substringBeforeLast('.'),
                                    path = file.uri.toString(),
                                    duration = 0L  // 先不读取元数据
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "scanDocumentFolderFast: Error processing file ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanDocumentFolderFast: Error scanning folder ${folder.name}", e)
        }
    }
    
    fun getMusicFilesFromContentUri(context: Context): List<MusicItem> {
        val musicList = mutableListOf<MusicItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val path = cursor.getString(pathColumn)
                    val duration = cursor.getLong(durationColumn)
                    
                    val uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    
                    musicList.add(
                        MusicItem(
                            uri = uri,
                            title = name.substringBeforeLast('.'),
                            path = path,
                            duration = duration
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return musicList
    }
    
    fun createOrderedPlaylist(files: List<MusicItem>, mode: PlayMode): List<MusicItem> {
        return when (mode) {
            PlayMode.RANDOM -> files.shuffled()
            PlayMode.SEQUENTIAL -> files.sortedBy { it.title.lowercase() }
        }
    }
    
    /**
     * 创建有序的路径播放列表（轻量级）
     */
    fun createOrderedPathPlaylist(paths: List<String>, mode: PlayMode): List<String> {
        return when (mode) {
            PlayMode.RANDOM -> paths.shuffled()
            PlayMode.SEQUENTIAL -> paths.sortedBy { File(it).nameWithoutExtension.lowercase() }
        }
    }
    
    fun formatTime(milliseconds: Long): String {
        if (milliseconds < 0) return "--:--"
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }
    
    fun compareAndUpdatePlaylist(
        oldPlaylist: List<MusicItem>,
        newFiles: List<MusicItem>,
        currentIndex: Int,
        currentMode: PlayMode
    ): Pair<List<MusicItem>, Int> {
        if (oldPlaylist.isEmpty()) {
            return createOrderedPlaylist(newFiles, currentMode) to 0
        }
        
        val oldSet = oldPlaylist.map { it.path }.toSet()
        val newSet = newFiles.map { it.path }.toSet()
        
        val deletedSongs = oldSet - newSet
        val addedSongs = newSet - oldSet
        
        val currentSongPath = oldPlaylist.getOrNull(currentIndex)?.path
        
        var updatedPlaylist = oldPlaylist.toMutableList()
        var updatedIndex = currentIndex
        
        if (currentMode == PlayMode.SEQUENTIAL) {
            if (deletedSongs.isNotEmpty()) {
                updatedPlaylist = updatedPlaylist.filter { it.path !in deletedSongs }.toMutableList()
            }
            if (addedSongs.isNotEmpty()) {
                updatedPlaylist.addAll(newFiles.filter { it.path in addedSongs })
            }
            if (updatedPlaylist.isNotEmpty()) {
                updatedPlaylist.sortBy { it.title.lowercase() }
            }
            
            if (currentSongPath != null && currentSongPath in updatedPlaylist.map { it.path }) {
                updatedIndex = updatedPlaylist.indexOfFirst { it.path == currentSongPath }
            } else if (updatedPlaylist.isNotEmpty()) {
                updatedIndex = 0
            }
        } else {
            if (deletedSongs.isNotEmpty()) {
                val indicesToRemove = mutableListOf<Int>()
                deletedSongs.forEach { deletedPath ->
                    val idx = updatedPlaylist.indexOfFirst { it.path == deletedPath }
                    if (idx >= 0) {
                        indicesToRemove.add(idx)
                    }
                }
                
                indicesToRemove.sortedDescending().forEach { idx ->
                    updatedPlaylist.removeAt(idx)
                    if (idx < updatedIndex) {
                        updatedIndex--
                    }
                }
            }
            
            if (addedSongs.isNotEmpty()) {
                val newSongsList = newFiles.filter { it.path in addedSongs }.shuffled()
                updatedPlaylist.addAll(newSongsList)
            }
        }
        
        if (updatedPlaylist.isNotEmpty()) {
            updatedIndex = updatedIndex.coerceIn(0, updatedPlaylist.size - 1)
        } else {
            updatedIndex = 0
        }
        
        return updatedPlaylist to updatedIndex
    }
    
    /**
     * 比对和更新路径播放列表（轻量级，只比对路径）
     * 用于启动时快速恢复播放列表
     */
    fun compareAndUpdatePathPlaylist(
        oldPaths: List<String>,
        newPaths: List<String>,
        currentIndex: Int,
        currentMode: PlayMode
    ): Pair<List<String>, Int> {
        if (oldPaths.isEmpty()) {
            return createOrderedPathPlaylist(newPaths, currentMode) to 0
        }
        
        val oldSet = oldPaths.toSet()
        val newSet = newPaths.toSet()
        
        val deletedSongs = oldSet - newSet
        val addedSongs = newSet - oldSet
        
        val currentSongPath = oldPaths.getOrNull(currentIndex)
        
        var updatedPaths = oldPaths.toMutableList()
        var updatedIndex = currentIndex
        
        if (currentMode == PlayMode.SEQUENTIAL) {
            // 顺序模式：删除已删文件，添加新文件，然后按文件名排序
            if (deletedSongs.isNotEmpty()) {
                updatedPaths = updatedPaths.filter { it !in deletedSongs }.toMutableList()
            }
            if (addedSongs.isNotEmpty()) {
                updatedPaths.addAll(newPaths.filter { it in addedSongs })
            }
            if (updatedPaths.isNotEmpty()) {
                updatedPaths.sortBy { File(it).nameWithoutExtension.lowercase() }
            }
            
            // 恢复当前播放位置（统一到Python版本的智能定位逻辑）
            if (currentSongPath != null && currentSongPath in updatedPaths) {
                // 当前歌曲仍在列表中，直接定位
                updatedIndex = updatedPaths.indexOf(currentSongPath)
            } else if (currentSongPath != null && updatedPaths.isNotEmpty()) {
                // 当前歌曲被删除，智能定位到该歌曲应该在的位置
                // 找到第一个文件名大于当前歌曲文件名的位置
                val currentName = File(currentSongPath).nameWithoutExtension.lowercase()
                updatedIndex = updatedPaths.size  // 默认为末尾
                for (i in updatedPaths.indices) {
                    if (File(updatedPaths[i]).nameWithoutExtension.lowercase() > currentName) {
                        updatedIndex = i
                        break
                    }
                }
            } else if (updatedPaths.isNotEmpty()) {
                updatedIndex = 0
            }
        } else {
            // 随机模式：保持播放顺序，删除已删文件，将新歌+未播放歌曲一起打乱（统一到Python版本）
            if (deletedSongs.isNotEmpty()) {
                val indicesToRemove = mutableListOf<Int>()
                deletedSongs.forEach { deletedPath ->
                    val idx = updatedPaths.indexOf(deletedPath)
                    if (idx >= 0) {
                        indicesToRemove.add(idx)
                    }
                }
                
                indicesToRemove.sortedDescending().forEach { idx ->
                    updatedPaths.removeAt(idx)
                    if (idx < updatedIndex) {
                        updatedIndex--
                    }
                }
            }
            
            // 添加新文件：将新歌与当前播放之后的歌曲一起打乱（Python逻辑）
            if (addedSongs.isNotEmpty()) {
                if (updatedIndex < updatedPaths.size) {
                    // 保留从开始到当前播放的歌曲（包括当前）
                    val songsBeforeAndCurrent = updatedPaths.subList(0, updatedIndex + 1).toMutableList()
                    // 获取当前之后的所有歌曲
                    val songsAfterCurrent = updatedPaths.subList(updatedIndex + 1, updatedPaths.size).toMutableList()
                    // 将新歌 + 当前之后的歌曲一起打乱
                    val newSongsList = newPaths.filter { it in addedSongs }.toMutableList()
                    val songsToShuffle = (newSongsList + songsAfterCurrent).shuffled()
                    // 重新组合播放列表
                    updatedPaths = (songsBeforeAndCurrent + songsToShuffle).toMutableList()
                } else {
                    // 当前索引已经在末尾或超出，直接打乱新歌并追加
                    val newSongsList = newPaths.filter { it in addedSongs }.shuffled()
                    updatedPaths.addAll(newSongsList)
                }
            }
        }
        
        if (updatedPaths.isNotEmpty()) {
            updatedIndex = updatedIndex.coerceIn(0, updatedPaths.size - 1)
        } else {
            updatedIndex = 0
        }
        
        return updatedPaths to updatedIndex
    }
    
    /**
     * 将 SAF URI 转换为文件路径（尽可能）
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        // 对于 Android 10+，直接路径访问受限
        // 这里尝试从 URI 中提取路径
        val path = getRealPathFromURI(context, uri)
        if (path != null && File(path).exists()) {
            return path
        }
        return null
    }
    
    private fun getRealPathFromURI(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"),
                    id.toLong()
                )
                return getDataColumn(context, contentUri, null, null)
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]
                var contentUri: Uri? = null
                when (type) {
                    "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }
    
    private fun getDataColumn(context: Context, uri: Uri?, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            cursor = context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return null
    }
    
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }
    
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }
    
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}

