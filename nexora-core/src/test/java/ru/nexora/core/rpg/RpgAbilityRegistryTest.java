package ru.nexora.core.rpg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RpgAbilityRegistryTest {
    @Test
    void normalizesIdsAndRejectsDuplicateRegistrations() {
        RpgAbilityRegistry registry = new RpgAbilityRegistry();
        RpgAbilityDefinition ability = new RpgAbilityDefinition("Fire_Bolt", "Fire Bolt",
                "A ranged spell", 40, 12, RpgAbilityDefinition.TargetMode.ENTITY);

        registry.register(ability);

        assertEquals(ability, registry.require("FIRE_BOLT"));
        assertThrows(IllegalStateException.class, () -> registry.register(ability));
    }

    @Test
    void returnsAbilitiesInStableDisplayOrder() {
        RpgAbilityRegistry registry = new RpgAbilityRegistry();
        registry.register(new RpgAbilityDefinition("zeta", "Zeta", "Last", 0, 0,
                RpgAbilityDefinition.TargetMode.SELF));
        registry.register(new RpgAbilityDefinition("alpha", "Alpha", "First", 0, 0,
                RpgAbilityDefinition.TargetMode.SELF));

        assertEquals(List.of("alpha", "zeta"),
                registry.all().stream().map(RpgAbilityDefinition::id).toList());
    }

    @Test
    void rejectsInvalidCostsAndCooldowns() {
        assertThrows(IllegalArgumentException.class, () ->
                new RpgAbilityDefinition("bad", "Bad", "Bad", -1, 0,
                        RpgAbilityDefinition.TargetMode.SELF));
        assertThrows(IllegalArgumentException.class, () ->
                new RpgAbilityDefinition("bad", "Bad", "Bad", 0, -1,
                        RpgAbilityDefinition.TargetMode.SELF));
    }
}
