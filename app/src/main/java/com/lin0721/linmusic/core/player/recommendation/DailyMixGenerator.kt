package com.lin0721.linmusic.core.player.recommendation

import androidx.compose.ui.graphics.Color
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.lin0721.linmusic.core.aurora.AuroraApiClient
import com.lin0721.linmusic.core.history.PlaybackHistoryRepository
import com.lin0721.linmusic.core.importer.ParsedTrackInfo
import com.lin0721.linmusic.core.lastfm.LastFmRepository
import com.lin0721.linmusic.core.laya.AffinityInputTrack
import com.lin0721.linmusic.core.laya.AffinityScoringResult
import com.lin0721.linmusic.core.laya.LayaDecisionEngine
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.ArtistPortraitProvider
import com.lin0721.linmusic.core.metadata.IsrcMetadataResolver
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.ui.components.MimikyuCoverType
import com.lin0721.linmusic.core.ui.components.getMimikyuPastelColor
import com.lin0721.linmusic.feature.recent.domain.RecentSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import com.lin0721.linmusic.core.ui.model.DailyMixPlaylist
import com.lin0721.linmusic.core.ui.model.MimikyuShelvesResult

private const val TAG = "DailyMixGenerator"

/**
 * Generates Mimikyu discovery shelves powered by Laya Continuous Affinity Scoring,
 * candidate selection, and time-of-day mood deduction.
 */
class DailyMixGenerator(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val lastFmRepository: LastFmRepository = LastFmRepository(),
    private val tasteSignalScorer: TasteSignalScorer? = null,
    private val playbackHistoryRepository: PlaybackHistoryRepository? = null,
    private val auroraApiClient: AuroraApiClient? = null,
    private val isrcMetadataResolver: IsrcMetadataResolver? = null
) {

    companion object {
        const val ON_REPEAT_ID = -9L
        const val DAILY_MIX_1_ID = -10L
        const val DAILY_MIX_2_ID = -11L
        const val DAILY_MIX_3_ID = -12L

        private const val TRACKS_PER_MIX = 30
        private const val TOP_ARTISTS_FETCH = 20

        private val cachedMixes = ConcurrentHashMap<Long, DailyMixPlaylist>()
        private val soundtrackCache = ConcurrentHashMap<String, Pair<List<DailyMixPlaylist>, String>>()

        fun getCachedMix(id: Long): DailyMixPlaylist? {
            val normalizedId = when (id) {
                -1L -> DAILY_MIX_1_ID
                -2L -> DAILY_MIX_2_ID
                -3L -> DAILY_MIX_3_ID
                else -> id
            }
            return cachedMixes[normalizedId]
        }

        fun cacheMix(mix: DailyMixPlaylist) {
            cachedMixes[mix.id] = mix
        }

        val FALLBACK_SEED_VIDEO_IDS = listOf(
            "OPf0YbXqDm0",  // Mark Ronson - Uptown Funk
            "X-TiHfM0t5E",  // Taylor Swift - Anti-Hero
            "YQHsXMglC9A",  // Adele - Hello
            "JGwWNGJdvx8",  // Ed Sheeran - Shape of You
            "3tmd-ClpJxA",  // Taylor Swift - Look What You Made Me Do
            "kJQP7kiw5Fk",  // Luis Fonsi - Despacito
            "2Vv-BfVoq4g",  // Ed Sheeran - Perfect
            "09R8_2nJtjg",  // Maroon 5 - Sugar
            "hT_nvWreIhg"   // OneRepublic - Counting Stars
        )

        val CURATED_ARTISTS = listOf(
            "The Weeknd",
            "Kendrick Lamar",
            "Taylor Swift",
            "Drake",
            "Bruno Mars",
            "SZA",
            "Billie Eilish",
            "Travis Scott",
            "Dua Lipa",
            "Coldplay",
            "Tame Impala",
            "Frank Ocean",
            "Kanye West",
            "Arijit Singh",
            "Diljit Dosanjh",
            "Chaar Diwaari",
            "Seedhe Maut",
            "Michael Jackson",
            "Post Malone",
            "Arctic Monkeys"
        )

        /**
         * Normalizes an artist name to a canonical deduplication key to prevent
         * duplicates like "The Jackson 5", "Jackson 5", "The Jacksons", or feature credits.
         */
        fun canonicalArtistKey(artist: String): String {
            if (artist.isBlank()) return ""
            var key = artist.trim().lowercase()
            if (key.startsWith("the ")) {
                key = key.removePrefix("the ").trim()
            }
            key = key.split(Regex("\\s+(feat\\.|ft\\.|with|vs\\.|&|/)\\s+")).first().trim()
            val alphaNumeric = key.replace(Regex("[^a-z0-9]"), "")
            if (alphaNumeric.contains("jackson") && (alphaNumeric.contains("5") || alphaNumeric.contains("five") || alphaNumeric.contains("jacksons") || alphaNumeric == "jacksons")) {
                return "jackson5"
            }
            if (alphaNumeric == "michaeljackson") {
                return "michaeljackson"
            }
            return alphaNumeric
        }
    }

    /**
     * Generates all Mimikyu shelves with 7-10 items each.
     */
    suspend fun generateAllMimikyuShelves(): MimikyuShelvesResult = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val firestoreSeeds = if (uid != null) fetchUserTopArtists(uid) else emptyList()
        val eligibleTasteSeeds = tasteSignalScorer?.getEligibleSeedTracks() ?: emptyList()
        val familiarFavorites = tasteSignalScorer?.getFamiliarFavorites() ?: emptyList()
        val onRepeatTracks = tasteSignalScorer?.getOnRepeatTracks() ?: emptyList()
        val history: List<RecentSong> = playbackHistoryRepository?.plays?.value ?: emptyList()

        // Continuous affinity scoring via Laya Decision Engine
        val affinityTracks = history.map {
            AffinityInputTrack(
                title = it.title,
                artist = it.artist,
                album = it.album,
                genre = null,
                playCount = 1,
                lastPlayedTimestampMs = it.playTime
            )
        }
        val affinityResult = if (affinityTracks.isNotEmpty()) {
            LayaDecisionEngine.scoreArtistsAndGenres(affinityTracks)
        } else null

        // Combine user seeds prioritizing high-affinity scored tracks
        val userSeeds = if (eligibleTasteSeeds.isNotEmpty()) {
            val fromTaste = eligibleTasteSeeds.map { it.artist to (it.videoId.ifBlank { it.songId.toString() }) }
            (fromTaste + firestoreSeeds).distinctBy { it.first }
        } else {
            firestoreSeeds
        }

        // Establish the top artists pool: user's history first, augmented by Last.fm and curated icons
        val historyTopArtists = affinityResult?.topArtists?.map { it.name }.orEmpty()
        val userArtists = (historyTopArtists + userSeeds.map { it.first })
            .filter { it.isNotBlank() }
            .distinctBy { canonicalArtistKey(it) }

        val primaryArtist = userArtists.firstOrNull() ?: CURATED_ARTISTS.first()

        // Last.fm similar artists for primary artist - strictly deduplicated & capped at 3 so they don't flood the shelf
        val lastFmSimilar = runCatching { lastFmRepository.getSimilarArtists(primaryArtist, limit = 10) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .distinctBy { canonicalArtistKey(it) }
            .filter { canonicalArtistKey(it) != canonicalArtistKey(primaryArtist) }
            .take(3)

        // Merge intelligently: User artists first + up to 3 similar artists + diverse curated artists
        val seenEnrichedKeys = mutableSetOf<String>()
        val combinedCandidates = (userArtists.ifEmpty { listOf(primaryArtist) } + lastFmSimilar + CURATED_ARTISTS)
        val enrichedArtists = combinedCandidates.filter { artist ->
            val key = canonicalArtistKey(artist)
            key.isNotBlank() && seenEnrichedKeys.add(key)
        }.take(14)

        // Preload portraits for all top and similar artists in parallel
        val allArtistsToResolve = (enrichedArtists + lastFmSimilar).distinct()
        coroutineScope {
            allArtistsToResolve.map { artist ->
                async {
                    runCatching {
                        ArtistPortraitProvider.getArtistPortrait(artist)
                    }
                }
            }.awaitAll()
        }

        // 1. Shelf 1: "Your top mixes" (Continuous affinity mixes + On Repeat + Daily Mixes 1-3 + Vibe Mixes)
        val topMixes = buildTopMixes(userSeeds, eligibleTasteSeeds, familiarFavorites, onRepeatTracks, affinityResult)
        topMixes.forEach { cacheMix(it) }

        // 2. Shelf 2: "Recommended Stations" (7-10 artist radios)
        val stations = buildRecommendedStations(enrichedArtists, lastFmSimilar)
        stations.forEach { cacheMix(it) }

        // 3. Made for your vibe (6 personal behavioral vibe cards with artist cutouts)
        val madeForYourVibe = VibeRulesEngine.generateVibeShelves(history, eligibleTasteSeeds, onRepeatTracks)
        madeForYourVibe.forEach { spec ->
            cacheMix(
                DailyMixPlaylist(
                    id = spec.virtualId,
                    title = "This Is " + spec.headline.lowercase().replaceFirstChar { it.uppercase() },
                    subtitle = spec.subtitle,
                    coverUrl = (spec.artistCutoutUrlOrBitmap as? String).orEmpty(),
                    coverType = MimikyuCoverType.THIS_IS,
                    primaryColor = spec.bottomColorStart,
                    seedQuery = "${spec.headline} ${spec.artistName} hits"
                )
            )
        }

        // 4. Shelf 3: "Albums featuring songs you like" (Strictly local ranking from history)
        val albumsYouLike = buildAlbumsYouLike(history)
        albumsYouLike.forEach { cacheMix(it) }

        // 5. Shelf 4: "Soundtrack your [Day] [TimeOfDay]" (Laya mood deduced)
        val (soundtrackShelves, soundtrackTitle) = buildSoundtrackYourDay(affinityTracks)
        soundtrackShelves.forEach { cacheMix(it) }

        // 6. Shelf 5: "Best of artists" (7-10 "THIS IS [Artist]" playlists)
        val bestOf = buildBestOfArtists(enrichedArtists)
        bestOf.forEach { cacheMix(it) }

        // 7. Shelf 6: "More like [Artist]" shelf (7-10 mixed items)
        val moreLike = buildMoreLikeShelf(primaryArtist, lastFmSimilar)
        moreLike.forEach { cacheMix(it) }

        // 8. Shelf 7: "Your favourite artists" (7-10 circular artist cards, strictly local)
        val favoriteArtists = buildFavoriteArtists(enrichedArtists)
        favoriteArtists.forEach { cacheMix(it) }

        // 9. Shelf 8: "Popular" (Aurora popular / charts & backend curated shelves, client stealth)
        val popularShelves = buildPopularShelves()
        popularShelves.forEach { cacheMix(it) }

        // 10. Shelf 9: "Top Genre Mixes" (YouTube playlist search for top genres, bottom anchored)
        val genreMixShelves = buildGenreMixShelves(affinityResult)
        genreMixShelves.forEach { cacheMix(it) }

        val primaryArtistCover = ArtistPortraitProvider.getCachedPortrait(primaryArtist)
            .ifBlank { stations.firstOrNull { it.title.contains(primaryArtist, ignoreCase = true) }?.coverUrl ?: "" }

        MimikyuShelvesResult(
            topMixes = topMixes,
            recommendedStations = stations,
            bestOfArtists = bestOf,
            favoriteArtists = favoriteArtists,
            moreLikeMixes = moreLike,
            moreLikeArtistName = primaryArtist,
            moreLikeArtistCover = primaryArtistCover,
            madeForYourVibe = madeForYourVibe,
            albumsYouLike = albumsYouLike,
            soundtrackYourDay = soundtrackShelves,
            soundtrackTitle = soundtrackTitle,
            popularShelves = popularShelves,
            genreMixShelves = genreMixShelves
        )
    }

    /**
     * Backward-compatible helper for HomeViewModel.
     */
    suspend fun generateDailyMixes(): List<DailyMixPlaylist> {
        val all = generateAllMimikyuShelves()
        return all.topMixes
    }

    private suspend fun buildTopMixes(
        userSeeds: List<Pair<String, String>>,
        eligibleTasteSeeds: List<ScoredTrackRecord>,
        familiarFavorites: List<ScoredTrackRecord>,
        onRepeatTracks: List<ScoredTrackRecord>,
        affinityResult: AffinityScoringResult?
    ): List<DailyMixPlaylist> = coroutineScope {
        val mixes = mutableListOf<DailyMixPlaylist>()

        // Continuous affinity artist mixes (top 3)
        affinityResult?.topArtists?.take(3)?.forEachIndexed { idx, candidate ->
            val artistName = candidate.name
            val cover = ArtistPortraitProvider.getCachedPortrait(artistName)
            mixes.add(
                DailyMixPlaylist(
                    id = -40L - idx,
                    title = "$artistName Mix",
                    subtitle = "Featuring $artistName and similar artists",
                    coverUrl = cover,
                    coverType = MimikyuCoverType.IMAGE_URL,
                    primaryColor = getMimikyuPastelColor(artistName),
                    seedQuery = "$artistName essential hits"
                )
            )
        }

        // Continuous affinity genre mixes (top 3)
        affinityResult?.topGenres?.take(3)?.forEachIndexed { idx, candidate ->
            val genreName = candidate.name.replaceFirstChar { it.uppercase() }
            val color = getMimikyuPastelColor(genreName)
            mixes.add(
                DailyMixPlaylist(
                    id = -50L - idx,
                    title = "$genreName Mix",
                    subtitle = "The best of $genreName, curated for you",
                    coverUrl = "",
                    coverType = MimikyuCoverType.IMAGE_URL,
                    primaryColor = color,
                    seedQuery = "$genreName top music mix"
                )
            )
        }

        // "On Repeat" shelf if user has repeat listens or high scores
        if (onRepeatTracks.isNotEmpty()) {
            val repeatItems = onRepeatTracks.map {
                QueueItem(
                    songId = it.songId,
                    title = it.title,
                    artist = it.artist,
                    coverUrl = it.coverUrl
                )
            }
            val onRepeatMix = DailyMixPlaylist(
                id = ON_REPEAT_ID,
                title = "On Repeat",
                subtitle = "Songs you've been playing again and again",
                coverUrl = repeatItems.firstOrNull()?.coverUrl ?: "",
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = Color(0xFF1DB954),
                tracks = repeatItems
            )
            mixes.add(onRepeatMix)
        }

        // Daily Mix 1-3 using Taste Clustering + InnerTube RDAMVM Radio Engine
        val tasteClusters = if (eligibleTasteSeeds.isNotEmpty()) {
            TasteClusterEngine(lastFmRepository).clusterHistory(eligibleTasteSeeds)
        } else emptyList()

        val familiarQueueItems = familiarFavorites.map {
            QueueItem(
                songId = it.songId,
                title = it.title,
                artist = it.artist,
                coverUrl = it.coverUrl
            )
        }

        val mixLabels = listOf("Daily Mix 1", "Daily Mix 2", "Daily Mix 3")
        val defaultColors = listOf(Color(0xFFE91E63), Color(0xFF2196F3), Color(0xFF4CAF50))
        val fallbackSeeds = if (userSeeds.isNotEmpty()) userSeeds.map { it.second } else FALLBACK_SEED_VIDEO_IDS

        for (i in 0 until 3) {
            val id = -10L - i
            val cluster = tasteClusters.getOrNull(i)
            val seedVideoId = cluster?.seedTracks?.firstOrNull()?.videoId?.takeIf { it.isNotBlank() }
                ?: fallbackSeeds.getOrNull(i)
                ?: FALLBACK_SEED_VIDEO_IDS[i % FALLBACK_SEED_VIDEO_IDS.size]

            val mix = buildMixFromSeed(
                id = id,
                mixLabel = mixLabels[i],
                subtitleOverride = cluster?.subtitle,
                seedVideoId = seedVideoId,
                trackCount = TRACKS_PER_MIX,
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = cluster?.primaryColor ?: defaultColors[i],
                familiarFavorites = familiarQueueItems
            )
            if (mix != null) mixes.add(mix)
        }

        // Vibe Mixes: Pop, Chill, Happy, Indie, Rock, R&B, 90s
        val vibeConfigs = listOf(
            Triple(-13L, "Pop Mix", "Dua Lipa"),
            Triple(-14L, "Chill Mix", "Frank Ocean"),
            Triple(-15L, "Happy Mix", "Bruno Mars"),
            Triple(-16L, "Indie Mix", "Tame Impala"),
            Triple(-17L, "Rock Mix", "Coldplay"),
            Triple(-18L, "R&B Mix", "SZA"),
            Triple(-19L, "90s Mix", "Michael Jackson")
        )

        for ((id, title, seedArtist) in vibeConfigs) {
            val color = getMimikyuPastelColor(title)
            val artistCover = ArtistPortraitProvider.getCachedPortrait(seedArtist)

            mixes.add(
                DailyMixPlaylist(
                    id = id,
                    title = title,
                    subtitle = "Personalized for you",
                    coverUrl = artistCover,
                    coverType = MimikyuCoverType.IMAGE_URL,
                    primaryColor = color,
                    seedQuery = "$title $seedArtist hits"
                )
            )
        }

        mixes
    }

    private suspend fun buildRecommendedStations(
        artists: List<String>,
        lastFmSimilar: List<String>
    ): List<DailyMixPlaylist> {
        val stationList = mutableListOf<DailyMixPlaylist>()
        val distinctArtists = artists.take(10)

        for ((index, artist) in distinctArtists.withIndex()) {
            val id = -100L - index
            val color = getMimikyuPastelColor(artist)

            // Discover 3 related artists for subtitle & side avatars
            val similar = if (index == 0 && lastFmSimilar.isNotEmpty()) {
                lastFmSimilar.take(3)
            } else {
                runCatching { lastFmRepository.getSimilarArtists(artist, limit = 3) }.getOrDefault(emptyList())
            }

            val subtitle = if (similar.isNotEmpty()) {
                "$artist, ${similar.joinToString(", ")} & more"
            } else {
                "$artist Radio"
            }

            val centerCover = ArtistPortraitProvider.getCachedPortrait(artist)
            val sideAvatars = similar.map { ArtistPortraitProvider.getCachedPortrait(it) }

            stationList.add(
                DailyMixPlaylist(
                    id = id,
                    title = artist,
                    subtitle = subtitle,
                    coverUrl = centerCover,
                    coverType = MimikyuCoverType.IMAGE_URL,
                    primaryColor = color,
                    mosaicImages = sideAvatars,
                    seedQuery = "$artist radio"
                )
            )
        }

        return stationList
    }

    /**
     * Shelf 3: "Albums featuring songs you like"
     * Strictly local ranking from library/history. Zero external network calls.
     */
    private fun buildAlbumsYouLike(history: List<RecentSong>): List<DailyMixPlaylist> {
        if (history.isEmpty()) return emptyList()

        val validAlbumSongs = history.filter { !it.album.isNullOrBlank() }
        if (validAlbumSongs.isEmpty()) return emptyList()

        // Group by normalized album title to combine plays
        val albumGroups = validAlbumSongs.groupBy { it.album?.trim()?.lowercase().orEmpty() }
            .filterKeys { it.isNotBlank() }

        // Rank by count of tracks/plays descending, then recency
        val sortedAlbums = albumGroups.values.sortedByDescending { songs ->
            songs.size * 10000000000L + (songs.maxOfOrNull { it.playTime } ?: 0L)
        }.take(10)

        return sortedAlbums.map { songs ->
            val first = songs.first()
            val albumName = first.album?.trim().orEmpty()
            val artistName = first.artist.trim()
            val albumId = YouTubeIdRegistry.registerAlbum(albumName)
            val coverUrl = songs.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl.orEmpty()

            DailyMixPlaylist(
                id = albumId,
                title = albumName,
                subtitle = artistName,
                coverUrl = coverUrl,
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = getMimikyuPastelColor(albumName),
                isAlbum = true
            )
        }
    }

    /**
     * Shelf 4: "Soundtrack your [Day] [TimeOfDay]"
     * Driven by LayaDecisionEngine.decideMoodForTimeOfDay(affinityTracks).
     * Cache key format: "${LocalDate.now()}_${decision.timeBlock}".
     */
    private fun buildSoundtrackYourDay(affinityTracks: List<AffinityInputTrack>): Pair<List<DailyMixPlaylist>, String> {
        val decision = LayaDecisionEngine.decideMoodForTimeOfDay(affinityTracks)
        val today = LocalDate.now()
        val cacheKey = "${today}_${decision.timeBlock}"

        soundtrackCache[cacheKey]?.let { return it }

        val moodName = decision.mood.replaceFirstChar { it.uppercase() }
        val timeBlock = decision.timeBlock
        val dayOfWeek = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

        val moodPresets = when (decision.mood) {
            "focus" -> listOf(
                "Deep Focus" to "Instrumental beats for concentration",
                "Focus Flow" to "Ambient soundscapes and steady rhythms",
                "Lofi Study Beats" to "Calm, relaxing beats to keep you centered",
                "Piano Meditation" to "Gentle acoustics for deep thought",
                "Morning Clarity" to "Crisp melodies to kick off your flow"
            )
            "chill" -> listOf(
                "Chill Vibes" to "Laid-back rhythms for total unwinding",
                "Sunset Lofi" to "Mellow frequencies as the evening settles",
                "Acoustic Evening" to "Intimate songs and warm harmonies",
                "Cozy Bedroom Pop" to "Soft vocals and dreamy guitar lines",
                "Late Night Waves" to "Slow tempo tracks for quiet moments"
            )
            "energy" -> listOf(
                "High Energy Hits" to "Fast tempos and dynamic beats to power you",
                "Electric Boost" to "Pumping bass and high-voltage tracks",
                "Peak Performance" to "Uplifting bangers for maximum momentum",
                "Dancefloor Ignition" to "Irresistible grooves to move your body",
                "Power Hour" to "Non-stop adrenaline and heavy beats"
            )
            "workout", "exercise" -> listOf(
                "Beast Mode Workout" to "Heavy bass and intense rhythms for training",
                "Running Rhythm" to "Steady 160 BPM bangers for the road",
                "Cardio Rush" to "Pure energy tracks to elevate heart rate",
                "Trap Gym Motivation" to "Raw grit and heavy drops",
                "Cooldown & Stretch" to "Slow, soothing frequencies after the grind"
            )
            "mellow" -> listOf(
                "Nighttime Calm" to "Slow acoustic strums and whisper vocals",
                "Dream Sequence" to "Ethereal tones for drifting off",
                "Midnight Melancholy" to "Deep, emotional tracks for the late hours",
                "Soft Horizon" to "Gentle ambient textures and slow beats",
                "Quiet Hours" to "Peaceful melodies to soothe the mind"
            )
            "party" -> listOf(
                "Club Bangers" to "Top dance anthems and party starters",
                "Weekend Pre-Game" to "Hype tracks to get the night rolling",
                "House & Bassline" to "Electrifying drops that never let up",
                "Festival Anthems" to "Huge drops and sing-along hooks",
                "Late Night Afterparty" to "Deep grooves and hypnotic basslines"
            )
            "fun" -> listOf(
                "Feel-Good Favorites" to "Sunny melodies and upbeat hooks",
                "Good Times" to "Catchy tunes to keep you smiling",
                "Pop Sunshine" to "Vibrant, happy tracks for any moment",
                "Golden Hour Jams" to "Warm, breezy songs with great vibes",
                "Upbeat Groove" to "Rhythms that make you want to dance"
            )
            "cleaning" -> listOf(
                "Cleaning Fever" to "Fast, infectious bops to get through the chores",
                "Upbeat Housekeeping" to "Fun, energetic tracks to keep you moving",
                "Groovy Motivation" to "Soulful, funk-infused rhythms",
                "Sunday Reset" to "Refreshing sounds for a clean slate",
                "Rhythm & Reset" to "Uplifting tunes while organizing your space"
            )
            "vacation" -> listOf(
                "Tropical Escape" to "Breezy beats and island vibes",
                "Sunny Getaway" to "Warm acoustic tracks and beachside jams",
                "Road Trip Horizons" to "Open-road anthems and catchy hooks",
                "Poolside Grooves" to "Sun-soaked chill electronic rhythms",
                "Summer Nostalgia" to "Classic hits from unforgettable summers"
            )
            "wedding" -> listOf(
                "Timeless Romance" to "Heartfelt love songs and acoustic ballads",
                "First Dance" to "Tender, emotional melodies that linger",
                "Celebration of Love" to "Joyous romantic anthems",
                "Acoustic Serenade" to "Gentle guitar and passionate vocals",
                "Sweet Devotion" to "Classic love ballads from all eras"
            )
            else -> listOf(
                "$moodName Flow" to "Curated selections for your $dayOfWeek $timeBlock",
                "Daily Pulse" to "Tracks matching your rhythm right now",
                "Mellow Moments" to "Subtle harmonies and gentle grooves",
                "Vibrant Daybreak" to "Inspiring tunes to keep you going",
                "Evening Drift" to "Smooth transitions into the night"
            )
        }

        val playlists = moodPresets.mapIndexed { idx, (title, subtitle) ->
            val color = getMimikyuPastelColor(title)
            DailyMixPlaylist(
                id = -800L - idx,
                title = title,
                subtitle = subtitle,
                coverUrl = "",
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = color,
                seedQuery = "$title $moodName music playlist"
            )
        }

        val result = playlists to decision.formattedTitle
        soundtrackCache[cacheKey] = result
        return result
    }

    private fun buildBestOfArtists(artists: List<String>): List<DailyMixPlaylist> {
        val seenKeys = mutableSetOf<String>()
        return artists.filter { it.isNotBlank() }
            .filter { artist ->
                val key = canonicalArtistKey(artist)
                key.isNotBlank() && seenKeys.add(key)
            }
            .take(10).mapIndexed { index, artist ->
                val cover = ArtistPortraitProvider.getCachedPortrait(artist)
                DailyMixPlaylist(
                    id = -200L - index,
                    title = artist,
                    subtitle = "This is $artist. The essential tracks, all in one playlist.",
                    coverUrl = cover,
                    coverType = MimikyuCoverType.IMAGE_URL,
                    primaryColor = getMimikyuPastelColor(artist),
                    seedQuery = "$artist essential top hits"
                )
            }
    }

    /**
     * Shelf 7: "Your Favorite Artists"
     * Strictly local lookup with circular avatars.
     */
    private fun buildFavoriteArtists(artists: List<String>): List<DailyMixPlaylist> {
        val seenKeys = mutableSetOf<String>()
        return artists.filter { it.isNotBlank() }
            .filter { artist ->
                val key = canonicalArtistKey(artist)
                key.isNotBlank() && seenKeys.add(key)
            }
            .take(10).mapIndexed { index, artist ->
                val artistId = YouTubeIdRegistry.registerArtistByName(artist)
                val cover = ArtistPortraitProvider.getCachedPortrait(artist)
                DailyMixPlaylist(
                    id = -300L - index,
                    title = artist,
                    subtitle = "Artist",
                    coverUrl = cover,
                    isArtistProfile = true,
                    artistId = artistId
                )
            }
    }

    private fun buildMoreLikeShelf(
        primaryArtist: String,
        lastFmSimilar: List<String>
    ): List<DailyMixPlaylist> {
        val items = mutableListOf<DailyMixPlaylist>()
        val primaryCover = ArtistPortraitProvider.getCachedPortrait(primaryArtist)

        // 1. Primary Artist Radio
        val related = if (lastFmSimilar.isNotEmpty()) lastFmSimilar.take(6) else listOf("Drake", "Frank Ocean", "Travis Scott", "Kendrick Lamar", "SZA")
        val sideAvatars = related.take(2).map { ArtistPortraitProvider.getCachedPortrait(it) }

        items.add(
            DailyMixPlaylist(
                id = -400L,
                title = primaryArtist,
                subtitle = "$primaryArtist Radio",
                coverUrl = primaryCover,
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = getMimikyuPastelColor(primaryArtist),
                mosaicImages = sideAvatars,
                seedQuery = "$primaryArtist radio"
            )
        )

        // 2. Similar artists from Last.fm
        related.forEachIndexed { idx, relArtist ->
            val relCover = ArtistPortraitProvider.getCachedPortrait(relArtist)
            val artistId = YouTubeIdRegistry.registerArtistByName(relArtist)
            items.add(
                DailyMixPlaylist(
                    id = -401L - idx,
                    title = relArtist,
                    subtitle = "Artist",
                    coverUrl = relCover,
                    isArtistProfile = true,
                    artistId = artistId
                )
            )
        }

        // 3. Two related genre mixes
        val rAndBCover = ArtistPortraitProvider.getCachedPortrait("SZA")
        val popCover = ArtistPortraitProvider.getCachedPortrait("Taylor Swift")
        items.add(
            DailyMixPlaylist(
                id = -410L,
                title = "R&B Mix",
                subtitle = "$primaryArtist, SZA, Giveon & more",
                coverUrl = rAndBCover,
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = Color(0xFFB490E0),
                seedQuery = "R&B hits"
            )
        )
        items.add(
            DailyMixPlaylist(
                id = -411L,
                title = "Pop Mix",
                subtitle = "$primaryArtist, Lana Del Rey, The Weeknd & more",
                coverUrl = popCover,
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = Color(0xFF7FE4C6),
                seedQuery = "Pop hits"
            )
        )

        return items
    }

    /**
     * Shelf 8: "Popular"
     * Popular shelves/playlists/charts from auroraApiClient?.getHome() or fallback.
     * Client stealth: Clean out "Spotify"/"Deezer" branding, replace with "Melodia".
     */
    private suspend fun buildPopularShelves(): List<DailyMixPlaylist> {
        val shelves = mutableListOf<DailyMixPlaylist>()

        try {
            val homeFeed = auroraApiClient?.getHome()
            if (homeFeed != null && homeFeed.sections.isNotEmpty()) {
                var itemIdx = 0
                for (section in homeFeed.sections) {
                    for (item in section.items) {
                        if (item.title.isBlank() || item.artworkUrl.isNullOrBlank()) continue
                        val cleanTitle = item.title
                            .replace("Spotify", "Melodia", ignoreCase = true)
                            .replace("Deezer", "Melodia", ignoreCase = true)
                        val cleanSubtitle = (item.type.ifBlank { section.title })
                            .replace("Spotify", "Melodia", ignoreCase = true)
                            .replace("Deezer", "Melodia", ignoreCase = true)

                        shelves.add(
                            DailyMixPlaylist(
                                id = -900L - itemIdx,
                                title = cleanTitle,
                                subtitle = cleanSubtitle,
                                coverUrl = item.artworkUrl.orEmpty(),
                                coverType = MimikyuCoverType.IMAGE_URL,
                                primaryColor = getMimikyuPastelColor(cleanTitle),
                                seedQuery = "$cleanTitle playlist"
                            )
                        )
                        itemIdx++
                        if (itemIdx >= 12) break
                    }
                    if (shelves.size >= 10) break
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "auroraApiClient.getHome() failed for popular shelves: ${e.message}")
        }

        if (shelves.isEmpty()) {
            val fallbackPopular = listOf(
                "Today's Top Hits" to "The hottest tracks right now on Melodia",
                "Global Top 50" to "Daily update of the most played tracks right now",
                "Viral Hits Melodia" to "The songs going viral across the globe",
                "Mega Hit Mix" to "A continuous mix of today's biggest hits",
                "All Out 2020s" to "The biggest tracks of the decade so far",
                "Pop Rising" to "Who's next in pop music, today",
                "Hot Hits International" to "Chart-topping international sensations",
                "Party Anthems" to "Pure dance hits to turn up any celebration"
            )
            fallbackPopular.forEachIndexed { idx, (title, sub) ->
                shelves.add(
                    DailyMixPlaylist(
                        id = -900L - idx,
                        title = title,
                        subtitle = sub,
                        coverUrl = "",
                        coverType = MimikyuCoverType.IMAGE_URL,
                        primaryColor = getMimikyuPastelColor(title),
                        seedQuery = "$title popular hits"
                    )
                )
            }
        }

        return shelves
    }

    /**
     * Shelf 9: "Top Genre Mixes"
     * Direct YouTube playlist search for top genres from history (or default fallbacks).
     * Strictly anchored at the bottom of the feed.
     */
    private fun buildGenreMixShelves(affinityResult: AffinityScoringResult?): List<DailyMixPlaylist> {
        val userGenres = affinityResult?.topGenres?.map { it.name.replaceFirstChar { c -> c.uppercase() } } ?: emptyList()
        val defaultGenres = listOf("Pop", "Hip-Hop", "R&B", "Rock", "Indie", "Electronic", "Acoustic")
        val combinedGenres = (userGenres + defaultGenres).distinct().take(8)

        return combinedGenres.mapIndexed { idx, genre ->
            val title = "$genre Mix"
            val subtitle = "The quintessential $genre tracks, selected for you"
            DailyMixPlaylist(
                id = -950L - idx,
                title = title,
                subtitle = subtitle,
                coverUrl = "",
                coverType = MimikyuCoverType.IMAGE_URL,
                primaryColor = getMimikyuPastelColor(genre),
                seedQuery = "$genre hits playlist"
            )
        }
    }

    /**
     * Resolves playable tracks on-demand when a playlist is clicked.
     * When resolving candidates, uses candidate selection via LayaDecisionEngine.selectBestSong.
     */
    suspend fun getOrGeneratePlaylist(id: Long): DailyMixPlaylist? = withContext(Dispatchers.IO) {
        getCachedMix(id)?.takeIf { it.tracks.isNotEmpty() }?.let { return@withContext it }

        val cached = getCachedMix(id)
        val familiar = tasteSignalScorer?.getFamiliarFavorites()?.map {
            QueueItem(
                songId = it.songId,
                title = it.title,
                artist = it.artist,
                coverUrl = it.coverUrl
            )
        } ?: emptyList()

        val tracks = if (!cached?.seedVideoId.isNullOrBlank()) {
            val candidates = InnerTubeRadioEngine.getRadioCandidates(cached.seedVideoId, count = 35)
            MimikyuPolishFilter.applyPolish(
                novelCandidates = candidates,
                familiarFavorites = familiar,
                targetCount = TRACKS_PER_MIX
            )
        } else {
            val query = cached?.seedQuery?.ifBlank { null } ?: cached?.title ?: "Popular music"
            try {
                val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val songItems = searchRes?.items?.filterIsInstance<SongItem>() ?: emptyList()

                // Candidate selection via LayaDecisionEngine.selectBestSong
                val sortedSongItems = if (songItems.isNotEmpty() && cached != null) {
                    val targetTitle = cached.title
                    val targetArtist = cached.subtitle.split(",", "/", "&").firstOrNull()?.trim() ?: ""
                    val targetInfo = ParsedTrackInfo(title = targetTitle, artist = targetArtist, durationMs = 0L)
                    val bestMatch = LayaDecisionEngine.selectBestSong(targetInfo, songItems).selected
                    if (bestMatch != null) {
                        listOf(bestMatch) + (songItems - bestMatch)
                    } else {
                        songItems
                    }
                } else {
                    songItems
                }

                val raw = sortedSongItems.take(TRACKS_PER_MIX + 10).mapNotNull { song ->
                    try {
                        val cleanTitle = cleanMetadataText(song.title)
                        val cleanArtist = cleanMetadataText(song.artists.joinToString(", ") { cleanMetadataText(it.name) })
                        val songId = YouTubeIdRegistry.register(
                            videoId = song.id,
                            title = cleanTitle,
                            artist = cleanArtist,
                            coverUrl = song.thumbnail.orEmpty(),
                            durationSec = song.duration ?: 0
                        )
                        val studioCover = YouTubeIdRegistry.getCoverUrl(songId).takeIf { it.isNotBlank() } ?: song.thumbnail.orEmpty()
                        QueueItem(
                            songId = songId,
                            title = cleanTitle,
                            artist = cleanArtist,
                            coverUrl = studioCover,
                            videoId = song.id,
                            album = cleanMetadataText(song.album?.name.orEmpty()),
                            durationSec = song.duration ?: 0
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                MimikyuPolishFilter.applyPolish(
                    novelCandidates = raw,
                    familiarFavorites = familiar,
                    targetCount = TRACKS_PER_MIX
                )
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (tracks.isEmpty()) return@withContext cached

        val updated = (cached ?: DailyMixPlaylist(
            id = id,
            title = "Personalized Mix",
            subtitle = "Made for you",
            coverUrl = tracks.firstOrNull()?.coverUrl ?: ""
        )).copy(
            tracks = tracks,
            coverUrl = if (cached?.coverUrl?.startsWith("http") == true && !cached.coverUrl.contains("unsplash")) cached.coverUrl else (tracks.firstOrNull()?.coverUrl ?: cached?.coverUrl ?: "")
        )

        cacheMix(updated)
        updated
    }

    private suspend fun fetchUserTopArtists(uid: String): List<Pair<String, String>> {
        val seeds = mutableListOf<Pair<String, String>>()
        val seenArtists = mutableSetOf<String>()

        try {
            val topSongsSnapshot = firestore
                .collection("users")
                .document(uid)
                .collection("topSongs")
                .orderBy("plays", Query.Direction.DESCENDING)
                .limit(TOP_ARTISTS_FETCH.toLong())
                .get()
                .await()

            for (doc in topSongsSnapshot.documents) {
                val videoId = doc.id
                val artist = doc.getString("artist") ?: continue
                val primaryArtist = artist.split(",", "/", "&").firstOrNull()?.trim() ?: artist

                if (seenArtists.add(primaryArtist)) {
                    seeds.add(primaryArtist to videoId)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read user topSongs from Firestore: ${e.message}")
        }

        return seeds
    }

    private suspend fun buildMixFromSeed(
        id: Long,
        mixLabel: String,
        subtitleOverride: String? = null,
        seedVideoId: String,
        trackCount: Int,
        coverType: MimikyuCoverType = MimikyuCoverType.DAILY_MIX,
        primaryColor: Color = Color(0xFF1DB954),
        familiarFavorites: List<QueueItem> = emptyList()
    ): DailyMixPlaylist? {
        val radioCandidates = InnerTubeRadioEngine.getRadioCandidates(seedVideoId, count = 35)
        if (radioCandidates.isEmpty()) return null

        val polishedTracks = MimikyuPolishFilter.applyPolish(
            novelCandidates = radioCandidates,
            familiarFavorites = familiarFavorites,
            targetCount = trackCount
        )
        if (polishedTracks.isEmpty()) return null

        val topArtists = polishedTracks
            .map { it.artist.split(",").firstOrNull()?.trim() ?: it.artist }
            .distinct()
            .take(3)
        val defaultSubtitle = when {
            topArtists.size >= 3 -> "${topArtists[0]}, ${topArtists[1]} & more"
            topArtists.size == 2 -> "${topArtists[0]} & ${topArtists[1]}"
            else -> topArtists.firstOrNull() ?: ""
        }

        val coverUrl = polishedTracks.firstOrNull()?.coverUrl ?: ""

        return DailyMixPlaylist(
            id = id,
            title = mixLabel,
            subtitle = subtitleOverride ?: defaultSubtitle,
            coverUrl = coverUrl,
            coverType = coverType,
            primaryColor = primaryColor,
            tracks = polishedTracks,
            seedVideoId = seedVideoId
        )
    }
}
