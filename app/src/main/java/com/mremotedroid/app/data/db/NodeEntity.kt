package com.mremotedroid.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single node in the connection tree. A node is either a folder (container)
 * or a connection (leaf). The tree is represented by [parentId] self-references;
 * a null [parentId] means the node lives at the root.
 */
@Entity(
    tableName = "nodes",
    indices = [Index("parentId")]
)
data class NodeEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    /** "FOLDER" or "CONNECTION" */
    val nodeType: String,
    val name: String,
    val sortIndex: Int = 0,
    val expanded: Boolean = true,

    // --- connection fields (ignored for folders) ---
    val protocol: String = "RDP",
    val hostname: String = "",
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    /** Encrypted password produced by CredentialCrypto; null = no stored password. */
    @ColumnInfo(name = "credential_blob") val credentialBlob: String? = null,

    // --- RDP display / session options ---
    val screenWidth: Int = 0,   // 0 = fit to device
    val screenHeight: Int = 0,
    val colorDepth: Int = 32,
    val fullscreen: Boolean = true,
    val consoleSession: Boolean = false,
    val gatewayHostname: String = "",

    val description: String = ""
) {
    companion object {
        const val TYPE_FOLDER = "FOLDER"
        const val TYPE_CONNECTION = "CONNECTION"
    }
}
