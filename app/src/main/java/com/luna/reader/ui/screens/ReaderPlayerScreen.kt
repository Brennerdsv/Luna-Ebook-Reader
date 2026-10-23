package com.luna.reader.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.reader.document.TextChunk
import com.luna.reader.playback.PlaybackUiState
import com.luna.reader.tts.AvailableVoice
import com.luna.reader.tts.WordBoundary
import com.luna.reader.ui.components.ChapterListBottomSheet
import com.luna.reader.ui.components.VoiceSelectionBottomSheet
import com.luna.reader.ui.theme.*

enum class ReaderViewMode {
    READING_FLOW, // Modo leitura editorial contínua com realce de voz (Estilo Kindle)
    COVER_NOW_PLAYING // Modo capa cheia "Now Playing"
}

@Composable
fun ReaderPlayerScreen(
    playbackState: PlaybackUiState,
    onBackClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekRelative: (Int) -> Unit,
    onSelectChunk: (Int) -> Unit,
    onSelectChapter: (Int) -> Unit,
    onPersistProgress: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVoiceChange: (AvailableVoice) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(ReaderViewMode.READING_FLOW) }
    var fontScale by remember { mutableFloatStateOf(1.0f) }
    var showChapterBottomSheet by remember { mutableStateOf(false) }
    var showVoiceBottomSheet by remember { mutableStateOf(false) }
    var showFontSettings by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Salva progresso ao sair da tela
    DisposableEffect(Unit) {
        onDispose {
            onPersistProgress()
        }
    }

    // Auto-scroll inteligente: só rola se o item estiver fora da tela ou cortado nas bordas
    LaunchedEffect(playbackState.currentChunkIndex) {
        val targetIndex = playbackState.currentChunkIndex
        if (playbackState.chunks.isNotEmpty() && targetIndex in playbackState.chunks.indices) {
            val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
            if (visibleItem == null) {
                // Item fora da visão: rola diretamente para ele deixando margem no topo
                listState.animateScrollToItem(
                    index = targetIndex,
                    scrollOffset = -60
                )
            } else {
                // Se já estiver na tela, mas cortado no topo ou muito próximo da barra inferior
                val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                val isCutAtTop = visibleItem.offset < 20
                val isCutAtBottom = (visibleItem.offset + visibleItem.size) > (viewportHeight - 120)
                if (isCutAtTop || isCutAtBottom) {
                    listState.animateScrollToItem(
                        index = targetIndex,
                        scrollOffset = -60
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ReaderTopBar(
                title = playbackState.bookTitle,
                author = playbackState.bookAuthor,
                currentVoiceName = playbackState.currentVoice.displayName,
                viewMode = viewMode,
                onBackClick = {
                    onPersistProgress()
                    onBackClick()
                },
                onOpenChapters = { showChapterBottomSheet = true },
                onOpenVoiceSelection = { showVoiceBottomSheet = true },
                onToggleViewMode = {
                    viewMode = if (viewMode == ReaderViewMode.READING_FLOW) {
                        ReaderViewMode.COVER_NOW_PLAYING
                    } else {
                        ReaderViewMode.READING_FLOW
                    }
                },
                onToggleFontSettings = { showFontSettings = !showFontSettings }
            )
        },
        bottomBar = {
            ReaderBottomControls(
                playbackState = playbackState,
                onTogglePlayPause = onTogglePlayPause,
                onSeekRelative = onSeekRelative,
                onSpeedChange = onSpeedChange,
                onOpenVoiceSelection = { showVoiceBottomSheet = true }
            )
        },
        containerColor = LunaSurface,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = viewMode,
                label = "ViewModeTransition"
            ) { mode ->
                when (mode) {
                    ReaderViewMode.READING_FLOW -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Painel de Configurações de Tamanho de Fonte
                            AnimatedVisibility(visible = showFontSettings) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = LunaSurfaceContainerLowest),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Tamanho do Texto",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = LunaPrimary
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            IconButton(
                                                onClick = { fontScale = (fontScale - 0.1f).coerceAtLeast(0.8f) },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(LunaSurfaceContainer)
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Diminuir fonte", tint = LunaPrimary)
                                            }
                                            Text(
                                                "${(fontScale * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = LunaPrimary
                                            )
                                            IconButton(
                                                onClick = { fontScale = (fontScale + 0.1f).coerceAtMost(1.5f) },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(LunaSurfaceContainer)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Aumentar fonte", tint = LunaPrimary)
                                            }
                                        }
                                    }
                                }
                            }

                            // Fluxo de Leitura Editorial Contínua (Diagramação Estilo Kindle)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
                            ) {
                                itemsIndexed(playbackState.chunks) { index, chunk ->
                                    val isCurrentChunk = index == playbackState.currentChunkIndex
                                    val isFirstChunkOfChapter = index == 0 ||
                                            chunk.chapterIndex != playbackState.chunks[index - 1].chapterIndex

                                    if (isFirstChunkOfChapter && chunk.chapterTitle.isNotBlank()) {
                                        ChapterHeader(
                                            chapterIndex = chunk.chapterIndex,
                                            chapterTitle = chunk.chapterTitle
                                        )
                                    }

                                    EditorialParagraph(
                                        chunk = chunk,
                                        isCurrentChunk = isCurrentChunk,
                                        activeWord = if (isCurrentChunk) playbackState.activeWord else null,
                                        fontScale = fontScale,
                                        onClick = { onSelectChunk(index) }
                                    )
                                }
                            }
                        }
                    }

                    ReaderViewMode.COVER_NOW_PLAYING -> {
                        CoverNowPlayingView(
                            playbackState = playbackState,
                            onOpenVoiceSelection = { showVoiceBottomSheet = true }
                        )
                    }
                }
            }

            // BottomSheet do Sumário / Capítulos
            if (showChapterBottomSheet) {
                val overallProgress = if (playbackState.chunks.isNotEmpty()) {
                    playbackState.currentChunkIndex.toFloat() / playbackState.chunks.size.toFloat()
                } else 0f

                ChapterListBottomSheet(
                    bookTitle = playbackState.bookTitle,
                    chapters = playbackState.chapters,
                    currentChapterIndex = playbackState.currentChapterIndex,
                    overallProgressPercent = overallProgress,
                    onSelectChapter = { chapterIndex ->
                        onSelectChapter(chapterIndex)
                    },
                    onDismissRequest = { showChapterBottomSheet = false }
                )
            }

            if (showVoiceBottomSheet) {
                VoiceSelectionBottomSheet(
                    currentVoice = playbackState.currentVoice,
                    onSelectVoice = { voice ->
                        onVoiceChange(voice)
                    },
                    onDismissRequest = { showVoiceBottomSheet = false }
                )
            }


        }
    }
}

/**
 * Título de capítulo com diagramação clássica e espaçamento superior generoso.
 */
@Composable
fun ChapterHeader(
    chapterIndex: Int,
    chapterTitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 36.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "CAPÍTULO ${chapterIndex + 1}".uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp,
            color = LunaTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = LunaPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(2.dp)
                .background(LunaOutlineVariant.copy(alpha = 0.4f))
        )
    }
}

/**
 * Parágrafo editorial contínuo estilo Kindle com recuo de primeira linha e highlight suave.
 */
@Composable
fun EditorialParagraph(
    chunk: TextChunk,
    isCurrentChunk: Boolean,
    activeWord: WordBoundary?,
    fontScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chunkText = chunk.text

    val annotatedString = remember(chunkText, activeWord, isCurrentChunk) {
        buildAnnotatedString {
            if (!isCurrentChunk || activeWord == null || activeWord.text.isBlank()) {
                append(chunkText)
            } else {
                val start = activeWord.textOffset.coerceIn(0, chunkText.length)
                val wordLen = if (activeWord.wordLength > 0) activeWord.wordLength else activeWord.text.length
                val end = (start + wordLen).coerceIn(start, chunkText.length)

                if (start < end) {
                    append(chunkText.substring(0, start))
                    withStyle(
                        SpanStyle(
                            background = Color(0xFFFDE047).copy(alpha = 0.75f),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                    ) {
                        append(chunkText.substring(start, end))
                    }
                    append(chunkText.substring(end))
                } else {
                    val wordIdx = chunkText.indexOf(activeWord.text, ignoreCase = true)
                    if (wordIdx != -1) {
                        append(chunkText.substring(0, wordIdx))
                        withStyle(
                            SpanStyle(
                                background = Color(0xFFFDE047).copy(alpha = 0.75f),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                        ) {
                            append(chunkText.substring(wordIdx, wordIdx + activeWord.text.length))
                        }
                        append(chunkText.substring(wordIdx + activeWord.text.length))
                    } else {
                        append(chunkText)
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrentChunk) Color(0xFFF9F5EB) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = annotatedString,
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = (19 * fontScale).sp,
                lineHeight = (32 * fontScale).sp,
                letterSpacing = 0.2.sp,
                textIndent = TextIndent(firstLine = 22.sp),
                color = if (isCurrentChunk) LunaPrimary else LunaOnSurfaceVariant.copy(alpha = 0.9f)
            )
        )
    }
}

@Composable
fun CoverNowPlayingView(
    playbackState: PlaybackUiState,
    onOpenVoiceSelection: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(32.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(0.75f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E1B4B), Color(0xFF4B41E1))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = playbackState.bookTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = playbackState.bookAuthor,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LunaOnPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenVoiceSelection)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Text(
                                text = "Narrador: ${playbackState.currentVoice.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderTopBar(
    title: String,
    author: String,
    currentVoiceName: String,
    viewMode: ReaderViewMode,
    onBackClick: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenVoiceSelection: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleFontSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(68.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = LunaPrimary)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = LunaPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.clickable(onClick = onOpenVoiceSelection)
            ) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = " • ",
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaOnSurfaceVariant
                )
                Text(
                    text = currentVoiceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaTertiary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row {
            IconButton(onClick = onOpenChapters) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = "Sumário de Capítulos",
                    tint = LunaPrimary
                )
            }
            IconButton(onClick = onToggleFontSettings) {
                Icon(Icons.Default.FormatSize, contentDescription = "Tipografia", tint = LunaPrimary)
            }
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    imageVector = if (viewMode == ReaderViewMode.READING_FLOW) Icons.Default.Album else Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = "Alternar modo de exibição",
                    tint = LunaPrimary
                )
            }
        }
    }
}

@Composable
fun ReaderBottomControls(
    playbackState: PlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onSeekRelative: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOpenVoiceSelection: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        colors = CardDefaults.cardColors(containerColor = LunaSurfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Linha de progresso da frase atual
            val progressFraction = if (playbackState.durationMs > 0) {
                (playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = LunaPrimary,
                trackColor = LunaSurfaceContainerHigh,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Controles de Playback
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Seletor de Velocidade
                TextButton(
                    onClick = {
                        val nextSpeed = when (playbackState.speed) {
                            0.75f -> 1.0f
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            else -> 0.75f
                        }
                        onSpeedChange(nextSpeed)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = LunaPrimary)
                ) {
                    Text(
                        text = "${playbackState.speed}x",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // Retroceder 10s
                IconButton(
                    onClick = { onSeekRelative(-10) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LunaSurfaceContainerLow)
                ) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "Voltar 10s",
                        tint = LunaPrimary
                    )
                }

                // Botão Principal Play / Pause com animação de Loading
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = LunaPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.size(64.dp)
                ) {
                    if (playbackState.isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproduzir",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Avançar 10s
                IconButton(
                    onClick = { onSeekRelative(10) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LunaSurfaceContainerLow)
                ) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = "Avançar 10s",
                        tint = LunaPrimary
                    )
                }

                // Botão de Seleção de Narrador Offline
                IconButton(
                    onClick = onOpenVoiceSelection,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LunaPrimaryContainer)
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = "Selecionar Narrador: ${playbackState.currentVoice.displayName}",
                        tint = LunaOnPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

