package app.clearmark.data.export

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.clearmark.ClearMarkApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Vordergrunddienst, der den Prozess während eines Exports am Leben hält und den Fortschritt als
 * Benachrichtigung zeigt. Die eigentliche Arbeit macht [ExportManager] in einer Coroutine.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastPercent = -1

    private val manager: ExportManager
        get() = (application as ClearMarkApp).container.exportManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Abbrechen aus der Benachrichtigung; der Zustandswechsel beendet den Dienst.
            manager.cancel()
            if (observer == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        ExportNotifications.ensureChannel(this)
        // Nach startForegroundService muss startForeground in jedem Fall aufgerufen werden.
        try {
            ServiceCompat.startForeground(
                this,
                ExportNotifications.PROGRESS_ID,
                ExportNotifications.progress(this, percent = null, cancelIntent = cancelIntent()),
                foregroundServiceType(),
            )
        } catch (e: Exception) {
            // z. B. ForegroundServiceStartNotAllowedException: Export läuft ohne Dienst weiter.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (observer == null) {
            observer = scope.launch {
                manager.state.collect { state ->
                    if (state is ExportState.Running) {
                        updateProgress((state.progress * 100).toInt())
                    } else {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun updateProgress(percent: Int) {
        if (percent == lastPercent) return
        lastPercent = percent
        try {
            NotificationManagerCompat.from(this).notify(
                ExportNotifications.PROGRESS_ID,
                ExportNotifications.progress(this, percent, cancelIntent()),
            )
        } catch (_: SecurityException) {
            // Ohne Benachrichtigungsrecht läuft der Dienst trotzdem.
        }
    }

    private fun stopForegroundAndSelf() {
        observer?.cancel()
        observer = null
        lastPercent = -1
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun foregroundServiceType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.clearmark.action.CANCEL_EXPORT"
    }
}
