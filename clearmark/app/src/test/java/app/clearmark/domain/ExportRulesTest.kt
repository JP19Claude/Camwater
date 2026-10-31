package app.clearmark.domain

import app.clearmark.domain.FfmpegFailureClassifier.Kind
import app.clearmark.domain.model.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportRulesTest {

    @Test
    fun `Speicherbedarf mit bekannter Dateigroesse`() {
        val video = VideoInfo(1920, 1080, sizeBytes = 100L * 1024 * 1024)
        assertEquals(150L * 1024 * 1024 + StorageEstimator.SAFETY_BYTES, StorageEstimator.requiredBytes(video))
    }

    @Test
    fun `Speicherbedarf ohne Dateigroesse ueber die Dauer`() {
        val video = VideoInfo(1920, 1080, durationMs = 9_500, sizeBytes = null)
        assertEquals(10 * StorageEstimator.FALLBACK_BYTES_PER_SECOND + StorageEstimator.SAFETY_BYTES, StorageEstimator.requiredBytes(video))
    }

    @Test
    fun `Fehler werden verstaendlichen Ursachen zugeordnet`() {
        assertEquals(Kind.NO_SPACE, FfmpegFailureClassifier.classify("av_interleaved_write_frame(): No space left on device"))
        assertEquals(Kind.UNREADABLE_INPUT, FfmpegFailureClassifier.classify("saf:3.mp4: Invalid data found when processing input"))
        assertEquals(Kind.UNREADABLE_INPUT, FfmpegFailureClassifier.classify("[mov,mp4] moov atom not found"))
        assertEquals(
            Kind.AUDIO_INCOMPATIBLE,
            FfmpegFailureClassifier.classify(
                "[mp4] Could not find tag for codec vorbis in stream #1, codec not currently supported in container",
            ),
        )
        assertEquals(Kind.OTHER, FfmpegFailureClassifier.classify("Error while filtering: Cannot allocate memory"))
        assertEquals(Kind.OTHER, FfmpegFailureClassifier.classify(null))
    }

    @Test
    fun `Zusammenfassung nimmt die letzten nicht-leeren Zeilen`() {
        val log = "Zeile 1\n\nZeile 2\n  Zeile 3  \nZeile 4\nZeile 5\n\n"
        assertEquals("Zeile 3\nZeile 4\nZeile 5", FfmpegFailureClassifier.summarize(log, maxLines = 3))
        assertNull(FfmpegFailureClassifier.summarize("  \n "))
    }
}
