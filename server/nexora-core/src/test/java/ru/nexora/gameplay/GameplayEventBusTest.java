package ru.nexora.gameplay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameplayEventBusTest {
    @Test
    void deliversEventsInRegistrationOrder() {
        GameplayEventBus bus = new GameplayEventBus();
        List<String> received = new ArrayList<>();

        bus.subscribe(GameplayEventBus.Type.SPELL_CAST, e -> received.add("magic"));
        bus.subscribe(GameplayEventBus.Type.SPELL_CAST, e -> received.add("combat"));

        bus.publish(new GameplayEventBus.Event(
                GameplayEventBus.Type.SPELL_CAST, "player-1", "fireball"));

        assertEquals(List.of("magic", "combat"), received);
    }
}
