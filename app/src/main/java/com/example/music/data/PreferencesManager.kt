package com.example.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "music_settings")

class PreferencesManager(private val context: Context) {
    
    companion object {
        private val VOLUME_KEY = floatPreferencesKey("volume")
        private val LAST_FOLDER_KEY = stringPreferencesKey("last_folder")
        private val NEXT_NEW_PLAYLIST_MODE_KEY = stringPreferencesKey("next_new_playlist_mode")
        private val PLAYLISTS_KEY = stringPreferencesKey("playlists")
    }

    val volume: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[VOLUME_KEY] ?: 1.0f
    }

    val lastFolder: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LAST_FOLDER_KEY] ?: ""
    }

    val nextNewPlaylistMode: Flow<PlayMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[NEXT_NEW_PLAYLIST_MODE_KEY] ?: "random"
        if (mode == "random") PlayMode.RANDOM else PlayMode.SEQUENTIAL
    }

    suspend fun saveVolume(volume: Float) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_KEY] = volume
        }
    }

    suspend fun saveLastFolder(folder: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_FOLDER_KEY] = folder
        }
    }

    suspend fun saveNextNewPlaylistMode(mode: PlayMode) {
        context.dataStore.edit { preferences ->
            preferences[NEXT_NEW_PLAYLIST_MODE_KEY] = if (mode == PlayMode.RANDOM) "random" else "sequential"
        }
    }

    suspend fun savePlaylistState(folder: String, state: PlaylistState) {
        context.dataStore.edit { preferences ->
            val playlistsJson = preferences[PLAYLISTS_KEY] ?: "{}"
            val playlists = try {
                JSONObject(playlistsJson)
            } catch (e: Exception) {
                JSONObject()
            }

            // 只保存路径列表，不保存完整的MusicItem
            val pathsArray = JSONArray()
            state.songPaths.forEach { path ->
                pathsArray.put(path)
            }

            playlists.put(folder, JSONObject().apply {
                put("paths", pathsArray)  // 改为只保存路径
                put("index", state.currentIndex)
                put("position", state.position)
                put("mode", if (state.playMode == PlayMode.RANDOM) "random" else "sequential")
            })

            preferences[PLAYLISTS_KEY] = playlists.toString()
        }
    }

    suspend fun getPlaylistState(folder: String): PlaylistState? {
        return try {
            val preferences = context.dataStore.data.first()
            val playlistsJson = preferences[PLAYLISTS_KEY] ?: "{}"
            val playlists = JSONObject(playlistsJson)
            
            if (playlists.has(folder)) {
                val playlistObj = playlists.getJSONObject(folder)
                
                // 尝试新格式（只有路径）
                val songPaths = if (playlistObj.has("paths")) {
                    val pathsArray = playlistObj.getJSONArray("paths")
                    val paths = mutableListOf<String>()
                    for (i in 0 until pathsArray.length()) {
                        paths.add(pathsArray.getString(i))
                    }
                    paths
                } else if (playlistObj.has("songs")) {
                    // 兼容旧格式：从MusicItem中提取路径
                    val songsArray = playlistObj.getJSONArray("songs")
                    val paths = mutableListOf<String>()
                    for (i in 0 until songsArray.length()) {
                        val songObj = songsArray.getJSONObject(i)
                        paths.add(songObj.getString("path"))
                    }
                    paths
                } else {
                    emptyList()
                }
                
                PlaylistState(
                    songPaths = songPaths,  // 只保存路径
                    currentIndex = playlistObj.getInt("index"),
                    position = playlistObj.getLong("position"),
                    playMode = if (playlistObj.getString("mode") == "random") 
                        PlayMode.RANDOM else PlayMode.SEQUENTIAL
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 删除指定文件夹的播放列表状态
     */
    suspend fun deletePlaylistState(folder: String) {
        context.dataStore.edit { preferences ->
            val playlistsJson = preferences[PLAYLISTS_KEY] ?: "{}"
            val playlists = try {
                JSONObject(playlistsJson)
            } catch (e: Exception) {
                JSONObject()
            }
            
            if (playlists.has(folder)) {
                playlists.remove(folder)
                preferences[PLAYLISTS_KEY] = playlists.toString()
            }
        }
    }
}

