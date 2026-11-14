package ru.nexora.core.rpg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RpgClassRegistryTest {
    @Test
    void registersAndFindsNormalizedClassIds() {
        RpgClassRegistry registry = new RpgClassRegistry();
        RpgClassDefinition mage = new RpgClassDefinition("Mage", "Mage",
                "A ranged spellcaster", Map.of("intelligence", 8), List.of("fire_bolt"));

        registry.register(mage);

        assertEquals(mage, registry.find("MAGE").orElseThrow());
        assertEquals(mage, registry.require("mage"));
        assertEquals(1, registry.size());
    }

    @Test
    void rejectsDuplicateIdsAndUnknownClasses() {
        RpgClassRegistry registry = new RpgClassRegistry();
        RpgClassDefinition first = new RpgClassDefinition("warrior", "Warrior",
                "Front-line fighter", Map.of("strength", 5), List.of());
        registry.register(first);

        assertThrows(IllegalStateException.class, () -> registry.register(first));
        assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));
    }

    @Test
    void definitionsAreImmutableAndRejectInvalidAbilityIds() {
        RpgClassDefinition definition = new RpgClassDefinition("ranger", "Ranger",
                "Mobile ranged fighter", Map.of("agility", 6), List.of("aim", "volley"));
        assertThrows(UnsupportedOperationException.class,
                () -> definition.baseAttributes().put("strength", 2));
        assertThrows(UnsupportedOperationException.class,
                () -> definition.abilityIds().add("dash"));
        assertThrows(IllegalArgumentException.class, () ->
                new RpgClassDefinition("bad", "Bad", "Bad class", Map.of("luck", 5), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new RpgClassDefinition("bad", "Bad", "Bad class", Map.of(), List.of("same", "same")));
    }

    @Test
    void listsClassesInStableDisplayNameOrder() {
        RpgClassRegistry registry = new RpgClassRegistry();
        registry.register(new RpgClassDefinition("warrior", "Warrior", "Fighter", Map.of(), List.of()));
        registry.register(new RpgClassDefinition("archer", "Archer", "Ranged", Map.of(), List.of()));

        assertEquals(List.of("Archer", "Warrior"),
                registry.all().stream().map(RpgClassDefinition::displayName).toList());
    }
}
