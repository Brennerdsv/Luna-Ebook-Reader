package com.luna.reader.tts

/**
 * Representa um evento de marcação temporal retornado pela síntese neural local.
 *
 * @property text Palavra ou segmento atual
 * @property offsetMs Posição no tempo do áudio (em milissegundos) onde a palavra se inicia
 * @property durationMs Duração da pronúncia da palavra (em milissegundos)
 * @property textOffset Índice inicial do caractere no texto original sintetizado
 * @property wordLength Comprimento em caracteres da palavra
 */
data class WordBoundary(
    val text: String,
    val offsetMs: Long,
    val durationMs: Long,
    val textOffset: Int = 0,
    val wordLength: Int = 0
)

/**
 * Alias de compatibilidade com a enum de catálogo AvailableVoice.
 */
typealias Voice = AvailableVoice

/**
 * Resultado completo da síntese de um bloco de texto gerado localmente pelo modelo neural ONNX.
 */
data class TtsSynthesisResult(
    val audioFilePath: String,
    val wordBoundaries: List<WordBoundary>,
    val durationEstimateMs: Long,
    val text: String
)
