package com.dsatm.guardianai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RedactionDao {

    /**
     * Inserts a new mapping record. If a record with the same hash exists, it is replaced (UPDATE).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(entity: RedactedFileEntity)

    /**
     * Looks up the encrypted file name using the hash of the publicly exposed redacted file.
     * This is the core lookup operation for file detection.
     */
    @Query("SELECT * FROM redacted_mapping WHERE redactedFileHash = :hash LIMIT 1")
    suspend fun getMappingByHash(hash: String): RedactedFileEntity?

    /**
     * Gets all entities. Used for listing secured files in the RedactedFilesScreen.
     */
    @Query("SELECT * FROM redacted_mapping ORDER BY timestamp DESC")
    suspend fun getAllMappings(): List<RedactedFileEntity>
}