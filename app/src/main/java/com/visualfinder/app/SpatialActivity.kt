package com.visualfinder.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// SpatialCameraRendererV2
class SpatialActivity : ComponentActivity() {
    private val sessionLock = Any()
    private var session: Session? = null
    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private var installRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Spatial Mode: initializing"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(20, 20, 20, 20)
        }

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(CameraRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        layout.addView(status)
        layout.addView(
            surface,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(layout)
    }

    private inner class CameraRenderer : GLSurfaceView.Renderer {
        private var textureId = 0
        private var program = 0
        private var width = 1
        private var height = 1
        private var lastStatus = 0L

        private val quad = floatBuffer(floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        ))

        private val uv = floatBuffer(FloatArray(8))

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        private fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) {
                "Shader error: ${GLES20.glGetShaderInfoLog(id)}"
            }
            return id
        }

        override fun onSurfaceCreated(
            gl: GL10?,
            config: EGLConfig?
        ) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                textureId
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )

            val vertex = shader(
                GLES20.GL_VERTEX_SHADER,
                """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = aTexCoord;
                }
                """.trimIndent()
            )

            val fragment = shader(
                GLES20.GL_FRAGMENT_SHADER,
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uCamera;
                void main() {
                    gl_FragColor = texture2D(uCamera, vTexCoord);
                }
                """.trimIndent()
            )

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)

            val linked = IntArray(1)
            GLES20.glGetProgramiv(
                program, GLES20.GL_LINK_STATUS, linked, 0
            )
            check(linked[0] != 0) {
                "Program error: ${GLES20.glGetProgramInfoLog(program)}"
            }

            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }

        override fun onSurfaceChanged(
            gl: GL10?,
            w: Int,
            h: Int
        ) {
            width = w
            height = h
            GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            try {
                synchronized(sessionLock) {
                    val s = session ?: return
                    if (textureId == 0 || program == 0) return

                    val rotation = when (
                        this@SpatialActivity.display?.rotation
                    ) {
                        Surface.ROTATION_90 -> 1
                        Surface.ROTATION_180 -> 2
                        Surface.ROTATION_270 -> 3
                        else -> 0
                    }

                    s.setDisplayGeometry(rotation, width, height)
                    s.setCameraTextureName(textureId)

                    val frame = s.update()

                    quad.position(0)
                    uv.position(0)

                    frame.transformCoordinates2d(
                        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                        quad,
                        Coordinates2d.TEXTURE_NORMALIZED,
                        uv
                    )

                    GLES20.glUseProgram(program)

                    val positionLoc = GLES20.glGetAttribLocation(
                        program, "aPosition"
                    )
                    val uvLoc = GLES20.glGetAttribLocation(
                        program, "aTexCoord"
                    )

                    quad.position(0)
                    uv.position(0)

                    GLES20.glEnableVertexAttribArray(positionLoc)
                    GLES20.glVertexAttribPointer(
                        positionLoc, 2, GLES20.GL_FLOAT,
                        false, 0, quad
                    )

                    GLES20.glEnableVertexAttribArray(uvLoc)
                    GLES20.glVertexAttribPointer(
                        uvLoc, 2, GLES20.GL_FLOAT,
                        false, 0, uv
                    )

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(
                        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        textureId
                    )
                    GLES20.glUniform1i(
                        GLES20.glGetUniformLocation(program, "uCamera"),
                        0
                    )

                    GLES20.glDrawArrays(
                        GLES20.GL_TRIANGLE_STRIP, 0, 4
                    )

                    GLES20.glDisableVertexAttribArray(positionLoc)
                    GLES20.glDisableVertexAttribArray(uvLoc)

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStatus > 700L) {
                        lastStatus = now
                        val state = frame.camera.trackingState.name
                        runOnUiThread {
                            status.text = "Spatial Mode | ARCore: $state"
                        }
                    }
                }
            } catch (e: Exception) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastStatus > 1000L) {
                    lastStatus = now
                    runOnUiThread {
                        status.text =
                            "Spatial camera error: ${e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "Camera permission required"
            return
        }

        try {
            when (
                ArCoreApk.getInstance().requestInstall(
                    this, !installRequested
                )
            ) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    status.text = "ARCore installation requested"
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            synchronized(sessionLock) {
                if (session == null) {
                    session = Session(this).apply {
                        val configuration = Config(this)
                        configuration.updateMode =
                            Config.UpdateMode.LATEST_CAMERA_IMAGE
                        configure(configuration)
                    }
                }
                session?.resume()
            }

            surface.onResume()
        } catch (e: Exception) {
            status.text =
                "ARCore startup error: ${e.javaClass.simpleName}"
        }
    }

    override fun onPause() {
        if (::surface.isInitialized) surface.onPause()
        synchronized(sessionLock) {
            session?.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        synchronized(sessionLock) {
            session?.close()
            session = null
        }
        super.onDestroy()
    }
}
