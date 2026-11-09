package com.trackstudio.app.tracking.glomap

/**
 * MT19937 plus the 53-bit generate_canonical construction used by
 * std::uniform_real_distribution<double> on the pinned GLOMAP reference build.
 *
 * Kept local instead of java.util.Random: GLOMAP seeds std::mt19937 with 1
 * and consumes three uniform samples per Eigen::Vector3d.
 */
internal class GlomapMt19937(seed: Int = 1) {
    private val state = IntArray(624)
    private var index = 624
    var samplesConsumed: Int = 0
        private set

    init {
        state[0] = seed
        for (i in 1 until state.size) {
            val previous = state[i - 1].toLong() and UINT_MASK
            val value = 1_812_433_253L * (previous xor (previous ushr 30)) + i
            state[i] = (value and UINT_MASK).toInt()
        }
    }

    fun nextUInt32(): Long {
        if (index >= state.size) twist()
        var value = state[index++]
        value = value xor (value ushr 11)
        value = value xor ((value shl 7) and 0x9d2c5680.toInt())
        value = value xor ((value shl 15) and 0xefc60000.toInt())
        value = value xor (value ushr 18)
        samplesConsumed++
        return value.toLong() and UINT_MASK
    }

    /** Equivalent to std::uniform_real_distribution<double>(low, high). */
    fun uniformReal(low: Double, high: Double): Double {
        require(low.isFinite() && high.isFinite() && high > low)
        // generate_canonical<double, 53>: first engine result is the low word.
        val lowWord = nextUInt32().toDouble()
        val highWord = nextUInt32().toDouble()
        val unit = (lowWord + highWord * UINT_RANGE) / UINT_RANGE_SQUARED
        return low + (high - low) * unit
    }

    fun randomVector3d(low: Double, high: Double, scale: Double = 1.0): DoubleArray =
        DoubleArray(3) { uniformReal(low, high) * scale }

    private fun twist() {
        for (i in state.indices) {
            val y = (state[i] and UPPER_MASK) or
                (state[(i + 1) % state.size] and LOWER_MASK)
            state[i] = state[(i + 397) % state.size] xor (y ushr 1) xor
                if ((y and 1) != 0) MATRIX_A else 0
        }
        index = 0
    }

    private companion object {
        const val UINT_MASK = 0xffff_ffffL
        const val UINT_RANGE = 4_294_967_296.0
        const val UINT_RANGE_SQUARED = 18_446_744_073_709_551_616.0
        val UPPER_MASK = 0x80000000.toInt()
        const val LOWER_MASK = 0x7fffffff
        val MATRIX_A = 0x9908b0df.toInt()
    }
}
