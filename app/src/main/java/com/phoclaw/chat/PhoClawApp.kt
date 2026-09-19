package com.phoclaw.chat

import android.app.Application
import com.phoclaw.chat.auto.Notifications
import com.phoclaw.chat.data.AutomationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoClawApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 通知渠道要在任何通知发出前建好。放在这里而不是服务里，
        // 是因为用户可能在设置里关掉某个渠道，而重复创建渠道会重置他的设置 ——
        // 集中在启动时建一次，幂等且不会覆盖用户选择
        Notifications.ensureChannel(this)

        // 冷启动时校准一次闹钟。
        //
        // BootReceiver 已经覆盖了重启与改时间的场景，这里再加一道是因为：
        // 应用被「强行停止」后，系统会清掉它的所有闹钟，且不再发送任何广播
        //（强行停止的应用收不到 BOOT_COMPLETED）。用户下次手动打开应用时，
        // 这是唯一能把调度恢复回来的时机。
        val appContext = this
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val tasks = AutomationStore(appContext).list().filter { it.enabled }
                com.phoclaw.chat.auto.AlarmScheduler.rescheduleAll(appContext, tasks)
            }
        }
    }
}
