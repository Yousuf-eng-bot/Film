/*
 * Film (Yousuf-eng-bot) — video source wrapper. GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * MediaMetadataRetriever behind a narrow, closeable surface for the Video Lab:
 * open a picked clip, read its metadata (duration/size/fps/transfer) and pull
 * individual frames as scaled ARGB bitmaps. Frames are returned upright (the
 * retriever applies rotation) — width/height below are the FRAME's, which is what
 * the preview surface and the exporter both need.
 *
 * Deliberately conservative API use: MediaMetadataRetriever.getFrameAtTime +
 * MediaExtractor for track colour-transfer. The newer one-shot scaled-frame
 * retrieval (API 27) and retriever.getTrackFormat (API 28) do not resolve against
 * this project's compileSdk 37 android.jar (removed in the media rewrite), and
 * the older paths work on every minSdk 24+ device anyway.
 *
 * Everything returns null instead of throwing; the caller shows a status and
 * keeps working.
 */
package com.spectrafilm.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.spectrafilm.app.VideoTransfer
import com.spectrafilm.app.classifyVideoTransfer
import com.spectrafilm.app.isHdr

/** One clip's routing-relevant metadata. All values are best-effort. */
internal data class VideoMeta(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val transfer: VideoTransfer,
) {
    val durationSeconds: Float get() = durationUs / 1_000_000f
    /** True when the clip carries an HDR transfer this build cannot simulate yet. */
    val isHdr: Boolean get() = transfer.isHdr()
}

/**
 * One open clip. Not thread-safe; the caller serializes access (the Video Lab
 * decodes on a single IO coroutine).
 */
internal class VideoClip private constructor(
    private val retriever: MediaMetadataRetriever,
    val meta: VideoMeta,
) : AutoCloseable {

    override fun close() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
        }
    }

    /**
     * Nearest frame at [us], scaled so the long edge is about [longEdge] px (never
     * enlarged). Returns null when that timestamp has no frame.
     */
    fun frameArgb(us: Long, longEdge: Int): Bitmap? {
        val source = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val longSource = maxOf(source.width, source.height)
        if (longSource <= longEdge) return source
        val scale = longEdge.toFloat() / longSource
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    companion object {
        private const val FALLBACK_FPS = 24f

        /** Open a local clip; null when unreadable or plainly not a video. */
        fun open(ctx: Context, uri: Uri): VideoClip? = runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, uri)
            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()?.times(1000) ?: return null
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            val fps = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE,
            )?.toFloatOrNull()?.takeIf { it in 1f..240f } ?: FALLBACK_FPS
            val transfer = classifyTrackTransfer(ctx, uri)
            // 90/270 clips: the retriever's frames come back upright, so swap.
            val uprightW = if (rotation == 90 || rotation == 270) height else width
            val uprightH = if (rotation == 90 || rotation == 270) width else height
            VideoClip(
                retriever,
                VideoMeta(durationUs, uprightW, uprightH, fps, transfer),
            )
        }.getOrNull()

        /**
         * Read the video track's colour transfer via MediaExtractor (works on every
         * supported API level, unlike retriever.getTrackFormat which is gone from
         * the compileSdk 37 stubs). UNKNOWN when nothing honest is declared.
         */
        private fun classifyTrackTransfer(ctx: Context, uri: Uri): VideoTransfer {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(ctx, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/")) continue
                    val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                    } else null
                    return classifyVideoTransfer(transfer)
                }
                return VideoTransfer.UNKNOWN
            } catch (_: Exception) {
                return VideoTransfer.UNKNOWN
            } finally {
                extractor.release()
            }
        }
    }
}
