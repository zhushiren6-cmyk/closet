package com.felix.closet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {

    /**
     * Decodes a picked image, applying EXIF rotation, scaled so the short side is ≤1080 and the long side ≤4096.
     * Long order screenshots keep enough resolution for the model to read them.
     */
    fun decodeForImport(ctx: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val s = min(1.0, min(1080.0 / min(w, h), 4096.0 / max(w, h)))
            if (s < 1.0) dec.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun jpegBase64(b: Bitmap, quality: Int = 85): String {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun saveJpeg(b: Bitmap, f: File, quality: Int = 88) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun loadFull(f: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }.getOrNull()

    /** Crops [box] (left, top, right, bottom in [src] pixels) and scales the result to ≤640 px on the long side. */
    fun crop(src: Bitmap, box: Rect): Bitmap {
        val l = box.left.coerceIn(0, src.width - 1)
        val t = box.top.coerceIn(0, src.height - 1)
        val r = box.right.coerceIn(l + 1, src.width)
        val b = box.bottom.coerceIn(t + 1, src.height)
        val c = Bitmap.createBitmap(src, l, t, r - l, b - t)
        val s = min(1.0, 640.0 / max(c.width, c.height))
        if (s >= 1.0) return c
        return Bitmap.createScaledBitmap(c, max(1, (c.width * s).roundToInt()), max(1, (c.height * s).roundToInt()), true)
    }

    // ---------------- thumbnails ----------------

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun invalidate(f: File) {
        val prefix = f.path + "@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    /** Decodes [f] at roughly [target] px on the short side. Cached. */
    fun thumb(f: File, target: Int): Bitmap? {
        val key = "${f.path}@$target@${f.lastModified()}"
        cache.get(key)?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (min(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, b)
        return b
    }
}
