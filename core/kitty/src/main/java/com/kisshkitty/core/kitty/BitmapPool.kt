package com.kisshkitty.core.kitty

import android.graphics.Bitmap

/**
 * Reusable bitmaps for video-rate image traffic.
 *
 * A bitmap may only be pooled when nothing references it anymore: the
 * parser store and the UI placed-list can share instances, so callers
 * must check ownership (see usages) before releasing. Pooled bitmaps
 * are always fully overwritten (setPixels over w*h) before reuse.
 *
 * Displaced bitmaps go through [retire] first: the RenderThread may
 * still be drawing the frame that was just replaced, and overwriting its
 * pixels would tear. They only become reusable after a short grace period.
 */
object BitmapPool {
    private const val MAX_BITMAPS = 3
    private const val MAX_BYTES = 64L * 1024L * 1024L
    private const val MAX_RETIRED = 6
    private const val RETIRE_NANOS = 120_000_000L

    private class Retired(val bitmap: Bitmap, val at: Long)

    private val pool = ArrayDeque<Bitmap>()
    private val retired = ArrayDeque<Retired>()

    @Synchronized
    fun obtain(width: Int, height: Int): Bitmap? {
        drainRetired()
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

    /** Queue a no-longer-referenced bitmap for reuse after a grace period. */
    @Synchronized
    fun retire(bitmap: Bitmap) {
        drainRetired()
        retired.addLast(Retired(bitmap, System.nanoTime()))
        // Never let the queue pin memory: oldest simply drops to the GC.
        while (retired.size > MAX_RETIRED) retired.removeFirst()
    }

    @Synchronized
    private fun drainRetired() {
        val now = System.nanoTime()
        while (retired.isNotEmpty() && now - retired.first().at > RETIRE_NANOS) {
            release(retired.removeFirst().bitmap)
        }
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
        retired.clear()
    }
}
