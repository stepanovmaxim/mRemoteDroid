package com.mremotedroid.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A saved login that connections and folders can share. */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val name: String,
    val username: String = "",
    val domain: String = "",
    /** Encrypted password produced by CredentialCrypto; null = no stored password. */
    @ColumnInfo(name = "credential_blob") val credentialBlob: String? = null
)
