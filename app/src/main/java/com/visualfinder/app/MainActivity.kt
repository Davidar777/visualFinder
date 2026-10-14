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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
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
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification().build()
    )
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else status.text = "Camera permission required" }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) status.text = "Reference image selected. Visual similarity matching is not yet implemented."
    }
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!words.isNullOrEmpty()) query.setText(words.first())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val title = TextView(this).apply { text = "VISUAL FINDER  •  OFFLINE"; setTextColor(Color.WHITE); textSize = 18f; setPadding(18,18,18,18) }
        root.addView(title)
        val cameraContainer = android.widget.FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = BoxOverlay()
        cameraContainer.addView(preview, android.widget.FrameLayout.LayoutParams(-1,-1))
        cameraContainer.addView(overlay, android.widget.FrameLayout.LayoutParams(-1,-1))
        root.addView(cameraContainer, LinearLayout.LayoutParams(-1,0,1f))
        query = EditText(this).apply { hint = "Find e.g. food, shoes, bag"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
        root.addView(query)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val speak = Button(this).apply { text = "Speak"; setOnClickListener { try { voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } catch (_:Exception) { status.text = "Speech recognizer unavailable" } } }
        val photo = Button(this).apply { text = "Reference"; setOnClickListener { imagePicker.launch("image/*") } }
        controls.addView(speak); controls.addView(photo); root.addView(controls)
        status = TextView(this).apply { text = "Looking for visible objects"; setTextColor(Color.WHITE); setPadding(16,8,16,18) }
        root.addView(status)
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || !busy.compareAndSet(false,true)) { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector.process(input).addOnSuccessListener { objects ->
                        val term = query.text.toString().trim().lowercase()
                        val results = objects.mapNotNull { obj ->
                            val labels = obj.labels.map { it.text }
                            // Generic ML Kit categories are limited; this is not arbitrary product matching.
                            if (term.isBlank() || labels.any { label -> label.lowercase().contains(term) || term.contains(label.lowercase()) })
                                Detection(RectF(obj.boundingBox), labels.firstOrNull() ?: "Object") else null
                        }
                        overlay.update(results, input.width, input.height)
                        status.text = if (results.isEmpty()) "No matching category detected" else "${results.size} possible match(es)"
                    }.addOnFailureListener { status.text = "Detector error" }
                        .addOnCompleteListener { busy.set(false); proxy.close() }
                }
                provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,p,analysis)
            } catch (e: Exception) { status.text = "Camera error: ${e.message}" }
        },ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy() { super.onDestroy(); detector.close(); executor.shutdown() }
    data class Detection(val rect:RectF,val label:String)
    inner class BoxOverlay: View(this) {
        private var detections:List<Detection> = emptyList()
        private var imgW=1; private var imgH=1
        private val pen = Paint().apply { color=Color.GREEN; strokeWidth=5f; style=Paint.Style.STROKE }
        private val words = Paint().apply { color=Color.GREEN; textSize=38f; style=Paint.Style.FILL }
        fun update(d:List<Detection>,w:Int,h:Int) { detections=d;imgW=w;imgH=h;invalidate() }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            // FIT_CENTER mapping; InputImage dimensions account for rotation differently across devices.
            // This approximation must be verified on physical device for all rotations.
            val rotated = imgW > imgH && height > width
            val iw = if(rotated) imgH.toFloat() else imgW.toFloat()
            val ih = if(rotated) imgW.toFloat() else imgH.toFloat()
            val scale = minOf(width/iw,height/ih)
            val dx=(width-iw*scale)/2; val dy=(height-ih*scale)/2
            for (d in detections) {
                val r=d.rect
                val box=RectF(dx+r.left*scale,dy+r.top*scale,dx+r.right*scale,dy+r.bottom*scale)
                canvas.drawRect(box,pen);canvas.drawText(d.label,box.left, maxOf(40f,box.top-8f),words)
            }
        }
    }
}
