package ru.nexora.core.platform;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPlatformTest {
    @Test
    void platformContractUsesOnlyNexoraTypes() {
        UUID id = UUID.randomUUID();
        PlayerSnapshot player = new PlayerSnapshot(id, "TestPlayer");
        NexoraPlatform platform = new NexoraPlatform() {
            @Override public String getPlatformName() { return "TestPlatform"; }
            @Override public String getPlatformVersion() { return "test"; }
            @Override public Collection<PlayerSnapshot> getOnlinePlayers() { return List.of(player); }
            @Override public Optional<PlayerSnapshot> findPlayer(UUID uniqueId) {
                return id.equals(uniqueId) ? Optional.of(player) : Optional.empty();
            }
        };

        assertEquals("TestPlatform", platform.getPlatformName());
        assertEquals("test", platform.getPlatformVersion());
        assertEquals(player, platform.getOnlinePlayers().iterator().next());
        assertEquals(Optional.of(player), platform.findPlayer(id));
        assertTrue(platform.findPlayer(UUID.randomUUID()).isEmpty());
    }
}
