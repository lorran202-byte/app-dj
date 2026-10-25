# Testes — Virada DJ v1.0

Automáticos (rodam no GitHub a cada versão): 14 de 14 passando
- BPM detectado em faixas de 92, 100, 128 e 140 BPM (erro < 0,3) e primeira batida certa (< 30 ms)
- Tocar com pitch +8% anda na velocidade certa · para no fim da música
- Loop não escapa do trecho · Reverso volta e, ao soltar, a música continua onde estaria (slip)
- EQ no meio não muda o volume · grave no zero corta o grave · agudo+médio no zero mantêm o grave
- Os 8 efeitos com intensidade máxima + filtro no extremo: sem estouro e sem NaN
- Os 8 pads soam e não passam do limite · gravação gera WAV válido com o tamanho certo

Lint: 0 erros, 13 avisos (sem gravidade). APK: com.lorran.virada 1.0 (code 1), minSdk 24, target 35, 1,2 MB, assinado com chave fixa.

Não testado em aparelho real (sem emulador aqui): toque no prato, encaixe da tela em celulares diferentes, latência do som.
Bugs abertos: nenhum S1/S2 conhecido.

## 1.1 — Keylock
- 5 testes: tom mantido a 1,08x e 0,90x (440 Hz ± 4), volume estável, velocidade certa; sem keylock o tom acompanha.

## 1.2 — Stems e Audius
- Separação (Spleeter 4 faixas do Cantare, ONNX): com modelo falso, as faixas somam a música (erro < 0,001) e têm o tamanho certo.
- Deck: mutar a voz zera só os 440 Hz (< 2%), bateria e melodia ficam (± 8%); acapela + keylock a 1,06x mantém tom e volume da voz.
- APK 111 MB (modelos 79 MB + ONNX Runtime arm64/armv7). Publicado em partes de 45 MB no branch builds (limite de 100 MB do git).
- Audius: busca e em alta conferidos pela API pública (sem chave). Download e separação não testados em aparelho real.
