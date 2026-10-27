package com.zanzan.voicetranscribe.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * 麦克风实时流式识别：100ms 一帧喂给流式模型，端点检测断句。
 * 同时把原始 16bit 小端 PCM 落盘，供后续高精度重转。
 */
class StreamingTranscriber(
    private val onPartial: (String) -> Unit,
    private val onSegment: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val pcmFile: File?,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(recognizer: OnlineRecognizer): Boolean {
        if (running) return true
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("无法初始化麦克风缓冲区")
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 4, 8192),
            )
        } catch (e: Exception) {
            onError("麦克风初始化失败: ${e.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("麦克风不可用：请检查录音权限，或麦克风被其他应用占用")
            return false
        }
        running = true
        worker = thread(name = "asr-stream") {
            try {
                record.startRecording()
                run(recognizer, record, sampleRate)
            } catch (t: Throwable) {
                onError(t.message ?: "识别线程异常")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }
        return true
    }

    private fun run(recognizer: OnlineRecognizer, record: AudioRecord, sampleRate: Int) {
        val buffer = ShortArray(1600) // 100ms @16kHz
        var pcmOut: FileOutputStream? = null
        if (pcmFile != null) {
            try {
                pcmFile.parentFile?.mkdirs()
                pcmOut = FileOutputStream(pcmFile)
            } catch (_: Exception) {
                pcmOut = null
            }
        }
        val stream = recognizer.createStream()
        try {
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                if (pcmOut != null) {
                    try {
                        for (i in 0 until n) {
                            val v = buffer[i].toInt()
                            pcmOut.write(v and 0xFF)
                            pcmOut.write((v shr 8) and 0xFF)
                        }
                    } catch (_: Exception) {
                        try { pcmOut.close() } catch (_: Exception) {}
                        pcmOut = null
                    }
                }
                stream.acceptWaveform(samples, sampleRate)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }
                val text = recognizer.getResult(stream).text
                if (text.isNotBlank()) onPartial(text)
                if (recognizer.isEndpoint(stream)) {
                    if (text.isNotBlank()) {
                        onSegment(text)
                        onPartial("")
                    }
                    recognizer.reset(stream)
                }
            }
        } finally {
            stream.release()
            try { pcmOut?.flush(); pcmOut?.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { worker?.join(3000) } catch (_: Exception) {}
        worker = null
    }
}
