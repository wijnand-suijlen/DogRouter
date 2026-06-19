package app.dogrouter.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Replace per-day `dog_schedule_entries` with multi-day `dog_schedule_rules`
 * plus a walk duration. Each existing entry becomes a single-day rule with
 * a 60-minute default duration; the walker can consolidate to multi-day
 * rules manually afterwards.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dog_schedule_rules` (
                `id` TEXT NOT NULL,
                `dogId` TEXT NOT NULL,
                `weekdaysMask` INTEGER NOT NULL,
                `earliestStart` TEXT,
                `latestEnd` TEXT,
                `durationMinutes` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`dogId`) REFERENCES `dogs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dog_schedule_rules_dogId` ON `dog_schedule_rules` (`dogId`)")

        db.execSQL(
            """
            INSERT INTO `dog_schedule_rules` (id, dogId, weekdaysMask, earliestStart, latestEnd, durationMinutes)
            SELECT id, dogId, (1 << (weekday - 1)), earliestPickup, latestDropoff, 60
            FROM `dog_schedule_entries`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `dog_schedule_entries`")
    }
}

/**
 * Add nullable `latitude` and `longitude` to dogs so addresses chosen via
 * the BAN autocomplete carry their coordinates.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dogs` ADD COLUMN `latitude` REAL")
        db.execSQL("ALTER TABLE `dogs` ADD COLUMN `longitude` REAL")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
