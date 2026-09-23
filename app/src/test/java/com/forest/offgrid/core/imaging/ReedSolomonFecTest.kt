package com.forest.offgrid.core.imaging

import com.forest.offgrid.core.imaging.fec.GaloisField256
import com.forest.offgrid.core.imaging.fec.ReedSolomonErasureCoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

class ReedSolomonFecTest {

    @Test
    fun testGaloisFieldBasicArithmetic() {
        assertEquals(0, GaloisField256.multiply(0, 42))
        assertEquals(0, GaloisField256.multiply(42, 0))
        assertEquals(42, GaloisField256.multiply(42, 1))

        // Multiplication and division inverse
        for (a in 1..255) {
            val inv = GaloisField256.inverse(a)
            assertEquals(1, GaloisField256.multiply(a, inv))
        }

        val a = 57
        val b = 189
        val prod = GaloisField256.multiply(a, b)
        val div = GaloisField256.divide(prod, b)
        assertEquals(a, div)
    }

    @Test
    fun testReedSolomonEncodeAndExactRecovery() {
        val k = 10
        val m = 3 // 10:3 ratio
        val shardSize = 180

        val coder = ReedSolomonErasureCoder(dataShards = k, parityShards = m)

        // Generate mock data shards
        val random = Random(42)
        val originalData = Array(k) {
            val bytes = ByteArray(shardSize)
            random.nextBytes(bytes)
            bytes
        }

        // Generate parity shards
        val parity = coder.encodeParity(originalData)
        assertEquals(m, parity.size)

        // Simulate receiving ALL K data shards (systematic case)
        val allShardsMap = mutableMapOf<Int, ByteArray>()
        for (i in 0 until k) allShardsMap[i] = originalData[i]
        for (p in 0 until m) allShardsMap[k + p] = parity[p]

        val decodedAll = coder.decode(allShardsMap, shardSize)
        assertNotNull(decodedAll)
        for (i in 0 until k) {
            assertArrayEquals("Shard $i mismatch", originalData[i], decodedAll!![i])
        }
    }

    @Test
    fun testReedSolomonRecoveryWith30PercentLoss() {
        val k = 10
        val m = 3 // 10:3 ratio: total 13 shards, can lose any 3 shards (~23-30%)
        val shardSize = 180

        val coder = ReedSolomonErasureCoder(dataShards = k, parityShards = m)

        val random = Random(123)
        val originalData = Array(k) {
            val bytes = ByteArray(shardSize)
            random.nextBytes(bytes)
            bytes
        }

        val parity = coder.encodeParity(originalData)

        val allShards = mutableMapOf<Int, ByteArray>()
        for (i in 0 until k) allShards[i] = originalData[i]
        for (p in 0 until m) allShards[k + p] = parity[p]

        // Drop 3 arbitrary data shards: shard 1, shard 4, shard 8
        val receivedWithLoss = HashMap(allShards)
        receivedWithLoss.remove(1)
        receivedWithLoss.remove(4)
        receivedWithLoss.remove(8)

        // We have exactly 10 shards remaining (7 data + 3 parity)
        assertEquals(10, receivedWithLoss.size)

        val recovered = coder.decode(receivedWithLoss, shardSize)
        assertNotNull("Failed to recover from 30% loss", recovered)

        for (i in 0 until k) {
            assertArrayEquals("Recovered shard $i mismatch", originalData[i], recovered!![i])
        }
    }

    @Test
    fun testReedSolomonRecoveryWithLostParityAndData() {
        val k = 6
        val m = 2
        val shardSize = 180

        val coder = ReedSolomonErasureCoder(k, m)
        val originalData = Array(k) { ByteArray(shardSize) { (it + 1).toByte() } }
        val parity = coder.encodeParity(originalData)

        val allShards = mutableMapOf<Int, ByteArray>()
        for (i in 0 until k) allShards[i] = originalData[i]
        for (p in 0 until m) allShards[k + p] = parity[p]

        // Drop 1 data shard (shard 0) and 1 parity shard (shard 6)
        allShards.remove(0)
        allShards.remove(6)

        val recovered = coder.decode(allShards, shardSize)
        assertNotNull(recovered)
        for (i in 0 until k) {
            assertArrayEquals("Shard $i mismatch", originalData[i], recovered!![i])
        }
    }
}
