package app.clearmark.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.clearmark.ui.editor.EditorScreen
import app.clearmark.ui.start.StartScreen

object Routes {
    const val START = "start"

    const val ARG_URI = "uri"
    const val EDITOR = "editor?$ARG_URI={$ARG_URI}"

    fun editor(uri: Uri): String = "editor?$ARG_URI=" + Uri.encode(uri.toString())
}

@Composable
fun ClearMarkNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.START) {
        composable(Routes.START) {
            StartScreen(onVideoPicked = { uri -> navController.navigate(Routes.editor(uri)) })
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument(Routes.ARG_URI) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            EditorScreen(onBack = { navController.popBackStack() })
        }
    }
}
