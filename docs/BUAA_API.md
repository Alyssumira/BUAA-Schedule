# 北航教务接口参考

> 从 `README.md` 挪出来的一节：只有要改导入链路或签到链路的人才需要，装 App 的人用不到。
> 结论都来自真实返回 JSON 与真机抓取，接口哪天变了先复核这一页。

## 已确认接口

> 生产在用的核心几条列在下面；homeapp 全部 69 个 `.do` 的实测状态见后文
> 「homeapp 全接口清单（2026-09-22 登录态实扫）」。

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

## homeapp 全接口清单（2026-09-22 登录态实扫）

前端包（`/jwapp/sys/homeapp/home/umi.9f77300f.js`）内嵌 69 个 `.do`，下面按实测状态分类。
除注明外全部为 `GET`、base 省略 `/jwapp/sys/homeapp/api/home/` 前缀，凭证走页面 Cookie。
状态口径：✅=实抓到数据；⭕=接口通但返回空（该账号真实如此，不是接口坏）；❌=服务端"系统异常"
（缺参/该部署未启用）；🚫=404；✍️=写操作或登录辅助，未动。

### 课表核心（生产在用）

| 接口 | 参数 | 状态 | 能拿到的东西 |
| --- | --- | --- | --- |
| `student/getMyScheduleDetail.do` | `termCode` `campusCode` `type=week\|term` `week` | ✅ | `arrangedList[]` 每门课：课程号/名/课序号/学分、起止节次+真实时间、`dayOfWeek`、`weeksAndTeachers`、`teachClassId`、`teachingTarget`、`placeName`、`titleDetail`（校区/楼/教室全路径）、`cellDetail`、`color`；另有 `notArrangeList`（未排课）、`practiceList`（实践课）。页面自己发的是 POST form，GET 同样通 |
| `getTermWeeks.do` | `termCode` | ✅ | 20 周：`startDate/endDate/serialNumber/curWeek` |
| `student/getSections.do` | `termCode` `campusCode` | ✅ | 14 个节次；**`campusCode` 传空时 `startTime/endTime` 全空**，要真实校区码 |
| `kb/xnxq.do` | — | ✅ | 全部可选学期 47 个（2009 秋→2028 夏） |
| `scheduleTip.do` | `termCode` | ✅ | 今日有课提示：`sfhj/tip/today/week0/week1` |
| `getMyScheduleConfig.do` | — | 🚫 | code=404，该部署未启用 |

### 今日日程（teachingSchedule 三件套，比拉整周课表轻）

| 接口 | 参数 | 状态 | 能拿到的东西 |
| --- | --- | --- | --- |
| `teachingSchedule/list.do` | `rq=yyyy-MM-dd` `lxdm=student` | ✅ | 日程分类（上课课程/校历…）+ `days[]` + 启用开关 `sfqy` |
| `teachingSchedule/detail.do` | 同上 | ✅ | 当日逐条：`bizKey` `bizName` `place` `time`（如 09:50-11:25） |
| `teachingSchedule/classWeek.do` | `rq` | ✅ | 任意日期的 `classWeek` |
| `teachingSchedule/display_range.do` | — | ✅ | 可查日期范围 2016-08-29～2028-01-09 |
| `teachingSchedule/schoolCalendar.do` | — | ✅ | 只回一个 `fileToken`，校历图要再 POST `sys/emapcomponent/file/getUploadedAttachment/<token>.do` 取 |

### 学生数据

| 接口 | 参数 | 状态 | 能拿到的东西 |
| --- | --- | --- | --- |
| `currentUser.do` | — | ✅ | 学号、姓名、角色（本科生）、`userType`、当前学期+周次 |
| `student/courses.do` | `termCode` | ⭕ | 本学期课程列表（新生第一学期为空） |
| `student/scores.do` | `termCode` | ⭕ | 成绩（空为真实） |
| `student/exams.do` | `termCode` | ⭕ | 考试安排（空为真实） |
| `student/schoolCalendars.do` | — | ✅ | 可选学期 3 个 |
| `student/educational-program.do` | `termCode` | ✅ | 培养方案：`planId/planName/totalCredit/alreadyGainCredit/major` |
| `student/config.do` | — | ✅ | 功能开关 + **`careerConfig.careerId`**（下面两个接口要用） |
| `student/academic-career-term.do` | `termCode` `termIndex` `careerId` | ✅ | 学业生涯按学期分组，每学期挂 5 个阶段 |
| `student/academic-career-matter.do` | 同上 | ✅ | 阶段明细：`fullName/shortName/description/relateApp/times` |
| `student/applys.do` | `termCode` | ⭕ | 申请事项（datas=null） |
| `student/checkuser.do` | — | ✅ | ⚠️ **返回注册手机号 `sjh` 和验证状态**，纯登录辅助，别往 App 里带 |
| `student/academic-status.do`（+2 子接口）、`academic-career-navigation.do` | — | ❌ | 服务端异常；`config.do` 里 `academicStatusEnable:false`，未启用 |
| `student/sendVerifyCode.do` / `checkVerifyCode.do` | — | ✍️ | 短信验证辅助，未测 |

### 门户/杂项

| 接口 | 参数 | 状态 | 能拿到的东西 |
| --- | --- | --- | --- |
| `menus.do` | `userType=student` | ✅ | **全系统地图**：9 大区 30+ 子模块 URL（见下节） |
| `others/myBusiness.do` | — | ✅ | 17 个常用业务 + 各自 pages |
| `announcement.do` | `userType=student` | ✅ | 公告列表 + `unreadCount` |
| `messages_pc.do` | `userType=student` | ⭕ | 站内消息（空） |
| `ggxxpz.do` | `userType=student` | ✅ | 公告/个人信息/系统消息三个开关 |
| `commonLink/queryCommonLink.do` | `USERTYPE=student`（大写） | ⭕ | 常用链接（空数组） |
| `config/global.do` | — | ✅ | `firstDayOfWeek`、`byFlag` 等全局显示配置 |
| `config/yssz/config.do` | — | ✅ | 门户皮肤/页脚/欢迎语配置（含图片 token） |
| `logout_url.do` | — | ✅ | 登出跳转 `/sys/yjsrzfwapp/logout/forceLogoutBy.do` |
| `kb/bynj.do` | — | ✅ | 毕业年级码表 11 项；`kb/kkdw.do` ⭕ 空 |
| `myApps.do`、`myDashboardSettings.do`、`kb.do`、`confirmSchedule.do`、`scheduleConfirmationSetting.do`、`homeSchoolCalendar.do`、`artificialMessage.do`、`myTodo.do`、`todo/pendingTasks.do`、`todo/completedTasks.do`、`tjyy.do`、`ywjz.do`、`config/wdjxkb`、`kb/bkpc`、`kb/xspjwj` | 各种 | ❌ | 一律"系统异常"，GET/POST 都试过——该部署未启用或参数无法从前端包还原，别再浪费时间 |
| `todo/apps.do` | — | ⭕ | code=0 空数组 |
| `saveUserSetting.do`、`changeAppRole.do`、`todo/generate_tododetails.do`、其余 `kb/*` 码表 | — | ✍️ | 写操作/未测 |
| `teacher/*` 8 个 | — | — | 学生角色不适用 |

### 课表 Excel 导出（FineReport，实测可用）

`GET /jwapp/sys/frReport2/show.do?reportlet=homeapp%2Fxskb_buaa.cpt&format=excel&xnxqdm=<termCode>&campusCode=`
→ 200，`application/x-excel`，整学期课表 xlsx（实测 6.7KB）。前端包里的导出按钮走的就是它
（参数名是 `xnxqdm` 不是 `termCode`）。可作为 JSON 链路坏掉时的兜底数据源候选。

### menus.do 里的子模块（各自是独立子应用，未逐个实扫）

`/sys/` 前缀、`*default/index.do#/<页>` 形式：xsjbxxgl 学生基本信息（含学生证申请）、xjxxbg 信息变更、
xjydyy 学籍异动、syxkapp 实验选课、mtxkbl 免听免修、kbapp 我的课表、wdkwapp 我的考试、hksq 缓考、
cjzhcxapp 成绩综合查询、jljhglbuaa 交流交换、kctdjrdapp 课程认定、xyjctjapp 个人学业进度、
xyyj 学业预警、xxzxapp 通知公告、xsfacx 我的培养方案、qxfacx 全校方案查询、kxjas 空闲教室查询、
jsjy 教室借用、syjxapp 实验报告、cxcyxljhgl 大创、jsxmjlgl 竞赛、zyxzxtbuaa 专业选择志愿、
kcdgglbuaa 教学大纲。外链：byxk.buaa.edu.cn 学生选课、bykc.buaa.edu.cn 博雅、CNKI 毕设/实习。

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
