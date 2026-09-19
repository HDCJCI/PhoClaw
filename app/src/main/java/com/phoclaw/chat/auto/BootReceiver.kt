package com.phoclaw.chat.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phoclaw.chat.data.AutomationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 改时间 / 改时区 / 应用升级后重建闹钟。
 *
 * **这个接收器不能省。** Android 在重启后会清空所有已注册的 AlarmManager 闹钟，
 * 改系统时间与时区也会让基于 `RTC_WAKEUP` 的闹钟失效。不重建的话，
 * 用户重启一次手机，所有定时任务就永久静默失效了 —— 而且不会收到任何提示。
 *
 * 用 `goAsync()` 是因为读任务列表要走磁盘 IO，而 `onReceive` 是在主线程上跑的。
 * 这里只需要读一个很小的 JSON 列表再逐个注册闹钟，远在 10 秒窗口之内。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in TRIGGER_ACTIONS) return

        Log.i(TAG, "收到 $action，开始重建闹钟")

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tasks = AutomationStore(appContext).list()
                AlarmScheduler.rescheduleAll(appContext, tasks)
                Log.i(TAG, "已重建 ${tasks.count { it.enabled }} 个任务的闹钟")
            } catch (e: Exception) {
                Log.e(TAG, "重建闹钟失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val TRIGGER_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
