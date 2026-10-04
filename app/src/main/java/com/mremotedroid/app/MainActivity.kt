package com.mremotedroid.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mremotedroid.app.data.repo.ConnectionRepository
import com.mremotedroid.app.data.settings.AppSettings
import com.mremotedroid.app.ui.edit.EditConnectionScreen
import com.mremotedroid.app.ui.edit.EditViewModel
import com.mremotedroid.app.ui.theme.MRemoteTheme
import com.mremotedroid.app.ui.tree.ConnectionTreeScreen
import com.mremotedroid.app.ui.tree.TreeViewModel

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as MRemoteApp).repository
        val settings = AppSettings(this)
        setContent { MRemoteTheme { AppNav(repo, settings) } }
        if (savedInstanceState == null) offerLastCrashReport()
    }

    /** If the previous run crashed, let the user send the saved stack trace. */
    private fun offerLastCrashReport() {
        val file = File(filesDir, MRemoteApp.CRASH_FILE)
        if (!file.exists()) return
        val report = runCatching { file.readText() }.getOrNull()
        file.delete()
        if (report.isNullOrBlank()) return

        AlertDialog.Builder(this)
            .setTitle("Приложение аварийно закрылось")
            .setMessage("Отправьте отчёт об ошибке разработчику — по нему можно найти и исправить причину. Пароли в отчёт не попадают.")
            .setPositiveButton("Отправить отчёт") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "mRemoteDroid crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(send, "Отправить отчёт"))
            }
            .setNeutralButton("Скопировать") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mRemoteDroid crash", report))
                Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}

@Composable
private fun AppNav(repo: ConnectionRepository, settings: AppSettings) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "tree") {
        composable("tree") {
            val vm: TreeViewModel = viewModel(factory = TreeViewModel.Factory(repo, settings))
            ConnectionTreeScreen(
                vm = vm,
                onAddConnection = { parentId ->
                    nav.navigate("edit?parentId=${parentId ?: ""}")
                },
                onEditConnection = { nodeId ->
                    nav.navigate("edit?nodeId=$nodeId")
                }
            )
        }
        composable(
            route = "edit?nodeId={nodeId}&parentId={parentId}",
            arguments = listOf(
                navArgument("nodeId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("parentId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            val nodeId = entry.arguments?.getString("nodeId")?.ifBlank { null }
            val parentId = entry.arguments?.getString("parentId")?.ifBlank { null }
            val vm: EditViewModel = viewModel(
                factory = EditViewModel.Factory(repo, nodeId, parentId)
            )
            EditConnectionScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
