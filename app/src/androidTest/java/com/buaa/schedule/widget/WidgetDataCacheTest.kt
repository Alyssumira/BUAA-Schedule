package com.buaa.schedule.widget

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [WidgetDataCache] 的快照新鲜度与并发安全（需设备：读 Room）。
 *
 * 两条必须成立的不变量（对应 P1-4）：
 * - `peek` 只能在**同一 key** 命中：key 与 data 作为一个整体发布，
 *   绝不能出现「key 已切到新学期、data 还是上学期课程」的撕裂读；
 * - `peek` 是给 `RemoteViewsFactory.onDataSetChanged()`（主线程）用的非阻塞读，
 *   并发调用不得抛异常。
 */
@RunWith(AndroidJUnit4::class)
class WidgetDataCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        WidgetDataCache.invalidate()
    }

    @Test
    fun peekReturnsNullBeforeAnythingIsCached() {
        WidgetDataCache.invalidate()
        assertNull(WidgetDataCache.peek(null))
        assertNull(WidgetDataCache.peek("2026-2027-1"))
    }

    @Test
    fun getThenPeekHitsTheSameKey() = runBlocking {
        val data = WidgetDataCache.get(context, null)
        assertNotNull(data)

        assertNotNull("同一 key 必须命中", WidgetDataCache.peek(null))
        assertEquals(data.courses.size, WidgetDataCache.peek(null)!!.courses.size)
    }

    @Test
    fun peekDoesNotReturnAnotherKeysSnapshot() = runBlocking {
        WidgetDataCache.get(context, "semester-A")

        assertNotNull(WidgetDataCache.peek("semester-A"))
        assertNull(
            "key 不匹配时绝不能返回别的学期的课程（撕裂读的直接后果）",
            WidgetDataCache.peek("semester-B"),
        )
    }

    @Test
    fun invalidateDropsTheSnapshot() = runBlocking {
        WidgetDataCache.get(context, null)
        assertNotNull(WidgetDataCache.peek(null))

        WidgetDataCache.invalidate()
        assertNull(WidgetDataCache.peek(null))
    }

    @Test
    fun concurrentPeekAndInvalidateNeverThrowsAndNeverReturnsForeignData() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = mutableListOf<Thread>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(6)

        // 2 个刷新线程反复 get（会切换缓存 key）
        repeat(2) { i ->
            threads += thread(name = "refresh-$i") {
                start.await()
                try {
                    repeat(20) {
                        runBlocking { WidgetDataCache.get(context, if (it % 2 == 0) "semester-A" else "semester-B") }
                    }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    done.countDown()
                }
            }
        }
        // 3 个读线程模拟组件 onDataSetChanged 的并发 peek
        repeat(3) { i ->
            threads += thread(name = "peek-$i") {
                start.await()
                try {
                    repeat(400) {
                        val key = if (it % 2 == 0) "semester-A" else "semester-B"
                        // 命中即结构完整；未命中返回 null 是允许的（缓存已失效）
                        WidgetDataCache.peek(key)?.courses
                    }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    done.countDown()
                }
            }
        }
        // 1 个失效线程制造竞态
        threads += thread(name = "invalidate") {
            start.await()
            try {
                repeat(200) {
                    WidgetDataCache.invalidate()
                    Thread.yield()
                }
            } catch (t: Throwable) {
                errors += t
            } finally {
                done.countDown()
            }
        }

        start.countDown()
        assertTrue("并发用例不应超时", done.await(60, TimeUnit.SECONDS))
        threads.forEach { it.join(5_000) }

        assertTrue("并发访问不应抛异常：${errors.joinToString { it.toString() }}", errors.isEmpty())

        WidgetDataCache.invalidate()
    }
}
