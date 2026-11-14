package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetPeerHostIdentityBindingTest {
    @Test
    void defaultPeerHostFailsClosedWhenItCannotBindVerifiedIdentity() throws Exception {
        NetherNetPeerHost host = new NetherNetPeerHost() {
            @Override
            public void setConnectionListener(java.util.function.Consumer<TransportConnection> listener) {
            }

            @Override
            public CompletableFuture<String> createAnswer(String networkId, String webRtcOfferSdp) {
                return CompletableFuture.completedFuture("v=0");
            }

            @Override
            public void close() {
            }
        };

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        java.security.interfaces.ECPublicKey key =
                (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic();
        NetherNetIdentityValidator.VerifiedIdentity identity =
                new NetherNetIdentityValidator.VerifiedIdentity(
                        "minecraft-services.net", "xuid-test", "TestPlayer", key);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> host.createAnswer("network-id", "v=0", identity)
                        .toCompletableFuture().join());
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
