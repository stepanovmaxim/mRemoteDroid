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
        // Not gated on savedInstanceState: after a crash Android restores the task
        // with saved state. Each report file is deleted once offered, so no repeats.
        offerReport(
            MRemoteApp.CRASH_FILE,
            "Приложение аварийно закрылось",
            "Отправьте отчёт об ошибке разработчику — по нему можно найти и исправить причину. Пароли в отчёт не попадают.",
            "mRemoteDroid crash report"
        )
        // session logs are no longer collected; drop one left by an older version
        File(filesDir, "last_session_log.txt").delete()
    }

    /** Offers to share a saved report file (then deletes it). Returns true if shown. */
    private fun offerReport(fileName: String, title: String, message: String, subject: String): Boolean {
        val file = File(filesDir, fileName)
        if (!file.exists()) return false
        val report = runCatching { file.readText() }.getOrNull()
        file.delete()
        if (report.isNullOrBlank()) return false

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Отправить отчёт") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(send, "Отправить отчёт"))
            }
            .setNeutralButton("Скопировать") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(subject, report))
                Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
        return true
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
