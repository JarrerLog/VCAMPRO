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
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min

class VideoToFrames : Runnable {
    private var stopDecode = false
    private var outputImageFormat: OutputImageFormat? = null
    private var videoFilePath: Any? = null
    private var childThread: Thread? = null
    private val decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var play_surf: Surface? = null
    private val DEFAULT_TIMEOUT_US: Long = 10000

    fun stopDecode() {
        stopDecode = true
    }

    fun setSaveFrames(imageFormat: OutputImageFormat) {
        outputImageFormat = imageFormat
    }

    fun set_surface(player_surface: Surface) {
        play_surf = player_surface
    }

    fun decode(videoFilePath: Any) {
        this.videoFilePath = videoFilePath
        if (childThread == null) {
            childThread = Thread(this, "decode").apply { start() }
        }
    }

    override fun run() {
        try {
            videoFilePath?.let { videoDecode(it) }
        } catch (t: Throwable) {
            Log.e("vcamsx", "Decode thread error: ${t.message}")
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
            if (trackIndex < 0) return

            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat)
            decoder.configure(mediaFormat, play_surf, null, 0)

            do {
                decodeFramesToImage(decoder, extractor, mediaFormat)
                decoder.stop()
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            } while (!stopDecode)
        } catch (e: Exception) {
            Log.e("vcamsx", "videoDecode error: ${e.message}")
        } finally {
            decoder?.release()
            extractor?.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun decodeFramesToImage(decoder: MediaCodec, extractor: MediaExtractor, mediaFormat: MediaFormat) {
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                val inputId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US)
                if (inputId >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputId)
                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        decoder.queueInputBuffer(inputId, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US)
            if (outputId >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true

                if (info.size != 0) {
                    val image = decoder.getOutputImage(outputId)
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        val scaledBitmap = scaleBitmapToMatchCamera(bitmap)
                        MainHook.data_buffer = bitmapToYUV(scaledBitmap)
                        image.close()
                    }
                    decoder.releaseOutputBuffer(outputId, true)
                }
            }
        }
    }

    private fun scaleBitmapToMatchCamera(src: Bitmap): Bitmap {
        val targetWidth = MainHook.camera_onPreviewFrame?.parameters?.previewSize?.width ?: src.width
        val targetHeight = MainHook.camera_onPreviewFrame?.parameters?.previewSize?.height ?: src.height

        val scale = min(targetWidth / src.width.toFloat(), targetHeight / src.height.toFloat())
        val newWidth = (src.width * scale).toInt()
        val newHeight = (src.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(src, newWidth, newHeight, true)

        // Letterbox: create final bitmap with black background to fill target
        val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        canvas.drawColor(Color.BLACK)
        val left = (targetWidth - newWidth) / 2f
        val top = (targetHeight - newHeight) / 2f
        canvas.drawBitmap(scaled, left, top, null)

        return finalBitmap
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 90, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun bitmapToYUV(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val rgb = argb[j * width + i]
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}
