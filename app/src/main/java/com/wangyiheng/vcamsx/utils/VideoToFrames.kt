package com.wangyiheng.vcamsx.utils

import android.content.ContentValues
import android.graphics.*
import android.media.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.wangyiheng.vcamsx.MainHook
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class VideoToFrames : Runnable {

    private var stopDecode = false
    private var outputImageFormat: OutputImageFormat? = null
    private var videoFilePath: Any? = null
    private var childThread: Thread? = null
    private var throwable: Throwable? = null
    private val decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var play_surf: Surface? = null
    private val DEFAULT_TIMEOUT_US: Long = 10000
    private val callback: Callback? = null
    private val mQueue: LinkedBlockingQueue<ByteArray>? = null

    fun stopDecode() {
        stopDecode = true
    }

    interface Callback {
        fun onFinishDecode()
        fun onDecodeFrame(index: Int)
    }

    @Throws(IOException::class)
    fun setSaveFrames(imageFormat: OutputImageFormat) {
        outputImageFormat = imageFormat
    }

    fun set_surface(player_surface: Surface?) {
        play_surf = player_surface
    }

    fun decode(videoFilePath: Any) {
        this.videoFilePath = videoFilePath
        if (childThread == null) {
            childThread = Thread(this, "decode").apply { start() }
            throwable?.let { throw it }
        }
    }

    override fun run() {
        try {
            Log.d("vcamsxtoast", "------开始解码------")
            videoFilePath?.let { videoDecode(it) }
        } catch (t: Throwable) {
            throwable = t
        }
    }

    private fun videoDecode(videoPath: Any) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                when (videoPath) {
                    is String -> setDataSource(videoPath)
                    is Uri -> MainHook.context?.let { setDataSource(it, videoPath, null) }
                    else -> throw IllegalArgumentException("Unsupported video path type")
                }
            }
            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                XposedBridge.log("No video track found in $videoFilePath")
                return
            }
            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return
            decoder = MediaCodec.createDecoderByType(mime)
            showSupportedColorFormat(decoder.codecInfo.getCapabilitiesForType(mime))
            if (isColorFormatSupported(decodeColorFormat, decoder.codecInfo.getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat)
                XposedBridge.log("set decode color format to type $decodeColorFormat")
            } else {
                Log.i(ContentValues.TAG, "Color format type $decodeColorFormat not supported")
            }
            decodeFramesToImage(decoder, extractor, mediaFormat)
            decoder.stop()
            while (!stopDecode) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decodeFramesToImage(decoder, extractor, mediaFormat)
                decoder.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            decoder?.stop()
            decoder?.release()
            extractor?.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun showSupportedColorFormat(caps: MediaCodecInfo.CodecCapabilities) {
        Log.d("vcamsx", "Supported color formats: ${caps.colorFormats.joinToString()}")
    }

    fun isColorFormatSupported(colorFormat: Int, caps: MediaCodecInfo.CodecCapabilities): Boolean {
        return colorFormat in caps.colorFormats
    }

    private fun decodeFramesToImage(decoder: MediaCodec, extractor: MediaExtractor, mediaFormat: MediaFormat) {
        decoder.configure(mediaFormat, play_surf, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var outputFrameCount = 0
        var startWhen: Long = 0
        var isFirst = false

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                val inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US)
                if (inputBufferId >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferId)
                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US)
            if (outputBufferId >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                if (info.size != 0) {
                    outputFrameCount++
                    callback?.onDecodeFrame(outputFrameCount)
                    if (!isFirst) {
                        startWhen = System.currentTimeMillis()
                        isFirst = true
                    }

                    if (play_surf == null) {
                        val image = decoder.getOutputImage(outputBufferId)
                        image?.let {
                            logImageFormat(it)
                            if (outputImageFormat != null) {
                                MainHook.data_buffer = getDataFromImage(it)
                            }
                            it.close()
                        }
                    }

                    val sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen)
                    if (sleepTime > 0) Thread.sleep(sleepTime)
                    decoder.releaseOutputBuffer(outputBufferId, true)
                }
            }
        }
        callback?.onFinishDecode()
    }

    fun logImageFormat(image: Image) {
        val formatString = when (image.format) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.JPEG -> "JPEG"
            else -> "Unknown: ${image.format}"
        }
        Log.d("vcamsx", "Image format is $formatString")
    }

    private fun getDataFromImage(image: Image): ByteArray {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes
        val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(image.format) / 8)
        var offset = 0

        planes.forEachIndexed { i, plane ->
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val shift = if (i == 0) 0 else 1
            val w = width shr shift
            val h = height shr shift
            val rowData = ByteArray(rowStride)

            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
            for (row in 0 until h) {
                val length = if (pixelStride == 1) w else (w - 1) * pixelStride + 1
                buffer.get(rowData, 0, length)
                for (col in 0 until w) {
                    data[offset++] = rowData[col * pixelStride]
                }
                if (row < h - 1) buffer.position(buffer.position() + rowStride - length)
            }
        }
        return data
    }

    private fun isImageFormatSupported(image: Image) =
        image.format in listOf(ImageFormat.YUV_420_888, ImageFormat.NV21, ImageFormat.YV12)
}

enum class OutputImageFormat(val friendlyName: String) {
    I420("I420"), NV21("NV21"), JPEG("JPEG");
    override fun toString() = friendlyName
}
