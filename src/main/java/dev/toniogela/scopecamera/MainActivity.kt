package dev.toniogela.scopecamera

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.AspectRatioSurfaceView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var uvcView: AspectRatioSurfaceView
    private lateinit var imageView: ImageView
    private lateinit var status: TextView
    private lateinit var sizeSpinner: Spinner
    private lateinit var lockButton: ImageButton

    private var uvc: ICameraHelper? = null
    private var superCam: Supercamera? = null
    private val decoder = Handler(HandlerThread("jpeg-decode").apply { start() }.looper)
    private val pendingJpeg = AtomicReference<ByteArray?>()
    @Volatile private var lastJpeg: ByteArray? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openSupercamera()
            else showStatus("USB permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(UsbManager::class.java)

        uvcView = AspectRatioSurfaceView(this).apply { visibility = View.GONE }
        imageView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setPadding(48, 0, 48, 0); text = HINT
        }
        sizeSpinner = Spinner(this).apply { setBackgroundColor(0x80000000.toInt()); visibility = View.GONE }
        lockButton = ImageButton(this).apply {
            setBackgroundColor(0x80000000.toInt())
            setOnClickListener { toggleOrientationLock() }
        }
        showLockState(locked = false)
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sizeSpinner)
            addView(lockButton)
            // Keep the buttons clear of the punch-hole and rounded corners even while the bars are hidden.
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, 0)
                insets
            }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(uvcView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        })

        // Immersive fullscreen: draw edge to edge (also under the camera cutout), bars come back on swipe.
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        uvcView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { uvc?.addSurface(holder.surface, false) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { uvc?.removeSurface(holder.surface) }
        })

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
    }

    // Re-hide after anything that brings the bars back (permission dialogs, recents, the notification shade).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.hide(WindowInsets.Type.systemBars())
    }

    private fun toggleOrientationLock() {
        val locked = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED
        requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR
        showLockState(locked)
    }

    private fun showLockState(locked: Boolean) {
        lockButton.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        lockButton.contentDescription = if (locked) "Unlock orientation" else "Lock orientation"
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        decoder.looper.quitSafely()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // Android 9+ denies USB permission for UVC devices unless the app holds CAMERA.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startUvc()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        openSupercamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startUvc()
        else if (superCam == null) showStatus("Camera permission is needed for UVC cameras")
    }

    private fun startUvc() {
        if (uvc == null) uvc = CameraHelper().apply { setStateCallback(uvcCallback) }
    }

    override fun onStop() {
        uvc?.release()
        uvc = null
        superCam?.stop()
        superCam = null
        super.onStop()
    }

    // Launched again by USB_DEVICE_ATTACHED (singleTop): the UVC helper notices by itself, the supercamera doesn't.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSupercamera()
    }

    private fun showStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // --- Supercamera (vendor protocol) ---

    private fun openSupercamera() {
        if (superCam != null) return
        val device = usb.deviceList.values.firstOrNull(Supercamera::isSupported) ?: return
        if (!usb.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags))
            return
        }
        val cam = Supercamera(usb, device)
        try {
            cam.start(
                onFrame = { jpeg ->
                    lastJpeg = jpeg
                    // Decode off the USB thread so reads never stall; frames arriving mid-decode replace the pending one.
                    if (pendingJpeg.getAndSet(jpeg) == null) decoder.post {
                        val next = pendingJpeg.getAndSet(null) ?: return@post
                        // ponytail: new Bitmap per frame; reuse via inBitmap if GC stutters show up
                        BitmapFactory.decodeByteArray(next, 0, next.size)?.let { runOnUiThread { imageView.setImageBitmap(it) } }
                    }
                },
                // The camera's frames are already JPEGs: save the pressed one as is.
                onButton = { lastJpeg?.let { jpeg -> decoder.post { saveJpeg(jpeg) } } },
                onStopped = { _ ->
                    runOnUiThread {
                        cam.stop()
                        if (superCam === cam) {
                            superCam = null
                            imageView.visibility = View.GONE
                            showStatus(HINT) // unplugging also lands here, so an error message would just be noise
                        }
                    }
                },
            )
        } catch (e: IOException) {
            showStatus("Supercamera: ${e.message}")
            return
        }
        superCam = cam
        imageView.visibility = View.VISIBLE
        showStatus(null)
    }

    // --- UVC ---

    private val uvcCallback = object : ICameraHelper.StateCallback {
        // The helper reports every USB device, supercameras included; those aren't UVC and are handled by openSupercamera().
        override fun onAttach(device: UsbDevice) { if (!Supercamera.isSupported(device)) uvc?.selectDevice(device) }
        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) { uvc?.openCamera() }

        override fun onCameraOpen(device: UsbDevice) {
            val h = uvc ?: return
            val sizes = h.supportedSizeList.orEmpty()
                .sortedWith(compareBy<Size>({ it.type != UVCCamera.UVC_VS_FRAME_MJPEG }, { -it.width * it.height }))
            // Raw YUYV saturates USB 2.0 at high resolutions (5 fps on the borescope); start on the biggest MJPEG.
            sizes.firstOrNull()?.takeIf { !same(it, h.previewSize) }?.let(h::setPreviewSize)
            h.startPreview()
            h.previewSize?.let { uvcView.setAspectRatio(it.width, it.height) }
            h.addSurface(uvcView.holder.surface, false)
            // UVC still-image button; state 1 = pressed, 0 = released.
            h.setButtonCallback { _, state -> if (state == 1) runOnUiThread(::takeUvcPhoto) }
            uvcView.visibility = View.VISIBLE
            showStatus(null)
            showSizes(sizes)
        }

        override fun onCameraClose(device: UsbDevice) {
            uvc?.removeSurface(uvcView.holder.surface)
            uvcView.visibility = View.GONE
            sizeSpinner.visibility = View.GONE
            if (superCam == null) showStatus(HINT)
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {}
        override fun onCancel(device: UsbDevice) { showStatus("USB permission denied") }
    }

    private fun showSizes(sizes: List<Size>) {
        sizeSpinner.onItemSelectedListener = null
        sizeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizes.map(::label))
        sizeSpinner.setSelection(sizes.indexOfFirst { same(it, uvc?.previewSize) }.coerceAtLeast(0), false)
        sizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val h = uvc ?: return
                val size = sizes[position]
                if (same(size, h.previewSize)) return
                h.stopPreview()
                h.setPreviewSize(size)
                h.startPreview()
                uvcView.setAspectRatio(size.width, size.height)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sizeSpinner.visibility = View.VISIBLE
    }

    // --- Photos (Pictures/ScopeCam; MediaStore needs no storage permission on Android 10+) ---

    private fun takeUvcPhoto() {
        val h = uvc ?: return
        val options = IImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues()).build()
        h.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(outputFileResults: IImageCapture.OutputFileResults) { runOnUiThread { toast("Photo saved") } }
            override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) { runOnUiThread { toast("Photo failed: $message") } }
        })
    }

    private fun saveJpeg(jpeg: ByteArray) {
        val saved = runCatching {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues())!!
            contentResolver.openOutputStream(uri)!!.use { it.write(jpeg) }
        }
        runOnUiThread { toast(if (saved.isSuccess) "Photo saved" else "Photo failed: ${saved.exceptionOrNull()?.message}") }
    }

    private fun photoValues() = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "ScopeCam_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScopeCam")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun same(a: Size, b: Size?) = b != null && a.type == b.type && a.width == b.width && a.height == b.height

    private fun label(s: Size): String {
        val format = when (s.type) {
            UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
            UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
            else -> "type ${s.type}"
        }
        return "${s.width}×${s.height} $format ${s.fps} fps"
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.toniogela.scopecamera.USB_PERMISSION"
        private const val REQUEST_CAMERA = 1
        private const val HINT = "Plug in a USB camera\n\n" +
            "Works with standard USB (UVC) cameras, borescopes and endoscopes, " +
            "and with “supercamera” USB microscopes and endoscopes."
    }
}
