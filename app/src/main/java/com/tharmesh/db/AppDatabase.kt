package com.tharmesh.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tharmesh.db.dao.BundleDao
import com.tharmesh.db.dao.ConversationDao
import com.tharmesh.db.dao.MessageDao
import com.tharmesh.db.dao.ContactDao
import com.tharmesh.db.entity.BundleEntity
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.db.entity.ContactEntity

@Database(
    entities = [MessageEntity::class, BundleEntity::class, ConversationEntity::class, ContactEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun bundleDao(): BundleDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

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
                ).fallbackToDestructiveMigration().build()
                instance = created
                return created
            }
        }
    }
}
