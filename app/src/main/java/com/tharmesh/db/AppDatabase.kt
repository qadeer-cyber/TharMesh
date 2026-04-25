// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer / Qadeer Cyber. All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tharmesh.db.dao.BundleDao
import com.tharmesh.db.dao.ContactDao
import com.tharmesh.db.dao.ConversationDao
import com.tharmesh.db.dao.MessageDao
import com.tharmesh.db.dao.PeerIdentityDao
import com.tharmesh.db.entity.BundleEntity
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.db.entity.PeerIdentityEntity

@Database(
    entities = [
        MessageEntity::class,
        BundleEntity::class,
        ConversationEntity::class,
        ContactEntity::class,
        PeerIdentityEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun bundleDao(): BundleDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao
    abstract fun peerIdentityDao(): PeerIdentityDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Stage 6.2 — additive migration from schema v4 → v5. Adds
         * `peer_identity.verified` (NOT NULL DEFAULT 0) and
         * `peer_identity.verifiedAtMs` (nullable). Preserves every existing
         * TOFU-pinned key so users do not have to re-bind peers after the
         * upgrade — the whole point of pinning is that it survives restarts.
         */
        @JvmField
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE peer_identity ADD COLUMN verified INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE peer_identity ADD COLUMN verifiedAtMs INTEGER"
                )
            }
        }

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            val existing = instance
            if (existing != null) {
                return existing
            }
            synchronized(this) {
                val again = instance
                if (again != null) {
                    return again
                }
                val created = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tharmesh.db"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                instance = created
                return created
            }
        }
    }
}
