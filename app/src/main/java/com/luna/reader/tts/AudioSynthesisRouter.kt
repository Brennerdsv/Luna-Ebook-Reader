package com.luna.reader.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.luna.reader.tts.edge.EdgeTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exceção lançada quando uma voz online é requisitada sem acesso à internet.
 */
class NoInternetException(message: String) : RuntimeException(message)

/**
 * Exceção lançada quando uma voz offline requer download antes do uso.
 */
class VoiceNotDownloadedException(val voice: AvailableVoice, message: String) : RuntimeException(message)

/**
 * Contrato unificado de sintetizador de voz para motores locais (Offline) e nuvem (Online).
 */
interface VoiceSynthesizer {
    suspend fun synthesize(
        text: String,
        voice: AvailableVoice,
        speed: Float = 1.0f,
        chunkIndex: Int = 0
    ): TtsSynthesisResult

    fun cancelCurrentRequest()
    fun release()
    fun isNetworkAvailable(): Boolean
}

/**
 * Roteador inteligente de síntese de áudio.
 * - Direciona vozes offline (Faber) para o motor local Sherpa-ONNX.
 * - Direciona vozes online (Microsoft Edge) para o cliente WebSockets com streaming em buffer.
 * - Trata perda de conectividade e orienta o fallback para o narrador offline.
 */
class AudioSynthesisRouter(
    private val appContext: Context,
    private val localTtsManager: LocalTtsManager = LocalTtsManager.getInstance(appContext),
    private val edgeTtsClient: EdgeTtsClient = EdgeTtsClient(appContext)
) : VoiceSynthesizer {

    companion object {
        private const val TAG = "LunaAudioRouter"

        @Volatile
        private var instance: AudioSynthesisRouter? = null

        fun getInstance(context: Context): AudioSynthesisRouter {
            return instance ?: synchronized(this) {
                instance ?: AudioSynthesisRouter(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    override fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override suspend fun synthesize(
        text: String,
        voice: AvailableVoice,
        speed: Float,
        chunkIndex: Int
    ): TtsSynthesisResult = withContext(Dispatchers.IO) {
        when (val engine = voice.engine) {
            is VoiceEngine.OfflinePiper -> {
                Log.d(TAG, "Roteando síntese para motor OFFLINE (Sherpa-ONNX / Faber)...")

                if (!PiperVoiceManager.isVoiceReady(appContext, voice)) {
                    val err = "O narrador offline ${voice.displayName} precisa ser baixado (~60 MB) antes do uso."
                    Log.w(TAG, err)
                    throw VoiceNotDownloadedException(voice, err)
                }

                localTtsManager.synthesizeChunk(
                    text = text,
                    speed = speed,
                    chunkIndex = chunkIndex,
                    voice = voice
                )
            }

            is VoiceEngine.OnlineEdge -> {
                Log.d(TAG, "Roteando síntese para motor ONLINE (Microsoft Edge / ${engine.voiceIdentifier})...")

                if (!isNetworkAvailable()) {
                    val err = "Sem conexão com a internet para usar ${voice.displayName}. Conecte-se à rede ou alterne para a voz offline Faber."
                    Log.w(TAG, err)
                    throw NoInternetException(err)
                }

                edgeTtsClient.synthesizeChunk(
                    text = text,
                    voiceIdentifier = engine.voiceIdentifier,
                    speed = speed,
                    chunkIndex = chunkIndex
                )
            }
        }
    }

    override fun cancelCurrentRequest() {
        localTtsManager.cancelCurrentRequest()
    }

    override fun release() {
        localTtsManager.release()
    }
}
