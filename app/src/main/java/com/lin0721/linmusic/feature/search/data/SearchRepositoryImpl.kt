package com.lin0721.linmusic.feature.search.data

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.mapToAppError
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.metadata.extractCleanTitleAndArtist
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.lin0721.linmusic.feature.search.domain.HotSearch
import com.lin0721.linmusic.feature.search.domain.PlaylistCategoryPage
import com.lin0721.linmusic.feature.search.domain.PlaylistTag
import com.lin0721.linmusic.feature.search.domain.SearchPageResult
import com.lin0721.linmusic.feature.search.domain.SearchResultItem
import com.lin0721.linmusic.feature.search.domain.SearchSuggestion
import com.lin0721.linmusic.feature.search.domain.SearchType
import com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler
import com.lin0721.linmusic.core.metadata.SongArtworkEngine
import com.lin0721.linmusic.core.metadata.MetadataEnricher
import com.lin0721.linmusic.core.lastfm.LastFmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SearchRepositoryImpl"

/**
 * YouTube Music-backed search repository with Last.fm authentic listener ranking
 * and iTunes + MusicBrainz verified artwork and metadata.
 */
class SearchRepositoryImpl(
    private val lastFmRepository: LastFmRepository? = null,
    private val metadataEnricher: MetadataEnricher? = null,
    private val auroraApiClient: com.lin0721.linmusic.core.aurora.AuroraApiClient? = null,
) : SearchRepository {

    override fun getDefaultSearchKeyword(): Flow<Result<String>> = flow {
        emit(Result.success(""))
    }

    override fun search(keyword: String, type: SearchType, offset: Int, limit: Int): Flow<Result<SearchPageResult>> = flow {
        val filter = when (type) {
            SearchType.ALL -> YouTube.SearchFilter("")
            SearchType.SONG -> YouTube.SearchFilter.FILTER_SONG
            SearchType.ALBUM -> YouTube.SearchFilter.FILTER_ALBUM
            SearchType.ARTIST -> YouTube.SearchFilter.FILTER_ARTIST
            SearchType.PLAYLIST -> YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST
        }

        val result = runCatching {
            YouTube.search(keyword, filter).getOrThrow()
        }.recoverCatching { err ->
            AppLogger.w(TAG, "Search with filter $filter failed, retrying without filter", err)
            YouTube.search(keyword, YouTube.SearchFilter("")).getOrThrow()
        }.getOrThrow()
        val items = result.items

        val mapped: List<SearchResultItem> = when (type) {
            SearchType.ALL -> {
                val songs = items.filterIsInstance<SongItem>().take(10).map { song ->
                    val (cleanTitle, cleanArtist) = extractCleanTitleAndArtist(
                        song.title,
                        song.artists.joinToString(", ") { it.name }
                    )
                    val cleanAlbumName = cleanMetadataText(song.album?.name ?: cleanTitle)
                    val cachedStudio = SongArtworkEngine.getCachedCoverArt(cleanTitle, cleanArtist)
                    val cover = cachedStudio ?: SongArtworkEngine.optimizeArtworkUrl(song.thumbnail, 544)
                    val durationSec = song.duration ?: 0
                    val realArtistNames = cleanArtist.split(",", "&", "/").map { it.trim() }.filter { it.isNotBlank() }
                    val mappedArtists = if (realArtistNames.isNotEmpty()) {
                        realArtistNames.map { name ->
                            val aId = YouTubeIdRegistry.registerArtistByName(name)
                            Artist(id = aId, name = name)
                        }
                    } else {
                        song.artists.map {
                            val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                                ?: YouTubeIdRegistry.registerArtistByName(it.name)
                            Artist(id = aId, name = cleanMetadataText(it.name))
                        }
                    }
                    val songId = YouTubeIdRegistry.register(
                        videoId = song.id,
                        title = cleanTitle,
                        artist = cleanArtist,
                        durationSec = durationSec,
                        album = cleanAlbumName,
                        coverUrl = cover,
                        artists = mappedArtists
                    )
                    val track = Track(
                        id = songId,
                        name = cleanTitle,
                        ar = mappedArtists,
                        al = Album(
                            id = (song.album?.id ?: "").hashCode().toLong() and 0x7FFFFFFFL,
                            name = cleanAlbumName,
                            picUrl = cover
                        ),
                        dt = durationSec * 1000L
                    )
                    YouTubeIdRegistry.registerTrack(track)
                    SearchResultItem.SongItem(track)
                }

                val artists = items.filterIsInstance<ArtistItem>().take(5).map { artist ->
                    val artistId = YouTubeIdRegistry.registerArtist(artist.id, artist.title)
                    val avatar = SongArtworkEngine.optimizeArtworkUrl(artist.thumbnail ?: "", 544)
                    SearchResultItem.ArtistItem(
                        Artist(
                            id = artistId,
                            name = cleanMetadataText(artist.title),
                            picUrl = avatar,
                            img1v1Url = avatar
                        )
                    )
                }

                val albums = items.filterIsInstance<AlbumItem>().take(5).map { album ->
                    val albumId = YouTubeIdRegistry.registerAlbum(album.browseId)
                    val cover = SongArtworkEngine.optimizeArtworkUrl(album.thumbnail, 544)
                    SearchResultItem.AlbumItem(
                        Album(
                            id = albumId,
                            name = cleanMetadataText(album.title),
                            picUrl = cover,
                            artists = album.artists?.map {
                                Artist(id = (it.id ?: it.name).hashCode().toLong() and 0x7FFFFFFFL, name = cleanMetadataText(it.name))
                            } ?: emptyList()
                        )
                    )
                }

                val playlists = items.filterIsInstance<PlaylistItem>().take(5).map { playlist ->
                    val playlistId = YouTubeIdRegistry.registerPlaylist(playlist.id)
                    val cover = SongArtworkEngine.optimizeArtworkUrl(playlist.thumbnail ?: "", 544)
                    SearchResultItem.PlaylistItem(
                        PlaylistDetail(
                            id = playlistId,
                            name = cleanMetadataText(playlist.title),
                            coverImgUrl = cover,
                            trackCount = playlist.songCountText?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                            creator = com.lin0721.linmusic.core.model.PlaylistCreator(
                                userId = playlist.author?.id.hashCode().toLong() and 0x7FFFFFFFL,
                                nickname = cleanMetadataText(playlist.author?.name ?: "")
                            )
                        )
                    )
                }

                songs + artists + albums + playlists
            }
            SearchType.SONG -> {
                val isExplicitLiveSearch = keyword.contains("live", ignoreCase = true)
                val isExplicitAcousticSearch = keyword.contains("acoustic", ignoreCase = true)
                val isExplicitSlowedSearch = keyword.contains("slowed", ignoreCase = true) || keyword.contains("reverb", ignoreCase = true)
                val isExplicitRemixSearch = keyword.contains("remix", ignoreCase = true)
                val isExplicitInstrumentalSearch = keyword.contains("instrumental", ignoreCase = true) || keyword.contains("karaoke", ignoreCase = true)

                // 1. Filter out obvious video titles and non-music variations unless explicitly requested
                val filteredSongItems = items.filterIsInstance<SongItem>().filter { song ->
                    val tLower = song.title.lowercase()

                    if (!isExplicitLiveSearch && (tLower.contains("live at") || tLower.contains("live from") || tLower.contains("live in") || tLower.contains("(live)") || tLower.contains("[live]"))) {
                        return@filter false
                    }
                    if (!isExplicitAcousticSearch && (tLower.contains("(acoustic") || tLower.contains("[acoustic"))) {
                        return@filter false
                    }
                    if (!isExplicitSlowedSearch && (tLower.contains("slowed") || tLower.contains("reverb") || tLower.contains("nightcore") || tLower.contains("sped up") || tLower.contains("speed up"))) {
                        return@filter false
                    }
                    if (!isExplicitRemixSearch && (tLower.contains("(remix") || tLower.contains("[remix") || tLower.contains(" - remix"))) {
                        return@filter false
                    }
                    if (!isExplicitInstrumentalSearch && (tLower.contains("instrumental") || tLower.contains("karaoke"))) {
                        return@filter false
                    }

                    val hasVideoMarker = tLower.contains("official video") ||
                        tLower.contains("official music video") ||
                        tLower.contains("music video") ||
                        tLower.contains("lyric video") ||
                        tLower.contains("lyrics video") ||
                        tLower.contains("(visualizer)") ||
                        tLower.contains("[visualizer]") ||
                        tLower.contains("1 hour") ||
                        tLower.contains("one hour") ||
                        tLower.contains("8d audio") ||
                        tLower.contains("bass boosted") ||
                        tLower.contains("full album")

                    if (hasVideoMarker) {
                        return@filter false
                    }

                    // Duration sanity check: reject short clips (< 45s) or long mixes (> 15 mins)
                    val dur = song.duration ?: 0
                    if (dur in 1..44 || dur > 900) {
                        return@filter false
                    }

                    true
                }

                // 2. Deduplicate different variants of the same song by the same artist to keep only authentic studio tracks
                val deduplicatedSongs = mutableListOf<SongItem>()
                val seenSongSignatures = HashSet<String>()

                for (song in filteredSongItems) {
                    val cleanT = cleanMetadataText(song.title).lowercase()
                        .replace(Regex("""\s*[\(\[](official\s*(music\s*)?video|audio|lyric(s)?(\s*video)?|visualizer|album\s*version|single|remaster(ed)?(\s*\d+)?|radio\s*edit)[\)\]]""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\s*-\s*(single|remaster(ed)?|radio\s*edit|official\s*audio).*$""", RegexOption.IGNORE_CASE), "")
                        .trim()
                    val primaryArtist = song.artists.firstOrNull()?.name?.lowercase()?.trim() ?: ""
                    val signature = "$cleanT|||$primaryArtist"

                    if (seenSongSignatures.add(signature)) {
                        deduplicatedSongs.add(song)
                    }
                }

                val songTracks = deduplicatedSongs.map { song ->
                    val (cleanTitle, cleanArtist) = extractCleanTitleAndArtist(
                        song.title,
                        song.artists.joinToString(", ") { it.name }
                    )
                    val durationSec = song.duration ?: 0
                    val realArtistNames = cleanArtist.split(",", "&", "/").map { it.trim() }.filter { it.isNotBlank() }
                    val mappedArtists = if (realArtistNames.isNotEmpty()) {
                        realArtistNames.map { name ->
                            val aId = YouTubeIdRegistry.registerArtistByName(name)
                            Artist(id = aId, name = name)
                        }
                    } else {
                        song.artists.map {
                            val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                                ?: YouTubeIdRegistry.registerArtistByName(it.name)
                            Artist(id = aId, name = cleanMetadataText(it.name))
                        }
                    }
                    val cleanAlbumName = cleanMetadataText(song.album?.name ?: "")
                    val cachedStudio = SongArtworkEngine.getCachedCoverArt(cleanTitle, cleanArtist)
                    val bestCover = cachedStudio ?: (YouTubeThumbnailUpscaler.upscale(song.thumbnail) ?: song.thumbnail)

                    val songId = YouTubeIdRegistry.register(
                        videoId = song.id,
                        title = cleanTitle,
                        artist = cleanArtist,
                        durationSec = durationSec,
                        album = cleanAlbumName,
                        coverUrl = bestCover,
                        artists = mappedArtists
                    )
                    val track = Track(
                        id = songId,
                        name = cleanTitle,
                        ar = mappedArtists,
                        al = Album(
                            id = (song.album?.id ?: "").hashCode().toLong() and 0x7FFFFFFFL,
                            name = cleanAlbumName,
                            picUrl = bestCover
                        ),
                        dt = durationSec.toLong() * 1000L
                    )
                    YouTubeIdRegistry.registerTrack(track)
                    track
                }

                // Rank using Last.fm listeners if available
                val rankedTracks = if (lastFmRepository != null && songTracks.isNotEmpty()) {
                    coroutineScope {
                        val scored = songTracks.take(15).map { track ->
                            async {
                                val artistName = track.ar.firstOrNull()?.name ?: ""
                                val info = lastFmRepository.getTrackInfo(artistName, track.name)
                                track to (info?.listeners ?: 0L)
                            }
                        }.map { it.await() }

                        val topRanked = scored.sortedByDescending { it.second }.map { it.first }
                        val remaining = songTracks.drop(15)
                        topRanked + remaining
                    }
                } else {
                    songTracks
                }

                // Keep YouTube metadata pristine without external overrides
                val verifiedSongItems = rankedTracks.map { SearchResultItem.SongItem(it) }

                // Place matched artist at the top of results when searching an artist, followed by their songs
                val artistPrefix = if (offset == 0) {
                    val artistItem = runCatching {
                        YouTube.search(keyword, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                            ?.items?.filterIsInstance<ArtistItem>()?.firstOrNull { item ->
                                val aTitle = item.title.lowercase().trim()
                                val kLower = keyword.lowercase().trim()
                                aTitle == kLower || aTitle.contains(kLower) || kLower.contains(aTitle) ||
                                    com.lin0721.linmusic.core.contentfilter.CuratedArtists.isCurated(aTitle)
                            }
                    }.getOrNull()

                    if (artistItem != null) {
                        val artistId = YouTubeIdRegistry.registerArtist(artistItem.id, artistItem.title)
                        val avatar = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(artistItem.thumbnail)
                            ?: artistItem.thumbnail ?: ""
                        listOf(
                            SearchResultItem.ArtistItem(
                                Artist(
                                    id = artistId,
                                    name = cleanMetadataText(artistItem.title),
                                    picUrl = avatar,
                                    img1v1Url = avatar
                                )
                            )
                        )
                    } else emptyList()
                } else emptyList()

                artistPrefix + verifiedSongItems
            }
            SearchType.ALBUM -> items.filterIsInstance<AlbumItem>().map { album ->
                val albumId = YouTubeIdRegistry.registerAlbum(album.browseId)
                SearchResultItem.AlbumItem(
                    Album(
                        id = albumId,
                        name = cleanMetadataText(album.title),
                        picUrl = album.thumbnail,
                        artists = album.artists?.map {
                            Artist(id = (it.id ?: it.name).hashCode().toLong() and 0x7FFFFFFFL, name = cleanMetadataText(it.name))
                        } ?: emptyList()
                    )
                )
            }
            SearchType.ARTIST -> items.filterIsInstance<ArtistItem>().map { artist ->
                val artistId = YouTubeIdRegistry.registerArtist(artist.id, artist.title)
                SearchResultItem.ArtistItem(
                    Artist(
                        id = artistId,
                        name = cleanMetadataText(artist.title),
                        picUrl = artist.thumbnail ?: "",
                        img1v1Url = artist.thumbnail ?: ""
                    )
                )
            }
            SearchType.PLAYLIST -> items.filterIsInstance<PlaylistItem>().map { playlist ->
                val playlistId = YouTubeIdRegistry.registerPlaylist(playlist.id)
                SearchResultItem.PlaylistItem(
                    PlaylistDetail(
                        id = playlistId,
                        name = cleanMetadataText(playlist.title),
                        coverImgUrl = playlist.thumbnail ?: "",
                        playCount = 0
                    )
                )
            }
        }

        val distinctMapped = mapped.distinctBy { item ->
            when (item) {
                is SearchResultItem.SongItem -> "song_${item.track.id}"
                is SearchResultItem.AlbumItem -> "album_${item.album.id}"
                is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
                is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}"
            }
        }

        emit(Result.success(
            SearchPageResult(
                items = distinctMapped,
                totalCount = distinctMapped.size,
                hasMore = result.continuation != null,
                rawFetchedCount = mapped.size
            )
        ))
    }.catch { e ->
        AppLogger.e(TAG, "YouTube Music search failed", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getSuggestions(keyword: String): Flow<Result<List<SearchSuggestion>>> = flow {
        val result = YouTube.searchSuggestions(keyword).getOrThrow()
        val artistSuggestions = mutableListOf<SearchSuggestion>()
        val albumSuggestions = mutableListOf<SearchSuggestion>()
        val songSuggestions = mutableListOf<SearchSuggestion>()
        val keywordSuggestions = mutableListOf<SearchSuggestion>()

        val videoBlacklist = listOf(
            "video", "official video", "music video", "official music video",
            "mv", "m/v", "lyrics", "lyric video", "lyric", "live", "live at",
            "concert", "acoustic", "sped up", "speed up", "slowed", "reverb",
            "slowed and reverb", "slowed + reverb", "nightcore", "bass boosted",
            "1 hour", "one hour", "loop", "extended", "8d", "8d audio",
            "reaction", "tutorial", "cover", "karaoke", "instrumental",
            "status", "whatsapp status", "shorts", "tiktok", "dance", "choreography",
            "teaser", "trailer"
        )

        val keywordLower = keyword.lowercase().trim()

        // Prioritize artist, album, and authentic song matches
        result.recommendedItems.forEach { item ->
            when (item) {
                is ArtistItem -> {
                    val artistId = YouTubeIdRegistry.registerArtist(item.id, item.title)
                    artistSuggestions.add(
                        SearchSuggestion.ArtistMatch(
                            id = artistId,
                            text = cleanMetadataText(item.title),
                            avatarUrl = SongArtworkEngine.optimizeArtworkUrl(item.thumbnail ?: "", 544)
                        )
                    )
                }
                is AlbumItem -> {
                    val albumId = YouTubeIdRegistry.registerAlbum(item.browseId)
                    albumSuggestions.add(
                        SearchSuggestion.AlbumMatch(
                            id = albumId,
                            text = cleanMetadataText(item.title),
                            artistName = cleanMetadataText(item.artists?.firstOrNull()?.name ?: "")
                        )
                    )
                }
                is SongItem -> {
                    val cleanTitle = cleanMetadataText(item.title)
                    val tLower = cleanTitle.lowercase()
                    val hasVideoMarker = videoBlacklist.any { tLower.contains(it) }
                    if (!hasVideoMarker) {
                        val artistName = cleanMetadataText(item.artists.joinToString(", ") { cleanMetadataText(it.name) })
                        val mappedArtists = item.artists.map {
                            val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                                ?: YouTubeIdRegistry.registerArtistByName(it.name)
                            Artist(id = aId, name = cleanMetadataText(it.name))
                        }
                        val cleanAlbumName = cleanMetadataText(item.album?.name ?: "")
                        val bestCover = SongArtworkEngine.optimizeArtworkUrl(item.thumbnail, 544)

                        val songId = YouTubeIdRegistry.register(
                            videoId = item.id,
                            title = cleanTitle,
                            artist = artistName,
                            durationSec = item.duration ?: 0,
                            album = cleanAlbumName,
                            coverUrl = bestCover,
                            artists = mappedArtists
                        )
                        songSuggestions.add(
                            SearchSuggestion.SongMatch(
                                id = songId,
                                text = cleanTitle,
                                artistName = artistName,
                                coverUrl = bestCover,
                                rawVideoId = item.id
                            )
                        )
                    }
                }
                else -> {}
            }
        }

        val seenQueries = HashSet<String>()
        result.queries.forEach { rawQuery ->
            val qLower = rawQuery.lowercase().trim()

            // Filter out queries containing video or non-music suffixes unless user is typing them
            val hasVideoTerm = videoBlacklist.any { term ->
                qLower.contains(term) && !keywordLower.contains(term)
            }

            if (!hasVideoTerm) {
                val cleaned = rawQuery
                    .replace(Regex("""\s*[\(\[](official\s*(music\s*)?video|audio|lyric(s)?(\s*video)?|visualizer)[\)\]]""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*-\s*(official\s*audio|official\s*video|lyrics).*$""", RegexOption.IGNORE_CASE), "")
                    .trim()

                if (cleaned.isNotBlank() && seenQueries.add(cleaned.lowercase())) {
                    keywordSuggestions.add(SearchSuggestion.Keyword(cleaned))
                }
            }
        }

        val allSuggestions = (artistSuggestions + albumSuggestions + songSuggestions + keywordSuggestions).distinctBy { suggestion ->
            when (suggestion) {
                is SearchSuggestion.ArtistMatch -> "artist_${suggestion.id}"
                is SearchSuggestion.AlbumMatch -> "album_${suggestion.id}"
                is SearchSuggestion.SongMatch -> "song_${suggestion.id}"
                is SearchSuggestion.Keyword -> "keyword_${suggestion.text.lowercase().trim()}"
            }
        }

        emit(Result.success(allSuggestions))
    }.catch { e ->
        AppLogger.w(TAG, "YouTube Music suggestions failed", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getHotSearches(): Flow<Result<List<HotSearch>>> = flow {
        // Fetch top trending songs directly from YouTube Music
        val ytResult = runCatching {
            val homeResult = YouTube.home().getOrThrow()
            homeResult.sections
                .flatMap { section -> section.items.filterIsInstance<SongItem>() }
                .take(30)
                .mapIndexed { index, song ->
                    HotSearch(
                        keyword = "${song.title} - ${song.artists.firstOrNull()?.name ?: ""}",
                        score = 100 - index,
                        description = song.artists.firstOrNull()?.name.orEmpty(),
                        iconUrl = null
                    )
                }
        }.getOrDefault(emptyList())
        emit(Result.success(ytResult))
    }

    override fun getPlaylistTags(): Flow<Result<List<PlaylistTag>>> = flow {
        // Top curated artist playlists for the "Curated Playlists" section
        val topCuratedArtists = listOf(
            "Taylor Swift", "The Weeknd", "Drake", "Bad Bunny", "Travis Scott",
            "Kendrick Lamar", "Bruno Mars", "Billie Eilish", "Eminem", "Ed Sheeran",
            "Rihanna", "Coldplay", "Kanye West", "Post Malone", "Dua Lipa",
            "Lana Del Rey", "Justin Bieber", "Ariana Grande", "Arijit Singh", "Linkin Park",
            "Queen", "SZA", "Harry Styles", "Adele", "Arctic Monkeys",
            "Michael Jackson", "The Beatles", "Frank Ocean", "Future", "J Balvin"
        )
        emit(Result.success(topCuratedArtists.map { PlaylistTag(name = it, coverUrl = "") }))
    }

    override fun getPlaylistsByCategory(category: String, cursor: Long, limit: Int): Flow<Result<PlaylistCategoryPage>> = flow {
        val searchRes = runCatching {
            YouTube.search("$category playlist", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                ?: YouTube.search("$category playlist", YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST).getOrNull()
        }.getOrNull()

        val rawPlaylists = searchRes?.items?.filterIsInstance<PlaylistItem>().orEmpty()
        val curatedPlaylists = rawPlaylists.filter {
            com.lin0721.linmusic.core.contentfilter.CuratedArtists.isPlaylistOrAlbumCurated(it.title, it.author?.name)
        }

        val details = curatedPlaylists.map { item ->
            val playlistId = YouTubeIdRegistry.registerPlaylist(item.id)
            val cleanTitle = cleanMetadataText(item.title)
            val cleanAuthor = cleanMetadataText(item.author?.name ?: "Curated Mix")
            val cover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(item.thumbnail) ?: item.thumbnail ?: ""
            PlaylistDetail(
                id = playlistId,
                name = cleanTitle,
                coverImgUrl = cover,
                description = cleanAuthor,
                playCount = 0L,
                trackCount = item.songCountText?.filter { it.isDigit() }?.toIntOrNull() ?: 25,
                creator = com.lin0721.linmusic.core.model.PlaylistCreator(nickname = cleanAuthor)
            )
        }

        emit(Result.success(
            PlaylistCategoryPage(
                playlists = details,
                hasMore = false,
                nextCursor = 0L,
                total = details.size
            )
        ))
    }.catch { e ->
        AppLogger.w(TAG, "getPlaylistsByCategory failed", e)
        emit(Result.success(PlaylistCategoryPage(emptyList(), false, 0L, 0)))
    }
}
