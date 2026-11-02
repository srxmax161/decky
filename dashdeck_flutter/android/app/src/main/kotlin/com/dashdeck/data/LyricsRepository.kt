package com.dashdeck.data

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Time-synced lyrics from LRCLIB (https://lrclib.net, an open lyrics database) and NetEase Cloud Music,
 * asked at the same time so a slow or overloaded one doesn't hold up the other. Found lyrics are kept
 * on the phone, so a song heard before shows instantly and offline.
 */
class LyricsRepository(private val hub: TelemetryHub, private val scope: CoroutineScope, filesDir: File) {
    private val cache = object : LinkedHashMap<String, Lyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?) = size > CACHE_SIZE
    }
    private val disk = File(filesDir, "lyrics")
    private var job: Job? = null

    fun onTrack(track: NowPlaying?) {
        if (track == null || track.title.isBlank()) {
            job?.cancel()
            if (hub.state.value.lyrics != null) hub.update { it.copy(lyrics = null) }
            return
        }
        val key = track.trackKey
        if (hub.state.value.lyrics?.trackKey == key) return
        cache[key]?.let { cached ->
            hub.update { it.copy(lyrics = cached) }
            return
        }
        job?.cancel()
        hub.update { it.copy(lyrics = Lyrics(key, Lyrics.Status.LOADING)) }
        job = scope.launch {
            var result = withContext(Dispatchers.IO) { load(key) } ?: lookUp(track)
            // Both services failing usually means a dead spot or a busy server; keep trying for a while.
            for (wait in RETRY_DELAYS_MS) {
                if (result.status != Lyrics.Status.ERROR) break
                delay(wait)
                result = lookUp(track)
            }
            Log.i("DashDeck", "Lyrics for '${track.title}': ${result.status}")
            if (result.status != Lyrics.Status.ERROR) {
                cache[key] = result
                withContext(Dispatchers.IO) { save(result) }
            }
            hub.update { if (it.nowPlaying?.trackKey == key) it.copy(lyrics = result) else it }
        }
    }

    /** Asks every source at once; the first synced result wins, otherwise the most useful answer. */
    private suspend fun lookUp(track: NowPlaying): Lyrics {
        // Not children of this coroutine: a source stuck on a slow connection mustn't delay the winner.
        val pending = mutableListOf<Deferred<Lyrics>>(
            scope.async(Dispatchers.IO) { attempt("LRCLIB", track) { fromLrclib(track) } },
            scope.async(Dispatchers.IO) { attempt("NetEase", track) { fromNetease(track) } },
        )
        val answers = mutableListOf<Lyrics>()
        while (pending.isNotEmpty()) {
            val (done, lyrics) = select { pending.forEach { d -> d.onAwait { d to it } } }
            pending.remove(done)
            if (lyrics.status == Lyrics.Status.SYNCED) return lyrics
            answers += lyrics
        }
        return answers.minBy { PREFERENCE.indexOf(it.status) }
    }

    private fun attempt(source: String, track: NowPlaying, block: () -> Lyrics): Lyrics = runCatching(block)
        .onSuccess { Log.i("DashDeck", "$source: ${it.status} for '${track.title}'") }
        .getOrElse {
            Log.w("DashDeck", "$source lyrics lookup failed for '${track.title}': $it")
            Lyrics(track.trackKey, Lyrics.Status.ERROR)
        }

    // --- LRCLIB ---

    private fun fromLrclib(track: NowPlaying): Lyrics {
        // The exact lookup needs album and duration; search covers players that don't report them.
        val exact = if (track.album.isNotBlank() && track.durationMs > 0) {
            // A failure here (often "server busy") shouldn't stop the search below from trying.
            runCatching {
                lrclib(
                    "get",
                    "track_name" to track.title,
                    "artist_name" to track.artist,
                    "album_name" to track.album,
                    "duration" to (track.durationMs / 1000.0).roundToLong().toString(),
                )?.jsonObject
            }.getOrNull()
        } else {
            null
        }
        val match = exact?.takeIf { it.hasLyrics() } ?: lrclibSearch(track)
        return match?.let { parseLrclib(track.trackKey, it) } ?: Lyrics(track.trackKey, Lyrics.Status.NOT_FOUND)
    }

    private fun lrclibSearch(track: NowPlaying): JsonObject? {
        val results = (lrclib("search", "track_name" to clean(track.title), "artist_name" to track.artist) as? JsonArray)
            ?.map { it.jsonObject }
            .orEmpty()
        return results.firstOrNull { it.string("syncedLyrics") != null }
            ?: results.firstOrNull { it.hasLyrics() }
    }

    private fun parseLrclib(key: String, json: JsonObject): Lyrics {
        if (json["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return Lyrics(key, Lyrics.Status.INSTRUMENTAL)
        json.string("syncedLyrics")?.let { synced ->
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(key, Lyrics.Status.SYNCED, lines = lines)
        }
        json.string("plainLyrics")?.let { return Lyrics(key, Lyrics.Status.PLAIN, plain = it) }
        return Lyrics(key, Lyrics.Status.NOT_FOUND)
    }

    private fun lrclib(endpoint: String, vararg params: Pair<String, String>): JsonElement? =
        // LRCLIB asks clients to identify themselves.
        get("https://lrclib.net/api/$endpoint?" + query(*params), "DashDeck/1.0 (personal Android Auto dashboard)")

    // --- NetEase Cloud Music ---

    private fun fromNetease(track: NowPlaying): Lyrics {
        val key = track.trackKey
        val search = get(
            "https://music.163.com/api/cloudsearch/pc?" +
                query("s" to "${clean(track.title)} ${track.artist}".trim(), "type" to "1", "limit" to "10"),
            BROWSER,
        )
        val songs = search?.jsonObject?.get("result")?.jsonObject?.get("songs")?.jsonArray?.map { it.jsonObject }.orEmpty()
        val song = bestNeteaseMatch(track, songs) ?: return Lyrics(key, Lyrics.Status.NOT_FOUND)
        val id = song["id"]?.jsonPrimitive?.longOrNull ?: return Lyrics(key, Lyrics.Status.NOT_FOUND)

        val json = get("https://music.163.com/api/song/lyric?" + query("id" to id.toString(), "lv" to "1"), BROWSER)
            ?.jsonObject ?: return Lyrics(key, Lyrics.Status.NOT_FOUND)
        if (json["nolyric"]?.jsonPrimitive?.booleanOrNull == true) return Lyrics(key, Lyrics.Status.INSTRUMENTAL)
        val text = json["lrc"]?.jsonObject?.string("lyric") ?: return Lyrics(key, Lyrics.Status.NOT_FOUND)
        val lines = parseLrc(text)
        return when {
            lines.any { it.text.isNotBlank() } -> Lyrics(key, Lyrics.Status.SYNCED, lines = lines)
            text.isNotBlank() -> Lyrics(key, Lyrics.Status.PLAIN, plain = text)
            else -> Lyrics(key, Lyrics.Status.NOT_FOUND)
        }
    }

    /** NetEase search is loose, so only accept a song whose title, artist and length agree. */
    private fun bestNeteaseMatch(track: NowPlaying, songs: List<JsonObject>): JsonObject? {
        val title = normalize(clean(track.title))
        val artists = track.artist.split(',', '&', '/', ';').map(::normalize).filter { it.isNotEmpty() }
        fun JsonObject.titleMatches(): Boolean {
            val name = normalize(clean(string("name").orEmpty()))
            return name.isNotEmpty() && (name.contains(title) || title.contains(name))
        }
        fun JsonObject.artistMatches(): Boolean {
            val names = this["ar"]?.jsonArray?.mapNotNull { it.jsonObject.string("name")?.let(::normalize) }.orEmpty()
            return artists.isEmpty() || names.any { n -> artists.any { a -> n.contains(a) || a.contains(n) } }
        }
        fun JsonObject.durationOff(): Long {
            val ms = this["dt"]?.jsonPrimitive?.longOrNull ?: return Long.MAX_VALUE
            return if (track.durationMs > 0) abs(ms - track.durationMs) else 0
        }
        val candidates = songs.filter { it.titleMatches() && it.artistMatches() }
        return candidates.filter { it.durationOff() <= DURATION_SLACK_MS }.minByOrNull { it.durationOff() }
            ?: candidates.firstOrNull().takeIf { track.durationMs <= 0 }
    }

    // --- Saved lyrics ---

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(disk, "$hash.txt")
    }

    /** Format: the status on the first line, then "timeMs<TAB>text" lines (synced) or the plain text. */
    private fun save(lyrics: Lyrics) = runCatching {
        if (lyrics.status == Lyrics.Status.NOT_FOUND) return@runCatching // might turn up later
        disk.mkdirs()
        val body = when (lyrics.status) {
            Lyrics.Status.SYNCED -> lyrics.lines.joinToString("\n") { "${it.timeMs}\t${it.text}" }
            Lyrics.Status.PLAIN -> lyrics.plain.orEmpty()
            else -> ""
        }
        fileFor(lyrics.trackKey).writeText(lyrics.status.name + "\n" + body)
    }

    private fun load(key: String): Lyrics? = runCatching {
        val file = fileFor(key)
        if (!file.exists()) return@runCatching null
        val text = file.readText()
        val status = Lyrics.Status.valueOf(text.substringBefore('\n'))
        val body = text.substringAfter('\n', "")
        when (status) {
            Lyrics.Status.SYNCED -> Lyrics(key, status, lines = body.lines().mapNotNull { line ->
                val time = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                LyricLine(time, line.substringAfter('\t'))
            })
            Lyrics.Status.PLAIN -> Lyrics(key, status, plain = body)
            else -> Lyrics(key, status)
        }
    }.getOrNull()

    // --- HTTP ---

    private fun get(url: String, userAgent: String): JsonElement? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            when (connection.responseCode) {
                200 -> Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                else -> error("HTTP ${connection.responseCode} from ${connection.url.host}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun query(vararg params: Pair<String, String>) = params.joinToString("&") { (k, v) -> k + "=" + Uri.encode(v) }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.hasLyrics() = string("syncedLyrics") != null || string("plainLyrics") != null ||
        this["instrumental"]?.jsonPrimitive?.booleanOrNull == true

    companion object {
        private const val CACHE_SIZE = 50
        private const val TIMEOUT_MS = 6_000
        private const val DURATION_SLACK_MS = 5_000L
        private const val BROWSER = "Mozilla/5.0 (Linux; Android 16) DashDeck/1.0"
        private val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L, 60_000L)

        /** Which answer to show when no source has synced lyrics; an error is retried, "not found" isn't. */
        private val PREFERENCE = listOf(
            Lyrics.Status.SYNCED, Lyrics.Status.PLAIN, Lyrics.Status.INSTRUMENTAL, Lyrics.Status.ERROR,
            Lyrics.Status.NOT_FOUND, Lyrics.Status.LOADING,
        )

        /** `[mm:ss.xx]`, and NetEase's occasional `[mm:ss:xx]`. */
        private val TIME_TAG = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
        private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val SUFFIX = Regex("""\s+-\s+.*\b(remaster|version|live|edit|mix|mono|stereo)\b.*""", RegexOption.IGNORE_CASE)

        /** NetEase opens with credits such as "作词 : Name" or "Producer: Name". */
        private val CREDIT = Regex("""^[^:：]{1,24}\s*[:：]\s*\S.*""")
        private const val CREDITS_UNTIL_MS = 30_000L

        /** "Song (feat. X) - 2011 Remaster" → "Song", for the fuzzy search. */
        fun clean(title: String) = title.replace(SUFFIX, "").replace(BRACKETS, "").trim().ifEmpty { title }

        private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** Parses `[mm:ss.xx] text` lines; a line may carry several time tags. */
        fun parseLrc(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { raw ->
            val tags = TIME_TAG.findAll(raw).toList()
            if (tags.isEmpty()) return@flatMap emptySequence()
            val text = raw.substring(tags.last().range.last + 1).trim()
            tags.asSequence().map { tag ->
                val minutes = tag.groupValues[1].toLong()
                val seconds = tag.groupValues[2].toLong()
                val fraction = tag.groupValues[3].padEnd(3, '0').take(3).toLong()
                LyricLine(minutes * 60_000 + seconds * 1000 + fraction, text)
            }
        }.filterNot { it.timeMs < CREDITS_UNTIL_MS && CREDIT.matches(it.text) }
            .sortedBy { it.timeMs }
            .toList()
    }
}
