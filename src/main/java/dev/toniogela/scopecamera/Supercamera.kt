// Adapted from https://github.com/AfzalivE/UseeEndoscopeCamera (Apache-2.0),
// itself a port of https://github.com/jmz3/EndoscopeCamera.
package dev.toniogela.scopecamera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * "Geek szitman supercamera" endoscopes/microscopes: not UVC, a vendor protocol over bulk endpoints
 * that streams JPEG frames.
 */
class Supercamera(private val usbManager: UsbManager, val device: UsbDevice) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var connection: UsbDeviceConnection? = null

    /**
     * Callbacks run on the USB read thread. [onButton] fires once per press of the camera's shutter button,
     * right after that frame's [onFrame]. [onStopped] gets null on a normal stop.
     */
    fun start(onFrame: (ByteArray) -> Unit, onButton: () -> Unit, onStopped: (String?) -> Unit) {
        val conn = usbManager.openDevice(device) ?: throw IOException("Cannot open USB device")
        try {
            val ifaceA = (0 until device.interfaceCount).map(device::getInterface)
                .firstOrNull { it.id == 0 && it.alternateSetting == 0 } ?: throw IOException("Interface 0 not found")
            val ifaceB = (0 until device.interfaceCount).map(device::getInterface)
                .firstOrNull { it.id == 1 && it.alternateSetting == 1 } ?: throw IOException("Interface 1 alt 1 not found")
            if (!conn.claimInterface(ifaceA, true) || !conn.claimInterface(ifaceB, true)) throw IOException("Claim interface failed")
            if (!conn.setInterface(ifaceB)) throw IOException("Set interface 1 alt 1 failed")

            fun bulk(iface: android.hardware.usb.UsbInterface, dir: Int, num: Int): UsbEndpoint =
                (0 until iface.endpointCount).map(iface::getEndpoint).firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == dir && it.endpointNumber == num
                } ?: throw IOException("Endpoint $num not found")
            val ep1In = bulk(ifaceB, UsbConstants.USB_DIR_IN, 1)
            val ep1Out = bulk(ifaceB, UsbConstants.USB_DIR_OUT, 1)
            val ep2Out = bulk(ifaceA, UsbConstants.USB_DIR_OUT, 2)

            // CLEAR_FEATURE(ENDPOINT_HALT) on EP1 OUT; failure is harmless.
            conn.controlTransfer(0x02, 0x01, 0, ep1Out.address, null, 0, TIMEOUT)
            write(conn, ep2Out, START_SEQUENCE)
            write(conn, ep1Out, START_STREAM)

            connection = conn
            running = true
            thread = Thread({ readLoop(conn, ep1In, Parser(onFrame, onButton), onStopped) }, "supercamera").apply { start() }
        } catch (e: IOException) {
            conn.close()
            throw e
        }
    }

    fun stop() {
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2000)
        thread = null
        connection?.close()
        connection = null
    }

    private fun readLoop(conn: UsbDeviceConnection, ep: UsbEndpoint, parser: Parser, onStopped: (String?) -> Unit) {
        val buf = ByteArray(0x400)
        var failures = 0
        var error: String? = null
        while (running) {
            val n = conn.bulkTransfer(ep, buf, buf.size, TIMEOUT)
            if (n > 0) {
                failures = 0
                parser.handle(buf, n)
            } else if (n < 0 && ++failures >= 5) { // timeouts and errors look the same on Android
                error = "The camera stopped sending data"
                break
            }
        }
        running = false
        onStopped(error)
    }

    private fun write(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray) {
        if (conn.bulkTransfer(ep, data, data.size, TIMEOUT) != data.size) throw IOException("USB write failed")
    }

    /**
     * Reassembles JPEG frames. Packet layout (little-endian):
     * `u16 magic 0xBBAA | u8 cid (7|11) | u16 len | u8 fid | u8 cam | u8 flags | u32 gsensor | jpeg chunk (len-7)`.
     * A frame ends when a packet with a different fid arrives.
     */
    class Parser(private val onFrame: (ByteArray) -> Unit, private val onButton: () -> Unit = {}) {
        private val buffer = ByteArrayOutputStream(128 * 1024)
        private var fid = -1
        private var cam = -1
        private var buttonInFrame = false
        private var buttonWasDown = false

        fun handle(data: ByteArray, size: Int) {
            if (size < 12) return
            val magic = u8(data, 0) or (u8(data, 1) shl 8)
            val cid = u8(data, 2)
            val len = u8(data, 3) or (u8(data, 4) shl 8)
            if (magic != 0xBBAA || (cid != 7 && cid != 11) || len < 7 || 5 + len > size) return
            val partFid = u8(data, 5)
            val partCam = u8(data, 6)
            // flags: bit0 g-sensor, bit1 shutter button, bits2-7 other. Only image packets (button aside) carry JPEG.
            if (partCam >= 2 || (u8(data, 7) and 0x02.inv()) != 0) return

            if (buffer.size() > 0 && partFid != fid) emit()
            if (buffer.size() > 0 && partCam != cam) return
            fid = partFid
            cam = partCam
            if ((u8(data, 7) and 0x02) != 0) buttonInFrame = true
            buffer.write(data, 12, len - 7)
        }

        private fun emit() {
            val jpeg = buffer.toByteArray()
            buffer.reset()
            // A lost chunk still decodes as a half-grey image (flicker), so only pass on complete JPEGs (SOI..EOI).
            val n = jpeg.size
            if (n >= 4 && u8(jpeg, 0) == 0xFF && u8(jpeg, 1) == 0xD8 && u8(jpeg, n - 2) == 0xFF && u8(jpeg, n - 1) == 0xD9) onFrame(jpeg)
            // The button bit stays set while held: fire on the first frame of a press only.
            if (buttonInFrame && !buttonWasDown) onButton()
            buttonWasDown = buttonInFrame
            buttonInFrame = false
        }

        private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF
    }

    companion object {
        private const val TIMEOUT = 1000
        private val START_SEQUENCE = byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10)
        private val START_STREAM = byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 5, 0, 0)
        private val IDS = setOf(0x0329 to 0x2022, 0x2ce3 to 0x3828)

        fun isSupported(d: UsbDevice) = (d.vendorId to d.productId) in IDS
    }
}
