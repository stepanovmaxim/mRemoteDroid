package com.mremotedroid.app.launch

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.mremotedroid.app.MainActivity
import com.mremotedroid.app.R
import com.mremotedroid.app.data.db.NodeEntity

/** Home-screen shortcuts for connections; a shortcut carries only the node id. */
object ConnectionShortcuts {
    const val ACTION_CONNECT = "com.mremotedroid.app.action.CONNECT"
    const val EXTRA_NODE_ID = "nodeId"

    private fun info(context: Context, node: NodeEntity): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_CONNECT)
            .putExtra(EXTRA_NODE_ID, node.id)
        return ShortcutInfo.Builder(context, node.id)
            .setShortLabel(node.name.ifBlank { node.hostname }.take(25))
            .setLongLabel(node.name.ifBlank { node.hostname })
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    }

    /** Asks the launcher to pin a shortcut; false if the launcher does not support it. */
    fun pin(context: Context, node: NodeEntity): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return manager.requestPinShortcut(info(context, node), null)
    }

    /** Publishes the most recent connections to the app icon's long-press menu. */
    fun publishRecent(context: Context, recent: List<NodeEntity>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val limit = manager.maxShortcutCountPerActivity.coerceAtMost(4)
        manager.dynamicShortcuts = recent.take(limit).map { info(context, it) }
    }
}
