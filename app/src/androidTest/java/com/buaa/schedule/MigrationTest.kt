package com.buaa.schedule

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移测试（需要设备/模拟器）：每一步都用 `runMigrationsAndValidate` 把迁移后的
 * 表结构与**同一次 KSP 导出的** schema（3.json … 8.json）逐项比对（列 / 类型 /
 * NOT NULL / defaultValue / 索引），再断言数据没丢。
 *
 * 此前 v1/v2 的基线 JSON 的 identityHash 是手写的假值，以它们为起点的 1→2、2→3
 * 其实是在跟一份虚构 schema 对齐；本应用没有发布过 v1/v2 的包，所以那条链连同
 * 基线一起删掉，最低支持 v3（R5 F-03）。同时补上此前完全没覆盖的 6→7 与到当前
 * 版本为止的全链。
 *
 * 运行：./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate3To4CreatesCalendarSyncTable() {
        helper.createDatabase(TEST_DB_V4, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB_V4, 4, true, AppDatabase.MIGRATION_3_4)
        db.execSQL(
            "INSERT INTO calendar_sync (occurrenceId, courseStableId, occurrenceDate, calendarId, " +
                "calendarEventId, contentHash, syncedAt) " +
                "VALUES ('occ-1', 'course-1', '2026-09-07', 5, 100, 'abc', 1000)"
        )
        db.query("SELECT occurrenceId, calendarEventId FROM calendar_sync").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("occ-1", cursor.getString(0))
            assertEquals(100L, cursor.getLong(1))
        }
    }

    @Test
    fun migrate4To5AddsAliasColumn() {
        helper.createDatabase(TEST_DB_V4, 4).use { db ->
            db.execSQL(
                "INSERT INTO courses (name, dayOfWeek, periods, weeks, colorIndex, isManualOverride) " +
                    "VALUES ('高等数学', 1, '1,2', '1,2,3', 0, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB_V4, 5, true, AppDatabase.MIGRATION_4_5)
        db.query("SELECT name, alias FROM courses").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("高等数学", c.getString(0))
            // 别名默认 NULL（未设置）
            assertTrue(c.isNull(1))
        }
    }

    @Test
    fun migrate5To6CreatesWidgetSnapshotTable() {
        helper.createDatabase(TEST_DB_V6, 5).close()
        val db = helper.runMigrationsAndValidate(TEST_DB_V6, 6, true, AppDatabase.MIGRATION_5_6)
        db.execSQL("INSERT INTO widget_snapshots (`key`, dataJson, updatedAt) VALUES ('current', '{}', 1)")
        db.query("SELECT `key`, dataJson, updatedAt FROM widget_snapshots").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("current", c.getString(0))
            assertEquals("{}", c.getString(1))
            assertEquals(1L, c.getLong(2))
        }
    }

    /**
     * 6→7 补两张 courses 索引。索引名不对（Room 校验用的是 `index_<表>_<列>`）
     * runMigrationsAndValidate 就会红 —— 这条此前完全没覆盖。
     */
    @Test
    fun migrate6To7AddsCourseIndexesAndKeepsRows() {
        helper.createDatabase(TEST_DB_V7, 6).use { db ->
            db.execSQL(
                "INSERT INTO courses (name, dayOfWeek, periods, weeks, colorIndex, semesterCode, isManualOverride) " +
                    "VALUES ('线性代数', 1, '1,2', '1,2,3', 2, '2026-2027-1', 1)"
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB_V7, 7, true, AppDatabase.MIGRATION_6_7)
        assertEquals(
            "两张索引都要在",
            setOf("index_courses_semesterCode", "index_courses_sourceGroupKey"),
            indexNames(db, "courses"),
        )
        db.query("SELECT name, isManualOverride FROM courses").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("线性代数", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
    }

    /**
     * 7→8 重建 courses 以补上 `isManualOverride` 的 `DEFAULT 0`（R5 F-03）。
     * 断言点：结构校验通过 + 行数据与 id 不变 + 索引仍在 + **默认值真的生效**
     * （不写这列也能插入，取回 0）—— 最后一条才是这次迁移存在的理由。
     */
    @Test
    fun migrate7To8AddsColumnDefaultAndKeepsRows() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO courses (name, alias, dayOfWeek, periods, weeks, colorIndex, sourceGroupKey, " +
                    "semesterCode, isManualOverride) " +
                    "VALUES ('高等数学', 'GS', 1, '1,2', '1,2,3', 0, 'G1', '2026-2027-1', 1)"
            )
            db.execSQL("INSERT INTO reminders (courseId, enabled, advanceMinutes) VALUES (1, 1, 15)")
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AppDatabase.MIGRATION_7_8)

        assertEquals(
            "重建后索引必须还在",
            setOf("index_courses_semesterCode", "index_courses_sourceGroupKey"),
            indexNames(db, "courses"),
        )
        db.query("SELECT id, name, alias, periods, weeks, isManualOverride FROM courses").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("高等数学", c.getString(1))
            assertEquals("GS", c.getString(2))
            assertEquals("1,2", c.getString(3))
            assertEquals("1,2,3", c.getString(4))
            assertEquals(1, c.getInt(5))
        }
        // 提醒行按 courseId 关联，重建 courses 不能把它带走
        db.query("SELECT advanceMinutes FROM reminders WHERE courseId = 1").use { c ->
            assertTrue("重建 courses 后提醒必须还在", c.moveToFirst())
            assertEquals(15, c.getInt(0))
        }
        // v7 上这列 NOT NULL 且无默认值，插入会直接撞 NOT NULL；v8 起走默认值
        db.execSQL(
            "INSERT INTO courses (name, dayOfWeek, periods, weeks, colorIndex) " +
                "VALUES ('靠默认值', 2, '3', '4', 0)"
        )
        db.query("SELECT isManualOverride FROM courses WHERE name = '靠默认值'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // AUTOINCREMENT 的自增位点跟着表一起搬过来了，不会复用已删行的 id
        db.query("SELECT id FROM courses ORDER BY id DESC LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2L, c.getLong(0))
        }
    }

    /** 全链 3→8：一路换表/加列/补索引之后，用户既有课表与提醒都还在。 */
    @Test
    fun migrateFullChain3To8KeepsCoursesAndReminders() {
        helper.createDatabase(TEST_DB_FULL, 3).use { db ->
            db.execSQL(
                "INSERT INTO courses (name, teacher, location, campus, dayOfWeek, periods, weeks, " +
                    "colorIndex, remark, sourceGroupKey, semesterCode, isManualOverride) " +
                    "VALUES ('算法设计与分析', '王五', 'J3-301', NULL, 2, '1,2,3', '1,2,4', 3, NULL, 'G3', " +
                    "'2026-2027-1', 1)"
            )
            db.execSQL("INSERT INTO reminders (courseId, enabled, advanceMinutes) VALUES (1, 1, 30)")
        }
        val db = helper.runMigrationsAndValidate(
            TEST_DB_FULL, 8, true,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
        )
        db.query("SELECT name, periods, weeks, alias, isManualOverride FROM courses").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("算法设计与分析", c.getString(0))
            assertEquals("1,2,3", c.getString(1))
            assertEquals("1,2,4", c.getString(2))
            assertTrue(c.isNull(3))
            assertEquals(1, c.getInt(4))
        }
        db.query("SELECT advanceMinutes FROM reminders WHERE courseId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(30, c.getInt(0))
        }
        // 链上新增的表在最终版本里都可用
        db.execSQL("INSERT INTO widget_snapshots (`key`, dataJson, updatedAt) VALUES ('T', '{}', 2)")
        db.execSQL(
            "INSERT INTO calendar_sync (occurrenceId, courseStableId, occurrenceDate, calendarId, " +
                "calendarEventId, contentHash, syncedAt) VALUES ('o', 's', '2026-09-07', 1, 2, 'h', 3)"
        )
        assertEquals(
            setOf("index_courses_semesterCode", "index_courses_sourceGroupKey"),
            indexNames(db, "courses"),
        )
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA index_list($table)").use { c ->
            val nameColumn = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                val name = c.getString(nameColumn)
                // 主键自增带来的 sqlite_autoindex_* 不属于 Room 管的索引
                if (!name.startsWith("sqlite_autoindex_")) names += name
            }
        }
        return names
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
        private const val TEST_DB_V4 = "migration-test-v4.db"
        private const val TEST_DB_V6 = "migration-test-v6.db"
        private const val TEST_DB_V7 = "migration-test-v7.db"
        private const val TEST_DB_FULL = "migration-test-full.db"
    }
}
