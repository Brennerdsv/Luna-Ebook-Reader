package com.luna.reader.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gerenciador de Síntese de Voz Neural 100% Offline via Sherpa-ONNX (Piper TTS / VITS).
 * Suporta múltiplos narradores em Português Brasileiro (Lucas Razo, Faber, etc.) com troca a quente
 * e liberação imediata de memória RAM do modelo anterior.
 *
 * Todas as operações de I/O, carregamento de pesos e geração de áudio executam
 * ESTRITAMENTE em Dispatchers.IO com tratamento exaustivo de erros e logs detalhados.
 */
class LocalTtsManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "LunaTTS"

        @Volatile
        private var instance: LocalTtsManager? = null

        fun getInstance(context: Context): LocalTtsManager {
            return instance ?: synchronized(this) {
                instance ?: LocalTtsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val mutex = Mutex()
    private var ttsEngine: OfflineTts? = null

    @Volatile
    private var currentVoice: AvailableVoice = AvailableVoice.DEFAULT

    @Volatile
    private var isCancelled: Boolean = false

    init {
        loadNativeLibrariesSafely()
    }

    /**
     * Retorna o narrador atualmente carregado em memória.
     */
    fun getCurrentVoice(): AvailableVoice = currentVoice

    /**
     * Pré-carrega as bibliotecas nativas C++ para evitar UnsatisfiedLinkError.
     */
    private fun loadNativeLibrariesSafely() {
        try {
            System.loadLibrary("onnxruntime")
            Log.d(TAG, "LocalTtsManager: libonnxruntime.so carregada com sucesso.")
        } catch (t: Throwable) {
            Log.w(TAG, "LocalTtsManager: aviso no carregamento de libonnxruntime.so: ${t.message}")
        }

        try {
            System.loadLibrary("sherpa-onnx-jni")
            Log.d(TAG, "LocalTtsManager: libsherpa-onnx-jni.so carregada com sucesso.")
        } catch (t: Throwable) {
            Log.w(TAG, "LocalTtsManager: aviso no carregamento de libsherpa-onnx-jni.so: ${t.message}")
        }
    }

    /**
     * Alterna a voz neural ativa a quente:
     * 1. Libera a memória RAM do modelo anterior (ttsEngine?.release()).
     * 2. Prepara os arquivos da nova voz (assets ou download sob demanda).
     * 3. Instancia o novo OfflineTts apontando para o modelo e tokens correspondentes.
     */
    suspend fun switchVoice(
        voice: AvailableVoice,
        onProgress: ((message: String, progress: Float) -> Unit)? = null
    ): OfflineTts = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ttsEngine != null && currentVoice == voice) {
                return@withContext ttsEngine!!
            }

            Log.i(TAG, "LocalTtsManager: trocando narrador de ${currentVoice.displayName} para ${voice.displayName}...")

            // Libera o modelo anterior para desocupar RAM
            try {
                ttsEngine?.release()
                Log.d(TAG, "LocalTtsManager: instância anterior de OfflineTts liberada da memória")
            } catch (t: Throwable) {
                Log.w(TAG, "Aviso ao liberar OfflineTts anterior: ${t.message}")
            }
            ttsEngine = null

            val engine = initVoiceEngineInternal(voice, onProgress)
            currentVoice = voice
            ttsEngine = engine
            engine
        }
    }

    /**
     * Inicializa ou reaproveita o sintetizador para a voz desejada.
     */
    private suspend fun getOrInitTts(voice: AvailableVoice = currentVoice): OfflineTts = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ttsEngine != null && currentVoice == voice) {
                return@withContext ttsEngine!!
            }

            try {
                ttsEngine?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Aviso ao liberar OfflineTts: ${t.message}")
            }
            ttsEngine = null

            val engine = initVoiceEngineInternal(voice, null)
            currentVoice = voice
            ttsEngine = engine
            engine
        }
    }

    private suspend fun initVoiceEngineInternal(
        voice: AvailableVoice,
        onProgress: ((message: String, progress: Float) -> Unit)?
    ): OfflineTts {
        Log.i(TAG, "LocalTtsManager: preparando arquivos para o narrador ${voice.displayName}...")

        val modelPaths = try {
            PiperVoiceManager.prepareVoice(appContext, voice, onProgress)
        } catch (t: Throwable) {
            Log.e(TAG, "LocalTtsManager: FALHA CRÍTICA ao preparar arquivos para ${voice.displayName}: ${t.message}", t)
            throw IllegalStateException("Falha ao preparar arquivos da voz ${voice.displayName}: ${t.message}", t)
        }

        val modelFile = File(modelPaths.modelPath)
        val tokensFile = File(modelPaths.tokensPath)
        val espeakDir = File(modelPaths.dataDirPath)

        Log.i(TAG, "LocalTtsManager: Validação no disco para ${voice.displayName}:")
        Log.i(TAG, "  -> model: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
        Log.i(TAG, "  -> tokens: ${tokensFile.absolutePath} (${tokensFile.length()} bytes)")
        Log.i(TAG, "  -> espeak: ${espeakDir.absolutePath}")

        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            throw IllegalStateException("Arquivo do modelo inválido ou incompleto: ${modelFile.absolutePath}")
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            throw IllegalStateException("Arquivo de tokens ausente: ${tokensFile.absolutePath}")
        }
        if (!espeakDir.exists() || !File(espeakDir, "phondata").exists()) {
            throw IllegalStateException("Diretório espeak-ng-data corrompido em: ${espeakDir.absolutePath}")
        }

        val engine = try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = espeakDir.absolutePath,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )

            OfflineTts(
                assetManager = null,
                config = config
            )
        } catch (t: Throwable) {
            Log.e(TAG, "LocalTtsManager: ERRO FATAL ao instanciar OfflineTts nativo C++/JNI: ${t.message}", t)
            throw RuntimeException("Falha ao inicializar motor neural C++ para ${voice.displayName}: ${t.message}", t)
        }

        val sampleRate = try { engine.sampleRate() } catch (_: Throwable) { voice.sampleRate }
        Log.i(TAG, "LocalTtsManager: Narrador '${voice.displayName}' pronto e operacional! (sampleRate=${sampleRate}Hz)")
        return engine
    }

    /**
     * Sintetiza sob demanda um bloco de texto em áudio PCM na memória
     * e grava como arquivo .wav no cache para reprodução imediata.
     */
    suspend fun synthesizeChunk(
        text: String,
        speed: Float = 1.0f,
        chunkIndex: Int = 0,
        voice: AvailableVoice = currentVoice
    ): TtsSynthesisResult = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            throw IllegalArgumentException("Texto para síntese está vazio")
        }

        isCancelled = false
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "LocalTtsManager: iniciando synthesizeChunk($chunkIndex) com ${voice.displayName} ('${cleanText.take(35)}...', ${speed}x)...")

        val tts = try {
            getOrInitTts(voice)
        } catch (t: Throwable) {
            Log.e(TAG, "LocalTtsManager: abortando síntese do chunk $chunkIndex devido a falha na inicialização de ${voice.displayName}", t)
            throw t
        }

        val generatedAudio = try {
            tts.generate(
                text = cleanText,
                sid = 0,
                speed = speed.coerceIn(0.5f, 2.0f)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "LocalTtsManager: ERRO na chamada nativa tts.generate para chunk $chunkIndex: ${t.message}", t)
            throw RuntimeException("Falha na geração de áudio neural: ${t.message}", t)
        }

        val samples = generatedAudio.samples
        val sampleRate = if (generatedAudio.sampleRate > 0) generatedAudio.sampleRate else voice.sampleRate

        if (samples.isEmpty()) {
            val err = "Síntese neural retornou 0 amostras de áudio para o texto fornecido"
            Log.e(TAG, "LocalTtsManager: $err")
            throw IllegalStateException(err)
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        val durationMs = (samples.size.toDouble() / sampleRate * 1000.0).toLong().coerceAtLeast(400L)
        Log.i(TAG, "LocalTtsManager: síntese do chunk $chunkIndex com ${voice.displayName} concluída em ${elapsedMs}ms! Amostras: ${samples.size}, Duração: ${durationMs}ms ($sampleRate Hz)")

        val wavFile = File(appContext.cacheDir, "current_chunk_${chunkIndex}.wav")
        val saved = try {
            generatedAudio.save(wavFile.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "LocalTtsManager: generatedAudio.save falhou (${t.message}), gravando via writer manual...")
            false
        }

        if (!saved || !wavFile.exists() || wavFile.length() == 0L) {
            try {
                writePcmFloatToWav(samples, sampleRate, wavFile)
            } catch (t: Throwable) {
                Log.e(TAG, "LocalTtsManager: erro ao gravar arquivo WAV: ${t.message}", t)
                throw t
            }
        }

        val wordBoundaries = generateInterpolatedWordBoundaries(cleanText, durationMs)

        TtsSynthesisResult(
            audioFilePath = wavFile.absolutePath,
            wordBoundaries = wordBoundaries,
            durationEstimateMs = durationMs,
            text = cleanText
        )
    }

    private fun generateInterpolatedWordBoundaries(text: String, totalDurationMs: Long): List<WordBoundary> {
        val wordTokens = mutableListOf<Triple<String, Int, Int>>()
        val regex = Regex("\\S+")
        for (match in regex.findAll(text)) {
            wordTokens.add(Triple(match.value, match.range.first, match.value.length))
        }

        if (wordTokens.isEmpty()) return emptyList()

        val wordCount = wordTokens.size
        val durationPerWord = if (totalDurationMs > 0) totalDurationMs / wordCount else 300L

        return wordTokens.mapIndexed { index, (word, textOffset, wordLength) ->
            val offsetMs = index * durationPerWord
            val wordDuration = if (index == wordCount - 1) {
                (totalDurationMs - offsetMs).coerceAtLeast(durationPerWord)
            } else {
                durationPerWord
            }
            WordBoundary(
                text = word,
                offsetMs = offsetMs,
                durationMs = wordDuration,
                textOffset = textOffset,
                wordLength = wordLength
            )
        }
    }

    private fun writePcmFloatToWav(samples: FloatArray, sampleRate: Int, outputFile: File) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val dataSize = samples.size * 2
        val totalFileSize = 36 + dataSize

        val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put("RIFF".toByteArray())
                putInt(totalFileSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1.toShort())
                putShort(numChannels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((numChannels * (bitsPerSample / 8)).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray())
                putInt(dataSize)
            }
            fos.write(header.array())

            val pcmBuffer = ByteBuffer.allocate(samples.size * 2).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) {
                    val clamped = sample.coerceIn(-1.0f, 1.0f)
                    val pcm16 = (clamped * 32767.0f).toInt().toShort()
                    putShort(pcm16)
                }
            }
            fos.write(pcmBuffer.array())
        }
        if (outputFile.exists()) outputFile.delete()
        tmp.renameTo(outputFile)
    }

    fun cancelCurrentRequest() {
        isCancelled = true
    }

    fun release() {
        try {
            ttsEngine?.release()
            ttsEngine = null
            Log.d(TAG, "LocalTtsManager liberado com sucesso")
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao liberar LocalTtsManager: ${t.message}", t)
        }
    }
}
