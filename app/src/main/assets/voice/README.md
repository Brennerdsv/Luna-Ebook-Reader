# Modelo de Voz Neural Local (Lucas Razo - Piper TTS / VITS)

Este diretório contém os arquivos do modelo neural offline para o narrador exclusivo do Luna: **Lucas Razo** (`pt-BR`).

### Arquivos Esperados:
1. **`razo.onnx`** (ou `pt-BR-razo-medium.onnx`): Pesos da rede neural do modelo Piper TTS / VITS.
2. **`razo.onnx.json`** (ou `config.json`): Configuração fonética e hiperparâmetros do modelo.
3. **`tokens.txt`** (opcional): Gerado automaticamente a partir do `razo.onnx.json` se não fornecido.
4. **`espeak-ng-data/`** (opcional): Diretório de dados de fonemas espeak-ng.

### Fonte do Modelo:
- Repositório Hugging Face: [Lucasllfs/Razo-piper-voice](https://huggingface.co/Lucasllfs/Razo-piper-voice)
- Download direto do modelo: `https://huggingface.co/Lucasllfs/Razo-piper-voice/resolve/main/pt-BR-razo-medium.onnx`
- Download do config: `https://huggingface.co/Lucasllfs/Razo-piper-voice/resolve/main/config.json`

> **Nota:** Caso o aplicativo seja executado sem esses arquivos nos assets, o utilitário `PiperVoiceManager` realizará o download direto e seguro sob demanda do repositório Hugging Face para o armazenamento interno do dispositivo (`context.filesDir/voice/`).
