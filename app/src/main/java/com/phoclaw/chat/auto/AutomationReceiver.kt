package com.phoclaw.chat.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 闹钟触发的接收器。
 *
 * 收到 AlarmManager 的广播后，交给前台服务去执行 ——
 * 这里不能直接跑任务，因为 BroadcastReceiver 的 `onReceive` 只有约 10 秒窗口，
 * 而任务要跑几十秒甚至更久。
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_TASK) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.w(TAG, "收到空的 taskId，忽略")
            return
        }
        Log.i(TAG, "闹钟触发任务：$taskId")
        AutomationService.start(context.applicationContext, taskId)
    }

    companion object {
        private const val TAG = "AutomationReceiver"

        const val ACTION_RUN_TASK = "com.phoclaw.chat.action.RUN_AUTOMATION_TASK"
        const val EXTRA_TASK_ID = "phoclaw.automation.task_id"
    }
}
