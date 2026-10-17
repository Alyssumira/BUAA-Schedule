package com.buaa.schedule.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T422 甲档：壁纸采样档位（`wallpaperSampleSize`）的账 —— 纯 JVM 表驱动，零 Context、零设备。
 *
 * ## 病灶（只这一处，别泛化）
 * 本文件原有那道 `while (outWidth / (sampleSize * 2) >= 1080 && outHeight / (sampleSize * 2) >= 1920)`
 * 要求**两轴同时**达标，而两个目标数是按竖屏配对的（宽对宽、高对高）。一张 4032×3024 的横图
 * 短边再缩一档是 1512 < 1920 ⇒ 循环一轮都不跑 ⇒ `inSampleSize = 1` ⇒ 整份进内存：
 * 12,192,768 px × 4 B（ARGB_8888）= 48,771,072 B ≈ 46.5 MiB；同一开关下竖图 3024×4032 只花
 * 12,192,768 B ⇒ 横竖差恰 4 倍。清单没有 `android:largeHeap`、也没有 `android:process` 可指望。
 *
 * ## 落点（第⓪问裁定＝丙档）
 * 第一趟原样保留（"其余不动"），其后加一记**长边硬上界** [WALLPAPER_MAX_DECODED_LONG_EDGE_PX]。
 * 不选甲（按实屏量目标）：本卡文件切面只有一枚，甲要么改两枚调用点把设备事实传进来（本卡对它们
 * 只许读），要么在本文件取 `DisplayMetrics` —— 而 API 31+ 的 WindowManager 分档读数一手出处取不到，
 * 本卡不许拿猜测选目标分辨率。不选乙（解码后二次缩放）：46.5 MiB 的整份解码峰值照旧先发生，
 * 这一档治驻留不治峰值，与本卡的病灶不对口。
 *
 * ## 平台事实
 * `inSampleSize` 只按 2 的幂生效（非 2 的幂向下取整到 2 的幂），所以第二趟沿 1 → 2 → 4 → 8 跳，
 * 停在第一个让长边不超过上界的 2 的幂。[theLadderOnlyEverReturnsAPowerOfTwo] 里 6000 那格就是
 * 「算出 3 就以为缩了 3 倍」那种假判据的靶子：正确档是 4，不是 3。
 */
class WallpaperSampleSizeTest {

    /**
     * 主表：卡面点名的四格（横图 4032×3024／竖图 3024×4032／边界形 2159×3839／小图 800×600）
     * 全部在册，另加上界两端、常见竖幅的代价格、非正边界与 Int.MAX_VALUE 那格必须终止的形状。
     *
     * 逐格断四件事：档位**等于**表里那个数、是 2 的幂、解出来的长边不超过上界、
     * 字节账（ARGB_8888 = 4 B/px）等于表里那个数（不是"大概小了一些"）。
     */
    @Test
    fun everySizingCellLandsOnTheTabledSampleSizeAndByteAccount() {
        for (cell in cells) {
            val sample = cell.widthPx.wallpaperSampleSizeFor(cell.heightPx)
            assertEquals("${cell.label}：档位不是 ${cell.expectedSampleSize}", cell.expectedSampleSize, sample)
            assertTrue(
                "${cell.label}：档位 $sample 不是 2 的幂 —— inSampleSize 会把它向下取整，" +
                    "等于把「以为缩了 3 倍」那种假判据请回来",
                sample > 0 && sample and (sample - 1) == 0,
            )
            val decodedWidth = cell.widthPx / sample
            val decodedHeight = cell.heightPx / sample
            val longEdge = maxOf(decodedWidth, decodedHeight)
            assertTrue(
                "${cell.label}：解出来的长边 $longEdge 越过上界 $CEILING（最坏账没封顶＝本卡的落点没成立）",
                longEdge <= CEILING,
            )
            val bytes = decodedWidth.toLong() * decodedHeight.toLong() * BYTES_PER_PIXEL
            assertEquals("${cell.label}：字节账不是 ${cell.expectedBytes}", cell.expectedBytes, bytes)
            assertEquals("${cell.label}：像素数不是 ${cell.expectedPixels}", cell.expectedPixels, bytes / BYTES_PER_PIXEL)
        }
    }

    /**
     * 卡面那格 4 倍不对称要**当场**量两次：改前公式（本文件原样的两轴判据，在测试里同式复算）
     * 对横图给 1、对竖图给 2；新档位对两者给同一档 ⇒ 横竖字节账逐位相等。
     *
     * 这一枚是"修的是不对称，不是只把横图调小"的凭据：只断横图变小会放过"横竖各坏一处"的形状。
     */
    @Test
    fun landscapeAndPortraitNowPayTheSameByteAccount() {
        val landscape = LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT
        val portrait = LANDSCAPE_HEIGHT to LANDSCAPE_WIDTH
        assertEquals(
            "改前横图那格的实测读数变了（现给 ${legacySampleSize(landscape.first, landscape.second)}）：" +
                "本卡说的「循环一轮都不跑」依赖这一格",
            1,
            legacySampleSize(landscape.first, landscape.second),
        )
        assertEquals(
            "改前竖图那格的实测读数变了（现给 ${legacySampleSize(portrait.first, portrait.second)}）",
            2,
            legacySampleSize(portrait.first, portrait.second),
        )
        val legacyLandscapeBytes = bytesOf(landscape.first, landscape.second, legacySampleSize(landscape.first, landscape.second))
        val legacyPortraitBytes = bytesOf(portrait.first, portrait.second, legacySampleSize(portrait.first, portrait.second))
        val legacyRatio = legacyLandscapeBytes / legacyPortraitBytes
        assertEquals("改前横竖的字节账不是恰 4 倍（实测 $legacyRatio）", 4L, legacyRatio)
        assertEquals("改前横图那格不是卡面复算的 48,771,072 B", 48_771_072L, legacyLandscapeBytes)

        val landscapeBytes = bytesOf(landscape.first, landscape.second, wallpaperSampleSize(landscape.first, landscape.second))
        val portraitBytes = bytesOf(portrait.first, portrait.second, wallpaperSampleSize(portrait.first, portrait.second))
        assertEquals(
            "改后横竖不再同账（横 $landscapeBytes / 竖 $portraitBytes）—— 4 倍不对称塌成 1 倍是这一档的立论",
            portraitBytes,
            landscapeBytes,
        )
        assertEquals("改后横图那格应恰好落在 12,192,768 B（与改前竖图同值）", 12_192_768L, landscapeBytes)
        assertTrue(
            "改后横图仍比上界的封顶值大：$landscapeBytes B > 封顶值 ${CEILING * CEILING * BYTES_PER_PIXEL} B",
            landscapeBytes <= CEILING.toLong() * CEILING * BYTES_PER_PIXEL,
        )
    }

    /**
     * 「2 的幂」那一格：档位在整个横竖尺寸扫描里全是 2 的幂，且 6000×2400 落在 4 而不是 3。
     *
     * 假判据的形状写清楚：`ceil(6000 / 2160) = 3`，有人会把这枚 3 直接交给 `inSampleSize`
     * 并以为缩了 3 倍；平台向下取整到 2 的幂 ⇒ 实际只缩到 2 ⇒ 长边 3000 仍越过上界。
     */
    @Test
    fun theLadderOnlyEverReturnsAPowerOfTwo() {
        val edges = generateSequence(1) { if (it >= 70000) null else it * 3 }
            .takeWhile { it <= 70000 }
            .toList()
        for (width in edges) {
            for (height in edges) {
                val sample = wallpaperSampleSize(width, height)
                assertTrue("$width×$height 给出 $sample，不是 2 的幂：平台会向下取整，档位与账都对不上",
                    sample > 0 && sample and (sample - 1) == 0)
                assertTrue("$width×$height 解出的长边 ${maxOf(width, height) / sample} 越过上界 $CEILING",
                    maxOf(width / sample, height / sample) <= CEILING)
            }
        }
        assertEquals(
            "6000×2400 这一格是假判据的靶子：ceil(6000/2160)=3，正确档是 4（3 会被平台折成 2，长边 3000 越界）",
            4,
            wallpaperSampleSize(6000, 2400),
        )
    }

    /**
     * 上界关掉 ⇒ 逐格退回旧形状：这一档是"本卡只动了上界、没顺手改第一趟"的全部凭据。
     *
     * 把 [WALLPAPER_MAX_DECODED_LONG_EDGE_PX] 换成 Int.MAX_VALUE 之后，第二趟永不点火，
     * 每一枚 (w, h) 的读数都等于改前那道两轴判据的读数（同式复算在 [legacySampleSize]）。
     */
    @Test
    fun turningTheCeilingOffRestoresTheLegacyLadderExactly() {
        for (cell in cells) {
            assertEquals(
                "${cell.label}：把上界关掉之后读数与改前不等 —— 第一趟被这次改动碰到了",
                legacySampleSize(cell.widthPx, cell.heightPx),
                wallpaperSampleSize(cell.widthPx, cell.heightPx, maxDecodedLongEdgePx = Int.MAX_VALUE),
            )
        }
        // 顺带钉住卡面点名那一格的改前读数：横图在上界关掉时确实一轮都不跑。
        assertEquals(1, wallpaperSampleSize(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, maxDecodedLongEdgePx = Int.MAX_VALUE))
    }

    /**
     * 上界那枚常数的两条边界（KDoc 的论证必须能在数上复算）：
     * - 不得低于竖图现有档的长边 2016，否则竖图被多推一档到 1008×756（清晰度当场倒退）；
     * - 必须低于 4032，否则卡面那格横图一点都没按住；
     * - 恰在上界（2160）不缩，刚过界（2161）缩一档 —— 判据是 `>` 而不是 `>=`。
     */
    @Test
    fun theCeilingConstantSitsBetweenTheTwoBoundsItClaims() {
        assertTrue(
            "上界 $CEILING 低于 2016 就会推翻竖图现有档（3024×4032 会被推到 4 ⇒ 1008×756）",
            CEILING >= 2016,
        )
        assertTrue("上界 $CEILING 不低于 4032 就按不住卡面那格横图", CEILING < LANDSCAPE_WIDTH)
        assertEquals(
            "上界取 2016 时竖图那一档必须原地不动（这是下界的意义）",
            2,
            wallpaperSampleSize(3024, 4032, maxDecodedLongEdgePx = 2016),
        )
        assertEquals(
            "上界取 2015 时竖图被推到 4 —— 倒退机制就长这样，所以常数不许低于 2016",
            4,
            wallpaperSampleSize(3024, 4032, maxDecodedLongEdgePx = 2015),
        )
        assertEquals(
            "上界取 4032 时横图一点没按住（档位仍是 1）",
            1,
            wallpaperSampleSize(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, maxDecodedLongEdgePx = 4032),
        )
        assertEquals("长边恰等于上界 $CEILING 不该缩（判据是 >）", 1, wallpaperSampleSize(CEILING, CEILING))
        assertEquals("长边刚过上界就该跳一档", 2, wallpaperSampleSize(CEILING + 1, CEILING + 1))
        assertEquals(
            "上界给成非正数＝第二趟不点火（横图退回第一趟的 1），绝不返回 0 或负数那种假档",
            1,
            wallpaperSampleSize(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, maxDecodedLongEdgePx = 0),
        )
    }

    /**
     * 代价格格（不许悄悄发生）：常见竖幅 1080×2400 会被多推一档，读数钉死在这里，
     * 有人把常数调大时这一格会第一个红。
     */
    @Test
    fun theTallPortraitCellIsPinnedBecauseItIsTheCost() {
        assertEquals(
            "1080×2400 的档位读数变了：$CEILING 这一档把它推到 2（540×1200）。" +
                "这一格是硬上界的**代价**，改常数的人要连这条一起重钉（KDoc 的代价栏有账）",
            2,
            wallpaperSampleSize(1080, 2400),
        )
        assertEquals(
            "目标档本身（1080×1920）不许被上界碰到：它整份解，正是默认路径的分辨率",
            1,
            wallpaperSampleSize(WALLPAPER_TARGET_WIDTH_FOR_TEST, WALLPAPER_TARGET_HEIGHT_FOR_TEST),
        )
    }

    /**
     * 终止性与脏边界：Int.MAX_VALUE 那一格必须返回一个有限的 2 的幂（不许死循环、不许返回负数），
     * 非正的边界尺寸不许抛 —— 调用点已经挡过 `outWidth <= 0`，纯判据函数这层再兜一次。
     */
    @Test
    fun garbageAndOverflowEdgesTerminateInsteadOfHangingOrThrowing() {
        val overflow = runCatching { wallpaperSampleSize(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertTrue("Int.MAX_VALUE 那一格抛了：${overflow.exceptionOrNull()}", overflow.isSuccess)
        val sample = overflow.getOrThrow()
        assertTrue("Int.MAX_VALUE 给出 $sample：不是正数（溢出的那一型）", sample > 0)
        assertTrue("Int.MAX_VALUE 给出 $sample：不是 2 的幂", sample and (sample - 1) == 0)
        assertTrue(
            "Int.MAX_VALUE 解出的长边 ${Int.MAX_VALUE / sample} 越过上界 $CEILING，封顶没成立",
            Int.MAX_VALUE / sample <= CEILING,
        )
        for (dirty in listOf(0 to 0, -1 to -1, 0 to LANDSCAPE_HEIGHT, LANDSCAPE_WIDTH to 0)) {
            val outcome = runCatching { wallpaperSampleSize(dirty.first, dirty.second) }
            assertTrue("${dirty.first}×${dirty.second} 这一格抛了：${outcome.exceptionOrNull()}", outcome.isSuccess)
            assertTrue("${dirty.first}×${dirty.second} 给出 ${outcome.getOrNull()}，不是正数", outcome.getOrThrow() > 0)
        }
    }

    // ---- 取值 --------------------------------------------------------------

    /** 表里那一格的调用形（参数名留着，读起来知道边界是宽还是高） */
    private fun Int.wallpaperSampleSizeFor(heightPx: Int): Int = wallpaperSampleSize(this, heightPx)

    /** ARGB_8888 = 4 B/px（本文件那两路解码的位图配置） */
    private fun bytesOf(widthPx: Int, heightPx: Int, sampleSize: Int): Long =
        (widthPx / sampleSize).toLong() * (heightPx / sampleSize).toLong() * BYTES_PER_PIXEL

    /**
     * 改前的两轴判据，同式复算（本卡**不动**第一趟，所以这一枚与生产里那一趟逐字同形）：
     * 它只要求宽对 [WALLPAPER_TARGET_WIDTH]、高对 [WALLPAPER_TARGET_HEIGHT] 同时达标。
     */
    private fun legacySampleSize(outWidthPx: Int, outHeightPx: Int): Int {
        var sampleSize = 1
        while (outWidthPx / (sampleSize * 2) >= WALLPAPER_TARGET_WIDTH_FOR_TEST &&
            outHeightPx / (sampleSize * 2) >= WALLPAPER_TARGET_HEIGHT_FOR_TEST
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val CEILING = WALLPAPER_MAX_DECODED_LONG_EDGE_PX
        const val BYTES_PER_PIXEL = 4L

        /** 本文件那两枚目标常数（测试里不写第二份字面量） */
        const val WALLPAPER_TARGET_WIDTH_FOR_TEST = 1080
        const val WALLPAPER_TARGET_HEIGHT_FOR_TEST = 1920

        /** 卡面点名的横图（4032×3024） */
        const val LANDSCAPE_WIDTH = 4032
        const val LANDSCAPE_HEIGHT = 3024

        /**
         * 表：卡面点名的四格 + 上界两端 + 代价格 + 假判据靶子 + 第一趟已给档再被上界推一档 +
         * 非正边界 + Int.MAX_VALUE。字节账按 ARGB_8888 逐格算死（不是区间）。
         */
        val cells = listOf(
            Cell("卡面横图 4032×3024（改前整份解＝46.5 MiB）", LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, 2, 2016L * 1512L, 12_192_768L),
            Cell("卡面竖图 3024×4032（改前后同档）", LANDSCAPE_HEIGHT, LANDSCAPE_WIDTH, 2, 1512L * 2016L, 12_192_768L),
            Cell("边界形 2159×3839（短边 1079 差一枚就低于 1080）", 2159, 3839, 2, 1079L * 1919L, 8_282_404L),
            Cell("小图 800×600（不该被碰到）", 800, 600, 1, 800L * 600L, 1_920_000L),
            Cell("恰在上界 2160×2160（判据是 >）", CEILING, CEILING, 1, 2160L * 2160L, 18_662_400L),
            Cell("刚过界 2161×2161", CEILING + 1, CEILING + 1, 2, 1080L * 1080L, 4_665_600L),
            Cell("假判据靶子 6000×2400（3 是假的，正确档 4）", 6000, 2400, 4, 1500L * 600L, 3_600_000L),
            Cell("第一趟给 2、上界再推一档 8000×6000", 8000, 6000, 4, 2000L * 1500L, 12_000_000L),
            Cell("代价格：常见竖幅 1080×2400", 1080, 2400, 2, 540L * 1200L, 2_592_000L),
            Cell("目标档 1080×1920（默认路径同分辨率，原地不动）", WALLPAPER_TARGET_WIDTH_FOR_TEST, WALLPAPER_TARGET_HEIGHT_FOR_TEST, 1, 1080L * 1920L, 8_294_400L),
            Cell("脏边界 0×0（调用点已挡，纯判据再兜）", 0, 0, 1, 0L, 0L),
            Cell("溢出档 Int.MAX_VALUE（只断终止，长边 2047 落在上界内）", Int.MAX_VALUE, Int.MAX_VALUE, 1048576, 2047L * 2047L, 16_760_836L),
        )
    }

    /** 表里一格：边界尺寸 → 期望档位 / 期望像素数 / 期望字节账 */
    private class Cell(
        val label: String,
        val widthPx: Int,
        val heightPx: Int,
        val expectedSampleSize: Int,
        val expectedPixels: Long,
        val expectedBytes: Long,
    )
}
