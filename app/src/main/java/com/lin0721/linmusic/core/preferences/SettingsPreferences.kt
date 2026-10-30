package com.lin0721.linmusic.core.preferences

import android.content.Context
import com.lin0721.linmusic.BuildConfig
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.CrossfadePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "SettingsPreferences"

// preferencesDataStore
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        AppLogger.e(TAG, "Settings corrupted, reset to default", ex)
        emptyPreferences()
    }
)

class SettingsPreferences(private val context: Context) {

    companion object {
        // Wi-Fi Play KEY， "lossless"
        private val KEY_WIFI_QUALITY = stringPreferencesKey("wifi_quality")
        // Play KEY， "standard"
        private val KEY_MOBILE_QUALITY = stringPreferencesKey("mobile_quality")
        // New Playlist
        private val KEY_DEFAULT_PLAYLIST_PRIVATE = booleanPreferencesKey("default_playlist_private")
        // Search
        private val KEY_DEFAULT_SEARCH_SOURCE = stringPreferencesKey("default_search_source")
        // KEY， false
        private val KEY_STREAM_CACHE_ENABLED = booleanPreferencesKey("stream_cache_enabled")
        // Audio Cache KEY， 512MB
        private val KEY_AUDIO_CACHE_MAX_SIZE = longPreferencesKey("audio_cache_max_size")
        // CustomDownload Folder（SAF tree Uri）
        private val KEY_DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")
        // Download
        private val KEY_DOWNLOAD_LYRICS_ENABLED = booleanPreferencesKey("download_lyrics_enabled")
        // IP ， false
        private val KEY_USE_REAL_IP = booleanPreferencesKey("use_real_ip")
        // Custom IP ， ""
        private val KEY_REAL_IP_VALUE = stringPreferencesKey("real_ip_value")
        // Audiometa API key
        private val KEY_AUDIOMETA_API_KEY = stringPreferencesKey("audiometa_api_key")
        // Zoho Cloud TTML lyrics base URL (e.g., https://yourdomain.com/ttml)
        private val KEY_ZOHO_LYRICS_BASE_URL = stringPreferencesKey("zoho_lyrics_base_url")
        // Deezer ARL Token for FLAC Lossless downloads
        private val KEY_DEEZER_ARL = stringPreferencesKey("deezer_arl")
        // Tidal Session/API Token for Hi-Res Master FLAC downloads
        private val KEY_TIDAL_TOKEN = stringPreferencesKey("tidal_token")

        // Autoplay recommended songs， true
        private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        // Play alongside other apps， false
        private val KEY_PLAY_WITH_OTHER_APPS = booleanPreferencesKey("play_with_other_apps")
        // Resume after external audio stops， true
        private val KEY_RESUME_AFTER_EXTERNAL_INTERRUPTION = booleanPreferencesKey("resume_after_external_interruption")
        // ， false
        private val KEY_CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        // (ms), default 3000
        private val KEY_CROSSFADE_DURATION_MS = intPreferencesKey("crossfade_duration_ms")
        // Play， "loop" ()
        // Stream over Wi-Fi only， false
        private val KEY_WIFI_ONLY_PLAY = booleanPreferencesKey("wifi_only_play")
        // Cellular playback warning， true
        private val KEY_MOBILE_ALERT = booleanPreferencesKey("mobile_alert")
        // Use proxy server， false
        private val KEY_USE_PROXY = booleanPreferencesKey("use_proxy")
        // ， false
        private val KEY_SHOW_DESKTOP_LRC = booleanPreferencesKey("show_desktop_lrc")
        // Enable system lock screen， true
        private val KEY_SHOW_LOCKSCREEN = booleanPreferencesKey("show_lockscreen")
        // Car Bluetooth auto resume， false
        private val KEY_CAR_MODE = booleanPreferencesKey("car_mode")
        // CreatePlaylists， true
        private val KEY_SHOW_CREATE_ENTRY = booleanPreferencesKey("show_create_entry")
        // ， 14sp
        private val KEY_LYRIC_TEXT_SIZE = intPreferencesKey("lyric_text_size")
        // ， "#FFFFFF"
        private val KEY_LYRIC_TEXT_COLOR = stringPreferencesKey("lyric_text_color")
        // KEY， debug  "DEBUG", release bundle "WARN"
        private val KEY_LOG_LEVEL = stringPreferencesKey("log_level")
        // ， true
        private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        // （beta/rc）， false
        private val KEY_ALLOW_PRERELEASE_CHANNEL = booleanPreferencesKey("allow_prerelease_channel")
        // of  tag，
        private val KEY_IGNORED_UPDATE_TAG = stringPreferencesKey("ignored_update_tag")
        // ， 22sp
        private val KEY_FULL_SCREEN_LYRIC_TEXT_SIZE = intPreferencesKey("full_screen_lyric_text_size")
        // ， "left"
        private val KEY_FULL_SCREEN_LYRIC_ALIGNMENT = stringPreferencesKey("full_screen_lyric_alignment")
        // ， true
        private val KEY_FULL_SCREEN_LYRIC_SHOW_TRANSLATION = booleanPreferencesKey("full_screen_lyric_show_translation")
        // ("translation", "roma", "none"), default "translation"
        private val KEY_FULL_SCREEN_LYRIC_SECONDARY_MODE = stringPreferencesKey("full_screen_lyric_secondary_mode")
        // ， true
        private val KEY_FULL_SCREEN_KARAOKE_ADVANCED_EFFECT = booleanPreferencesKey("full_screen_karaoke_advanced_effect")
        // and  (dp), default 24
        private val KEY_FULL_SCREEN_LYRIC_LINE_SPACING = intPreferencesKey("full_screen_lyric_line_spacing")
        // and / (dp), default 6
        private val KEY_FULL_SCREEN_LYRIC_SECONDARY_SPACING = intPreferencesKey("full_screen_lyric_secondary_spacing")
        // Play and ， FullPlayerCardLayout
        private val KEY_FULL_PLAYER_CARD_LAYOUT = stringPreferencesKey("full_player_card_layout")
        // SuperLyric ， false
        private val KEY_SUPER_LYRIC_ENABLED = booleanPreferencesKey("super_lyric_enabled")
        // LyricInfo ， true
        private val KEY_LYRIC_INFO_ENABLED = booleanPreferencesKey("lyric_info_enabled")
        // (AVRCP), default false
        private val KEY_BLUETOOTH_LYRIC_ENABLED = booleanPreferencesKey("bluetooth_lyric_enabled")
        // Lyricon ， false
        private val KEY_LYRICON_ENABLED = booleanPreferencesKey("lyricon_enabled")
        // Status Bar Lyrics (Android 16 ), default false
        private val KEY_FLUID_CLOUD_LYRIC_ENABLED = booleanPreferencesKey("fluid_cloud_lyric_enabled")
        const val PLAYER_STYLE_MODERN = "modern"
        const val PLAYER_STYLE_CLASSIC = "classic"
        const val PLAYER_STYLE_NEW = "new"
        private val KEY_PLAYER_STYLE = stringPreferencesKey("player_style")

        // Animated album & track canvas motion artwork (Apple Music, Tidal, Community)
        private val KEY_ANIMATED_COVER_ENABLED = booleanPreferencesKey("animated_cover_enabled")
        private val KEY_CANVAS_OVER_CELLULAR = booleanPreferencesKey("canvas_over_cellular")
    }

    // Wi-Fi  Flow
    val wifiQuality: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_WIFI_QUALITY] ?: "lossless"
    }

    suspend fun saveWifiQuality(quality: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_WIFI_QUALITY] = quality
        }
    }

    // Flow
    val mobileQuality: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_MOBILE_QUALITY] ?: "standard"
    }

    suspend fun saveMobileQuality(quality: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MOBILE_QUALITY] = quality
        }
    }

    // Playlists Flow
    val defaultPlaylistPrivate: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PLAYLIST_PRIVATE] ?: false
    }

    suspend fun saveDefaultPlaylistPrivate(private: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_PLAYLIST_PRIVATE] = private
        }
    }

    // Search Flow
    val defaultSearchSource: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_SEARCH_SOURCE] ?: "netease"
    }

    suspend fun saveDefaultSearchSource(source: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_SEARCH_SOURCE] = source
        }
    }

    // Flow
    val streamCacheEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_STREAM_CACHE_ENABLED] ?: false
    }

    suspend fun saveStreamCacheEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_STREAM_CACHE_ENABLED] = enabled
        }
    }

    // CustomDownload Folder Uri（SAF tree Uri ）， null， of  Mimikyu/
    val downloadFolderUri: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_FOLDER_URI]
    }

    suspend fun saveDownloadFolderUri(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uri != null) prefs[KEY_DOWNLOAD_FOLDER_URI] = uri
            else prefs.remove(KEY_DOWNLOAD_FOLDER_URI)
        }
    }

    // Download Lyrics Flow
    val downloadLyricsEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_LYRICS_ENABLED] ?: false
    }

    suspend fun saveDownloadLyricsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DOWNLOAD_LYRICS_ENABLED] = enabled
        }
    }

    // Audio Cache Flow
    val audioCacheMaxSize: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUDIO_CACHE_MAX_SIZE] ?: (500 * 1024 * 1024L) // 500MB
    }

    suspend fun saveAudioCacheMaxSize(size: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUDIO_CACHE_MAX_SIZE] = size
        }
    }

    // IP  Flow
    val useRealIp: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_USE_REAL_IP] ?: false
    }

    suspend fun saveUseRealIp(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_USE_REAL_IP] = enabled
        }
    }

    // Custom IP  Flow
    val realIpValue: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_REAL_IP_VALUE] ?: ""
    }

    suspend fun saveRealIpValue(ip: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_REAL_IP_VALUE] = ip
        }
    }

    // Autoplay recommended songs Flow
    val autoPlayNext: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUTO_PLAY_NEXT] ?: true
    }

    suspend fun saveAutoPlayNext(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUTO_PLAY_NEXT] = enabled
        }
    }

    // Play alongside other apps Flow
    val playWithOtherApps: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_PLAY_WITH_OTHER_APPS] ?: false
    }

    suspend fun savePlayWithOtherApps(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PLAY_WITH_OTHER_APPS] = enabled
        }
    }

    // Resume after external audio stops Flow
    val resumeAfterExternalInterruption: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_RESUME_AFTER_EXTERNAL_INTERRUPTION] ?: false
    }

    suspend fun saveResumeAfterExternalInterruption(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_RESUME_AFTER_EXTERNAL_INTERRUPTION] = enabled
        }
    }

    // Flow
    val crossfadeEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CROSSFADE_ENABLED] ?: false
    }

    suspend fun saveCrossfadeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CROSSFADE_ENABLED] = enabled
        }
    }

    // Flow
    val crossfadeDurationMs: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CROSSFADE_DURATION_MS] ?: CrossfadePolicy.DEFAULT_DURATION_MS
    }

    suspend fun saveCrossfadeDurationMs(durationMs: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CROSSFADE_DURATION_MS] = CrossfadePolicy.normalizeDurationMs(durationMs)
        }
    }

    // Stream over Wi-Fi only Flow
    val wifiOnlyPlay: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_WIFI_ONLY_PLAY] ?: false
    }

    suspend fun saveWifiOnlyPlay(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_WIFI_ONLY_PLAY] = enabled
        }
    }

    // Cellular playback warning Flow
    val mobileAlert: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_MOBILE_ALERT] ?: true
    }

    suspend fun saveMobileAlert(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MOBILE_ALERT] = enabled
        }
    }

    // Use proxy server Flow
    val useProxy: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_USE_PROXY] ?: false
    }

    suspend fun saveUseProxy(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_USE_PROXY] = enabled
        }
    }

    // Flow
    val showDesktopLrc: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_SHOW_DESKTOP_LRC] ?: false
    }

    suspend fun saveShowDesktopLrc(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SHOW_DESKTOP_LRC] = enabled
        }
    }

    // Enable system lock screen Flow
    val showLockscreen: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_SHOW_LOCKSCREEN] ?: true
    }

    suspend fun saveShowLockscreen(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SHOW_LOCKSCREEN] = enabled
        }
    }

    // Car Bluetooth auto resume Flow
    val carMode: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CAR_MODE] ?: false
    }

    suspend fun saveCarMode(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CAR_MODE] = enabled
        }
    }

    // CreatePlaylists Flow
    val showCreateEntry: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_SHOW_CREATE_ENTRY] ?: true
    }

    suspend fun saveShowCreateEntry(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SHOW_CREATE_ENTRY] = enabled
        }
    }

    // Flow
    val lyricTextSize: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LYRIC_TEXT_SIZE] ?: 14
    }

    suspend fun saveLyricTextSize(size: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LYRIC_TEXT_SIZE] = size
        }
    }

    // Flow
    val lyricTextColor: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LYRIC_TEXT_COLOR] ?: "#FFFFFF"
    }

    suspend fun saveLyricTextColor(color: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LYRIC_TEXT_COLOR] = color
        }
    }

    // Flow， AppLogger.LogLevel  of  name（DEBUG/INFO/WARN/ERROR）
    val logLevel: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LOG_LEVEL] ?: if (BuildConfig.DEBUG) "DEBUG" else "WARN"
    }

    suspend fun saveLogLevel(level: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LOG_LEVEL] = level
        }
    }

    // Flow
    val autoCheckUpdateEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUTO_CHECK_UPDATE] ?: true
    }

    suspend fun saveAutoCheckUpdateEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUTO_CHECK_UPDATE] = enabled
        }
    }

    // Flow
    val allowPrereleaseChannel: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_ALLOW_PRERELEASE_CHANNEL] ?: false
    }

    suspend fun saveAllowPrereleaseChannel(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_ALLOW_PRERELEASE_CHANNEL] = enabled
        }
    }

    // of  tag Flow
    val ignoredUpdateTag: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_IGNORED_UPDATE_TAG] ?: ""
    }

    suspend fun saveIgnoredUpdateTag(tag: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_IGNORED_UPDATE_TAG] = tag
        }
    }

    // Flow
    val fullScreenLyricTextSize: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        val saved = prefs[KEY_FULL_SCREEN_LYRIC_TEXT_SIZE]
        if (saved == null || saved <= 26) 32 else saved
    }

    suspend fun saveFullScreenLyricTextSize(size: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_LYRIC_TEXT_SIZE] = size
        }
    }

    // Flow
    val fullScreenLyricAlignment: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_LYRIC_ALIGNMENT] ?: "left"
    }

    suspend fun saveFullScreenLyricAlignment(alignment: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_LYRIC_ALIGNMENT] = alignment
        }
    }

    // Flow
    val fullScreenLyricShowTranslation: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_LYRIC_SHOW_TRANSLATION] ?: true
    }

    suspend fun saveFullScreenLyricShowTranslation(show: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_LYRIC_SHOW_TRANSLATION] = show
        }
    }

    // Flow（"translation": Translation, "roma": Romaji, "none"：）
    val fullScreenLyricSecondaryMode: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_LYRIC_SECONDARY_MODE] ?: if (prefs[KEY_FULL_SCREEN_LYRIC_SHOW_TRANSLATION] == false) "none" else "translation"
    }

    suspend fun saveFullScreenLyricSecondaryMode(mode: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_LYRIC_SECONDARY_MODE] = mode
            prefs[KEY_FULL_SCREEN_LYRIC_SHOW_TRANSLATION] = mode != "none"
        }
    }

    // Flow
    val fullScreenKaraokeAdvancedEffect: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_KARAOKE_ADVANCED_EFFECT] ?: true
    }

    suspend fun saveFullScreenKaraokeAdvancedEffect(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_KARAOKE_ADVANCED_EFFECT] = enabled
        }
    }

    // and  Flow
    val fullScreenLyricLineSpacing: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_LYRIC_LINE_SPACING] ?: 24
    }

    suspend fun saveFullScreenLyricLineSpacing(spacing: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_LYRIC_LINE_SPACING] = spacing
        }
    }

    // and / Flow
    val fullScreenLyricSecondarySpacing: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_LYRIC_SECONDARY_SPACING] ?: 6
    }

    suspend fun saveFullScreenLyricSecondarySpacing(spacing: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_LYRIC_SECONDARY_SPACING] = spacing
        }
    }

    // Play and  Flow
    val fullPlayerCardLayout: Flow<List<FullPlayerCardSetting>> = context.settingsDataStore.data.map { prefs ->
        FullPlayerCardLayout.decode(prefs[KEY_FULL_PLAYER_CARD_LAYOUT])
    }

    suspend fun saveFullPlayerCardLayout(layout: List<FullPlayerCardSetting>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FULL_PLAYER_CARD_LAYOUT] = FullPlayerCardLayout.encode(layout)
        }
    }

    // SuperLyric  Flow
    val superLyricEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_SUPER_LYRIC_ENABLED] ?: false
    }

    suspend fun saveSuperLyricEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SUPER_LYRIC_ENABLED] = enabled
        }
    }

    // LyricInfo  Flow
    val lyricInfoEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LYRIC_INFO_ENABLED] ?: true
    }

    suspend fun saveLyricInfoEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LYRIC_INFO_ENABLED] = enabled
        }
    }

    // (AVRCP) Flow
    val bluetoothLyricEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_BLUETOOTH_LYRIC_ENABLED] ?: false
    }

    suspend fun saveBluetoothLyricEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_BLUETOOTH_LYRIC_ENABLED] = enabled
        }
    }

    // Lyricon  Flow
    val lyriconEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LYRICON_ENABLED] ?: false
    }

    suspend fun saveLyriconEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LYRICON_ENABLED] = enabled
        }
    }

    // Status Bar Lyrics Flow
    val fluidCloudLyricEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FLUID_CLOUD_LYRIC_ENABLED] ?: false
    }

    suspend fun saveFluidCloudLyricEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_FLUID_CLOUD_LYRIC_ENABLED] = enabled
        }
    }

    val audiometaApiKey: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUDIOMETA_API_KEY] ?: ""
    }

    suspend fun saveAudiometaApiKey(key: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUDIOMETA_API_KEY] = key.trim()
        }
    }

    val zohoLyricsBaseUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_ZOHO_LYRICS_BASE_URL]?.ifBlank { null } ?: com.lin0721.linmusic.BuildConfig.SUPABASE_LYRICS_BASE_URL
    }

    suspend fun saveZohoLyricsBaseUrl(url: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_ZOHO_LYRICS_BASE_URL] = url.trim()
        }
    }

    val deezerArl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_DEEZER_ARL] ?: ""
    }

    suspend fun saveDeezerArl(arl: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DEEZER_ARL] = arl.trim()
        }
    }

    val tidalToken: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_TIDAL_TOKEN] ?: ""
    }

    suspend fun saveTidalToken(token: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_TIDAL_TOKEN] = token.trim()
        }
    }

    // (±ms)
    fun getSongLyricOffset(songId: Long): Flow<Long> = context.settingsDataStore.data.map { prefs ->
        if (songId <= 0L) 0L else prefs[longPreferencesKey("lyric_offset_$songId")] ?: 0L
    }

    suspend fun saveSongLyricOffset(songId: Long, offsetMs: Long) {
        if (songId <= 0L) return
        context.settingsDataStore.edit { prefs ->
            prefs[longPreferencesKey("lyric_offset_$songId")] = offsetMs
        }
    }

    val playerStyle: Flow<String> = context.settingsDataStore.data.map { prefs ->
        PLAYER_STYLE_CLASSIC
    }

    suspend fun savePlayerStyle(style: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PLAYER_STYLE] = style
        }
    }

    val animatedCoverEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_ANIMATED_COVER_ENABLED] ?: true
    }

    suspend fun saveAnimatedCoverEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_ANIMATED_COVER_ENABLED] = enabled
        }
    }

    val canvasOverCellular: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CANVAS_OVER_CELLULAR] ?: false
    }

    suspend fun saveCanvasOverCellular(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CANVAS_OVER_CELLULAR] = enabled
        }
    }
}


