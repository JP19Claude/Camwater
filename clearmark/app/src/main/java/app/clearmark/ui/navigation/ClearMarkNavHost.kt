package app.clearmark.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.clearmark.ui.editor.EditorScreen
import app.clearmark.ui.result.ResultScreen
import app.clearmark.ui.start.StartScreen

object Routes {
    const val START = "start"

    const val ARG_URI = "uri"
    const val EDITOR = "editor?$ARG_URI={$ARG_URI}"

    fun editor(uri: Uri): String = "editor?$ARG_URI=" + Uri.encode(uri.toString())

    const val ARG_PATH = "path"
    const val RESULT = "result?$ARG_PATH={$ARG_PATH}"

    fun result(path: String): String = "result?$ARG_PATH=" + Uri.encode(path)
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
            EditorScreen(
                onBack = { navController.popBackStack() },
                onExported = { path -> navController.navigate(Routes.result(path)) },
            )
        }
        composable(
            route = Routes.RESULT,
            arguments = listOf(
                navArgument(Routes.ARG_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ResultScreen(
                onBack = { navController.popBackStack() },
                onNewVideo = { navController.popBackStack(Routes.START, inclusive = false) },
            )
        }
    }
}
