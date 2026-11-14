package com.mremotedroid.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {

    @Query("SELECT * FROM nodes ORDER BY sortIndex ASC, name ASC")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getById(id: String): NodeEntity?

    @Query("SELECT * FROM nodes")
    suspend fun getAll(): List<NodeEntity>

    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<NodeEntity>)

    @Update
    suspend fun update(node: NodeEntity)

    @Delete
    suspend fun delete(node: NodeEntity)

    @Query("DELETE FROM nodes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE nodes SET expanded = :expanded WHERE id = :id")
    suspend fun setExpanded(id: String, expanded: Boolean)

    @Query("UPDATE nodes SET credentialId = :credentialId WHERE id = :id")
    suspend fun setCredential(id: String, credentialId: String?)

    @Query("UPDATE nodes SET credentialId = NULL WHERE credentialId = :credentialId")
    suspend fun clearCredential(credentialId: String)

    @Query("UPDATE nodes SET lastConnected = :time WHERE id = :id")
    suspend fun setLastConnected(id: String, time: Long)

    @Query("SELECT * FROM nodes WHERE nodeType = 'CONNECTION' AND lastConnected > 0 ORDER BY lastConnected DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE nodeType = 'CONNECTION' AND lastConnected > 0 ORDER BY lastConnected DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<NodeEntity>
}
