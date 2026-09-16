package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.TimeSlot

/**
 * 「智慧节次」推算：只要给出 首节开始时间、每节时长、节间休息、午休配置，
 * 就能推算出全天所有节次的时间，免去一节一节手填（Sleepy `SmartPeriodConfig` 同思路）。
 *
 * 纯函数，可单测。推算结果仍走既有的节次编辑保存链路（用户可在推算后微调）。
 */
object SmartPeriods {

    /** 推算参数；校验失败返回的错误信息走 [Result.exceptionOrNull] */
    data class Params(
        val firstStart: String,      // HH:mm，第一节上课时间
        val periodMinutes: Int,      // 每节课时长（分钟）
        val breakMinutes: Int,       // 节间休息（分钟）
        val lunchAfterPeriod: Int,   // 第几节之后进入午休（0 = 不设午休）
        val lunchMinutes: Int,       // 午休时长（分钟）
        val count: Int,              // 节次总数
    )

    fun derive(params: Params): Result<List<TimeSlot>> {
        val first = runCatching { java.time.LocalTime.parse(params.firstStart.trim()) }
            .getOrElse { return Result.failure(IllegalArgumentException("首节开始时间需为 HH:mm")) }
        if (params.count !in 1..CourseConstraints.MAX_PERIOD) {
            return Result.failure(IllegalArgumentException("节次总数需在 1..${CourseConstraints.MAX_PERIOD}"))
        }
        if (params.periodMinutes !in 1..120) {
            return Result.failure(IllegalArgumentException("每节时长需在 1..120 分钟"))
        }
        if (params.breakMinutes < 0 || params.lunchMinutes < 0) {
            return Result.failure(IllegalArgumentException("休息时长不能为负"))
        }
        if (params.lunchAfterPeriod !in 0..params.count) {
            return Result.failure(IllegalArgumentException("午休位置超出节次范围"))
        }

        val slots = ArrayList<TimeSlot>(params.count)
        var cursor = first
        for (number in 1..params.count) {
            val end = cursor.plusMinutes(params.periodMinutes.toLong())
            if (end <= cursor) {
                return Result.failure(IllegalArgumentException("推算时间溢出（时长过大）"))
            }
            slots += TimeSlot(number = number, startTime = cursor.toString(), endTime = end.toString())
            cursor = end.plusMinutes(
                (if (params.lunchAfterPeriod in 1..params.count && number == params.lunchAfterPeriod) {
                    params.lunchMinutes
                } else {
                    params.breakMinutes
                }).toLong(),
            )
        }
        return Result.success(slots)
    }
}
