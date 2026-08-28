package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locale-follow suggestion ranking (AlphabetIndex.suggestFrom).
 *
 * The original scoring multiplied the corpus tier INTO a max-score
 * (tier 1 beat tier 0), so every locale followed onto a WorldAlphabets
 * variant — which renders a flat, unweighted-looking canvas due to a
 * pre-existing engine bug in those files. These tests pin the corrected
 * ranking: maintained > worldalphabets > legacy, engine default first
 * within its tier, then fuller alphabets.
 */
class AlphabetSuggestionTest {

    private fun a(id: String, lang: String?, source: String, chars: Int = 30) =
        AlphabetInfo(id, "ltr", lang, null, null, chars, source)

    private val list = listOf(
        a("English with limited punctuation", "en", "maintained", chars = 61),
        a("English with accents, numerals, punctuation", "en", "maintained", chars = 115),
        a("English (WorldAlphabets)", "en", "worldalphabets", chars = 67),
        a("Arabic (WorldAlphabets)", "ar", "worldalphabets", chars = 87),
        a("Some Old English", "en", "legacy", chars = 40),
        a("Türkmen / Turkmen with punctuation and numerals", "tk", "legacy", chars = 45),
    )

    @Test fun `maintained beats worldalphabets for english`() {
        val s = AlphabetIndex.suggestFrom(list, "en-US")!!
        assertEquals("maintained", s.source)
    }

    @Test fun `engine default preferred within maintained tier`() {
        val s = AlphabetIndex.suggestFrom(list, "en")!!
        assertEquals(AlphabetIndex.ENGINE_DEFAULT_ALPHABET, s.id)
    }

    @Test fun `exact lang match wins for other languages`() {
        assertEquals("Arabic (WorldAlphabets)",
            AlphabetIndex.suggestFrom(list, "ar")!!.id)
    }

    @Test fun `legacy fallback still reachable`() {
        assertEquals("Türkmen / Turkmen with punctuation and numerals",
            AlphabetIndex.suggestFrom(list, "tk")!!.id)
    }

    @Test fun `unknown locale returns null`() {
        assertEquals(null, AlphabetIndex.suggestFrom(list, "xx"))
    }

    @Test fun `empty index returns null`() {
        assertEquals(null, AlphabetIndex.suggestFrom(emptyList(), "en"))
    }
}
