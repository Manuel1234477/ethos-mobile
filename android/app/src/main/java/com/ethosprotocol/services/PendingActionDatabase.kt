package com.ethosprotocol.services

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// #483: version 3 adds indices on queuedAt and synced.
@Database(entities = [PendingAction::class], version = 3, exportSchema = false)
abstract class PendingActionDatabase : RoomDatabase() {
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        fun create(context: Context): PendingActionDatabase =
            Room.databaseBuilder(context, PendingActionDatabase::class.java, "pending_actions.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
