package com.mremotedroid.app

import android.os.Bundle
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
