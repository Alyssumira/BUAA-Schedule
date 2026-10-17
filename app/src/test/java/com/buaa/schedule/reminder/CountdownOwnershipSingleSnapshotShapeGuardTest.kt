package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 卡 #748 的形状尺：倒计时归属在 main 侧**只剩一枚可赋值快照**。
 *
 * 旧形状是 `@Volatile internal var countdownCourseId` ＋ `@Volatile internal var countdownClassStart`
 * 两枚字段分写分读 —— `@Volatile` 只给可见性、不给 read-modify-write 的原子性，
 * `recordCountdownOwnership` 写到一半就能被另一线程读成「新 id ＋旧 classStart」的交叉结构。
 * 本卡（第⓪问裁的甲档）把它折成一枚不可变 [ReminderNotifications.CountdownOwnership] 快照，
 * 一次赋值同点带上两格。这把尺钉的就是「折回去不许再来」：
 *  - 可赋值的归属快照声明恰好一枚（`SNAPSHOT_DECL`）；
 *  - 改写归属的赋值语句恰好两枚（`ASSIGN_NEEDLE` = 记录整枚那一句 + 归零那一句，旧的三枚
 *    分写点折成这两句；长出第三枚 = 又开一条不经快照的写路）；
 *  - 那两枚分写字段的名字在 main 里彻底绝迹（各 0 处）；
 *  - 快照是 `data class` 且两格 `val`（不可变，交叉只能由一整份显式赋值产生）。
 *
 * 针全是代码专属串（注释里也不含 `countdownOwnership = ` / `internal var countdownOwnership`
 * / `countdownCourseId`），故不做注释抹除 —— 遵守落点纪律第 3 条：不自造 char-array 版抹除器。
 */
class CountdownOwnershipSingleSnapshotShapeGuardTest {

    private val code: String by lazy { source(NOTIFICATIONS) }

    @Test
    fun `归属只剩一枚可赋值快照`() {
        assertEquals(
            "可赋值的归属快照声明恰一处（`$SNAPSHOT_DECL`；实到 " + occurrences(code, SNAPSHOT_DECL) + " 处）",
            1,
            occurrences(code, SNAPSHOT_DECL),
        )
        assertEquals(
            "改写归属快照的赋值语句恰两处（`$ASSIGN_NEEDLE` = 记录整枚 [recordCountdownOwnership] 一句 " +
                "+ 归零 [clearCountdownOwnership] 一句；旧的三枚分写点折成这两句；实到 " +
                occurrences(code, ASSIGN_NEEDLE) + " 处）：长出第三枚 = 又开了一条不经快照的写路",
            2,
            occurrences(code, ASSIGN_NEEDLE),
        )
        assertEquals(
            "分写字段 `countdownCourseId` 在 main 侧必须彻底绝迹（实到 " +
                occurrences(code, "countdownCourseId") + " 处）：回来一丁点 = 交叉结构重新可达",
            0,
            occurrences(code, "countdownCourseId"),
        )
        assertEquals(
            "分写字段 `countdownClassStart` 同样必须 0 处（实到 " +
                occurrences(code, "countdownClassStart") + " 处）",
            0,
            occurrences(code, "countdownClassStart"),
        )
        assertEquals(
            "快照必须是不可变 `data class`、两格全 `val`（`$SNAPSHOT_DEF`；实到 " +
                occurrences(code, SNAPSHOT_DEF) + " 处）：`val` 改回 `var` 就等于把交叉又请回来一半",
            1,
            occurrences(code, SNAPSHOT_DEF),
        )
    }

    /**
     * 探测器自检（朝宽那一臂）：把分写旧形状喂进同一把尺，`countdownCourseId` 必须数得到、
     * 快照赋值针必须数不到 —— 否则上面 main 侧那句「0 处」是空锚点。
     */
    @Test
    fun `探测器认得分写旧形状`() {
        val splitShape = """
            @Volatile
            internal var countdownCourseId = 0L
            @Volatile
            internal var countdownClassStart = 0L
            internal fun record() {
                countdownCourseId = window.courseId
                countdownClassStart = countdownAnchorOf(window)
            }
        """.trimIndent()
        assertEquals(
            "分写旧形状里 `countdownCourseId` 必须数得到（实到 " +
                occurrences(splitShape, "countdownCourseId") + " 处）—— main 侧那句「0 处」的对照物",
            2,
            occurrences(splitShape, "countdownCourseId"),
        )
        assertEquals(
            "分写旧形状里快照赋值针 `$ASSIGN_NEEDLE` 0 处（它写的是裸字段不是快照；实到 " +
                occurrences(splitShape, ASSIGN_NEEDLE) + " 处）",
            0,
            occurrences(splitShape, ASSIGN_NEEDLE),
        )
    }

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        check(file.isFile) { "找不到 ${file.path}：文件挪过家就得跟着改路径" }
        return file.readText().replace("\r\n", "\n")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 单测工作目录是模块目录还是仓库根不由这里决定：几种布局都试，全落空就抛。 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            val candidate = listOf("src/main/java", "app/src/main/java")
                .map { File(current, it) }
                .firstOrNull { it.isDirectory }
            if (candidate != null) return candidate
            dir = current.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val NOTIFICATIONS = "com/buaa/schedule/reminder/ReminderNotifications.kt"
        const val SNAPSHOT_DECL = "internal var countdownOwnership: CountdownOwnership? = null"
        const val ASSIGN_NEEDLE = "countdownOwnership = "
        const val SNAPSHOT_DEF =
            "internal data class CountdownOwnership(val courseId: Long, val classStartMillis: Long)"
    }
}
