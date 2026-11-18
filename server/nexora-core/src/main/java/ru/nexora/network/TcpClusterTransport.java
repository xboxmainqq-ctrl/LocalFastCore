package ru.nexora.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded TCP adapter for authenticated NEXORA node messages.
 *
 * Uses one short-lived TCP connection per message and an application ACK after
 * authentication, authorization, replay filtering and receiver acceptance.
 * HMAC provides message integrity/authenticity; this adapter adds a configured
 * peer allowlist and process-local replay filtering. Use TLS/network isolation
 * for confidentiality and a durable replay ledger for restart-safe dedup.
 */
public final class TcpClusterTransport implements ClusterTransport {
    private static final int ACK_ACCEPTED = 1;
    private static final int ACK_REJECTED = 0;
    private static final int MAX_ENVELOPE_BYTES = ClusterMessage.MAX_PAYLOAD_BYTES + 512;

    private final String localNodeId;
    private final InetSocketAddress bindAddress;
    private final Map<String, InetSocketAddress> peers;
    private final Set<String> authorizedPeers;
    private final AuthenticatedClusterMessageCodec codec;
    private final ClusterReplayFilter replayGuard;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final ThreadPoolExecutor sendExecutor;
    private final ThreadPoolExecutor receiveExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ServerSocket serverSocket;
    private volatile Consumer<ClusterMessage> receiver;
    private volatile Thread acceptThread;

    public TcpClusterTransport(
            String localNodeId,
            InetSocketAddress bindAddress,
            Map<String, InetSocketAddress> peers,
            Set<String> authorizedPeers,
            AuthenticatedClusterMessageCodec codec,
            ClusterReplayFilter replayGuard,
            Duration connectTimeout,
            Duration readTimeout,
            int workerCount,
            int queueCapacity
    ) {
        if (localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid local node ID");
        this.localNodeId = localNodeId;
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.peers = Map.copyOf(Objects.requireNonNull(peers, "peers"));
        this.authorizedPeers = Set.copyOf(Objects.requireNonNull(authorizedPeers, "authorizedPeers"));
        if (this.authorizedPeers.contains(localNodeId)) throw new IllegalArgumentException("Local node cannot authorize itself");
        for (Map.Entry<String, InetSocketAddress> peer : this.peers.entrySet()) {
            if (peer.getKey() == null || !peer.getKey().matches("[A-Za-z0-9._-]{1,64}")
                    || peer.getKey().equals(localNodeId) || peer.getValue() == null
                    || !this.authorizedPeers.contains(peer.getKey())) {
                throw new IllegalArgumentException("Invalid or unauthorized peer configuration");
            }
        }
        this.codec = Objects.requireNonNull(codec, "codec");
        this.replayGuard = Objects.requireNonNull(replayGuard, "replayGuard");
        this.connectTimeoutMillis = timeoutMillis(connectTimeout, "connectTimeout");
        this.readTimeoutMillis = timeoutMillis(readTimeout, "readTimeout");
        if (workerCount < 1 || queueCapacity < 1) throw new IllegalArgumentException("Worker/queue limits must be positive");
        this.sendExecutor = executor(workerCount, queueCapacity, "nexora-cluster-send-");
        this.receiveExecutor = executor(workerCount, queueCapacity, "nexora-cluster-recv-");
    }

    @Override
    public synchronized void start(Consumer<ClusterMessage> receiver) throws IOException {
        Objects.requireNonNull(receiver, "receiver");
        if (closed.get()) throw new IOException("Cluster transport is closed");
        if (!started.compareAndSet(false, true)) throw new IOException("Cluster transport already started");
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(bindAddress);
            this.receiver = receiver;
            this.serverSocket = socket;
            Thread thread = new Thread(this::acceptLoop, "nexora-cluster-accept-" + localNodeId);
            thread.setDaemon(true);
            this.acceptThread = thread;
            thread.start();
        } catch (IOException | RuntimeException failure) {
            started.set(false);
            try { socket.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public InetSocketAddress localAddress() {
        ServerSocket socket = serverSocket;
        if (socket == null || !socket.isBound()) throw new IllegalStateException("Cluster transport is not started");
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    @Override
    public CompletionStage<Void> send(ClusterMessage message) {
        Objects.requireNonNull(message, "message");
        if (!started.get() || closed.get()) return CompletableFuture.failedFuture(
                new IOException("Cluster transport is not running"));
        if (!localNodeId.equals(message.sourceNode())) return CompletableFuture.failedFuture(
                new SecurityException("Message source does not match local node"));
        InetSocketAddress target = peers.get(message.targetNode());
        if (target == null || !authorizedPeers.contains(message.targetNode())) return CompletableFuture.failedFuture(
                new SecurityException("Target node is not configured/authorized"));
        try {
            return CompletableFuture.runAsync(() -> {
                try { sendBlocking(target, message); }
                catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, sendExecutor);
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            return CompletableFuture.failedFuture(new IOException("Cluster send queue is full", saturated));
        }
    }

    private void sendBlocking(InetSocketAddress target, ClusterMessage message) throws IOException {
        byte[] envelope = codec.encode(message);
        if (envelope.length > MAX_ENVELOPE_BYTES) throw new IOException("Cluster envelope exceeds maximum");
        try (Socket socket = new Socket()) {
            socket.connect(target, connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(envelope.length);
            output.write(envelope);
            output.flush();
            int ack = new DataInputStream(socket.getInputStream()).readUnsignedByte();
            if (ack != ACK_ACCEPTED) throw new IOException("Remote node rejected cluster message");
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    receiveExecutor.execute(() -> receiveOne(socket));
                } catch (java.util.concurrent.RejectedExecutionException saturated) {
                    closeQuietly(socket);
                }
            } catch (SocketException stopped) {
                if (!closed.get()) close();
                return;
            } catch (IOException failure) {
                if (!closed.get()) System.err.println("NEXORA cluster accept failed: " + failure.getMessage());
            }
        }
    }

    private void receiveOne(Socket socket) {
        try (socket) {
            socket.setSoTimeout(readTimeoutMillis);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = input.readInt();
            if (length < 1 || length > MAX_ENVELOPE_BYTES) throw new IOException("Invalid cluster frame length");
            byte[] envelope = new byte[length];
            input.readFully(envelope);
            try {
                ClusterMessage message = codec.decode(envelope, localNodeId, System.currentTimeMillis());
                if (!authorizedPeers.contains(message.sourceNode()))
                    throw new SecurityException("Unauthorized cluster peer");
                if (!replayGuard.accept(message.messageId(), System.currentTimeMillis()))
                    throw new SecurityException("Duplicate or capacity-rejected cluster message");
                receiver.accept(message);
                socket.getOutputStream().write(ACK_ACCEPTED);
            } catch (Exception rejected) {
                socket.getOutputStream().write(ACK_REJECTED);
            }
            socket.getOutputStream().flush();
        } catch (IOException failure) {
            if (!closed.get()) System.err.println("NEXORA cluster receive failed: " + failure.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        sendExecutor.shutdownNow();
        receiveExecutor.shutdownNow();
        Thread thread = acceptThread;
        if (thread != null) thread.interrupt();
    }

    private static ThreadPoolExecutor executor(int workers, int queue, String prefix) {
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private static int timeoutMillis(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException(name + " must be positive");
        long millis = timeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) throw new IllegalArgumentException(name + " is outside supported range");
        return (int) millis;
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
