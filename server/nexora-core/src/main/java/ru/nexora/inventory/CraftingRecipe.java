package ru.nexora.inventory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable shaped or shapeless recipe with a stable protocol UUID. */
public record CraftingRecipe(
        String id,
        Map<String, Integer> ingredients,
        ItemStack result,
        List<String> pattern,
        Map<Character, String> key
) {
    public CraftingRecipe(String id, Map<String, Integer> ingredients, ItemStack result) {
        this(id, ingredients, result, List.of(), Map.of());
    }

    public CraftingRecipe {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Crafting recipe ID must be namespaced");
        }
        Objects.requireNonNull(ingredients, "ingredients");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(key, "key");
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            throw new IllegalArgumentException("Recipe must have 1..9 ingredient types");
        }

        Map<String, Integer> validated = new LinkedHashMap<>();
        for (var entry : ingredients.entrySet()) {
            String itemId = entry.getKey();
            Integer count = entry.getValue();
            if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || count == null || count < 1 || count > 64) {
                throw new IllegalArgumentException("Invalid crafting ingredient");
            }
            validated.put(itemId, count);
        }
        ingredients = Map.copyOf(validated);

        if (pattern.isEmpty()) {
            if (!key.isEmpty()) throw new IllegalArgumentException("Shapeless recipe cannot define a pattern key");
            pattern = List.of();
            key = Map.of();
        } else {
            if (pattern.size() > 3 || pattern.isEmpty()) {
                throw new IllegalArgumentException("Shaped recipe height must be 1..3");
            }
            int width = pattern.getFirst().length();
            if (width < 1 || width > 3) throw new IllegalArgumentException("Shaped recipe width must be 1..3");
            Map<String, Integer> patternIngredients = new HashMap<>();
            java.util.Set<Character> usedKeys = new java.util.HashSet<>();
            for (String row : pattern) {
                if (row == null || row.length() != width) {
                    throw new IllegalArgumentException("Shaped recipe rows must have equal width");
                }
                for (int i = 0; i < row.length(); i++) {
                    char symbol = row.charAt(i);
                    if (symbol == ' ') continue;
                    String itemId = key.get(symbol);
                    if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                        throw new IllegalArgumentException("Shaped recipe pattern references an unknown key");
                    }
                    usedKeys.add(symbol);
                    patternIngredients.merge(itemId, 1, Integer::sum);
                }
            }
            if (patternIngredients.isEmpty() || !patternIngredients.equals(ingredients)
                    || usedKeys.size() != key.size()) {
                throw new IllegalArgumentException("Shaped recipe key and ingredient counts do not match its pattern");
            }
            pattern = List.copyOf(pattern);
            key = Map.copyOf(key);
        }
    }

    public static CraftingRecipe shaped(
            String id, List<String> pattern, Map<Character, String> key, ItemStack result
    ) {
        Map<String, Integer> counts = new HashMap<>();
        for (String row : pattern) {
            for (int i = 0; i < row.length(); i++) {
                char symbol = row.charAt(i);
                if (symbol != ' ') counts.merge(key.get(symbol), 1, Integer::sum);
            }
        }
        return new CraftingRecipe(id, counts, result, pattern, key);
    }

    public boolean isShaped() {
        return !pattern.isEmpty();
    }

    public int width() {
        return isShaped() ? pattern.getFirst().length() : 0;
    }

    public int height() {
        return pattern.size();
    }

    public UUID uuid() {
        try {
            // Bedrock derives recipe UUIDs from the raw MD5 digest. UUID.nameUUIDFromBytes()
            // changes the version and variant bits, so it is not wire-compatible here.
            byte[] digest = MessageDigest.getInstance("MD5").digest(id.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("MD5 is required by the Java runtime", impossible);
        }
    }
}
