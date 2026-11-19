package ru.nexora.network;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Transport SPI. Concrete network adapters must authenticate peers and bound queues. */
public interface ClusterTransport extends AutoCloseable {
    void start(Consumer<ClusterMessage> receiver) throws IOException;
    CompletionStage<Void> send(ClusterMessage message);
    @Override void close() throws IOException;
}
