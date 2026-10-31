package com.lin0721.linmusic.feature.search.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.ui.components.MimikyuDragHandle
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.feature.search.domain.IdentifiedSong
import com.lin0721.linmusic.feature.search.domain.SongIdentifierEngine
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.sin

private val MimikyuGreen = Color(0xFF1ED760)
private val MimikyuDarkCard = Color(0xFF242426)
private val MimikyuSubtext = Color(0xFFA7A7A7)

sealed interface IdentifierUiState {
    data object Listening : IdentifierUiState
    data class Identifying(val transcript: String) : IdentifierUiState
    data class Found(val song: IdentifiedSong) : IdentifierUiState
    data class NotFound(val transcript: String) : IdentifierUiState
    data class Error(val message: String) : IdentifierUiState
    data object PermissionDenied : IdentifierUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongIdentifierSheet(
    engine: SongIdentifierEngine,
    onDismiss: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onSearchKeyword: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<IdentifierUiState>(IdentifierUiState.Listening) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var partialTranscript by remember { mutableStateOf("") }
    var manualInputText by remember { mutableStateOf("") }

    // Audio Preview MediaPlayer
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
        isPreviewPlaying = false
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPreview()
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            state = IdentifierUiState.Listening
        } else {
            state = IdentifierUiState.PermissionDenied
        }
    }

    // SpeechRecognizer holder
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun startListening() {
        stopPreview()
        partialTranscript = ""
        rmsLevel = 0f

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            state = IdentifierUiState.PermissionDenied
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            state = IdentifierUiState.Error("Speech recognition is not supported on this device.")
            return
        }

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    state = IdentifierUiState.Listening
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    // Normalize -2dB to 12dB -> 0f..1f
                    val normalized = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
                    rmsLevel = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    rmsLevel = 0f
                }

                override fun onError(error: Int) {
                    Timber.tag("SongIdentifier").w("SpeechRecognizer error code: %d", error)
                    rmsLevel = 0f
                    if (state is IdentifierUiState.Identifying) return

                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            if (partialTranscript.isNotBlank()) {
                                // Attempt identification on what we caught
                                val query = partialTranscript
                                state = IdentifierUiState.Identifying(query)
                                scope.launch {
                                    val match = engine.identifyFromQuery(query)
                                    state = if (match != null) IdentifierUiState.Found(match) else IdentifierUiState.NotFound(query)
                                }
                            } else {
                                state = IdentifierUiState.NotFound("")
                            }
                        }
                        SpeechRecognizer.ERROR_AUDIO -> state = IdentifierUiState.Error("Audio recording error. Check your microphone.")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> state = IdentifierUiState.PermissionDenied
                        else -> {
                            if (partialTranscript.isNotBlank()) {
                                val query = partialTranscript
                                state = IdentifierUiState.Identifying(query)
                                scope.launch {
                                    val match = engine.identifyFromQuery(query)
                                    state = if (match != null) IdentifierUiState.Found(match) else IdentifierUiState.NotFound(query)
                                }
                            } else {
                                state = IdentifierUiState.Error("Listening paused. Tap retry to listen again.")
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val query = matches?.firstOrNull()?.trim() ?: partialTranscript.trim()
                    if (query.isNotBlank()) {
                        state = IdentifierUiState.Identifying(query)
                        scope.launch {
                            val match = engine.identifyFromQuery(query)
                            state = if (match != null) IdentifierUiState.Found(match) else IdentifierUiState.NotFound(query)
                        }
                    } else {
                        state = IdentifierUiState.NotFound("")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val current = partials?.firstOrNull()?.trim().orEmpty()
                    if (current.isNotBlank()) {
                        partialTranscript = current
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            state = IdentifierUiState.Listening
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Timber.tag("SongIdentifier").e(e, "Failed to start speech recognizer")
            state = IdentifierUiState.Error("Failed to access microphone: ${e.message}")
        }
    }

    DisposableEffect(Unit) {
        startListening()
        onDispose {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            stopPreview()
            onDismiss()
        },
        containerColor = Color(0xFF141415),
        shape = BottomSheetShape,
        dragHandle = { MimikyuDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MimikyuGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.Soundwave),
                            contentDescription = null,
                            tint = MimikyuGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Song Identifier",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Close Button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF282828))
                        .clickable {
                            stopPreview()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Close),
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Main Content by State
            when (val currentState = state) {
                is IdentifierUiState.Listening -> {
                    ListeningVisualizer(
                        rmsLevel = rmsLevel,
                        partialTranscript = partialTranscript,
                        onTapMic = { startListening() }
                    )
                }

                is IdentifierUiState.Identifying -> {
                    IdentifyingVisualizer(query = currentState.transcript)
                }

                is IdentifierUiState.Found -> {
                    FoundSongCard(
                        song = currentState.song,
                        isPreviewPlaying = isPreviewPlaying,
                        onTogglePreview = {
                            val previewUrl = currentState.song.previewUrl
                            if (previewUrl == null) return@FoundSongCard
                            if (isPreviewPlaying) {
                                stopPreview()
                            } else {
                                stopPreview()
                                try {
                                    val mp = MediaPlayer().apply {
                                        setAudioAttributes(
                                            AudioAttributes.Builder()
                                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                                .build()
                                        )
                                        setDataSource(previewUrl)
                                        prepareAsync()
                                        setOnPreparedListener {
                                            start()
                                            isPreviewPlaying = true
                                        }
                                        setOnCompletionListener {
                                            isPreviewPlaying = false
                                        }
                                    }
                                    previewPlayer = mp
                                } catch (e: Exception) {
                                    Timber.tag("SongIdentifier").e(e, "Audio preview playback failed")
                                    isPreviewPlaying = false
                                }
                            }
                        },
                        onPlayNow = {
                            stopPreview()
                            onDismiss()
                            val track = currentState.song.mimikyuTrack
                            if (track != null) {
                                onPlayTrack(track)
                            } else {
                                onSearchKeyword("${currentState.song.title} ${currentState.song.artist}")
                            }
                        },
                        onSearchInMimikyu = {
                            stopPreview()
                            onDismiss()
                            onSearchKeyword("${currentState.song.title} ${currentState.song.artist}")
                        },
                        onIdentifyAgain = {
                            stopPreview()
                            startListening()
                        }
                    )
                }

                is IdentifierUiState.NotFound -> {
                    NotFoundView(
                        transcript = currentState.transcript,
                        onRetry = { startListening() },
                        onSearchQuery = {
                            stopPreview()
                            onDismiss()
                            onSearchKeyword(it)
                        }
                    )
                }

                is IdentifierUiState.Error -> {
                    ErrorView(
                        message = currentState.message,
                        onRetry = { startListening() }
                    )
                }

                is IdentifierUiState.PermissionDenied -> {
                    PermissionDeniedView(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Fallback manual query field at bottom
            ManualSearchField(
                value = manualInputText,
                onValueChange = { manualInputText = it },
                onSearch = {
                    if (manualInputText.isNotBlank()) {
                        val q = manualInputText.trim()
                        state = IdentifierUiState.Identifying(q)
                        scope.launch {
                            val match = engine.identifyFromQuery(q)
                            state = if (match != null) IdentifierUiState.Found(match) else IdentifierUiState.NotFound(q)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ListeningVisualizer(
    rmsLevel: Float,
    partialTranscript: String,
    onTapMic: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "listening_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val activeScale = (1.0f + rmsLevel * 0.4f).coerceAtLeast(pulseScale)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Glowing animated mic button
        Box(
            modifier = Modifier
                .size(140.dp)
                .clickable(onClick = onTapMic),
            contentAlignment = Alignment.Center
        ) {
            // Outer glow ring
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(activeScale)
                    .clip(CircleShape)
                    .background(MimikyuGreen.copy(alpha = 0.12f + rmsLevel * 0.25f))
            )

            // Middle ring
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(1.0f + rmsLevel * 0.2f)
                    .clip(CircleShape)
                    .background(MimikyuGreen.copy(alpha = 0.25f + rmsLevel * 0.35f))
            )

            // Center green button
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF26E06D), MimikyuGreen)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.Mic),
                    contentDescription = "Listening",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Soundwave frequency visualizer bars
        SoundwaveEqualizerBars(rmsLevel = rmsLevel)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Listening for music or voice...",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Sing, hum, or hold near the playing track",
            color = MimikyuSubtext,
            fontSize = 13.sp
        )

        AnimatedVisibility(
            visible = partialTranscript.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MimikyuDarkCard)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "“$partialTranscript”",
                    color = MimikyuGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SoundwaveEqualizerBars(rmsLevel: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val barCount = 11
    Row(
        modifier = Modifier
            .height(42.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val wave = ((sin(phase + i * 0.6) + 1.0) / 2.0).toFloat()
            val targetHeightFraction = (0.20f + wave * 0.40f + rmsLevel * 0.60f).coerceIn(0.15f, 1.0f)
            val animatedHeight by animateFloatAsState(
                targetValue = targetHeightFraction * 40f,
                animationSpec = tween(120),
                label = "bar_height_$i"
            )

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .clip(CircleShape)
                    .background(
                        if (rmsLevel > 0.08f) MimikyuGreen else MimikyuGreen.copy(alpha = 0.55f)
                    )
            )
        }
    }
}

@Composable
private fun IdentifyingVisualizer(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = MimikyuGreen,
            modifier = Modifier.size(54.dp),
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Identifying Song...",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (query.isNotBlank()) {
            Text(
                text = "Query: “$query”",
                color = MimikyuGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Searching Apple Music & Deezer catalogue",
            color = MimikyuSubtext,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun FoundSongCard(
    song: IdentifiedSong,
    isPreviewPlaying: Boolean,
    onTogglePreview: () -> Unit,
    onPlayNow: () -> Unit,
    onSearchInMimikyu: () -> Unit,
    onIdentifyAgain: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Source Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MimikyuGreen.copy(alpha = 0.15f))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = "Matched via ${song.source}",
                color = MimikyuGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Album Artwork
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MimikyuDarkCard),
            contentAlignment = Alignment.Center
        ) {
            if (song.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Icon(
                    painter = painterResource(MimikyuIcons.Track),
                    contentDescription = null,
                    tint = MimikyuSubtext,
                    modifier = Modifier.size(64.dp)
                )
            }

            // Preview Play Badge if preview available
            if (song.previewUrl != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.75f))
                        .clickable(onClick = onTogglePreview),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(if (isPreviewPlaying) MimikyuIcons.Pause else MimikyuIcons.Play),
                        contentDescription = "Preview",
                        tint = MimikyuGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title & Artist
        Text(
            text = song.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = song.artist + if (song.album.isNotBlank()) " • ${song.album}" else "",
            color = MimikyuSubtext,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        if (song.previewUrl != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isPreviewPlaying) "Playing 30s Audio Preview..." else "Tap badge to play 30s preview",
                color = if (isPreviewPlaying) MimikyuGreen else MimikyuSubtext,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Play on Mimikyu Button (Primary Mimikyu Green)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MimikyuGreen)
                .clickable(onClick = onPlayNow),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(MimikyuIcons.Play),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Play on Mimikyu",
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search in Mimikyu Button (Secondary Dark)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MimikyuDarkCard)
                .clickable(onClick = onSearchInMimikyu),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Search in Mimikyu",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Identify Another Song",
            color = MimikyuSubtext,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onIdentifyAgain)
                .padding(8.dp)
        )
    }
}

@Composable
private fun NotFoundView(
    transcript: String,
    onRetry: () -> Unit,
    onSearchQuery: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MimikyuDarkCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.Mic),
                contentDescription = null,
                tint = MimikyuSubtext,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Couldn't Identify the Song",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (transcript.isNotBlank()) {
                "Heard: “$transcript”"
            } else {
                "Make sure you're singing or holding the device near the speaker"
            },
            color = MimikyuSubtext,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(MimikyuGreen)
                .clickable(onClick = onRetry),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Try Again",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (transcript.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MimikyuDarkCard)
                    .clickable { onSearchQuery(transcript) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Search “$transcript” on Mimikyu",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Recognition Interrupted",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            color = MimikyuSubtext,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(MimikyuGreen)
                .clickable(onClick = onRetry),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Retry",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionDeniedView(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(MimikyuIcons.Mic),
            contentDescription = null,
            tint = MimikyuGreen,
            modifier = Modifier.size(54.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Microphone Permission Required",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Mimikyu needs microphone access to listen to songs, hums, and lyrics to identify music.",
            color = MimikyuSubtext,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(MimikyuGreen)
                .clickable(onClick = onRequestPermission),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Grant Microphone Permission",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ManualSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222224))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(MimikyuIcons.Search),
            contentDescription = null,
            tint = MimikyuSubtext,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(MimikyuGreen),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Or type song title / lyrics to identify...",
                            color = MimikyuSubtext,
                            fontSize = 13.sp
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
        )

        if (value.isNotBlank()) {
            Text(
                text = "Identify",
                color = MimikyuGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onSearch)
                    .padding(4.dp)
            )
        }
    }
}
