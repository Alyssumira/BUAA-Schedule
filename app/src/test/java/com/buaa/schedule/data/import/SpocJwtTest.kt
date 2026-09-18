package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * JWT 的 `exp` 读取 —— 只用来决定「要不要提前续期」，不验签。
 *
 * 钉住：不带填充的 base64url 段必须能解（JWT 规范就是不填充的），
 * 以及任何结构异常都**只返回 null**、不抛：解不出寿命的代价是不提前续期，
 * 而不是把签到这条路堵死。
 */
class SpocJwtTest {

    /** 用标准 base64url 编码器造 payload，再手动去掉填充，模拟真实 JWT 的写法 */
    private fun jwt(vararg claims: Pair<String, Long>): String {
        val payload = claims.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "eyJhbGciOiJIUzI1NiJ9.$encoded.sIgNaTuRl"
    }

    @Test
    fun `读出 exp 并换算成毫秒`() {
        val token = jwt("exp" to 1_800_000_000L)
        assertEquals(1_800_000_000_000L, SpocSession.jwtExpiresAtMillis(token))
    }

    @Test
    fun `带 Inco- 前缀同样能读`() {
        val token = jwt("exp" to 1_800_000_000L)
        assertEquals(
            SpocSession.jwtExpiresAtMillis(token),
            SpocSession.jwtExpiresAtMillis("Inco-$token"),
        )
    }

    @Test
    fun `payload 里带非 ASCII 也不影响`() {
        // 真实 JWT 的 payload 常含中文姓名，base64url 解出来必须是原样的 UTF-8
        val payload = """{"name":"张三","exp":1800000000}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        assertEquals(1_800_000_000_000L, SpocSession.jwtExpiresAtMillis("a.$encoded.b"))
    }

    @Test
    fun `结构异常一律返回空而不抛`() {
        assertNull(SpocSession.jwtExpiresAtMillis(jwt("iat" to 1L)))           // 没有 exp
        assertNull(SpocSession.jwtExpiresAtMillis("a.b"))                       // 只有两段
        assertNull(SpocSession.jwtExpiresAtMillis(""))                          // 空串
        assertNull(SpocSession.jwtExpiresAtMillis("Inco-not.a.jwt!!"))          // 段不是 base64
        assertNull(SpocSession.jwtExpiresAtMillis("Inco-eyJhbGciOiJIUzI1NiJ9.#{not json}.sig"))
    }
}
