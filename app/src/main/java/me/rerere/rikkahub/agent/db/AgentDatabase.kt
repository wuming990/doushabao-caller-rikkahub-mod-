package me.rerere.rikkahub.agent.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v218：独立 Agent 数据库。
 *
 * 与主数据库（AppDatabase，version 24）完全分离：
 * - 不修改主库版本、不迁移真实聊天数据；
 * - 代理线程/消息/事件只写这里；
 * - 代理功能出问题时可直接停用，不影响聊天与圆桌。
 *
 * v222：version 1 → 2，新增 finish_reason / truncated / resume_count 三列
 * （识别输出被截断 + 支持继续输出）。使用增量 ALTER TABLE 迁移，
 * 旧的子代理历史记录全部保留，绝不清库。
 *
 * v236：version 2 → 3，新增 writable_paths / active_model_id 两列
 * （编程位的可写白名单 + 备用模型链实际用了哪个模型）。同样只加列、不动既有数据。
 * 注意：主数据库 AppDatabase 仍然是 24，本次一个字都没碰 —— 聊天记录零风险。
 */
@Database(
    entities = [
        AgentThreadEntity::class,
        AgentMessageEntity::class,
        AgentEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentThreadDao(): AgentThreadDao
}

/**
 * v222 迁移：为 agent_threads 增加截断识别与续跑所需的三列。
 *
 * 只做加列，不动任何既有数据，可安全反复升级。
 */
val AGENT_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_threads ADD COLUMN finish_reason TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE agent_threads ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_threads ADD COLUMN resume_count INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v236 迁移：为 agent_threads 增加写白名单与实际模型两列。
 *
 * writable_paths 默认空串 = 只读，旧线程升级后不会凭空获得写权限。
 */
val AGENT_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_threads ADD COLUMN writable_paths TEXT DEFAULT ''")
        db.execSQL("ALTER TABLE agent_threads ADD COLUMN active_model_id TEXT DEFAULT NULL")
    }
}
