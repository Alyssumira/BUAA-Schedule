package com.buaa.schedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CourseEntity::class,
        SemesterEntity::class,
        TimeSlotEntity::class,
        ImportHistoryEntity::class,
        ReminderEntity::class,
        CalendarSyncEntity::class,
        WidgetSnapshotEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
    abstract fun timeSlotDao(): TimeSlotDao
    abstract fun importHistoryDao(): ImportHistoryDao
    abstract fun reminderDao(): ReminderDao
    abstract fun calendarSyncDao(): CalendarSyncDao
    abstract fun widgetSnapshotDao(): WidgetSnapshotDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v3 -> v4：系统日历同步映射表 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_sync (
                        occurrenceId TEXT NOT NULL PRIMARY KEY,
                        courseStableId TEXT NOT NULL,
                        occurrenceDate TEXT NOT NULL,
                        calendarId INTEGER NOT NULL,
                        calendarEventId INTEGER NOT NULL,
                        contentHash TEXT NOT NULL,
                        syncedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4 -> v5：课程别名（仅显示层使用，不改周次/提醒计算） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN alias TEXT")
            }
        }

        /** v5 -> v6：Widget 独立快照表（减少组件重复查主库） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS widget_snapshots (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        dataJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v6 -> v7：为 courses 表补两张索引（`semesterCode` / `sourceGroupKey`）。
         *
         * 索引名必须与 Room 为 [CourseEntity] 生成的名字完全一致，否则
         * Room 的迁移校验（validateMigration）会报 "expected vs found" 而崩溃。
         * Room 的命名规则是 `index_<表名>_<列名>`。
         * `IF NOT EXISTS` 让重复执行安全（降级重装等场景）。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_courses_semesterCode` " +
                        "ON `courses` (`semesterCode`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_courses_sourceGroupKey` " +
                        "ON `courses` (`sourceGroupKey`)"
                )
            }
        }

        /**
         * v7 -> v8：给 `courses.isManualOverride` 补上 schema 里缺的 `DEFAULT 0`（R5 F-03）。
         *
         * 实体此前没声明 `defaultValue`，所以新装库的这列没有默认值，而老库的这列是
         * `ALTER TABLE … DEFAULT 0` 加上去的 —— 两条路径产出的表不是同一份 schema。
         * SQLite 不能给已存在的列增删默认值，只能按 Room 的建表语句重建：
         * 临时表 → 按实体列序 `INSERT … SELECT` → drop → rename → 重建两张索引。
         * 索引名必须与 Room 生成的 `index_courses_<列>` 一致，否则迁移校验报
         * expected vs found（见 MIGRATION_6_7 的注释）。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `courses_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `alias` TEXT,
                        `teacher` TEXT,
                        `location` TEXT,
                        `campus` TEXT,
                        `dayOfWeek` INTEGER NOT NULL,
                        `periods` TEXT NOT NULL,
                        `weeks` TEXT NOT NULL,
                        `colorIndex` INTEGER NOT NULL,
                        `customColorArgb` INTEGER,
                        `remark` TEXT,
                        `sourceGroupKey` TEXT,
                        `semesterCode` TEXT,
                        `isManualOverride` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `courses_new` (`id`, `name`, `alias`, `teacher`, `location`, `campus`, " +
                        "`dayOfWeek`, `periods`, `weeks`, `colorIndex`, `customColorArgb`, `remark`, " +
                        "`sourceGroupKey`, `semesterCode`, `isManualOverride`) " +
                        "SELECT `id`, `name`, `alias`, `teacher`, `location`, `campus`, `dayOfWeek`, " +
                        "`periods`, `weeks`, `colorIndex`, `customColorArgb`, `remark`, `sourceGroupKey`, " +
                        "`semesterCode`, `isManualOverride` FROM `courses`"
                )
                db.execSQL("DROP TABLE `courses`")
                db.execSQL("ALTER TABLE `courses_new` RENAME TO `courses`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_courses_semesterCode` ON `courses` (`semesterCode`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_courses_sourceGroupKey` " +
                        "ON `courses` (`sourceGroupKey`)"
                )
            }
        }

        // 最低支持 schema = v3：v1/v2 的基线 JSON 的 identityHash 是手写的假值（不是 KSP
        // 产物），以它们为起点的链没人验证过，本应用也没发布过 v1/v2 的包，因此对应的
        // 迁移已删除。这种库打开时 Room 会直接抛"no migration defined"，
        // 而不是悄悄走一条虚构的链（R5 F-03）。
        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        )

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "buaa_schedule.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }

        /** 供迁移测试用的内存构建入口 */
        fun buildForTest(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addMigrations(*ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
    }
}
