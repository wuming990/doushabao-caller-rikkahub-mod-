package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1017: a model generation step is not an individual tool call or a network retry. */
class GenerationStepStatsTest {
    @Test fun `new reply previews and completes at one`() {
        val stats = GenerationStepStats(null, null)
        assertEquals(1, stats.preview())
        assertEquals(1, stats.complete())
    }

    @Test fun `successive model generations accumulate rather than staying at one`() {
        val stats = GenerationStepStats(null, null)
        assertEquals(1, stats.complete())
        assertEquals(2, stats.preview())
        assertEquals(2, stats.complete())
        assertEquals(3, stats.complete())
    }

    @Test fun `multiple streaming chunks and transport retries do not add steps`() {
        val stats = GenerationStepStats(3, 7)
        repeat(10) { assertEquals(4, stats.preview()) }
        assertEquals(4, stats.complete())
    }

    @Test fun `accepted partial reply followed by continue starts a new step`() {
        val stats = GenerationStepStats(2, 2)
        assertEquals(3, stats.preview())
        stats.acceptInterruptedStep()
        assertEquals(4, stats.preview())
        assertEquals(4, stats.complete())
    }

    @Test fun `explicit official steps take priority over billing requests`() {
        assertEquals(3, displayedGenerationSteps(3, 8))
        assertEquals(4, GenerationStepStats(3, 8).complete())
    }

    @Test fun `legacy reply can use already recorded request count`() {
        assertEquals(5, displayedGenerationSteps(null, 5))
        assertEquals(6, GenerationStepStats(null, 5).complete())
    }

    @Test fun `unknown old history is not assigned imaginary extra steps`() {
        assertEquals(1, displayedGenerationSteps(null, null))
        assertEquals(1, displayedGenerationSteps(null, 0))
    }

    @Test fun `invalid old values do not make negative steps`() {
        assertEquals(1, displayedGenerationSteps(-2, 0))
        assertEquals(4, displayedGenerationSteps(0, 4))
        assertEquals(1, GenerationStepStats(-1, -2).complete())
    }

    @Test fun `a separate new user reply does not inherit previous answer steps`() {
        val previous = GenerationStepStats(8, 8)
        assertEquals(9, previous.complete())
        assertEquals(1, GenerationStepStats(null, null).complete())
    }

    private fun source(relative: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        return File(checkNotNull(directory), relative).readText()
    }

    @Test fun `display reads existing counts rather than fixed fallback one`() {
        val text = source("app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt")
        assertTrue(text.contains("steps = displayedGenerationSteps(stepCount, cumulativeRequestCount)"))
    }

    @Test fun `generation persists the count for streaming and nonstreaming completion`() {
        val text = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(text.contains("stepCount = completedStepCount,"))
        assertTrue(text.contains("val completedStepCount = stepStats.complete()"))
        assertTrue(text.contains("stepCount = stepStats.preview()"))
    }
}
