package com.lin0721.linmusic.feature.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.BuildConfig
import com.lin0721.linmusic.core.player.CrossfadePolicy
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.feature.settings.data.UserBindingItem
import com.lin0721.linmusic.feature.settings.data.UserLevelData
import com.lin0721.linmusic.core.auth.AuthRepository
import com.lin0721.linmusic.feature.settings.data.SettingsRepository
import com.lin0721.linmusic.core.player.AudioCacheManager
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "SettingsViewModel"

@OptIn(FlowPreview::class)
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val settingsPreferences: SettingsPreferences,
    private val userPreferences: UserPreferences,
    private val authRepository: AuthRepository,
    private val resourceProvider: ResourceProvider
) : ViewModel() {

    // DataStore  of  StateFlow，
    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> = stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = initial
    )

    // ，
    private fun launchSave(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ───  ───
    val wifiQuality = settingsPreferences.wifiQuality.asState("lossless")

    val mobileQuality = settingsPreferences.mobileQuality.asState("standard")

    val useRealIp = settingsPreferences.useRealIp.asState(false)

    val realIpValue = settingsPreferences.realIpValue.asState("")

    val defaultPlaylistPrivate = settingsPreferences.defaultPlaylistPrivate.asState(false)

    val streamCacheEnabled = settingsPreferences.streamCacheEnabled.asState(false)

    val audioCacheMaxSize = settingsPreferences.audioCacheMaxSize.asState(500 * 1024 * 1024L)

    val downloadFolderUri = settingsPreferences.downloadFolderUri.asState(null)

    val downloadLyricsEnabled = settingsPreferences.downloadLyricsEnabled.asState(false)

    val autoPlayNext = settingsPreferences.autoPlayNext.asState(true)

    val playWithOtherApps = settingsPreferences.playWithOtherApps.asState(false)

    val resumeAfterExternalInterruption = settingsPreferences.resumeAfterExternalInterruption.asState(false)

    val crossfadeEnabled = settingsPreferences.crossfadeEnabled.asState(false)

    val automixEnabled = settingsPreferences.automixEnabled.asState(true)

    val crossfadeDurationMs = settingsPreferences.crossfadeDurationMs.asState(CrossfadePolicy.DEFAULT_DURATION_MS)

    val playerStyle = settingsPreferences.playerStyle.asState(SettingsPreferences.PLAYER_STYLE_CLASSIC)

    val animatedCoverEnabled = settingsPreferences.animatedCoverEnabled.asState(true)

    val canvasOverCellular = settingsPreferences.canvasOverCellular.asState(true)

    val wifiOnlyPlay = settingsPreferences.wifiOnlyPlay.asState(false)

    val mobileAlert = settingsPreferences.mobileAlert.asState(true)

    val useProxy = settingsPreferences.useProxy.asState(false)

    val showDesktopLrc = settingsPreferences.showDesktopLrc.asState(false)

    val showLockscreen = settingsPreferences.showLockscreen.asState(false)

    val carMode = settingsPreferences.carMode.asState(false)

    val showCreateEntry = settingsPreferences.showCreateEntry.asState(true)

    val deezerArl = settingsPreferences.deezerArl.asState("")

    val tidalToken = settingsPreferences.tidalToken.asState("")

    val lyricTextSize = settingsPreferences.lyricTextSize.asState(18)

    val lyricTextColor = settingsPreferences.lyricTextColor.asState("#FFFFFF")

    val fullScreenLyricTextSize = settingsPreferences.fullScreenLyricTextSize.asState(32)

    val fullScreenLyricAlignment = settingsPreferences.fullScreenLyricAlignment.asState("center")

    val fullScreenLyricShowTranslation = settingsPreferences.fullScreenLyricShowTranslation.asState(true)

    val fullScreenLyricSecondaryMode = settingsPreferences.fullScreenLyricSecondaryMode.asState("translation")

    val fullScreenKaraokeAdvancedEffect = settingsPreferences.fullScreenKaraokeAdvancedEffect.asState(true)

    val superLyricEnabled = settingsPreferences.superLyricEnabled.asState(true)

    val lyricInfoEnabled = settingsPreferences.lyricInfoEnabled.asState(true)

    val bluetoothLyricEnabled = settingsPreferences.bluetoothLyricEnabled.asState(false)

    val lyriconEnabled = settingsPreferences.lyriconEnabled.asState(false)

    val fluidCloudLyricEnabled = settingsPreferences.fluidCloudLyricEnabled.asState(false)

    val logLevel = settingsPreferences.logLevel.asState(if (BuildConfig.DEBUG) "DEBUG" else "WARN")

    val autoCheckUpdateEnabled = settingsPreferences.autoCheckUpdateEnabled.asState(true)

    val allowPrereleaseChannel = settingsPreferences.allowPrereleaseChannel.asState(false)

    // ───  ───
    private val _userLevel = MutableStateFlow<UserLevelData?>(null)
    val userLevel = _userLevel.asStateFlow()

    private val _userBindings = MutableStateFlow<List<UserBindingItem>>(emptyList())
    val userBindings = _userBindings.asStateFlow()

    // and Messages
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    // ───  ───
    private val _audioCacheSize = MutableStateFlow(0L)
    val audioCacheSize = _audioCacheSize.asStateFlow()

    private val _imageCacheSize = MutableStateFlow(0L)
    val imageCacheSize = _imageCacheSize.asStateFlow()

    private val _logCacheSize = MutableStateFlow(0L)
    val logCacheSize = _logCacheSize.asStateFlow()

    private val _updatePackageSize = MutableStateFlow(0L)
    val updatePackageSize = _updatePackageSize.asStateFlow()

    private val _otherCacheSize = MutableStateFlow(0L)
    val otherCacheSize = _otherCacheSize.asStateFlow()

    val totalCacheSize = combine(
        _audioCacheSize, _imageCacheSize, _logCacheSize, _updatePackageSize, _otherCacheSize
    ) { audio, image, log, update, other -> audio + image + log + update + other }
        .asState(0L)


    private val _nicknameInput = MutableStateFlow("")
    val nicknameInput = _nicknameInput.asStateFlow()

    private val _isNicknameDuplicated = MutableStateFlow<Boolean?>(null)
    val isNicknameDuplicated = _isNicknameDuplicated.asStateFlow()

    init {
        // (500ms )
        _nicknameInput
            .debounce(500)
            .filter { it.isNotBlank() }
            .distinctUntilChanged()
            .onEach { name ->
                val currentName = userPreferences.userProfile.first()?.nickname
                if (name == currentName) {
                    _isNicknameDuplicated.value = false
                    return@onEach
                }
                settingsRepository.checkNickname(name).collect { result ->
                    result.onSuccess { duplicated ->
                        _isNicknameDuplicated.value = duplicated
                    }.onFailure {
                        _isNicknameDuplicated.value = null
                    }
                }
            }
            .launchIn(viewModelScope)


        fetchRemoteSettingsData()
    }

    // ───  ───

    fun updateWifiQuality(quality: String) = launchSave { settingsPreferences.saveWifiQuality(quality) }

    fun updateMobileQuality(quality: String) = launchSave { settingsPreferences.saveMobileQuality(quality) }

    fun updateUseRealIp(enabled: Boolean) = launchSave { settingsPreferences.saveUseRealIp(enabled) }

    fun updateRealIpValue(ip: String) = launchSave { settingsPreferences.saveRealIpValue(ip) }

    fun updateDefaultPlaylistPrivate(private: Boolean) = launchSave { settingsPreferences.saveDefaultPlaylistPrivate(private) }

    fun updateStreamCacheEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveStreamCacheEnabled(enabled) }

    // null ResetDownload Folder
    fun updateDownloadFolderUri(uri: String?) = launchSave { settingsPreferences.saveDownloadFolderUri(uri) }

    fun updateDownloadLyricsEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveDownloadLyricsEnabled(enabled) }

    fun updateAudioCacheMaxSize(context: Context, size: Long) {
        viewModelScope.launch {
            settingsPreferences.saveAudioCacheMaxSize(size)
            AudioCacheManager.recreateCache(context, size)
        }
    }

    // AppLogger ， App
    fun updateLogLevel(level: AppLogger.LogLevel) {
        viewModelScope.launch {
            settingsPreferences.saveLogLevel(level.name)
            AppLogger.setLevel(level)
        }
    }

    fun updateAutoPlayNext(enabled: Boolean) = launchSave { settingsPreferences.saveAutoPlayNext(enabled) }

    fun updatePlayWithOtherApps(enabled: Boolean) = launchSave { settingsPreferences.savePlayWithOtherApps(enabled) }

    fun updateResumeAfterExternalInterruption(enabled: Boolean) = launchSave { settingsPreferences.saveResumeAfterExternalInterruption(enabled) }

    fun updateCrossfadeEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveCrossfadeEnabled(enabled) }

    fun updateAutomixEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveAutomixEnabled(enabled) }

    fun updateCrossfadeDurationMs(durationMs: Int) = launchSave { settingsPreferences.saveCrossfadeDurationMs(durationMs) }

    fun updatePlayerStyle(style: String) = launchSave { settingsPreferences.savePlayerStyle(style) }

    fun updateAnimatedCoverEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveAnimatedCoverEnabled(enabled) }

    fun updateCanvasOverCellular(enabled: Boolean) = launchSave { settingsPreferences.saveCanvasOverCellular(enabled) }

    fun updateWifiOnlyPlay(enabled: Boolean) = launchSave { settingsPreferences.saveWifiOnlyPlay(enabled) }

    fun updateMobileAlert(enabled: Boolean) = launchSave { settingsPreferences.saveMobileAlert(enabled) }

    fun updateUseProxy(enabled: Boolean) = launchSave { settingsPreferences.saveUseProxy(enabled) }

    fun updateShowDesktopLrc(enabled: Boolean) = launchSave { settingsPreferences.saveShowDesktopLrc(enabled) }

    fun updateShowLockscreen(enabled: Boolean) = launchSave { settingsPreferences.saveShowLockscreen(enabled) }

    fun updateCarMode(enabled: Boolean) = launchSave { settingsPreferences.saveCarMode(enabled) }

    fun updateShowCreateEntry(enabled: Boolean) = launchSave { settingsPreferences.saveShowCreateEntry(enabled) }

    fun updateLyricTextSize(size: Int) = launchSave { settingsPreferences.saveLyricTextSize(size) }

    fun updateLyricTextColor(color: String) = launchSave { settingsPreferences.saveLyricTextColor(color) }

    fun updateFullScreenLyricTextSize(size: Int) = launchSave { settingsPreferences.saveFullScreenLyricTextSize(size) }

    fun updateFullScreenLyricAlignment(alignment: String) = launchSave { settingsPreferences.saveFullScreenLyricAlignment(alignment) }
    
    fun updateFullScreenLyricShowTranslation(show: Boolean) = launchSave { settingsPreferences.saveFullScreenLyricShowTranslation(show) }
    
    fun updateFullScreenLyricSecondaryMode(mode: String) = launchSave { settingsPreferences.saveFullScreenLyricSecondaryMode(mode) }
    
    fun updateFullScreenKaraokeAdvancedEffect(enabled: Boolean) = launchSave { settingsPreferences.saveFullScreenKaraokeAdvancedEffect(enabled) }

    fun updateSuperLyricEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveSuperLyricEnabled(enabled) }

    fun updateLyricInfoEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveLyricInfoEnabled(enabled) }

    fun updateBluetoothLyricEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveBluetoothLyricEnabled(enabled) }

    fun updateLyriconEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveLyriconEnabled(enabled) }

    fun updateFluidCloudLyricEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveFluidCloudLyricEnabled(enabled) }

    fun updateAutoCheckUpdateEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveAutoCheckUpdateEnabled(enabled) }

    fun updateAllowPrereleaseChannel(enabled: Boolean) = launchSave { settingsPreferences.saveAllowPrereleaseChannel(enabled) }

    fun onNicknameInputChanged(name: String) {
        _nicknameInput.value = name
        if (name.isBlank()) {
            _isNicknameDuplicated.value = null
        }
    }

    // ───  ───

    fun fetchRemoteSettingsData() {
        viewModelScope.launch {
            _isLoading.value = true
            val profile = userPreferences.userProfile.first() ?: return@launch


            launch {
                settingsRepository.getUserLevel().collect { res ->
                    res.onSuccess { _userLevel.value = it }
                }
            }
            launch {
                settingsRepository.getUserBindings(profile.uid).collect { res ->
                    res.onSuccess { _userBindings.value = it }
                }
            }
            _isLoading.value = false
        }
    }


    fun executeDailySignin(type: Int = 0) {
        viewModelScope.launch {
            settingsRepository.dailySignin(type).collect { res ->
                res.onSuccess { point ->
                    if (point > 0) {
                        _toastEvent.emit("Sign-in successful! +$point points")
                    } else {
                        _toastEvent.emit("Already signed in today")
                    }
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
            }
        }
    }


    fun uploadUserAvatar(file: java.io.File) {
        viewModelScope.launch {
            _isLoading.value = true
            settingsRepository.uploadAvatar(file).collect { res ->
                res.onSuccess { newUrl ->

                    val currentProfile = userPreferences.userProfile.first()
                    if (currentProfile != null) {
                        userPreferences.saveUserProfile(currentProfile.copy(avatarUrl = newUrl))
                    }
                    _toastEvent.emit("Avatar updated successfully")
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
                _isLoading.value = false
            }
        }
    }

    // Save ()
    fun saveProfileChanges(nickname: String, signature: String, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            settingsRepository.updateUserProfile(
                nickname = nickname,
                gender = 1,
                birthday = 0L,
                province = 110000,
                city = 110101,
                signature = signature
            ).collect { res ->
                res.onSuccess {
                    // user profile nickname
                    val currentProfile = userPreferences.userProfile.first()
                    if (currentProfile != null) {
                        userPreferences.saveUserProfile(
                            currentProfile.copy(nickname = nickname)
                        )
                    }
                    _toastEvent.emit("Profile saved successfully")
                    onFinished(true)
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                    onFinished(false)
                }
                _isLoading.value = false
            }
        }
    }

    // ，Clear preferences
    fun executeLogout(onFinished: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout().collect { }
            userPreferences.clearUserProfile()
            _toastEvent.emit("Successfully logged out")
            onFinished()
        }
    }

    // ───  ───

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearApplicationCache(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                // 0.  ExoPlayer
                AudioCacheManager.clearCache(context)

                // 1.  Coil Image Cache
                val imageLoader = coil.Coil.imageLoader(context)
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()

                // 2.  of  cacheDir
                val cacheDir = context.cacheDir
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    cacheDir.listFiles()?.forEach { file ->
                        file.deleteRecursively()
                    }
                }

                // 3.  ( ExoPlayer  of )
                val extCacheDir = context.externalCacheDir
                if (extCacheDir != null && extCacheDir.exists() && extCacheDir.isDirectory) {
                    extCacheDir.listFiles()?.forEach { file ->
                        file.deleteRecursively()
                    }
                }

                // 4.  App Download of Install
                context.getExternalFilesDir(null)?.let { dir ->
                    File(dir, "updates").listFiles()?.forEach { it.deleteRecursively() }
                }
            }.onFailure { AppLogger.e(TAG, "Failed to clear app cache", it) }
            _audioCacheSize.value = 0L
            _imageCacheSize.value = 0L
            _logCacheSize.value = 0L
            _updatePackageSize.value = 0L
            _otherCacheSize.value = 0L
            _toastEvent.emit("Temporary data and image cache cleared")
            _isLoading.value = false
        }
    }

    // ，
    fun loadStorageStats(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _audioCacheSize.value = AudioCacheManager.getCacheDirSize(context)
            _imageCacheSize.value = dirSize(File(context.cacheDir, "image_cache"))
            _logCacheSize.value = AppLogger.getLogsSize()
            _updatePackageSize.value = context.getExternalFilesDir(null)?.let { dirSize(File(it, "updates")) } ?: 0L
            _otherCacheSize.value = otherTempFilesSize(context)
        }
    }

    private fun dirSize(dir: File): Long =
        if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    // cacheDir/externalCacheDir  of  audio_cache/image_cache/logs  of
    private fun otherTempFilesSize(context: Context): Long {
        val excluded = setOf("audio_cache", "image_cache", "logs")
        val cacheDirTotal = context.cacheDir.listFiles()
            ?.filter { it.name !in excluded }
            ?.sumOf { dirSize(it) } ?: 0L
        val extCacheDirTotal = context.externalCacheDir?.listFiles()
            ?.sumOf { dirSize(it) } ?: 0L
        return cacheDirTotal + extCacheDirTotal
    }

    fun clearAudioCacheOnly(context: Context) {
        viewModelScope.launch {
            runCatching { AudioCacheManager.clearCache(context) }
                .onFailure { AppLogger.e(TAG, "Failed to clear audio cache", it) }
            _audioCacheSize.value = 0L
            _toastEvent.emit("Audio cache cleared")
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearImageCacheOnly(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val imageLoader = coil.Coil.imageLoader(context)
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            }.onFailure { AppLogger.e(TAG, "Failed to clear image cache", it) }
            _imageCacheSize.value = 0L
            _toastEvent.emit("Image cache cleared")
        }
    }

    fun clearLogCacheOnly() {
        viewModelScope.launch {
            AppLogger.clearLogs()
            _logCacheSize.value = 0L
            _toastEvent.emit("Log files cleared")
        }
    }

    fun clearUpdatePackages(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.getExternalFilesDir(null)?.let { dir ->
                    File(dir, "updates").listFiles()?.forEach { it.deleteRecursively() }
                }
            }.onFailure { AppLogger.e(TAG, "Failed to clear update packages", it) }
            _updatePackageSize.value = 0L
            _toastEvent.emit("Update packages cleared")
        }
    }

    fun clearOtherTempFiles(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val excluded = setOf("audio_cache", "image_cache", "logs")
            runCatching {
                context.cacheDir.listFiles()?.filter { it.name !in excluded }?.forEach { it.deleteRecursively() }
                context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            }.onFailure { AppLogger.e(TAG, "Failed to clear other temp files", it) }
            _otherCacheSize.value = 0L
            _toastEvent.emit("Other temporary files cleared")
        }
    }

    fun updateDeezerArl(arl: String) {
        viewModelScope.launch {
            settingsPreferences.saveDeezerArl(arl)
            _toastEvent.emit("Deezer ARL updated")
        }
    }

    fun updateTidalToken(token: String) {
        viewModelScope.launch {
            settingsPreferences.saveTidalToken(token)
            _toastEvent.emit("Tidal Token updated")
        }
    }
}
