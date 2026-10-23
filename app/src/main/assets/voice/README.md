# Dados Fonéticos para Síntese Neural Offline (Sherpa-ONNX / Piper TTS)

Este diretório contém os dados fonéticos de base (`espeak-ng-data/`) necessários para o narrador offline **Faber** (`pt_BR-faber-medium`).

Os arquivos de modelo neural `.onnx` e `.json` são baixados sob demanda diretamente pelo aplicativo para o diretório interno do dispositivo (`context.filesDir/voices/faber/`), mantendo o tamanho do pacote APK leve.
