package com.dsatm.guardianai.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RedactedFileEntity::class],
    version = 1,
    exportSchema = false // We don't export schema files for this version
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun redactionDao(): RedactionDao
}