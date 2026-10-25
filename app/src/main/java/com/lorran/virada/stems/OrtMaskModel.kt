package com.lorran.virada.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/** Spleeter 4 faixas (Deezer, MIT) em ONNX, rodando com ONNX Runtime. Uma rede por faixa. */
class OrtMaskModel(paths: List<String>) : MaskModel, Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val sessions = paths.map { env.createSession(it, opts) }
    private val shape = longArrayOf(2, 1, SpleeterSeparator.T.toLong(), SpleeterSeparator.F.toLong())

    override val stemCount get() = sessions.size

    override fun run(input: FloatArray, outputs: Array<FloatArray>) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { x ->
            sessions.forEachIndexed { i, s ->
                s.run(mapOf(s.inputNames.first() to x)).use { r ->
                    (r.get(0) as OnnxTensor).floatBuffer.get(outputs[i])
                }
            }
        }
    }

    override fun close() {
        sessions.forEach { it.close() }
        opts.close()
    }

    companion object {
        /** Mesma ordem de StemId. */
        private val NAMES = listOf("vocals", "drums", "bass", "other").map { "$it.fp16.onnx" }

        /** Copia os modelos do APK para o armazenamento interno (uma vez) e abre. */
        fun open(context: Context): OrtMaskModel {
            val dir = File(context.noBackupFilesDir, "models4").apply { mkdirs() }
            File(context.noBackupFilesDir, "models").deleteRecursively() // modelos da versão 1.0
            for (name in NAMES) {
                val f = File(dir, name)
                val assetLen = context.assets.openFd("models/$name").use { it.length }
                if (!f.exists() || f.length() != assetLen) {
                    val tmp = File(dir, "$name.tmp")
                    context.assets.open("models/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                    tmp.renameTo(f)
                }
            }
            return OrtMaskModel(NAMES.map { File(dir, it).absolutePath })
        }
    }
}
