package com.mremotedroid.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NodeEntity::class, CredentialEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `credentials` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, `domain` TEXT NOT NULL, `credential_blob` TEXT, PRIMARY KEY(`id`))"
                )
                listOf(
                    "`gatewayUsername` TEXT NOT NULL DEFAULT ''",
                    "`gatewayDomain` TEXT NOT NULL DEFAULT ''",
                    "`gateway_credential_blob` TEXT",
                    "`soundMode` INTEGER NOT NULL DEFAULT 0",
                    "`redirectFolder` INTEGER NOT NULL DEFAULT 0",
                    "`credentialId` TEXT",
                    "`lastConnected` INTEGER NOT NULL DEFAULT 0"
                ).forEach { db.execSQL("ALTER TABLE `nodes` ADD COLUMN $it") }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `nodes` ADD COLUMN `private_key_blob` TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mremotedroid.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
