package com.phoclaw.chat.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.phoclaw.chat.data.AutomationTask
import com.phoclaw.chat.data.CronParser
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 用 AlarmManager 精确调度自动化任务。
 *
 * 为什么用 AlarmManager 而不是 WorkManager：WorkManager 的周期任务最小间隔是 15 分钟，
 * 而且系统会为省电做 Doze 批量延迟，偏差可能到几十分钟。用户明确选了「尽量准时」，
 * 所以走 `setExactAndAllowWhileIdle` —— 它在 Doze 模式下也能按时唤醒。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** 请求码的生成方式。用 id 的哈希，保证同一任务每次都是同一个 PendingIntent。 */
    private fun requestCode(taskId: String): Int = taskId.hashCode()

    /**
     * 为任务排下一次触发。
     *
     * 触发时刻由 cron 表达式**真实计算**得出，而不是用固定间隔。
     * 这点很关键：`0 9 * * 1-5` 的下一次应该是「下一个工作日的 9 点」，
     * 如果按「24 小时后」排，就会在周末的凌晨触发，完全不符合预期。
     */
    fun schedule(context: Context, task: AutomationTask) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            Log.w(TAG, "无法获取 AlarmManager，任务 ${task.id} 未排程")
            return
        }

        val parsed = CronParser.parse(task.cron).expression
        if (parsed == null) {
            Log.w(TAG, "任务 ${task.id} 的 cron 非法，跳过排程：${task.cron}")
            return
        }

        val next = parsed.nextAfter(LocalDateTime.now())
        if (next == null) {
            Log.w(TAG, "任务 ${task.id} 的 cron 永不触发：${task.cron}")
            return
        }

        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = buildPendingIntent(context, task.id)

        // 未授权精确闹钟时降级。不抛异常、不中断保存流程 ——
        // 任务照跑，只是时间可能偏十几分钟，比直接不可用强得多
        try {
            if (ExactAlarmPermission.canScheduleExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "任务 ${task.id} 已排程：$next")
        } catch (e: SecurityException) {
            // 部分 ROM 即使 canScheduleExactAlarms 返回 true 也会拒绝，
            // 这里兜一层，退回不精确模式
            Log.w(TAG, "精确闹钟被拒，降级为不精确：${e.message}")
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    /** 取消任务的闹钟。 */
    fun cancel(context: Context, taskId: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = buildPendingIntent(context, taskId, forCancel = true)
        manager.cancel(pending)
        pending.cancel()
        Log.i(TAG, "任务 $taskId 的闹钟已取消")
    }

    /**
     * 重建全部启用任务的闹钟。
     *
     * 开机、改系统时间、时区变更、应用升级后都必须调用 ——
     * 这些事件会清掉所有已注册的闹钟。不重建的话，用户重启一次手机
     * 所有定时任务就永久失效了，而且没有任何提示。
     */
    suspend fun rescheduleAll(context: Context, tasks: List<AutomationTask>) {
        tasks.filter { it.enabled }.forEach { schedule(context, it) }
    }

    /**
     * 计算并返回下次触发时刻，供界面预览。
     *
     * @return 表达式非法或永不触发时返回 null
     */
    fun nextTrigger(cron: String, count: Int = 3): List<LocalDateTime> {
        val parsed = CronParser.parse(cron).expression ?: return emptyList()
        return parsed.upcoming(LocalDateTime.now(), count)
    }

    private fun buildPendingIntent(
        context: Context,
        taskId: String,
        forCancel: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, AutomationReceiver::class.java).apply {
            action = AutomationReceiver.ACTION_RUN_TASK
            putExtra(AutomationReceiver.EXTRA_TASK_ID, taskId)
            // 必须带上包名，否则某些 ROM 上会被解析到别的应用
            setPackage(context.packageName)
        }

        var flags = PendingIntent.FLAG_IMMUTABLE
        flags = if (forCancel) {
            flags or PendingIntent.FLAG_NO_CREATE
        } else {
            flags or PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pending = PendingIntent.getBroadcast(context, requestCode(taskId), intent, flags)
        // FLAG_NO_CREATE 在没排过闹钟时会返回 null，用不到时给个空实现
        return pending ?: PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** 精确闹钟权限的查询与引导。 */
object ExactAlarmPermission {

    /**
     * 当前是否允许使用精确闹钟。
     *
     * Android 12（API 31）起 `SCHEDULE_EXACT_ALARM` 需要用户手动授权；
     * Android 13（API 33）起如果声明了 `USE_EXACT_ALARM` 则自动授予。
     * 12 以下没有这个限制，恒为 true。
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return manager.canScheduleExactAlarms()
    }
}
