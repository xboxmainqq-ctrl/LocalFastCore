package ru.nexora.core.rpg;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe registry for ability modules. */
public final class RpgAbilityRegistry {
    private final ConcurrentMap<String, RpgAbilityDefinition> abilities = new ConcurrentHashMap<>();

    public void register(RpgAbilityDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        RpgAbilityDefinition previous = abilities.putIfAbsent(definition.id(), definition);
        if (previous != null) {
            throw new IllegalStateException("RPG ability is already registered: " + definition.id());
        }
    }

    public Optional<RpgAbilityDefinition> find(String abilityId) {
        if (abilityId == null || abilityId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(abilities.get(
                abilityId.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    public RpgAbilityDefinition require(String abilityId) {
        return find(abilityId).orElseThrow(() ->
                new IllegalArgumentException("Unknown RPG ability: " + abilityId));
    }

    public List<RpgAbilityDefinition> all() {
        return abilities.values().stream()
                .sorted(Comparator.comparing(RpgAbilityDefinition::displayName,
                        String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RpgAbilityDefinition::id))
                .toList();
    }

    public int size() {
        return abilities.size();
    }
}
