package com.buaa.schedule.data.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T283③：源码形状守卫 —— 把「字符数闸门先答、Base64 才动」与「闸门吃的条数来自原始 JSON、
 * 不来自模型的默认值」两件事钉在盘上（台账 #530 发现 1 + 发现 3 的接线侧）。
 *
 * 为什么这两件要一枚静态守卫：它们的失效形状在编译期看不出来，而行为测试只覆盖**判据本体**
 * （`ShareCodeSizeGateTest` / `ShareCodeEnvelopeReviewTest`）—— 判据写得再对，调用点只要把
 * `reviewEntry` 挪到 Base64 之后、或把 `counts` 换回 `data.courses.size`，全套表驱动单测照样绿。
 * 这一枚守的就是那一步绿着的假修。
 *
 * ## 四种钉法逐格点名
 *
 * 1. [字符数闸门排在Base64之前] —— **读形状**（花括号配平取 `decodeOutcome` 的体）+ **原文切片比先后**
 *    （只比两枚锚点在体内的相对位置，不吃行号）。
 * 2. [闸门吃的条数不来自模型的默认值] —— **抹注释找锚点**（`data.courses.size` 那一形在 codec 里归零；
 *    注释里逐字引用着它，不抹就是把自己的说明当病灶）+ **逐字节**（`counts = gateCounts,` 那一句）
 *    + 探测器自检（改回旧那一形必须抓得住）。
 * 3. [信封判据真的吃到原始读数] —— **读形状 + 数出现次数**（`envelopeFactsOf` 体内三枚原始读数各一处、
 *    体内零处 `data.`）。
 * 4. [留痕那一族] —— **数出现次数 + 逐字节**（新 tag 按标识符数：1 处声明 + 5 处引用、处处 Info；
 *    反向对照旧 tag 仍旧恰好 5 处；codec 里 `Log.` 零处、零 android import；
 *    再加逐字节的三枚：两枚没有异常可记的哨兵名各一枚 + 「请检查是否完整复制」那一句在 VM 恰好一枚 ——
 *    `StreamStoppedEarly` 那一档在 domain 里故意**没有**第二份句子，同句两写是本仓在册的病）。
 * 5. [两枚内核是纯 JVM 判据] —— **读形状**（逐行扫 import）+ **数出现次数**（宿主 object 各一处、
 *    内核里零 `.size`、零 `BackupData`、零 kotlinx）。
 * 6. [档位册子] —— **数出现次数**（codec 九枚档位声明各一处 + VM 七支 `is ShareCodeOutcome.` 各一处 +
 *    五句 domain 文案各一处调用）。
 * 7. [两本上限册子 + leniency] —— **逐字节**（`MAX_SHARE_CODE_CHARS` / `MAX_DECOMPRESSED_BYTES` /
 *    `MAX_IMPORT_BYTES` / `Json { ignoreUnknownKeys = true }` 各恰好一处，内核里零份 4MB 复制）。
 *
 * 只读源码文本，**不证明**运行期行为（那一半归 `ScheduleShareCodecTierTest`）。
 * 读法与小工具抄同目录 `ShareRestoreVolumeGuardTest`（抹注释保字面量 + 花括号配平取块 + 原文切片）；
 * `app/src/main/java` 整目录已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里进
 * `tasks.withType<Test>()` 的 inputs（T92①，台账 #136），所以"改了源码、单跑守卫拿到缓存绿灯"那一档盖得住。
 */
class ShareCodeEntryGateWiringGuardTest {

    // ─────────────── ① 字符数闸门排在 Base64 之前（读形状 + 先后）───────────────

    @Test
    fun 字符数闸门排在Base64之前() {
        val code = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        val hits = indexOfAll(code, DECODE_OUTCOME_ANCHOR)
        assertEquals(
            "「$DECODE_OUTCOME_ANCHOR」在 codec 里恰好一处（本层取块的宿主）：实到 ${hits.size} 处。" +
                "签名换掉（比如加一枚 maxCodeChars 参数）就是把这枚锚点念歪 —— " +
                "上限该由内核那本册子供着，不是让调用点各带一瓶",
            1,
            hits.size,
        )
        val body = braceBodyFrom(code, code.indexOf('{', hits.first() + DECODE_OUTCOME_ANCHOR.length))
        assertEquals(
            "decodeOutcome 体内「$ENTRY_CALL」恰好一处（实到 " + occurrences(body, ENTRY_CALL) +
                " 处）：字符数那一问只许有一枚答案。两处 = 两把尺；零处 = 改前那个形状" +
                "（几十 MB 的合法字符集先被整串解成 3/4 长度的 ByteArray，然后才看体积）",
            1,
            occurrences(body, ENTRY_CALL),
        )
        val base64Hits = indexOfAll(body, BASE64_DECODE)
        assertEquals(
            "decodeOutcome 体内「$BASE64_DECODE」恰好一处（实到 ${base64Hits.size} 处）：解码那一站只一枚，" +
                "本层要拿它跟闸门比先后",
            1,
            base64Hits.size,
        )
        val entryAt = body.indexOf(ENTRY_CALL)
        val trimAt = body.indexOf(TRIM_ANCHOR)
        val base64At = base64Hits.first()
        assertTrue(
            "先后判据：$ENTRY_CALL 必须排在「$TRIM_ANCHOR」之前（切片内位置 entry=$entryAt, trim=$trimAt）。" +
                "trim() 在无空白时返回同一个 String 是实现细节，押它就是借 JDK 当尺",
            trimAt > entryAt,
        )
        assertTrue(
            "先后判据：$ENTRY_CALL 必须排在 $BASE64_DECODE 之前（切片内位置 entry=$entryAt, base64=$base64At）。" +
                "这正是 #530 发现 1 的本体：Base64 之前那一格连长度都没看过 ⇒ 一次粘贴即分配约 1.75× 字符数的堆",
            entryAt < base64At,
        )
        assertEquals(
            "闸门吃的是**算好的** code.length（实到 " + occurrences(body, ENTRY_CALL) +
                " 处）：内核不许自己去摸那枚 String，也不许换成 trimmed.length —— " +
                "这枚上限管的就是粘过来多少个字符，剪贴板带的空白同样占那枚分配的量",
            1,
            occurrences(body, "reviewEntry(code.length,"),
        )
    }

    // ─────────────── ② 闸门吃的条数不来自模型的默认值（抹注释找锚点 + 逐字节）───────────────

    @Test
    fun 闸门吃的条数不来自模型的默认值() {
        val codec = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        for (needle in listOf("data.courses.size", "data.timeSlots.size", "data.reminders.size")) {
            assertEquals(
                "codec（抹注释后）里「$needle」必须 0 处（实到 " + occurrences(codec, needle) +
                    " 处）：这是 #530 发现 3 的反面 —— 模型每一枚字段都带默认值（version=当前版本、" +
                    "三张表=空列表），数它就等于让 ignoreUnknownKeys 替闸门把被改名的表清成 0，" +
                    "条数闸门与「没预览过先问一句」一起吃下那枚 0",
                0,
                occurrences(codec, needle),
            )
        }
        val hits = indexOfAll(codec, DECODE_OUTCOME_ANCHOR)
        val body = braceBodyFrom(codec, codec.indexOf('{', hits.first() + DECODE_OUTCOME_ANCHOR.length))
        assertEquals(
            "decodeOutcome 体内「$COUNTS_ARG」恰好一处（实到 " + occurrences(body, COUNTS_ARG) +
                " 处）：递给 capacityViolation 的条数只许是信封判据算好的那一枚",
            1,
            occurrences(body, COUNTS_ARG),
        )
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        assertEquals(
            "VM 里「$DECIDE_COUNTS」恰好一处（实到 " + occurrences(vm, DECIDE_COUNTS) +
                " 处）：decide 吃的条数必须是 gateCounts（口令路 = codec 递进来的原始读数）。" +
                "写回模型字段就是「确认弹窗一次都不过」那一等换一种写法回来",
            1,
            occurrences(vm, DECIDE_COUNTS),
        )
        assertEquals(
            "VM 里「$FALLBACK_SHAPE」恰好一处（实到 " + occurrences(vm, FALLBACK_SHAPE) +
                " 处）：模型字段那一路只准作为没递读数时的回落存在一枚（备份文件两路 + 确认回来那一手）。" +
                "第二处就是第二本账，谁也说不清闸门这次吃的是哪一枚",
            1,
            occurrences(vm, FALLBACK_SHAPE),
        )
        // 探测器自己也得认得改前那一形（否则这一层是空转的）
        val oldShape = codec.replace(
            COUNTS_ARG,
            "counts = RestoreCounts(courseCount = data.courses.size, timeSlotCount = data.timeSlots.size,",
        )
        assertTrue(
            "探测器必须抓得出改回旧形状的那一枚（实到 " + occurrences(oldShape, "data.courses.size") +
                " 处）：抓不住就说明本层在空转",
            occurrences(oldShape, "data.courses.size") >= 1,
        )
        assertEquals(
            "改回旧形状之后，本层锚点就该归零（实到 " + occurrences(oldShape, COUNTS_ARG) + " 处）",
            0,
            occurrences(oldShape, COUNTS_ARG),
        )
    }

    // ─────────────── ③ 信封判据真的吃到原始读数（读形状 + 数出现次数）───────────────

    @Test
    fun 信封判据真的吃到原始读数() {
        val codec = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        assertEquals(
            "全 app/src/main 里「$ENVELOPE_CALL」恰好一处调用点（实到 " + mainDefinitionCount(ENVELOPE_CALL) +
                " 处）：读没读全这件事只有信封判据一份答案，codec 里自己写 if 就是第二把尺",
            1,
            mainDefinitionCount(ENVELOPE_CALL),
        )
        val factsHits = indexOfAll(codec, FACTS_SIG)
        assertEquals(
            "codec 里「$FACTS_SIG」恰好一处定义（实到 ${factsHits.size} 处）",
            1,
            factsHits.size,
        )
        val factsBody = braceBodyFrom(codec, codec.indexOf('{', factsHits.first() + FACTS_SIG.length))
        for (needle in listOf(
            "present = root.containsKey(key),",
            "rows = (root[key] as? JsonArray)?.size,",
            "versionKeyPresent = root.containsKey(BACKUP_VERSION_KEY),",
        )) {
            assertEquals(
                "envelopeFactsOf 体内「$needle」恰好一处（实到 " + occurrences(factsBody, needle) +
                    " 处）：三枚原始读数（键在不在 / 数组行数 / 版本键在不在）是这一族唯一的取数口 —— " +
                    "在不在这件事必须问 payload 自己，模型的默认值答不了",
                1,
                occurrences(factsBody, needle),
            )
        }
        assertEquals(
            "envelopeFactsOf 体内零处「data.」（实到 " + occurrences(factsBody, "data.") +
                " 处）：测量那一段只许摸原始 JSON，摸到模型就是第 ② 层作废",
            0,
            occurrences(factsBody, "data."),
        )
        assertEquals(
            "envelopeFactsOf 体内「versionKeyPresent」恰好一处（实到 " + occurrences(factsBody, "versionKeyPresent") +
                " 处）：版本这一问交出去的是键在不在，不是值 —— 缺键与键=2 在写侧是同一件事" +
                "（encodeDefaults=false ⇒ 当前版本号被省略），混成一档就会打死自家口令",
            1,
            occurrences(factsBody, "versionKeyPresent"),
        )
        assertEquals(
            "envelopeFactsOf 体内零处「ShareCodeEnvelopeDecision」（实到 " +
                occurrences(factsBody, "ShareCodeEnvelopeDecision") +
                " 处）：这一段只测不判 —— 档位全在 ShareCodeEnvelopeReview 那边定，" +
                "测量这边出现档位字样就是判据被拆成了两把尺",
            0,
            occurrences(factsBody, "ShareCodeEnvelopeDecision"),
        )
        assertEquals(
            "envelopeFactsOf 体内零处「return ShareCodeOutcome」（实到 " +
                occurrences(factsBody, "return ShareCodeOutcome") +
                " 处）：测量函数不许直接交结论，必须把读数交给内核去判",
            0,
            occurrences(factsBody, "return ShareCodeOutcome"),
        )
    }

    // ─────────────── ④ 留痕：新 tag 处处 Info，codec 零 Log（数出现次数 + 逐字节）───────────────

    @Test
    fun 留痕挂在新tag上而codec一行Log都没有() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        assertEquals(
            "VM 里「$NEW_TAG」恰好六处（一处声明 + 五处引用：解码失败 / 太大 / 版本过高 / 读不懂 / 什么都没带；" +
                "实到 " + occurrences(vm, NEW_TAG) + " 处）：被拦的口令是事后唯一能查的账，" +
                "少一处就是有一档重新回到静默（#530 发现 4 的那句「留痕要排在写文案之前」又没人兑）",
            6,
            occurrences(vm, NEW_TAG),
        )
        assertEquals(
            "VM 里挂在「$NEW_TAG」上的 Info 恰好五处（全部读数：" + logLevelsAtTag(vm, NEW_TAG) +
                "）：级别漂到 Log.d / Log.v 在那台已知设备上等于没写（logcat 被截在 Info 以下）",
            5,
            logLevelsAtTag(vm, NEW_TAG).count { it == "Log.i(" },
        )
        assertEquals(
            "新 tag 不许挂在非 Info 级上（实到 " + logLevelsAtTag(vm, NEW_TAG).filterNot { it == "Log.i(" } +
                " 处）：同页 SpecialDayRefreshWiringGuardTest 第 ③ 层那条「取证行级别漂过就红」是本仓成文口径",
            0,
            logLevelsAtTag(vm, NEW_TAG).filterNot { it == "Log.i(" }.size,
        )
        assertEquals(
            "新 tag 的取值逐字节钉着「$NEW_TAG_VALUE」（实到 " + occurrences(vm, NEW_TAG_VALUE) +
                " 处）：换 tag 名等于把 logcat 那本账搬到没人查的地方",
            1,
            occurrences(vm, NEW_TAG_VALUE),
        )
        assertEquals(
            "反向对照：VM 里「$RESTORE_VOLUME_TAG_NEEDLE」仍旧恰好五处（实到 " +
                occurrences(vm, RESTORE_VOLUME_TAG_NEEDLE) +
                " 处）—— 本卡一枚都没往那枚 tag 上挂；它的 5 处引用 / 4 处 Info 是 T270 在册那两把尺",
            5,
            occurrences(vm, RESTORE_VOLUME_TAG_NEEDLE),
        )
        assertEquals(
            "反向对照：RESTORE_VOLUME_LOG_TAG 挂在 Info 上的枚数仍旧恰好四处（实到 " +
                logLevelsAtTag(vm, RESTORE_VOLUME_TAG_NEEDLE).count { it == "Log.i(" } + " 处）",
            4,
            logLevelsAtTag(vm, RESTORE_VOLUME_TAG_NEEDLE).count { it == "Log.i(" },
        )
        val codec = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        assertEquals(
            "codec 里「Log.」必须 0 处（实到 " + occurrences(codec, "Log.") +
                " 处）：这一族的行为测试全在 JVM 上跑（ScheduleShareCodecTest 与三枚 backup 兼容测试直接调 decode），" +
                "而本仓没开 unitTests.returnDefaultValues ⇒ 在这里挂 Log 会让那些测试当场红" +
                "（Method i in android.util.Log not mocked）。留痕因此在调用点，" +
                "codec 负责把 Throwable 的读数随结论交出去",
            0,
            occurrences(codec, "Log."),
        )
        assertEquals(
            "codec 里零处 android import（实到 " + importLinesStartingWith(codec, "android") + " 处）：同上一条理由",
            0,
            importLinesStartingWith(codec, "android").size,
        )
        // 逐字节：两枚哨兵名 + 那一格唯一该说"没复制完整"的句子。
        // StreamStoppedEarly 那一档在 domain 里**没有**第三句文案（同句两写是本仓在册的病），
        // 它要说的那句在 VM 的 Malformed 那支已有在册原文 —— 这里钉的就是那一句与那两枚哨兵名，
        // 少一枚就是"被体积上限拦下"与"被剪贴板截断"重新折成一句话（#530 发现 2 换一种写法回来）。
        for (sentinel in listOf(STREAM_SENTINEL, ENVELOPE_SENTINEL)) {
            assertEquals(
                "codec 里哨兵名 $sentinel 恰好一处（实到 " + occurrences(codec, sentinel) +
                    " 处）：它是 Malformed 那一句里唯一真实的那一半，多一处就是第二格借它的句子说话",
                1,
                occurrences(codec, sentinel),
            )
        }
        assertEquals(
            "VM 里「$MALFORMED_COPY」恰好一处（实到 " + occurrences(vm, MALFORMED_COPY) +
                " 处）：这一句是 Malformed / StreamStoppedEarly 共用的那一句，" +
                "而 OverSize 那两档**不许**念它（domain 那边逐字钉着「这一句里不许出现完整复制」）",
            1,
            occurrences(vm, MALFORMED_COPY),
        )
    }

    // ─────────────── ⑤ 两枚内核是纯 JVM 判据（读形状 + 数出现次数）───────────────

    @Test
    fun 两枚内核零android零时钟零设备() {
        for (path in listOf(SIZE_KERNEL, ENVELOPE_KERNEL)) {
            val code = blankCommentsKeepingLiterals(source(path))
            val name = path.substringAfterLast('/')
            for (prefix in listOf("android", "androidx", "kotlinx")) {
                val imports = importLinesStartingWith(code, prefix)
                val why = if (prefix == "kotlinx") {
                    "键名与条数的解析全在 codec 那侧做完，内核只吃算好的值 —— 内核里出现 kotlinx " +
                        "就是它自己去看 JSON（第二把尺，且判据不再纯 JVM 可测）"
                } else {
                    "判据不许碰设备 / 机型 / 权限（这一族的成文规矩，同 RestoreVolumeGate 那一层）"
                }
                assertEquals(
                    "$name 里「import $prefix…」必须 0 处（实到 $imports 处）：$why",
                    0,
                    imports.size,
                )
            }
            for (forbidden in listOf(
                "System.currentTimeMillis(",
                "Date(",
                "LocalDate",
                "ZoneId",
                "TimeZone",
                "Build.VERSION",
                "Context",
                "checkSelfPermission",
                "BackupData",
                ".size",
            )) {
                assertEquals(
                    "$name 里「$forbidden」必须 0 处（实到 " + occurrences(code, forbidden) +
                        " 处）：内核自己数列表、自己认模型、自己读钟，就是把只吃算好的值那句话作废",
                    0,
                    occurrences(code, forbidden),
                )
            }
        }
        assertEquals(
            "内核宿主「object ShareCodeSizeGate {」全 app/src/main 恰好一处（实到 " +
                mainDefinitionCount("object ShareCodeSizeGate {") + " 处）：第二枚 object = 第二本体积册子",
            1,
            mainDefinitionCount("object ShareCodeSizeGate {"),
        )
        assertEquals(
            "内核宿主「object ShareCodeEnvelopeReview {」全 app/src/main 恰好一处（实到 " +
                mainDefinitionCount("object ShareCodeEnvelopeReview {") + " 处）",
            1,
            mainDefinitionCount("object ShareCodeEnvelopeReview {"),
        )
        val sizeKernel = blankCommentsKeepingLiterals(source(SIZE_KERNEL))
        for (fn in listOf("fun reviewEntry(", "fun reviewPayload(")) {
            assertEquals(
                "「$fn」在体积内核文件里恰好一处定义（实到 " + occurrences(sizeKernel, fn) + " 处）",
                1,
                occurrences(sizeKernel, fn),
            )
        }
        val envelopeKernel = blankCommentsKeepingLiterals(source(ENVELOPE_KERNEL))
        assertEquals(
            "「fun review(」在信封内核文件里恰好一处定义（实到 " + occurrences(envelopeKernel, "fun review(") +
                " 处）：读没读全这件事只一处判",
            1,
            occurrences(envelopeKernel, "fun review("),
        )
    }

    // ─────────────── ⑥ 档位册子：九枚声明各一处，一个都没折成 Boolean（数出现次数）───────────────

    @Test
    fun 档位册子还是九枚声明各一处() {
        val codec = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        for (decl in listOf(
            "data class Malformed",
            "sealed interface OverSize",
            "data class EntryTooLong",
            "data class PayloadTooLarge",
            "data class FromNewerApp",
            "data class UnreadableTables",
            "data class NothingRestorable",
            "data class OverCapacity",
            "data class Valid",
        )) {
            assertEquals(
                "ShareCodeOutcome 里「$decl」恰好一处声明（实到 " + occurrences(codec, decl) +
                    " 处）：九枚少一枚就有一格要借别人的句子说话。卡面要求档位不许折成 Boolean，" +
                    "这一层数的就是那本册子本身",
                1,
                occurrences(codec, decl),
            )
        }
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        for (branch in listOf(
            "is ShareCodeOutcome.Malformed ->",
            "is ShareCodeOutcome.OverSize ->",
            "is ShareCodeOutcome.FromNewerApp ->",
            "is ShareCodeOutcome.UnreadableTables ->",
            "is ShareCodeOutcome.NothingRestorable ->",
            "is ShareCodeOutcome.OverCapacity ->",
            "is ShareCodeOutcome.Valid ->",
        )) {
            assertEquals(
                "VM 的口令那一段里「$branch」恰好一处（实到 " + occurrences(vm, branch) +
                    " 处）：档位在 codec 里分出来了，调用点就得有一支念它 —— 少一支就是那一档在屏上没话",
                1,
                occurrences(vm, branch),
            )
        }
        for (copy in listOf(
            "shareCodeTooManyCharsCopy(",
            "shareCodeOverCapCopy(",
            "shareCodeNewerFormatCopy(",
            "shareCodeUnreadableTablesCopy(",
            "shareCodeNothingNamedCopy(",
        )) {
            assertEquals(
                "VM 里「$copy」恰好一处调用（实到 " + occurrences(vm, copy) +
                    " 处）：句子由 domain 那本册子渲染，ui 只念不拼（拼第二句就是两把尺）",
                1,
                occurrences(vm, copy),
            )
        }
        assertEquals(
            "兼容入口「fun decode(code: String): BackupData?」恰好一处且形状未变（实到 " +
                occurrences(codec, "fun decode(code: String): BackupData?") +
                " 处）：老调用点与老测试的契约（被拒那一律仍旧交回 null）不许被本卡改掉",
            1,
            occurrences(codec, "fun decode(code: String): BackupData?"),
        )
    }

    // ─────────────── ⑦ 两本上限册子各一处 + leniency 一字未动（逐字节）───────────────

    @Test
    fun 上限册子与leniency一字未动() {
        assertEquals(
            "字符数上限「$ENTRY_CAP」全 app/src/main 恰好一处（实到 " + mainDefinitionCount(ENTRY_CAP) +
                " 处）：第二处就是第二把尺（那枚数的推算写在内核文件的 KDoc 里）",
            1,
            mainDefinitionCount(ENTRY_CAP),
        )
        val codec = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        for (needle in listOf(
            "MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024",
            PAYLOAD_CALL,
            LENIENT,
            "const val PREFIX = \"BUAASCH1:\"",
            BASE64_DECODE,
            "Inflater()",
            FINISHED_READ,
        )) {
            assertEquals(
                "codec 里「$needle」恰好一处（实到 " + occurrences(codec, needle) +
                    " 处）：编码格式与那枚 leniency 都属红线 —— 第⓪问裁的是乙（继续 lenient、" +
                    "条数改从原始 payload 上数），改成 strict 会打死「新版加字段老应用照旧能读」那一族契约；" +
                    "而 finished() 必须排在 end() 之前读到，否则第 ② 问没有输入",
                1,
                occurrences(codec, needle),
            )
        }
        for (kernel in listOf(SIZE_KERNEL, ENVELOPE_KERNEL)) {
            val code = blankCommentsKeepingLiterals(source(kernel))
            assertEquals(
                "${kernel.substringAfterLast('/')} 里零处「4 * 1024 * 1024」（实到 " +
                    occurrences(code, "4 * 1024 * 1024") +
                    " 处）：解压上限由调用方递进来，内核不复制一份（同 RestoreCounts 那本册子的规矩）",
                0,
                occurrences(code, "4 * 1024 * 1024"),
            )
        }
        assertEquals(
            "文件那一路的入口闸门「$IMPORT_CAP」全 app/src/main 恰好一处声明（实到 " +
                mainDefinitionCount(IMPORT_CAP) + " 处）",
            1,
            mainDefinitionCount(IMPORT_CAP),
        )
        assertEquals(
            "「readTextLimited(MAX_IMPORT_BYTES)」仍旧恰好三处调用点（实到 " +
                mainDefinitionCount("readTextLimited(MAX_IMPORT_BYTES)") +
                " 处）：本卡没把口令那一路硬塞进文件那把尺（那枚尺的载体是 InputStream，" +
                "粘贴这一路根本没有流）—— 两路口各一枚上限、各一句实话",
            3,
            mainDefinitionCount("readTextLimited(MAX_IMPORT_BYTES)"),
        )
        val models = blankCommentsKeepingLiterals(source(BACKUP_MODELS))
        assertEquals(
            "顶层键册子只一枚真源：「val BACKUP_ENVELOPE_KEYS」在 BackupModels.kt 里恰好一处（实到 " +
                occurrences(models, "val BACKUP_ENVELOPE_KEYS") +
                " 处），domain 那两枚内核里零处（第 ⑤ 层的 kotlinx / BackupData 禁令已经盖住那一头）",
            1,
            occurrences(models, "val BACKUP_ENVELOPE_KEYS"),
        )
        assertEquals(
            "红线：「const val BACKUP_FORMAT_VERSION = 2」必须逐字还在（实到 " +
                occurrences(models, "const val BACKUP_FORMAT_VERSION = 2") + " 处）：本卡没加字段也没涨版本号",
            1,
            occurrences(models, "const val BACKUP_FORMAT_VERSION = 2"),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private data class Source(val path: String, val code: String)

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.map {
            Source(it.relativeTo(root).path.replace('\\', '/'), blankCommentsKeepingLiterals(it.readText()))
        }
    }

    private fun mainDefinitionCount(needle: String): Int = mainSources().sumOf { occurrences(it.code, needle) }

    // 本文件**故意不带** `lineOf` 那种"把偏移换算成行号"的小工具：卡面要求失败消息里不许出现行号，
    // 而消息里只报锚点原文与枚数（`实到 N 处`）就足够定位 —— 行号一漂，守卫自己就先红（在册同族教训）。

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
        return hits
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    /**
     * 从 tag 的**引用位**往回认它挂在哪一级日志上：形状是 `Log.` + 级别字母 + `(` + 任意空白 + tag。
     *
     * 按标识符取位，既不吃行号也不吃换行与缩进 —— 调用点把 `Log.i(` 与 tag 写成两行（长消息那种
     * 换行写法是 KDoc 与格式化工具自己选的）照样算一处。认不出日志调用的引用位（枚举个人的
     * `private const val` 声明）自然落空，不进账。
     */
    private fun logLevelsAtTag(code: String, tag: String): List<String> =
        indexOfAll(code, tag).mapNotNull { at ->
            val call = code.lastIndexOf("Log.", at)
            if (call < 0) return@mapNotNull null
            val between = code.substring(call + "Log.".length, at)
            val level = Regex("""^([vdiwe])\(\s*$""").find(between)?.groupValues?.get(1) ?: return@mapNotNull null
            "Log.$level("
        }

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(
            open + 1,
            (matchingBracket(source, open, '{', '}') ?: error("花括号没配平：从 $open 起")),
        )
    }

    /** 括号配平（跳过字符串与字符字面量）；不配平返回 null */
    private fun matchingBracket(src: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '\'' -> {
                    // 字符字面量：'{' / '}' 之类不参与配平
                    var j = i + 1
                    if (src.getOrNull(j) == '\\') j += 2 else j += 1
                    if (src.getOrNull(j) == '\'') i = j
                }

                c == openChar -> depth++
                c == closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 本卡尤其需要抹注释：codec、VM 与内核的 KDoc 都逐字引用着改前那两行闸门（登记旧形状用）。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
    /** 逐行扫 import：按前缀取那些行（不吃行号，返回的是原文行内容） */
    private fun importLinesStartingWith(code: String, prefix: String): List<String> =
        code.split('\n').mapNotNull { line ->
            val t = line.trim()
            if (t.startsWith("import $prefix")) t else null
        }

    private companion object {
        const val SHARE_CODEC = "com/buaa/schedule/data/share/ScheduleShareCodec.kt"
        const val SIZE_KERNEL = "com/buaa/schedule/domain/schedule/ShareCodeSizeGate.kt"
        const val ENVELOPE_KERNEL = "com/buaa/schedule/domain/schedule/ShareCodeEnvelopeReview.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val BACKUP_MODELS = "com/buaa/schedule/data/backup/BackupModels.kt"

        /** 解码那一站的宿主锚点（与 T270 那枚守卫同一把字面量，签名不许动） */
        const val DECODE_OUTCOME_ANCHOR = "fun decodeOutcome(code: String): ShareCodeOutcome"
        const val ENTRY_CALL = "ShareCodeSizeGate.reviewEntry(code.length,"
        const val BASE64_DECODE = "Base64.getUrlDecoder()"
        const val TRIM_ANCHOR = "val trimmed = code.trim()"
        const val COUNTS_ARG = "counts = counts,"
        const val DECIDE_COUNTS = "counts = gateCounts,"
        const val FALLBACK_SHAPE = "counts ?: RestoreCounts("
        const val ENVELOPE_CALL = "ShareCodeEnvelopeReview.review("
        const val FACTS_SIG = "private fun envelopeFactsOf(root: JsonObject): ShareCodeEnvelopeFacts"
        const val PAYLOAD_CALL = "reviewPayload(decompressedBytes, MAX_DECOMPRESSED_BYTES, streamFinished)"
        const val LENIENT = "Json { ignoreUnknownKeys = true }"
        const val FINISHED_READ = "streamFinished = inflater.finished()"
        const val ENTRY_CAP = "MAX_SHARE_CODE_CHARS = 1_000_000"
        const val IMPORT_CAP = "const val MAX_IMPORT_BYTES = 8 * 1024 * 1024"

        const val NEW_TAG = "SHARE_CODE_LOG_TAG"
        const val NEW_TAG_VALUE = "SHARE_CODE_LOG_TAG = \"ShareCodeGuard\""
        const val RESTORE_VOLUME_TAG_NEEDLE = "RESTORE_VOLUME_LOG_TAG"

        /** 两枚没有 Throwable 可记的哨兵读数（连引号一起钉，免得把注释里的说明当病灶） */
        const val STREAM_SENTINEL = "\"incomplete-deflate-stream\""
        const val ENVELOPE_SENTINEL = "\"envelope-not-an-object\""

        /** StreamStoppedEarly 那一档借以说话的那一句（调用点在册原文，全仓只此一份） */
        const val MALFORMED_COPY = "AppMessage(\"口令无法识别，请检查是否完整复制\", isError = true)"
    }
}
