package com.luna.reader.tts

/**
 * Catálogo de vozes neurais offline em Português Brasileiro (pt-BR)
 * suportadas nativamente pelo motor Sherpa-ONNX (Piper TTS / VITS).
 */
enum class AvailableVoice(
    val id: String,
    val displayName: String,
    val description: String,
    val modelFileName: String,
    val configFileName: String,
    val downloadUrlModel: String,
    val downloadUrlConfig: String,
    val sampleRate: Int = 22050
) {
    LUCAS_RAZO(
        id = "lucas_razo",
        displayName = "Lucas Razo",
        description = "Narrador natural e expressivo (pt-BR)",
        modelFileName = "razo.onnx",
        configFileName = "razo.onnx.json",
        downloadUrlModel = "https://huggingface.co/Lucasllfs/Razo-piper-voice/resolve/main/pt-BR-razo-medium.onnx",
        downloadUrlConfig = "https://huggingface.co/Lucasllfs/Razo-piper-voice/resolve/main/config.json"
    ),
    FABER(
        id = "faber",
        displayName = "Faber",
        description = "Dicção clássica e neutra para leitura (pt-BR)",
        modelFileName = "pt_BR-faber-medium.onnx",
        configFileName = "pt_BR-faber-medium.onnx.json",
        downloadUrlModel = "https://huggingface.co/csukuangfj/vits-piper-pt_BR-faber-medium/resolve/main/pt_BR-faber-medium.onnx",
        downloadUrlConfig = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json"
    );

    val tokensFileName: String
        get() = "${id}_tokens.txt"

    companion object {
        val DEFAULT = LUCAS_RAZO

        fun fromId(id: String?): AvailableVoice {
            if (id == null) return DEFAULT
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
