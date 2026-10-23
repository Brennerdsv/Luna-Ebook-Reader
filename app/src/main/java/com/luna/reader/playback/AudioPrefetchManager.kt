package com.luna.reader.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.luna.reader.data.local.LunaDatabase
import com.luna.reader.data.local.UserPreferencesRepository
import com.luna.reader.document.Chapter
import com.luna.reader.document.DocumentChunker
import com.luna.reader.document.TextChunk
import com.luna.reader.service.LunaPlaybackService
import com.luna.reader.tts.AvailableVoice
import com.luna.reader.tts.LocalTtsManager
import com.luna.reader.tts.TtsSynthesisResult
import com.luna.reader.tts.WordBoundary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Estado reativo detalhado de reprodução sincronizada em tempo real.
 */
data class PlaybackUiState(
    val bookId: Long = -1,
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val chunks: List<TextChunk> = emptyList(),
    val currentChunkIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val activeWord: WordBoundary? = null,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val currentVoice: AvailableVoice = AvailableVoice.DEFAULT,
    val speed: Float = 1.0f
)

/**
 * Orquestrador de leitura contínua com suporte a execução contínua em segundo plano
 * e com tela bloqueada via enfileiramento na playlist nativa do ExoPlayer (AndroidX Media3).
 *
 * Utiliza o modelo neural offline local Lucas Razo (Sherpa-ONNX / Piper TTS) para geração
 * instantânea de áudio PCM diretamente no processador do dispositivo.
 */
class AudioPrefetchManager(
    appContext: Context,
    private val localTtsManager: LocalTtsManager = LocalTtsManager.getInstance(appContext.applicationContext),
    private val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(appContext.applicationContext)
) {

    private val context = appContext.applicationContext

    companion object {
        private const val TAG = "LunaTTS"

        @Volatile
        private var instance: AudioPrefetchManager? = null

        fun getInstance(context: Context): AudioPrefetchManager {
            return instance ?: synchronized(this) {
                instance ?: AudioPrefetchManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bookDao = LunaDatabase.getInstance(context).bookDao()

    private var progressTrackingJob: Job? = null
    private var prefetchJob: Job? = null
    private var ttsJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Cache em memória dos resultados de síntese já prontos: Chunks -> TtsSynthesisResult
    private val synthesisCache = ConcurrentHashMap<Int, TtsSynthesisResult>()

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    init {
        startPlaybackServiceExplicitly()
        initializeMediaController()
        observePreferences()
    }

    private fun startPlaybackServiceExplicitly() {
        try {
            val serviceIntent = Intent(context, LunaPlaybackService::class.java)
            context.startService(serviceIntent)
            Log.d(TAG, "LunaPlaybackService iniciado explicitamente")
        } catch (t: Throwable) {
            Log.w(TAG, "Aviso ao iniciar LunaPlaybackService explicitamente: ${t.message}")
        }
    }

    private fun observePreferences() {
        scope.launch {
            preferencesRepository.selectedVoiceFlow.collect { savedVoice ->
                _uiState.value = _uiState.value.copy(currentVoice = savedVoice)
                Log.d(TAG, "Preferência de voz carregada: ${savedVoice.displayName}")
            }
        }
        scope.launch {
            preferencesRepository.playbackSpeedFlow.collect { savedSpeed ->
                _uiState.value = _uiState.value.copy(speed = savedSpeed)
                Log.d(TAG, "Preferência de velocidade carregada: ${savedSpeed}x")
            }
        }
    }

    private fun initializeMediaController() {
        try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, LunaPlaybackService::class.java)
            )
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                try {
                    val controller = controllerFuture?.get()
                    mediaController = controller
                    if (controller != null) {
                        setupPlayerListener(controller)
                        Log.d(TAG, "MediaController conectado com sucesso ao LunaPlaybackService")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao conectar MediaController inicial", e)
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar SessionToken do MediaController", e)
        }
    }

    private suspend fun getOrCreateController(): MediaController = withContext(Dispatchers.Main) {
        mediaController?.let { return@withContext it }

        val future = controllerFuture ?: run {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, LunaPlaybackService::class.java)
            )
            MediaController.Builder(context, sessionToken).buildAsync().also {
                controllerFuture = it
            }
        }

        suspendCancellableCoroutine { continuation ->
            future.addListener({
                try {
                    val controller = future.get()
                    mediaController = controller
                    setupPlayerListener(controller)
                    continuation.resume(controller)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao aguardar conexão do MediaController", e)
                    continuation.resumeWithException(e)
                }
            }, MoreExecutors.directExecutor())
        }
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "ExoPlayer onIsPlayingChanged: $isPlaying")
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    startProgressTracking()
                } else {
                    stopProgressTracking()
                    persistReadingPosition()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                val newIndex = mediaItem.mediaId.toIntOrNull() ?: return
                Log.d(TAG, "ExoPlayer onMediaItemTransition: transição contínua para o chunk $newIndex (reason=$reason)")

                val currentState = _uiState.value
                val chunk = currentState.chunks.getOrNull(newIndex)
                val chapterIdx = chunk?.chapterIndex ?: currentState.currentChapterIndex
                val result = synthesisCache[newIndex]

                _uiState.value = currentState.copy(
                    currentChunkIndex = newIndex,
                    currentChapterIndex = chapterIdx,
                    currentPositionMs = 0L,
                    durationMs = result?.durationEstimateMs ?: currentState.durationMs,
                    isLoading = false,
                    activeWord = null
                )

                persistReadingPosition()

                // Enfileira imediatamente o próximo trecho na playlist do ExoPlayer
                enqueueNextChunks(newIndex + 1)

                // Limpa itens já reproduzidos da playlist para liberar memória
                val controller = mediaController
                if (controller != null && controller.currentMediaItemIndex > 0) {
                    controller.removeMediaItems(0, controller.currentMediaItemIndex)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        Log.d(TAG, "ExoPlayer STATE_ENDED detectado (fim da playlist)")
                        onCurrentChunkFinished()
                    }
                    Player.STATE_BUFFERING -> {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                    }
                    Player.STATE_READY -> {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                    Player.STATE_IDLE -> {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
            }
        })
    }

    /**
     * Carrega um livro ou documento estruturado no reprodutor.
     * Interrompe imediatamente qualquer execução de livro anterior, limpa caches
     * e restaura o capítulo e parágrafo salvos no banco de dados.
     */
    fun loadDocument(
        bookId: Long,
        title: String,
        author: String,
        fullText: String,
        chapters: List<Chapter> = emptyList(),
        startChapterIndex: Int = 0,
        startChunkIndex: Int = 0,
        voice: AvailableVoice = _uiState.value.currentVoice,
        speed: Float = _uiState.value.speed,
        autoPlay: Boolean = false
    ) {
        Log.d(TAG, "loadDocument: Carregando livro \"$title\" (id=$bookId). Cancelando processamento anterior imediatamente...")

        // 1. Cancelamento imediato de tarefas do livro anterior
        ttsJob?.cancel()
        prefetchJob?.cancel()
        progressTrackingJob?.cancel()
        localTtsManager.cancelCurrentRequest()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        synthesisCache.clear()

        // 2. Extração/resolução de capítulos
        val resolvedChapters = if (chapters.isNotEmpty()) {
            chapters
        } else {
            DocumentChunker.splitIntoChapters(fullText)
        }
        val allChunks = resolvedChapters.flatMap { it.chunks }

        val safeChunkIndex = if (allChunks.isNotEmpty()) {
            startChunkIndex.coerceIn(0, allChunks.size - 1)
        } else {
            0
        }

        val activeChapter = resolvedChapters.find {
            safeChunkIndex in it.startChunkGlobalIndex until (it.startChunkGlobalIndex + it.totalChunks)
        } ?: resolvedChapters.getOrNull(startChapterIndex) ?: resolvedChapters.firstOrNull()

        val safeChapterIndex = activeChapter?.index ?: 0

        Log.d(TAG, "loadDocument: ${resolvedChapters.size} capítulos, ${allChunks.size} chunks totais. Posição inicial: capítulo $safeChapterIndex, chunk $safeChunkIndex")

        _uiState.value = _uiState.value.copy(
            bookId = bookId,
            bookTitle = title,
            bookAuthor = author,
            chapters = resolvedChapters,
            currentChapterIndex = safeChapterIndex,
            chunks = allChunks,
            currentChunkIndex = safeChunkIndex,
            currentVoice = voice,
            speed = speed,
            isLoading = autoPlay,
            isPlaying = false,
            currentPositionMs = 0L,
            activeWord = null
        )

        persistReadingPosition()

        if (autoPlay && allChunks.isNotEmpty()) {
            playChunk(safeChunkIndex)
        }
    }

    /**
     * Toca um bloco específico com prioridade máxima.
     * Cancela requisições ativas, prepara o player e enfileira antecipadamente o próximo
     * bloco na playlist do ExoPlayer para reprodução contínua e ininterrupta em segundo plano.
     */
    fun playChunk(chunkIndex: Int) {
        val currentState = _uiState.value
        if (chunkIndex < 0 || chunkIndex >= currentState.chunks.size) {
            Log.w(TAG, "playChunk: Índice inválido: $chunkIndex (Total: ${currentState.chunks.size})")
            return
        }

        // Garante que o ForegroundService esteja ativo
        startPlaybackServiceExplicitly()

        // Cancelamento de requisições anteriores
        ttsJob?.cancel()
        prefetchJob?.cancel()
        localTtsManager.cancelCurrentRequest()

        val chunk = currentState.chunks[chunkIndex]
        val chapterIdx = chunk.chapterIndex

        _uiState.value = currentState.copy(
            currentChunkIndex = chunkIndex,
            currentChapterIndex = chapterIdx,
            isLoading = true,
            activeWord = null
        )
        persistReadingPosition()

        ttsJob = scope.launch {
            try {
                Log.d(TAG, "playChunk($chunkIndex): Síntese prioritária do bloco '${chunk.text.take(30)}...'")
                val currentResult = getOrSynthesizeChunk(chunkIndex)

                val player = getOrCreateController()
                val currentMediaItem = buildMediaItem(
                    chunkIndex = chunkIndex,
                    result = currentResult,
                    title = currentState.bookTitle,
                    author = currentState.bookAuthor
                )

                // Define o MediaItem inicial e começa a reprodução
                player.stop()
                player.clearMediaItems()
                player.setMediaItem(currentMediaItem)
                player.prepare()
                player.play()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    durationMs = currentResult.durationEstimateMs
                )

                // Imediatamente enfileira o próximo trecho na playlist nativa do ExoPlayer
                enqueueNextChunks(chunkIndex + 1)
            } catch (t: Throwable) {
                if (isActive) {
                    Log.e(TAG, "AudioPrefetchManager: FALHA CRÍTICA ao sintetizar/reproduzir chunk $chunkIndex: ${t.message}", t)
                    _uiState.value = _uiState.value.copy(isLoading = false, isPlaying = false)
                }
            }
        }
    }

    /**
     * Enfileira o próximo parágrafo na playlist do ExoPlayer.
     * Graças a essa técnica, o ExoPlayer transiciona automaticamente sem emitir
     * STATE_ENDED, permitindo leitura contínua com a tela desligada ou bloqueada.
     */
    private fun enqueueNextChunks(nextIndex: Int) {
        if (nextIndex >= _uiState.value.chunks.size) return

        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Iniciando prefetch antecipado e enfileiramento para o chunk $nextIndex...")
                val nextResult = getOrSynthesizeChunk(nextIndex)

                withContext(Dispatchers.Main) {
                    val player = mediaController ?: return@withContext
                    // Verifica se o item já está na playlist para evitar duplicação
                    val alreadyEnqueued = (0 until player.mediaItemCount).any { idx ->
                        player.getMediaItemAt(idx).mediaId == nextIndex.toString()
                    }

                    if (!alreadyEnqueued) {
                        val state = _uiState.value
                        val nextMediaItem = buildMediaItem(
                            chunkIndex = nextIndex,
                            result = nextResult,
                            title = state.bookTitle,
                            author = state.bookAuthor
                        )
                        player.addMediaItem(nextMediaItem)
                        Log.d(TAG, "Chunk $nextIndex enfileirado com sucesso na playlist do ExoPlayer! (Total na fila: ${player.mediaItemCount})")

                        // Se o player estava em STATE_ENDED (por exemplo, parágrafo anterior muito curto), inicia o playback
                        if (player.playbackState == Player.STATE_ENDED || (player.playbackState == Player.STATE_IDLE && player.playWhenReady)) {
                            player.prepare()
                            player.play()
                        }
                    }
                }

                // Buffer duplo: realiza prefetch silencioso em memória para nextIndex + 1
                val bufferNext = nextIndex + 1
                if (bufferNext < _uiState.value.chunks.size && !synthesisCache.containsKey(bufferNext)) {
                    try {
                        getOrSynthesizeChunk(bufferNext)
                        Log.d(TAG, "Buffer aquecido em cache para o chunk $bufferNext")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Aviso no pré-carregamento do buffer seguinte $bufferNext: ${t.message}", t)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Prefetch/Enfileiramento falhou para o chunk $nextIndex: ${t.message}", t)
            }
        }
    }

    private fun buildMediaItem(
        chunkIndex: Int,
        result: TtsSynthesisResult,
        title: String,
        author: String
    ): MediaItem {
        val mediaFile = File(result.audioFilePath)
        if (!mediaFile.exists() || mediaFile.length() == 0L) {
            throw IllegalStateException("Arquivo de áudio inexistente ou corrompido: ${result.audioFilePath}")
        }

        val chunk = _uiState.value.chunks.getOrNull(chunkIndex)
        val chapter = _uiState.value.chapters.getOrNull(chunk?.chapterIndex ?: _uiState.value.currentChapterIndex)
        val chapterTitle = chapter?.title ?: "Capítulo ${(chunk?.chapterIndex ?: 0) + 1}"

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(chapterTitle)
            .setArtist(author)
            .setDisplayTitle(title)
            .build()

        return MediaItem.Builder()
            .setMediaId(chunkIndex.toString())
            .setUri(Uri.fromFile(mediaFile))
            .setMediaMetadata(mediaMetadata)
            .build()
    }

    /**
     * Navegação direta por capítulos via Tabela de Conteúdos (TOC).
     */
    fun playChapter(chapterIndex: Int) {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(chapterIndex) ?: return
        Log.d(TAG, "playChapter($chapterIndex): '${chapter.title}' (iniciando no chunk ${chapter.startChunkGlobalIndex})")
        playChunk(chapter.startChunkGlobalIndex)
    }

    private suspend fun getOrSynthesizeChunk(index: Int): TtsSynthesisResult {
        synthesisCache[index]?.let {
            Log.d(TAG, "getOrSynthesizeChunk($index): Usando áudio do cache")
            return it
        }

        val chunk = _uiState.value.chunks.getOrNull(index)
            ?: throw IllegalArgumentException("Chunk não encontrado no índice $index")

        val state = _uiState.value
        val result = localTtsManager.synthesizeChunk(
            text = chunk.text,
            speed = state.speed,
            chunkIndex = index,
            voice = state.currentVoice
        )
        synthesisCache[index] = result
        return result
    }

    private fun onCurrentChunkFinished() {
        val nextChunkIndex = _uiState.value.currentChunkIndex + 1
        Log.d(TAG, "Fim da fila do player. Próximo bloco esperado: $nextChunkIndex/${_uiState.value.chunks.size}")
        if (nextChunkIndex < _uiState.value.chunks.size) {
            playChunk(nextChunkIndex)
        } else {
            Log.d(TAG, "Fim da leitura de todos os blocos do documento")
            _uiState.value = _uiState.value.copy(isPlaying = false, currentPositionMs = 0, activeWord = null)
            persistReadingPosition()
        }
    }

    /**
     * Alterna entre Play e Pause tratando corretamente os estados do player (IDLE, ENDED, READY).
     */
    fun togglePlayPause() {
        startPlaybackServiceExplicitly()
        scope.launch(Dispatchers.Main) {
            try {
                val player = getOrCreateController()
                Log.d(TAG, "togglePlayPause: isPlaying=${player.isPlaying}, playbackState=${player.playbackState}, mediaItems=${player.mediaItemCount}")

                if (player.isPlaying) {
                    player.pause()
                    persistReadingPosition()
                } else {
                    if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
                        playChunk(_uiState.value.currentChunkIndex)
                    } else {
                        player.play()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AudioPrefetchManager: Falha em togglePlayPause: ${t.message}", t)
                _uiState.value = _uiState.value.copy(isLoading = false, isPlaying = false)
            }
        }
    }

    fun seekRelative(seconds: Int) {
        scope.launch(Dispatchers.Main) {
            try {
                val player = getOrCreateController()
                val target = (player.currentPosition + seconds * 1000L).coerceAtLeast(0L)
                player.seekTo(target)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao buscar áudio relativo", e)
            }
        }
    }

    fun setSpeed(speed: Float) {
        Log.d(TAG, "Alterando velocidade de síntese para ${speed}x")
        scope.launch(Dispatchers.Main) {
            preferencesRepository.setPlaybackSpeed(speed)
            ttsJob?.cancel()
            prefetchJob?.cancel()
            localTtsManager.cancelCurrentRequest()
            mediaController?.stop()
            mediaController?.clearMediaItems()
            synthesisCache.clear()

            _uiState.value = _uiState.value.copy(speed = speed)

            if (_uiState.value.chunks.isNotEmpty()) {
                playChunk(_uiState.value.currentChunkIndex)
            }
        }
    }

    fun setVoice(voice: AvailableVoice) {
        Log.d(TAG, "Troca dinâmica de voz para: ${voice.id} (${voice.displayName})")
        scope.launch(Dispatchers.Main) {
            try {
                // 1. Pausa o player atual
                mediaController?.pause()
                mediaController?.clearMediaItems()

                // 2. Cancela jobs de síntese e prefetch ativos
                ttsJob?.cancel()
                prefetchJob?.cancel()
                localTtsManager.cancelCurrentRequest()

                // 3. Limpa o cache de blocos de áudio da voz antiga
                synthesisCache.clear()

                // 4. Atualiza estado para loading da nova voz
                _uiState.value = _uiState.value.copy(
                    currentVoice = voice,
                    isLoading = true,
                    activeWord = null
                )

                // 5. Salva no DataStore (selected_offline_voice)
                preferencesRepository.setSelectedVoice(voice)

                // 6. Alterna o motor em segundo plano (libera RAM da anterior e carrega a nova)
                withContext(Dispatchers.IO) {
                    localTtsManager.switchVoice(voice)
                }

                // 7. Retoma reprodução com a nova voz
                if (_uiState.value.chunks.isNotEmpty()) {
                    playChunk(_uiState.value.currentChunkIndex)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Erro ao trocar para voz ${voice.displayName}: ${t.message}", t)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Persiste imediatamente o progresso no Room Database.
     */
    fun persistReadingPosition() {
        val state = _uiState.value
        if (state.bookId <= 0 || state.chunks.isEmpty()) return

        val total = state.chunks.size.coerceAtLeast(1)
        val progress = (state.currentChunkIndex.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val chapterIdx = state.currentChapterIndex
        val chunkIdx = state.currentChunkIndex
        val posMs = state.currentPositionMs

        scope.launch(Dispatchers.IO) {
            try {
                bookDao.updateReadingPosition(
                    bookId = state.bookId,
                    chapterIndex = chapterIdx,
                    chunkIndex = chunkIdx,
                    positionMs = posMs,
                    progress = progress,
                    timestamp = System.currentTimeMillis()
                )
                Log.d(TAG, "Progresso salvo no banco: Livro ${state.bookId}, Capítulo $chapterIdx, Chunk $chunkIdx ($progress%)")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao persistir progresso de leitura", e)
            }
        }
    }

    fun stop() {
        scope.launch(Dispatchers.Main) {
            ttsJob?.cancel()
            prefetchJob?.cancel()
            stopProgressTracking()
            localTtsManager.cancelCurrentRequest()
            mediaController?.stop()
            mediaController?.clearMediaItems()
            synthesisCache.clear()
            persistReadingPosition()

            _uiState.value = _uiState.value.copy(
                isPlaying = false,
                isLoading = false,
                bookId = -1,
                bookTitle = "",
                bookAuthor = "",
                chapters = emptyList(),
                currentChapterIndex = 0,
                chunks = emptyList(),
                currentChunkIndex = 0,
                currentPositionMs = 0L,
                durationMs = 0L,
                activeWord = null
            )
            Log.d(TAG, "AudioPrefetchManager parado e estado limpo")
        }
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = scope.launch {
            while (isActive) {
                val player = mediaController
                if (player != null && player.isPlaying) {
                    val currentPos = player.currentPosition
                    val duration = player.duration.coerceAtLeast(0L)

                    val activeResult = synthesisCache[_uiState.value.currentChunkIndex]
                    val boundaries = activeResult?.wordBoundaries
                    val matchedWord = if (!boundaries.isNullOrEmpty()) {
                        val candidate = boundaries.findLast { it.offsetMs <= currentPos }
                        if (candidate != null && currentPos <= (candidate.offsetMs + candidate.durationMs + 350)) {
                            candidate
                        } else {
                            boundaries.firstOrNull { currentPos in it.offsetMs..(it.offsetMs + it.durationMs + 100) }
                        }
                    } else null

                    _uiState.value = _uiState.value.copy(
                        currentPositionMs = currentPos,
                        durationMs = if (duration > 0) duration else _uiState.value.durationMs,
                        activeWord = matchedWord
                    )
                }
                delay(50)
            }
        }
    }

    private fun stopProgressTracking() {
        progressTrackingJob?.cancel()
    }

    fun release() {
        persistReadingPosition()
        progressTrackingJob?.cancel()
        prefetchJob?.cancel()
        ttsJob?.cancel()
        localTtsManager.cancelCurrentRequest()
        localTtsManager.release()
        mediaController?.release()
        controllerFuture = null
    }
}
