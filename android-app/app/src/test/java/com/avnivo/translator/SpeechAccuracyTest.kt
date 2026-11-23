package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the reported quality numbers are real edit distances, never a rounded-up "perfect". */
class SpeechAccuracyTest {

    private val eps = 1e-9

    @Test
    fun identicalTextIsPerfect() {
        assertEquals(0.0, SpeechAccuracy.wer("salom dunyo", "Salom, dunyo!"), eps)
        assertEquals(0.0, SpeechAccuracy.cer("salom dunyo", "Salom, dunyo!"), eps)
    }

    @Test
    fun oneSubstitutionInTwoWordsIsHalf() {
        assertEquals(0.5, SpeechAccuracy.wer("salom dunyo", "salom dunya"), eps)
    }

    @Test
    fun insertionAndDeletionAreCounted() {
        assertEquals(1.0, SpeechAccuracy.wer("salom", "salom dunyo"), eps)
        assertEquals(0.5, SpeechAccuracy.wer("salom dunyo", "salom"), eps)
    }

    @Test
    fun characterErrorRateUsesCharacters() {
        assertEquals(0.2, SpeechAccuracy.cer("salom", "salam"), eps)
    }

    @Test
    fun anEmptyReferenceIsOnlyPerfectForAnEmptyHypothesis() {
        assertEquals(0.0, SpeechAccuracy.wer("", ""), eps)
        assertEquals(1.0, SpeechAccuracy.wer("", "salom"), eps)
        assertEquals(0.0, SpeechAccuracy.cer("", " "), eps)
        assertEquals(1.0, SpeechAccuracy.cer("", "salom"), eps)
    }

    @Test
    fun aWrongTranscriptIsNeverReportedAsPerfect() {
        val wer = SpeechAccuracy.wer("navoiy o'zbekcha matnni tabiiy ravon ovozga aylantiradi", "butunlay boshqa gap")
        val cer = SpeechAccuracy.cer("navoiy o'zbekcha matnni tabiiy ravon ovozga aylantiradi", "butunlay boshqa gap")
        assertTrue("WER must be positive for a wrong transcript: $wer", wer > 0.0)
        assertTrue("CER must be positive for a wrong transcript: $cer", cer > 0.0)
    }

    @Test
    fun levenshteinIsSymmetricForEqualLengthInputs() {
        assertEquals(
            SpeechAccuracy.levenshtein("abc".toList(), "abd".toList()),
            SpeechAccuracy.levenshtein("abd".toList(), "abc".toList())
        )
    }
}
