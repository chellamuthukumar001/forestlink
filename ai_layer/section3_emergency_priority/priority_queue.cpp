#include "priority_queue.h"
#include <string.h>

typedef struct {
    QueueItem buffer[MAX_SOS_QUEUE_CAPACITY];
    uint8_t head;
    uint8_t tail;
    uint8_t count;
} SosQueue;

typedef struct {
    QueueItem buffer[MAX_NORMAL_QUEUE_CAPACITY];
    uint8_t head;
    uint8_t tail;
    uint8_t count;
} NormalQueue;

static SosQueue s_sos_queue;
static NormalQueue s_normal_queue;

void priority_queue_init(void) {
    memset(&s_sos_queue, 0, sizeof(SosQueue));
    memset(&s_normal_queue, 0, sizeof(NormalQueue));
}

bool priority_queue_enqueue(
    uint32_t msg_id,
    uint16_t sender_id,
    uint16_t dest_id,
    const char *payload_str,
    PriorityLevel priority,
    uint16_t backoff_delay_ms,
    uint8_t ttl,
    uint32_t current_millis
) {
    if (!payload_str) return false;

    QueueItem item;
    memset(&item, 0, sizeof(QueueItem));
    item.msg_id = msg_id;
    item.sender_id = sender_id;
    item.dest_id = dest_id;
    item.priority = priority;
    item.ttl = ttl;
    item.retry_count = 3;
    item.backoff_delay_ms = backoff_delay_ms;
    item.enqueue_millis = current_millis;

    size_t len = strlen(payload_str);
    if (len >= MAX_PAYLOAD_SIZE) len = MAX_PAYLOAD_SIZE - 1;
    strncpy(item.payload, payload_str, len);
    item.payload[len] = '\0';
    item.payload_len = (uint16_t)len;

    if (priority == PRIORITY_EMERGENCY_SOS) {
        // Enqueue to high-priority SOS buffer
        if (s_sos_queue.count >= MAX_SOS_QUEUE_CAPACITY) {
            // Buffer full: drop oldest SOS
            s_sos_queue.head = (s_sos_queue.head + 1) % MAX_SOS_QUEUE_CAPACITY;
            s_sos_queue.count--;
        }
        s_sos_queue.buffer[s_sos_queue.tail] = item;
        s_sos_queue.tail = (s_sos_queue.tail + 1) % MAX_SOS_QUEUE_CAPACITY;
        s_sos_queue.count++;
        return true;
    } else {
        // Enqueue to Normal/Elevated buffer
        if (s_normal_queue.count >= MAX_NORMAL_QUEUE_CAPACITY) {
            return false; // Normal queue full, drop packet
        }
        s_normal_queue.buffer[s_normal_queue.tail] = item;
        s_normal_queue.tail = (s_normal_queue.tail + 1) % MAX_NORMAL_QUEUE_CAPACITY;
        s_normal_queue.count++;
        return true;
    }
}

bool priority_queue_dequeue(QueueItem *out_item, uint32_t current_millis) {
    if (!out_item) return false;

    // 1. Check SOS Emergency Queue first (Strict Preemption)
    if (s_sos_queue.count > 0) {
        QueueItem *candidate = &s_sos_queue.buffer[s_sos_queue.head];
        // Check if backoff window has elapsed (SOS backoff is typically 0-20ms)
        if ((current_millis - candidate->enqueue_millis) >= candidate->backoff_delay_ms) {
            *out_item = *candidate;
            s_sos_queue.head = (s_sos_queue.head + 1) % MAX_SOS_QUEUE_CAPACITY;
            s_sos_queue.count--;
            return true;
        }
    }

    // 2. If no SOS message is ready, check Normal Queue
    if (s_normal_queue.count > 0) {
        QueueItem *candidate = &s_normal_queue.buffer[s_normal_queue.head];
        // Check standard CSMA/CA backoff window (500-1800ms)
        if ((current_millis - candidate->enqueue_millis) >= candidate->backoff_delay_ms) {
            *out_item = *candidate;
            s_normal_queue.head = (s_normal_queue.head + 1) % MAX_NORMAL_QUEUE_CAPACITY;
            s_normal_queue.count--;
            return true;
        }
    }

    return false; // Nothing ready for transmission
}

bool priority_queue_relay_packet(
    uint32_t msg_id,
    uint16_t sender_id,
    uint16_t dest_id,
    const char *payload_str,
    uint8_t current_ttl,
    uint32_t current_millis
) {
    // Decrement TTL; discard if expired
    if (current_ttl <= 1) {
        return false; // TTL exhausted, drop packet to prevent loop
    }

    uint8_t new_ttl = current_ttl - 1;
    PriorityClassification classification = emergency_classify(payload_str, sender_id, NULL, current_millis);

    return priority_queue_enqueue(
        msg_id,
        sender_id,
        dest_id,
        payload_str,
        classification.level,
        classification.backoff_delay_ms,
        new_ttl,
        current_millis
    );
}

uint8_t priority_queue_sos_count(void) {
    return s_sos_queue.count;
}

uint8_t priority_queue_normal_count(void) {
    return s_normal_queue.count;
}

uint8_t priority_queue_total_count(void) {
    return s_sos_queue.count + s_normal_queue.count;
}

bool priority_queue_is_empty(void) {
    return (s_sos_queue.count == 0 && s_normal_queue.count == 0);
}
