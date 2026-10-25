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
