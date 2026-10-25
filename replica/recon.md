# Recon — app de DJ inspirado no edjing Mix

Fontes públicas: página oficial da MWM (mwm.ai/mwm-studio/apps/edjing), ficha da App Store,
análises (Common Sense Media, grand-screen, marlvel, unitQ).

## O que o edjing Mix faz (funções, não conteúdo)
- Dois decks com prato virtual (jog/scratch), crossfader, EQ e hot cues
- Detecção automática de BPM e sincronia entre os decks
- Mais de 20 efeitos (eco, flanger, reverse, filtro, reverb)
- Sampler com pads
- Loops de 1/64 a 128 batidas
- Separação de stems em tempo real (voz, bateria, baixo) — recurso pago
- Fontes de música: biblioteca do celular + serviços de streaming (Beatport, SoundCloud) — FORA (conteúdo/rede de terceiros)
- Gravação da mixagem
- Skins de prato, aulas (masterclass)

## Telas
1. Tela principal (paisagem): onda das duas músicas no topo, deck A à esquerda, deck B à direita, mixer no meio
2. Biblioteca: busca e escolha de música para o deck A ou B
3. Painéis por deck: Cues, Loops, FX, Sampler
4. Gravação / lista de gravações
5. Configurações (curva do crossfader, pré-escuta)

## Fluxo principal
Abrir app → tocar no deck A → escolher música → play → carregar deck B → SYNC → arrastar crossfader → efeitos → gravar.

## Modelo de dados
Faixa (id, título, artista, duração, uri) · Análise (bpm, primeira batida, picos da onda) · Cues por faixa (4 pontos) · Gravações (arquivo .wav).

## Tamanho
Grande. Versão 1 enxuta: 2 decks com motor de áudio próprio, BPM/sync, EQ 3 bandas, filtro + 5 efeitos,
4 hot cues, loops automáticos, 8 pads com sons próprios, gravação em WAV. Fica para depois: stems, keylock, streaming.
