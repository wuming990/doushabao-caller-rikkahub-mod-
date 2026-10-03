package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupSpeakerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v318：分支创建时搬运「按对话 id 存」的设置。
 *
 * 全部走**真实函数调用**，不做源码字符串比对 —— 用户报的是「创建分支后讨论群组消失」，
 * 只有真跑才断得出来新对话上到底查不查得到配置。
 */
class ForkConversationSettingsTest {

    private val source = Uuid.random()
    private val forked = Uuid.random()
    private val memberA = Uuid.random()
    private val memberB = Uuid.random()
    private val modelA = Uuid.random()

    private val groupConfig = GroupChatConfig(
        memberModelIds = listOf(memberA, memberB),
        speakerMode = GroupSpeakerMode.MODERATED,
        turnsPerRun = 7,
        resumeMax = 12,
        enabled = false,
    )

    @Test
    fun `分支带上原对话的群组配置，原对话的一份不动`() {
        val settings = Settings(groupChatConfigs = mapOf(source to groupConfig))
        val after = settingsAfterFork(settings, source, forked)

        assertEquals("新对话要能查到群配置", groupConfig, after.groupChatConfigs[forked])
        assertSame("原对话的配置必须还在", groupConfig, after.groupChatConfigs[source])
        assertEquals("只多出一条", 2, after.groupChatConfigs.size)
    }

    @Test
    fun `整套配置原样带过去，不重置任何字段`() {
        val settings = Settings(groupChatConfigs = mapOf(source to groupConfig))
        val carried = settingsAfterFork(settings, source, forked).groupChatConfigs[forked]!!

        // 成员、顺序、发言方式、批次、续写次数、暂停状态 —— 用户拍板「一起带过来」
        assertEquals(groupConfig.memberModelIds, carried.memberModelIds)
        assertEquals(GroupSpeakerMode.MODERATED, carried.speakerMode)
        assertEquals(7, carried.turnsPerRun)
        assertEquals(12, carried.resumeMax)
        // 暂停态（enabled=false）也要照搬：不能因为分支就偷偷把用户暂停的群打开
        assertEquals(false, carried.enabled)
        assertEquals(groupConfig.allowAskUser, carried.allowAskUser)
        assertEquals(groupConfig.allowTools, carried.allowTools)
    }

    @Test
    fun `分支带上对话级模型记忆`() {
        val settings = Settings(
            rememberModelPerConversation = true,
            conversationModelIds = mapOf(source to modelA),
        )
        val after = settingsAfterFork(settings, source, forked)
        assertEquals(modelA, after.conversationModelIds[forked])
        assertEquals("原对话的记忆还在", modelA, after.conversationModelIds[source])
    }

    @Test
    fun `关掉对话独立记忆模型时不写模型记忆`() {
        // 关着时 ChatVM 根本不读这条记录，悄悄写一份只会留下看不见的脏数据。
        // 群组配置不受这个开关影响，照常搬。
        val settings = Settings(
            rememberModelPerConversation = false,
            conversationModelIds = mapOf(source to modelA),
            groupChatConfigs = mapOf(source to groupConfig),
        )
        val after = settingsAfterFork(settings, source, forked)
        assertNull("开关关着就不该搬模型记忆", after.conversationModelIds[forked])
        assertEquals("群配置仍然要搬", groupConfig, after.groupChatConfigs[forked])
    }

    @Test
    fun `源对话没建过群也没记忆时不产生任何写入`() {
        // 返回同一引用 = 调用方据此跳过落盘，避免每次分支都全量写一次盘
        val settings = Settings()
        assertSame(
            "没东西可搬时必须原样返回",
            settings,
            settingsAfterFork(settings, source, forked),
        )
    }

    @Test
    fun `源对话与目标对话是同一个时不动`() {
        val settings = Settings(groupChatConfigs = mapOf(source to groupConfig))
        assertSame(settings, settingsAfterFork(settings, source, source))
    }

    @Test
    fun `搬运会保留其它对话既有的配置`() {
        val other = Uuid.random()
        val otherConfig = GroupChatConfig(memberModelIds = listOf(memberA, memberB), turnsPerRun = 2)
        val settings = Settings(
            groupChatConfigs = mapOf(source to groupConfig, other to otherConfig),
        )
        val after = settingsAfterFork(settings, source, forked)
        assertEquals("另一场讨论的配置不能被覆盖", otherConfig, after.groupChatConfigs[other])
        assertEquals(groupConfig, after.groupChatConfigs[forked])
        assertEquals(3, after.groupChatConfigs.size)
    }

    @Test
    fun `除这两张表外不改动设置的其它部分`() {
        val settings = Settings(
            dynamicColor = false,
            developerMode = true,
            groupChatConfigs = mapOf(source to groupConfig),
        )
        val after = settingsAfterFork(settings, source, forked)
        assertTrue(after.developerMode)
        assertEquals(false, after.dynamicColor)
    }
}
