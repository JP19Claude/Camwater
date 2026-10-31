package app.clearmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.clearmark.ui.navigation.ClearMarkNavHost
import app.clearmark.ui.theme.ClearMarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ClearMarkTheme {
                ClearMarkNavHost()
            }
        }
    }
}
