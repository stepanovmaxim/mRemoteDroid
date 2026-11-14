package com.mremotedroid.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: String)
}
