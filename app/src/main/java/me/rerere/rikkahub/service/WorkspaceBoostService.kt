package me.rerere.rikkahub.service

import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "WorkspaceBoost"

/**
 * 工作区加速 (v207: 去掉前台服务与常驻通知, 只保留无感知的两项).
 *
 * 历史与实测结论 (骁龙 8 Gen 3, 8 核):
 * - 进程的 cpuset 分组在 fork 那一刻继承并固定, 系统之后不会迁移子进程,
 *   所以"命令启动时 App 是否在前台/小窗"直接决定这条命令(哪怕跑几小时)的核心数:
 *   前台/小窗 = /top-app = 8 核; 后台 = /background = 4 核 (实测差 10.4 倍)。
 * - v203~v206 曾用前台服务 + 常驻通知试图改善, 已实测证明: 前台服务并不能提升核心数,
 *   挂着通知在后台跑仍然只有 4 核 (三次测试一致)。
 * - 因此 v207 按用户要求彻底移除前台服务与通知; 保留两项无副作用、无 UI 的措施:
 *   1) [boostCurrentThread] 把发起线程 nice 抬回默认值 (子进程 fork 时继承);
 *   2) [acquireWakeLock] PARTIAL_WAKE_LOCK 防止 CPU 进入深度睡眠导致命令停滞。
 *
 * 代价 (已向用户说明): 没有前台服务后, 长任务期间若切走并锁屏,
 * 进程存在被系统冻结或回收的风险, 最坏情况是长时间构建白跑。
 */
object WorkspaceBoostManager {
    /** WakeLock 兜底超时: 正常在本轮生成结束时释放 */
    private const val WAKE_LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L

    /** v206 及更早版本的加速通知 id 与渠道, 仅用于升级后清理残留 */
    private const val LEGACY_NOTIFICATION_ID = 2003
    private const val LEGACY_CHANNEL_ID = "workspace_boost"

    private val activeCommands = AtomicInteger(0)
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    private var wakeLock: PowerManager.WakeLock? = null

    fun init(context: Context, eventBus: AppEventBus, scope: CoroutineScope) {
        appContext = context.applicationContext
        // 从 v206 升级上来时, 可能残留一条加速通知和一个已废弃的通知渠道, 开机清理一次
        clearLegacyNotification(context)
        scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                if (event is AppEvent.ChatGenerationEnded) {
                    Log.i(TAG, "generation ended (${event.reason}), releasing wake lock")
                    shutdown()
                }
            }
        }
    }

    /**
     * 一条工作区命令开始执行. 必须与 [end] 严格配对 (try/finally).
     * 不再启动任何服务, 也不再显示通知。
     */
    fun begin() {
        activeCommands.incrementAndGet()
        boostCurrentThread()
        acquireWakeLock()
    }

    /** 一条命令结束; 唤醒锁在本轮生成结束事件里统一释放 */
    fun end() {
        activeCommands.decrementAndGet()
    }

    /** 本轮生成结束: 释放唤醒锁 */
    internal fun shutdown() {
        releaseWakeLock()
    }

    /**
     * 把当前线程的 nice 值抬回默认值; 子进程 fork 时继承发起线程的 nice.
     * 失败(系统不允许提升优先级)时静默忽略.
     */
    private fun boostCurrentThread() {
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        }.onFailure {
            Log.d(TAG, "setThreadPriority skipped: ${it.message}")
        }
    }

    private fun acquireWakeLock() {
        val context = appContext ?: return
        synchronized(lock) {
            if (wakeLock?.isHeld == true) return
            runCatching {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "rikkahub:workspace-boost"
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }.onFailure {
                Log.w(TAG, "acquireWakeLock failed", it)
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(lock) {
            runCatching {
                wakeLock?.takeIf { it.isHeld }?.release()
            }.onFailure {
                Log.w(TAG, "releaseWakeLock failed", it)
            }
            wakeLock = null
        }
    }

    /** 清理 v206 遗留的加速通知与通知渠道, 避免通知设置里留下一个没用的开关 */
    private fun clearLegacyNotification(context: Context) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(LEGACY_NOTIFICATION_ID)
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }.onFailure {
            Log.w(TAG, "clear legacy boost notification failed", it)
        }
    }
}
