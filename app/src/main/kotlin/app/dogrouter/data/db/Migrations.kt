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

/**
 * Add the per-dog `allowLongerWalk` flag (default true). The planner
 * uses it to decide whether walks may exceed the minimum duration for
 * a given dog (puppies and injured dogs need exact times).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dogs` ADD COLUMN `allowLongerWalk` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Add the per-rule `isAlternative` flag (default false). Rules of one dog
 * marked alternative are mutually exclusive — the planner walks exactly one
 * of them per day ("end of morning OR end of afternoon").
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dog_schedule_rules` ADD COLUMN `isAlternative` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Add the nullable `latestStart` column: the latest time a walk may start,
 * independent of the return deadline (e.g. "start between 11:00 and 13:00,
 * no fixed end").
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dog_schedule_rules` ADD COLUMN `latestStart` TEXT")
    }
}

/**
 * Add the per-dog `active` flag (default true). An inactive dog is
 * temporarily paused (owner on holiday, etc.) — kept in the database but
 * skipped by the planner.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dogs` ADD COLUMN `active` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Add the `appointments` table: one-off, dog-free commitments on a date in an
 * exact time window at a fixed address, which the planner schedules around.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `appointments` (" +
                "`id` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                "`startTime` TEXT NOT NULL, `endTime` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, `address` TEXT NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * Add the `saved_plans` table: one pinned (possibly hand-edited) day plan per
 * date, stored as JSON, shown instead of re-solving and feeding the future
 * billing journal.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_plans` (" +
                "`date` TEXT NOT NULL, `planJson` TEXT NOT NULL, " +
                "`edited` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`))",
        )
    }
}

/**
 * Add the `owners` table (the billing/contact party) and a nullable `ownerId`
 * on dogs. Seed one owner per distinct existing `ownerName` (whole name kept in
 * `lastName`, phone taken from any of that owner's dogs) and link the dogs to
 * it. The legacy `ownerName`/`ownerPhone` columns are left in place; `ownerId`
 * is the new source of truth.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `owners` (" +
                "`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, `lastName` TEXT NOT NULL, " +
                "`billingAddress` TEXT NOT NULL, `phone` TEXT, `email` TEXT, " +
                "`isEmployer` INTEGER NOT NULL, `isTest` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("ALTER TABLE `dogs` ADD COLUMN `ownerId` TEXT")
        val now = System.currentTimeMillis()
        // One owner per distinct non-blank owner name; random hex id.
        db.execSQL(
            "INSERT INTO `owners` (id, firstName, lastName, billingAddress, phone, email, isEmployer, isTest, createdAt) " +
                "SELECT lower(hex(randomblob(16))), '', ownerName, '', MIN(ownerPhone), NULL, 0, 0, $now " +
                "FROM `dogs` WHERE TRIM(ownerName) <> '' GROUP BY ownerName",
        )
        db.execSQL(
            "UPDATE `dogs` SET ownerId = " +
                "(SELECT o.id FROM `owners` o WHERE o.lastName = dogs.ownerName) " +
                "WHERE TRIM(ownerName) <> ''",
        )
    }
}

/**
 * Billing fase 2: a per-rule price (`priceCents`, null = default tariff), and
 * the `billable_services` + `committed_days` tables that a committed day plan
 * fills.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dog_schedule_rules` ADD COLUMN `priceCents` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `billable_services` (" +
                "`id` TEXT NOT NULL, `ownerId` TEXT, `date` TEXT NOT NULL, `dogId` TEXT, " +
                "`description` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, " +
                "`durationMinutes` INTEGER NOT NULL, `paid` INTEGER NOT NULL, " +
                "`paidDate` TEXT, `invoiceNumber` TEXT, `isManual` INTEGER NOT NULL, " +
                "`committedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `committed_days` (" +
                "`date` TEXT NOT NULL, `committedAt` INTEGER NOT NULL, " +
                "`serviceCount` INTEGER NOT NULL, `totalCents` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`))",
        )
    }
}

/** Snapshot the committed day plan on `committed_days` so it can be shown later. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `committed_days` ADD COLUMN `planJson` TEXT NOT NULL DEFAULT ''")
    }
}

/** Billing fase 4: the `invoices` table (issued factures / credit notes). */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `invoices` (" +
                "`number` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `isTest` INTEGER NOT NULL, `acquitted` INTEGER NOT NULL, " +
                "`acquittedDate` TEXT, `totalCents` INTEGER NOT NULL, `pdfPath` TEXT, " +
                "PRIMARY KEY(`number`))",
        )
    }
}

/** Freeze a render snapshot on each invoice so reprints are identical. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `invoices` ADD COLUMN `renderJson` TEXT NOT NULL DEFAULT ''")
    }
}

val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        MIGRATION_13_14,
    )
