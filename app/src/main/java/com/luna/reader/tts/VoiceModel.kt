package com.luna.reader.tts

/**
 * Representação do motor de síntese de voz (Local Offline ou Nuvem Microsoft).
 */
sealed class VoiceEngine {
    data class OfflinePiper(
        val modelFileName: String,
        val configFileName: String,
        val modelUrl: String,
        val configUrl: String,
        val approximateSizeMb: Int = 60
    ) : VoiceEngine()

    data class OnlineEdge(
        val voiceIdentifier: String, // ex: pt-BR-FranciscaNeural
        val gender: String
    ) : VoiceEngine()
}

/**
 * Catálogo híbrido de vozes do Luna:
 * - 1 voz 100% OFFLINE: Faber (Piper TTS / VITS / Sherpa-ONNX)
 * - 5 vozes ONLINE: Microsoft Edge Neural (pt-BR)
 */
enum class AvailableVoice(
    val id: String,
    val displayName: String,
    val description: String,
    val isOfflineCapable: Boolean,
    val engine: VoiceEngine
) {
    // Narrador Offline (Sherpa-ONNX / Piper)
    FABER(
        id = "faber_offline",
        displayName = "Faber (Offline)",
        description = "Dicção clássica e neutra para leitura sem internet",
        isOfflineCapable = true,
        engine = VoiceEngine.OfflinePiper(
            modelFileName = "pt_BR-faber-medium.onnx",
            configFileName = "pt_BR-faber-medium.onnx.json",
            modelUrl = "https://huggingface.co/csukuangfj/vits-piper-pt_BR-faber-medium/resolve/main/pt_BR-faber-medium.onnx",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json",
            approximateSizeMb = 60
        )
    ),

    // Narradores Online (Microsoft Edge Neural)
    FRANCISCA(
        id = "ms_francisca",
        displayName = "Francisca (Online)",
        description = "Voz feminina natural e expressiva (Microsoft)",
        isOfflineCapable = false,
        engine = VoiceEngine.OnlineEdge(
            voiceIdentifier = "pt-BR-FranciscaNeural",
            gender = "Feminino"
        )
    ),
    ANTONIO(
        id = "ms_antonio",
        displayName = "Antônio (Online)",
        description = "Voz masculina natural e profissional (Microsoft)",
        isOfflineCapable = false,
        engine = VoiceEngine.OnlineEdge(
            voiceIdentifier = "pt-BR-AntonioNeural",
            gender = "Masculino"
        )
    ),
    BRENDA(
        id = "ms_brenda",
        displayName = "Brenda (Online)",
        description = "Voz feminina jovem e dinâmica (Microsoft)",
        isOfflineCapable = false,
        engine = VoiceEngine.OnlineEdge(
            voiceIdentifier = "pt-BR-BrendaNeural",
            gender = "Feminino"
        )
    ),
    DONATO(
        id = "ms_donato",
        displayName = "Donato (Online)",
        description = "Voz masculina padrão (Microsoft)",
        isOfflineCapable = false,
        engine = VoiceEngine.OnlineEdge(
            voiceIdentifier = "pt-BR-DonatoNeural",
            gender = "Masculino"
        )
    ),
    THALITA(
        id = "ms_thalita",
        displayName = "Thalita (Online)",
        description = "Voz feminina alternativa (Microsoft)",
        isOfflineCapable = false,
        engine = VoiceEngine.OnlineEdge(
            voiceIdentifier = "pt-BR-ThalitaNeural",
            gender = "Feminino"
        )
    );

    val tokensFileName: String
        get() = "${id}_tokens.txt"

    companion object {
        val DEFAULT = FABER

        fun fromId(id: String?): AvailableVoice {
            if (id == null) return DEFAULT
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
