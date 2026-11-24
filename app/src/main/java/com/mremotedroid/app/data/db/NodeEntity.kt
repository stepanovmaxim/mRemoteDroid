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
    /** Empty = the gateway uses the same login as the connection. */
    val gatewayUsername: String = "",
    val gatewayDomain: String = "",
    @ColumnInfo(name = "gateway_credential_blob") val gatewayCredentialBlob: String? = null,
    /** One of the SOUND_* constants. */
    val soundMode: Int = SOUND_OFF,
    val redirectFolder: Boolean = false,
    /** SSH private key (OpenSSH or PEM text), encrypted like passwords; null = none. */
    @ColumnInfo(name = "private_key_blob") val privateKeyBlob: String? = null,
    /** Id of the SSH connection this one is reached through (a tunnel or jump host); null = direct. */
    val sshTunnelId: String? = null,
    /** SSH port forwards, one per line, see [com.mremotedroid.app.remote.ssh.PortForward]. */
    val portForwards: String = "",
    /** MAC address for Wake-on-LAN; empty = none. */
    val macAddress: String = "",

    val description: String = "",

    /**
     * Saved login used instead of the fields above: null = this node's own fields,
     * [CREDENTIAL_INHERIT] = the nearest parent folder's login, otherwise a
     * [CredentialEntity] id. Folders hold either null or an id.
     */
    val credentialId: String? = null,
    /** Epoch millis of the last session start; 0 = never. */
    val lastConnected: Long = 0
) {
    companion object {
        const val TYPE_FOLDER = "FOLDER"
        const val TYPE_CONNECTION = "CONNECTION"
        const val CREDENTIAL_INHERIT = "inherit"
        const val SOUND_OFF = 0
        const val SOUND_DEVICE = 1
        const val SOUND_SERVER = 2
    }
}
