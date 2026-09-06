package com.example.navsync

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OfflineSearchLogicTest {

    data class MockPlace(val id: Long, val name: String, val address: String, val placeType: String)

    private fun scorePlace(query: String, place: MockPlace): Double {
        val qClean = query.trim()
        if (qClean.isEmpty()) return 0.0

        val qLower = qClean.lowercase(Locale.US)
        val tokens = qLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 1 }

        val nLower = place.name.lowercase(Locale.US)
        val aLower = place.address.lowercase(Locale.US)
        var score = 0.0

        if (nLower == qLower) {
            score += 1500.0
        } else if (nLower.startsWith(qLower)) {
            score += 850.0
        } else if (nLower.contains(qLower)) {
            score += 550.0
        } else if (aLower.contains(qLower)) {
            score += 250.0
        }

        val genericStopWords = setOf(
            "road", "rd", "street", "st", "lane", "ln", "avenue", "ave", "highway", "hwy",
            "marg", "chowk", "chawk", "circle", "bypass", "expressway", "cross",
            "near", "opp", "opposite", "behind", "beside", "at", "in", "the", "of", "and"
        )

        val distinctiveTokens = tokens.filter { it !in genericStopWords }
        if (distinctiveTokens.isNotEmpty()) {
            val hasAnyDistinctiveMatch = distinctiveTokens.any { t -> nLower.contains(t) || aLower.contains(t) }
            if (!hasAnyDistinctiveMatch) {
                return 0.0 // Reject candidates that only match generic stop-words like 'road'
            }
        }

        var matchedTokens = 0
        for (t in tokens) {
            if (nLower.contains(t)) {
                score += 180.0
                matchedTokens++
            } else if (aLower.contains(t)) {
                score += 80.0
                matchedTokens++
            }
        }
        if (tokens.isNotEmpty() && matchedTokens >= tokens.size) {
            score += 350.0
        }

        return score
    }

    @Test
    fun testDehuRoadSearchMatchesDehuRoadOnly() {
        val places = listOf(
            MockPlace(1L, "Pune Bharat Petroleum Pump", "Ring Road, Pune", "fuel"),
            MockPlace(2L, "Goodluck Cafe", "FC Road, Deccan, Pune", "food"),
            MockPlace(3L, "Fergusson College", "FC Road, Shivajinagar, Pune", "landmark"),
            MockPlace(4L, "Pune Airport", "Airport Road, Pune", "transit"),
            MockPlace(5L, "Dehu Road Railway Station", "Station Road, Dehu Road", "transit"),
            MockPlace(6L, "Dehu Cantonment Board", "Cantonment Area, Dehu Road", "landmark"),
            MockPlace(7L, "Dehu Road", "Cantonment & Town, Maval", "landmark")
        )

        val query = "dehu road"
        val scored = places.map { it to scorePlace(query, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }

        assertTrue("Must match Dehu Road items", scored.isNotEmpty())
        assertEquals("Top match must be Dehu Road", "Dehu Road", scored[0].first.name)
        assertTrue("Top match score must be very high", scored[0].second >= 1500.0)

        // Ensure completely unrelated Pune landmarks have score 0 and are excluded
        val matchedNames = scored.map { it.first.name }
        assertFalse("Must NOT contain Goodluck Cafe", matchedNames.contains("Goodluck Cafe"))
        assertFalse("Must NOT contain Fergusson College", matchedNames.contains("Fergusson College"))
        assertFalse("Must NOT contain Pune Bharat Petroleum Pump", matchedNames.contains("Pune Bharat Petroleum Pump"))
        assertFalse("Must NOT contain Pune Airport", matchedNames.contains("Pune Airport"))
    }

    @Test
    fun testUnmatchedQueryReturnsNoResultsWithoutFallback() {
        val places = listOf(
            MockPlace(1L, "Pune Bharat Petroleum Pump", "Ring Road, Pune", "fuel"),
            MockPlace(2L, "Goodluck Cafe", "FC Road, Deccan, Pune", "food"),
            MockPlace(3L, "Fergusson College", "FC Road, Shivajinagar, Pune", "landmark"),
            MockPlace(4L, "Pune Airport", "Airport Road, Pune", "transit")
        )

        val query = "nonexistent foreign landmark"
        val scored = places.map { it to scorePlace(query, it) }
            .filter { it.second > 0.0 }

        assertTrue("Unmatched query must return empty list without fallback", scored.isEmpty())
    }
}
