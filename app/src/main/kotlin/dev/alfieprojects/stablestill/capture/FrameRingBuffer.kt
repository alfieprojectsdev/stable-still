package dev.alfieprojects.stablestill.capture

import android.media.Image
import dev.alfieprojects.stablestill.core.FrameMeta

/**
 * A captured frame and the metadata that says when it happened.
 *
 * Owns the underlying [Image] and therefore a slot in the ImageReader's pool.
 * Failing to [close] one starves the camera: once the pool is exhausted the HAL
 * simply stops delivering frames, with no error anywhere.
 */
class CapturedFrame(
    val image: Image,
    val meta: FrameMeta,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { image.close() }
        }
    }
}

/**
 * Fixed-capacity buffer of the most recent frames.
 *
 * This is the piece that makes stills stabilisation possible at all. A shutter
 * press is a *reaction* to something that already happened, so the frames worth
 * keeping are the ones captured before the tap. The buffer keeps them warm and
 * throws away everything older.
 */
class FrameRingBuffer(val capacity: Int) {
    init {
        require(capacity >= 2) { "A ring buffer of $capacity frames cannot stack anything" }
    }

    private val lock = Any()
    private val frames = ArrayDeque<CapturedFrame>(capacity)

    val size: Int get() = synchronized(lock) { frames.size }

    /** Adds a frame, closing and discarding the oldest if the buffer is full. */
    fun add(frame: CapturedFrame) {
        val evicted = synchronized(lock) {
            val old = if (frames.size >= capacity) frames.removeFirst() else null
            frames.addLast(frame)
            old
        }
        evicted?.close()
    }

    /**
     * Removes and returns up to [count] frames centred on [aroundNanos],
     * transferring ownership to the caller.
     *
     * "Centred" is deliberate: motion immediately before the press is as useful
     * as motion after it, and the anchor selector wants candidates on both sides.
     */
    fun takeBurst(count: Int, aroundNanos: Long): List<CapturedFrame> = synchronized(lock) {
        if (frames.isEmpty()) return emptyList()
        val ordered = frames.sortedBy { it.meta.sensorTimestampNanos }
        val pivot = ordered.indexOfFirst { it.meta.sensorTimestampNanos >= aroundNanos }
            .let { if (it < 0) ordered.lastIndex else it }

        val half = count / 2
        var start = (pivot - half).coerceAtLeast(0)
        val end = (start + count).coerceAtMost(ordered.size)
        start = (end - count).coerceAtLeast(0)

        val taken = ordered.subList(start, end).toList()
        frames.removeAll(taken.toSet())
        // Re-index so downstream code can address frames 0..n-1 regardless of
        // where they sat in the ring.
        taken.mapIndexed { i, f -> CapturedFrame(f.image, f.meta.copy(index = i)) }
    }

    /** Closes and drops every retained frame. */
    fun clear() {
        val all = synchronized(lock) { frames.toList().also { frames.clear() } }
        all.forEach { it.close() }
    }
}
