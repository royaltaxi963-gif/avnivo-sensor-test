package com.avnivo.translator

/**
 * Text frontend for the Silero `v5_cis_base` TTS model, rebuilt from the model's own
 * `multi_acc_v3_package` so a Latin Uzbek string can be turned into the token ids the scripted
 * network expects.
 *
 * The network itself only accepts ids; all text handling lives in Python inside the package. For
 * plain text that path is short and fully reproducible, and both steps are verified against the
 * real model:
 *
 *  1. `convert_to_orig` maps Latin Uzbek letters onto the model's Cyrillic alphabet, matching the
 *     longest key first (`o'` before `o`, `sh` before `s`).
 *  2. `preprocess_tacotron` wraps the result in the sos/eos markers and maps every character
 *     through `symbol_to_id`.
 *
 * Tables are copied from the model, not guessed. `SileroTtsTokenizerTest` asserts them against
 * vectors produced by running the real model.
 */
internal object SileroTtsTokenizer {

    const val SOS = "|"
    const val EOS = "~"

    /** `uzb_saida` is the only Uzbek speaker in the MIT-licensed `v5_cis_base` package. */
    const val UZBEK_SPEAKER_ID = 46

    /**
     * Latin Uzbek to Cyrillic, one character at a time.
     *
     * The model's `ext_alph` also lists digraphs (`sh`, `ch`, `o'`), but its `convert_to_orig`
     * only ever looks up single characters, so those keys are unreachable and the digraphs are
     * NOT applied. `shahar` therefore becomes `s-ҳ-a-ҳ-a-r` ("сҳаҳар"), not "шаҳар". Matching the
     * model here is what keeps the audio identical; letters absent from this table (such as `c`)
     * pass through unchanged, as they do in the model.
     */
    private val TRANSLITERATION: Map<Char, Char> = mapOf(
        'a' to '\u0430', 'b' to '\u0431', 'd' to '\u0434', 'e' to '\u044D',
        'f' to '\u0444', 'g' to '\u0433', 'h' to '\u04B3', 'i' to '\u0438',
        'j' to '\u0436', 'k' to '\u043A', 'l' to '\u043B', 'm' to '\u043C',
        'n' to '\u043D', 'o' to '\u043E', 'p' to '\u043F', 'q' to '\u049B',
        'r' to '\u0440', 's' to '\u0441', 't' to '\u0442', 'u' to '\u0443',
        'v' to '\u0432', 'x' to '\u0445', 'y' to '\u0439', 'z' to '\u0437',
        '\'' to '\u044A'
    )

    private val SYMBOL_TO_ID: Map<Char, Int> = buildMap {
        put('_', 0); put('~', 1); put('|', 2); put('!', 3); put('\'', 4)
        put('+', 5); put(',', 6); put('-', 7); put('.', 8); put(':', 9)
        put(';', 10); put('?', 11); put('h', 12)
        put('\u0430', 13); put('\u0431', 14); put('\u0432', 15); put('\u0433', 16)
        put('\u0434', 17); put('\u0435', 18); put('\u0436', 19); put('\u0437', 20)
        put('\u0438', 21); put('\u0439', 22); put('\u043A', 23); put('\u043B', 24)
        put('\u043C', 25); put('\u043D', 26); put('\u043E', 27); put('\u043F', 28)
        put('\u0440', 29); put('\u0441', 30); put('\u0442', 31); put('\u0443', 32)
        put('\u0444', 33); put('\u0445', 34); put('\u0446', 35); put('\u0447', 36)
        put('\u0448', 37); put('\u0449', 38); put('\u044A', 39); put('\u044B', 40)
        put('\u044C', 41); put('\u044D', 42); put('\u044E', 43); put('\u044F', 44)
        put('\u0451', 45); put('\u0454', 46); put('\u0456', 47); put('\u0457', 48)
        put('\u0458', 49); put('\u045E', 50); put('\u0491', 51); put('\u0493', 52)
        put('\u0495', 53); put('\u0497', 54); put('\u0499', 55); put('\u049B', 56)
        put('\u049D', 57); put('\u049F', 58); put('\u04A3', 59); put('\u04A5', 60)
        put('\u04AB', 61); put('\u04AF', 62); put('\u04B1', 63); put('\u04B3', 64)
        put('\u04B7', 65); put('\u04B9', 66); put('\u04BB', 67); put('\u04D1', 68)
        put('\u04D7', 69); put('\u04D9', 70); put('\u04DD', 71); put('\u04DF', 72)
        put('\u04E3', 73); put('\u04E5', 74); put('\u04E7', 75); put('\u04E9', 76)
        put('\u04EF', 77); put('\u04F1', 78); put('\u04F3', 79); put('\u04F5', 80)
        put('\u04CF', 81); put('\u2014', 82); put('\u2026', 83); put(' ', 84)
    }

    /**
     * Latin Uzbek to the model's Cyrillic alphabet, matching the model's `convert_to_orig`:
     * a single-character lookup per character. See [TRANSLITERATION] for why digraphs are not used.
     */
    fun transliterate(text: String): String {
        val lower = text.lowercase()
        val out = StringBuilder(lower.length)
        for (char in lower) out.append(TRANSLITERATION[char] ?: char)
        return out.toString()
    }

    /**
     * Token ids for [text]: sos, one id per transliterated character, eos.
     *
     * Characters the model has no id for are dropped rather than substituted, which is what the
     * model itself does (`re.sub` over its symbol set before tokenizing): `choy` loses its `c` and
     * still speaks. Throws [IllegalArgumentException] when nothing is left to speak, matching the
     * model's own `ValueError` for empty input.
     */
    fun tokenize(text: String): IntArray {
        val ids = mutableListOf(SYMBOL_TO_ID.getValue(SOS[0]))
        var kept = 0
        for (char in transliterate(text)) {
            val id = SYMBOL_TO_ID[char] ?: continue
            ids += id
            if (char != ' ') kept++
        }
        require(kept > 0) { "Silero TTS has nothing to speak in \"$text\"" }
        ids += SYMBOL_TO_ID.getValue(EOS[0])
        return ids.toIntArray()
    }

    fun symbolId(char: Char): Int? = SYMBOL_TO_ID[char]
}
