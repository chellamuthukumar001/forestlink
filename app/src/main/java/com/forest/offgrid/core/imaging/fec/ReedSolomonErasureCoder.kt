package com.forest.offgrid.core.imaging.fec

/**
 * Systematic Reed-Solomon Erasure Coder over GF(2^8).
 * 
 * Uses a Cauchy generator matrix ensuring that every square submatrix is invertible.
 * Encodes K data shards into K data + M parity shards (N = K + M).
 * Recovers all K data shards from ANY K distinct shards received.
 */
class ReedSolomonErasureCoder(
    val dataShards: Int,
    val parityShards: Int
) {
    val totalShards: Int = dataShards + parityShards

    init {
        require(dataShards in 1..128) { "dataShards must be between 1 and 128" }
        require(parityShards in 1..127) { "parityShards must be between 1 and 127" }
        require(totalShards <= 255) { "totalShards must not exceed 255" }
    }

    /**
     * Generator matrix of size (K + M) x K.
     * Top K rows = Identity matrix I_K (systematic code).
     * Bottom M rows = Cauchy matrix over GF(2^8).
     */
    private val generatorMatrix: Array<IntArray> = Array(totalShards) { r ->
        IntArray(dataShards) { c ->
            if (r < dataShards) {
                // Identity matrix
                if (r == c) 1 else 0
            } else {
                // Cauchy matrix row: 1 / ( (r - dataShards) ^ (parityShards + c) )
                val i = r - dataShards
                val j = parityShards + c
                GaloisField256.divide(1, i xor j)
            }
        }
    }

    /**
     * Computes M parity shards given K data shards of uniform length.
     * @param dataShardsArray Array of K byte arrays, each of identical length.
     * @return Array of M parity byte arrays of the same length.
     */
    fun encodeParity(dataShardsArray: Array<ByteArray>): Array<ByteArray> {
        require(dataShardsArray.size == dataShards) { "Expected $dataShards data shards, got ${dataShardsArray.size}" }
        val shardSize = dataShardsArray[0].size
        for (i in 1 until dataShards) {
            require(dataShardsArray[i].size == shardSize) { "All shards must have identical size" }
        }

        val parity = Array(parityShards) { ByteArray(shardSize) }

        for (p in 0 until parityShards) {
            val genRow = generatorMatrix[dataShards + p]
            val outShard = parity[p]

            for (c in 0 until dataShards) {
                val coeff = genRow[c]
                if (coeff == 0) continue
                val inShard = dataShardsArray[c]

                for (b in 0 until shardSize) {
                    val inByte = inShard[b].toInt() and 0xFF
                    val prod = GaloisField256.multiply(coeff, inByte)
                    outShard[b] = (outShard[b].toInt() xor prod).toByte()
                }
            }
        }
        return parity
    }

    /**
     * Decodes / reconstructs the original K data shards given at least K received shards.
     * 
     * @param receivedShards Map of shard index (0 until totalShards) to its shard payload.
     * @param shardSize Length in bytes of each shard.
     * @return Array of K reconstructed data byte arrays, or null if recovery is impossible.
     */
    fun decode(receivedShards: Map<Int, ByteArray>, shardSize: Int): Array<ByteArray>? {
        if (receivedShards.size < dataShards) return null

        // Check if all K data shards are already present (systematic shortcut: 0 math required)
        var hasAllData = true
        for (k in 0 until dataShards) {
            if (!receivedShards.containsKey(k)) {
                hasAllData = false
                break
            }
        }
        if (hasAllData) {
            return Array(dataShards) { k ->
                val s = receivedShards[k]!!
                if (s.size == shardSize) s.copyOf() else s.copyOf(shardSize)
            }
        }

        // Select the first K received shards
        val selectedIndices = receivedShards.keys.sorted().take(dataShards)
        val subMatrix = Array(dataShards) { r ->
            val shardIdx = selectedIndices[r]
            generatorMatrix[shardIdx].copyOf()
        }

        // Invert sub-matrix
        val invSubMatrix = GaloisField256.invertMatrix(subMatrix, dataShards) ?: return null

        val reconstructed = Array(dataShards) { ByteArray(shardSize) }
        val selectedShards = Array(dataShards) { r ->
            val s = receivedShards[selectedIndices[r]]!!
            if (s.size == shardSize) s else s.copyOf(shardSize)
        }

        // Matrix multiply: reconstructed = invSubMatrix * selectedShards
        for (c in 0 until dataShards) {
            val invRow = invSubMatrix[c]
            val outShard = reconstructed[c]

            for (r in 0 until dataShards) {
                val coeff = invRow[r]
                if (coeff == 0) continue
                val inShard = selectedShards[r]

                for (b in 0 until shardSize) {
                    val inByte = inShard[b].toInt() and 0xFF
                    val prod = GaloisField256.multiply(coeff, inByte)
                    outShard[b] = (outShard[b].toInt() xor prod).toByte()
                }
            }
        }

        return reconstructed
    }
}
