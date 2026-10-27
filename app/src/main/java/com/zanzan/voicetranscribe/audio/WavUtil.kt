package com.zanzan.voicetranscribe.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object WavUtil {

    const val SAMPLE_RATE = 16000

    /** 把裸 PCM（16bit 小端单声道）包装成 WAV 文件 */
    fun pcmToWav(pcmFile: File, wavFile: File, sampleRate: Int = SAMPLE_RATE, channels: Int = 1, bits: Int = 16): Boolean {
        return try {
            val dataLen = pcmFile.length()
            RandomAccessFile(wavFile, "rw").use { raf ->
                writeHeader(raf, dataLen, sampleRate, channels, bits)
            }
            FileOutputStream(wavFile, true).use { out ->
                pcmFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeHeader(raf: RandomAccessFile, dataLen: Long, sampleRate: Int, channels: Int, bits: Int) {
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        raf.seek(0)
        raf.writeBytes("RIFF"); writeIntLe(raf, (36 + dataLen).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt "); writeIntLe(raf, 16)
        writeShortLe(raf, 1); writeShortLe(raf, channels)
        writeIntLe(raf, sampleRate); writeIntLe(raf, byteRate)
        writeShortLe(raf, blockAlign); writeShortLe(raf, bits)
        raf.writeBytes("data"); writeIntLe(raf, dataLen.toInt())
    }

    private fun writeIntLe(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
        raf.write((v shr 16) and 0xFF); raf.write((v shr 24) and 0xFF)
    }

    private fun writeShortLe(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
    }

    /** 读取裸 PCM（16bit 小端）为 [-1,1] 浮点数组 */
    fun readPcmSamplesLE(file: File): FloatArray {
        val totalShorts = (file.length() / 2).toInt()
        if (totalShorts <= 0) return FloatArray(0)
        val out = FloatArray(totalShorts)
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(8192)
            var idx = 0
            while (idx < totalShorts) {
                val n = ins.read(buf)
                if (n <= 0) break
                var i = 0
                while (i + 1 < n && idx < totalShorts) {
                    val lo = buf[i].toInt() and 0xFF
                    val hi = buf[i + 1].toInt() and 0xFF
                    out[idx++] = ((hi shl 8) or lo).toShort() / 32768.0f
                    i += 2
                }
            }
        }
        return out
    }
}
