package com.buaa.schedule.data.local

import android.content.Context
import com.buaa.schedule.data.import.TeachingScheduleParser
import com.buaa.schedule.domain.model.SpecialDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * 「学习日程」标注的本地缓存：**按月缓存接口原始响应**，读取时再解析。
 *
 * 缓存原始响应而不是解析结果，好处是解析器后续修正时旧缓存能直接受益，
 * 不需要做缓存版本迁移。文件不进 Room，因此**不涉及 schema 迁移**。
 *
 * ## 写侧的形状（T369；在册 #336 ＋ 任务表 T248-C1 那一格，同一枚文件并卡）
 * 改前那一手（取证原文，守卫按它在注释侧恰 1 处、在代码侧 0 处钉住）：
 * `file(context, month).writeText(rawResponse, Charsets.UTF_8)`。
 * 它是「截断直写 ＋ 裸吞」挤在同一手里：`File.writeText` 走的是开流即 truncate 那一手
 * （`writeBytes` → `FileOutputStream(this)`，POSIX 打开就清零），于是**磁盘满／配额用尽**这类确定性
 * 失败会写出前若干字节再抛，而那抛被 `runCatching { … }; Unit` 就地丢掉 —— 盘上留下的正是
 * 「半截正文 ＋ 新 mtime」这一枚组合。返回类型是 `Unit` ⇒ 调用点连"这次到底落没落"都拿不到，
 * 那句 `假期标注已更新`（SpecialDayRefreshPolicy.kt 裸 :230）说的从来只是"接口把正文带回来了"。
 *
 * 两档合起来的后果不是"可能写坏"这么轻：新 mtime 让这一月在名册里就是一枚 1 天大的新鲜缓存，
 * 而名册那枚载体（`SpecialDayCacheAge`）只有「月份 ＋ 月龄」两格，**没有"内容解不解得开"那一档**
 * ⇒ 这一月在 `ageMillis <= 604_800_000`（恰 7 天）之内**一次都不会被排进补抓**，
 * 期间三个触发点念的都是同一句「缓存都在有效期内（7 天内）」。
 *
 * 手法抄本仓已有的口径、不自造一套：同目录先写 `<yyyy-MM>.json.part` 临时件，写完整了再 `renameTo`
 * 顶上正式件（`UpdateCheck.kt` 裸 :431 ＋ 裸 :482 那一族；`.part` 这个尾巴不是本卡新造的词，
 * `UpdateInfo.kt` 裸 :151 早就按它扫尾）。**刻意不抄**它"改名不成就用 copy 顶上"那一手兜底：
 * 兜底只在正式件当时并不存在时走得到（同函数裸 :427 的 `if (target.isApkOnDisk()) return target`
 * 先 return 了），而缓存这一枚的正式件可以带着**上一份好内容**，往它上面 copy 就是把本卡要修的那手
 * 截断直写换个地方复现。⇒ 改名没成就按失败收场：临时件删掉、正式件原样留着 ——
 * 要么还是上一份好内容（mtime 不动 ⇒ 照常按月龄判过期），要么压根不存在（不进名册 ⇒ 当场就是待补抓）。
 * 这一档与改前的关键差别就在这里：改前一次失败要等 7 天才自愈，改后下一次触发就自愈。
 *
 * ⚠️ 覆盖面如实写，别当成全堵住了：`renameTo` 是原子的，所以**进程在写中间被杀**那一档堵住了
 * （正式名上永远只出现"完整内容之后那一次改名"）；**断电／没 fsync** 那一档没堵 —— 这里不做
 * `FileDescriptor.sync()`，一枚可重抓的缓存不值得换一次阻塞 IO，真要收那一档得连 `UpdateCheck`
 * 同族一起改（同一把尺不许只量一半）。`clear` 丢掉 `delete()` 返回值那一格也不在本卡射程
 * （它在 main 与测试树里都零调用点，见 T369⓪ 的残账点名）。
 */
object SpecialDayCache {

    /**
     * 月龄换算基数。**"多大算该重抓"不在这里判** —— 那条判据（连同 7 天这个口径）
     * 收在纯 JVM 的 `SpecialDayRefreshPolicy.SpecialDayCacheStaleAfterDays` 里，
     * 本文件只报「哪个月有文件、它有多少天大」这一份设备侧事实（T60）。
     */
    private const val MILLIS_PER_DAY = 24 * 60 * 60_000L

    /**
     * 临时件尾巴，抄 `UpdateCheck.kt` 那一族（裸 :431）。
     *
     * ⚠️ 必须**不以 `.json` 结尾**：名册与 `loadAll` 那两把尺现在都按同一串过滤
     * （`f.isFile && f.name.endsWith(".json")`）收文件，半成品一旦被任何一侧看见，
     * 就等于把"半截"从写侧搬进名册 —— 那正是本卡要修的病灶换了个位置。
     */
    private const val TEMP_SUFFIX = ".part"

    /** 缓存文件名：`yyyy-MM.json`。用 padStart 而不是 String.format("%02d")，
     *  后者受默认 Locale 影响（阿拉伯-埃及等 Locale 会写出非 ASCII 数字，
     *  于是这个月永远命中不到自己写的缓存文件，每次都重新联网）。 */
    private fun fileNameOf(month: YearMonth): String =
        "${month.year}-${month.monthValue.toString().padStart(2, '0')}.json"

    /**
     * 缓存目录。改前这里是 `File(...).apply { mkdirs() }` —— **丢掉了 `mkdirs()` 的返回值**，
     * 于是「目录压根立不起来」与「目录是空的」在证据上同形，读取路径还各自顺手建一次目录。
     * 现在只有写侧去建（[dirReady]），且它把建不起来那一档单独交回调用点。
     */
    private fun dir(context: Context): File = File(context.filesDir, "special_days")

    /** 目录在不在这儿、或这一趟建不建得起来。`mkdirs()` 返回假但目录已存在是正常态，所以两把一起判。 */
    private fun dirReady(context: Context): Boolean {
        val candidate = dir(context)
        return candidate.isDirectory || candidate.mkdirs()
    }

    private fun file(context: Context, month: YearMonth): File =
        File(dir(context), fileNameOf(month))

    /** 临时件与正式件**同目录**：跨目录改名在 Android 上是另一回事（cacheDir 与 filesDir 可能是
     *  两个挂载点，`renameTo` 会直接返回假），`UpdateCheck.kt` 那一族也是同目录。 */
    private fun tempFile(context: Context, month: YearMonth): File =
        File(dir(context), fileNameOf(month) + TEMP_SUFFIX)

    /**
     * 覆盖写入某个月的原始响应：**先把正文完整写进同目录的临时件，再把临时件改名顶上正式件**，
     * 并把结论交回调用点（改前返回 `Unit`，那就是 #336 那格的正面 —— 落没落盘没人知道）。
     *
     * 三档结论各有各的自愈路径，所以不许折成一枚 Boolean：
     * - [SpecialDayWriteOutcome.Written]：正式件上这一次是完整内容。
     * - [SpecialDayWriteOutcome.WriteFailed]：写临时件或改名这两步之一被拒（磁盘满、配额、IO 错误、
     *   ROM 拒改名）。**正式件没被碰过** ⇒ 要么留着上一份好内容（月龄照旧，第 8 天照常补抓），
     *   要么压根不存在（不进名册 ⇒ 下一轮当场就是 pending）。
     * - [SpecialDayWriteOutcome.CacheDirUnreachable]：`files/special_days` 这一趟立不起来，
     *   压根没地方写。它与"写失败"分档是因为给用户的下一手不一样（前者还能腾空间重试，后者是 ROM/权限）。
     *
     * `cause` 只带异常类名，不带异常消息（消息是 OS 原文、可能夹路径；本仓对"异常消息连响应体片段
     * 一起进 logcat"这一族是有在册顾虑的，别在这一枚取证行上新开第二个口）。
     */
    suspend fun put(
        context: Context,
        month: YearMonth,
        rawResponse: String,
    ): SpecialDayWriteOutcome = withContext(Dispatchers.IO) {
        if (!dirReady(context)) {
            return@withContext SpecialDayWriteOutcome.CacheDirUnreachable
        }
        val temp = tempFile(context, month)
        val stageFailure = runCatching { temp.writeText(rawResponse, Charsets.UTF_8) }.exceptionOrNull()
        if (stageFailure != null) {
            temp.delete()
            return@withContext SpecialDayWriteOutcome.WriteFailed(
                stage = SpecialDayWriteStage.TEMP_WRITE,
                cause = stageFailure::class.java.simpleName,
            )
        }
        val promotion = runCatching { temp.renameTo(file(context, month)) }
        // 失败路径上别把半成品留在目录里；成功那一档 renameTo 已经把临时件搬走，这一手只会扑空。
        temp.delete()
        val failure = promotion.exceptionOrNull()
        when {
            promotion.getOrDefault(false) -> SpecialDayWriteOutcome.Written
            failure != null -> SpecialDayWriteOutcome.WriteFailed(
                SpecialDayWriteStage.PROMOTE_RENAME,
                failure::class.java.simpleName,
            )
            else -> SpecialDayWriteOutcome.WriteFailed(SpecialDayWriteStage.PROMOTE_RENAME, null)
        }
    }

    /** 读取全部已缓存月份的标注（按日期去重、排序） */
    suspend fun loadAll(context: Context): List<SpecialDay> = withContext(Dispatchers.IO) {
        // 过滤串与 cachedMonthAges 那一把**并成同一串**（改前这里是 `f.name.endsWith(".json")`，
        // 少了 `f.isFile` —— 同一目录两把尺，改前那两份对"一枚名叫 2026-10.json 的目录"给出不同结论）。
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return@withContext emptyList()
        val byDate = LinkedHashMap<LocalDate, SpecialDay>()
        files.sortedBy { it.name }.forEach { f ->
            runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let { raw ->
                TeachingScheduleParser.parse(raw).forEach { day -> byDate[day.date] = day }
            }
        }
        byDate.values.sortedBy { it.date }
    }

    /**
     * 已缓存月份 → **月龄**（天，向上取整：不足一天算一天）。没列出的月份就是没有缓存文件。
     *
     * T60 之前这里直接判「哪些月份该重抓」（`staleMonths`），于是 7 天那条判据也落在这份
     * 读文件系统的代码里，JVM 单测完全碰不到它 —— "缓存新鲜 / 过期 / 半新半旧"这几档
     * 从来没有被打过表。现在这里只报事实，判过期在 `SpecialDayRefreshPolicy`。
     * 口径与旧写法等价：满 7 天向上取整成 7，判据那一头 `<= 7` 仍算新鲜，第 8 天才过期。
     *
     * ⚠️ 月龄下限夹 0：mtime 落在未来（设备时钟回摆过、或手工调过时间）时，
     * 负数在判据那一头与"刚抓的"同形，会让这一整月永远不再补抓。
     */
    suspend fun cachedMonthAges(context: Context): Map<YearMonth, Long> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return@withContext emptyMap()
        files.mapNotNull { f ->
            // 这一档 `runCatching` 解析的是**文件名**，不是正文：只有文件名本身不是 `yyyy-MM`
            // （外部塞进来的怪名字）才会被跳过、那一月才按"没有缓存"处理。
            // ⚠️ 改前这句注释写的是「解析不出来的（半截写入…）跳过，让那个月按"没有缓存"处理 ——
            // 方向与 loadAll 读到坏文件时一致：补抓一次就好」，**那是假注释**（T369⓪ 取证 (2) 与 (3)）：
            // 一枚半截的 `2026-10.json` 文件名完好 ⇒ 照样带着**新 mtime** 进名册 ⇒ 被判成新鲜月，
            // 7 天整之内不再补抓；而 loadAll 那一头对"内容坏"其实有两档（正文语法不合法 ⇒ 跳过；
            // 正文语法合法而形状怪 ⇒ `parse` 里没被 `runCatching` 罩住的 `walk` 会抛，整条 loadAll
            // 抛给调用点），所以"与 loadAll 一致、补抓一次就好"两头都不成立。
            // 本文件不修那两档（修法归 T369⓪ 否掉的乙档，以及读侧那一族），只把话说对。
            val month = runCatching { YearMonth.parse(f.name.removeSuffix(".json")) }.getOrNull()
                ?: return@mapNotNull null
            val ageMillis = (now - f.lastModified()).coerceAtLeast(0L)
            month to (ageMillis + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY
        }.toMap()
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        Unit
    }
}

/**
 * `put` 交回的落盘结论（改前那手 `runCatching { … }; Unit` 丢掉的就是它）。
 *
 * 这是**设备侧事实的载体**，不是判据：三档各自"该念哪一句"全在纯 JVM 的
 * `SpecialDayRefreshPolicy.SpecialDayWriteState` 里，调用点只做一次折算（与
 * `YearMonth ↔ SpecialDayMonth` 同一道在册折算缝），不许就地拼日志字符串。
 *
 * ⚠️ 这一族形状（临时件命名、`renameTo`、`delete()` 的返回值）天生不是纯函数，
 * 所以它由源码形状守卫 `SpecialDayCacheAtomicWriteGuardTest` 钉，不伪装成"纯判据"去要表驱动。
 */
sealed interface SpecialDayWriteOutcome {

    /** 临时件写完整 ＋ 改名顶上正式件都成了：正式件上的这一次是完整内容 */
    data object Written : SpecialDayWriteOutcome

    /**
     * 没落上。正式件**没被碰过**（改前的直写会把正式件先截断再写，那才是"半截＋新 mtime"的来源）。
     *
     * @param stage 停在哪一步，见 [SpecialDayWriteStage]
     * @param cause 异常类名；`renameTo` 平静地返回假时没有异常，那一档留 null（调用点不许把它折成"成功"）
     */
    data class WriteFailed(
        val stage: SpecialDayWriteStage,
        val cause: String?,
    ) : SpecialDayWriteOutcome

    /** `files/special_days` 这一趟立不起来：压根没地方写。与"写失败"分档，因为用户能做的下一步不同。 */
    data object CacheDirUnreachable : SpecialDayWriteOutcome
}

/** [SpecialDayWriteOutcome.WriteFailed] 停在写侧的哪一步（两步的自愈路径不同，不许并成一句）。 */
enum class SpecialDayWriteStage {
    /** 临时件没写完整（磁盘满／配额／IO 错误／权限） */
    TEMP_WRITE,

    /** 临时件写完整了，但改名这一步没成（ROM 拒绝，或改名那手自己抛出） */
    PROMOTE_RENAME,
}
