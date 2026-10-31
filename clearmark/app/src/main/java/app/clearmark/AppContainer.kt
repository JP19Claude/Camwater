package app.clearmark

import android.content.Context
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
}
