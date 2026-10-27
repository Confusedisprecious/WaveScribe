package com.zanzan.voicetranscribe.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 把任意音频文件（mp3/m4a/aac/wav/ogg/amr 等）解码为 16kHz 单声道 [-1,1] 浮点 PCM。
 */
object AudioFileDecoder {

    class DecodeException(msg: String) : Exception(msg)

    fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: throw DecodeException("无法读取所选文件")
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("读取文件失败: ${e.message}")
        }
    }

    fun decodeTo16kMono(file: File): FloatArray {
        try {
            return decodeWithMediaCodec(file)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("解码失败: ${e.message}")
        }
    }

    private fun decodeWithMediaCodec(file: File): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw DecodeException("文件中没有音频轨道")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val pcmChunks = ArrayList<ShortArray>()
            val floatChunks = ArrayList<FloatArray>()
            var totalShorts = 0L
            var totalFloats = 0L
            var isFloatPcm = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    isFloatPcm = enc == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        if (isFloatPcm) {
                            val fb = outBuf.asFloatBuffer()
                            val n = info.size / 4
                            val arr = FloatArray(n)
                            fb.get(arr)
                            floatChunks.add(arr)
                            totalFloats += n
                        } else {
                            val sb = outBuf.asShortBuffer()
                            val n = info.size / 2
                            val arr = ShortArray(n)
                            sb.get(arr)
                            pcmChunks.add(arr)
                            totalShorts += n
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            val merged: FloatArray = if (isFloatPcm) {
                val m = FloatArray(totalFloats.toInt())
                var p = 0
                for (c in floatChunks) { System.arraycopy(c, 0, m, p, c.size); p += c.size }
                m
            } else {
                val m = FloatArray(totalShorts.toInt())
                var p = 0
                for (c in pcmChunks) { for (s in c) m[p++] = s / 32768.0f }
                m
            }
            val mono = if (channels > 1) downmix(merged, channels) else merged
            return resampleTo16k(mono, sampleRate)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    fun downmix(x: FloatArray, channels: Int): FloatArray {
        val frames = x.size / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += x[i * channels + c]
            out[i] = acc / channels
        }
        return out
    }

    fun resampleTo16k(x: FloatArray, fromRate: Int): FloatArray {
        if (fromRate == 16000 || x.isEmpty()) return x
        val outLen = min((x.size.toLong() * 16000 / fromRate), Int.MAX_VALUE.toLong()).toInt()
        val out = FloatArray(outLen)
        val ratio = fromRate / 16000.0
        val last = x.size - 1
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            if (i0 >= last) {
                out[i] = x[last]
            } else {
                val frac = (pos - i0).toFloat()
                out[i] = x[i0] * (1f - frac) + x[i0 + 1] * frac
            }
        }
        return out
    }
}
