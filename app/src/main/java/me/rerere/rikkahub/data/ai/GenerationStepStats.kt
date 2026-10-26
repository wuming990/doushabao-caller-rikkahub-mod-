package me.rerere.rikkahub.data.ai

/**
 * v1017: model-generation steps, kept separate from billed request attempts.
 * Replayed network attempts preview the same step. An accepted partial answer followed
 * by a new continuation input starts another step without changing the billing ledger.
 */
internal class GenerationStepStats(
    previousStepCount: Int?,
    legacyRequestCount: Int?,
) {
    private val base = previousStepCount?.takeIf { it > 0 }
        ?: legacyRequestCount?.coerceAtLeast(0)
        ?: 0
    private var completed = 0

    fun preview(): Int = base + completed + 1

    fun complete(): Int {
        completed += 1
        return base + completed
    }

    fun acceptInterruptedStep() {
        completed += 1
    }
}

/** v1017: official step data wins; old mod messages can reuse their recorded requests. */
internal fun displayedGenerationSteps(stepCount: Int?, legacyRequestCount: Int?): Int =
    stepCount?.takeIf { it > 0 }
        ?: legacyRequestCount?.takeIf { it > 0 }
        ?: 1
