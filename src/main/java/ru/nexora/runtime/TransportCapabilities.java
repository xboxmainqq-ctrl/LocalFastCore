package ru.nexora.runtime;

/**
 * Immutable description of transport delivery support.
 *
 * <p>Capabilities are explicit so protocol code cannot accidentally assume
 * that every transport supports both delivery modes or fragmentation.</p>
 */
public record TransportCapabilities(
        boolean reliableOrdered,
        boolean unreliable,
        boolean reliableFragmentation,
        boolean unreliableFragmentation
) {
    public TransportCapabilities {
        if (reliableFragmentation && !reliableOrdered) {
            throw new IllegalArgumentException("Reliable fragmentation requires reliable ordered delivery");
        }
        if (unreliableFragmentation && !unreliable) {
            throw new IllegalArgumentException("Unreliable fragmentation requires unreliable delivery");
        }
    }
}
