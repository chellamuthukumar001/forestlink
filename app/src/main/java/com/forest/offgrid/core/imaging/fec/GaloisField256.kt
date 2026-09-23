package com.forest.offgrid.core.imaging.fec

/**
 * Finite Field GF(2^8) arithmetic implementation for Reed-Solomon Erasure Coding.
 * Uses primitive polynomial 0x11D (x^8 + x^4 + x^3 + x^2 + 1 = 285) with generator alpha = 2.
 */
object GaloisField256 {

    private const val FIELD_SIZE = 256
    private const val PRIMITIVE_POLYNOMIAL = 0x11D

    val expTable = IntArray(FIELD_SIZE * 2)
    val logTable = IntArray(FIELD_SIZE)

    init {
        var x = 1
        for (i in 0 until FIELD_SIZE - 1) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x >= FIELD_SIZE) {
                x = x xor PRIMITIVE_POLYNOMIAL
            }
        }
        // Mirror the upper half of expTable to eliminate modulo in multiply()
        for (i in (FIELD_SIZE - 1) until (FIELD_SIZE * 2)) {
            expTable[i] = expTable[i - (FIELD_SIZE - 1)]
        }
        logTable[0] = 0 // Convention, though log(0) is mathematically undefined
    }

    /**
     * Addition in GF(2^8) is bitwise XOR.
     */
    inline fun add(a: Int, b: Int): Int = (a xor b) and 0xFF

    /**
     * Subtraction in GF(2^8) is identical to addition (bitwise XOR).
     */
    inline fun subtract(a: Int, b: Int): Int = (a xor b) and 0xFF

    /**
     * Multiplication in GF(2^8) using precomputed log and exp tables.
     */
    fun multiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a and 0xFF] + logTable[b and 0xFF]]
    }

    /**
     * Division in GF(2^8).
     */
    fun divide(a: Int, b: Int): Int {
        if (b == 0) throw ArithmeticException("Division by zero in GF(2^8)")
        if (a == 0) return 0
        val logDiff = logTable[a and 0xFF] - logTable[b and 0xFF] + (FIELD_SIZE - 1)
        return expTable[logDiff % (FIELD_SIZE - 1)]
    }

    /**
     * Multiplicative inverse in GF(2^8).
     */
    fun inverse(a: Int): Int {
        if (a == 0) throw ArithmeticException("Zero has no inverse in GF(2^8)")
        return expTable[(FIELD_SIZE - 1) - logTable[a and 0xFF]]
    }

    /**
     * Inverts a square K x K matrix in GF(2^8) using Gaussian Elimination.
     * @return The inverted K x K matrix, or null if singular (non-invertible).
     */
    fun invertMatrix(matrix: Array<IntArray>, k: Int): Array<IntArray>? {
        val work = Array(k) { r ->
            IntArray(2 * k) { c ->
                if (c < k) matrix[r][c] and 0xFF
                else if (c - k == r) 1 else 0
            }
        }

        // Forward elimination
        for (col in 0 until k) {
            // Find pivot with non-zero entry
            var pivotRow = -1
            for (row in col until k) {
                if (work[row][col] != 0) {
                    pivotRow = row
                    break
                }
            }
            if (pivotRow == -1) return null // Singular matrix

            // Swap rows if needed
            if (pivotRow != col) {
                val temp = work[col]
                work[col] = work[pivotRow]
                work[pivotRow] = temp
            }

            // Scale pivot row so leading coefficient is 1
            val pivotVal = work[col][col]
            if (pivotVal != 1) {
                val inv = inverse(pivotVal)
                for (c in 0 until 2 * k) {
                    work[col][c] = multiply(work[col][c], inv)
                }
            }

            // Eliminate column entries in all other rows
            for (row in 0 until k) {
                if (row != col) {
                    val factor = work[row][col]
                    if (factor != 0) {
                        for (c in 0 until 2 * k) {
                            work[row][c] = work[row][c] xor multiply(work[col][c], factor)
                        }
                    }
                }
            }
        }

        // Extract right-hand K x K inverse matrix
        return Array(k) { r ->
            IntArray(k) { c ->
                work[r][c + k]
            }
        }
    }
}
