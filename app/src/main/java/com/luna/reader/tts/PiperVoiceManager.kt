package com.luna.reader.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gerenciador de armazenamento, validação e download sob demanda do narrador offline
 * Faber (Piper TTS / VITS / Sherpa-ONNX).
 */
object PiperVoiceManager {

    private const val TAG = "LunaTTS"
    private const val ESPEAK_DIR_NAME = "espeak-ng-data"

    data class ModelPaths(
        val modelPath: String,
        val tokensPath: String,
        val dataDirPath: String
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retorna o diretório exclusivo de uma voz offline (ex: context.filesDir/voices/faber).
     */
    fun getVoiceDirectory(context: Context, voice: AvailableVoice = AvailableVoice.FABER): File {
        val folderName = when (voice) {
            AvailableVoice.FABER -> "faber"
            else -> voice.id
        }
        val dir = File(context.filesDir, "voices/$folderName")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getSharedEspeakDirectory(context: Context): File {
        val dir = File(context.filesDir, "voice/$ESPEAK_DIR_NAME")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Validação se o modelo offline está 100% baixado e pronto para execução no disco.
     */
    fun isVoiceReady(context: Context, voice: AvailableVoice): Boolean {
        if (!voice.isOfflineCapable) return true // Vozes online não precisam de download de modelo

        val offlineEngine = voice.engine as? VoiceEngine.OfflinePiper ?: return false
        val voiceDir = getVoiceDirectory(context, voice)
        val modelFile = File(voiceDir, offlineEngine.modelFileName)
        val tokensFile = File(voiceDir, voice.tokensFileName)
        val configFile = File(voiceDir, offlineEngine.configFileName)
        val espeakDir = getSharedEspeakDirectory(context)
        val phondata = File(espeakDir, "phondata")
        val phontab = File(espeakDir, "phontab")

        val hasModel = modelFile.exists() && modelFile.length() > 30_000_000L
        val hasTokens = (tokensFile.exists() && tokensFile.length() > 50L) ||
                (configFile.exists() && configFile.length() > 100L)
        val hasEspeak = espeakDir.exists() && phondata.exists() && phondata.length() > 1000L && phontab.exists()

        return hasModel && hasTokens && hasEspeak
    }

    /**
     * Prepara os caminhos dos arquivos necessários para inicializar o Sherpa-ONNX.
     */
    suspend fun prepareVoice(
        context: Context,
        voice: AvailableVoice,
        onProgress: ((message: String, progress: Float) -> Unit)? = null
    ): ModelPaths = withContext(Dispatchers.IO) {
        val offlineEngine = voice.engine as? VoiceEngine.OfflinePiper
            ?: throw IllegalArgumentException("A voz ${voice.displayName} não utiliza o motor offline Piper")

        val voiceDir = getVoiceDirectory(context, voice)
        val espeakDir = getSharedEspeakDirectory(context)
        val modelFile = File(voiceDir, offlineEngine.modelFileName)
        val configFile = File(voiceDir, offlineEngine.configFileName)
        val tokensFile = File(voiceDir, voice.tokensFileName)

        Log.d(TAG, "PiperVoiceManager: preparando voz offline '${voice.displayName}' em ${voiceDir.absolutePath}...")

        // 1. Garante que espeak-ng-data esteja presente (copia de assets)
        copyEspeakDataSafely(context.assets, espeakDir)

        // 2. Se o modelo estiver corrompido no disco (< 30 MB), deleta
        if (modelFile.exists() && modelFile.length() < 30_000_000L) {
            Log.w(TAG, "PiperVoiceManager: arquivo corrompido detectado (${modelFile.length()} bytes). Removendo...")
            modelFile.delete()
        }

        // 3. Tenta copiar de assets/ caso venha pré-instalado
        copyIndividualAsset(context.assets, "voice/${offlineEngine.modelFileName}", modelFile, minSize = 30_000_000L)
        copyIndividualAsset(context.assets, "voice/${offlineEngine.configFileName}", configFile, minSize = 100L)
        copyIndividualAsset(context.assets, "voice/${voice.tokensFileName}", tokensFile, minSize = 50L)

        // 4. Download sob demanda se o modelo não existir
        if (!modelFile.exists() || modelFile.length() < 30_000_000L) {
            onProgress?.invoke("Baixando modelo offline ${voice.displayName}...", 0.1f)
            Log.i(TAG, "PiperVoiceManager: baixando ${offlineEngine.modelFileName}...")
            downloadFile(offlineEngine.modelUrl, modelFile) { progress ->
                onProgress?.invoke("Baixando voz ${voice.displayName} (${(progress * 100).toInt()}%)...", progress * 0.85f)
            }
        }

        // 5. Configuração e geração de tokens
        if (!tokensFile.exists() || tokensFile.length() < 50L) {
            if (!configFile.exists() || configFile.length() == 0L) {
                onProgress?.invoke("Baixando configurações fonéticas...", 0.9f)
                downloadFile(offlineEngine.configUrl, configFile)
            }
            generateTokensFromConfigJson(configFile, tokensFile)
        }

        // Validação final estrita
        val phondata = File(espeakDir, "phondata")
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            throw IllegalStateException("Arquivo do modelo inválido ou incompleto: ${modelFile.absolutePath}")
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            throw IllegalStateException("Arquivo de tokens ausente: ${tokensFile.absolutePath}")
        }
        if (!espeakDir.exists() || !phondata.exists() || phondata.length() == 0L) {
            throw IllegalStateException("Diretório espeak-ng-data ausente em: ${espeakDir.absolutePath}")
        }

        Log.i(TAG, "PiperVoiceManager: Voz offline '${voice.displayName}' pronta!")

        ModelPaths(
            modelPath = modelFile.absolutePath,
            tokensPath = tokensFile.absolutePath,
            dataDirPath = espeakDir.absolutePath
        )
    }

    /**
     * Download autônomo com progresso percentual (0.0 a 1.0) para uso direto na interface.
     */
    suspend fun downloadVoice(
        context: Context,
        voice: AvailableVoice,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val offlineEngine = voice.engine as? VoiceEngine.OfflinePiper ?: return@withContext false
        val voiceDir = getVoiceDirectory(context, voice)
        val espeakDir = getSharedEspeakDirectory(context)
        val modelFile = File(voiceDir, offlineEngine.modelFileName)
        val configFile = File(voiceDir, offlineEngine.configFileName)
        val tokensFile = File(voiceDir, voice.tokensFileName)

        try {
            copyEspeakDataSafely(context.assets, espeakDir)

            if (!configFile.exists() || configFile.length() == 0L) {
                downloadFile(offlineEngine.configUrl, configFile)
            }
            generateTokensFromConfigJson(configFile, tokensFile)

            if (!modelFile.exists() || modelFile.length() < 30_000_000L) {
                downloadFile(offlineEngine.modelUrl, modelFile) { progress ->
                    onProgress?.invoke(progress)
                }
            }

            onProgress?.invoke(1.0f)
            isVoiceReady(context, voice)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao baixar voz offline ${voice.displayName}: ${t.message}", t)
            false
        }
    }

    /**
     * Exclui os arquivos da voz offline para liberar espaço em disco (~60 MB).
     */
    fun deleteVoice(context: Context, voice: AvailableVoice): Boolean {
        if (!voice.isOfflineCapable) return false
        val offlineEngine = voice.engine as? VoiceEngine.OfflinePiper ?: return false
        val voiceDir = getVoiceDirectory(context, voice)
        val modelFile = File(voiceDir, offlineEngine.modelFileName)
        val configFile = File(voiceDir, offlineEngine.configFileName)
        val tokensFile = File(voiceDir, voice.tokensFileName)

        var deleted = false
        try {
            if (modelFile.exists()) deleted = modelFile.delete() || deleted
            if (configFile.exists()) configFile.delete()
            if (tokensFile.exists()) tokensFile.delete()
            Log.i(TAG, "PiperVoiceManager: modelo offline ${voice.displayName} excluído do disco.")
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao excluir modelo ${voice.displayName}: ${e.message}")
        }
        return deleted
    }

    private fun copyEspeakDataSafely(assetManager: AssetManager, espeakDir: File) {
        val phondata = File(espeakDir, "phondata")
        val ptDict = File(espeakDir, "pt_dict")
        if (!phondata.exists() || phondata.length() == 0L || !ptDict.exists()) {
            Log.d(TAG, "PiperVoiceManager: copiando espeak-ng-data de assets...")
            copyAssetFolderRecursive(assetManager, "voice/espeak-ng-data", espeakDir)
            if (!phondata.exists()) {
                copyAssetFolderRecursive(assetManager, "espeak-ng-data", espeakDir)
            }
        }
    }

    private fun copyIndividualAsset(
        assetManager: AssetManager,
        assetPath: String,
        destFile: File,
        minSize: Long = 0L
    ) {
        if (destFile.exists() && destFile.length() >= minSize) {
            return
        }

        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            assetManager.open(assetPath).use { input ->
                Log.d(TAG, "Copiando asset '$assetPath' para '${destFile.absolutePath}'...")
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)
            Log.d(TAG, "Asset '$assetPath' copiado com sucesso (${destFile.length()} bytes)")
        } catch (_: Exception) {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    private fun copyAssetFolderRecursive(
        assetManager: AssetManager,
        fromAssetPath: String,
        toDir: File
    ) {
        try {
            val list = assetManager.list(fromAssetPath) ?: return
            if (!toDir.exists()) toDir.mkdirs()

            for (file in list) {
                val subAssetPath = "$fromAssetPath/$file"
                val subDestFile = File(toDir, file)

                val subList = try { assetManager.list(subAssetPath) } catch (_: Exception) { null }
                if (!subList.isNullOrEmpty()) {
                    copyAssetFolderRecursive(assetManager, subAssetPath, subDestFile)
                } else {
                    if (!subDestFile.exists() || subDestFile.length() == 0L) {
                        try {
                            assetManager.open(subAssetPath).use { input ->
                                val tmp = File(toDir, "$file.tmp")
                                FileOutputStream(tmp).use { output ->
                                    input.copyTo(output)
                                }
                                if (subDestFile.exists()) subDestFile.delete()
                                tmp.renameTo(subDestFile)
                            }
                        } catch (e: IOException) {
                            subDestFile.mkdirs()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao copiar pasta '$fromAssetPath': ${e.message}")
        }
    }

    fun generateTokensFromConfigJson(jsonFile: File, outputFile: File) {
        try {
            val jsonText = jsonFile.readText()
            val root = JSONObject(jsonText)
            val phonemeIdMap = root.optJSONObject("phoneme_id_map") ?: return

            val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
            tmpFile.bufferedWriter().use { writer ->
                val keys = phonemeIdMap.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val idArray = phonemeIdMap.optJSONArray(key)
                    if (idArray != null && idArray.length() > 0) {
                        val id = idArray.getInt(0)
                        writer.write("$key $id\n")
                    }
                }
            }
            if (outputFile.exists()) outputFile.delete()
            tmpFile.renameTo(outputFile)
            Log.d(TAG, "tokens gerado com sucesso em ${outputFile.name}: ${outputFile.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao converter phoneme_id_map para tokens: ${e.message}", e)
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val request = Request.Builder().url(url).build()
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Falha no download de $url (HTTP ${response.code})")
            }
            val body = response.body ?: throw IllegalStateException("Corpo vazio para $url")
            val totalBytes = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0 && onProgress != null) {
                            val progress = (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                }
            }
        }
        if (destFile.exists()) destFile.delete()
        tempFile.renameTo(destFile)
    }
}
