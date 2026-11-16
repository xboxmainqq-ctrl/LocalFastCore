package ru.nexora.network.raknet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small UDP discovery responder. It intentionally does not accept game sessions yet.
 */
public final class RakNetDiscoveryServer implements AutoCloseable {
    private static final int RECEIVE_BUFFER_SIZE = 2048;

    private final InetSocketAddress bindAddress;
    private final BedrockServerStatus status;
    private final long serverGuid;
    private final AtomicBoolean running = new AtomicBoolean();
    private DatagramSocket socket;
    private Thread worker;

    public RakNetDiscoveryServer(InetAddress bindAddress, BedrockServerStatus status) {
        this(new InetSocketAddress(Objects.requireNonNull(bindAddress), status.ipv4Port()), status,
                new SecureRandom().nextLong());
    }

    public RakNetDiscoveryServer(InetSocketAddress bindAddress, BedrockServerStatus status, long serverGuid) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.status = Objects.requireNonNull(status, "status");
        this.serverGuid = serverGuid;
    }

    public synchronized void start() throws SocketException {
        if (running.get()) return;
        socket = new DatagramSocket(bindAddress);
        running.set(true);
        worker = new Thread(this::receiveLoop, "nexora-raknet-discovery");
        worker.setDaemon(false);
        worker.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (running.get()) {
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(incoming);
                byte[] request = new byte[incoming.getLength()];
                System.arraycopy(incoming.getData(), incoming.getOffset(), request, 0, incoming.getLength());

                RakNetDiscoveryCodec.encodePong(request, serverGuid, status).ifPresent(response -> {
                    DatagramPacket outgoing = new DatagramPacket(
                            response, response.length, incoming.getSocketAddress());
                    try {
                        socket.send(outgoing);
                    } catch (IOException exception) {
                        if (running.get()) {
                            System.err.println("NEXORA: failed to send RakNet pong: " + exception.getMessage());
                        }
                    }
                });
            } catch (SocketException exception) {
                if (running.get()) {
                    System.err.println("NEXORA: RakNet discovery socket error: " + exception.getMessage());
                }
                break;
            } catch (IOException exception) {
                if (running.get()) {
                    System.err.println("NEXORA: RakNet discovery receive error: " + exception.getMessage());
                }
            }
        }
    }

    public long serverGuid() {
        return serverGuid;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (socket != null) socket.close();
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
