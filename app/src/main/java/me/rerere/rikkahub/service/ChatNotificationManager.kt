package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val isForeground = MutableStateFlow(false)
    private val liveUpdateLastSentAt = ConcurrentHashMap<Uuid, Long>()

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> Unit // 二改：不再显示生成中常驻通知（上游 2.4.13 改由前台服务通知承载）
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val now = SystemClock.elapsedRealtime()
        val lastSentAt = liveUpdateLastSentAt[event.conversationId]
        if (lastSentAt != null && now - lastSentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS) return
        liveUpdateLastSentAt[event.conversationId] = now

        sendLiveUpdateNotification(event.conversationId, event.lastMessage, event.senderName)
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        // 兼容清理旧版本可能留下的 Live Update 通知；新版本不再发送生成中通知（二改）。
        cancelLiveUpdateNotification(event.conversationId)

        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return

        // 二改：不管成功失败都通知，并注明原因；上游 2.4.13 在 contentPreview 为空时直接返回。
        val content = when (event.reason) {
            AppEvent.GenerationEndReason.COMPLETED -> event.contentPreview
                ?: context.getString(R.string.notification_generation_finished)
            AppEvent.GenerationEndReason.FAILED -> context.getString(R.string.notification_generation_failed)
            AppEvent.GenerationEndReason.CANCELLED -> context.getString(R.string.notification_generation_cancelled)
            AppEvent.GenerationEndReason.STOPPED -> context.getString(R.string.notification_generation_stopped)
        }
        sendGenerationDoneNotification(event.conversationId, event.senderName, content)
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            // 二改：按会话散列生成通知 id，避免多会话同时完成时互相覆盖（上游用固定 1）。
            notificationId = conversationId.hashCode() + 20000
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String
    ) {
        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(lastMessage.parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            // 更新前台服务正在使用的同一条通知，避免重复显示生成进度。
            notificationId = ChatGenerationForegroundService.NOTIFICATION_ID
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.substringAfterLast("__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdateLastSentAt.remove(conversationId)
        // 前台服务持有通知时系统会保留它；启动失败时则清理普通 ongoing 通知。
        context.cancelNotification(ChatGenerationForegroundService.NOTIFICATION_ID)
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
