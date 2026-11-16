package ru.nexora.network.nethernet;

/** Receives complete NetherNet data-channel frames from an established peer. */
@FunctionalInterface
public interface NetherNetDataChannelListener {
    void onFrame(String networkId, String channelLabel, byte[] frame);

    default void onIdentity(String networkId, NetherNetClientIdentity identity) {
    }

    default void onDisconnected(String networkId) {
    }
}
