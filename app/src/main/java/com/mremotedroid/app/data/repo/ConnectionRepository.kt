package com.mremotedroid.app.data.repo

import com.mremotedroid.app.data.crypto.CredentialCrypto
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

class ConnectionRepository(private val dao: NodeDao) {

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
        description: String
    ) {
        // Re-encrypt only when the user entered a new password; otherwise keep the old blob.
        val blob = when {
            plainPassword == null -> existing?.credentialBlob
            plainPassword.isEmpty() -> null
            else -> CredentialCrypto.encrypt(plainPassword)
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
            description = description
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
    }

    fun decryptPassword(node: NodeEntity): String = CredentialCrypto.decrypt(node.credentialBlob)

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
        val nodes = dao.getAll()
        return com.mremotedroid.app.data.importer.MRemoteNgExporter.export(
            nodes = nodes,
            plaintextPasswordOf = { CredentialCrypto.decrypt(it.credentialBlob) },
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
