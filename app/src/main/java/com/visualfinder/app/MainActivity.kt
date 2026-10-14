package com.visualfinder.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.util.Size
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: BoxOverlay
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var flash: Button
    private lateinit var exposure: SeekBar
    private var camera: Camera? = null
    private var torchOn = false
    @Volatile private var activeTerm = ""
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status.text = "Camera permission required"
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) status.text = "Reference selected — image matching is not available yet"
    }
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                query.setText(it)
                query.setSelection(query.text.length)
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun cleanTerm(text: String): String = text.trim().lowercase()
        .replace(Regex("^(please\\s+)?(find|locate|look for|search for|where is|where are)\\s+"), "")
        .replace(Regex("^(my|the|a|an)\\s+"), "").trim()

    private fun submitSearch(text: String) {
        activeTerm = cleanTerm(text)
        overlay.update(emptyList(), 1, 1)
        status.text = if (activeTerm.isEmpty()) "Type or speak to search • red = other objects" else "Searching: $activeTerm"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 15, 21)
        window.navigationBarColor = Color.rgb(9, 15, 21)
        val bg = Color.rgb(9, 15, 21)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10), 0, dp(10), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(10), bars.top + dp(4), dp(10), bars.bottom + dp(4))
            insets
        }
        val title = TextView(this).apply {
            text = "Visual Finder  •  Offline"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), 0, dp(4))
        }
        root.addView(title)

        val cameraContainer = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = BoxOverlay()
        cameraContainer.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraContainer.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(cameraContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        cameraContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                camera?.let { cam ->
                    val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                    val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
                    cam.cameraControl.startFocusAndMetering(action)
                }
                true
            } else true
        }

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        query = EditText(this).apply {
            hint = "Find an object..."
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 16f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { submitSearch(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchRow.addView(query, LinearLayout.LayoutParams(0, dp(44), 1f))
        val speak = Button(this).apply {
            text = "🎤"
            contentDescription = "Speak search"
            setOnClickListener {
                try {
                    voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                } catch (_: Exception) { status.text = "Speech recognition unavailable" }
            }
        }
        searchRow.addView(speak, LinearLayout.LayoutParams(dp(52), dp(44)))
        val photo = Button(this).apply {
            text = "▧"
            contentDescription = "Choose reference image"
            setOnClickListener { imagePicker.launch("image/*") }
        }
        searchRow.addView(photo, LinearLayout.LayoutParams(dp(52), dp(44)))
        root.addView(searchRow)

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        flash = Button(this).apply {
            text = "Flash: Off"
            setOnClickListener {
                val cam = camera ?: return@setOnClickListener
                if (!cam.cameraInfo.hasFlashUnit()) { status.text = "Flashlight unavailable"; return@setOnClickListener }
                torchOn = !torchOn
                cam.cameraControl.enableTorch(torchOn)
                text = if (torchOn) "Flash: On" else "Flash: Off"
            }
        }
        tools.addView(flash, LinearLayout.LayoutParams(0, dp(42), 1f))
        val minus = Button(this).apply { text = "−"; setOnClickListener { adjustZoom(-0.25f) } }
        tools.addView(minus, LinearLayout.LayoutParams(dp(48), dp(42)))
        val plus = Button(this).apply { text = "+"; setOnClickListener { adjustZoom(0.25f) } }
        tools.addView(plus, LinearLayout.LayoutParams(dp(48), dp(42)))
        root.addView(tools)

        val exposureRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        exposureRow.addView(TextView(this).apply {
            text = "Exposure"; setTextColor(Color.WHITE); textSize = 13f
        })
        exposure = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    camera?.let { cam ->
                        val range = cam.cameraInfo.exposureState.exposureCompensationRange
                        if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
                            val index = range.lower + ((range.upper - range.lower) * progress / 100f).toInt()
                            cam.cameraControl.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        exposureRow.addView(exposure, LinearLayout.LayoutParams(0, dp(32), 1f))
        root.addView(exposureRow)

        counter = TextView(this).apply {
            text = "Detected: 0  •  Matches: 0"
            setTextColor(Color.rgb(90, 220, 170))
            textSize = 13f
        }
        root.addView(counter)
        status = TextView(this).apply {
            text = "Type or speak to search • red = other objects"
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            setPadding(0, dp(4), 0, dp(5))
        }
        root.addView(status)
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun adjustZoom(delta: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio((zoom.zoomRatio + delta).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    val media = proxy.image
                    if (media == null || !busy.compareAndSet(false, true)) {
                        proxy.close(); return@setAnalyzer
                    }
                    val rotation = proxy.imageInfo.rotationDegrees
                    val input = InputImage.fromMediaImage(media, rotation)
                    detector.process(input)
                        .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { objects ->
                            val term = activeTerm
                            val results = objects.map { obj ->
                                val labels = obj.labels.map { it.text }
                                val match = term.isNotBlank() && labels.any { label ->
                                    label.lowercase().contains(term) || term.contains(label.lowercase())
                                }
                                Detection(RectF(obj.boundingBox), labels.firstOrNull() ?: "Unknown", match)
                            }
                            // ML Kit bounding boxes are in the rotated InputImage coordinate space.
                            val w = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
                            val h = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
                            overlay.update(results, w, h)
                            val matches = results.count { it.matched }
                            counter.text = "Detected: ${results.size}  •  Matches: $matches"
                            status.text = when {
                                term.isBlank() -> "Type an object name • red = detected objects"
                                matches > 0 -> "Possible match: $term"
                                else -> "Searching: $term (limited offline categories)"
                            }
                        }
                        .addOnFailureListener(ContextCompat.getMainExecutor(this)) { status.text = "Detector error: ${it.message}" }
                        .addOnCompleteListener { busy.set(false); proxy.close() }
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
                if (camera?.cameraInfo?.exposureState?.isExposureCompensationSupported != true) exposure.isEnabled = false
            } catch (e: Exception) { status.text = "Camera error: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.close()
        executor.shutdown()
    }

    data class Detection(val rect: RectF, val label: String, val matched: Boolean)

    inner class BoxOverlay : View(this) {
        private var detections: List<Detection> = emptyList()
        private var imgW = 1
        private var imgH = 1
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE }
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(14).toFloat(); style = Paint.Style.FILL }
        fun update(d: List<Detection>, w: Int, h: Int) {
            detections = d
            imgW = maxOf(w, 1)
            imgH = maxOf(h, 1)
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width / imgW.toFloat(), height / imgH.toFloat())
            val dx = (width - imgW * scale) / 2f
            val dy = (height - imgH * scale) / 2f
            for (d in detections) {
                val color = if (d.matched) Color.GREEN else Color.RED
                pen.color = color
                words.color = color
                val r = d.rect
                val box = RectF(dx + r.left * scale, dy + r.top * scale, dx + r.right * scale, dy + r.bottom * scale)
                canvas.drawRect(box, pen)
                canvas.drawText(if (d.matched) "MATCH: ${d.label}" else d.label, box.left, maxOf(words.textSize, box.top - dp(5)), words)
            }
        }
    }
}
