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
 * Frame access: getScaledFrameAtTimestamp (API 27+, nearest frame) with the classic
 * getFrameAtTime(OPTION_CLOSEST) path below that for API 24-26. Everything returns
 * null instead of throwing; the caller shows a status and keeps working.
 */
package com.spectrafilm.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.spectrafilm.app.VideoTransfer
import com.spectrafilm.app.classifyVideoTransfer

/** One clip's routing-relevant metadata. All values are best-effort. */
data class VideoMeta(
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
class VideoClip private constructor(
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
        val scale = longEdge.toFloat() / maxOf(meta.width, meta.height).coerceAtLeast(1)
        val targetW = (meta.width * scale).toInt().coerceIn(16, meta.width)
        val targetH = (meta.height * scale).toInt().coerceIn(16, meta.height)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTimestamp(us, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH)
        } else {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        }
        return bitmap
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
            val transfer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val format = runCatching { retriever.getTrackFormat(0) }.getOrNull()
                classifyVideoTransfer(format?.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
            } else {
                VideoTransfer.UNKNOWN // pre-28: assume SDR, disclosed in the UI
            }
            // 90/270 clips: the retriever's frames come back upright, so swap.
            val uprightW = if (rotation == 90 || rotation == 270) height else width
            val uprightH = if (rotation == 90 || rotation == 270) width else height
            VideoClip(
                retriever,
                VideoMeta(durationUs, uprightW, uprightH, fps, transfer),
            )
        }.getOrNull()
    }
}
