package ru.nexora.runtime;

/** Delivery semantics exposed by a network transport. */
public enum DeliveryMode {
    /** Ordered delivery with retransmission and loss recovery. */
    RELIABLE_ORDERED,

    /** Unordered delivery without retransmission; loss is permitted. */
    UNRELIABLE
}
