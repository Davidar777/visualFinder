package com.visualfinder.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SpatialActivity : ComponentActivity() {
    private var session: Session? = null
    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private var installRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Spatial Mode: initializing"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(24, 30, 24, 30)
        }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                private var cameraTexture = 0

                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    cameraTexture = ids[0]
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    GLES20.glViewport(0, 0, width, height)
                }

                override fun onDrawFrame(gl: GL10?) {
                    GLES20.glClearColor(0.03f, 0.06f, 0.10f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    val s = session ?: return
                    if (cameraTexture == 0) return
                    try {
                        s.setCameraTextureName(cameraTexture)
                        val frame = s.update()
                        val state = frame.camera.trackingState.name
                        runOnUiThread {
                            status.text = "ARCore tracking: $state\n" +
                                "Stage 1 diagnostic — camera image not rendered yet"
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status.text = "Tracking error: ${e.message}"
                        }
                    }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        layout.addView(status)
        layout.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            status.text = "Camera permission required. Return to Standard Mode."
            return
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    status.text = "ARCore installation requested"
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            if (session == null) {
                session = Session(this).apply {
                    val config = Config(this)
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    configure(config)
                }
            }
            session?.resume()
            surface.onResume()
        } catch (e: Exception) {
            status.text = "ARCore startup error: ${e.message}"
        }
    }

    override fun onPause() {
        if (::surface.isInitialized) surface.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
