package com.avnivo.translator

/**
 * Word and character error rates for a real transcript.
 *
 * The point of these numbers is to report quality honestly: a perfect score is only possible when
 * the hypothesis really matches the reference. Nothing here rounds up or treats an empty
 * hypothesis as success.
 */
internal object SpeechAccuracy {
    private val nonWord = Regex("[^\\p{L}\\p{N}']+")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String =
        text.lowercase().replace(nonWord, " ").replace(spaces, " ").trim()

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Word error rate = edit distance in words / reference words. */
    fun wer(reference: String, hypothesis: String): Double {
        val ref = words(reference)
        if (ref.isEmpty()) return if (words(hypothesis).isEmpty()) 0.0 else 1.0
        return levenshtein(ref, words(hypothesis)).toDouble() / ref.size
    }

    /** Character error rate = edit distance in characters (spaces ignored) / reference characters. */
    fun cer(reference: String, hypothesis: String): Double {
        val ref = normalize(reference).replace(" ", "")
        val hyp = normalize(hypothesis).replace(" ", "")
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        return levenshtein(ref.toList(), hyp.toList()).toDouble() / ref.length
    }

    internal fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }
}
