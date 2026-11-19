package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TcpClusterTransportProcessIntegrationTest {
    @Test
    void twoIndependentJvmNodesExchangeAuthenticatedMessage() throws Exception {
        int[] ports = freePorts();
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        Process nodeB = start("node-b", ports[1], "node-a", ports[0], secret, "listen");
        try (BufferedReader nodeBOutput = new BufferedReader(
                new InputStreamReader(nodeB.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("READY:node-b", readLine(nodeBOutput, 8),
                    "Destination JVM must bind its independent cluster listener");

            Process nodeA = start("node-a", ports[0], "node-b", ports[1], secret, "send");
            assertTrue(nodeA.waitFor(10, TimeUnit.SECONDS), "Source JVM must finish its send");
            String sourceOutput = new String(nodeA.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, nodeA.exitValue(), sourceOutput);
            assertTrue(sourceOutput.contains("SENT:node-b"), sourceOutput);

            assertEquals("RECEIVED:node-a:CLUSTER_INTEGRATION_PROBE:010203",
                    readLine(nodeBOutput, 8),
                    "Independent destination JVM must authenticate and deliver the message");
        } finally {
            nodeB.destroyForcibly();
            assertTrue(nodeB.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private static Process start(String node, int bindPort, String peer, int peerPort,
                                 String secret, String mode) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", classpath, ClusterTransportProcess.class.getName(),
                node, Integer.toString(bindPort), peer, Integer.toString(peerPort), secret, mode)
                .redirectErrorStream(true)
                .start();
    }

    private static String readLine(BufferedReader reader, long timeoutSeconds) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try { return reader.readLine(); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private static int[] freePorts() throws Exception {
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket second = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return new int[] {first.getLocalPort(), second.getLocalPort()};
        }
    }
}
