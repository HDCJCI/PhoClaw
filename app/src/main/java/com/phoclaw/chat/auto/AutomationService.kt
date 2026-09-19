package com.phoclaw.chat.auto

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.phoclaw.chat.data.AutomationStore
import com.phoclaw.chat.data.AutomationTask
import com.phoclaw.chat.data.ConversationStore
import com.phoclaw.chat.data.CredentialStore
import com.phoclaw.chat.data.HeadlessTurnRunner
import com.phoclaw.chat.data.SkillStore
import com.phoclaw.chat.data.WorkspaceRepository
import com.phoclaw.chat.data.newConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在后台执行自动化任务的前台服务。
 *
 * ## 为什么必须是前台服务
 *
 * `BroadcastReceiver.goAsync()` 只有约 10 秒的执行窗口。一次任务最多 8 轮工具调用，
 * 每轮都要等模型流式响应，轻松超过 30 秒 —— 超时后系统会直接强杀进程并记为 ANR。
 *
 * 前台服务没有这个时限，代价是必须挂一个不可划掉的常驻通知。
 *
 * ## 从闹钟触发启动服务是否合法
 *
 * 合法。Android 12+ 的后台启动限制有几类系统豁免场景，而我们用的恰好都是：
 * 精确闹钟触发、`BOOT_COMPLETED`、高优先级 FCM。所以
 * `startForegroundService` 在这里不会被拒绝。
 */
class AutomationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var store: AutomationStore

    override fun onCreate() {
        super.onCreate()
        store = AutomationStore(applicationContext)
        Notifications.ensureChannel(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 必须**立刻**进入前台，否则 5 秒内不调用 startForeground 会被系统杀掉
        promoteToForeground("任务准备中")

        scope.launch {
            try {
                execute(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "任务 $taskId 执行异常", e)
            } finally {
                Notifications.clearRunning(applicationContext)
                ServiceCompat.stopForeground(this@AutomationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        // 不要 START_STICKY：任务已经跑完了，系统重新拉起一个空服务没有意义，
        // 而且可能在没有 taskId 的情况下启动导致异常
        return START_NOT_STICKY
    }

    private suspend fun execute(taskId: String) {
        val task = store.load(taskId)
        if (task == null) {
            Log.w(TAG, "任务 $taskId 不存在，可能已被删除")
            return
        }
        if (!task.enabled) {
            Log.i(TAG, "任务 $taskId 已停用，跳过")
            return
        }

        // ★ 第一件事就是重排下一次闹钟。
        // 放在最前面是刻意的：后面无论抛异常还是被杀，下一次触发都已经注册好了，
        // 不会因为某次执行失败导致任务永久停止。
        rescheduleNext(task)

        val context = applicationContext
        val conversationId = task.conversationId.ifBlank { newConversationId() }

        val runner = HeadlessTurnRunner(
            app = context,
            credentials = CredentialStore(context),
            workspace = WorkspaceRepository(context),
            skillStore = SkillStore(context),
            conversations = ConversationStore(context)
        )

        // 工作区 URI 是从 CredentialStore 恢复的，但 WorkspaceRepository 需要显式设置。
        // 这里补一次，否则后台执行时无法读写文件
        restoreWorkspace(context)

        val startedAt = System.currentTimeMillis()
        val outcome = runner.run(task, conversationId) { progress ->
            Notifications.showRunning(applicationContext, "${task.title} · $progress")
        }

        // 首次执行后把会话 id 写回任务，之后每次覆盖写同一个会话
        if (task.conversationId.isBlank()) {
            store.save(task.copy(conversationId = conversationId))
        }

        store.updateRunState(
            id = task.id,
            lastRunAt = startedAt,
            result = outcome.summary,
            success = outcome.success
        )

        Notifications.notifyResult(
            context = context,
            taskId = task.id,
            title = task.title,
            summary = outcome.summary,
            success = outcome.success
        )
    }

    /**
     * 排下一次触发。
     *
     * 重新从存储里读一遍任务，而不是用执行开始时的快照 ——
     * 用户可能在执行期间改了 cron，用旧快照会把新表达式覆盖掉。
     */
    private suspend fun rescheduleNext(task: AutomationTask) {
        val fresh = withContext(Dispatchers.IO) { store.load(task.id) } ?: return
        if (!fresh.enabled) return
        AlarmScheduler.schedule(applicationContext, fresh)
    }

    /** 恢复工作区授权。 */
    private fun restoreWorkspace(context: Context) {
        val saved = CredentialStore(context).workspaceUri
        if (saved.isBlank()) return
        runCatching {
            val uri = android.net.Uri.parse(saved)
            val allowed = context.contentResolver.persistedUriPermissions
                .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            if (allowed) WorkspaceRepository(context).setTreeUri(uri)
            else Log.w(TAG, "工作区授权已失效，任务无法读写文件")
        }
    }

    private fun promoteToForeground(text: String) {
        val notification = Notifications.runningNotification(applicationContext, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutomationService"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_TASK_ID = "phoclaw.automation.task_id"

        /** 启动服务执行任务。闹钟触发与「立即执行一次」都走这里，保证行为一致。 */
        fun start(context: Context, taskId: String) {
            val intent = Intent(context, AutomationService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.e(TAG, "启动自动化服务失败：${it.message}")
            }
        }
    }
}
