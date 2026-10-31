package app.clearmark.data.prefs

import android.content.Context

/** Kleine Einstellungen, z. B. ob der Hinweis beim ersten Start bestätigt wurde. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("clearmark_settings", Context.MODE_PRIVATE)

    fun isNoticeAccepted(): Boolean = prefs.getBoolean(KEY_NOTICE_ACCEPTED, false)

    fun setNoticeAccepted() {
        prefs.edit().putBoolean(KEY_NOTICE_ACCEPTED, true).apply()
    }

    private companion object {
        const val KEY_NOTICE_ACCEPTED = "notice_accepted"
    }
}
