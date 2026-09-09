package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ScopedCustomBodiesTest {
    private val providerA = Uuid.random()
    private val providerB = Uuid.random()

    private fun body(key: String, providerIds: Set<Uuid>? = null, excluded: Set<Uuid>? = null) =
        CustomBody(key = key, value = JsonPrimitive("v"), providerIds = providerIds, excludedProviderIds = excluded)

    @Test
    fun `entries without any scope are sent to every provider`() {
        val bodies = listOf(body("plain"))

        val result = scopedCustomBodies(
            assistantBodies = bodies,
            modelBodies = emptyList(),
            providerIds = setOf(providerB),
        )

        assertEquals(listOf("plain"), result.map { it.key })
    }

    @Test
    fun `excluded provider does not receive the entry`() {
        val bodies = listOf(body("no_a", excluded = setOf(providerA)))

        assertEquals(
            emptyList<String>(),
            scopedCustomBodies(bodies, emptyList(), setOf(providerA)).map { it.key },
        )
        assertEquals(
            listOf("no_a"),
            scopedCustomBodies(bodies, emptyList(), setOf(providerB)).map { it.key },
        )
    }

    @Test
    fun `exclusion also matches the effective override provider`() {
        val bodies = listOf(body("no_b", excluded = setOf(providerB)))

        // owning provider = A, effective (overridden) provider = B -> excluded
        assertEquals(
            emptyList<String>(),
            scopedCustomBodies(bodies, emptyList(), setOf(providerA, providerB)).map { it.key },
        )
    }

    @Test
    fun `legacy whitelist entries still only go to selected providers`() {
        val bodies = listOf(body("only_a", providerIds = setOf(providerA)))

        assertEquals(
            listOf("only_a"),
            scopedCustomBodies(bodies, emptyList(), setOf(providerA)).map { it.key },
        )
        assertEquals(
            emptyList<String>(),
            scopedCustomBodies(bodies, emptyList(), setOf(providerB)).map { it.key },
        )
    }

    @Test
    fun `assistant and model entries are merged with assistant first`() {
        val result = scopedCustomBodies(
            assistantBodies = listOf(body("assistant"), body("skipped", excluded = setOf(providerA))),
            modelBodies = listOf(body("model")),
            providerIds = setOf(providerA),
        )

        assertEquals(listOf("assistant", "model"), result.map { it.key })
    }
}
