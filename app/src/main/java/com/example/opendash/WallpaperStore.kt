package com.example.opendash

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** User-selected static photo, copied to private storage; no gallery-wide permission. */
class WallpaperStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "wallpaper.jpg"))
    private val prefs = context.getSharedPreferences("rota", Context.MODE_PRIVATE)
    val exists: Boolean get() = synchronized(lock) { file.baseFile.exists() }
    fun load(): Bitmap? = synchronized(lock) {
        runCatching { file.openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    fun importPhoto(uri: Uri) = synchronized(lock) {
        val temporary = File.createTempFile("wallpaper-import-", ".image", context.cacheDir)
        var decoded: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 25L * 1024 * 1024) { "Escolha uma imagem de até 25 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Não foi possível abrir esta imagem.")
            // ByteBuffer avoids file-descriptor decoder differences; input is capped at 25 MB above.
            decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(temporary.readBytes()))) { decoder, info, _ ->
                require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 100_000_000) {
                    "A imagem é muito grande. Escolha uma foto menor."
                }
                val scale = min(1.0, 1600.0 / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val output = file.startWrite()
            try {
                check(decoded!!.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Não foi possível salvar a imagem." }
                file.finishWrite(output)
            } catch (error: Exception) { file.failWrite(output); throw error }
            prefs.edit().putBoolean("wallpaper_enabled", true)
                .putLong("wallpaper_revision", prefs.getLong("wallpaper_revision", 0) + 1).commit()
        } finally { decoded?.recycle(); temporary.delete() }
    }
    fun remove() = synchronized(lock) {
        file.delete()
        prefs.edit().putBoolean("wallpaper_enabled", false)
            .putLong("wallpaper_revision", prefs.getLong("wallpaper_revision", 0) + 1).commit()
    }
    companion object { private val lock = Any() }
}

object WallpaperLayout {
    fun shouldShow(hasDestination: Boolean, enabled: Boolean, hasImage: Boolean): Boolean =
        !hasDestination && enabled && hasImage

    fun rectangle(width: Int, height: Int, fill: Boolean, targetWidth: Float = 526f, targetHeight: Float = 300f): RectF {
        require(width > 0 && height > 0)
        val factor = if (fill) max(targetWidth / width, targetHeight / height) else min(targetWidth / width, targetHeight / height)
        val w = width * factor; val h = height * factor
        return RectF((targetWidth-w)/2, (targetHeight-h)/2, (targetWidth+w)/2, (targetHeight+h)/2)
    }
    fun draw(canvas: Canvas, image: Bitmap, fill: Boolean, paint: Paint) {
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(image, null, rectangle(image.width,image.height,fill,canvas.width.toFloat(),canvas.height.toFloat()), paint)
    }
}
