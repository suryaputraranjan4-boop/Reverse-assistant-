package com.reverseassistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveVideoSessionTest {
    @Test
    fun acceptsFrameWhenQueueHasEnoughCapacity() {
        assertTrue(GeminiLiveVideoSession.canQueueFrame(0L, 256 * 1024))
    }

    @Test
    fun rejectsFrameThatExceedsPerFrameLimit() {
        assertFalse(
            GeminiLiveVideoSession.canQueueFrame(
                0L,
                GeminiLiveVideoSession.MAX_VIDEO_FRAME_BYTES + 1
            )
        )
    }

    @Test
    fun rejectsFrameWhenEncodedPayloadWouldExceedQueueLimit() {
        val jpegBytes = 512 * 1024
        val encodedBytes = ((jpegBytes.toLong() + 2L) / 3L) * 4L + 256L
        val lastAllowedQueueSize = GeminiLiveVideoSession.MAX_PENDING_VIDEO_BYTES - encodedBytes

        assertTrue(GeminiLiveVideoSession.canQueueFrame(lastAllowedQueueSize, jpegBytes))
        assertFalse(GeminiLiveVideoSession.canQueueFrame(lastAllowedQueueSize + 1L, jpegBytes))
    }

    @Test
    fun rejectsInvalidQueueAndFrameSizes() {
        assertFalse(GeminiLiveVideoSession.canQueueFrame(-1L, 100))
        assertFalse(GeminiLiveVideoSession.canQueueFrame(0L, 0))
        assertFalse(GeminiLiveVideoSession.canQueueFrame(0L, -1))
        assertFalse(
            GeminiLiveVideoSession.canQueueFrame(
                GeminiLiveVideoSession.MAX_PENDING_VIDEO_BYTES + 1L,
                1
            )
        )
    }
}
