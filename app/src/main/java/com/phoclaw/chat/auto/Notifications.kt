package com.phoclaw.chat.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phoclaw.chat.MainActivity
import com.phoclaw.chat.R

/**
 * 自动化任务的通知。
 *
 * 两个用途：
 * 1. **前台服务的常驻通知** —— 系统强制要求，Android 会杀掉没有通知的前台服务
 * 2. **执行结果通知** —— 点开跳进该任务的会话，用户能看到 AI 干了什么
 */
object Notifications {

    const val CHANNEL_ID = "phoclaw_automation"

    /** 执行中通知的固定 id。同一时刻只跑一个任务，复用它即可。 */
    private const val ID_RUNNING = 1001

    /** 结果通知的 id 基数，加上包名哈希避免和别的应用撞。 */
    private const val ID_RESULT_BASE = 2000

    /** 创建通知渠道。在 Application.onCreate 里调用一次即可，重复调用是幂等的。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // 已存在就不要再建 —— 重复创建会重置用户对渠道的自定义设置
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化任务",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "定时任务的执行状态与结果"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 前台服务必需的常驻通知。 */
    fun runningNotification(context: Context, title: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在执行：$title")
            .setContentText("AI 正在后台处理任务，请稍候")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .build()

    /** 执行完成通知。 */
    fun resultNotification(
        context: Context,
        taskId: String,
        title: String,
        summary: String,
        success: Boolean
    ): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (success) "任务完成：$title" else "任务失败：$title")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openTaskIntent(context, taskId))
            .build()

    /** 发送结果通知。缺少通知权限时静默失败（用户拒绝了通知权限不该导致任务崩掉）。 */
    fun notifyResult(
        context: Context,
        taskId: String,
        title: String,
        summary: String,
        success: Boolean
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            manager.notify(
                ID_RESULT_BASE + (taskId.hashCode() and 0xFF),
                resultNotification(context, taskId, title, summary, success)
            )
        }
    }

    /** 前台服务用：显示执行中通知。 */
    fun showRunning(context: Context, title: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(ID_RUNNING, runningNotification(context, title)) }
    }

    fun clearRunning(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_RUNNING) }
    }

    /** 点击通知打开应用。 */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 点击结果通知，直接跳到该任务的会话。
     *
     * 用 extra 传 taskId，[MainActivity] 收到后在启动时打开对应会话。
     */
    private fun openTaskIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TASK_ID, taskId)
        }
        return PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    const val EXTRA_OPEN_TASK_ID = "phoclaw.open_task_id"
}
