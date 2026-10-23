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
 * Gerenciador de arquivos dos modelos neurais de voz offline (Piper TTS / VITS / Sherpa-ONNX).
 *
 * Responsável por:
 * 1. Verificar integridade e disponibilidade local de cada narrador (Lucas Razo, Faber, etc.).
 * 2. Copiar modelos empacotados em `assets/voice/` de forma atômica (via `.tmp`).
 * 3. Baixar modelos sob demanda do Hugging Face com relatório de progresso.
 * 4. Extrair e converter o mapeamento de fonemas (`phoneme_id_map`) em tokens.txt.
 */
object PiperVoiceManager {

    private const val TAG = "LunaTTS"
    private const val ESPEAK_DIR_NAME = "espeak-ng-data"
    private const val VOICE_V2_MARKER = ".voice_v2_metadata_ready"

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

    fun getVoiceDirectory(context: Context): File {
        val dir = File(context.filesDir, "voice")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Retorna o arquivo de tokens correspondente para a voz informada.
     */
    fun getTokensFile(voiceDir: File, voice: AvailableVoice): File {
        val voiceSpecific = File(voiceDir, voice.tokensFileName)
        if (voiceSpecific.exists() && voiceSpecific.length() > 50L) {
            return voiceSpecific
        }
        // Fallback para tokens.txt genérico (compatibilidade com Lucas Razo)
        if (voice == AvailableVoice.LUCAS_RAZO) {
            val genericTokens = File(voiceDir, "tokens.txt")
            if (genericTokens.exists() && genericTokens.length() > 50L) {
                return genericTokens
            }
        }
        return voiceSpecific
    }

    /**
     * Validação rigorosa se a voz está pronta para execução offline imediata no disco.
     */
    fun isVoiceReady(context: Context, voice: AvailableVoice): Boolean {
        val voiceDir = getVoiceDirectory(context)
        val modelFile = File(voiceDir, voice.modelFileName)
        val tokensFile = getTokensFile(voiceDir, voice)
        val configFile = File(voiceDir, voice.configFileName)
        val espeakDir = File(voiceDir, ESPEAK_DIR_NAME)
        val phondata = File(espeakDir, "phondata")
        val phontab = File(espeakDir, "phontab")

        val hasModel = modelFile.exists() && modelFile.length() > 30_000_000L
        val hasTokens = (tokensFile.exists() && tokensFile.length() > 50L) ||
                (configFile.exists() && configFile.length() > 100L)
        val hasEspeak = espeakDir.exists() && phondata.exists() && phondata.length() > 1000L && phontab.exists()

        return hasModel && hasTokens && hasEspeak
    }

    /**
     * Prepara e valida os arquivos necessários para inicializar a voz no Sherpa-ONNX.
     * Copia de assets caso existam; caso contrário, executa download sob demanda.
     */
    suspend fun prepareVoice(
        context: Context,
        voice: AvailableVoice,
        onProgress: ((message: String, progress: Float) -> Unit)? = null
    ): ModelPaths = withContext(Dispatchers.IO) {
        val voiceDir = getVoiceDirectory(context)
        val modelFile = File(voiceDir, voice.modelFileName)
        val configFile = File(voiceDir, voice.configFileName)
        val tokensFile = getTokensFile(voiceDir, voice)
        val espeakDir = File(voiceDir, ESPEAK_DIR_NAME)

        Log.d(TAG, "PiperVoiceManager: preparando voz '${voice.displayName}' em ${voiceDir.absolutePath}...")

        // Se Lucas Razo, garante limpeza da v1 sem metadados
        if (voice == AvailableVoice.LUCAS_RAZO) {
            val versionMarker = File(voiceDir, VOICE_V2_MARKER)
            if (!versionMarker.exists()) {
                Log.i(TAG, "PiperVoiceManager: migração v2 detectada para Lucas Razo...")
                try {
                    if (modelFile.exists()) modelFile.delete()
                    if (configFile.exists()) configFile.delete()
                    if (tokensFile.exists()) tokensFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Aviso na limpeza: ${e.message}")
                }
            }
        }

        // Se o modelo existir mas tiver tamanho corrompido (< 30 MB), deleta
        if (modelFile.exists() && modelFile.length() < 30_000_000L) {
            Log.w(TAG, "PiperVoiceManager: modelo corrompido detectado (${modelFile.length()} bytes). Deletando...")
            modelFile.delete()
        }

        // 1. Tenta copiar arquivos correspondentes de assets/voice/
        copyVoiceAssetsSafely(context, voice, voiceDir, espeakDir)

        if (voice == AvailableVoice.LUCAS_RAZO && modelFile.exists() && modelFile.length() > 30_000_000L) {
            try { File(voiceDir, VOICE_V2_MARKER).createNewFile() } catch (_: Exception) {}
        }

        // 2. Garante geração de tokens a partir do config JSON se necessário
        if ((!tokensFile.exists() || tokensFile.length() < 50L) && configFile.exists() && configFile.length() > 0L) {
            Log.d(TAG, "PiperVoiceManager: gerando tokens para ${voice.id}...")
            generateTokensFromConfigJson(configFile, tokensFile)
        }

        // 3. Fallback: Download sob demanda se o modelo ainda não existir
        if (!modelFile.exists() || modelFile.length() < 30_000_000L) {
            onProgress?.invoke("Baixando voz neural ${voice.displayName}...", 0.1f)
            Log.i(TAG, "PiperVoiceManager: baixando ${voice.modelFileName} de ${voice.downloadUrlModel}...")
            downloadFile(voice.downloadUrlModel, modelFile) { progress ->
                onProgress?.invoke("Baixando voz ${voice.displayName} (${(progress * 100).toInt()}%)...", progress * 0.85f)
            }
        }

        if (!tokensFile.exists() || tokensFile.length() < 50L) {
            if (!configFile.exists() || configFile.length() == 0L) {
                onProgress?.invoke("Baixando configurações da voz...", 0.9f)
                Log.i(TAG, "PiperVoiceManager: baixando config para ${voice.id}...")
                downloadFile(voice.downloadUrlConfig, configFile)
            }
            generateTokensFromConfigJson(configFile, tokensFile)
        }

        // 4. Validação final estrita
        val phondata = File(espeakDir, "phondata")
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            throw IllegalStateException("Arquivo do modelo inválido ou incompleto: ${modelFile.absolutePath}")
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            throw IllegalStateException("Arquivo de tokens ausente ou vazio: ${tokensFile.absolutePath}")
        }
        if (!espeakDir.exists() || !phondata.exists() || phondata.length() == 0L) {
            throw IllegalStateException("Diretório espeak-ng-data corrompido em: ${espeakDir.absolutePath}")
        }

        Log.i(TAG, "PiperVoiceManager: Voz '${voice.displayName}' pronta e validada com sucesso!")
        Log.d(TAG, "  -> Model: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)} MB)")
        Log.d(TAG, "  -> Tokens: ${tokensFile.absolutePath} (${tokensFile.length()} bytes)")
        Log.d(TAG, "  -> EspeakData: ${espeakDir.absolutePath}")

        ModelPaths(
            modelPath = modelFile.absolutePath,
            tokensPath = tokensFile.absolutePath,
            dataDirPath = espeakDir.absolutePath
        )
    }

    /**
     * Download autônomo com progresso percentual (0.0 a 1.0) para uso direto no BottomSheet.
     */
    suspend fun downloadVoice(
        context: Context,
        voice: AvailableVoice,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val voiceDir = getVoiceDirectory(context)
        val modelFile = File(voiceDir, voice.modelFileName)
        val configFile = File(voiceDir, voice.configFileName)
        val tokensFile = getTokensFile(voiceDir, voice)
        val espeakDir = File(voiceDir, ESPEAK_DIR_NAME)

        try {
            // Garante que espeak-ng-data esteja presente
            copyEspeakDataSafely(context.assets, espeakDir)

            // Download do config.json primeiro (rápido, ~7KB)
            if (!configFile.exists() || configFile.length() == 0L) {
                downloadFile(voice.downloadUrlConfig, configFile)
            }
            generateTokensFromConfigJson(configFile, tokensFile)

            // Download do modelo .onnx (~63MB)
            if (!modelFile.exists() || modelFile.length() < 30_000_000L) {
                downloadFile(voice.downloadUrlModel, modelFile) { progress ->
                    onProgress?.invoke(progress)
                }
            }

            onProgress?.invoke(1.0f)
            isVoiceReady(context, voice)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao baixar voz ${voice.displayName}: ${t.message}", t)
            false
        }
    }

    private fun copyVoiceAssetsSafely(context: Context, voice: AvailableVoice, voiceDir: File, espeakDir: File) {
        val assetManager = context.assets

        // Copia arquivos do modelo e config específicos
        copyIndividualAsset(assetManager, "voice/${voice.modelFileName}", File(voiceDir, voice.modelFileName), minSize = 30_000_000L)
        copyIndividualAsset(assetManager, "voice/${voice.configFileName}", File(voiceDir, voice.configFileName), minSize = 100L)
        copyIndividualAsset(assetManager, "voice/${voice.tokensFileName}", File(voiceDir, voice.tokensFileName), minSize = 50L)

        // Se for Lucas Razo, também aceita tokens.txt genérico
        if (voice == AvailableVoice.LUCAS_RAZO) {
            copyIndividualAsset(assetManager, "voice/tokens.txt", File(voiceDir, "tokens.txt"), minSize = 50L)
        }

        // Copia diretório espeak-ng-data compartilhado
        copyEspeakDataSafely(assetManager, espeakDir)
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
        } catch (e: Exception) {
            if (tmpFile.exists()) tmpFile.delete()
            Log.d(TAG, "Asset '$assetPath' não encontrado em assets (será feito download se necessário)")
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
