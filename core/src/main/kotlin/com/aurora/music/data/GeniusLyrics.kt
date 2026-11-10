
package com.aurora.music.data

import com.aurora.music.model.Song
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class GeniusLyricsStatus { PENDING, SUCCESS, NOT_FOUND, ERROR, RATE_LIMITED, BLOCKED }

data class GeniusLyricsRecord(
    val status: GeniusLyricsStatus = GeniusLyricsStatus.PENDING,
    val geniusUrl: String = "",
    val message: String = "",
    val updatedAt: Long = 0L,
)

data class GeniusLyricsStoreStats(
    val success: Int = 0,
    val notFound: Int = 0,
    val errors: Int = 0,
    val rateLimited: Int = 0,
    val blocked: Int = 0,
    val pending: Int = 0,
    val bytes: Long = 0L,
)

data class GeniusImportProgress(
    val total: Int = 0,
    val processed: Int = 0,
    val found: Int = 0,
    val notFound: Int = 0,
    val failed: Int = 0,
    val bytesStored: Long = 0L,
    val currentTrack: String = "",
    val running: Boolean = false,
    val rateLimited: Boolean = false,
    val blocked: Boolean = false,
    val message: String = "",
)

/**
 * Lyrics are written as individual UTF-8 files. The small index stores only statuses and Genius
 * URLs, so loading it never pulls the lyric contents for the whole library into memory.
 */
class GeniusLyricsStore(root: File) {
    private val directory = File(root, "genius-lyrics").apply { mkdirs() }
    private val indexFile = File(root, "genius-lyrics-index.json")
    private val gson = Gson()
    private val lock = Any()
    private val entries: LinkedHashMap<String, GeniusLyricsRecord> = loadIndex()

    private fun loadIndex(): LinkedHashMap<String, GeniusLyricsRecord> = runCatching {
        if (!indexFile.isFile) return LinkedHashMap()
        gson.fromJson<LinkedHashMap<String, GeniusLyricsRecord>>(
            indexFile.readText(Charsets.UTF_8),
            object : TypeToken<LinkedHashMap<String, GeniusLyricsRecord>>() {}.type,
        ) ?: LinkedHashMap()
    }.getOrDefault(LinkedHashMap())

    fun record(songId: String): GeniusLyricsRecord? = synchronized(lock) { entries[songId] }

    fun hasLyrics(songId: String): Boolean = synchronized(lock) {
        entries[songId]?.status == GeniusLyricsStatus.SUCCESS && lyricsFile(songId).isFile
    }

    fun hasLyrics(songId: String): Boolean = synchronized(lock) {
        entries[songId]?.status == GeniusLyricsStatus.SUCCESS && lyricsFile(songId).isFile
    }

    fun lyrics(songId: String): String? = synchronized(lock) {
        val record = entries[songId] ?: return@synchronized null
        if (record.status != GeniusLyricsStatus.SUCCESS) return@synchronized null
        val file = lyricsFile(songId)
        if (!file.isFile) return@synchronized null
        runCatching { file.readText(Charsets.UTF_8).takeIf(String::isNotBlank) }.getOrNull()
    }

    fun save(songId: String, url: String, text: String) = synchronized(lock) {
        require(songId.isNotBlank())
        require(text.isNotBlank())
        val target = lyricsFile(songId)
        target.parentFile?.mkdirs()
        persistBackupFileAtomically(target, text.toByteArray(Charsets.UTF_8))
        entries[songId] = GeniusLyricsRecord(GeniusLyricsStatus.SUCCESS, url, "", System.currentTimeMillis())
        persistIndex()
    }

    fun mark(songId: String, status: GeniusLyricsStatus, url: String = "", message: String = "") = synchronized(lock) {
        require(songId.isNotBlank())
        val previous = entries[songId]
        entries[songId] = GeniusLyricsRecord(
            status = status,
            geniusUrl = url.ifBlank { previous?.geniusUrl.orEmpty() },
            message = message.take(240),
            updatedAt = System.currentTimeMillis(),
        )
        if (status != GeniusLyricsStatus.SUCCESS) lyricsFile(songId).takeIf(File::exists)?.delete()
        persistIndex()
    }

    fun stats(): GeniusLyricsStoreStats = synchronized(lock) {
        val values = entries.values
        GeniusLyricsStoreStats(
            success = values.count { it.status == GeniusLyricsStatus.SUCCESS },
            notFound = values.count { it.status == GeniusLyricsStatus.NOT_FOUND },
            errors = values.count { it.status == GeniusLyricsStatus.ERROR },
            rateLimited = values.count { it.status == GeniusLyricsStatus.RATE_LIMITED },
            blocked = values.count { it.status == GeniusLyricsStatus.BLOCKED },
            pending = values.count { it.status == GeniusLyricsStatus.PENDING },
            bytes = directory.walkTopDown().filter(File::isFile).sumOf { it.length() },
        )
    }

    fun clear() = synchronized(lock) {
        directory.deleteRecursively()
        directory.mkdirs()
        entries.clear()
        indexFile.delete()
    }

    private fun persistIndex() {
        indexFile.parentFile?.mkdirs()
        persistBackupFileAtomically(indexFile, gson.toJson(entries).toByteArray(Charsets.UTF_8))
    }

    private fun lyricsFile(songId: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(songId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, hash + ".txt")
    }
}

sealed interface GeniusFetchResult {
    data class Found(val url: String, val lyrics: String) : GeniusFetchResult
    data object NotFound : GeniusFetchResult
    data class RateLimited(val retryAfterSeconds: Long?) : GeniusFetchResult
    data object Blocked : GeniusFetchResult
    data object MissingToken : GeniusFetchResult
    data class Failed(val message: String) : GeniusFetchResult
}

class GeniusLyricsClient(
    private val store: GeniusLyricsStore,
    private val tokenProvider: suspend () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun lyricsFor(song: Song): Lyrics? {
        store.lyrics(song.id)?.let { return toLyrics(it) }
        // A stored miss or refusal is not retried on each playback. The importer is the retry path.
        if (store.record(song.id) != null) return null
        val token = tokenProvider().trim()
        if (token.isBlank()) return null
        return when (val result = fetchAndStore(song, token)) {
            is GeniusFetchResult.Found -> toLyrics(result.lyrics)
            else -> null
        }
    }

    suspend fun fetchAndStore(song: Song, token: String): GeniusFetchResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext GeniusFetchResult.MissingToken
        try {
            val candidate = searchSong(song, token)
            if (candidate == null) {
                store.mark(song.id, GeniusLyricsStatus.NOT_FOUND)
                return@withContext GeniusFetchResult.NotFound
            }
            val page = requestPage(candidate.url)
            if (page.code == 429) {
                store.mark(song.id, GeniusLyricsStatus.RATE_LIMITED, candidate.url, "Genius returned HTTP 429")
                return@withContext GeniusFetchResult.RateLimited(page.retryAfter)
            }
            if (page.code == 403) {
                store.mark(song.id, GeniusLyricsStatus.BLOCKED, candidate.url, "Genius refused the request (HTTP 403)")
                return@withContext GeniusFetchResult.Blocked
            }
            if (page.code == 404) {
                store.mark(song.id, GeniusLyricsStatus.NOT_FOUND, candidate.url, "Song page no longer exists")
                return@withContext GeniusFetchResult.NotFound
            }
            if (page.code !in 200..299) throw IOException("Genius song page returned HTTP " + page.code)
            val lyrics = extractLyrics(page.body)
            if (lyrics == null) {
                store.mark(song.id, GeniusLyricsStatus.NOT_FOUND, candidate.url, "No lyrics container found on the page")
                return@withContext GeniusFetchResult.NotFound
            }
            store.save(song.id, candidate.url, lyrics)
            GeniusFetchResult.Found(candidate.url, lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniusHttpException) {
            when (e.code) {
                429 -> GeniusFetchResult.RateLimited(e.retryAfterSeconds).also {
                    store.mark(song.id, GeniusLyricsStatus.RATE_LIMITED, message = "Genius API returned HTTP 429")
                }
                403 -> GeniusFetchResult.Blocked.also {
                    store.mark(song.id, GeniusLyricsStatus.BLOCKED, message = "Genius refused the API request (HTTP 403)")
                }
                else -> GeniusFetchResult.Failed("Genius API returned HTTP " + e.code).also {
                    store.mark(song.id, GeniusLyricsStatus.ERROR, message = "HTTP " + e.code)
                }
            }
        } catch (e: Exception) {
            GeniusFetchResult.Failed(e.message?.take(200) ?: e.javaClass.simpleName).also {
                store.mark(song.id, GeniusLyricsStatus.ERROR, message = it.message)
            }
        }
    }

    private data class Candidate(val url: String, val score: Int)
    private data class Page(val code: Int, val body: String, val retryAfter: Long?)

    private fun searchSong(song: Song, token: String): Candidate? {
        val query = URLEncoder.encode((song.title + " " + song.artist).trim(), "UTF-8")
        val request = Request.Builder()
            .url("https://api.genius.com/search?q=" + query + "&per_page=5&page=1")
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .header("User-Agent", "Aurora Music")
            .build()
        val page = execute(request)
        if (page.code !in 200..299) throw GeniusHttpException(page.code, page.retryAfter)
        val root = JsonParser.parseString(page.body).asJsonObject
        val hits = root.getAsJsonObject("response")?.getAsJsonArray("hits") ?: return null
        return hits.mapNotNull { hit ->
            val result = hit.asJsonObject.getAsJsonObject("result") ?: return@mapNotNull null
            val title = result.get("title")?.asString.orEmpty()
            val artist = result.getAsJsonObject("primary_artist")?.get("name")?.asString.orEmpty()
            val url = result.get("url")?.asString.orEmpty()
            val score = matchScore(song, title, artist)
            if (score < 150 || !isGeniusSongUrl(url)) null else Candidate(url, score)
        }.maxByOrNull(Candidate::score)
    }

    private fun matchScore(song: Song, candidateTitle: String, candidateArtist: String): Int {
        val wantedTitle = normalize(song.title)
        val foundTitle = normalize(candidateTitle)
        val titleScore = when {
            wantedTitle.isBlank() || foundTitle.isBlank() -> 0
            wantedTitle == foundTitle -> 100
            foundTitle.startsWith(wantedTitle + " lyrics") -> 90
            else -> 0
        }
        if (titleScore == 0) return 0
        val wantedArtist = normalize(song.artist)
        val foundArtist = normalize(candidateArtist)
        val creditedArtists = (listOf(wantedArtist) + song.artist.split(
            Regex("(?i),|;|&|\\||/|\\b(?:feat\\.?|ft\\.?|featuring|with)\\b|\\s+[x×]\\s+")
        ).map(::normalize)).filter(String::isNotBlank).toSet()
        val artistScore = if (foundArtist.isNotBlank() && foundArtist in creditedArtists) 100 else 0
        return titleScore + artistScore
    }

    private fun normalize(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun isGeniusSongUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.host?.lowercase() in setOf("genius.com", "www.genius.com")
            && uri.path.orEmpty().startsWith("/")
    }.getOrDefault(false)

    private fun requestPage(url: String): Page {
        val request = Request.Builder().url(url).header("User-Agent", "Aurora Music").build()
        return execute(request)
    }

    private fun execute(request: Request): Page {
        http.newCall(request).execute().use { response ->
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_RESPONSE_BYTES) throw IOException("Genius response is too large")
            val body = response.body?.string().orEmpty()
            return Page(response.code, body, response.header("Retry-After")?.toLongOrNull())
        }
    }

    internal fun extractLyrics(html: String): String? {
        val document = Jsoup.parse(html)
        val containers = document.select("div[data-lyrics-container=true]")
        if (containers.isEmpty()) return null
        val text = containers.joinToString("\n") { renderLyricsBlock(it) }
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return text.takeIf { it.isNotBlank() }
    }

    private fun renderLyricsBlock(root: Element): String {
        val output = StringBuilder()
        val blockTags = setOf("div", "p", "section", "ul", "ol", "li")
        fun newline() {
            while (output.isNotEmpty() && output.last() == ' ') output.setLength(output.length - 1)
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
        }
        fun visit(node: Node) {
            when (node) {
                is TextNode -> output.append(node.wholeText)
                is Element -> {
                    val tag = node.normalName()
                    if (tag in setOf("script", "style", "noscript")) return
                    if (tag == "br") {
                        newline()
                    } else {
                        val block = tag in blockTags
                        if (block) newline()
                        node.childNodes().forEach(::visit)
                        if (block) newline()
                    }
                }
                else -> node.childNodes().forEach(::visit)
            }
        }
        root.childNodes().forEach(::visit)
        return output.toString().trim()
    }

    private fun toLyrics(text: String): Lyrics =
        Lyrics(text.lineSequence().map { LyricLine(-1, it) }.toList(), false, "Genius")

    private class GeniusHttpException(val code: Int, val retryAfterSeconds: Long?) :
        IOException("Genius HTTP " + code)

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}

class GeniusLyricsImporter(
    private val store: GeniusLyricsStore,
    private val client: GeniusLyricsClient,
    private val tokenProvider: suspend () -> String,
    private val songsProvider: suspend () -> List<Song>,
    private val delayBetweenTracksMs: Long = 3_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableProgress = MutableStateFlow(GeniusImportProgress())
    val progress: StateFlow<GeniusImportProgress> = mutableProgress.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runImport() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
    }

    private suspend fun runImport() {
        try {
            val token = tokenProvider().trim()
            if (token.isBlank()) {
                mutableProgress.value = mutableProgress.value.copy(running = false, message = "Add your Genius API token first.")
                return
            }
            val songs = songsProvider().distinctBy(Song::id).filter { it.id.isNotBlank() }
            val targets = songs.filter { song ->
                when (store.record(song.id)?.status) {
                    GeniusLyricsStatus.SUCCESS -> !store.hasLyrics(song.id)
                    GeniusLyricsStatus.NOT_FOUND -> false
                    else -> true
                }
            }
            var found = songs.count { store.hasLyrics(it.id) }
            var notFound = songs.count { store.record(it.id)?.status == GeniusLyricsStatus.NOT_FOUND }
            var processed = found + notFound
            var failed = 0
            mutableProgress.value = GeniusImportProgress(
                total = songs.size,
                processed = processed,
                found = found,
                notFound = notFound,
                failed = failed,
                bytesStored = store.stats().bytes,
                running = true,
                message = "Importing lyrics from Genius",
            )
            var consecutiveErrors = 0
            for ((index, song) in targets.withIndex()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                mutableProgress.update { it.copy(currentTrack = song.artist + " — " + song.title) }
                store.mark(song.id, GeniusLyricsStatus.PENDING)
                when (val result = client.fetchAndStore(song, token)) {
                    is GeniusFetchResult.Found -> { found++; processed++; consecutiveErrors = 0 }
                    GeniusFetchResult.NotFound -> { notFound++; processed++; consecutiveErrors = 0 }
                    is GeniusFetchResult.RateLimited -> {
                        failed++; processed++
                        mutableProgress.update {
                            it.copy(processed = processed, found = found, notFound = notFound,
                                failed = failed, bytesStored = store.stats().bytes,
                                rateLimited = true, message = result.retryAfterSeconds?.let { seconds ->
                                    "Genius rate limit. Retry after " + seconds + " seconds."
                                } ?: "Genius rate limit. Import stopped.")
                        }
                        return
                    }
                    GeniusFetchResult.Blocked -> {
                        failed++; processed++
                        mutableProgress.update {
                            it.copy(processed = processed, found = found, notFound = notFound,
                                failed = failed, bytesStored = store.stats().bytes,
                                blocked = true, message = "Genius refused the request (HTTP 403). Import stopped.")
                        }
                        return
                    }
                    GeniusFetchResult.MissingToken -> {
                        mutableProgress.update { it.copy(message = "Add your Genius API token first.") }
                        return
                    }
                    is GeniusFetchResult.Failed -> { failed++; processed++; consecutiveErrors++ }
                }
                mutableProgress.update {
                    it.copy(processed = processed, found = found, notFound = notFound,
                        failed = failed, bytesStored = store.stats().bytes)
                }
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    mutableProgress.update { it.copy(message = "Import paused after repeated network errors.") }
                    return
                }
                if (index < targets.lastIndex && delayBetweenTracksMs > 0) delay(delayBetweenTracksMs)
            }
            mutableProgress.update { it.copy(message = "Genius import finished.") }
        } catch (e: CancellationException) {
            mutableProgress.update { it.copy(message = "Import stopped. Start again to resume.") }
            throw e
        } catch (e: Exception) {
            mutableProgress.update { it.copy(message = e.message?.take(200) ?: "Genius import failed.") }
        } finally {
            mutableProgress.update { it.copy(running = false, currentTrack = "") }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }
}
