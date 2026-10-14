package app.camwater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Startet die Automatik nach einem Neustart oder App-Update wieder, wenn sie an war. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!PresetStore(context).autoEnabled || !Permissions.hasPhotoAccess(context)) return
        try {
            CameraWatchService.start(context)
        } catch (e: Exception) {
            Log.w("Camwater", "Automatik konnte nicht gestartet werden", e)
        }
    }
}
