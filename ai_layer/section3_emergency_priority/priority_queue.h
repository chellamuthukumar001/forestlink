#ifndef FORESTLINK_PRIORITY_QUEUE_H
#define FORESTLINK_PRIORITY_QUEUE_H

#include <stdint.h>
#include <stdbool.h>
#include "emergency_classifier.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MAX_SOS_QUEUE_CAPACITY     8   // Dedicated emergency circular buffer
#define MAX_NORMAL_QUEUE_CAPACITY  24  // Normal operational traffic circular buffer
#define MAX_PAYLOAD_SIZE           192 // Max LoRa packet text payload
#define DEFAULT_TTL                7   // Default packet hop limit

typedef struct {
    uint32_t msg_id;
    uint16_t sender_id;
    uint16_t dest_id;
    uint8_t  ttl;
    uint8_t  retry_count;
    PriorityLevel priority;
    uint16_t backoff_delay_ms;
    uint32_t enqueue_millis;
    uint16_t payload_len;
    char     payload[MAX_PAYLOAD_SIZE];
} QueueItem;

/**
 * Initializes the two-tier preemptive priority queue.
 */
void priority_queue_init(void);

/**
 * Pushes a message into the appropriate queue tier.
 * Emergency SOS messages enter the high-priority queue with zero/near-zero backoff.
 */
bool priority_queue_enqueue(
    uint32_t msg_id,
    uint16_t sender_id,
    uint16_t dest_id,
    const char *payload_str,
    PriorityLevel priority,
    uint16_t backoff_delay_ms,
    uint8_t ttl,
    uint32_t current_millis
);

/**
 * Dequeues the highest-priority message whose backoff timer has expired.
 * SOS messages preempt normal messages unconditionally.
 * Returns true if an item was retrieved, false if queue is empty or still in backoff.
 */
bool priority_queue_dequeue(QueueItem *out_item, uint32_t current_millis);

/**
 * Relays an incoming packet: decrements TTL and enqueues for forwarding.
 * Discards packet if TTL reaches 0 to prevent routing loops.
 */
bool priority_queue_relay_packet(
    uint32_t msg_id,
    uint16_t sender_id,
    uint16_t dest_id,
    const char *payload_str,
    uint8_t current_ttl,
    uint32_t current_millis
);

/**
 * Queue statistics
 */
uint8_t priority_queue_sos_count(void);
uint8_t priority_queue_normal_count(void);
uint8_t priority_queue_total_count(void);
bool priority_queue_is_empty(void);

#ifdef __cplusplus
}
#endif

#endif // FORESTLINK_PRIORITY_QUEUE_H
