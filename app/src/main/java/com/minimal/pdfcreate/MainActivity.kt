package com.minimal.pdfcreate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.minimal.pdfcreate.ui.camera.CameraScreen
import com.minimal.pdfcreate.ui.editor.PageEditorScreen
import com.minimal.pdfcreate.ui.home.HomeScreen
import com.minimal.pdfcreate.ui.review.ReviewScreen
import com.minimal.pdfcreate.ui.theme.ScanlyTheme
import com.minimal.pdfcreate.ui.viewer.PdfViewerScreen

object Routes {
    const val HOME = "home"
    fun camera(docId: String) = "camera/$docId"
    fun review(docId: String) = "review/$docId"
    fun editor(docId: String, pageId: String) = "editor/$docId/$pageId"
    fun viewer(docId: String) = "viewer/$docId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppContainer.init(this)
        setContent {
            ScanlyTheme {
                AppNavHost(rememberNavController())
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onCreate = { docId -> nav.navigate(Routes.camera(docId)) },
                onOpen = { docId -> nav.navigate(Routes.viewer(docId)) },
                onEdit = { docId -> nav.navigate(Routes.review(docId)) },
                // An import lands in the same place a scan does: the review screen, with the
                // new pages already there and nothing exported yet.
                onImported = { docId -> nav.navigate(Routes.review(docId)) },
            )
        }
        composable("camera/{docId}") { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            CameraScreen(
                docId = docId,
                onDone = { nav.navigate(Routes.review(docId)) { popUpTo("camera/{docId}") { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("review/{docId}") { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            ReviewScreen(
                docId = docId,
                onAddPages = { nav.navigate(Routes.camera(docId)) },
                onEditPage = { pageId -> nav.navigate(Routes.editor(docId, pageId)) },
                onSaved = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("editor/{docId}/{pageId}") { entry ->
            PageEditorScreen(
                docId = entry.arguments?.getString("docId").orEmpty(),
                pageId = entry.arguments?.getString("pageId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable("viewer/{docId}") { entry ->
            PdfViewerScreen(
                docId = entry.arguments?.getString("docId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
