package com.kisshkitty.core.kitty

import android.graphics.Bitmap

/**
 * Reusable bitmaps for video-rate image traffic.
 *
 * A bitmap may only be pooled when nothing references it anymore: the
 * parser store and the UI placed-list can share instances, so callers
 * must check ownership (see usages) before releasing. Pooled bitmaps
 * are always fully overwritten (setPixels over w*h) before reuse.
 */
object BitmapPool {
    private const val MAX_BITMAPS = 6
    private const val MAX_BYTES = 192L * 1024L * 1024L

    private val pool = ArrayDeque<Bitmap>()

    @Synchronized
    fun obtain(width: Int, height: Int): Bitmap? {
        val it = pool.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.width == width && b.height == height) {
                it.remove()
                return b
            }
        }
        return null
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        // Only mutable bitmaps can be reused as decode targets.
        // HARDWARE (GPU-resident, immutable) bitmaps are never pooled.
        if (bitmap.config == Bitmap.Config.HARDWARE || !bitmap.isMutable) return
        var bytes = 0L
        for (b in pool) bytes += b.width.toLong() * b.height.toLong() * 4L
        val size = bitmap.width.toLong() * bitmap.height.toLong() * 4L
        if (pool.size < MAX_BITMAPS && bytes + size <= MAX_BYTES) {
            pool.addLast(bitmap)
        }
    }

    @Synchronized
    fun clear() {
        pool.clear()
    }
}
