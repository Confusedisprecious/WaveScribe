package com.zanzan.voicetranscribe.asr

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线（SenseVoice）转写：长音频按固定秒数切块，逐块解码后拼接。
 */
object OfflineTranscriber {

    suspend fun transcribe(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        chunkSeconds: Float = 25f,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext ""
        val chunk = (chunkSeconds * 16000).toInt()
        val chunks: List<FloatArray> = if (samples.size <= chunk) {
            listOf(samples)
        } else {
            val n = (samples.size + chunk - 1) / chunk
            (0 until n).map { i ->
                samples.sliceArray(i * chunk until minOf((i + 1) * chunk, samples.size))
            }
        }
        val sb = StringBuilder()
        chunks.forEachIndexed { i, c ->
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(c, 16000)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            } finally {
                stream.release()
            }
            onProgress(i + 1, chunks.size)
        }
        sb.toString()
    }
}
