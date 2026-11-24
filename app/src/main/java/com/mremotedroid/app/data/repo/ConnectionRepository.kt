package com.mremotedroid.app.data.repo

import com.mremotedroid.app.data.crypto.CredentialCrypto
import com.mremotedroid.app.data.db.CredentialDao
import com.mremotedroid.app.data.db.CredentialEntity
import com.mremotedroid.app.data.db.NodeDao
import com.mremotedroid.app.data.db.NodeEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * A flattened tree row ready for rendering in a lazy list.
 * Only the visible rows (respecting collapsed folders) are emitted.
 */
data class TreeRow(
    val node: NodeEntity,
    val depth: Int,
    val hasChildren: Boolean
)

/** The login a connection actually uses, with passwords still encrypted. */
data class ResolvedLogin(
    val username: String,
    val domain: String,
    val credentialBlob: String?,
    val gatewayCredentialBlob: String?,
    val privateKeyBlob: String? = null,
    /** The SSH connection to go through, with its own login; null = direct. */
    val tunnel: ResolvedTunnel? = null
) {
    val hasStoredPassword: Boolean
        get() = credentialBlob != null || gatewayCredentialBlob != null || privateKeyBlob != null ||
            tunnel?.login?.hasStoredPassword == true
}

/** The SSH server a connection is tunnelled through. */
data class ResolvedTunnel(val node: NodeEntity, val login: ResolvedLogin)

class ConnectionRepository(
    private val dao: NodeDao,
    private val credentials: CredentialDao
) {

    fun observeAll(): Flow<List<NodeEntity>> = dao.observeAll()

    suspend fun getById(id: String): NodeEntity? = dao.getById(id)

    suspend fun isEmpty(): Boolean = dao.count() == 0

    suspend fun saveConnection(
        existing: NodeEntity?,
        parentId: String?,
        name: String,
        protocol: String,
        hostname: String,
        port: Int,
        username: String,
        domain: String,
        plainPassword: String?,
        colorDepth: Int,
        fullscreen: Boolean,
        consoleSession: Boolean,
        gatewayHostname: String,
        gatewayUsername: String,
        gatewayDomain: String,
        gatewayPlainPassword: String?,
        soundMode: Int,
        redirectFolder: Boolean,
        credentialId: String?,
        description: String,
        privateKey: String? = null,
        sshTunnelId: String? = existing?.sshTunnelId,
        portForwards: String = existing?.portForwards.orEmpty(),
        macAddress: String = existing?.macAddress.orEmpty()
    ) {
        // Re-encrypt only when the user entered a new password; otherwise keep the old blob.
        val blob = when {
            plainPassword == null -> existing?.credentialBlob
            plainPassword.isEmpty() -> null
            else -> CredentialCrypto.encrypt(plainPassword)
        }
        val gatewayBlob = when {
            gatewayPlainPassword == null -> existing?.gatewayCredentialBlob
            gatewayPlainPassword.isEmpty() -> null
            else -> CredentialCrypto.encrypt(gatewayPlainPassword)
        }
        val node = (existing ?: NodeEntity(
            id = UUID.randomUUID().toString(),
            parentId = parentId,
            nodeType = NodeEntity.TYPE_CONNECTION,
            name = name
        )).copy(
            parentId = parentId,
            name = name,
            protocol = protocol,
            hostname = hostname,
            port = port,
            username = username,
            domain = domain,
            credentialBlob = blob,
            colorDepth = colorDepth,
            fullscreen = fullscreen,
            consoleSession = consoleSession,
            gatewayHostname = gatewayHostname,
            gatewayUsername = gatewayUsername,
            gatewayDomain = gatewayDomain,
            gatewayCredentialBlob = gatewayBlob,
            soundMode = soundMode,
            redirectFolder = redirectFolder,
            credentialId = credentialId,
            description = description,
            privateKeyBlob = when {
                privateKey == null -> existing?.privateKeyBlob
                privateKey.isEmpty() -> null
                else -> CredentialCrypto.encrypt(privateKey)
            },
            sshTunnelId = sshTunnelId?.takeIf { it != existing?.id },
            portForwards = portForwards,
            macAddress = macAddress
        )
        dao.upsert(node)
    }

    suspend fun createFolder(parentId: String?, name: String) {
        dao.upsert(
            NodeEntity(
                id = UUID.randomUUID().toString(),
                parentId = parentId,
                nodeType = NodeEntity.TYPE_FOLDER,
                name = name
            )
        )
    }

    suspend fun rename(id: String, name: String) {
        dao.getById(id)?.let { dao.update(it.copy(name = name)) }
    }

    suspend fun setExpanded(id: String, expanded: Boolean) = dao.setExpanded(id, expanded)

    /** Deletes a node and, for folders, every descendant. */
    suspend fun deleteRecursive(id: String) {
        val all = dao.getAll()
        val byParent = all.groupBy { it.parentId }
        val toDelete = mutableListOf<String>()
        fun collect(nodeId: String) {
            toDelete += nodeId
            byParent[nodeId]?.forEach { collect(it.id) }
        }
        collect(id)
        dao.deleteByIds(toDelete)
        dao.clearTunnels(toDelete)
    }

    /** SSH connections that can carry other connections, for the tunnel picker. */
    suspend fun sshConnections(): List<NodeEntity> =
        dao.getAll().filter { it.nodeType == NodeEntity.TYPE_CONNECTION && it.protocol == "SSH" }
            .sortedBy { it.name.lowercase() }

    fun decrypt(blob: String?): String = CredentialCrypto.decrypt(blob)

    // --- saved logins ---

    fun observeCredentials(): Flow<List<CredentialEntity>> = credentials.observeAll()

    /** [plainPassword]: null = keep the stored one, "" = clear it. */
    suspend fun saveCredential(
        existing: CredentialEntity?,
        name: String,
        username: String,
        domain: String,
        plainPassword: String?
    ) {
        val blob = when {
            plainPassword == null -> existing?.credentialBlob
            plainPassword.isEmpty() -> null
            else -> CredentialCrypto.encrypt(plainPassword)
        }
        credentials.upsert(
            CredentialEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name,
                username = username,
                domain = domain,
                credentialBlob = blob
            )
        )
    }

    suspend fun deleteCredential(id: String) {
        dao.clearCredential(id)
        credentials.deleteById(id)
    }

    suspend fun setCredential(nodeId: String, credentialId: String?) = dao.setCredential(nodeId, credentialId)

    /** The login a connection inside [folderId] would inherit, if any. */
    suspend fun inheritedCredential(folderId: String?): CredentialEntity? =
        folderCredential(folderId, dao.getAll().associateBy { it.id }, credentials.getAll().associateBy { it.id })

    private fun folderCredential(
        folderId: String?,
        nodes: Map<String, NodeEntity>,
        saved: Map<String, CredentialEntity>
    ): CredentialEntity? {
        val seen = HashSet<String>()
        var current = folderId?.let(nodes::get)
        while (current != null && seen.add(current.id)) {
            current.credentialId?.let(saved::get)?.let { return it }
            current = current.parentId?.let(nodes::get)
        }
        return null
    }

    suspend fun resolveLogin(node: NodeEntity): ResolvedLogin =
        resolveLogin(node, dao.getAll().associateBy { it.id }, credentials.getAll().associateBy { it.id })

    private fun resolveLogin(
        node: NodeEntity,
        nodes: Map<String, NodeEntity>,
        saved: Map<String, CredentialEntity>,
        withTunnel: Boolean = true
    ): ResolvedLogin {
        // one hop only: the tunnel's own tunnel setting is not followed
        val tunnel = node.sshTunnelId?.takeIf { withTunnel && it != node.id }?.let(nodes::get)
            ?.takeIf { it.nodeType == NodeEntity.TYPE_CONNECTION && it.protocol == "SSH" }
            ?.let { ResolvedTunnel(it, resolveLogin(it, nodes, saved, withTunnel = false)) }
        val credential = when (node.credentialId) {
            null -> null
            NodeEntity.CREDENTIAL_INHERIT -> folderCredential(node.parentId, nodes, saved)
            else -> saved[node.credentialId]
        }
        return ResolvedLogin(
            username = credential?.username ?: node.username,
            domain = credential?.domain ?: node.domain,
            credentialBlob = if (credential != null) credential.credentialBlob else node.credentialBlob,
            gatewayCredentialBlob = node.gatewayCredentialBlob,
            privateKeyBlob = node.privateKeyBlob,
            tunnel = tunnel
        )
    }

    /** Sets a folder's login; [applyToChildren] switches every connection inside to inherit it. */
    suspend fun setFolderCredential(folderId: String, credentialId: String?, applyToChildren: Boolean) {
        dao.setCredential(folderId, credentialId)
        if (!applyToChildren) return
        val byParent = dao.getAll().groupBy { it.parentId }
        fun walk(parent: String): List<NodeEntity> =
            byParent[parent].orEmpty().flatMap { if (it.nodeType == NodeEntity.TYPE_FOLDER) walk(it.id) else listOf(it) }
        walk(folderId).forEach { dao.setCredential(it.id, NodeEntity.CREDENTIAL_INHERIT) }
    }

    data class MergeResult(val connections: Int, val folders: Int, val duplicates: Int)

    /**
     * Adds imported nodes without duplicating: folders with the same place and name are
     * reused, connections already in the list are skipped. Parents must precede children.
     */
    suspend fun mergeImported(imported: List<NodeEntity>): MergeResult {
        fun key(n: NodeEntity) = "${n.hostname.lowercase()}|${n.port}|${n.username.lowercase()}"
        val existing = dao.getAll()
        val folders = existing.filter { it.nodeType == NodeEntity.TYPE_FOLDER }
            .associateTo(HashMap()) { (it.parentId to it.name.lowercase()) to it.id }
        val connections = existing.filter { it.nodeType == NodeEntity.TYPE_CONNECTION }
            .associateTo(HashMap()) { key(it) to it.id }
        val ids = HashMap<String, String>()
        val added = ArrayList<NodeEntity>()
        var duplicates = 0
        imported.forEach { node ->
            val parent = node.parentId?.let(ids::get)
            if (node.nodeType == NodeEntity.TYPE_FOLDER) {
                val folderKey = parent to node.name.lowercase()
                val known = folders[folderKey]
                if (known != null) {
                    ids[node.id] = known
                } else {
                    folders[folderKey] = node.id
                    ids[node.id] = node.id
                    added += node.copy(parentId = parent)
                }
            } else {
                val known = connections[key(node)]
                if (known == null) {
                    connections[key(node)] = node.id
                    ids[node.id] = node.id
                    added += node.copy(parentId = parent)
                } else {
                    // tunnels through a skipped duplicate go through the existing connection
                    ids[node.id] = known
                    duplicates++
                }
            }
        }
        dao.upsertAll(added.map { n -> n.copy(sshTunnelId = n.sshTunnelId?.let { ids[it] ?: it }) })
        return MergeResult(
            connections = added.count { it.nodeType == NodeEntity.TYPE_CONNECTION },
            folders = added.count { it.nodeType == NodeEntity.TYPE_FOLDER },
            duplicates = duplicates
        )
    }

    // --- recent connections ---

    fun observeRecent(limit: Int): Flow<List<NodeEntity>> = dao.observeRecent(limit)

    suspend fun recent(limit: Int): List<NodeEntity> = dao.getRecent(limit)

    suspend fun markConnected(id: String) = dao.setLastConnected(id, System.currentTimeMillis())

    suspend fun allNodes(): List<NodeEntity> = dao.getAll()

    /** Replaces the entire tree (used by import-with-replace). */
    suspend fun replaceAll(nodes: List<NodeEntity>) {
        val existing = dao.getAll().map { it.id }
        dao.deleteByIds(existing)
        dao.upsertAll(nodes)
    }

    /** Adds nodes, keeping whatever is already stored (used by import-merge). */
    suspend fun addAll(nodes: List<NodeEntity>) = dao.upsertAll(nodes)

    /** Builds a mRemoteNG confCons.xml from the whole tree, re-encrypting passwords. */
    suspend fun buildExportXml(filePassword: String): String {
        // confCons.xml has no saved logins: write each connection's effective login.
        val all = dao.getAll()
        val byId = all.associateBy { it.id }
        val saved = credentials.getAll().associateBy { it.id }
        val logins = all.associate { it.id to resolveLogin(it, byId, saved) }
        val nodes = all.map { n ->
            val login = logins.getValue(n.id)
            n.copy(username = login.username, domain = login.domain)
        }
        return com.mremotedroid.app.data.importer.MRemoteNgExporter.export(
            nodes = nodes,
            plaintextPasswordOf = { CredentialCrypto.decrypt(logins[it.id]?.credentialBlob) },
            gatewayPasswordOf = { CredentialCrypto.decrypt(it.gatewayCredentialBlob) },
            filePassword = filePassword.ifBlank { com.mremotedroid.app.data.importer.MRemoteNgCrypto.DEFAULT_FILE_PASSWORD }
        )
    }

    companion object {
        /** Flattens the node list into visible rows honoring collapsed folders. */
        fun flatten(nodes: List<NodeEntity>): List<TreeRow> {
            val byParent = nodes.groupBy { it.parentId }
                .mapValues { (_, v) -> v.sortedWith(compareBy({ it.sortIndex }, { it.name.lowercase() })) }
            val rows = mutableListOf<TreeRow>()
            fun walk(parentId: String?, depth: Int) {
                byParent[parentId]?.forEach { node ->
                    val children = byParent[node.id].orEmpty()
                    rows += TreeRow(node, depth, children.isNotEmpty())
                    val isFolder = node.nodeType == NodeEntity.TYPE_FOLDER
                    if (isFolder && node.expanded) walk(node.id, depth + 1)
                }
            }
            walk(null, 0)
            return rows
        }
    }
}
