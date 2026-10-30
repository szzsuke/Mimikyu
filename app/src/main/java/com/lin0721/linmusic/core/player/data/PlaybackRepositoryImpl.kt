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
        // ── PRIMARY: Try native Catalog Similar Tracks (Radio Algorithm) ──
        if (auroraApiClient != null) {
            try {
                val title = YouTubeIdRegistry.getTitle(songId)
                val artist = YouTubeIdRegistry.getArtist(songId)
                val query = if (artist.isNotBlank()) "$title $artist" else title
                if (query.isNotBlank()) {
                    val matched = auroraApiClient.searchTracks(query, limit = 1).firstOrNull()
                    if (matched != null) {
                        val similarCatalogTracks = auroraApiClient.getSimilarTracks(matched.id, limit = 20)
                        if (similarCatalogTracks.isNotEmpty()) {
                            val tracks = similarCatalogTracks.map { st ->
                                val cleanTitle = cleanMetadataText(st.title)
                                val cleanArtist = cleanMetadataText(st.author)
                                val cleanAlbum = cleanMetadataText(st.albumName ?: "")
                                val durationSec = (st.durationMs / 1000).toInt()
                                val coverUrl = st.artworkUrl ?: ""

                                val mappedArtists = st.author.split(Regex(""",\s*|/\s*""")).map { aName ->
                                    val cleanA = cleanMetadataText(aName)
                                    val aId = YouTubeIdRegistry.registerArtistByName(cleanA)
                                    com.lin0721.linmusic.core.model.Artist(id = aId, name = cleanA)
                                }.ifEmpty {
                                    val aId = YouTubeIdRegistry.registerArtistByName(cleanArtist)
                                    listOf(com.lin0721.linmusic.core.model.Artist(id = aId, name = cleanArtist))
                                }

                                val simiSongId = YouTubeIdRegistry.registerPlaceholder(
                                    title = cleanTitle,
                                    artist = cleanArtist,
                                    album = cleanAlbum,
                                    durationSec = durationSec,
                                    coverUrl = coverUrl,
                                    artists = mappedArtists
                                ).id

                                val track = Track(
                                    id = simiSongId,
                                    name = cleanTitle,
                                    ar = mappedArtists,
                                    al = com.lin0721.linmusic.core.model.Album(
                                        id = (st.albumId ?: cleanAlbum).hashCode().toLong() and 0x7FFFFFFFL,
                                        name = cleanAlbum,
                                        picUrl = coverUrl
                                    ),
                                    dt = st.durationMs
                                )
                                YouTubeIdRegistry.registerTrack(track)
                                track
                            }
                            emit(Result.success(tracks))
                            return@flow
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Spotify getSimilarTracks failed for songId=$songId: ${e.message}")
            }
        }

        // ── FALLBACK: Use YouTube Music's related/next endpoint for similar songs ──
        val videoId = YouTubeIdRegistry.getVideoId(songId)
        if (videoId == null) {
            emit(Result.success(emptyList()))
            return@flow
        }

        val tracks = try {
            val nextResult = com.metrolist.innertube.YouTube.next(
                endpoint = com.metrolist.innertube.models.WatchEndpoint(videoId = videoId)
            ).getOrThrow()

            val relatedSongs = nextResult.relatedEndpoint?.let { endpoint ->
                com.metrolist.innertube.YouTube.related(endpoint).getOrNull()?.songs
            }?.filter { it.id.isNotBlank() } ?: emptyList()

            val candidateSongs = if (relatedSongs.isNotEmpty()) relatedSongs else nextResult.items.filter { it.id.isNotBlank() }

            candidateSongs.mapNotNull { song ->
                try {
                    val cleanTitle = cleanMetadataText(song.title)
                    val artistName = cleanMetadataText(song.artists.joinToString(", ") { cleanMetadataText(it.name) })
                    val simiSongId = YouTubeIdRegistry.register(
                        videoId = song.id,
                        title = cleanTitle,
                        artist = artistName,
                        coverUrl = song.thumbnail.orEmpty(),
                        durationSec = song.duration ?: 0
                    )
                    Track(
                        id = simiSongId,
                        name = cleanTitle,
                        ar = song.artists.map { com.lin0721.linmusic.core.model.Artist(
                            id = (it.id ?: it.name).hashCode().toLong() and 0x7FFFFFFFL,
                            name = cleanMetadataText(it.name)
                        ) },
                        al = com.lin0721.linmusic.core.model.Album(
                            id = (song.album?.id ?: "").hashCode().toLong() and 0x7FFFFFFFL,
                            name = cleanMetadataText(song.album?.name ?: ""),
                            picUrl = song.thumbnail
                        ),
                        dt = (song.duration?.toLong() ?: 0L) * 1000L
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.w(TAG, "getSimilarSongs failed for videoId=$videoId", e)
            emptyList()
        }
        emit(Result.success(tracks))
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
