package ru.nexora.network.nethernet;

import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudburstNetherNetTransportTest {
    @TempDir
    Path dataDirectory;

    @Test
    void startsNativeNetherNetSignalingAndServesJoinMetadata() throws Exception {
        int tcpPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            tcpPort = socket.getLocalPort();
        }

        CloudburstNetherNetTransport transport = new CloudburstNetherNetTransport(
                new InetSocketAddress("127.0.0.1", tcpPort),
                "NEXOR transport smoke test",
                "NEXORA Test World",
                12,
                41000,
                41032,
                List.of(),
                Map.of(),
                dataDirectory,
                (networkId, channelLabel, frame) -> {
                },
                () -> 0,
                () -> true,
                TokenTrust.ANY);

        try {
            transport.start();

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + tcpPort + "/v1/join"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("NEXOR transport smoke test"));
            assertTrue(response.body().contains("NEXORA Test World"));
            assertTrue(transport.isStarted());
            assertTrue(Files.isRegularFile(
                    dataDirectory.resolve("identity").resolve("nethernet-operator.pem")));
        } finally {
            transport.close();
        }
    }
}
