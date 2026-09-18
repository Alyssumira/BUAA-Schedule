package com.buaa.schedule.data.local

import androidx.room.migration.Migration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * v8 → v9（courses 加 `credit`）迁移的纸面校验。
 *
 * 真正跑 `runMigrationsAndValidate` 需要设备/模拟器（`MigrationTest` 在 androidTest，
 * 本机 `--offline` 跑不了），而这次迁移的全部风险都落在两处纯数据上，不需要 SQLite
 * 也能钉死：
 * - **DDL 串的形状**：写成 `NOT NULL` 或带 `DEFAULT 0` 会让 Room 的迁移校验
 *   expected vs found 当场红；更糟的是把升级前所有课的学分伪装成"0 学分"
 *   （统计页从此算不对，而且从界面上看不出来）。多带一条语句则 `execSQL` 直接抛。
 * - **版本记账**：`@Database` 涨了版本却没注册对应迁移，用户手机上就是
 *   "no migration defined"，课表根本打不开。
 *
 * 记账这一项靠 KSP 导出的 `app/schemas/<数据库类全名>/<版本>.json` 反查（导出文件名就是
 * 声明的数据库版本），比反射注解可靠 —— `@Database` 是 CLASS 保留，运行时读不到。
 */
class MigrationChainTest {

    @Test
    fun creditDdlIsASingleNullableRealColumn() {
        val sql = AppDatabase.MIGRATION_8_9_SQL

        assertTrue("只允许一条语句（execSQL 遇到分号会抛）", !sql.contains(';'))
        assertTrue(sql, sql.startsWith("ALTER TABLE `courses` ADD COLUMN"))
        assertTrue("列名必须与实体属性推导出的 `credit` 一致", sql.contains("ADD COLUMN `credit`"))
        assertTrue("Double 在 Room 里的 affinity 是 REAL", sql.endsWith("REAL"))
        // 下面三条是"数据能不能保住"的判据：不重建表、不把老行写成 0 学分
        assertFalse("重建表才是真正会丢数据的动线", sql.contains("DROP TABLE"))
        assertFalse(sql, sql.contains("CREATE TABLE"))
        assertFalse("老课的学分是「不知道」，不是 0 学分", sql.contains("DEFAULT"))
        assertFalse("不能要求非空：升级前的存量行没有值可填", sql.contains("NOT NULL"))
    }

    /** 迁移不许顺手改动已有列（改了就不是 ADD COLUMN 能表达的了） */
    @Test
    fun creditMigrationDoesNotTouchExistingColumns() {
        val sql = AppDatabase.MIGRATION_8_9_SQL

        listOf("id", "name", "alias", "periods", "weeks", "isManualOverride").forEach { column ->
            assertFalse("$column 出现在 DDL 里：这条迁移在动已存在的列", sql.contains("`$column`"))
        }
    }

    /** 反射读实体字段的**声明类型**：Room 把列映射成什么，取决于这里写的 Kotlin 类型 */
    @Test
    fun entityCreditColumnIsANullableNumberMatchingTheDdl() {
        val field = CourseEntity::class.java.getDeclaredField("credit")

        assertTrue(
            "必须是装箱类型：写成非空 Double 会映射成 primitive double（NOT NULL），" +
                "与 ADD COLUMN 补出来的可空列对不上，迁移校验红在用户手机上",
            field.type === Double::class.javaObjectType,
        )
        assertEquals("REAL", AppDatabase.MIGRATION_8_9_SQL.substringAfterLast(' '))
    }

    @Test
    fun migrationSpansEightToNine() {
        val migration: Migration = AppDatabase.MIGRATION_8_9

        assertEquals(8, migration.startVersion)
        assertEquals(9, migration.endVersion)
    }

    /** 链必须从最低支持版本一路连到当前版本，中间不断档、不重复 */
    @Test
    fun migrationChainIsContiguousUpToCurrentVersion() {
        val migrations = AppDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }

        assertEquals("最低支持 schema 仍是 v3（v1/v2 的基线 JSON 是假的）", 3, migrations.first().startVersion)
        assertEquals(CURRENT_SCHEMA_VERSION, migrations.last().endVersion)
        for (index in 1 until migrations.size) {
            assertEquals(
                "第 $index 步与上一步不衔接：这种库打开时抛 no migration defined",
                migrations[index - 1].endVersion,
                migrations[index].startVersion,
            )
        }
        assertEquals(
            "每一步只能有一个迁移对象（重复注册 Room 会报 duplicate migrations）",
            migrations.size,
            migrations.map { it.startVersion to it.endVersion }.distinct().size,
        )
    }

    /**
     * 迁移全部注册进了 `addMigrations`：只写对象不注册，运行时等于没有迁移。
     * （`ALL_MIGRATIONS` 是 internal，正是为了让这条断言写得出来。）
     */
    @Test
    fun everyDeclaredMigrationIsRegisteredInOrder() {
        val versions = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals(
            listOf(3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9),
            versions,
        )
    }

    /**
     * 用 KSP 导出的 schema JSON 反向核对版本记账与列定义。
     *
     * `schemas/<数据库类全名>/<N>.json` 的文件名就是 `@Database` 声明的版本号，因此：
     * - 9.json 存在 ⇒ 数据库确实升到了 9；
     * - 没有比链尾更高的 json ⇒ 不存在"涨了版本没写迁移"；
     * - 9.json 的 courses 与 8.json 只差一个可空 REAL 的 `credit` ⇒
     *   迁移的 DDL 与新库定义一致（connected 的 MigrationTest 比的就是这份文件）。
     *
     * 找不到 schemas 目录时整条跳过（工作目录布局不由本测试决定），不制造假红。
     */
    @Test
    fun exportedSchemaMatchesTheMigrationColumn() {
        val found = findSchemaDir()
        assumeTrue("本机工作目录里找不到 KSP 导出的 schemas/，跳过 JSON 反向核对", found != null)
        val schemaDir = requireNotNull(found)
        val exportedVersions = schemaDir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> file.nameWithoutExtension.toIntOrNull() }
        val current = File(schemaDir, "$CURRENT_SCHEMA_VERSION.json")
        assertTrue("KSP 没有导出 ${CURRENT_SCHEMA_VERSION}.json：@Database 的 version 与链尾不一致", current.isFile)
        assertEquals(
            "导出了比迁移链更高的 schema：有人涨了版本却没写迁移（用户手机上是打不开库）",
            CURRENT_SCHEMA_VERSION,
            exportedVersions.maxOrNull(),
        )

        val courses = coursesFieldsOf(current, CURRENT_SCHEMA_VERSION)
        val previous = coursesFieldsOf(
            File(schemaDir, "${CURRENT_SCHEMA_VERSION - 1}.json"),
            CURRENT_SCHEMA_VERSION - 1,
        )
        val credit = courses["credit"]
        assertNotNull("9.json 里没有 credit 列", credit)
        assertEquals("REAL", credit?.get("affinity")?.jsonPrimitive?.content)
        assertFalse("credit 不能是 NOT NULL", credit?.containsKey("notNull") == true)
        assertFalse("credit 不能带 defaultValue", credit?.containsKey("defaultValue") == true)
        assertEquals(
            "除 credit 之外的列必须与 v8 完全一致，否则 ADD COLUMN 补不出新库的形状",
            previous.keys.sorted(),
            (courses.keys - "credit").sorted(),
        )
        previous.forEach { (name, definition) ->
            assertEquals("列 $name 的定义被改动", definition, courses[name])
        }
    }

    private fun coursesFieldsOf(file: File, expectedVersion: Int): Map<String, JsonObject> {
        assertTrue("缺少导出的 schema 文件：$file", file.isFile)
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        // 文件名与内容里的版本必须是一回事：只有一边被改过就是手改产物或漏导出
        assertEquals(expectedVersion, database["version"]?.jsonPrimitive?.int ?: -1)
        return database["entities"]!!.jsonArray
            .first { it.jsonObject["tableName"]?.jsonPrimitive?.content == "courses" }
            .jsonObject["fields"]!!.jsonArray
            .associateBy(
                { it.jsonObject["columnName"]!!.jsonPrimitive.content },
                { it.jsonObject },
            )
    }

    /**
     * 单测的工作目录是模块目录还是仓库根，不由这里决定：两种布局都试一遍。
     *
     * KSP 导出的目录名是**数据库类的完全限定名**（`schemas/com.buaa.schedule.data.local.AppDatabase/9.json`），
     * 不是包名 —— 按包名找会一直找不到，而这条核对是 `assumeTrue` 跳过的，
     * 找错路径只表现为"永远是绿的"，比红更糟。
     */
    private fun findSchemaDir(): File? {
        val schemaFolder = AppDatabase::class.qualifiedName.orEmpty()
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val relative = listOf("schemas/$schemaFolder", "app/schemas/$schemaFolder")
            val hit = relative.map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        /** 与 `@Database(version = ...)` 同步：链尾、注册表、导出文件名三处必须相等 */
        const val CURRENT_SCHEMA_VERSION = 9
    }
}
