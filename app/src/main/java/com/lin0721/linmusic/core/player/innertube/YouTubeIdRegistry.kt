package com.lin0721.linmusic.core.player.innertube

import android.content.Context
import android.content.SharedPreferences
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional registry mapping Mimikyu's Long songId ↔ YouTube video ID string.
 * Backed by both fast in-memory ConcurrentHashMaps and persistent SharedPreferences,
 * guaranteeing mappings survive process death when the app is cleared from RAM.
 */
object YouTubeIdRegistry {

    private val idToVideoId = ConcurrentHashMap<Long, String>()
    private val videoIdToId = ConcurrentHashMap<String, Long>()

    // Additional metadata for lyrics/Qobuz resolution
    private val idToTitle = ConcurrentHashMap<Long, String>()
    private val idToArtist = ConcurrentHashMap<Long, String>()
    private val idToDurationSec = ConcurrentHashMap<Long, Int>()
    private val idToAlbumName = ConcurrentHashMap<Long, String>()
    private val idToCoverUrl = ConcurrentHashMap<Long, String>()
    private val idToReleaseDate = ConcurrentHashMap<Long, String>()
    private val idToGenre = ConcurrentHashMap<Long, String>()
    private val idToArtists = ConcurrentHashMap<Long, List<Artist>>()
    private val idToTrack = ConcurrentHashMap<Long, Track>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("mimikyu_youtube_registry", Context.MODE_PRIVATE)
        }
    }

    fun sanitizeVideoId(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val regex = Regex("""(?:v=|youtu\.be/|embed/|shorts/|^)([a-zA-Z0-9_-]{11})(?:[?&#]|$)""")
        val match = regex.find(trimmed)
        return match?.groupValues?.get(1) ?: if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) trimmed else trimmed
    }

    /**
     * Register a YouTube video ID and return its deterministic Long songId.
     * Sanitizes title and artist with cleanMetadataText.
     */
    fun register(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationSec: Int = 0,
        album: String = "",
        coverUrl: String = "",
        artists: List<Artist> = emptyList()
    ): Long {
        val cleanVid = sanitizeVideoId(videoId)
        val (extractedTitle, extractedArtist) = com.lin0721.linmusic.core.metadata.extractCleanTitleAndArtist(title, artist)
        val cleanT = cleanMetadataText(extractedTitle)
        val cleanA = cleanMetadataText(extractedArtist)
        val cleanAlb = cleanMetadataText(album)
        val cachedStudio = com.lin0721.linmusic.core.metadata.SongArtworkEngine.getCachedCoverArt(cleanT, cleanA)
        val effectiveCover = cachedStudio ?: coverUrl
        val optimizedCover = if (effectiveCover.isNotBlank()) {
            com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(effectiveCover, 1200)
        } else ""

        val existing = videoIdToId[cleanVid] ?: prefs?.getLong("v2id_$cleanVid", -1L)?.takeIf { it != -1L }
        val songId = existing ?: (cleanVid.hashCode().toLong() and 0x7FFFFFFFL)

        val existingCover = idToCoverUrl[songId]
        val shouldPreserveExistingCover = !existingCover.isNullOrBlank() && 
            (optimizedCover.isBlank() || (optimizedCover.contains("ytimg.com") && !existingCover.contains("ytimg.com")))
        val coverToStore = if (shouldPreserveExistingCover) existingCover!! else optimizedCover

        idToVideoId[songId] = cleanVid
        videoIdToId[cleanVid] = songId
        if (cleanT.isNotBlank()) idToTitle[songId] = cleanT
        if (cleanA.isNotBlank()) idToArtist[songId] = cleanA
        if (cleanAlb.isNotBlank()) idToAlbumName[songId] = cleanAlb
        if (coverToStore.isNotBlank()) idToCoverUrl[songId] = coverToStore
        if (durationSec > 0) idToDurationSec[songId] = durationSec
        if (artists.isNotEmpty()) idToArtists[songId] = artists

        prefs?.edit()?.apply {
            putString("vid_$songId", cleanVid)
            putLong("v2id_$cleanVid", songId)
            if (cleanT.isNotBlank()) putString("title_$songId", cleanT)
            if (cleanA.isNotBlank()) putString("artist_$songId", cleanA)
            if (cleanAlb.isNotBlank()) putString("album_$songId", cleanAlb)
            if (optimizedCover.isNotBlank()) putString("cover_$songId", optimizedCover)
            if (durationSec > 0) putInt("dur_$songId", durationSec)
            apply()
        }

        return songId
    }

    fun isStudioCover(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("mzstatic.com") ||
               lower.contains("apple.com") ||
               lower.contains("coverartarchive.org") ||
               lower.contains("scdn.co") ||
               lower.contains("deezer.com") ||
               lower.contains("dzcdn.net") ||
               lower.contains("tidal.com") ||
               lower.startsWith("content://") ||
               lower.startsWith("file://")
    }

    fun registerMetadata(
        songId: Long,
        title: String = "",
        artist: String = "",
        coverUrl: String = "",
        videoId: String? = null
    ) {
        val (extractedTitle, extractedArtist) = com.lin0721.linmusic.core.metadata.extractCleanTitleAndArtist(title, artist)
        val cleanT = cleanMetadataText(extractedTitle)
        val cleanA = cleanMetadataText(extractedArtist)
        val cachedStudio = com.lin0721.linmusic.core.metadata.SongArtworkEngine.getCachedCoverArt(cleanT, cleanA)
        val effectiveCover = cachedStudio ?: coverUrl
        val optimizedCover = if (effectiveCover.isNotBlank()) {
            com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(effectiveCover, 1200)
        } else ""

        if (cleanT.isNotBlank()) idToTitle[songId] = cleanT
        if (cleanA.isNotBlank()) idToArtist[songId] = cleanA
        if (optimizedCover.isNotBlank()) {
            val existing = idToCoverUrl[songId]
            val isExistingStudio = isStudioCover(existing)
            val isNewStudio = isStudioCover(optimizedCover)
            if (!isExistingStudio || isNewStudio) {
                idToCoverUrl[songId] = optimizedCover
            }
        }
        val cleanVid = sanitizeVideoId(videoId)
        if (cleanVid.isNotBlank()) {
            idToVideoId[songId] = cleanVid
            videoIdToId[cleanVid] = songId
        }

        prefs?.edit()?.apply {
            if (cleanT.isNotBlank()) putString("title_$songId", cleanT)
            if (cleanA.isNotBlank()) putString("artist_$songId", cleanA)
            val currentCover = idToCoverUrl[songId] ?: optimizedCover
            if (currentCover.isNotBlank()) putString("cover_$songId", currentCover)
            if (cleanVid.isNotBlank()) {
                putString("vid_$songId", cleanVid)
                putLong("v2id_$cleanVid", songId)
            }
            apply()
        }
    }

    fun registerPlaceholder(
        title: String,
        artist: String,
        album: String = "",
        durationSec: Int = 0,
        coverUrl: String = "",
        artists: List<Artist> = emptyList()
    ): Track {
        val cleanT = cleanMetadataText(title)
        val cleanA = cleanMetadataText(artist)
        val cleanAlb = cleanMetadataText(album)
        val artistList = if (artists.isNotEmpty()) artists else {
            val splitArtists = cleanA.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                .map { cleanMetadataText(it.trim()) }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(cleanA) }
            splitArtists.map { aName ->
                val aId = registerArtistByName(aName)
                Artist(id = aId, name = aName)
            }
        }
        val uniqueKey = "placeholder:${cleanT.lowercase()}:${cleanA.lowercase()}"
        val songId = (uniqueKey.hashCode().toLong() and 0x7FFFFFFFL)
        val albumId = if (cleanAlb.isNotBlank()) (cleanAlb.hashCode().toLong() and 0x7FFFFFFFL) else 0L
        val durMs = if (durationSec > 0) durationSec.toLong() * 1000L else 210_000L
        val track = Track(
            id = songId,
            name = cleanT,
            ar = artistList,
            al = Album(id = albumId, name = cleanAlb, picUrl = coverUrl),
            dt = durMs
        )
        idToTrack[songId] = track
        idToTitle[songId] = cleanT
        idToArtist[songId] = cleanA
        idToArtists[songId] = artistList
        idToAlbumName[songId] = cleanAlb
        if (coverUrl.isNotBlank()) idToCoverUrl[songId] = coverUrl
        if (durationSec > 0) idToDurationSec[songId] = durationSec

        prefs?.edit()?.apply {
            putString("title_$songId", cleanT)
            putString("artist_$songId", cleanA)
            putString("album_$songId", cleanAlb)
            if (coverUrl.isNotBlank()) putString("cover_$songId", coverUrl)
            if (durationSec > 0) putInt("dur_$songId", durationSec)
            apply()
        }

        return track
    }

    fun updatePlaceholderTrack(placeholderId: Long, resolvedTrack: Track, videoId: String) {
        val cleanVid = sanitizeVideoId(videoId)
        idToVideoId[placeholderId] = cleanVid
        videoIdToId[cleanVid] = placeholderId
        idToTrack[placeholderId] = resolvedTrack.copy(id = placeholderId)
        val cleanT = cleanMetadataText(resolvedTrack.name)
        val cleanA = cleanMetadataText(resolvedTrack.ar.joinToString(", ") { it.name })
        val cleanAlb = cleanMetadataText(resolvedTrack.al.name)
        val durSec = (resolvedTrack.dt / 1000).toInt()
        val cov = resolvedTrack.al.picUrl

        idToTitle[placeholderId] = cleanT
        idToArtist[placeholderId] = cleanA
        idToArtists[placeholderId] = resolvedTrack.ar
        idToCoverUrl[placeholderId] = cov
        idToAlbumName[placeholderId] = cleanAlb
        idToDurationSec[placeholderId] = durSec

        prefs?.edit()?.apply {
            putString("vid_$placeholderId", cleanVid)
            putLong("v2id_$cleanVid", placeholderId)
            putString("title_$placeholderId", cleanT)
            putString("artist_$placeholderId", cleanA)
            putString("album_$placeholderId", cleanAlb)
            putString("cover_$placeholderId", cov)
            putInt("dur_$placeholderId", durSec)
            apply()
        }
    }

    fun registerTrack(track: Track) {
        idToTrack[track.id] = track
        val cleanT = cleanMetadataText(track.name)
        val cleanA = cleanMetadataText(track.ar.joinToString(", ") { it.name })
        val cleanAlb = cleanMetadataText(track.al.name)
        val durSec = if (track.dt > 0) (track.dt / 1000).toInt() else 0
        val cov = track.al.picUrl

        if (cleanT.isNotBlank()) idToTitle[track.id] = cleanT
        if (track.ar.isNotEmpty()) {
            idToArtists[track.id] = track.ar
            idToArtist[track.id] = cleanA
            track.ar.forEach { a ->
                if (a.name.isNotBlank()) {
                    idToArtistName[a.id] = a.name
                    artistNameToId[a.name.lowercase().trim()] = a.id
                }
            }
        }
        if (cleanAlb.isNotBlank()) idToAlbumName[track.id] = cleanAlb
        if (cov.isNotBlank()) {
            val existing = idToCoverUrl[track.id]
            val isExistingStudio = isStudioCover(existing)
            val isNewYtThumb = cov.contains("i.ytimg.com") || cov.contains("img.youtube.com")
            if (!isExistingStudio || !isNewYtThumb) {
                idToCoverUrl[track.id] = cov
            }
        }
        if (durSec > 0) idToDurationSec[track.id] = durSec

        prefs?.edit()?.apply {
            if (cleanT.isNotBlank()) putString("title_${track.id}", cleanT)
            if (cleanA.isNotBlank()) putString("artist_${track.id}", cleanA)
            if (cleanAlb.isNotBlank()) putString("album_${track.id}", cleanAlb)
            val currentCover = idToCoverUrl[track.id] ?: cov
            if (currentCover.isNotBlank()) putString("cover_${track.id}", currentCover)
            if (durSec > 0) putInt("dur_${track.id}", durSec)
            apply()
        }
    }

    fun getTrack(songId: Long): Track? {
        val inMem = idToTrack[songId]
        if (inMem != null) return inMem
        val title = getTitle(songId)
        if (title.isBlank()) return null
        val artist = getArtist(songId)
        val album = getAlbumName(songId)
        val cover = getCoverUrl(songId)
        val durSec = getDurationSec(songId)
        val artistsList = getArtists(songId)
        val track = Track(
            id = songId,
            name = title,
            ar = artistsList,
            al = Album(id = if (album.isNotBlank()) album.hashCode().toLong() and 0x7FFFFFFFL else 0L, name = album, picUrl = cover),
            dt = durSec.toLong() * 1000L
        )
        idToTrack[songId] = track
        return track
    }

    /**
     * Look up the YouTube video ID for a given songId.
     * Checks memory cache first, then disk persistence.
     */
    fun getVideoId(songId: Long): String? {
        val inMem = idToVideoId[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("vid_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToVideoId[songId] = fromDisk
            videoIdToId[fromDisk] = songId
            return fromDisk
        }
        return null
    }

    fun findVideoIdByTitleArtist(title: String, artist: String, expectedDurationSec: Int = 0): String? {
        val cleanT = cleanMetadataText(title).lowercase().trim()
        val cleanA = cleanMetadataText(artist).lowercase().trim()
        if (cleanT.isBlank()) return null
        for ((songId, vid) in idToVideoId) {
            if (vid.isBlank() || vid.startsWith("track_") || vid.startsWith("placeholder_")) continue
            val t = (idToTitle[songId] ?: "").lowercase().trim()
            val a = (idToArtist[songId] ?: "").lowercase().trim()
            if (t == cleanT) {
                val artistMatches = if (cleanA.isNotBlank() && a.isNotBlank()) {
                    a == cleanA || a.contains(cleanA) || cleanA.contains(a)
                } else {
                    cleanA.isBlank() && a.isBlank()
                }
                if (artistMatches) {
                    if (expectedDurationSec > 0) {
                        val dur = idToDurationSec[songId] ?: 0
                        if (dur > 0 && kotlin.math.abs(dur - expectedDurationSec) > 20) {
                            continue
                        }
                    }
                    return vid
                }
            }
        }
        return null
    }

    /**
     * Check if a songId corresponds to a YouTube Music track.
     */
    fun isYouTubeTrack(songId: Long): Boolean = !getVideoId(songId).isNullOrBlank()

    fun getTitle(songId: Long): String {
        val inMem = idToTitle[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("title_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToTitle[songId] = fromDisk
            return fromDisk
        }
        return ""
    }

    fun getArtist(songId: Long): String {
        val inMem = idToArtist[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("artist_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToArtist[songId] = fromDisk
            return fromDisk
        }
        return ""
    }

    fun getAlbumName(songId: Long): String {
        val inMem = idToAlbumName[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("album_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToAlbumName[songId] = fromDisk
            return fromDisk
        }
        return ""
    }

    fun getCoverUrl(songId: Long): String {
        val inMem = idToCoverUrl[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("cover_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToCoverUrl[songId] = fromDisk
            return fromDisk
        }
        return ""
    }

    fun getArtists(songId: Long): List<Artist> {
        val inMem = idToArtists[songId]
        if (!inMem.isNullOrEmpty()) return inMem
        val artistStr = getArtist(songId)
        if (artistStr.isNotBlank()) {
            val splitArtists = artistStr.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                .map { cleanMetadataText(it.trim()) }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(artistStr) }
            val list = splitArtists.map { aName ->
                Artist(id = registerArtistByName(aName), name = aName)
            }
            idToArtists[songId] = list
            return list
        }
        return emptyList()
    }

    fun getDurationSec(songId: Long): Int {
        val inMem = idToDurationSec[songId]
        if (inMem != null && inMem > 0) return inMem
        val fromDisk = prefs?.getInt("dur_$songId", 0) ?: 0
        if (fromDisk > 0) {
            idToDurationSec[songId] = fromDisk
            return fromDisk
        }
        return 0
    }

    fun getReleaseDate(songId: Long): String {
        val inMem = idToReleaseDate[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("release_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToReleaseDate[songId] = fromDisk
            return fromDisk
        }
        return ""
    }

    fun getGenre(songId: Long): String {
        val inMem = idToGenre[songId]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("genre_$songId", null)
        if (!fromDisk.isNullOrBlank()) {
            idToGenre[songId] = fromDisk
            return fromDisk
        }
        return ""
    }

    fun registerEnrichedMetadata(
        songId: Long,
        title: String = "",
        artist: String = "",
        album: String = "",
        releaseDate: String = "",
        genre: String = "",
        coverUrl: String = ""
    ) {
        val cleanT = cleanMetadataText(title)
        val cleanA = cleanMetadataText(artist)
        val cleanAlb = cleanMetadataText(album)
        if (cleanT.isNotBlank()) idToTitle[songId] = cleanT
        if (cleanA.isNotBlank()) idToArtist[songId] = cleanA
        if (cleanAlb.isNotBlank()) idToAlbumName[songId] = cleanAlb
        if (releaseDate.isNotBlank()) idToReleaseDate[songId] = releaseDate
        if (genre.isNotBlank()) idToGenre[songId] = genre
        if (coverUrl.isNotBlank()) idToCoverUrl[songId] = coverUrl

        val currentTrack = idToTrack[songId]
        if (currentTrack != null) {
            val updatedArtists = if (cleanA.isNotBlank()) listOf(Artist(id = currentTrack.ar.firstOrNull()?.id ?: 0L, name = cleanA)) else currentTrack.ar
            val updatedAlbum = currentTrack.al.copy(
                name = if (cleanAlb.isNotBlank()) cleanAlb else currentTrack.al.name,
                picUrl = if (coverUrl.isNotBlank()) coverUrl else currentTrack.al.picUrl
            )
            idToTrack[songId] = currentTrack.copy(
                name = if (cleanT.isNotBlank()) cleanT else currentTrack.name,
                ar = updatedArtists,
                al = updatedAlbum
            )
        }

        prefs?.edit()?.apply {
            if (cleanT.isNotBlank()) putString("title_$songId", cleanT)
            if (cleanA.isNotBlank()) putString("artist_$songId", cleanA)
            if (cleanAlb.isNotBlank()) putString("album_$songId", cleanAlb)
            if (releaseDate.isNotBlank()) putString("release_$songId", releaseDate)
            if (genre.isNotBlank()) putString("genre_$songId", genre)
            if (coverUrl.isNotBlank()) putString("cover_$songId", coverUrl)
            apply()
        }
    }

    // Playlists & Albums registry
    private val idToPlaylistId = ConcurrentHashMap<Long, String>()
    private val playlistIdToId = ConcurrentHashMap<String, Long>()
    private val idToAlbumId = ConcurrentHashMap<Long, String>()
    private val albumIdToId = ConcurrentHashMap<String, Long>()

    fun registerPlaylist(playlistId: String): Long {
        val existing = playlistIdToId[playlistId] ?: prefs?.getLong("pl2id_$playlistId", -1L)?.takeIf { it != -1L }
        if (existing != null) return existing
        val id = (playlistId.hashCode().toLong() and 0x7FFFFFFFL)
        idToPlaylistId[id] = playlistId
        playlistIdToId[playlistId] = id

        prefs?.edit()?.apply {
            putString("pl_$id", playlistId)
            putLong("pl2id_$playlistId", id)
            apply()
        }
        return id
    }

    fun getPlaylistId(id: Long): String? {
        val inMem = idToPlaylistId[id]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("pl_$id", null)
        if (!fromDisk.isNullOrBlank()) {
            idToPlaylistId[id] = fromDisk
            playlistIdToId[fromDisk] = id
            return fromDisk
        }
        return null
    }

    fun registerAlbum(albumId: String): Long {
        val existing = albumIdToId[albumId] ?: prefs?.getLong("alb2id_$albumId", -1L)?.takeIf { it != -1L }
        if (existing != null) return existing
        val id = (albumId.hashCode().toLong() and 0x7FFFFFFFL)
        idToAlbumId[id] = albumId
        albumIdToId[albumId] = id

        prefs?.edit()?.apply {
            putString("albId_$id", albumId)
            putLong("alb2id_$albumId", id)
            apply()
        }
        return id
    }

    fun getAlbumId(id: Long): String? {
        val inMem = idToAlbumId[id]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("albId_$id", null)
        if (!fromDisk.isNullOrBlank()) {
            idToAlbumId[id] = fromDisk
            albumIdToId[fromDisk] = id
            return fromDisk
        }
        return null
    }

    // Artist registry
    private val idToArtistBrowseId = ConcurrentHashMap<Long, String>()
    private val artistBrowseIdToId = ConcurrentHashMap<String, Long>()
    private val idToArtistName = ConcurrentHashMap<Long, String>()
    private val artistNameToId = ConcurrentHashMap<String, Long>()

    fun registerArtist(browseId: String, name: String = ""): Long {
        val cleanN = cleanMetadataText(name)
        val existing = artistBrowseIdToId[browseId] ?: prefs?.getLong("ar2id_$browseId", -1L)?.takeIf { it != -1L }
        if (existing != null) {
            if (cleanN.isNotBlank()) {
                idToArtistName[existing] = cleanN
                artistNameToId[cleanN.lowercase().trim()] = existing
                prefs?.edit()?.apply {
                    putString("arName_$existing", cleanN)
                    putLong("arName2id_${cleanN.lowercase().trim()}", existing)
                    apply()
                }
            }
            return existing
        }
        val id = (browseId.hashCode().toLong() and 0x7FFFFFFFL)
        idToArtistBrowseId[id] = browseId
        artistBrowseIdToId[browseId] = id
        val edit = prefs?.edit()
        edit?.putString("arId_$id", browseId)
        edit?.putLong("ar2id_$browseId", id)
        if (cleanN.isNotBlank()) {
            idToArtistName[id] = cleanN
            artistNameToId[cleanN.lowercase().trim()] = id
            edit?.putString("arName_$id", cleanN)
            edit?.putLong("arName2id_${cleanN.lowercase().trim()}", id)
        }
        edit?.apply()
        return id
    }

    fun registerArtistByName(name: String): Long {
        val cleanN = cleanMetadataText(name)
        val key = cleanN.lowercase().trim()
        val existing = artistNameToId[key] ?: prefs?.getLong("arName2id_$key", -1L)?.takeIf { it != -1L }
        if (existing != null) return existing
        val id = (key.hashCode().toLong() and 0x7FFFFFFFL)
        idToArtistName[id] = cleanN
        artistNameToId[key] = id
        prefs?.edit()?.apply {
            putString("arName_$id", cleanN)
            putLong("arName2id_$key", id)
            apply()
        }
        return id
    }

    fun associateArtistBrowseId(artistId: Long, browseId: String) {
        idToArtistBrowseId[artistId] = browseId
        artistBrowseIdToId[browseId] = artistId
        prefs?.edit()?.apply {
            putString("arId_$artistId", browseId)
            putLong("ar2id_$browseId", artistId)
            apply()
        }
    }

    fun getArtistBrowseId(id: Long): String? {
        val inMem = idToArtistBrowseId[id]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("arId_$id", null)
        if (!fromDisk.isNullOrBlank()) {
            idToArtistBrowseId[id] = fromDisk
            artistBrowseIdToId[fromDisk] = id
            return fromDisk
        }
        return null
    }

    fun getArtistName(id: Long): String {
        val inMem = idToArtistName[id]
        if (!inMem.isNullOrBlank()) return inMem
        val fromDisk = prefs?.getString("arName_$id", null)
        if (!fromDisk.isNullOrBlank()) {
            idToArtistName[id] = fromDisk
            return fromDisk
        }
        return ""
    }
}
