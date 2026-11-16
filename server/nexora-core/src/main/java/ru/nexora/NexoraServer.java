package ru.nexora;

import ru.nexora.network.nethernet.DefaultNetherNetIdentityService;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetServerMetadata;
import ru.nexora.network.nethernet.NetherNetSignalingServer;
import ru.nexora.network.nethernet.WebRtcSdpNegotiator;
import ru.nexora.network.raknet.BedrockServerStatus;
import ru.nexora.network.raknet.RakNetDiscoveryServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * NEXORA Core bootstrap. Modern Bedrock transport is NetherNet over WebRTC.
 */
public final class NexoraServer {
    private NexoraServer() {
    }

    public static void main(String[] args) throws Exception {
        String bindHost = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19132;
        String motd = args.length > 2 ? args[2] : "NEXORA Core - Development";
        int maxPlayers = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        InetAddress bindAddress = InetAddress.getByName(bindHost);
        InetSocketAddress socketAddress = new InetSocketAddress(bindAddress, port);

        BedrockServerStatus legacyStatus = new BedrockServerStatus(
                motd,
                2225,
                "1.26.62",
                0,
                maxPlayers,
                "Legacy RakNet discovery only",
                "Survival",
                1,
                port,
                port
        );
        RakNetDiscoveryServer discovery = new RakNetDiscoveryServer(
                socketAddress,
                legacyStatus,
                new java.security.SecureRandom().nextLong()
        );

        DefaultNetherNetIdentityService identityService =
                new DefaultNetherNetIdentityService(Path.of("data", "identity"));
        NetherNetInboundPipeline inboundPipeline = new NetherNetInboundPipeline(message ->
                System.out.printf("NEXORA: received %s payload from %s (%d bytes); Bedrock batch decoding is next%n",
                        message.channelLabel(), message.networkId(), message.payload().length));
        WebRtcSdpNegotiator negotiator = new WebRtcSdpNegotiator(
                identityService, inboundPipeline, maxPlayers);
        NetherNetServerMetadata metadata = new NetherNetServerMetadata(
                motd,
                2225,
                "1.26.62",
                "NEXORA World",
                0,
                maxPlayers,
                0
        );
        NetherNetSignalingServer signaling = new NetherNetSignalingServer(
                socketAddress, metadata, negotiator);

        try {
            discovery.start();
            signaling.start();
        } catch (Exception exception) {
            signaling.close();
            negotiator.close();
            discovery.close();
            throw exception;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            signaling.close();
            negotiator.close();
            discovery.close();
        }, "nexora-shutdown"));

        System.out.printf("NEXORA NetherNet signaling listening on TCP %s:%d%n", bindHost, port);
        System.out.printf("NEXORA legacy RakNet discovery listening on UDP %s:%d%n", bindHost, port);
        System.out.println("WebRTC sessions use the persistent operator identity in data/identity/.");
        System.out.println("Plain HTTP signaling is enabled; use HTTPS in production.");
        System.out.println("Transport is not yet a playable server: Bedrock batch decoding, login and world simulation are next.");
    }
}
