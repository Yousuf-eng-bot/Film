/*
 * Film (Yousuf-eng-bot) — Video Lab (beta). GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * Phase 2's first milestone (docs/VIDEO_PLAN.md): pick a video, apply a film stock +
 * print paper, preview, export a silent MP4.
 *
 * PREVIEW IS CPU-RENDERED, deliberately. The first beta drew frames through a GLES 3.0
 * 3D-LUT shader (mirroring the editor's experimental GPU preview); on real devices the
 * viewfinder stayed black — the shader path the editor ships is itself marked
 * "not yet verified on a real GPU". A phone's video preview must never depend on an
 * unverified driver path, so both preview and export now run the SAME CPU colour path
 * (VideoExport.colorizedArgb: photo-decode conversion -> gain -> 33^3 trilinear LUT).
 * What the user scrubs is pixel-for-pixel what the export writes, on every device.
 *
 * Reversal (slide) films scan directly: the params carry io.scanFilm=true, exactly as
 * the photo editor's Slide mode — otherwise the bake would print the slide onto
 * negative paper and the LUT would come out inverted.
 *
 * Beta limits, disclosed in the UI: pointwise look only (no grain/halation on video),
 * silent export, SDR clips only, preview decodes frame-by-frame at decode speed.
 */
package com.spectrafilm.app.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spectrafilm.app.DropdownGroup
import com.spectrafilm.app.EngineHolder
import com.spectrafilm.app.GroupedDropdown
import com.spectrafilm.app.MaterialSymbols
import com.spectrafilm.app.R
import com.spectrafilm.app.SpektraBrand
import com.spectrafilm.app.SpektraTheme
import com.spectrafilm.app.StockCatalog
import com.spectrafilm.app.toGroups
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoLabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpektraTheme(dark = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VideoLabScreen()
                }
            }
        }
    }
}

@Composable
private fun VideoLabScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var clip by remember { mutableStateOf<VideoClip?>(null) }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var profiles by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        profiles = withContext(Dispatchers.IO) {
            runCatching { EngineHolder.get(ctx.applicationContext).listProfiles() }.getOrNull()
        }
    }
    val filmGroups: List<DropdownGroup> = remember(profiles) {
        val p = profiles ?: return@remember emptyList()
        StockCatalog.optionsFor(ctx, p, forFilm = true).toGroups()
    }
    val printGroups: List<DropdownGroup> = remember(profiles) {
        val p = profiles ?: return@remember emptyList()
        StockCatalog.optionsFor(ctx, p, forFilm = false).toGroups()
    }
    var filmId by remember { mutableStateOf("") }
    var paperId by remember { mutableStateOf("") }
    // Seed with the catalog's first film/paper once profiles load.
    LaunchedEffect(filmGroups, printGroups) {
        if (filmId.isBlank()) filmId = filmGroups.firstOrNull()?.options?.firstOrNull()?.id ?: ""
        if (paperId.isBlank()) paperId = printGroups.firstOrNull()?.options?.firstOrNull()?.id ?: ""
    }

    var previewLut by remember { mutableStateOf<TrilinearLut?>(null) }
    var gain by remember { mutableFloatStateOf(1f) }
    var baking by remember { mutableStateOf(false) }
    var bakeNote by remember { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var playheadUs by remember { mutableLongStateOf(0L) }
    var previewFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameBusy by remember { mutableStateOf(false) }

    var exportBusy by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<VideoExportProgress?>(null) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val exportCancelled = remember { AtomicBoolean(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        clip?.close()
        clip = null
        meta = null
        playheadUs = 0L
        isPlaying = false
        previewFrame = null
        val opened = VideoClip.open(ctx.applicationContext, uri)
        if (opened == null) {
            error = ctx.getString(R.string.video_lab_open_failed)
        } else if (opened.meta.isHdr) {
            opened.close()
            error = ctx.getString(R.string.video_lab_hdr_unsupported)
        } else {
            error = null
            clip = opened
            meta = opened.meta
        }
    }

    // The look's engine params: a reversal (slide) film scans directly — no print stage —
    // matching the editor's Slide mode. Without this a slide film's LUT bakes through the
    // print stage and comes out inverted.
    fun lookParams(film: String, paper: String): SpektraParams = SpektraParams(
        filmProfile = film,
        printProfile = paper,
        io = IoParams(scanFilm = StockCatalog.isReversalFilm(ctx, film)),
    )

    // Bake the preview LUT whenever the look changes (film/paper/clip). SHAPER_SRGB is
    // not used any more (no shader): one LINEAR-lattice bake serves preview and export.
    LaunchedEffect(clip, filmId, paperId) {
        val c = clip ?: return@LaunchedEffect
        if (filmId.isBlank() || paperId.isBlank()) return@LaunchedEffect
        baking = true
        bakeNote = null
        previewFrame = null
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val engine = EngineHolder.get(ctx.applicationContext)
                val params = lookParams(filmId, paperId)
                val bake = engine.bakeCubeLut(params, 33, SpektraEngine.SHAPER_NONE, null)
                val parsed = TrilinearLut.parse(bake.text)
                    ?: throw VideoExportException("LUT parse failed")
                // Meter ONE representative frame (the midpoint): one static gain for
                // the whole clip avoids flicker between frames.
                val probeLong = VideoExport.previewLongEdge(maxOf(c.meta.width, c.meta.height))
                val bitmap = c.frameArgb(c.meta.durationUs / 2, probeLong)
                    ?: c.frameArgb(0L, probeLong)
                val g = if (bitmap != null) {
                    val v = meterGain(bitmap, engine, params)
                    bitmap.recycle()
                    v
                } else 1f
                parsed to g
            }
        }
        baking = false
        result.onSuccess { (lut, g) ->
            previewLut = lut
            gain = if (g.isFinite() && g > 0f) g else 1f
        }.onFailure {
            bakeNote = it.message
        }
    }

    // Playback loop: decode + render + publish frames while playing, paced to real time.
    LaunchedEffect(clip, isPlaying, previewLut) {
        val c = clip
        val lut = previewLut
        if (c == null || lut == null || !isPlaying) return@LaunchedEffect
        val edge = VideoExport.previewLongEdge(maxOf(c.meta.width, c.meta.height))
        withContext(Dispatchers.IO) {
            val fps = c.meta.fps.coerceIn(1f, 120f)
            val frameInterval = (1_000_000f / fps).toLong()
            val startUs = playheadUs
            val startWall = System.nanoTime()
            var index = (startUs / frameInterval).toInt()
            val totalFrames = (c.meta.durationSeconds * fps).toInt()
            while (coroutineContext.isActive && isPlaying && index < totalFrames) {
                val targetUs = (startUs + index * frameInterval).coerceAtMost(c.meta.durationUs - 1)
                frameBusy = true
                val frame = renderPreviewFrame(c, targetUs, lut, gain, edge)
                if (frame != null) previewFrame = frame
                frameBusy = false
                index++
                playheadUs = targetUs
                // Pace to real time minus what the render already took.
                val targetWall = startWall + index * frameInterval * 1000L
                val now = System.nanoTime()
                if (targetWall - now > 0) Thread.sleep((targetWall - now) / 1_000_000L)
            }
        }
        if (isPlaying) {
            playheadUs = (c.meta.durationUs - 1).coerceAtLeast(0)
            isPlaying = false
        }
    }

    // Scrub / first frame: while paused, render exactly the playhead frame.
    LaunchedEffect(clip, previewLut, playheadUs, isPlaying) {
        val c = clip
        val lut = previewLut
        if (c == null || lut == null || isPlaying) return@LaunchedEffect
        val edge = VideoExport.previewLongEdge(maxOf(c.meta.width, c.meta.height))
        withContext(Dispatchers.IO) {
            frameBusy = true
            val frame = renderPreviewFrame(c, playheadUs, lut, gain, edge)
            if (frame != null) previewFrame = frame
            frameBusy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.video_lab_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.video_lab_beta_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val m = meta
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(SpektraBrand.canvasNearBlack) // fixed neutral: the frame is judged on it,
            contentAlignment = Alignment.Center,
        ) {
            val frame = previewFrame
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = stringResource(R.string.video_lab_preview_cd),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    stringResource(if (m == null) R.string.video_lab_no_video else R.string.video_lab_baking),
                    color = SpektraBrand.onCanvas.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (frameBusy && frame != null) {
                CircularProgressIndicator(Modifier.height(22.dp))
            }
        }
        if (m != null) {
            Text(
                "${m.width}×${m.height} · %.1fs · %.0f fps".format(m.durationSeconds, m.fps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { isPlaying = !isPlaying }, enabled = previewLut != null) {
                    Icon(
                        if (isPlaying) MaterialSymbols.Pause else MaterialSymbols.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.video_lab_pause else R.string.video_lab_play,
                        ),
                    )
                }
                Slider(
                    value = playheadUs.toFloat().coerceIn(0f, (m.durationUs - 1).toFloat().coerceAtLeast(1f)),
                    onValueChange = { isPlaying = false; playheadUs = it.toLong() },
                    valueRange = 0f..(m.durationUs - 1).toFloat().coerceAtLeast(1f),
                    enabled = previewLut != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        GroupedDropdown(
            label = stringResource(R.string.video_lab_film),
            selectedId = filmId,
            groups = filmGroups,
            onSelect = { filmId = it; isPlaying = false },
        )
        GroupedDropdown(
            label = stringResource(R.string.video_lab_paper),
            selectedId = paperId,
            groups = printGroups,
            onSelect = { paperId = it; isPlaying = false },
        )

        if (baking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text(stringResource(R.string.video_lab_baking), style = MaterialTheme.typography.bodySmall)
            }
        }
        bakeNote?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }) {
                Text(stringResource(if (clip == null) R.string.video_lab_pick else R.string.video_lab_pick_another))
            }
            OutlinedButton(
                enabled = clip != null && previewLut != null && !baking && !exportBusy && m?.isHdr != true,
                onClick = {
                    val c = clip ?: return@OutlinedButton
                    exportBusy = true
                    exportCancelled.set(false)
                    exportProgress = null
                    exportResult = null
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            runExport(ctx.applicationContext, c, filmId, paperId, gain, exportCancelled) {
                                exportProgress = it
                            }
                        }.onSuccess { exportResult = it }
                            .onFailure {
                                exportResult = ctx.getString(
                                    R.string.video_lab_export_failed,
                                    it.message ?: "",
                                )
                            }
                        exportBusy = false
                    }
                },
            ) {
                Text(stringResource(R.string.video_lab_export))
            }
        }

        exportProgress?.let { p ->
            Column {
                LinearProgressIndicator(
                    progress = { if (p.total <= 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.video_lab_export_progress, p.done, p.total),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        exportResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Decode + colourize one frame for the preview (same path the export writes). */
private fun renderPreviewFrame(
    clip: VideoClip,
    us: Long,
    lut: TrilinearLut,
    gain: Float,
    previewLong: Int,
): ImageBitmap? {
    val bitmap = clip.frameArgb(us, previewLong) ?: return null
    try {
        val pixels = VideoExport.colorizedArgb(bitmap, lut, gain, bitmap.width, bitmap.height)
        return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    } finally {
        bitmap.recycle()
    }
}

/** Engine metering on one display-referred frame; returns the linear gain (2^ev). */
private fun meterGain(bitmap: Bitmap, engine: SpektraEngine, params: SpektraParams): Float =
    com.spectrafilm.app.bitmapToLinearProPhoto(bitmap).use { image ->
        engine.exposureGain(image, params, null).toFloat()
    }

/**
 * Bake the export LUT (SHAPER_NONE: a linear lattice for the CPU trilinear path — the
 * preview uses the SAME bake now, so look params only affect it once), render
 * frame-by-frame, publish to the gallery, and return the status message.
 */
private suspend fun runExport(
    appCtx: Context,
    clip: VideoClip,
    filmId: String,
    paperId: String,
    gain: Float,
    cancelled: AtomicBoolean,
    onProgress: (VideoExportProgress) -> Unit,
): String = withContext(Dispatchers.Default) {
    val engine = EngineHolder.get(appCtx)
    val params = SpektraParams(
        filmProfile = filmId,
        printProfile = paperId,
        io = IoParams(scanFilm = StockCatalog.isReversalFilm(appCtx, filmId)),
    )
    val bake = engine.bakeCubeLut(params, 33, SpektraEngine.SHAPER_NONE, null)
    val exportLut = TrilinearLut.parse(bake.text) ?: throw VideoExportException("LUT parse failed")

    val tempFile = File(appCtx.cacheDir, "film_video_export_${System.currentTimeMillis()}.mp4")
    val fps = clip.meta.fps.coerceIn(1f, 60f).toInt()
    VideoExport.exportToFile(
        clip = clip,
        lut = exportLut,
        exposureGain = gain,
        outFile = tempFile,
        fps = fps,
        isCancelled = { cancelled.get() },
        onProgress = onProgress,
    )
    val message = publishToGallery(appCtx, tempFile, clip.meta)
    tempFile.delete()
    message
}

/** Copy the finished MP4 into Pictures/Film (MediaStore, API 29+; app files below). */
private fun publishToGallery(appCtx: Context, file: File, meta: VideoMeta): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Film_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/Film")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.WIDTH, meta.width)
            put(MediaStore.Video.Media.HEIGHT, meta.height)
        }
        val resolver = appCtx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw VideoExportException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw VideoExportException("MediaStore stream failed")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return appCtx.getString(R.string.video_lab_export_done_q)
    }
    // Pre-29: app-specific Movies dir (no permission; the status discloses the path).
    val dir = appCtx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: appCtx.cacheDir
    val out = File(dir, "Film_${System.currentTimeMillis()}.mp4")
    file.copyTo(out, overwrite = true)
    return appCtx.getString(R.string.video_lab_export_done_legacy, out.absolutePath)
}
