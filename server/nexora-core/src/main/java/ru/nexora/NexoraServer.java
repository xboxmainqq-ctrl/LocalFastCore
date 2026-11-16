package ru.nexora;

import ru.nexora.network.raknet.BedrockServerStatus;
import ru.nexora.network.raknet.RakNetDiscoveryServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * NEXORA Core bootstrap. Legacy RakNet discovery is retained for protocol experiments only.
 */
public final class NexoraServer {
    private NexoraServer() {
    }

    public static void main(String[] args) throws Exception {
        String bindHost = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19132;
        String motd = args.length > 2 ? args[2] : "NEXORA Core - Development";
        int maxPlayers = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        BedrockServerStatus status = new BedrockServerStatus(
                motd,
                2225,
                "1.26.62",
                0,
                maxPlayers,
                "Independent Bedrock server core",
                "Survival",
                1,
                port,
                port
        );

        RakNetDiscoveryServer discovery = new RakNetDiscoveryServer(
                new InetSocketAddress(InetAddress.getByName(bindHost), port),
                status,
                new java.security.SecureRandom().nextLong()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(discovery::close, "nexora-shutdown"));
        discovery.start();
        System.out.printf("NEXORA legacy RakNet discovery listening on %s:%d%n",
                bindHost, port);
        System.out.println("Legacy discovery only. Modern Bedrock clients require the NetherNet WebRTC transport.");
        System.out.println("NetherNet signaling and gameplay sessions are not wired into the bootstrap yet.");
    }
}
