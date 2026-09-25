package com.buaa.schedule.data.local

/**
 * 北航 iClass（轻新课堂）签到凭据的加密落盘。
 *
 * 载荷只有一枚 **`User.id`**（登录响应里的 `result.id`，签到时以 `&id=` 参数提交）。
 * 刻意**不存口令**：老客户端把 phone + password 明文写进 SharedPreferences、每次进页面
 * 重新登一遍，那是这条链上最坏的一笔账 —— 口令能过学校的 `ve` 网关，也就是能登统一身份认证，
 * 明文躺在可读写的目录里等于把账号交给任何一个拿到这台设备的人。
 * 我们只要那枚 id：签到请求不需要口令，也没有 token 会过期。
 *
 * 加密与存储格式与 [BuaaCookieStore] / [SpocTokenStore] 同一套实现（[KeystoreBlobStore]），
 * 但**单开一份 prefs 与密钥 alias**：三条链路互不牵连 —— 清掉教务 Cookie
 * 不该把 SPOC 的登录带掉，反之 iClass 这一枚也不该。
 */
internal val IClassIdStore = KeystoreBlobStore(
    prefsName = "iclass_id_store",
    blobKey = "credential",
    keyAlias = "iclass_id_store_key",
)
