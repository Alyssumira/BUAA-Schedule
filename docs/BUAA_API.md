# 北航教务接口参考

> 从 `README.md` 挪出来的一节：只有要改导入链路的人才需要，装 App 的人用不到。
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
