# 🌙 Luna Ebook Reader — Leitor de E-Books com Áudio Híbrido (Offline & Online)

<div align="center">

![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple?style=for-the-badge&logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-green?style=for-the-badge&logo=jetpackcompose)
![Sherpa-ONNX](https://img.shields.io/badge/Sherpa--ONNX-Piper%20VITS-orange?style=for-the-badge)
![Microsoft Edge TTS](https://img.shields.io/badge/Microsoft%20Edge%20TTS-WebSockets-blue?style=for-the-badge&logo=microsoft)
![AndroidX Media3](https://img.shields.io/badge/Media3%20ExoPlayer-1.5.0-blue?style=for-the-badge&logo=android)
![Room Database](https://img.shields.io/badge/Room-2.6.1-red?style=for-the-badge&logo=sqlite)
![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)

**Leitor moderno de e-books (EPUB e PDF) com reprodução de áudio via motor híbrido inteligente: síntese neural local 100% offline (Faber via Piper TTS / Sherpa-ONNX) e vozes neurais da Microsoft (Edge-TTS) online sob demanda.**

</div>

---

## 📖 Sobre o Projeto

**Luna Ebook Reader** é um aplicativo Android desenvolvido em Kotlin e Jetpack Compose com foco em leitura imersiva e acessibilidade de alta fidelidade. O app permite importar arquivos nos formatos **EPUB** e **PDF**, convertendo-os em capítulos estruturados e parágrafos legíveis com destaque sincronizado da fala em tempo real.

O diferencial do Luna é a sua **Arquitetura de Áudio Híbrida**:
1. **100% Offline (Faber):** Processamento local diretamente na CPU do aparelho via C++/JNI **Sherpa-ONNX** com o modelo neural **Faber (`pt_BR-faber-medium`)**. O modelo é baixado sob demanda (~60 MB) sem inflar o tamanho do APK, e pode ser excluído a qualquer momento para liberar espaço.
2. **Online sob Demanda (Microsoft Edge Neural):** Streaming em buffer via WebSockets das vozes de alta fidelidade em português brasileiro (**Francisca**, **Antônio**, **Brenda**, **Donato** e **Thalita**), sem necessidade de downloads volumosos e com tolerância a quedas de rede.

---

## ✨ Principais Funcionalidades

- 📚 **Biblioteca Inteligente:**
  - Importação de arquivos `.epub` e `.pdf` diretamente do armazenamento local.
  - Salvamento automático da posição exata de leitura (capítulo, parágrafo e milissegundo).
  - Gestão de livros favoritos e exclusão com diálogo de confirmação.

- 🎙️ **Motor de Áudio Híbrido (`AudioSynthesisRouter`):**
  - **Faber (Offline):** Dicção clássica e neutra para leitura sem internet, sem envio de dados a servidores.
  - **Vozes Microsoft (Online):** 5 opções em pt-BR (femininas e masculinas) com expressividade neural.
  - **Roteamento Inteligente:** Alterne narradores em tempo real; detecção automática de conectividade e aviso com opção de retorno à voz offline Faber caso a internet caia.

- ⚡ **Sincronização Palavra por Palavra (Word-Boundary Highlights):**
  - Destaque visual suave da palavra exata que está sendo pronunciada no momento da fala.
  - Toque em qualquer parágrafo para saltar a leitura e o áudio instantaneamente para ele.
  - Rolagem automática inteligente: mantém o texto em leitura sempre visível e centralizado.

- 📑 **Sumário e Navegação Estruturada:**
  - Extração automática de índices (TOC) para e-books EPUB.
  - Navegação fluida entre capítulos via *Modal Bottom Sheet*.

- 🎧 **Reprodução Contínua em Segundo Plano:**
  - Gerenciamento de áudio com **AndroidX Media3 (ExoPlayer)** integrado ao `MediaSessionService`.
  - Controles interativos na central de notificações do sistema e na tela de bloqueio.
  - Buffer duplo inteligente (`AudioPrefetchManager`) com pré-carregamento contínuo para transição ininterrupta entre parágrafos com tela bloqueada.

- 🎨 **Design Editorial Moderno:**
  - Interface construída 100% com **Jetpack Compose** seguindo diretrizes do **Material Design 3**.
  - Tipografia serifada editorial e espaçamentos inspirados em leitores de referência (Kindle / Apple Books).
  - Modos de exibição: **Modo Leitura Fluida** e **Modo Capa (Now Playing)**.

---

## 🏗️ Arquitetura do Projeto

```
app/src/main/java/com/luna/reader/
├── LunaApp.kt                           # Classe de inicialização da aplicação
├── MainActivity.kt                      # Activity principal com navegação Compose
├── data/
│   └── local/                           # Camada de persistência local
│       ├── BookEntity.kt                # Entidade de persistência do livro
│       ├── BookDao.kt                   # DAO com consultas assíncronas Room
│       ├── LunaDatabase.kt              # Banco de dados Room SQLite
│       └── UserPreferencesRepository.kt # DataStore (voz selecionada, velocidade, escala)
├── document/                            # Processamento e parsing de documentos
│   ├── Chapter.kt                       # Modelo de dados de capítulos
│   ├── DocumentChunker.kt               # Divisão em blocos de síntese e leitura
│   ├── EpubTextExtractor.kt             # Extrator completo de EPUB (HTML/XHTML & TOC)
│   └── PdfTextExtractor.kt              # Extrator de páginas e texto de PDF
├── playback/                            # Pipeline de áudio e pré-carregamento
│   └── AudioPrefetchManager.kt          # Orquestrador de chunks, cache e fila ExoPlayer
├── service/                             # Serviços do sistema Android
│   └── LunaPlaybackService.kt           # MediaSessionService do AndroidX Media3
├── tts/                                 # Motores de síntese de voz (Híbrido)
│   ├── AudioSynthesisRouter.kt          # Roteador de síntese entre Local e Edge-TTS
│   ├── VoiceModel.kt                    # Catálogo híbrido de vozes (Faber & Microsoft Edge)
│   ├── LocalTtsManager.kt               # Instanciação Sherpa-ONNX, JNI e síntese PCM/WAV
│   ├── PiperVoiceManager.kt             # Gerenciamento de download sob demanda e tokens
│   ├── WordBoundary.kt                  # Metadados de marcação temporal por palavra
│   └── edge/                            # Cliente Microsoft Edge-TTS via WebSockets
│       ├── EdgeTtsClient.kt             # Cliente OkHttp WebSockets com SSML e parsing MP3
│       └── SecMsGec.kt                  # Autenticação DRM v2 Microsoft Edge-TTS
└── ui/                                  # Interface com o Usuário (Jetpack Compose)
    ├── components/                      # Componentes (VoiceManagerBottomSheet, ChapterListBottomSheet)
    ├── screens/                         # Telas (LibraryScreen, ReaderPlayerScreen)
    └── theme/                           # Cores, tipografia editorial e temas Material 3
```

---

## 🛠️ Tecnologias e Dependências

- **Linguagem:** [Kotlin 2.0.21](https://kotlinlang.org/)
- **UI Toolkit:** [Jetpack Compose (BOM 2024.10.01)](https://developer.android.com/jetpack/compose) + Material 3
- **Motor Offline (Sherpa-ONNX):** [Sherpa-ONNX 1.10.35](https://github.com/k2-fsa/sherpa-onnx) + [Piper TTS / VITS](https://github.com/rhasspy/piper)
- **Modelo Offline pt-BR:** [csukuangfj/vits-piper-pt_BR-faber-medium](https://huggingface.co/csukuangfj/vits-piper-pt_BR-faber-medium)
- **Motor Online (Microsoft Edge-TTS):** WebSockets via [OkHttp 4.12.0](https://square.github.io/okhttp/) com protocolo DRM v2
- **Áudio & Sessão:** [AndroidX Media3 1.5.0](https://developer.android.com/guide/topics/media/media3)
- **Banco de Dados Local:** [Room 2.6.1](https://developer.android.com/training/data-storage/room)
- **Configurações:** [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore)

---

## 🚀 Como Compilar e Executar

### Pré-requisitos
- **Android Studio** Ladybug (2024.2.1+) ou versão recente.
- **JDK 17** instalado e configurado no ambiente.
- Dispositivo Android físico ou emulador executando **Android 8.0 (API 26)** ou superior.

### Passos:

1. **Clone o repositório:**
   ```bash
   git clone https://github.com/Brennerdsv/Luna-Ebook-Reader.git
   cd Luna-Ebook-Reader
   ```

2. **Compile o APK de depuração:**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Instale no dispositivo conectado via ADB:**
   ```bash
   ./gradlew installDebug
   ```

*(O arquivo APK gerado fica disponível em: `app/build/outputs/apk/debug/app-debug.apk`).*

---

## 📄 Licença

Distribuído sob os termos da licença **MIT**. Veja `LICENSE` para mais detalhes.
