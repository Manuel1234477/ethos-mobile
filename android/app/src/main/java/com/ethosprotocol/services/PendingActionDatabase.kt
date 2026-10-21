package com.ethosprotocol.services

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// #483: version 3 adds indices on queuedAt and synced.
@Database(entities = [PendingAction::class], version = 3, exportSchema = false)
abstract class PendingActionDatabase : RoomDatabase() {
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        /**
         * Explicit migration from v1 to v2.
         *
         * v2 adds the `idempotencyKey` and `synced` columns and a unique index on `dedupeKey`.
         * These columns must match the [PendingAction] entity definition exactly.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pending_actions` ADD COLUMN `idempotencyKey` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `pending_actions` ADD COLUMN `synced` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_actions_dedupeKey` " +
                        "ON `pending_actions` (`dedupeKey`)"
                )
            }
        }

        /**
         * Explicit migration from v2 to v3.
         *
         * v3 adds indices on `queuedAt` and `synced` so the sync worker's queries stay fast as
         * the queue grows (#483). These must match the [PendingAction] entity definition.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_actions_queuedAt` " +
                        "ON `pending_actions` (`queuedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_actions_synced` " +
                        "ON `pending_actions` (`synced`)"
                )
            }
        }

        /**
         * All explicit migrations, in order. When the database version is bumped, add a new
         * [Migration] here (e.g. `MIGRATION_2_3`) and register it in [create]. Never rely on
         * destructive fallback for release builds: queued actions are unsynced user intentions.
         */
        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3
        )

        fun create(context: Context): PendingActionDatabase =
            Room.databaseBuilder(context, PendingActionDatabase::class.java, "pending_actions.db")
                .addMigrations(*ALL_MIGRATIONS)
                .apply {
                    // Only allow destructive fallback in debug builds to surface missing
                    // migrations during development without risking data loss in release.
                    if (BuildConfig.DEBUG) {
                        fallbackToDestructiveMigration()
                    }
                }
                .build()
    }
}