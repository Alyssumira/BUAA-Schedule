package com.buaa.schedule.domain.schedule

/**
 * 分享口令的**体积闸门**（台账 #530 发现 1 + 发现 2）：入口字符数一把尺、解压后字节数一把尺，
 * 两把尺各自成档，谁越界点谁的名。
 *
 * ## 病在哪（改前的两行）
 *
 * 1. `data/share/ScheduleShareCodec.kt` 里 `Base64.getUrlDecoder().decode(...)` 排在**任何**长度
 *    判据之前：一条 `BUAASCH1:` + 几十 MB 合法字符集的粘贴，先把整串解成 3/4 长度的 `ByteArray`，
 *    而那段 `String` 本身按 UTF-16 已经占 2 字节/字符 ⇒ 一次粘贴即分配约 1.75× 字符数的堆。
 *    `ui/ImportLimits.kt` 的立项注释逐字点的就是这一等（"无上限地吸进内存再交给解析器，
 *    是可被构造的 OOM/ANR 路径"），但那把尺（`MAX_IMPORT_BYTES`）今天只吃三路**文件**入口
 *    （`readTextLimited` 是 `InputStream` 的扩展函数，粘贴那一路根本没有流）。
 * 2. 解压循环在 4MB 处 `break` 出去后，把**截断前缀**直接交给 JSON 解析器，解析失败就整条折进
 *    `Malformed` ⇒ 用户读到「口令无法识别，请检查是否完整复制」。可那条口令是**完整的**，
 *    只是内容太大；于是他被劝去重新复制一条本来就完整的码（发现 2 的假话）。
 *    同一条口令如果是"行数越界"那一档，得到的才是 `restoreVolumeRejectCopy` 点名表名的实话。
 *
 * ## 本文件的规矩（与同目录 `RestoreVolumeGate.kt` 同一口径）
 *
 * 纯 JVM 判据：吃**调用方已经算好的**数值（字符数 / 解压字节数 / 那两枚上限 / `inflater.finished()`
 * 的 Boolean），交回"进不进得去、哪一档"。本文件不许自己去 `List.size`、不许自己 inflate、
 * 不碰 Room、不碰 kotlinx、不读时钟/时区/权限/机型/`Build.VERSION`、不碰设备。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 三档要说三种不同的话：「太长」要报字符数与上限并劝人别贴这么大的东西（下面第一句）；
 * 「太大」要报解压字节数与上限、并且**不许**劝人重新复制（下面第二句）；
 * [ShareCodePayloadGate.StreamStoppedEarly] 那一档说的才是「多半没复制完整」—— 那一句在本仓
 * 已有在册原文（调用点 `Malformed` 那支的「请检查是否完整复制」），所以它**不需要第二份句子**，
 * 却**需要自己那一格**：少了它，"被体积上限拦下"与"被剪贴板截断"就只能在同一句话里赌一个，
 * 而那两个下一步正好相反（一个劝人删掉内容重导、一个劝人重新复制整串）。
 * 折成 Boolean 之后"太大"与"读不懂"在同一枚 false 里合体，调用点只能猜 —— 那正是发现 2 的病灶。
 *
 * ## 那枚 1,000,000 字符上限怎么来的（推算，不是实测）
 *
 * 合法规模的最坏一枚由 `RestoreVolumeGate` 那本册子决定：课程 2000 条 × 最小行 51 字节
 * （那边逐字记着"实测形状最小的 JSON 行 51 字节"）≈ 10 万字节，加节次 500 行、提醒 2000 行、
 * 学期行 ≈ 再 10 万字节 ⇒ 解压前 ~2×10⁵ 字节；deflate 对这种重复度高的 JSON 约 4× ⇒ 5×10⁴ 字节；
 * URL-safe Base64 再 ×4/3 ⇒ **约 7×10⁴ 字符**。上限取 1,000,000 字符 ≈ 这个最坏合法值的 14 倍，
 * 同时把"闸门之前已经发生的分配"钉死在 ~2MB 字符 + ~750KB Base64 字节这一档，而不是无上限。
 * 想收紧到 10⁵ 也讲得通，但那会把"用户手改了大备份"那一档一起掐掉，本卡朝宽方向不许误杀合法规模。
 */
sealed interface ShareCodeEntryGate {

    /** 字符数在上限内 ⇒ 允许往下走（下一步才是 Base64） */
    data object Proceed : ShareCodeEntryGate

    /**
     * 贴进来的东西本身就不可能是一条口令。
     *
     * ⚠️ 这一档**不叫**「无法识别」：它识别得出来（前缀在不在都还没问），只是不许分配。
     */
    data class TooManyChars(val codeChars: Int, val maxCodeChars: Int) : ShareCodeEntryGate
}

/** 解压那一站的三档结论（[Proceed] / [OverCap] / [StreamStoppedEarly]）。 */
sealed interface ShareCodePayloadGate {

    /** 流自己结束了，且解压出来的字节数没越过 [capBytes] */
    data class Proceed(val decompressedBytes: Int, val capBytes: Int) : ShareCodePayloadGate

    /**
     * 解压输出撞上体积上限而流**还没结束** ⇒ 这条口令的内容比本应用能接的大。
     *
     * [streamFinished] = false 是常态（循环就是在这一格停的）；= true 那一等（恰好落在上限上又
     * 自称结束了）也归本档，因为"等于上限"不是"在上限内"—— 宁可少接一条，不许多写一行。
     */
    data class OverCap(val decompressedBytes: Int, val capBytes: Int, val streamFinished: Boolean) :
        ShareCodePayloadGate

    /**
     * 流在上限**之前**就自己停了（`inflate` 回 0 而 `finished()` 是 false）：
     * 压缩流不完整/被改坏 ⇒ 这一档才该说「请检查是否完整复制」。
     */
    data class StreamStoppedEarly(val decompressedBytes: Int, val capBytes: Int) : ShareCodePayloadGate
}

object ShareCodeSizeGate {

    /**
     * 入口字符数上限（推算过程见本文件 KDoc 最后那段）。
     *
     * 为什么记在 domain 而不在 `ui/ImportLimits.kt`：
     * `MAX_IMPORT_BYTES` 的载体是 `InputStream.readTextLimited(maxBytes)`，它**只能**长在"读流"那一格，
     * 而口令那一路到手时已经是一枚 `String`（剪贴板文本），没有流可读；
     * 且 `app/src/main/java/com/buaa/schedule/data` 那一整棵目录全仓零处 import `com.buaa.schedule.ui`
     * （实测 `grep -rn "^import com.buaa.schedule.ui" app/src/main/java/com/buaa/schedule/data` 零命中），
     * 把判据写进 ui 再让 codec 回头拿常量就是让 data 层倒依存 ui 层。
     * 同族先例：口令那一路的**条数**上限也记在 domain（`RestoreVolumeLimits.MAX_TIME_SLOT_COUNT = 500`），
     * 不在 ui —— 同一族的入口上限在同一本册子里，才数得出"两路口各几把尺"。
     */
    const val MAX_SHARE_CODE_CHARS = 1_000_000

    /**
     * 入口那一问：**先量字符数，再决定要不要分配**。
     *
     * @param codeChars 由调用方算好的 `code.length`（本文件不许自己去摸那个 String）
     */
    fun reviewEntry(codeChars: Int, maxCodeChars: Int = MAX_SHARE_CODE_CHARS): ShareCodeEntryGate =
        if (codeChars > maxCodeChars) {
            ShareCodeEntryGate.TooManyChars(codeChars, maxCodeChars)
        } else {
            ShareCodeEntryGate.Proceed
        }

    /**
     * 解压那一问：**不许把截断前缀交给解析器**。
     *
     * @param decompressedBytes 调用方在循环退出后读到的 `out.size()`
     * @param capBytes 调用方自己的那枚解压上限（codec 的 `MAX_DECOMPRESSED_BYTES`，本文件不复制一份）
     * @param streamFinished 调用方在 `inflater.end()` **之前**读到的 `inflater.finished()`
     */
    fun reviewPayload(
        decompressedBytes: Int,
        capBytes: Int,
        streamFinished: Boolean,
    ): ShareCodePayloadGate {
        if (!streamFinished && decompressedBytes >= capBytes) {
            return ShareCodePayloadGate.OverCap(decompressedBytes, capBytes, streamFinished = false)
        }
        if (!streamFinished) {
            return ShareCodePayloadGate.StreamStoppedEarly(decompressedBytes, capBytes)
        }
        if (decompressedBytes >= capBytes) {
            return ShareCodePayloadGate.OverCap(decompressedBytes, capBytes, streamFinished = true)
        }
        return ShareCodePayloadGate.Proceed(decompressedBytes, capBytes)
    }
}

// ─────────────────── 文案（档位与句子同源，抄进 ui 就是第二把尺）───────────────────

/** [ShareCodeEntryGate.TooManyChars] 那一句：说得出字符数与上限，并保证一次都没分配那枚 ByteArray。 */
fun shareCodeTooManyCharsCopy(decision: ShareCodeEntryGate.TooManyChars): String =
    "口令导入已拒绝：贴进来的文本有 ${decision.codeChars} 个字符，超过上限 ${decision.maxCodeChars} 个 ⇒ " +
        "一条都没有写入，你的课程与节次表还是原样。这不是复制不完整，是这份东西太大了本应用不接 —— " +
        "让对方用 App 重新导出一条正常的口令"

/**
 * [ShareCodePayloadGate.OverCap] 那一句（发现 2 要的"完整但太大"那一档）。
 *
 * ⚠️ 这一句里**不许**出现「请检查是否完整复制」—— 那条口令可以是完整的，只是内容超上限；
 * 劝人重新复制一条本来就完整的码就是假话（改前正是这一格折进了 `Malformed`）。
 *
 * ⚠️ [ShareCodePayloadGate.StreamStoppedEarly] 那一档**故意没有第三句**：它要说的那句
 * 「口令无法识别，请检查是否完整复制」在调用点（`ui/ScheduleViewModel.importShareCode` 的
 * `Malformed` 那支）已有在册原文，本文件再写一遍就是同一句话的两个版本（同页在册的同句多写那一族）。
 * 这一档在 codec 里折进 `Malformed` 时带着自己的读数（`failure.type = "incomplete-deflate-stream"`），
 * logcat 因此仍分得出"被上限拦下"与"被剪贴板截断"。
 */
fun shareCodeOverCapCopy(decision: ShareCodePayloadGate.OverCap): String =
    "口令导入已拒绝：这条口令解压后要占 ${decision.decompressedBytes} 字节以上，超过上限 " +
        "${decision.capBytes} 字节 ⇒ 一条都没有写入，你的课程与节次表还是原样。" +
        "口令本身未必被剪贴板截断，是它带的课表太大（周次/节次行数被改大也会这样）—— " +
        "请让对方删掉不需要的学期或整门课再导一次"

/** [ShareCodePayloadGate.StreamStoppedEarly] 那一句：这一档才该说"复制不完整"。 */
fun shareCodeStreamStoppedCopy(decision: ShareCodePayloadGate.StreamStoppedEarly): String =
    "口令无法识别：压缩流在 ${decision.decompressedBytes} 字节处就断了（还没到上限 ${decision.capBytes} 字节），" +
        "多半是没复制完整 ⇒ 一条都没有写入。请重新复制以 BUAASCH1: 开头的那一整串"
