package com.buaa.schedule.data.import

import android.content.Context
import android.util.Log
import com.buaa.schedule.data.local.IClassIdStore

/**
 * 北航 iClass（竞业达「轻新课堂」）登录会话（应用级单例）。
 *
 * 形状是 `init` / `hasSession` / `authorized` / `clear` 四颗 —— T84 立这一族时照着当时那族
 * （智学北航 SPOC）的形状对齐，为的是 T85 拆它时**只换调用点的一句判断**、不再写一枚新单例。
 * 那一天真的来了，四颗一颗没动：课前提醒那道闸门（[com.buaa.schedule.reminder.ReminderReceiver]）
 * 与首页那条扫码入口现在问的都是这一颗 [hasSession]。
 *
 * ⚠️ **这里没有会过期的东西**：SPOC 的鉴权材料是一枚带 `exp` 的 JWT，所以要预留续期、要解析 `exp`；
 * iClass 的签到请求只带一枚 `id`（`GET …原文…&id=<User.id>`），
 * 所以 [authorized] 就是"盘上有就给你"，服务端认不认由那一次请求自己判
 * （判不出来的时候界面上读到的是服务端的 ERRMSG 原文，见 [IClassApi]）。
 *
 * ⚠️ 只存 `id`，**绝不存口令**：`id ≠ 学号`（老客户端的 `User` 里 `id` 与 `studentNo`
 * 是两条独立字段，签到只取前者），而登录要的那对 phone/password 只在 [login] 这一趟
 * 内存里存在过，成功之后落盘的只有服务端回的那枚 id。
 */
object IClassSession {

    private const val TAG = "IClassSession"

    private val api = IClassApi()

    @Volatile private var appContext: Context? = null

    @Volatile private var cached: String? = null

    /** 区分「盘上确实没有」与「还没读盘」，避免每次取值都解一次密文 */
    @Volatile private var loadedFromDisk = false

    /** 任意线程调一次，只寄存 Context（读盘是懒的，冷启动不为此解一次密文） */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    /** 是否已经有登录材料（iClass 这一族没有"过期"这回事，有就能用） */
    fun hasSession(): Boolean = userId() != null

    /** 冷启动后从盘上读一次；不碰 WebView，可在任意线程调用 */
    fun userId(): String? {
        if (loadedFromDisk) return cached
        val context = appContext ?: return null
        // ⚠️ 顺序不能反：两个 @Volatile 之间没有互斥，
        // 先发布 loadedFromDisk 再算 cached 的话，并发读到的就是"已加载 + null"，
        // 表现是随机"未登录"。
        val loaded = IClassIdStore.load(context)?.trim()?.takeIf { it.isNotEmpty() }
        cached = loaded
        loadedFromDisk = true
        return loaded
    }

    /**
     * 取一个可以直接发签到请求的 `id`。
     *
     * @return null 表示界面该把用户送去登录页 —— 这一族没有可静默续期的东西，
     * 所以"没有会话"与"会话坏了"是同一个出口，不给两套文案。
     */
    suspend fun authorized(): String? = userId()

    /** 登录并落盘：成功返回服务端给的 `id`，失败原样抛出 [IClassApi] 的异常（消息是 ERRMSG 原文） */
    suspend fun login(context: Context, phone: String, password: String): Result<String> =
        api.login(phone, password).onSuccess { id -> save(context, id) }

    /** 登录成功后调用：内存与磁盘同时更新 */
    fun save(context: Context, id: String) {
        val value = id.trim()
        if (value.isEmpty()) return
        appContext = context.applicationContext
        cached = value
        loadedFromDisk = true
        IClassIdStore.save(context, value)
        Log.i(TAG, "iClass 会话已保存（只存 id，口令未落盘）")
    }

    /** 退出 iClass 登录：只清这一族的 id，教务 Cookie 一概不动 */
    fun clear() {
        cached = null
        loadedFromDisk = true
        appContext?.let { IClassIdStore.clear(it) }
        Log.i(TAG, "iClass 会话已清除")
    }
}
