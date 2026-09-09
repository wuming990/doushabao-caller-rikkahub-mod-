package me.rerere.rikkahub.di

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.agent.db.AGENT_MIGRATION_1_2
import me.rerere.rikkahub.agent.db.AGENT_MIGRATION_2_3
import me.rerere.rikkahub.agent.db.AgentDatabase
import me.rerere.rikkahub.agent.repo.AgentThreadRepository
import me.rerere.rikkahub.agent.repo.RoomAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.GenerationAgentBackend
import me.rerere.rikkahub.data.datastore.findModelById
import org.koin.dsl.module

/**
 * v218：独立 Codex 风格子代理（Agent Thread）模块。
 *
 * - 独立数据库 `rikka_hub_agents`（version 1），与主库/圆桌完全分离；
 * - 真实后端 GenerationAgentBackend（GenerationLoop + 独立隔离助手 + 只读工具）；
 * - 并发上限跟随设置（agentMaxConcurrent，1~8，运行时生效）；
 * - 圆桌代码不得引用本模块中的任何类型（由依赖边界测试守护）。
 */
val agentModule = module {
    // 代理专用作用域：与 AppScope 分离，代理运行不占用主线程、不依赖主会话
    single {
        CoroutineScope(
            SupervisorJob()
                + Dispatchers.IO
                + CoroutineName("AgentScope")
                + CoroutineExceptionHandler { _, e ->
                    android.util.Log.e("AgentModule", "AgentScope exception", e)
                }
        )
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AgentDatabase::class.java, "rikka_hub_agents")
            // v222：version 1 → 2 增量迁移（新增 finish_reason / truncated / resume_count），
            // v236：version 2 → 3 增量迁移（新增 writable_paths / active_model_id），
            // 绝不使用破坏性迁移，旧子代理记录全部保留
            .addMigrations(AGENT_MIGRATION_1_2, AGENT_MIGRATION_2_3)
            .build()
    }

    single<AgentThreadRepository> {
        RoomAgentThreadRepository(get())
    }

    single {
        val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = get()
        val manager = AgentThreadManager(
            repository = get(),
            backend = GenerationAgentBackend(
                generationLoop = get(),
                settingsStore = settingsStore,
                workspaceRepository = get(),
                agentRepository = get(),
                conversationRepository = get(),
            ),
            scope = get(),
            maxConcurrent = 4,
            // v241：自动续跑次数跟随设置（0 = 完全不自动续跑），每次用到时现读现用，
            // 用户改完设置立刻生效，不需要重启 App。
            autoResumeMax = {
                settingsStore.settingsFlow.first().agentAutoResumeMax.coerceIn(0, 10)
            },
            // v242：换模型前先确认这个模型 id 在设置里真的存在。
            // 旧行为是照样把 id 钉到线程上，候选链解析时找不到就静默跳过、回落到
            // 原来的模型，于是事件流里「已换成 xxx」是假的，用户白等一场（真机实测）。
            modelExists = { id ->
                val settings = settingsStore.settingsFlow.first()
                runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull()
                    ?.let { uuid -> settings.findModelById(uuid) } != null
            },
            // v244：流水线要靠它算出每一棒该等多久。
            // 真机故障：审查那一棒被调用方给的时限掐掉，它自己的重试/换模型/续跑
            // 一次都没来得及用（等待方先到点，被等的一方就没有第二次机会）。
            // 注意这个设置项从 v244 起的语义是「多久没有新内容算卡住」，不是总时长。
            idleTimeoutMillis = {
                val minutes = settingsStore.settingsFlow.first()
                    .agentThreadTimeoutMinutes.coerceIn(0, 120)
                if (minutes <= 0) 0L else minutes * 60_000L
            },
        )
        // 并发上限跟随设置（运行时生效，1~8）
        val agentScope: CoroutineScope = get()
        agentScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                manager.maxConcurrent = settings.agentMaxConcurrent.coerceIn(1, 8)
            }
        }
        manager
    }
}
