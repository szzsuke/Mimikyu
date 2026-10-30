package com.lin0721.linmusic

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lin0721.linmusic.core.download.DownloadWorkerFactory
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.log.CrashHandler
import com.lin0721.linmusic.core.update.UpdateManager
import com.lin0721.linmusic.di.downloadModule
import com.lin0721.linmusic.di.localModule
import com.lin0721.linmusic.di.localMusicModule
import com.lin0721.linmusic.di.networkModule
import com.lin0721.linmusic.di.playerModule
import com.lin0721.linmusic.di.repositoryModule
import com.lin0721.linmusic.di.updateModule
import com.lin0721.linmusic.di.metadataModule
import com.lin0721.linmusic.di.viewModelModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.android.inject
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import java.util.Locale

class MimikyuApplication : Application() {

    private val updateManager: UpdateManager by inject()
    private val downloadWorkerFactory: DownloadWorkerFactory by inject()

    override fun onCreate() {
        super.onCreate()
        val systemCountry = resources.configuration.locales.get(0)?.country?.takeIf { it.isNotBlank() } ?: "US"
        val appLocale = Locale("en", systemCountry)
        Locale.setDefault(appLocale)
        com.metrolist.innertube.YouTube.locale = com.metrolist.innertube.models.YouTubeLocale(gl = systemCountry, hl = "en")
        val config = resources.configuration
        config.setLocale(appLocale)
        createConfigurationContext(config)

        com.lin0721.linmusic.core.player.innertube.InnerTubeXPlayer.initialize(this)
        com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.initialize(this)
        // ， Koin/Coil  of  and
        AppLogger.init(this)
        CrashHandler.init(this)
        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
            val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                try {
                    val debugFactoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    val getInstanceMethod = debugFactoryClass.getMethod("getInstance")
                    val factory = getInstanceMethod.invoke(null) as com.google.firebase.appcheck.AppCheckProviderFactory
                    appCheck.installAppCheckProviderFactory(factory)
                    AppLogger.i("MimikyuApplication", "Firebase App Check initialized with DebugAppCheckProviderFactory")
                } catch (e: Exception) {
                    AppLogger.w("MimikyuApplication", "DebugAppCheckProviderFactory not available: ${e.message}")
                }
            } else {
                appCheck.installAppCheckProviderFactory(
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
                )
                AppLogger.i("MimikyuApplication", "Firebase App Check initialized with PlayIntegrityAppCheckProviderFactory")
            }
        } catch (e: Exception) {
            AppLogger.w("MimikyuApplication", "FirebaseApp / AppCheck init warning: ${e.message}")
        }

        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(com.lin0721.linmusic.core.metadata.ThumbnailUpscaleInterceptor())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(4))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(8))
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)
        // DiskLruCache.initialize()，
        Thread { imageLoader.diskCache }.start()

        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.ERROR)
            androidContext(this@MimikyuApplication)
            modules(
                networkModule, repositoryModule, viewModelModule, playerModule,
                localModule, updateModule, downloadModule, localMusicModule, metadataModule
            )
        }

        // WorkManager Custom WorkerFactory
        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(downloadWorkerFactory)
                .build()
        )

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val syncUseCase = org.koin.java.KoinJavaComponent.get<com.lin0721.linmusic.core.auth.YouTubeAccountSyncUseCase>(com.lin0721.linmusic.core.auth.YouTubeAccountSyncUseCase::class.java)
                syncUseCase.restoreSessionIfAvailable()
            }
        }
    }
}
