package com.reverseassistant

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Procedural, original low-poly 3D companion. No third-party character assets are bundled. */
class Avatar3DView(context: Context) : GLSurfaceView(context) {
    private val avatarRenderer = AvatarRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(avatarRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setSpeaking(value: Boolean) {
        avatarRenderer.speaking = value
    }

    fun setImportedMesh(data: FloatArray?) {
        queueEvent { avatarRenderer.setImportedMesh(data) }
    }
}

private class AvatarRenderer : GLSurfaceView.Renderer {
    @Volatile var speaking = false
    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var mvpHandle = 0
    private var modelHandle = 0
    private var colorHandle = 0
    private var colorAttributeHandle = 0
    private var useVertexColorHandle = 0
    @Volatile private var pendingImportedData: FloatArray? = null
    private var loadedImportedData: FloatArray? = null
    private var importedVertices: FloatBuffer? = null
    private var importedVertexCount = 0
    private var importedCenterX = 0f
    private var importedCenterY = 0f
    private var importedCenterZ = 0f
    private var importedScale = 1f
    private lateinit var vertices: FloatBuffer
    private lateinit var indices: ShortBuffer
    private var indexCount = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private var angle = 0f
    private var startedAt = System.nanoTime()

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.055f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vertexShader = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            uniform float uUseVertexColor;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying float vUseVertexColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = mat3(uModel) * aNormal;
                vColor = aColor;
                vUseVertexColor = uUseVertexColor;
            }
        """.trimIndent()
        val fragmentShader = """
            precision mediump float;
            uniform vec4 uColor;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying float vUseVertexColor;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 light = normalize(vec3(-0.45, 0.72, 1.0));
                float diffuse = max(dot(n, light), 0.0);
                float shade = 0.38 + 0.62 * diffuse;
                vec3 baseColor = mix(uColor.rgb, vColor, vUseVertexColor);
                gl_FragColor = vec4(baseColor * shade, uColor.a);
            }
        """.trimIndent()
        program = linkProgram(compileShader(GLES20.GL_VERTEX_SHADER, vertexShader), compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        modelHandle = GLES20.glGetUniformLocation(program, "uModel")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        colorAttributeHandle = GLES20.glGetAttribLocation(program, "aColor")
        useVertexColorHandle = GLES20.glGetUniformLocation(program, "uUseVertexColor")
        createSphere()
        startedAt = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 42f, aspect, 0.1f, 30f)
        Matrix.setLookAtM(view, 0, 0f, 0.05f, 6.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, vertices)
        vertices.position(3)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, vertices)
        GLES20.glUniform1f(useVertexColorHandle, 0f)
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
        angle = (sin(elapsed * 0.42) * 5.5).toFloat()
        val blink = if (elapsed % 4.8 > 4.62) 0.035f else 1f
        val mouthHeight = if (speaking) 0.055f + abs(sin(elapsed * 11.0)).toFloat() * 0.15f else 0.045f

        if (!drawImportedModel()) {
        drawPart(0f, -1.25f, 0f, 0.72f, 0.9f, 0.48f, 18f, 0.42f, 0.38f, 0.82f)
        drawPart(0f, -0.57f, 0f, 0.25f, 0.36f, 0.25f, 18f, 0.72f, 0.55f, 0.92f)
        drawPart(0f, 0.35f, 0f, 0.92f, 1.12f, 0.76f, 18f, 0.76f, 0.61f, 0.98f)
        drawPart(-0.33f, 0.53f, 0.69f, 0.145f, 0.16f * blink, 0.09f, 18f, 0.96f, 0.97f, 1.0f)
        drawPart(0.33f, 0.53f, 0.69f, 0.145f, 0.16f * blink, 0.09f, 18f, 0.96f, 0.97f, 1.0f)
        drawPart(-0.33f, 0.52f, 0.77f, 0.065f, 0.085f * blink, 0.045f, 18f, 0.10f, 0.10f, 0.18f)
        drawPart(0.33f, 0.52f, 0.77f, 0.065f, 0.085f * blink, 0.045f, 18f, 0.10f, 0.10f, 0.18f)
        drawPart(0f, 0.08f, 0.735f, 0.22f, mouthHeight, 0.055f, 18f, 0.18f, 0.07f, 0.20f)
        drawPart(0f, 0.25f, 0.72f, 0.065f, 0.12f, 0.065f, 18f, 0.62f, 0.43f, 0.72f)
        }
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    fun setImportedMesh(data: FloatArray?) {
        pendingImportedData = data
    }

    private fun drawImportedModel(): Boolean {
        val requested = pendingImportedData
        if (requested !== loadedImportedData) {
            loadedImportedData = requested
            importedVertices = null
            importedVertexCount = 0
            if (requested != null && requested.size >= 9 && requested.size % 9 == 0) {
                var minX = Float.POSITIVE_INFINITY
                var minY = Float.POSITIVE_INFINITY
                var minZ = Float.POSITIVE_INFINITY
                var maxX = Float.NEGATIVE_INFINITY
                var maxY = Float.NEGATIVE_INFINITY
                var maxZ = Float.NEGATIVE_INFINITY
                var i = 0
                while (i < requested.size) {
                    val x = requested[i]
                    val y = requested[i + 1]
                    val z = requested[i + 2]
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
                    i += 9
                }
                importedCenterX = (minX + maxX) * 0.5f
                importedCenterY = (minY + maxY) * 0.5f
                importedCenterZ = (minZ + maxZ) * 0.5f
                val extent = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(0.001f)
                importedScale = 2.45f / extent
                val bytes = ByteBuffer.allocateDirect(requested.size * 4).order(ByteOrder.nativeOrder())
                importedVertices = bytes.asFloatBuffer().apply { put(requested); position(0) }
                importedVertexCount = requested.size / 9
            }
        }
        val buffer = importedVertices ?: return false
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, -importedCenterX, -importedCenterY, -importedCenterZ)
        Matrix.scaleM(model, 0, importedScale, importedScale, importedScale)
        Matrix.rotateM(model, 0, angle, 0f, 1f, 0f)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4f(colorHandle, 1f, 1f, 1f, 1f)
        GLES20.glUniform1f(useVertexColorHandle, 1f)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 36, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 36, buffer)
        buffer.position(6)
        GLES20.glEnableVertexAttribArray(colorAttributeHandle)
        GLES20.glVertexAttribPointer(colorAttributeHandle, 3, GLES20.GL_FLOAT, false, 36, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, importedVertexCount)
        GLES20.glDisableVertexAttribArray(colorAttributeHandle)
        return true
    }

    private fun drawPart(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        ignoredRotation: Float,
        red: Float, green: Float, blue: Float
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, angle, 0f, 1f, 0f)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4f(colorHandle, red, green, blue, 1f)
        GLES20.glUniform1f(useVertexColorHandle, 0f)
        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
    }

    private fun createSphere() {
        val stacks = 18
        val slices = 24
        val data = ArrayList<Float>()
        for (i in 0..stacks) {
            val phi = Math.PI * i / stacks
            val y = cos(phi).toFloat()
            val ring = sin(phi).toFloat()
            for (j in 0..slices) {
                val theta = 2.0 * Math.PI * j / slices
                val x = ring * cos(theta).toFloat()
                val z = ring * sin(theta).toFloat()
                data.add(x); data.add(y); data.add(z)
                data.add(x); data.add(y); data.add(z)
            }
        }
        val indexData = ArrayList<Short>()
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val first = (i * (slices + 1) + j).toShort()
                val second = (first.toInt() + slices + 1).toShort()
                indexData.add(first); indexData.add(second); indexData.add((first + 1).toShort())
                indexData.add(second); indexData.add((second + 1).toShort()); indexData.add((first + 1).toShort())
            }
        }
        val vertexBytes = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        vertices = vertexBytes.asFloatBuffer().apply { data.forEach(::put); position(0) }
        val indexBytes = ByteBuffer.allocateDirect(indexData.size * 2).order(ByteOrder.nativeOrder())
        indices = indexBytes.asShortBuffer().apply { indexData.forEach(::put); position(0) }
        indexCount = indexData.size
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Avatar shader compilation failed: $message")
        }
        return shader
    }

    private fun linkProgram(vertex: Int, fragment: Int): Int {
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) error("Avatar shader link failed: " + GLES20.glGetProgramInfoLog(result))
        return result
    }
}
