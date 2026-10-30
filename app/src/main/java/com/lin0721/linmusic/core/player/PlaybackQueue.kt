package com.lin0721.linmusic.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// PlayQueue of ： and Play、Current、Play and Play
class PlaybackQueue {

    // of ，Shuffle
    private var originalItems: List<QueueItem> = emptyList()

    // of Play
    private var playItems: List<QueueItem> = emptyList()

    // of Queue
    private var snapshotItems: List<QueueItem> = emptyList()
    private var snapshotIndex: Int = -1
    private var snapshotContext: String? = null

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.LIST_LOOP)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val _playContext = MutableStateFlow<String?>(null)
    val playContext: StateFlow<String?> = _playContext.asStateFlow()

    val size: Int get() = playItems.size

    val isEmpty: Boolean get() = playItems.isEmpty()

    val original: List<QueueItem> get() = originalItems

    fun itemAt(index: Int): QueueItem? = playItems.getOrNull(index)

    fun currentItem(): QueueItem? = playItems.getOrNull(_currentIndex.value)

    // Current（） of ，： currentIndex  playItems  of ，
    // original  of ， original  of Confirm
    fun currentIndexInOriginal(): Int {
        val current = currentItem() ?: return _currentIndex.value
        val idx = originalItems.indexOfFirst { it.songId == current.songId }
        return if (idx >= 0) idx else _currentIndex.value
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
    }

    fun setPlayContext(context: String?) {
        _playContext.value = context
    }

    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index
    }

    fun updateCoverUrl(songId: Long, newCoverUrl: String) {
        if (newCoverUrl.isBlank()) return
        val currentPlayItems = playItems
        if (currentPlayItems.none { it.songId == songId }) return

        playItems = currentPlayItems.map {
            if (it.songId == songId) it.copy(coverUrl = newCoverUrl) else it
        }
        originalItems = originalItems.map {
            if (it.songId == songId) it.copy(coverUrl = newCoverUrl) else it
        }
        _items.value = playItems
    }

    fun updateItemMetadata(
        songId: Long,
        title: String? = null,
        artist: String? = null,
        coverUrl: String? = null,
        videoId: String? = null,
        album: String? = null
    ) {
        val currentPlayItems = playItems
        if (currentPlayItems.none { it.songId == songId }) return

        playItems = currentPlayItems.map {
            if (it.songId == songId) {
                it.copy(
                    title = if (!title.isNullOrBlank()) title else it.title,
                    artist = if (!artist.isNullOrBlank()) artist else it.artist,
                    coverUrl = if (!coverUrl.isNullOrBlank()) coverUrl else it.coverUrl,
                    videoId = if (!videoId.isNullOrBlank()) videoId else it.videoId,
                    album = if (!album.isNullOrBlank()) album else it.album
                )
            } else it
        }
        originalItems = originalItems.map {
            if (it.songId == songId) {
                it.copy(
                    title = if (!title.isNullOrBlank()) title else it.title,
                    artist = if (!artist.isNullOrBlank()) artist else it.artist,
                    coverUrl = if (!coverUrl.isNullOrBlank()) coverUrl else it.coverUrl,
                    videoId = if (!videoId.isNullOrBlank()) videoId else it.videoId,
                    album = if (!album.isNullOrBlank()) album else it.album
                )
            } else it
        }
        _items.value = playItems
    }

    fun nextIndex(): Int = nextIndexFrom(_currentIndex.value)

    fun nextIndexFrom(index: Int): Int = if (playItems.isEmpty()) -1 else (index + 1) % playItems.size

    fun previousIndex(): Int {
        if (playItems.isEmpty()) return -1
        val cur = _currentIndex.value
        return if (cur - 1 < 0) playItems.size - 1 else cur - 1
    }

    // CurrentPlayNext，QueueBack null
    fun nextItemByMode(): QueueItem? {
        val total = playItems.size
        if (total <= 1) return null
        val nextIdx = when (_playMode.value) {
            PlayMode.SINGLE_LOOP -> _currentIndex.value
            PlayMode.SEQUENTIAL -> {
                val next = _currentIndex.value + 1
                if (next < total) next else -1
            }
            else -> (_currentIndex.value + 1) % total
        }
        return if (nextIdx >= 0) playItems.getOrNull(nextIdx) else null
    }

    // Queue；index Current items（） of 。
    // Current playMode ， playItems  of ， of
    fun restore(items: List<QueueItem>, index: Int, context: String?) {
        if (items.isEmpty()) return
        originalItems = items
        val safeIndex = index.coerceIn(0, items.size - 1)
        if (_playMode.value == PlayMode.SHUFFLE) {
            playItems = shufflePreservingCurrent(items, safeIndex)
            _currentIndex.value = 0
        } else {
            playItems = items
            _currentIndex.value = safeIndex
        }
        _items.value = playItems
        _playContext.value = context
    }

    // Queue，
    fun replaceAll(items: List<QueueItem>, startIndex: Int) {
        if (items.isEmpty()) return
        originalItems = items
        val safeIndex = startIndex.coerceIn(0, items.size - 1)
        if (_playMode.value == PlayMode.SHUFFLE) {
            playItems = shufflePreservingCurrent(items, safeIndex)
            _currentIndex.value = 0
        } else {
            playItems = items
            _currentIndex.value = safeIndex
        }
        _items.value = playItems
    }

    // QueueTracks
    fun replaceWithSingle(item: QueueItem) {
        originalItems = listOf(item)
        playItems = listOf(item)
        _currentIndex.value = 0
        _items.value = playItems
    }

    // “Next”
    fun insertNext(items: List<QueueItem>) {
        if (items.isEmpty()) return
        val curIndex = _currentIndex.value
        val targetInsertIndex = curIndex + 1

        // Current of PlayQueue
        val mutablePlay = playItems.toMutableList()
        mutablePlay.addAll(targetInsertIndex.coerceIn(0, mutablePlay.size), items)
        playItems = mutablePlay

        // Queue
        val currentItem = playItems.getOrNull(curIndex)
        val mutableOrig = originalItems.toMutableList()
        if (currentItem != null) {
            val origIdx = mutableOrig.indexOfFirst { it.songId == currentItem.songId }
            if (origIdx >= 0) {
                mutableOrig.addAll(origIdx + 1, items)
            } else {
                mutableOrig.addAll(items)
            }
        } else {
            mutableOrig.addAll(items)
        }
        originalItems = mutableOrig

        _items.value = playItems
    }

    // ，Back of ，Back -1
    fun removeAt(index: Int): Int {
        if (index < 0 || index >= playItems.size || playItems.size <= 1) return -1
        val removedItem = playItems[index]
        val mutablePlay = playItems.toMutableList()
        mutablePlay.removeAt(index)
        playItems = mutablePlay

        val mutableOrig = originalItems.toMutableList()
        val origIdx = mutableOrig.indexOfFirst { it.songId == removedItem.songId }
        if (origIdx >= 0) mutableOrig.removeAt(origIdx)
        originalItems = mutableOrig

        var replayIndex = -1
        when {
            index < _currentIndex.value -> _currentIndex.value -= 1
            index == _currentIndex.value -> {
                val newIdx = _currentIndex.value.coerceAtMost(playItems.size - 1)
                _currentIndex.value = newIdx
                replayIndex = newIdx
            }
        }
        _items.value = playItems
        return replayIndex
    }

    // ，Back of
    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from < 0 || from >= playItems.size || to < 0 || to >= playItems.size) return false
        val mutable = playItems.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        playItems = mutable
        originalItems = mutable.toList()

        val cur = _currentIndex.value
        _currentIndex.value = when (cur) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) ->
                if (from < to) cur - 1 else cur + 1
            else -> cur
        }
        _items.value = playItems
        return true
    }

    // PlayPlay
    fun applyMode(newMode: PlayMode, currentItem: QueueItem?) {
        _playMode.value = newMode
        if (originalItems.isEmpty()) return
        val target = currentItem ?: currentItem() ?: playItems.getOrNull(_currentIndex.value) ?: originalItems.firstOrNull() ?: return
        when (newMode) {
            PlayMode.SHUFFLE -> {
                val origIdx = originalItems.indexOfFirst { it.songId == target.songId }.takeIf { it != -1 } ?: 0
                playItems = shufflePreservingCurrent(originalItems, origIdx)
                _currentIndex.value = 0
            }
            PlayMode.LIST_LOOP, PlayMode.SEQUENTIAL -> {
                val origIdx = originalItems.indexOfFirst { it.songId == target.songId }
                playItems = originalItems
                _currentIndex.value = if (origIdx >= 0) origIdx else _currentIndex.value.coerceIn(0, (originalItems.size - 1).coerceAtLeast(0))
            }
            PlayMode.SINGLE_LOOP -> return
        }
        _items.value = playItems
    }

    // CurrentPlay，Back of ；CurrentPlayClearQueueBack null
    fun keepOnlyCurrent(): QueueItem? {
        val curIndex = _currentIndex.value
        val currentTrackItem = if (curIndex in playItems.indices) playItems[curIndex] else null
        if (currentTrackItem != null) {
            originalItems = listOf(currentTrackItem)
            playItems = listOf(currentTrackItem)
            _currentIndex.value = 0
            _items.value = playItems
        } else {
            originalItems = emptyList()
            playItems = emptyList()
            _currentIndex.value = -1
            _items.value = emptyList()
        }
        return currentTrackItem
    }

    // ，
    fun appendAfter(index: Int, items: List<QueueItem>) {
        if (items.isEmpty()) return
        val newQueue = originalItems.take(index + 1) + items
        originalItems = newQueue
        playItems = newQueue
        _items.value = playItems
    }

    // Append items to the end of the queue without truncating existing items
    fun append(items: List<QueueItem>) {
        if (items.isEmpty()) return
        originalItems = originalItems + items
        playItems = playItems + items
        _items.value = playItems
    }

    // CurrentQueue and Play，/Heartbeat Mode
    fun takeSnapshot() {
        snapshotItems = originalItems
        snapshotIndex = _currentIndex.value
        snapshotContext = _playContext.value
    }

    // of Queue，CurrentNow Playing of Queue
    fun restoreSnapshot() {
        if (snapshotItems.isEmpty()) return
        originalItems = snapshotItems
        val currentTrackItem = playItems.getOrNull(_currentIndex.value)
        val newIndex = if (currentTrackItem != null) {
            val idx = snapshotItems.indexOfFirst { it.songId == currentTrackItem.songId }
            if (idx != -1) idx else snapshotIndex.coerceIn(0, snapshotItems.size - 1)
        } else {
            snapshotIndex.coerceIn(0, snapshotItems.size - 1)
        }

        if (_playMode.value == PlayMode.SHUFFLE) {
            playItems = shufflePreservingCurrent(snapshotItems, newIndex)
            _currentIndex.value = 0
        } else {
            playItems = snapshotItems
            _currentIndex.value = newIndex
        }

        _items.value = playItems
        _playContext.value = snapshotContext
        snapshotItems = emptyList()
        snapshotIndex = -1
        snapshotContext = null
    }

    private fun shufflePreservingCurrent(items: List<QueueItem>, currentIndex: Int): List<QueueItem> {
        if (items.isEmpty()) return items
        val safeIndex = currentIndex.coerceIn(0, items.size - 1)
        val current = items[safeIndex]
        val otherItems = items.filterIndexed { index, _ -> index != safeIndex }.shuffled()
        return listOf(current) + otherItems
    }
}
