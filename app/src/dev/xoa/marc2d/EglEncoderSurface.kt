package dev.xoa.marc2d

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An OpenGL context that draws into the encoder. It has to be used always from the thread that creates it. */
internal class EglEncoderSurface(
	surface: Surface,
) {
	private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
	private val context: android.opengl.EGLContext
	private val eglSurface: android.opengl.EGLSurface
	private val rotation = FloatArray(16)
	private val quad =
		ByteBuffer
			.allocateDirect(QUAD.size * Float.SIZE_BYTES)
			.order(ByteOrder.nativeOrder())
			.asFloatBuffer()
			.apply {
				put(QUAD)
				position(0)
			}

	init {
		val version = IntArray(2)
		check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not start" }
		val configs = arrayOfNulls<EGLConfig>(1)
		val count = IntArray(1)
		check(EGL14.eglChooseConfig(display, CONFIG, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
		context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
		eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
		check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL context not usable" }
	}

	fun createExternalTexture(): Int {
		val textures = IntArray(1)
		GLES20.glGenTextures(1, textures, 0)
		GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
		return textures[0]
	}

	fun createProgram(): Int {
		val program = GLES20.glCreateProgram()
		GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
		GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
		GLES20.glLinkProgram(program)
		val linked = IntArray(1)
		GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
		check(linked[0] != 0) { "Shaders did not link" }
		return program
	}

	private fun compile(
		type: Int,
		source: String,
	): Int {
		val shader = GLES20.glCreateShader(type)
		GLES20.glShaderSource(shader, source)
		GLES20.glCompileShader(shader)
		val compiled = IntArray(1)
		GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
		check(compiled[0] != 0) { "Shader did not compile" }
		return shader
	}

	/** Turns the frame by [degrees], the panel's rotation, and hands it to the encoder with the camera's time. */
	fun draw(
		program: Int,
		texture: Int,
		transform: FloatArray,
		degrees: Float,
		width: Int,
		height: Int,
		timeNs: Long,
	) {
		GLES20.glViewport(0, 0, width, height)
		GLES20.glUseProgram(program)
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
		GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
		Matrix.setRotateM(rotation, 0, degrees, 0f, 0f, 1f)
		GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "rotation"), 1, false, rotation, 0)
		GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0)
		val position = GLES20.glGetAttribLocation(program, "position")
		GLES20.glEnableVertexAttribArray(position)
		GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD.size / 2)
		EGLExt.eglPresentationTimeANDROID(display, eglSurface, timeNs)
		EGL14.eglSwapBuffers(display, eglSurface)
	}

	fun release() {
		EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
		EGL14.eglDestroySurface(display, eglSurface)
		EGL14.eglDestroyContext(display, context)
		EGL14.eglTerminate(display)
	}

	private companion object {
		// Needed for the encoder's surface, which is not a regular window.
		const val EGL_RECORDABLE_ANDROID = 0x3142
		val CONFIG =
			intArrayOf(
				EGL14.EGL_RED_SIZE,
				8,
				EGL14.EGL_GREEN_SIZE,
				8,
				EGL14.EGL_BLUE_SIZE,
				8,
				EGL14.EGL_RENDERABLE_TYPE,
				EGL14.EGL_OPENGL_ES2_BIT,
				EGL_RECORDABLE_ANDROID,
				1,
				EGL14.EGL_NONE,
			)
		val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
		const val VERTEX_SHADER = """
			attribute vec2 position;
			uniform mat4 rotation;
			uniform mat4 transform;
			varying vec2 coordinate;
			void main() {
				gl_Position = rotation * vec4(position, 0.0, 1.0);
				coordinate = (transform * vec4((position + 1.0) / 2.0, 0.0, 1.0)).xy;
			}
		"""
		const val FRAGMENT_SHADER = """
			#extension GL_OES_EGL_image_external : require
			precision mediump float;
			uniform samplerExternalOES frame;
			varying vec2 coordinate;
			void main() {
				gl_FragColor = texture2D(frame, coordinate);
			}
		"""
	}
}
