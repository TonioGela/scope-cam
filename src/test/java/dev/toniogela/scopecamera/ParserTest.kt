package dev.toniogela.scopecamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ParserTest {
    private fun packet(fid: Int, payload: ByteArray, cam: Int = 0, flags: Int = 0): ByteArray {
        val len = payload.size + 7
        return byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 7, len.toByte(), (len shr 8).toByte(),
            fid.toByte(), cam.toByte(), flags.toByte(), 0, 0, 0, 0) + payload
    }

    @Test
    fun reassemblesFramesOnFidChange() {
        val frames = mutableListOf<ByteArray>()
        val p = Supercamera.Parser(onFrame = { frames += it })
        fun feed(b: ByteArray) = p.handle(b, b.size)

        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        feed(packet(1, soi + 1))
        feed(packet(1, byteArrayOf(2), flags = 0x02)) // button bit still counts as image data
        feed(packet(1, byteArrayOf(9), flags = 0x01)) // g-sensor packet: ignored
        feed(packet(1, byteArrayOf(9), cam = 1))      // other lens mid-frame: ignored
        feed(byteArrayOf(0, 0, 7, 1, 0, 1, 0, 0, 0, 0, 0, 0, 9)) // bad magic
        feed(packet(1, eoi))
        feed(packet(2, soi + 3))                       // truncated frame (no EOI): dropped
        feed(packet(3, soi))

        assertEquals(1, frames.size)
        assertArrayEquals(soi + 1 + 2 + eoi, frames[0])
    }

    @Test
    fun buttonFiresOncePerPress() {
        var presses = 0
        val p = Supercamera.Parser({}, { presses++ })
        fun frame(fid: Int, button: Boolean) = packet(fid, byteArrayOf(1), flags = if (button) 0x02 else 0).let { p.handle(it, it.size) }

        frame(1, true); frame(2, true); frame(3, true) // held across three frames: one press
        frame(4, false)
        frame(5, true)                                // second press
        frame(6, false)

        assertEquals(2, presses)
    }
}
