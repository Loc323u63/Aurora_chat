package com.aurora.music.data

import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
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
    @Test fun indexJournalCompactsRepeatedStatusWritesAndIgnoresAnIncompleteTail() {
        val directory = Files.createTempDirectory("genius-journal-test").toFile()
        try {
            val store = GeniusLyricsStore(directory)
            repeat(520) { index ->
                store.mark("same-song", if (index % 2 == 0) GeniusLyricsStatus.ERROR else GeniusLyricsStatus.PENDING)
            }
            val journal = File(directory, "genius-lyrics-index.jsonl")
            assertTrue(journal.isFile)
            assertTrue("Repeated status changes should compact the journal", journal.readLines().size < 20)

            // A process can stop between writing the JSON and its final newline.
            journal.appendText("{")
            val restored = GeniusLyricsStore(directory)
            assertEquals(GeniusLyricsStatus.PENDING, restored.record("same-song")?.status)
            restored.mark("another-song", GeniusLyricsStatus.NOT_FOUND)
            val reopened = GeniusLyricsStore(directory)
            assertEquals(GeniusLyricsStatus.PENDING, reopened.record("same-song")?.status)
            assertEquals(GeniusLyricsStatus.NOT_FOUND, reopened.record("another-song")?.status)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun migratesTheLegacyJsonIndexWithoutLosingStatuses() {
        val directory = Files.createTempDirectory("genius-legacy-index-test").toFile()
        try {
            val legacy = File(directory, "genius-lyrics-index.json")
            val oldRecord = GeniusLyricsRecord(
                status = GeniusLyricsStatus.SUCCESS,
                geniusUrl = "https://genius.com/example-song-lyrics",
                updatedAt = 123L,
            )
            legacy.writeText(Gson().toJson(linkedMapOf("legacy-song" to oldRecord)), Charsets.UTF_8)

            val migrated = GeniusLyricsStore(directory)
            assertEquals(oldRecord, migrated.record("legacy-song"))
            assertTrue(File(directory, "genius-lyrics-index.jsonl").isFile)
            assertFalse("The old index can be removed after a successful migration", legacy.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun boundedUtf8ReaderHonoursLimitWithoutContentLength() {
        val text = "Кот"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, readUtf8Limited(ByteArrayInputStream(bytes), bytes.size))

        try {
            readUtf8Limited(ByteArrayInputStream(bytes), bytes.size - 1)
            throw AssertionError("Expected an oversized response to be rejected")
        } catch (_: IOException) {
            // Expected: the reader requests at most maxBytes + 1 bytes before rejecting.
        }
    }

    @Test fun storeConstructionDoesNotCreateLyricsDirectory() {
        val directory = Files.createTempDirectory("genius-lazy-store-test").toFile()
        try {
            GeniusLyricsStore(directory)
            assertFalse(File(directory, "genius-lyrics").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

}
