package com.buaa.schedule.data.local

/**
 * 教务会话 Cookie 的加密落盘。
 *
 * **为什么需要它**：WebView 的 `CookieManager` 只保证"进程存活期间"的会话。
 * Cookie 如果没有 `Max-Age` / `Expires`（SSO 的会话 Cookie 通常就是这种），
 * 它不会被写进磁盘 —— 用户从最近任务里划掉应用、或者被系统回收进程之后，
 * 再打开就只剩一个非会话 Cookie，访问 byxt 会被重定向回统一身份认证登录页，
 * 表现为「退出应用后登录状态还是没了」。
 *
 * 因此这里把 byxt / sso 两个域的 Cookie 单独加密存一份，冷启动时再注回 CookieManager。
 *
 * 加密与存储格式见 [KeystoreBlobStore]（AES-256/GCM，`Base64(iv):Base64(密文)`）。
 * prefs 名与密钥 alias 都写死在这里：改动等于让已落盘的凭据全部解不开。
 */
internal val BuaaCookieStore = KeystoreBlobStore(
    prefsName = "buaa_cookie_store",
    blobKey = "cookies",
    keyAlias = "buaa_cookie_store_key",
)
