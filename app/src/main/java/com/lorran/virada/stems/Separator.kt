package com.lorran.virada.stems

import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/**
 * Modelo que recebe o espectro de magnitude (2, 1, 512, 1024) e devolve uma estimativa
 * de espectro por faixa, no mesmo formato.
 */
interface MaskModel {
    val stemCount: Int
    fun run(input: FloatArray, outputs: Array<FloatArray>)
}

/**
 * Separação no estilo Spleeter (2 ou 4 faixas) com máscara de Wiener entre as faixas.
 * Processa em blocos de 512 quadros (~12 s) para manter a memória baixa.
 * As faixas sempre somam exatamente a mistura original.
 */
class SpleeterSeparator(private val model: MaskModel) {

    companion object {
        const val SR = 44100
        const val N = 4096
        const val HOP = 1024
        const val T = 512
        const val F = 1024
        const val BINS = N / 2 + 1
        const val PAD = N - HOP
        /** Soma de hann² com 75% de sobreposição. */
        const val WSUM = 1.5f
        private const val HIGH_FROM = 960
    }

    private val fft = Fft(N)
    private val win = Fft.hann(N)

    fun separate(
        source: StemSource,
        writers: List<StemSink>,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val s = model.stemCount
        require(writers.size == s) { "Esperado $s saídas" }
        val n = source.frames
        val paddedLen = PAD + n + PAD + N
        val nFrames = ((paddedLen - N) / HOP + 1).toInt()
        val nChunks = (nFrames + T - 1) / T

        val specRe = Array(2) { FloatArray(T * BINS) }
        val specIm = Array(2) { FloatArray(T * BINS) }
        val mag = FloatArray(2 * T * F)
        val outs = Array(s) { FloatArray(2 * T * F) }
        // máscara [faixa][canal]
        val mask = Array(s) { Array(2) { FloatArray(T * BINS) } }
        val olaLen = T * HOP + N - HOP
        val ola = Array(s) { Array(2) { FloatArray(olaLen) } }
        val segLen = T * HOP + N - HOP
        val segL = FloatArray(segLen)
        val segR = FloatArray(segLen)
        val zr = FloatArray(N)
        val zi = FloatArray(N)
        val sq = FloatArray(s)
        val hi = FloatArray(s)
        val eps = 1e-10f
        val epsPer = eps / s

        for (c in 0 until nChunks) {
            if (isCancelled()) throw CancellationException()
            val f0 = c * T
            val nf = minOf(T, nFrames - f0)
            val padStart = f0.toLong() * HOP
            val segCount = (nf - 1) * HOP + N
            source.readFloat(padStart - PAD, segCount, segL, segR)

            // STFT dos dois canais de uma vez (truque do sinal complexo)
            java.util.Arrays.fill(mag, 0f)
            for (j in 0 until nf) {
                val o = j * HOP
                for (i in 0 until N) {
                    zr[i] = segL[o + i] * win[i]
                    zi[i] = segR[o + i] * win[i]
                }
                fft.transform(zr, zi)
                val base = j * BINS
                for (k in 0 until BINS) {
                    val nk = (N - k) and (N - 1)
                    val r0 = 0.5f * (zr[k] + zr[nk]); val i0 = 0.5f * (zi[k] - zi[nk])
                    val r1 = 0.5f * (zi[k] + zi[nk]); val i1 = 0.5f * (zr[nk] - zr[k])
                    specRe[0][base + k] = r0; specIm[0][base + k] = i0
                    specRe[1][base + k] = r1; specIm[1][base + k] = i1
                    if (k < F) {
                        mag[j * F + k] = sqrt(r0 * r0 + i0 * i0)
                        mag[T * F + j * F + k] = sqrt(r1 * r1 + i1 * i1)
                    }
                }
            }

            model.run(mag, outs)

            // Máscara de Wiener; acima de 11 kHz usa a média das últimas faixas de frequência
            for (ch in 0 until 2) {
                for (j in 0 until nf) {
                    val mb = ch * T * F + j * F
                    java.util.Arrays.fill(hi, 0f)
                    for (k in 0 until F) {
                        var sum = eps
                        for (t in 0 until s) { val v = outs[t][mb + k]; sq[t] = v * v; sum += sq[t] }
                        for (t in 0 until s) {
                            val mk = (sq[t] + epsPer) / sum
                            mask[t][ch][j * BINS + k] = mk
                            if (k >= HIGH_FROM) hi[t] += mk
                        }
                    }
                    for (t in 0 until s) {
                        val h = hi[t] / (F - HIGH_FROM)
                        val m = mask[t][ch]
                        for (k in F until BINS) m[j * BINS + k] = h
                    }
                }
            }

            // ISTFT + sobreposição para cada faixa
            for (stem in 0 until s) {
                val o0 = ola[stem][0]; val o1 = ola[stem][1]
                val m0a = mask[stem][0]; val m1a = mask[stem][1]
                for (j in 0 until nf) {
                    val base = j * BINS
                    for (k in 0 until BINS) {
                        val m0 = m0a[base + k]; val m1 = m1a[base + k]
                        val y0r = specRe[0][base + k] * m0; val y0i = specIm[0][base + k] * m0
                        val y1r = specRe[1][base + k] * m1; val y1i = specIm[1][base + k] * m1
                        zr[k] = y0r - y1i
                        zi[k] = y0i + y1r
                        if (k in 1 until N / 2) {
                            zr[N - k] = y0r + y1i
                            zi[N - k] = y1r - y0i
                        }
                    }
                    fft.transform(zr, zi, inverse = true)
                    val o = j * HOP
                    for (i in 0 until N) {
                        val w = win[i] / WSUM
                        o0[o + i] += zr[i] * w
                        o1[o + i] += zi[i] * w
                    }
                }
            }

            // Amostras prontas: [0, nf*HOP) deste bloco, só as que pertencem ao áudio real
            val ready = nf * HOP
            val first = maxOf(0L, PAD - padStart).toInt()
            val last = minOf(ready.toLong(), n + PAD - padStart).toInt()
            if (last > first) {
                for (stem in 0 until s) {
                    writers[stem].write(ola[stem][0], ola[stem][1], first, last - first)
                }
            }
            // leva a cauda para o próximo bloco
            for (stem in 0 until s) for (ch in 0 until 2) {
                val buf = ola[stem][ch]
                System.arraycopy(buf, ready, buf, 0, N - HOP)
                java.util.Arrays.fill(buf, N - HOP, olaLen, 0f)
            }
            progress((c + 1).toFloat() / nChunks)
        }
    }
}
