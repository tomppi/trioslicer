package com.tomppi.enderslicer.viewer

import android.opengl.GLES20

/**
 * Compiling and linking GLES20 programs.
 *
 * All four surface views need exactly this, and each of them carried its own
 * copy. The compile and link results are checked here, so a shader that will not
 * build fails loudly instead of drawing nothing.
 */
internal fun createGlProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileGlShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileGlShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    return GLES20.glCreateProgram().also { program ->
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }
}

/** Compiles one stage, throwing with the driver's own log if it will not build. */
internal fun compileGlShader(type: Int, source: String): Int =
    GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    }
