package app.clearmark

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras

class ClearMarkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Zugriff auf die App (und damit den [AppContainer]) in ViewModel-Factories. */
fun CreationExtras.clearMarkApp(): ClearMarkApp = this[APPLICATION_KEY] as ClearMarkApp
