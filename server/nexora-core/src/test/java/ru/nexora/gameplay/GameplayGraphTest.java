package ru.nexora.gameplay;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.AdaptiveRuntime;
import ru.nexora.runtime.TaskContract;
import ru.nexora.runtime.WorkGraph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameplayGraphTest {
    @Test
    void composesRpgMagicAndCombatIntoOneDependencyGraph() {
        GameplayGraph graph = new GameplayGraph()
                .action("class-check",
                        TaskContract.critical(AdaptiveRuntime.Lane.PLAYER, 20_000L))
                .action("spell-cast",
                        TaskContract.ordered(AdaptiveRuntime.Lane.MAGIC, 100_000L))
                .action("damage",
                        TaskContract.critical(AdaptiveRuntime.Lane.ENTITY, 80_000L))
                .action("effect",
                        TaskContract.deferrable(AdaptiveRuntime.Lane.ENTITY, 40_000L))
                .after("spell-cast", "class-check")
                .after("damage", "spell-cast")
                .after("effect", "damage")
                .validate();

        List<WorkGraph.NodeView> ready = graph.ready();
        assertEquals(1, ready.size());
        assertEquals("class-check", ready.get(0).id());
    }
}
