package com.buaa.schedule.update

import com.buaa.schedule.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布号与自更新之间的关系。
 *
 * 发版走 `release.ps1`，它把 `gradle.properties` 的 `VERSION_NAME` 写进包里、再按同一个号
 * 打 Gitee tag。这个用例钉的是两端对上之后剩下的那段契约：
 * tag 的写法（`v` + 三段数字）必须能被解析回包内版本号，而且**恰好等于当前版本的 tag
 * 不能被当成更新** —— 一旦把它当成更新，用户装完号不变、下次冷启动再弹一遍，
 * 更新通道从此只会制造噪音，而这正是唯一还能修好其他 bug 的通道。
 */
class ReleaseVersionContractTest {

    private fun releaseAt(tag: String) = GiteeRelease(
        tagName = tag,
        name = tag,
        body = null,
        prerelease = false,
        assets = listOf(
            GiteeAsset(
                name = "buaa-schedule-$tag.apk",
                browserDownloadUrl = "https://gitee.com/alyssumira/buaa-schedule/releases/download/$tag/buaa-schedule.apk",
            ),
        ),
    )

    @Test
    fun versionNameIsThreeNumericSegments() {
        assertTrue(
            "VERSION_NAME=${BuildConfig.VERSION_NAME} 不是三段数字：Gitee 的 tag 是 v+它，" +
                "段数一错，逐段比较就会把新版本判成旧版本",
            BuildConfig.VERSION_NAME.matches(Regex("""\d+\.\d+\.\d+""")),
        )
    }

    @Test
    fun versionCodeLeavesRoomForReinstallOverExistingBuilds() {
        // 1 是从脚手架带出来的号；>=2 才是"发过的号"，同 versionCode 的覆盖安装不算升级
        assertTrue("VERSION_CODE=${BuildConfig.VERSION_CODE} 低得可疑", BuildConfig.VERSION_CODE >= 2)
    }

    @Test
    fun tagNamingConventionRoundTripsToPackageVersion() {
        assertEquals(BuildConfig.VERSION_NAME, normalizeVersion("v${BuildConfig.VERSION_NAME}"))
        assertEquals(
            "$RELEASES_PAGE_URL/tag/v${BuildConfig.VERSION_NAME}",
            releasePageUrl("v${BuildConfig.VERSION_NAME}"),
        )
    }

    @Test
    fun aTagEqualToTheInstalledVersionIsNeverOffered() {
        val tag = "v${BuildConfig.VERSION_NAME}"
        assertNull(
            "重号 tag 被当成更新 = 装完号不变、下次冷启动再弹一遍",
            pickLatestUpdate(listOf(releaseAt(tag)), BuildConfig.VERSION_NAME, null),
        )
    }

    @Test
    fun aTagAboveTheInstalledVersionIsOffered() {
        val parts = BuildConfig.VERSION_NAME.split('.').map { it.toInt() }
        val nextTag = "v${parts[0]}.${parts[1]}.${parts[2] + 1}"
        val picked = pickLatestUpdate(listOf(releaseAt(nextTag)), BuildConfig.VERSION_NAME, null)
        assertEquals(nextTag.removePrefix("v"), picked?.version)
    }
}
