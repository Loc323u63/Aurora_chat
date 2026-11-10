package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GeniusLyricsTest {
    @Test fun extractsOnlyGeniusLyricsContainersAndPreservesLineBreaks() {
        val directory = Files.createTempDirectory("genius-lyrics-test").toFile()
        try {
            val store = GeniusLyricsStore(directory)
            val client = GeniusLyricsClient(store, tokenProvider = { "test-token" })
            val html = """
                <html><body>
                  <div class="annotation">not lyrics</div>
                  <div data-lyrics-container="true">First line<br>Second line<div>Chorus</div></div>
                  <div data-lyrics-container="true">Final verse</div>
                </body></html>
            """.trimIndent()
            assertEquals("First line\nSecond line\nChorus\nFinal verse", client.extractLyrics(html))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun lyricsAndStatusSurviveStoreRecreationWithoutLoadingAllBodies() {
        val directory = Files.createTempDirectory("genius-store-test").toFile()
        try {
            val first = GeniusLyricsStore(directory)
            first.save("song-1", "https://genius.com/artist-title-lyrics", "Verse one\nVerse two")
            first.mark("song-2", GeniusLyricsStatus.NOT_FOUND)
            assertTrue(first.hasLyrics("song-1"))
            assertEquals(1, first.stats().success)
            assertEquals(1, first.stats().notFound)

            val restored = GeniusLyricsStore(directory)
            assertEquals("Verse one\nVerse two", restored.lyrics("song-1"))
            assertEquals(GeniusLyricsStatus.NOT_FOUND, restored.record("song-2")?.status)
            assertFalse(restored.hasLyrics("song-2"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun geniusCanBeUsedAsAnIndependentLyricsSource() = runBlocking {
        val song = Song("genius-test", "Track", "Artist", "Album", "", 120)
        val expected = Lyrics(listOf(LyricLine(-1, "From Genius")), false, "Genius")
        val repository = LyricsRepository(
            backendProvider = { null },
            lrclibEnabledProvider = { false },
            orderProvider = { listOf("genius") },
            geniusLyricsProvider = { expected },
        )
        assertEquals(expected, repository.lyricsFor(song))
    }

    @Test fun geniusIsIncludedInDefaultLyricsSourceOrder() {
        assertTrue(DEFAULT_LYRICS_SOURCES.contains("genius"))
        assertNotNull(DEFAULT_LYRICS_SOURCES.indexOf("genius").takeIf { it >= 0 })
    }
}
