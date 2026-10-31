package com.lin0721.linmusic.core.player

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.lin0721.linmusic.core.auth.AuthState
import com.lin0721.linmusic.core.log.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interface responsible for writing play recording batch to Firestore.
 */
interface PlayBatchWriter {
    fun writePlayBatch(
        uid: String,
        videoId: String,
        title: String,
        artist: String,
        artistId: String?,
        album: String?,
        thumbnailUrl: String,
        durationSec: Long,
        listenedSec: Long,
        dayKey: String,
        monthKey: String,
        artistKey: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    )
}

/**
 * Default Firestore implementation writing ONE single WriteBatch.
 * Writing:
 *   a) users/{uid}/plays/{autoId}
 *   b) users/{uid}/stats/summary (increment totalPlays & totalSeconds)
 *   c) users/{uid}/statsDaily/{dayKey} (increment plays & seconds)
 *   d) users/{uid}/statsMonthly/{monthKey} (increment plays & seconds)
 *   e) users/{uid}/topSongs/{videoId} (increment plays & seconds; set title, artist, thumbnailUrl)
 *   f) users/{uid}/topArtists/{artistKey} (increment plays & seconds; set name)
 */
class FirestorePlayBatchWriter(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : PlayBatchWriter {
    companion object {
        private const val TAG = "FirestorePlayBatch"
    }

    override fun writePlayBatch(
        uid: String,
        videoId: String,
        title: String,
        artist: String,
        artistId: String?,
        album: String?,
        thumbnailUrl: String,
        durationSec: Long,
        listenedSec: Long,
        dayKey: String,
        monthKey: String,
        artistKey: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val batch = firestore.batch()
        val userDoc = firestore.collection("users").document(uid)

        // a) users/{uid}/plays/{autoId}
        val playRef = userDoc.collection("plays").document()
        val playData = hashMapOf(
            "videoId" to videoId,
            "title" to title,
            "artist" to artist,
            "artistId" to artistId,
            "album" to album,
            "thumbnailUrl" to thumbnailUrl,
            "durationSec" to durationSec,
            "listenedSec" to listenedSec,
            "startedAt" to FieldValue.serverTimestamp(),
            "dayKey" to dayKey
        )
        batch.set(playRef, playData)

        // b) users/{uid}/stats/summary
        val summaryRef = userDoc.collection("stats").document("summary")
        val summaryData = hashMapOf<String, Any>(
            "totalPlays" to FieldValue.increment(1),
            "totalSeconds" to FieldValue.increment(listenedSec)
        )
        batch.set(summaryRef, summaryData, SetOptions.merge())

        // c) users/{uid}/statsDaily/{dayKey}
        val dailyRef = userDoc.collection("statsDaily").document(dayKey)
        val dailyData = hashMapOf<String, Any>(
            "plays" to FieldValue.increment(1),
            "seconds" to FieldValue.increment(listenedSec)
        )
        batch.set(dailyRef, dailyData, SetOptions.merge())

        // users/{uid}/statsMonthly/{yyyy-MM}
        val monthlyRef = userDoc.collection("statsMonthly").document(monthKey)
        val monthlyData = hashMapOf<String, Any>(
            "plays" to FieldValue.increment(1),
            "seconds" to FieldValue.increment(listenedSec)
        )
        batch.set(monthlyRef, monthlyData, SetOptions.merge())

        // d) users/{uid}/topSongs/{videoId}
        val topSongRef = userDoc.collection("topSongs").document(videoId)
        val topSongData = hashMapOf<String, Any>(
            "plays" to FieldValue.increment(1),
            "seconds" to FieldValue.increment(listenedSec),
            "title" to title,
            "artist" to artist,
            "thumbnailUrl" to thumbnailUrl
        )
        batch.set(topSongRef, topSongData, SetOptions.merge())

        // e) users/{uid}/topArtists/{artistKey}
        val topArtistRef = userDoc.collection("topArtists").document(artistKey)
        val topArtistData = hashMapOf<String, Any>(
            "plays" to FieldValue.increment(1),
            "seconds" to FieldValue.increment(listenedSec),
            "name" to artist
        )
        batch.set(topArtistRef, topArtistData, SetOptions.merge())

        batch.commit()
            .addOnSuccessListener {
                AppLogger.i(TAG, "Recorded play in Firestore for $title ($videoId), dayKey=$dayKey")
                onSuccess()
            }
            .addOnFailureListener { e ->
                AppLogger.e(TAG, "Failed to commit play batch: ${e.message}", e)
                onFailure(e)
            }
    }
}

/**
 * Counts listened time only while actively playing (ignores pauses, seek jumps, and repeat loops).
 * When a track reaches 30 seconds of listened time (or 50% if under 60s), records exactly one play.
 */
class PlayRecorder(
    private val authState: AuthState,
    private val batchWriter: PlayBatchWriter = FirestorePlayBatchWriter()
) {
    companion object {
        private const val TAG = "PlayRecorder"
    }

    var currentVideoId: String? = null
        private set
    var currentTitle: String = ""
        private set
    var currentArtist: String = ""
        private set
    var currentArtistId: String? = null
        private set
    var currentAlbum: String? = null
        private set
    var currentThumbnailUrl: String = ""
        private set
    var currentDurationSec: Long = 0L
        private set

    var sessionRecorded: Boolean = false
        private set
    var sessionActiveMs: Long = 0L
        private set

    fun onTrackStarted(
        videoId: String,
        title: String,
        artist: String,
        artistId: String? = null,
        album: String? = null,
        thumbnailUrl: String = "",
        durationSec: Long = 0L
    ) {
        currentVideoId = videoId
        currentTitle = title
        currentArtist = artist
        currentArtistId = artistId
        currentAlbum = album
        currentThumbnailUrl = thumbnailUrl
        currentDurationSec = durationSec

        sessionRecorded = false
        sessionActiveMs = 0L
        AppLogger.i(TAG, "New play session started for $title ($videoId), duration=${durationSec}s")
    }

    fun updateDuration(durationSec: Long) {
        if (durationSec > 0) {
            currentDurationSec = durationSec
        }
    }

    fun onPlaybackTick(isPlaying: Boolean, durationMs: Long = 0L, deltaMs: Long = 1000L) {
        if (!isPlaying || currentVideoId == null || sessionRecorded) return

        sessionActiveMs += deltaMs

        val effectiveDurationSec = if (currentDurationSec > 0) currentDurationSec else durationMs / 1000
        val listenedSec = sessionActiveMs / 1000

        val targetSec = if (effectiveDurationSec in 1..59) {
            (effectiveDurationSec / 2).coerceAtLeast(1L)
        } else {
            30L
        }

        if (listenedSec >= targetSec) {
            recordPlay(listenedSec, effectiveDurationSec)
        }
    }

    fun resetSession() {
        sessionRecorded = false
        sessionActiveMs = 0L
        currentVideoId = null
    }

    private fun recordPlay(listenedSec: Long, durationSec: Long) {
        if (sessionRecorded) return
        sessionRecorded = true

        val uid = authState.currentUid
        val videoId = currentVideoId
        if (uid.isNullOrBlank() || videoId.isNullOrBlank()) {
            AppLogger.d(TAG, "Skip recording play: uid is null or user not logged in")
            return
        }

        val now = Date()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(now)
        val artistKey = currentArtistId?.takeIf { it.isNotBlank() }
            ?: currentArtist.trim().lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_").ifEmpty { "unknown" }

        batchWriter.writePlayBatch(
            uid = uid,
            videoId = videoId,
            title = currentTitle,
            artist = currentArtist,
            artistId = currentArtistId,
            album = currentAlbum,
            thumbnailUrl = currentThumbnailUrl,
            durationSec = durationSec,
            listenedSec = listenedSec,
            dayKey = dayKey,
            monthKey = monthKey,
            artistKey = artistKey
        )
    }
}
