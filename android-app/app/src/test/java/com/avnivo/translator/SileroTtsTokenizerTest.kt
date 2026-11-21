package com.avnivo.translator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ground truth here was produced by running the real Silero `v5_cis_base` package
 * (`apply_tts(text=..., speaker='uzb_saida', lang='uzb')`) and recording the ids the network
 * actually consumed. Each expected id array below is therefore a fact about the model, not a
 * restatement of the Kotlin implementation.
 */
class SileroTtsTokenizerTest {

    private val vectors = mapOf(
        "salom" to Pair("салом", intArrayOf(2, 30, 13, 24, 27, 25, 1)),
        "xayr" to Pair("хайр", intArrayOf(2, 34, 13, 22, 29, 1)),
        "rahmat" to Pair("раҳмат", intArrayOf(2, 29, 13, 64, 25, 13, 31, 1)),
        "O'zbekiston" to Pair("оъзбэкистон", intArrayOf(2, 27, 39, 20, 14, 42, 23, 21, 30, 31, 27, 26, 1)),
        "yaxshi" to Pair("йахсҳи", intArrayOf(2, 22, 13, 34, 30, 64, 21, 1)),
        "shahar" to Pair("сҳаҳар", intArrayOf(2, 30, 64, 13, 64, 13, 29, 1)),
        // `c` has no id: the model transliterates it unchanged ("cҳой") and drops it at
        // tokenizing time, so the transliteration and the ids intentionally differ here.
        "choy" to Pair("cҳой", intArrayOf(2, 64, 27, 22, 1))
    )

    @Test
    fun `transliteration matches the model for every vector`() {
        vectors.forEach { (latin, expected) ->
            assertEquals("transliteration of $latin", expected.first, SileroTtsTokenizer.transliterate(latin))
        }
    }

    @Test
    fun `token ids match the model for every vector`() {
        vectors.forEach { (latin, expected) ->
            assertArrayEquals("ids for $latin", expected.second, SileroTtsTokenizer.tokenize(latin))
        }
    }

    /**
     * The model looks up single characters only, so the `sh`/`ch`/`o'` digraphs in its `ext_alph`
     * are never applied. Asserting this stops a future "cleanup" from making the tokens diverge.
     */
    @Test
    fun `digraphs are not applied because the model applies none`() {
        assertEquals("сҳаҳар", SileroTtsTokenizer.transliterate("shahar"))
        assertEquals("ҳой", SileroTtsTokenizer.transliterate("choy").filter { it != 'c' })
    }

    @Test
    fun `sos and eos wrap every token sequence`() {
        val ids = SileroTtsTokenizer.tokenize("salom")
        assertEquals(SileroTtsTokenizer.symbolId('|'), ids.first())
        assertEquals(SileroTtsTokenizer.symbolId('~'), ids.last())
    }

    /** The model strips characters it has no id for and still speaks, so `choy` keeps its `ҳой`. */
    @Test
    fun `characters without an id are dropped not substituted`() {
        assertArrayEquals(intArrayOf(2, 64, 27, 22, 1), SileroTtsTokenizer.tokenize("choy"))
        assertTrue("c must have no id", SileroTtsTokenizer.symbolId('c') == null)
    }

    /** Nothing speakable must fail loudly rather than emit silence as if it were audio. */
    @Test
    fun `text with no speakable characters is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SileroTtsTokenizer.tokenize("c") }
        assertThrows(IllegalArgumentException::class.java) { SileroTtsTokenizer.tokenize("   ") }
    }

    @Test
    fun `uzbek speaker id is the one the model exposes`() {
        assertEquals(46, SileroTtsTokenizer.UZBEK_SPEAKER_ID)
    }

    /** Uppercase input must reach the same ids, because the model lowercases before converting. */
    @Test
    fun `casing does not change the ids`() {
        assertArrayEquals(SileroTtsTokenizer.tokenize("salom"), SileroTtsTokenizer.tokenize("SALOM"))
        assertArrayEquals(SileroTtsTokenizer.tokenize("salom"), SileroTtsTokenizer.tokenize("Salom"))
    }
}
