package com.forest.offgrid.data.ble

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialized BLE Write Queue – inspired by Meshtastic's reliable packet delivery.
 *
 * Solves the core BLE race condition: Android's BLE stack silently drops writes
 * if a second write is issued before onCharacteristicWrite() fires for the first.
 *
 * Usage:
 *   1. Call enqueue(bytes) to add a packet.
 *   2. Call onWriteComplete() from onCharacteristicWrite() to unblock the next send.
 *   3. Call drain() on shutdown.
 */
class BleWriteQueue(
    private val onWrite: (ByteArray) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "BleWriteQueue"
        private const val MAX_QUEUE_SIZE = 64
        private const val WRITE_TIMEOUT_MS = 3000L // 3s per-write timeout
    }

    private val queue = Channel<ByteArray>(MAX_QUEUE_SIZE)
    private val writeAck = Channel<Unit>(1)
    private val running = AtomicBoolean(false)
    private var consumerJob: Job? = null

    private val _pendingCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val pendingCount: kotlinx.coroutines.flow.StateFlow<Int> = _pendingCount

    /**
     * Start the consumer coroutine. Must be called before enqueue().
     */
    fun start() {
        if (running.getAndSet(true)) return
        consumerJob = scope.launch {
            for (bytes in queue) {
                _pendingCount.value = (_pendingCount.value - 1).coerceAtLeast(0)
                val sent = onWrite(bytes)
                if (sent) {
                    // Wait for write callback or timeout
                    withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                        writeAck.receive()
                    } ?: Log.w(TAG, "Write timeout – proceeding (${bytes.size} bytes)")
                } else {
                    Log.e(TAG, "Write returned false – packet dropped")
                }
                // Small inter-packet gap to let the BLE stack breathe
                delay(20)
            }
        }
    }

    /**
     * Enqueue a packet for serialized delivery. Suspends if queue is full.
     */
    suspend fun enqueue(bytes: ByteArray) {
        if (!running.get()) {
            Log.w(TAG, "Queue not started – dropping ${bytes.size} bytes")
            return
        }
        queue.send(bytes)
        _pendingCount.value = _pendingCount.value + 1
        Log.d(TAG, "Enqueued ${bytes.size} bytes (pending: ${_pendingCount.value})")
    }

    /**
     * Non-suspending enqueue that returns false if queue is full.
     */
    fun tryEnqueue(bytes: ByteArray): Boolean {
        if (!running.get()) return false
        val offered = queue.trySend(bytes).isSuccess
        if (offered) _pendingCount.value = _pendingCount.value + 1
        return offered
    }

    /**
     * Called from BleManager's onCharacteristicWrite() callback to unblock the next send.
     */
    fun onWriteComplete() {
        writeAck.trySend(Unit)
    }

    /**
     * Stop the consumer and clear pending queue.
     */
    fun drain() {
        running.set(false)
        consumerJob?.cancel()
        consumerJob = null
        // Drain remaining items
        while (queue.tryReceive().isSuccess) { /* discard */ }
        _pendingCount.value = 0
        Log.d(TAG, "Queue drained")
    }
}
