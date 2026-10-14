package app.camwater

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Permissions {
    /** Berechtigungen, die für die Automatik angefragt werden. */
    fun needed(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Darf die App alle Fotos sehen (nicht nur ausgewählte)? */
    fun hasPhotoAccess(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 14+: Nutzer hat nur "Ausgewählte Fotos" erlaubt. */
    fun hasOnlyPartialAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !hasPhotoAccess(context) &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
}
