# Arquitetura — Virada DJ

- Package: `com.lorran.virada` · Kotlin + Jetpack Compose · minSdk 24, target 35 · tela em paisagem
- Motor de áudio próprio (não usa ExoPlayer, porque precisa de 2 decks com scratch, loops e efeitos amostra por amostra):
  - `Decoder`: MediaExtractor + MediaCodec → PCM 16 bits estéreo 44,1 kHz num arquivo de cache, lido com memory-map (não pesa na RAM)
  - `Analyzer`: onda (picos a cada 10 ms), BPM (autocorrelação + pente de batidas), primeira batida, ganho automático
  - `Deck`: leitura com velocidade variável (pitch/scratch), loops, hot cues, slip (roll/reverso), EQ isolador 3 bandas, filtro, efeitos
  - `Fx`: eco ping-pong, flanger, reverb (Freeverb reduzido), phaser, gate, roll, reverso, freio
  - `Sampler`: 8 sons sintetizados no próprio app (sem samples de terceiros)
  - `Engine`: thread de áudio prioritária, AudioTrack float de baixa latência, crossfader, master, limitador, pré-escuta dividida, gravação
  - `Recorder`: grava o master em WAV numa thread separada (Música/Virada DJ)
- `PlaybackService` em primeiro plano para o som não parar com a tela apagada
- Dados: cues e BPM em SharedPreferences; análise e PCM em cache (máx. 16 faixas)
- Permissões: READ_MEDIA_AUDIO (ou READ_EXTERNAL_STORAGE ≤ Android 12) para listar músicas; POST_NOTIFICATIONS e FOREGROUND_SERVICE_MEDIA_PLAYBACK para o serviço. Nada de internet.
