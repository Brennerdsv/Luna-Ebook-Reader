package com.luna.reader.tts.edge

import android.content.Context
import android.util.Log
import com.luna.reader.tts.TtsSynthesisResult
import com.luna.reader.tts.WordBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cliente nativo de síntese de voz via WebSockets da Microsoft (Edge-TTS).
 * Gera áudio MP3 sob demanda com streaming em buffer e captura de marcações temporais.
 */
class EdgeTtsClient(private val appContext: Context) {

    companion object {
        private const val TAG = "EdgeTTS"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_BASE_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Sintetiza um parágrafo sob demanda usando a voz da Microsoft especificada.
     */
    suspend fun synthesizeChunk(
        text: String,
        voiceIdentifier: String,
        speed: Float = 1.0f,
        chunkIndex: Int = 0
    ): TtsSynthesisResult = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            throw IllegalArgumentException("Texto para síntese está vazio")
        }

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "EdgeTtsClient: iniciando síntese do chunk $chunkIndex com voz '$voiceIdentifier'...")

        withTimeout(25_000L) {
            suspendCancellableCoroutine { continuation ->
                val requestId = UUID.randomUUID().toString().replace("-", "")
                val connectionId = UUID.randomUUID().toString().replace("-", "")
                val secMsGec = SecMsGec.generateSecMsGec()
                val muid = SecMsGec.generateMuid()

                val url = "$WSS_BASE_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                        "&Sec-MS-GEC=$secMsGec" +
                        "&Sec-MS-GEC-Version=${SecMsGec.SEC_MS_GEC_VERSION}" +
                        "&ConnectionId=$connectionId"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                    .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .addHeader("Cookie", "muid=$muid;")
                    .build()

                val audioStream = ByteArrayOutputStream()
                val wordBoundaries = mutableListOf<WordBoundary>()
                var isCompleted = false

                val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "Edge WebSocket conectado! Enviando speech.config e SSML...")

                        // 1. Envia configuração de áudio
                        val configPayload = "Content-Type:application/json; charset=utf-8\r\n" +
                                "Path:speech.config\r\n\r\n" +
                                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                        webSocket.send(configPayload)

                        // 2. Monta SSML com taxa de velocidade
                        val speedPercentage = ((speed - 1.0f) * 100).toInt()
                        val rateStr = if (speedPercentage >= 0) "+$speedPercentage%" else "$speedPercentage%"
                        val escapedText = escapeXml(cleanText)
                        val timestamp = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }.format(Date())

                        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='pt-BR'>" +
                                "<voice name='$voiceIdentifier'>" +
                                "<prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>" +
                                escapedText +
                                "</prosody></voice></speak>"

                        val ssmlMessage = "X-RequestId:$requestId\r\n" +
                                "Content-Type:application/ssml+xml\r\n" +
                                "X-Timestamp:${timestamp}Z\r\n" +
                                "Path:ssml\r\n\r\n" +
                                ssml

                        webSocket.send(ssmlMessage)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end")) {
                            Log.d(TAG, "Edge WebSocket: turn.end recebido!")
                            finishSynthesis(webSocket)
                        } else if (text.contains("Path:audio.metadata")) {
                            parseMetadata(text, wordBoundaries)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // Formato binário: primeiros 2 bytes = tamanho do header de texto
                        val data = bytes.toByteArray()
                        if (data.size > 2) {
                            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            val audioOffset = 2 + headerLen
                            if (audioOffset < data.size) {
                                audioStream.write(data, audioOffset, data.size - audioOffset)
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Edge WebSocket falhou: ${t.message} (HTTP ${response?.code})", t)
                        if (!isCompleted) {
                            isCompleted = true
                            continuation.resumeWithException(
                                RuntimeException("Falha na conexão Edge-TTS (${response?.code ?: "Sem rede"}): ${t.message}", t)
                            )
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!isCompleted) {
                            finishSynthesis(webSocket)
                        }
                    }

                    private fun finishSynthesis(webSocket: WebSocket) {
                        if (isCompleted) return
                        isCompleted = true
                        webSocket.close(1000, "Done")

                        val audioBytes = audioStream.toByteArray()
                        if (audioBytes.isEmpty()) {
                            continuation.resumeWithException(IllegalStateException("Edge-TTS não retornou dados de áudio"))
                            return
                        }

                        val mp3File = File(appContext.cacheDir, "current_chunk_${chunkIndex}_edge.mp3")
                        FileOutputStream(mp3File).use { it.write(audioBytes) }

                        // Estimativa de duração para MP3 48kbps (6000 bytes por segundo)
                        val durationMs = ((audioBytes.size.toDouble() / 6000.0) * 1000.0).toLong().coerceAtLeast(500L)
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.i(TAG, "EdgeTtsClient: chunk $chunkIndex sintetizado com sucesso em ${elapsed}ms (${audioBytes.size} bytes, ~${durationMs}ms)")

                        val finalBoundaries = if (wordBoundaries.isNotEmpty()) {
                            wordBoundaries
                        } else {
                            generateFallbackWordBoundaries(cleanText, durationMs)
                        }

                        continuation.resume(
                            TtsSynthesisResult(
                                audioFilePath = mp3File.absolutePath,
                                wordBoundaries = finalBoundaries,
                                durationEstimateMs = durationMs,
                                text = cleanText
                            )
                        )
                    }
                })

                continuation.invokeOnCancellation {
                    try {
                        webSocket.cancel()
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun parseMetadata(text: String, boundaries: MutableList<WordBoundary>) {
        try {
            val jsonIndex = text.indexOf("{")
            if (jsonIndex == -1) return
            val jsonStr = text.substring(jsonIndex)
            val root = JSONObject(jsonStr)
            val metadataArray = root.optJSONArray("Metadata") ?: return

            for (i in 0 until metadataArray.length()) {
                val item = metadataArray.optJSONObject(i) ?: continue
                val data = item.optJSONObject("Data") ?: continue
                val textObj = data.optJSONObject("text") ?: continue

                val word = textObj.optString("Text", "")
                val offsetTicks = data.optLong("Offset", 0L)
                val durationTicks = data.optLong("Duration", 0L)
                val textOffset = textObj.optInt("Length", 0)

                // 1 tick = 100ns = 1e-4 ms
                val offsetMs = offsetTicks / 10_000L
                val durationMs = durationTicks / 10_000L

                if (word.isNotBlank()) {
                    boundaries.add(
                        WordBoundary(
                            text = word,
                            offsetMs = offsetMs,
                            durationMs = durationMs,
                            textOffset = textOffset,
                            wordLength = word.length
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao analisar metadados de áudio: ${e.message}")
        }
    }

    private fun generateFallbackWordBoundaries(text: String, totalDurationMs: Long): List<WordBoundary> {
        val wordTokens = mutableListOf<Triple<String, Int, Int>>()
        val regex = Regex("\\S+")
        for (match in regex.findAll(text)) {
            wordTokens.add(Triple(match.value, match.range.first, match.value.length))
        }

        if (wordTokens.isEmpty()) return emptyList()
        val durationPerWord = if (totalDurationMs > 0) totalDurationMs / wordTokens.size else 300L

        return wordTokens.mapIndexed { index, (word, textOffset, wordLength) ->
            WordBoundary(
                text = word,
                offsetMs = index * durationPerWord,
                durationMs = durationPerWord,
                textOffset = textOffset,
                wordLength = wordLength
            )
        }
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
