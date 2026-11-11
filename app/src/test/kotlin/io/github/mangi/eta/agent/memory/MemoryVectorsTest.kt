package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorsTest {
    @Test
    fun cosineIsOneForSameDirectionAndZeroForOrthogonalOrZeroVectors() {
        assertEquals(1.0, MemoryVectors.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)), 0.0001)
        assertEquals(0.0, MemoryVectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.0001)
        assertEquals(0.0, MemoryVectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0.0001)
        assertEquals(0.0, MemoryVectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f)), 0.0001)
        assertEquals(0.0, MemoryVectors.cosine(FloatArray(0), FloatArray(0)), 0.0001)
    }

    @Test
    fun topKSortsByScoreAndAppliesLimitAndThreshold() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf(
            "near" to floatArrayOf(0.9f, 0.1f),
            "exact" to floatArrayOf(1f, 0f),
            "orthogonal" to floatArrayOf(0f, 1f),
        )

        val ranked = MemoryVectors.topK(query, candidates, limit = 2, minScore = 0.5)

        assertEquals(listOf("exact", "near"), ranked.map { it.first })
        assertTrue(ranked[0].second >= ranked[1].second)
        assertTrue(MemoryVectors.topK(query, candidates, limit = 0).isEmpty())
        assertEquals(1, MemoryVectors.topK(query, candidates, limit = 2, minScore = 0.995).size)
    }

    @Test
    fun encodeDecodeRoundTripsThroughJson() {
        val vector = floatArrayOf(0.123456f, -1.5f, 0f)

        val decoded = MemoryVectors.decode(MemoryVectors.encode(vector))

        requireNotNull(decoded)
        assertEquals(vector.size, decoded.size)
        assertEquals(0.12346f, decoded[0], 0.00001f)
        assertEquals(-1.5f, decoded[1], 0.00001f)
    }

    @Test
    fun decodeRejectsEmptyOrInvalidArrays() {
        assertNull(MemoryVectors.decode(null))
        assertNull(MemoryVectors.decode(org.json.JSONArray()))
        assertNull(MemoryVectors.decode(org.json.JSONArray().put("nan-ish")))
    }
}
