package com.buaa.schedule.data.local

/**
 * 智学北航（SPOC）鉴权材料的加密落盘。
 *
 * **为什么不放进 WebView**：SPOC 的接口鉴权走请求头 `token: "Inco-"+JWT`，
 * 不是 Cookie（取证见 docs/BUAA_SPOC_SIGNIN_PLAN.md §0）。拿到 token 之后
 * 签到提交是纯 HTTP，不需要保留任何 WebView 实例 —— 因此这里存的是一小段
 * JSON，而不是 [BuaaCookieStore] 那种「Cookie 头 + 注回 CookieManager」的形态。
 *
 * 但**必须**落盘：token 存在 H5 的 localStorage 里，WebView 实例一销毁就没了，
 * 而课前签到提醒要在**没有界面**的广播进程里发起 HTTP 请求。
 *
 * 加密与存储格式跟 [BuaaCookieStore] 是同一套实现（[KeystoreBlobStore]），
 * 只是单开一份 prefs 与密钥 alias，避免两条链路互相牵连：
 * 教务那边清 Cookie 不应该把 SPOC 的登录也带掉，反之亦然。
 */
internal val SpocTokenStore = KeystoreBlobStore(
    prefsName = "spoc_token_store",
    blobKey = "credential",
    keyAlias = "spoc_token_store_key",
)
