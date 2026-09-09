package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v289：存储管理多选批量删除的纯汇总函数测试。
 * 只测 summarizeBatchDelete（空集 / 全成功 / 部分失败 / 全失败），不碰真实文件与 Room。
 */
class FilesManagerBatchDeleteTest {

    @Test
    fun `empty set summarizes to empty result`() {
        val result = summarizeBatchDelete(emptyList())
        assertEquals(0, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.deletedIds.isEmpty())
        assertTrue(result.failedIds.isEmpty())
        assertTrue(result.allSucceeded)
    }

    @Test
    fun `all succeeded keeps every id and reports no failure`() {
        val result = summarizeBatchDelete(
            listOf(
                FileDeleteOutcome(id = 1L, deleted = true),
                FileDeleteOutcome(id = 2L, deleted = true),
                FileDeleteOutcome(id = 3L, deleted = true),
            )
        )
        assertEquals(listOf(1L, 2L, 3L), result.deletedIds)
        assertEquals(3, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.failedIds.isEmpty())
        assertTrue(result.allSucceeded)
    }

    @Test
    fun `partial failure splits ids into deleted and failed`() {
        val result = summarizeBatchDelete(
            listOf(
                FileDeleteOutcome(id = 1L, deleted = true),
                FileDeleteOutcome(id = 2L, deleted = false),
                FileDeleteOutcome(id = 3L, deleted = true),
                FileDeleteOutcome(id = 4L, deleted = false),
            )
        )
        assertEquals(listOf(1L, 3L), result.deletedIds)
        assertEquals(listOf(2L, 4L), result.failedIds)
        assertEquals(2, result.deletedCount)
        assertEquals(2, result.failedCount)
        assertFalse(result.allSucceeded)
    }

    @Test
    fun `all failed keeps every id as failed`() {
        val result = summarizeBatchDelete(
            listOf(
                FileDeleteOutcome(id = 1L, deleted = false),
                FileDeleteOutcome(id = 2L, deleted = false),
            )
        )
        assertEquals(0, result.deletedCount)
        assertEquals(2, result.failedCount)
        assertTrue(result.deletedIds.isEmpty())
        assertEquals(listOf(1L, 2L), result.failedIds)
        assertFalse(result.allSucceeded)
    }
}
