package ru.nexora.runtime.rpg;

import ru.nexora.runtime.NamespacedRegistry;

import java.util.Map;
import java.util.Optional;

/** Deterministic registry of selectable NEXORA RPG classes. */
public final class RpgClassRegistry {
    private final NamespacedRegistry<RpgClassDefinition> definitions =
            new NamespacedRegistry<>("RPG class");

    public void register(RpgClassDefinition definition) {
        if (definition == null) throw new NullPointerException("definition");
        definitions.register(definition.id(), definition);
    }

    public Optional<RpgClassDefinition> find(String id) {
        return definitions.find(id);
    }

    public RpgClassDefinition require(String id) {
        return definitions.require(id);
    }

    public Map<String, RpgClassDefinition> freeze() {
        return definitions.freeze();
    }

    public Map<String, RpgClassDefinition> snapshot() {
        return definitions.snapshot();
    }

    public static RpgClassRegistry createDefaults() {
        RpgClassRegistry registry = new RpgClassRegistry();
        registry.register(new RpgClassDefinition(
                "nexora:warrior", "Воин", "Ближний бой, стойкость и защита.",
                new RpgStats(14, 16, 7, 4, 13), 140, 20));
        registry.register(new RpgClassDefinition(
                "nexora:mage", "Маг", "Магический урон, контроль и запас маны.",
                new RpgStats(6, 4, 8, 18, 5), 85, 160));
        registry.register(new RpgClassDefinition(
                "nexora:ranger", "Следопыт", "Дальний бой, ловкость и выживание.",
                new RpgStats(9, 10, 17, 7, 8), 105, 70));
        registry.register(new RpgClassDefinition(
                "nexora:guardian", "Страж", "Защита союзников и удержание противников.",
                new RpgStats(18, 10, 5, 6, 18), 175, 45));
        return registry;
    }
}
