package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.ContactEntity

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(contactEntity: ContactEntity): Long

    @Query("SELECT * FROM contacts ORDER BY lastSeen DESC, addedAt DESC")
    fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE userId = :userId LIMIT 1")
    fun getByUserId(userId: String): ContactEntity?
}
