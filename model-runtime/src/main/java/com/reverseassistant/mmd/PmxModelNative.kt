package com.reverseassistant.mmd

/** Static PMX/PMD mesh extraction backed by Assimp's MMD importer. */
object PmxModelNative {
    init { System.loadLibrary("mmd-model") }

    /** Returns expanded triangle vertices as position(3), normal(3), diffuse RGB(3) floats. */
    external fun loadTriangleVertices(modelPath: String): FloatArray?
}
