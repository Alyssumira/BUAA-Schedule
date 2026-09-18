# 北航教务接口参考

> 从 `README.md` 挪出来的一节：只有要改导入链路或签到链路的人才需要，装 App 的人用不到。
> 结论都来自真实返回 JSON 与真机抓取，接口哪天变了先复核这一页。

## 已确认接口

- 登录：`https://sso.buaa.edu.cn/login`
- 学期周次：`/jwapp/sys/homeapp/api/home/getTermWeeks.do`
  - 参数：`termCode=2026-2027-1`
  - 返回：`datas[]`，含 `startDate`、`serialNumber`、`curWeek`
- 课表：`/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do`
  - 参数：`termCode`、`campusCode`、`type=week`、`week=1`（也可 `type=term` 拉整学期）
- 已用真实返回 JSON 验证解析器：`arrangedList` / `cellDetail` / `titleDetail` / `weeksAndTeachers` / `getTermWeeks`

## 两条链路，别混用

| 链路 | 方法 | 位置 | 说明 |
| --- | --- | --- | --- |
| 页面内抓取（**生产在用**） | `GET` + query string | `BuaaInPageFetcher.kt` | 走 byxt WebView 页面内 `fetch`，凭证随页面 Cookie 自动带上 |
| 原生网络栈（仅 debug 的 Cookie 粘贴入口） | `POST` + form | `BuaaApi.kt` | 复刻不出页面上下文凭证，正式登录流程不用它 |

`type=term`（整学期一次拉取）已由 `BuaaScheduleParserTest` / `SemesterCoursesCompletenessTest`
覆盖验证可用；生产仍按周（`type=week`）循环抓取，以兼容部分课程周次不均匀的排课。

原生网络栈之所以必 401：统一身份认证发的是页面上下文里的会话凭证，脱离 WebView 复刻不出同一套
Cookie / 跳转链，所以正式导入只在 byxt 页面里发请求。

## 智学北航（SPOC）签到链路

与上面是**两套会话**：域名不同、鉴权机制不同，结论不许互相外推。唯一同源的地方是登录前端
——两边都跳 `sso.buaa.edu.cn/login`（见下），省得下输一次密码，但省不下换凭证那一步。
取证来自线上 H5 主包与签到页 chunk，细节与偏差记在 `docs/BUAA_SPOC_SIGNIN_PLAN.md`。

> 证据等级与教务那一节不同：教务的解析器已用真实返回 JSON 验证；SPOC 这一侧的接口、
> 请求头与响应形态全部来自 H5 源码，**尚未真机跑通**，唯一没取证到的环节是老师端
> 二维码的字面内容（生成逻辑在 APP 原生侧，H5 里没有）。

- Base：`https://spoc.buaa.edu.cn/spocnewht/`，H5 前端在 `/bhspoc/`
- 登录入口：`spocnewht/casmobile`，2026-09-18 无凭证实测 `302` 到
  `https://sso.buaa.edu.cn/login?service=…/spocnewht/mobilecasLogin` —— 与教务导入跳的是
  **同一台 SSO、同一个 `/login`**，只有 `service` 不同。App 里 WebView 共用进程级
  `CookieManager`（`BuaaWebSession` 持久化的两个域之一就是 `sso.buaa.edu.cn`），所以已登录
  教务时这条应当免输密码直接回跳；但 byxt 那条「SSO 自动跳过登录表单」是真机实测，
  SPOC 这条 `service` 还没验过，别当既成事实写进界面文案
- 完成后回到 `/bhspoc/` 的 hash 路由，token 由页面自己落进 `localStorage`
- 鉴权：**请求头** `token: "Inco-<JWT>"` + `rolecode`，不是 Cookie。所以原生
  `HttpURLConnection` 就是生产路径（`SpocApi.kt`），上面那条"必 401"不适用于这边
- 续期：`sys/refreshToken`，body `{refreshToken}`，请求本身不带鉴权头；
  返回的 token 统一补成带 `Inco-` 前缀的完整值，调用方直接进请求头
- 过期判定走 JWT 的 `exp`（Base64 解 payload，不引库），临期 10 分钟内提前续

四条业务接口（`POST` + JSON body）：

| 路径 | body | 说明 |
| --- | --- | --- |
| `spocxssk/queryQdxxByQdid` | `{qdid}` | 按签到 ID 查详情；已签过则带 `QDSJ`。`qdid` 就是活动的 `CJID`，不是独立 ID |
| `spocxssk/queryQdhdListByZjdm` | `{zjdm}` | 按教师班 ID 列活动 |
| `spocxssk/queryQdhdByHdid` | `{hdid}` | 按课堂活动 ID 取 `{ZJDM, CZID}` |
| `spocxssk/saveXsqd` | `{zjdm, czid, xh}` | 提交签到，`xh`（学号）必填；他班的码由服务端拒绝，本地不判断 |

响应外壳统一是 `{code: String, msg, content}`，页面口径 `content || 整个对象`：

- **`content` 可能是对象，也可能是数组** —— 列表接口直接返回数组（页面里做的是
  `listData.concat(i)`，没有再取键）。所以解壳返回 `JsonElement`，再由
  `expectObject()` / `expectList()` 校验形态；形态不符**抛错**，不静默当空列表，
  否则「接口改版」会被伪装成「这节课没有签到活动」。
- 字段大小写不固定（`ZJDM` / `zjdm` 都出现过），统一走 `SpocApi.pick/string` 的不敏感查找。
- WebSocket 的 `xsqdcg` 推送是发给老师端界面加速的广播，服务端落库不依赖它，客户端不复刻。
