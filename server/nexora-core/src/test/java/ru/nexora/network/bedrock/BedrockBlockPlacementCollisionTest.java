package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;
import ru.nexora.world.WorldItemEntityStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockPlacementCollisionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void placementIsRejectedInsideAnotherConnectedPlayersHitbox() throws Exception {
        World world = new World("placement-collision-test", 0L, new FlatWorldGenerator(),
                0, 65, 0, null,
                new WorldItemEntityStorage(temporaryDirectory, "NEXORA"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> { }, event -> { });
        try {
            manager.onIdentity("builder", identity("builder"));
            manager.onIdentity("other", identity("other"));
            Object builder = session(manager, "builder");
            Object other = session(manager, "other");
            set(builder, "positionX", 0.5f);
            set(builder, "positionY", 65.0f);
            set(builder, "positionZ", 0.5f);
            set(other, "positionX", 1.5f);
            set(other, "positionY", 65.0f);
            set(other, "positionZ", 0.5f);
            set(other, "loginAuthenticated", true);

            Method collision = BedrockSessionManager.class.getDeclaredMethod(
                    "intersectsOtherPlayer", builder.getClass(), int.class, int.class, int.class);
            collision.setAccessible(true);

            assertTrue((boolean) collision.invoke(manager, builder, 1, 65, 0),
                    "a block must not be placed through another connected player's body");

            set(other, "positionX", 8.5f);
            assertFalse((boolean) collision.invoke(manager, builder, 1, 65, 0),
                    "players outside the target block must not prevent placement");
        } finally {
            manager.close();
        }
    }

    private static NetherNetClientIdentity identity(String id) {
        return new NetherNetClientIdentity(id + "-token", new byte[] {1, 2, 3},
                id + "-xuid", id, id + "-playfab", "20CA2", null);
    }

    @SuppressWarnings("unchecked")
    private static Object session(BedrockSessionManager manager, String id) throws Exception {
        Field field = BedrockSessionManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return ((Map<String, Object>) field.get(manager)).get(id);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
