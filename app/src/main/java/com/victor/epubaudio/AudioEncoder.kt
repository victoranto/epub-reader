package com.victor.epubaudio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class PcmInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

/** Une varios WAV (los trozos de un capítulo) y los codifica a AAC dentro de un .m4a. */
object AudioEncoder {

    /**
     * Copia el audio PCM de cada WAV a [pcmOut] y devuelve el formato del primero.
     * Se asume que todos los trozos salen del mismo motor de voz con el mismo formato.
     */
    fun concatWavs(wavs: List<File>, pcmOut: File): PcmInfo {
        var info: PcmInfo? = null
        FileOutputStream(pcmOut).use { out ->
            val buf = ByteArray(64 * 1024)
            for (wav in wavs) {
                val (fmt, dataOffset, dataLen) = readWavHeader(wav)
                if (info == null) info = fmt
                FileInputStream(wav).use { input ->
                    input.skip(dataOffset)
                    var remaining = dataLen
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
        }
        return info ?: throw IllegalStateException("No se generó audio")
    }

    private fun readWavHeader(file: File): Triple<PcmInfo, Long, Long> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                throw IllegalStateException("El motor de voz no generó un WAV válido")
            }
            var fmt = PcmInfo(22050, 1, 16)
            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                raf.readFully(idBytes)
                val id = String(idBytes)
                val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xffffffffL
                if (id == "fmt ") {
                    val start = raf.filePointer
                    raf.readShort() // formato de audio (1 = PCM)
                    val channels = java.lang.Short.reverseBytes(raf.readShort()).toInt()
                    val sampleRate = Integer.reverseBytes(raf.readInt())
                    raf.readInt() // byte rate
                    raf.readShort() // block align
                    val bits = java.lang.Short.reverseBytes(raf.readShort()).toInt()
                    fmt = PcmInfo(sampleRate, channels, bits)
                    raf.seek(start + size + (size and 1))
                } else if (id == "data") {
                    val offset = raf.filePointer
                    val available = raf.length() - offset
                    // Algunos motores dejan el tamaño a 0 o mal: en ese caso usamos el resto del archivo.
                    val len = if (size == 0L || size > available) available else size
                    return Triple(fmt, offset, len)
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
            throw IllegalStateException("WAV sin datos de audio")
        }
    }

    /** Codifica PCM 16 bits a AAC-LC en un contenedor MP4 (.m4a). */
    fun encodeToM4a(pcm: File, info: PcmInfo, out: File, bitRate: Int = 64_000) {
        require(info.bitsPerSample == 16) { "Solo se admite PCM de 16 bits" }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val bytesPerFrame = 2 * info.channels
        val bufInfo = MediaCodec.BufferInfo()
        val readBuf = ByteArray(16 * 1024)
        var totalBytes = 0L
        var inputDone = false

        try {
            FileInputStream(pcm).use { input ->
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            inBuf.clear()
                            var max = minOf(inBuf.capacity(), readBuf.size)
                            max -= max % bytesPerFrame
                            val n = input.read(readBuf, 0, max)
                            val ptsUs = totalBytes / bytesPerFrame * 1_000_000L / info.sampleRate
                            if (n <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inBuf.put(readBuf, 0, n)
                                codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                                totalBytes += n
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufInfo.size = 0
                            }
                            if (bufInfo.size > 0 && muxerStarted) {
                                outBuf.position(bufInfo.offset)
                                outBuf.limit(bufInfo.offset + bufInfo.size)
                                muxer.writeSampleData(track, outBuf, bufInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }
}
