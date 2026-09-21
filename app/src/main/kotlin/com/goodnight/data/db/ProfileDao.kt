package com.goodnight.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
    @Query("SELECT * FROM profile ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM profile WHERE id = :id") suspend fun byId(id: Long): ProfileEntity?
    @Query("SELECT * FROM profile WHERE name = :name LIMIT 1") suspend fun byName(name: String): ProfileEntity?
    @Query("SELECT mode FROM profile WHERE id = :id") suspend fun modeById(id: Long): Int?
    @Query("SELECT COUNT(*) FROM profile") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<ProfileEntity>)
    @Query("SELECT * FROM profile ORDER BY createdAt, id") suspend fun getAll(): List<ProfileEntity>
}
