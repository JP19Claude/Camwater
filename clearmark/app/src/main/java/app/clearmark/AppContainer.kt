package app.clearmark

import android.content.Context
import app.clearmark.data.export.ExportManager
import app.clearmark.data.ffmpeg.FfmpegRunner
import app.clearmark.data.media.MediaStoreRepository
import app.clearmark.data.media.ShareRepository
import app.clearmark.data.prefs.SettingsRepository
import app.clearmark.data.video.VideoInfoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Einfache manuelle Dependency Injection: hält die app-weiten Objekte. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Lebt so lange wie der Prozess, damit ein Export nicht am Screen hängt. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository by lazy { SettingsRepository(appContext) }
    val videoInfoRepository by lazy { VideoInfoRepository(appContext) }
    val mediaStoreRepository by lazy { MediaStoreRepository(appContext) }
    val shareRepository by lazy { ShareRepository(appContext) }
    val exportManager by lazy { ExportManager(appContext, FfmpegRunner(appContext), appScope) }
}
