package com.buaa.schedule.ui

/**
 * 「撤销写库失败了：这条撤销记录该怎么办、那句提示该说什么」的判据内核（T183）。
 *
 * 纯判据：**零 android import**、零时钟读取、不碰 ViewModel 也不碰数据库（本仓口径，
 * 同 `ui/UndoUpdateAdmission.kt`、`data/repository/CourseDeletionPolicy.kt` 那一族）。
 * 运行期事实一律由 `ScheduleViewModel.undo()` 量好了当参数递进来：
 * - 异常链上的**类名**（本文件不 import `android.database.*`，所以按 FQN 查表，见 [exceptionClassNamesOf]），
 * - 「这一条到底回推成功了没」由 `UndoManager.restoreTop` 的返回值说（栈自己的票据闸门不归本文件判），
 * - 「库那一手落没落成」由调用点那枚局部旗标说：`afterDataChangedInternal()` 那一拍抛的错**不**回推，
 *   撤销条目不是幂等的（Delete 的撤销是 insert，点两回就多一门课）。
 *
 * ## 病
 *
 * `undo()` 在**落库之前**就 `UndoManager.pop()` 消费掉条目，失败那一支只念一句「撤销失败：…」。
 * DB 回滚、快照却已经从栈里摘走，而进程内那枚栈是它的唯一副本 ⇒ 那条撤销**再也点不回来**。
 * 修法是失败时把条目放回栈顶（`UndoManager.restoreTop`），但**放回哪一档、怎么说**得按原因分：
 *
 * - 库被锁 / 正忙（[UndoFailureKind.WriteLockBusy]）：环境没变、同一条链再跑一遍就成了 ⇒ 回推 + 邀请再点。
 * - 环境写不下（[UndoFailureKind.StorageUnavailable]：磁盘满、I/O 失败、堆不够）：条目要留着
 *   （腾出空间后还得点得回来），但**不许**把「再点一次」写成承诺 —— 环境不变它永远失败。
 * - 快照本身写不进去（[UndoFailureKind.RejectedBySnapshot]：撞约束、参数越界、我们自己的 bug）：
 *   重试是同一个结果 ⇒ **不回推**。这一档不是省内存：栈只有 pop 栈顶一条出路，
 *   一枚永远失败的条目躺在顶上 = 压在它下面那些条目**全都永远撤不回来**，丢一条比钉死整栈便宜。
 * - 认不出原因（[UndoFailureKind.Unexplained]，含泛化的 `SQLException` / `SQLiteException` 这些
 *   表里没点名的类）：留着条目（这一族不留下的代价是「再也点不回来」，值得保底），但不许诺重试。
 *
 * 残余（本卡按红线没做，登记在册）：**没装机证据** —— 真实 `SQLiteBusyException` / 磁盘满那一档
 * 只有 JVM 侧的类名查表证据，异常是否真的按这些类抛出、提示条在屏上念成什么样，都要设备那一趟。
 */
internal enum class UndoFailureKind(
    /** 这条撤销记录该不该放回栈顶（放回 = 用户还点得回来） */
    val restoreEntry: Boolean,
    /** 那句提示许不许邀请「可以再点一次」：只有原因本身可恢复才允许 */
    val retryInvitation: Boolean,
    /** 条目真的回推成功时补的那半句；不回推的那一档留空（没有「还留着」可说） */
    val heldSuffix: String,
) {
    WriteLockBusy(
        restoreEntry = true,
        retryInvitation = true,
        heldSuffix = "；这条撤销还留着，可以再点一次",
    ),
    StorageUnavailable(
        restoreEntry = true,
        retryInvitation = false,
        heldSuffix = "；这条撤销还留着，腾出能写的空间后再点一次",
    ),
    RejectedBySnapshot(
        restoreEntry = false,
        retryInvitation = false,
        heldSuffix = "",
    ),
    Unexplained(
        restoreEntry = true,
        retryInvitation = false,
        heldSuffix = "；这条撤销还留着，但失败的原因没认出来",
    ),
    ;

    /**
     * 失败那句本体：值 = f(同表参数) ⇒ 按本仓口径长在**类体**里（与
     * `domain/schedule/ImportWriteOutcome.writtenCount` 同一格落点，不给它在调用点二次拼装的机会）。
     *
     * 前缀「撤销失败：」与 `undo()` 里回推**没成**那一支的旧句逐字同形 —— 那一支不宣称条目留着，
     * 所以不必改口；改口的只有"真的留下来了"这一支。
     */
    fun copyForRestored(reason: String?): String {
        val cause = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "数据库没有接受这次撤销"
        return "撤销失败：$cause$heldSuffix"
    }
}

/**
 * 异常类名（FQN）→ 档。表里没点名的类一律 [UndoFailureKind.Unexplained]（保守：留条目、不许诺）。
 *
 * 表**只按类名认，不做 isAssignableFrom**：内核零 android import，拿不到那些 Class 对象，
 * 而"某个类算不算某一档"这件事本来就该是一张能逐行读、能逐行测的表（父类通吃会把
 * `SQLiteConstraintException` 之类的具体档冲成泛化档，正是本卡要的区分反掉）。
 *
 * `internal`（不是 private）是**给单测留的对照面**：`UndoFailureRecoveryTest` 拿格子表的类名册子
 * 与这张表的键集**逐枚比**，表里添一行不给格子、或删一行不收格子，当场红。
 */
internal val UNDO_FAILURE_TABLE: Map<String, UndoFailureKind> = mapOf(
    "android.database.sqlite.SQLiteBusyException" to UndoFailureKind.WriteLockBusy,
    "android.database.sqlite.SQLiteDatabaseLockedException" to UndoFailureKind.WriteLockBusy,
    "android.database.sqlite.SQLiteFullException" to UndoFailureKind.StorageUnavailable,
    "android.database.sqlite.SQLiteDiskIOException" to UndoFailureKind.StorageUnavailable,
    "java.io.IOException" to UndoFailureKind.StorageUnavailable,
    "java.lang.OutOfMemoryError" to UndoFailureKind.StorageUnavailable,
    "android.database.sqlite.SQLiteConstraintException" to UndoFailureKind.RejectedBySnapshot,
    "java.lang.IllegalArgumentException" to UndoFailureKind.RejectedBySnapshot,
    "java.lang.IllegalStateException" to UndoFailureKind.RejectedBySnapshot,
    "java.lang.NullPointerException" to UndoFailureKind.RejectedBySnapshot,
    "java.lang.ClassCastException" to UndoFailureKind.RejectedBySnapshot,
    "java.lang.IndexOutOfBoundsException" to UndoFailureKind.RejectedBySnapshot,
)

/**
 * 同一趟失败链上出现两档时取哪一档：**越具体越优先**。
 *
 * 次序不是风格：Room 常把真正的原因包在一枚泛化的 `android.database.SQLException` 里递上来，
 * 按"链上第一个命中"取就把"快照写不进去"读成"锁问题"、于是回推 + 许诺重试 ⇒ 用户反复点、
 * 反复同一个约束错。`Unexplained` 不在这榜上（它是兜底，不是某档）。
 *
 * 同样 `internal` 给单测对照：榜上的档序逐枚钉着（朝"取链上第一个命中"那一扭就红在这里）。
 */
internal val UNDO_FAILURE_PRIORITY: List<UndoFailureKind> = listOf(
    UndoFailureKind.RejectedBySnapshot,
    UndoFailureKind.StorageUnavailable,
    UndoFailureKind.WriteLockBusy,
)

/** 链上类名 → 档（先按 [UNDO_FAILURE_PRIORITY] 取最具体的那一档，都不在表里就是兜底档） */
internal fun undoFailureKindOf(exceptionClassNames: List<String>): UndoFailureKind {
    val matched = exceptionClassNames.mapNotNull { UNDO_FAILURE_TABLE[it] }
    return UNDO_FAILURE_PRIORITY.firstOrNull { it in matched } ?: UndoFailureKind.Unexplained
}

/**
 * 异常链上的类名，从外层往里取。
 *
 * 两道防爆：自指的 `cause` 跳过去 + 最多 [CLASS_CHAIN_DEPTH] 枚 —— 环形的 `cause` 图
 * （a→b→a，`initCause` 造得出来）不许把这一手变成死循环，那是提示链上的新病。
 */
internal fun exceptionClassNamesOf(failure: Throwable): List<String> {
    val names = mutableListOf<String>()
    var remaining: Throwable? = failure
    while (remaining != null && names.size < CLASS_CHAIN_DEPTH) {
        val current = remaining
        names += current.javaClass.name
        remaining = current.cause?.takeIf { it !== current }
    }
    return names
}

/** 异常链最多读几枚（外层 + 若干层 cause）；深过这个数的链上再没有本卡要认的档 */
private const val CLASS_CHAIN_DEPTH = 8
