package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLoginPacketParserTest {
    @Test
    void parsesModernLoginConnectionRequest() throws Exception {
        String certificate = "{\"chain\":[\"chain-token-a\",\"chain-token-b\"]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"game-server-token\"}";
        byte[] connectionRequest = connectionRequest(chainJson, "client-data-token");
        byte[] payload = loginPayload(2225, connectionRequest);

        BedrockLoginPacketParser.LoginRequest request =
                BedrockLoginPacketParser.parse(new BedrockPacket(1, 0, 0, payload));

        assertEquals(2225, request.clientProtocol());
        assertEquals(0, request.authenticationType());
        assertEquals("game-server-token", request.multiplayerToken());
        assertEquals("client-data-token", request.rawClientDataToken());
        assertEquals(2, request.certificateChain().size());
        assertEquals("chain-token-b", request.certificateChain().get(1));
    }

    @Test
    void parsesLegacyDirectChainShape() throws Exception {
        String chainJson = "{\"chain\":[\"legacy-token\"],\"Token\":\"game-server-token\"}";
        BedrockLoginPacketParser.LoginRequest request = BedrockLoginPacketParser.parse(
                new BedrockPacket(1, 0, 0,
                        loginPayload(2225, connectionRequest(chainJson, "client-data-token"))));

        assertEquals(1, request.certificateChain().size());
        assertEquals("legacy-token", request.certificateChain().getFirst());
    }

    @Test
    void rejectsUnexpectedPacketAndTruncatedLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketParser.parse(new BedrockPacket(2, 0, 0, new byte[8])));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketParser.parse(new BedrockPacket(1, 0, 0, new byte[] {0, 0, 0, 1, 5})));
    }

    private static byte[] connectionRequest(String chainJson, String rawToken) throws Exception {
        byte[] chainBytes = chainJson.getBytes(StandardCharsets.UTF_8);
        byte[] rawBytes = rawToken.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chainBytes.length).array());
        output.write(chainBytes);
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rawBytes.length).array());
        output.write(rawBytes);
        return output.toByteArray();
    }

    private static byte[] loginPayload(int protocol, byte[] connectionRequest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protocol).array());
        writeVarInt(output, connectionRequest.length);
        output.writeBytes(connectionRequest);
        return output.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.write(current);
        } while (value != 0);
    }
}
