/*
 * Film (Yousuf-eng-bot) — video export (Phase 2 MVP). GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * Frame-stepped LUT export (docs/VIDEO_PLAN.md, milestone V2): decode each frame
 * (MediaMetadataRetriever), convert to the engine's linear ProPhoto space, apply the
 * exposure gain + baked 33^3 film LUT on the CPU (TrilinearLut — deterministic and
 * JVM-tested), encode sRGB-encoded RGB to H.264 YUV420 via MediaCodec, and mux MP4.
 *
 * The codec input is written through getInputImage()'s planes, honouring each
 * plane's pixel/row strides — that works for both planar (I420) and semi-planar
 * (NV12) layouts every COLOR_FormatYUV420Flexible codec produces.
 *
 * SCOPE (the beta the UI discloses): the LUT carries the film's POINTWISE look
 * (tone + colour, exactly as bakeCubeLut emits it); grain/halation are spatial and
 * are not applied. Audio is not copied — the beta exports a silent clip. HDR clips
 * are refused upstream of this file.
 */
package com.spectrafilm.app.video

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.spectrafilm.app.bitmapToLinearProPhoto
import java.io.File
import java.nio.ByteBuffer

/** Progress snapshot for the export dialog. */
data class VideoExportProgress(val done: Int, val total: Int)

/** Errors the UI can present directly. */
class VideoExportException(message: String) : Exception(message)

internal object VideoExport {

    /** Preview + export decode targets. A 4K source exports at 1080p in this beta. */
    const val EXPORT_LONG_EDGE = 1920
    private const val PREVIEW_LONG_EDGE = 720

    fun exportLongEdge(sourceLongEdge: Int): Int = minOf(sourceLongEdge, EXPORT_LONG_EDGE)
    fun previewLongEdge(sourceLongEdge: Int): Int = minOf(sourceLongEdge, PREVIEW_LONG_EDGE)

    /** Bitrate scaled from resolution + fps; ~12 Mbps at 1080p24. */
    internal fun bitrateFor(w: Int, h: Int, fps: Int): Int {
        val pixelsPerSecond = w.toLong() * h * fps.coerceIn(1, 120)
        val target = (pixelsPerSecond * 12_000_000L / (1920L * 1080 * 24)).toInt()
        return target.coerceIn(2_000_000, 40_000_000)
    }

    /**
     * Run one export. Blocking; call from a worker coroutine. Returns the written
     * file. [isCancelled] is checked between frames; cancellation throws
     * [VideoExportException] after the encoder/muxer are released.
     */
    fun exportToFile(
        clip: VideoClip,
        lut: TrilinearLut,
        exposureGain: Float,
        outFile: File,
        fps: Int,
        isCancelled: () -> Boolean,
        onProgress: (VideoExportProgress) -> Unit,
    ): File {
        val meta = clip.meta
        val longEdge = exportLongEdge(maxOf(meta.width, meta.height))
        val scale = longEdge.toFloat() / maxOf(meta.width, meta.height).coerceAtLeast(1)
        val width = ((meta.width * scale).toInt()).let { it - (it % 2) }.coerceAtLeast(16)
        val height = ((meta.height * scale).toInt()).let { it - (it % 2) }.coerceAtLeast(16)
        val frameCount = maxOf(1, (meta.durationSeconds * fps).toInt())
        val frameIntervalUs = 1_000_000L / fps

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, VideoYuv.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, VideoYuv.COLOR_TRANSFER_SRGB)
            setInteger(MediaFormat.KEY_COLOR_RANGE, VideoYuv.COLOR_RANGE_LIMITED)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        var encoderError: Exception? = null

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val planes = VideoYuv.Planes(width, height)
            val pixels = IntArray(width * height)

            var frameIndex = 0
            var drainEnded = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!drainEnded) {
                if (isCancelled()) throw VideoExportException("cancelled")

                // Feed: decode + colourize the next frame, or signal end-of-stream.
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    if (frameIndex < frameCount) {
                        val us = minOf(frameIndex.toLong() * frameIntervalUs, meta.durationUs - 1).coerceAtLeast(0)
                        val bitmap = clip.frameArgb(us, maxOf(width, height))
                        val image = encoder.getInputImage(inputIndex)
                        if (bitmap != null && image != null) {
                            colorizeFrame(bitmap, width, height, pixels, planes, lut, exposureGain)
                            writePlanesToImage(planes, image)
                            encoder.queueInputBuffer(inputIndex, 0, frameSizeBytes(width, height), frameIndex * frameIntervalUs, 0)
                        } else {
                            // Hole in the decode: push a legal limited-black frame.
                            val buffer = encoder.getInputBuffer(inputIndex)
                            if (buffer == null || image == null) throw VideoExportException("no encoder input")
                            fillBlack(image, width, height)
                            encoder.queueInputBuffer(inputIndex, 0, frameSizeBytes(width, height), frameIndex * frameIntervalUs, 0)
                        }
                        frameIndex++
                        onProgress(VideoExportProgress(done = frameIndex, total = frameCount))
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                // Drain whatever the encoder produced.
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val output = encoder.getOutputBuffer(outputIndex) ?: throw VideoExportException("no output buffer")
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, output, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            drainEnded = true
                        }
                    }
                    // INFO_TRY_AGAIN_LATER: loop again; the EOS handoff above guarantees progress.
                }
            }
        } catch (e: InterruptedException) {
            encoderError = VideoExportException("cancelled")
        } catch (e: VideoExportException) {
            encoderError = e
        } catch (e: Exception) {
            encoderError = VideoExportException(e.message ?: "encoder failed")
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }

        encoderError?.let { throw it }
        if (outFile.length() == 0L) throw VideoExportException("empty output")
        return outFile
    }

    /**
     * One frame: display sRGB Bitmap -> linear ProPhoto (bitmapToLinearProPhoto — the
     * photo decode's exact conversion, so video and photo frames enter the LUT in the
     * same domain) -> gain -> LUT -> sRGB-encoded ARGB -> YUV planes.
     */
    private fun colorizeFrame(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        pixels: IntArray,
        planes: VideoYuv.Planes,
        lut: TrilinearLut,
        gain: Float,
    ) {
        val src = if (bitmap.width != width || bitmap.height != height) {
            // Retriever scaling can be off by a pixel; bilinear resample.
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        try {
            val display = FloatArray(3)
            bitmapToLinearProPhoto(src).use { image ->
                image.acquireDataLease().use { lease ->
                    val f = lease.data.asFloatBuffer()
                    // The LUT output is sRGB-encoded display RGB (bake IoParams default:
                    // outputCctfEncoding=true) — pack it straight into ARGB for the
                    // YUV converter; NO second sRGB encode here.
                    var i = 0
                    for (p in pixels.indices) {
                        lut.apply(f.get(i) * gain, f.get(i + 1) * gain, f.get(i + 2) * gain, display, 0)
                        val ir = (display[0].coerceIn(0f, 1f) * 255f).toInt()
                        val ig = (display[1].coerceIn(0f, 1f) * 255f).toInt()
                        val ib = (display[2].coerceIn(0f, 1f) * 255f).toInt()
                        pixels[p] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
                        i += 3
                    }
                }
            }
        } finally {
            if (src !== bitmap) src.recycle()
        }
        VideoYuv.argbToYuv420(pixels, width, height, planes)
    }

    private fun frameSizeBytes(w: Int, h: Int): Int {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        return w * h + cw * ch * 2
    }

    /** Copy packed planes into the codec Image, honouring its strides and layout. */
    private fun writePlanesToImage(planes: VideoYuv.Planes, image: Image) {
        val imgPlanes = image.planes
        copyInto(imgPlanes[0], planes.y, planes.width, planes.height)
        val uPlane = imgPlanes[1]
        if (uPlane.pixelStride == 2) {
            // NV12: U and V interleave in one plane; plane[2] aliases it at +1.
            interleaveUvInto(uPlane, planes.u, planes.v, planes.cw, planes.ch)
        } else {
            copyInto(uPlane, planes.u, planes.cw, planes.ch)
            if (imgPlanes.size > 2) copyInto(imgPlanes[2], planes.v, planes.cw, planes.ch)
        }
    }

    private fun copyInto(plane: Image.Plane, src: ByteArray, w: Int, h: Int) {
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        buf.position(0)
        if (pixelStride == 1 && rowStride == w) {
            buf.put(src, 0, w * h)
        } else if (pixelStride == 1) {
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.put(src, y * w, w)
            }
        } else {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    buf.position(y * rowStride + x * pixelStride)
                    buf.put(src[y * w + x])
                }
            }
        }
        buf.position(0)
    }

    private fun interleaveUvInto(plane: Image.Plane, u: ByteArray, v: ByteArray, w: Int, h: Int) {
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        buf.position(0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pos = y * rowStride + x * 2
                buf.position(pos)
                buf.put(u[y * w + x])
                buf.put(v[y * w + x])
            }
        }
        buf.position(0)
    }

    private fun fillBlack(image: Image, w: Int, h: Int) {
        val planes = image.planes
        val y = ByteArray(w * h) { 0x10 }
        copyInto(planes[0], y, w, h)
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val neutral = ByteArray(cw * ch) { 0x80 }
        if (planes[1].pixelStride == 2) {
            interleaveUvInto(planes[1], neutral, neutral, cw, ch)
        } else {
            copyInto(planes[1], neutral, cw, ch)
            if (planes.size > 2) copyInto(planes[2], neutral, cw, ch)
        }
    }
}
