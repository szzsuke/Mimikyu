package com.lin0721.linmusic.core.player.data
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.importer.TrackMatcher

import android.content.Context
import android.net.ConnectivityManager
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.lyrics.ExternalLyricsService
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.core.network.mapToAppError
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricParser
import com.lin0721.linmusic.core.player.innertube.AudioQuality
import com.lin0721.linmusic.core.player.innertube.InnerTubeXPlayer
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.qobuz.QobuzService
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.auth.UserPreferences
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

private const val TAG = "PlaybackRepositoryImpl"

/**
 * YouTube Music + Qobuz + LRCLIB playback repository.
 * Replaces the old NetEase-based playback pipeline entirely.
 *
 * Stream resolution chain:
 * 1. Try Qobuz lossless (FLAC) if title/artist/duration are available
 * 2. Fall back to InnerTubeXPlayer (YouTube Music Opus/AAC stream)
 *
 * Lyrics: fetched from lrclib.net (synced LRC)
 */
class PlaybackRepositoryImpl(
    private val context: Context,
    private val settingsPreferences: SettingsPreferences,
    private val qobuzService: QobuzService,
    private val externalLyricsService: ExternalLyricsService,
    private val userPreferences: UserPreferences? = null,
    private val playbackResolver: com.lin0721.linmusic.core.player.resolver.PlaybackResolver? = null,
    private val auroraApiClient: com.lin0721.linmusic.core.aurora.AuroraApiClient? = null
) : PlaybackRepository {

    init {
        InnerTubeXPlayer.initialize(context)
        YouTubeIdRegistry.initialize(context)
    }

    override fun getSongUrl(songId: Long): Flow<Result<String>> = flow {
        var videoId = YouTubeIdRegistry.getVideoId(songId)
        if (videoId.isNullOrBlank()) {
            val title = YouTubeIdRegistry.getTitle(songId)
            val artist = YouTubeIdRegistry.getArtist(songId)
            if (title.isNotBlank()) {
                val query = if (artist.isNotBlank()) "$title $artist" else title
                val searchRes = runCatching {
                    com.metrolist.innertube.YouTube.search(query, com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                }.getOrNull()
                val candidates = searchRes?.items?.filterIsInstance<com.metrolist.innertube.models.SongItem>() ?: emptyList()
                val parsed = com.lin0721.linmusic.core.importer.ParsedTrackInfo(
                    title = title,
                    artist = artist,
                    durationMs = (YouTubeIdRegistry.getDurationSec(songId) * 1000).toLong()
                )
                val firstSong = TrackMatcher.selectBestSong(parsed, candidates).selected
                if (firstSong != null) {
                    videoId = firstSong.id
                    val cleanT = com.lin0721.linmusic.core.metadata.cleanMetadataText(firstSong.title)
                    val cleanA = com.lin0721.linmusic.core.metadata.cleanMetadataText(firstSong.artists.joinToString(", ") { it.name })
                    val cleanAlb = com.lin0721.linmusic.core.metadata.cleanMetadataText(firstSong.album?.name ?: "")
                    val durSec = firstSong.duration ?: 210
                    val resolvedArtists = firstSong.artists.map { itArt ->
                        val aId = itArt.id?.let { id -> YouTubeIdRegistry.registerArtist(id, itArt.name) }
                            ?: YouTubeIdRegistry.registerArtistByName(itArt.name)
                        Artist(id = aId, name = com.lin0721.linmusic.core.metadata.cleanMetadataText(itArt.name))
                    }.ifEmpty {
                        if (cleanA.isNotBlank()) {
                            val aId = YouTubeIdRegistry.registerArtistByName(cleanA)
                            listOf(Artist(id = aId, name = cleanA))
                        } else emptyList()
                    }
                    val albumId = (firstSong.album?.id ?: cleanAlb).hashCode().toLong() and 0x7FFFFFFFL
                    val resolved = Track(
                        id = songId,
                        name = cleanT,
                        ar = resolvedArtists,
                        al = Album(id = albumId, name = cleanAlb, picUrl = firstSong.thumbnail ?: ""),
                        dt = durSec.toLong() * 1000L
                    )
                    YouTubeIdRegistry.updatePlaceholderTrack(songId, resolved, firstSong.id)
                }
            }
        }
        if (videoId.isNullOrBlank()) {
            AppLogger.w(TAG, "songId=$songId could not be resolved to a YouTube track")
            emit(Result.failure(AppError.BizError(-1, "Could not resolve audio stream for track")))
            return@flow
        }

        val title = YouTubeIdRegistry.getTitle(songId)
        val artist = YouTubeIdRegistry.getArtist(songId)
        val durationSec = YouTubeIdRegistry.getDurationSec(songId)

        if (playbackResolver != null) {
            val resolveResult = playbackResolver.resolve(
                videoId = videoId,
                songId = songId,
                title = title,
                artist = artist,
                durationSec = durationSec
            )
            resolveResult.onSuccess { resolvedData ->
                emit(Result.success(resolvedData.streamUrl))
            }.onFailure { error ->
                emit(Result.failure(mapToAppError(error)))
            }
            return@flow
        }

        // Direct InnerTube audio extraction
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val audioQuality = AudioQuality.AUTO

        val result = InnerTubeXPlayer.playerResponseForPlayback(
            videoId = videoId,
            audioQuality = audioQuality,
            connectivityManager = connectivityManager
        )

        result.onSuccess { playbackData ->
            AppLogger.i(TAG, "YouTube stream resolved: videoId=$videoId client=${playbackData.streamClient}")
            emit(Result.success(playbackData.streamUrl))
        }.onFailure { error ->
            AppLogger.e(TAG, "YouTube stream resolution failed for videoId=$videoId", error)
            emit(Result.failure(mapToAppError(error)))
        }
    }.catch { e ->
        AppLogger.e(TAG, "getSongUrl unexpected error", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getLyrics(songId: Long): Flow<Result<List<LyricLine>>> = flow {
        val title = YouTubeIdRegistry.getTitle(songId)
        val artist = YouTubeIdRegistry.getArtist(songId)
        val durationSec = YouTubeIdRegistry.getDurationSec(songId)
        val videoId = YouTubeIdRegistry.getVideoId(songId)

        val cleanTitle = cleanMetadataText(title)
        val cleanArtist = cleanMetadataText(artist)

        // 1. Check local TTML flash disk cache
        val diskFile = externalLyricsService.getDiskCacheFile(videoId, cleanTitle, cleanArtist)
        if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
            val diskLyrics = runCatching {
                val parsed = LyricParser.parseTtml(diskFile.readText())
                if (parsed.isNotEmpty()) parsed else null
            }.getOrNull()
            if (diskLyrics != null) {
                emit(Result.success(diskLyrics))
                return@flow
            }
        }

        // 2. Check local audio file sibling TTML or LRC
        val localUri = YouTubeIdRegistry.getTrack(songId)?.localUri
        if (!localUri.isNullOrBlank()) {
            val localParsed = readLocalSiblingLyrics(localUri)
            if (!localParsed.isNullOrEmpty()) {
                emit(Result.success(localParsed))
                return@flow
            }
        }

        if (cleanTitle.isBlank()) {
            AppLogger.w(TAG, "No title metadata for songId=$songId, cannot fetch lyrics")
            emit(Result.success(emptyList()))
            return@flow
        }

        val lines = externalLyricsService.fetchLyrics(cleanTitle, cleanArtist, durationSec, videoId)
        emit(Result.success(lines))
    }.catch { e ->
        AppLogger.e(TAG, "getLyrics failed", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getCachedLyrics(songId: Long): List<LyricLine>? {
        val title = YouTubeIdRegistry.getTitle(songId)
        val artist = YouTubeIdRegistry.getArtist(songId)
        val durationSec = YouTubeIdRegistry.getDurationSec(songId)
        val videoId = YouTubeIdRegistry.getVideoId(songId)
        val cleanTitle = cleanMetadataText(title)
        val cleanArtist = cleanMetadataText(artist)

        val diskFile = externalLyricsService.getDiskCacheFile(videoId, cleanTitle, cleanArtist)
        if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
            val diskLyrics = runCatching {
                val parsed = LyricParser.parseTtml(diskFile.readText())
                if (parsed.isNotEmpty()) parsed else null
            }.getOrNull()
            if (diskLyrics != null) return diskLyrics
        }

        val localUri = YouTubeIdRegistry.getTrack(songId)?.localUri
        if (!localUri.isNullOrBlank()) {
            val localParsed = readLocalSiblingLyrics(localUri)
            if (!localParsed.isNullOrEmpty()) return localParsed
        }

        if (cleanTitle.isBlank()) return null
        return externalLyricsService.getCached(cleanTitle, cleanArtist, durationSec, videoId)
    }

    private fun readLocalSiblingLyrics(uriStr: String): List<LyricLine>? = runCatching {
        if (uriStr.startsWith("file://") || uriStr.startsWith("/")) {
            val audioPath = uriStr.removePrefix("file://")
            val dot = audioPath.lastIndexOf('.')
            val base = if (dot > 0) audioPath.substring(0, dot) else audioPath
            val ttmlFile = File("$base.ttml")
            if (ttmlFile.exists()) {
                val parsed = LyricParser.parseTtml(ttmlFile.readText())
                if (parsed.isNotEmpty()) return@runCatching parsed
            }
            val lrcFile = File("$base.lrc")
            if (lrcFile.exists()) {
                val parsed = LyricParser.parseLrc(lrcFile.readText())
                if (parsed.isNotEmpty()) return@runCatching parsed
            }
        }
        null
    }.getOrNull()

    override fun getSimilarSongs(songId: Long): Flow<Result<List<Track>>> = flow {
        // ── Direct BitChord Radio Pipeline (InnerTube RDAMVM + BitChord QueueBuilder) ──
        val title = YouTubeIdRegistry.getTitle(songId)
        val artist = YouTubeIdRegistry.getArtist(songId)
        val videoId = YouTubeIdRegistry.getVideoId(songId)?.takeIf { it.isNotBlank() }
            ?: com.lin0721.linmusic.core.player.recommendation.InnerTubeRadioEngine.resolveVideoId(songId, title, artist)

        if (videoId.isNullOrBlank()) {
            emit(Result.success(emptyList()))
            return@flow
        }

        try {
            val candidates = com.lin0721.linmusic.core.player.recommendation.InnerTubeRadioEngine.getRadioCandidates(videoId, count = 35)
            val filtered = com.lin0721.linmusic.core.player.recommendation.BitChordQueueBuilder.extend(
                existing = emptyList(),
                candidates = candidates,
                limit = 20
            )

            val tracks = filtered.map { q ->
                val artists = com.lin0721.linmusic.core.player.recommendation.BitChordQueueBuilder.artistSet(q.artist).map { aName ->
                    val aId = YouTubeIdRegistry.registerArtistByName(aName)
                    com.lin0721.linmusic.core.model.Artist(id = aId, name = aName)
                }.ifEmpty {
                    val aId = YouTubeIdRegistry.registerArtistByName(q.artist)
                    listOf(com.lin0721.linmusic.core.model.Artist(id = aId, name = q.artist))
                }

                Track(
                    id = q.songId,
                    name = q.title,
                    ar = artists,
                    al = com.lin0721.linmusic.core.model.Album(
                        id = (q.album.orEmpty()).hashCode().toLong() and 0x7FFFFFFFL,
                        name = q.album.orEmpty(),
                        picUrl = q.coverUrl
                    ),
                    dt = (q.durationSec * 1000).toLong()
                )
            }
            emit(Result.success(tracks))
        } catch (e: Exception) {
            AppLogger.w(TAG, "getSimilarSongs failed for songId=$songId (videoId=$videoId)", e)
            emit(Result.success(emptyList()))
        }
    }

    override fun getIntelligenceSongs(songId: Long, playlistId: Long): Flow<Result<List<Track>>> = flow {
        // YouTube Music doesn't have an "intelligence" feature; delegate to similar songs
        getSimilarSongs(songId).collect { emit(it) }
    }

    override fun reportStartPlay(songId: Long): Flow<Result<Unit>> = flow {
        val track = YouTubeIdRegistry.getTrack(songId) ?: run {
            val title = YouTubeIdRegistry.getTitle(songId)
            if (title.isNotBlank()) {
                val artist = YouTubeIdRegistry.getArtist(songId)
                val album = YouTubeIdRegistry.getAlbumName(songId)
                val cover = YouTubeIdRegistry.getCoverUrl(songId)
                val dur = YouTubeIdRegistry.getDurationSec(songId)
                Track(
                    id = songId,
                    name = title,
                    ar = listOf(com.lin0721.linmusic.core.model.Artist(id = (artist.hashCode().toLong() and 0x7FFFFFFFL), name = artist)),
                    al = com.lin0721.linmusic.core.model.Album(name = album, picUrl = cover),
                    dt = dur.toLong() * 1000L
                )
            } else null
        }
        if (track != null && userPreferences != null) {
            userPreferences.recordTrackPlayed(track)
        }
        emit(Result.success(Unit))
    }

    override fun reportPlayEnd(songId: Long, playedSeconds: Long): Flow<Result<Unit>> = flow {
        // No-op for YouTube Music: no server-side play duration reporting
        emit(Result.success(Unit))
    }
}
