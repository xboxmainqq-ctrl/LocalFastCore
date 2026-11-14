package ru.nexora.core.integration.nukkit;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.security.PermissionSubject;
import ru.nexora.core.platform.PlayerSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Nukkit-MOT implementation of the platform-neutral NEXORA platform contract.
 * Keep all cn.nukkit imports inside this integration package.
 */
public final class NukkitPlatformAdapter implements NexoraPlatform {
    private static final String TARGET_VERSION = "1.26.60-R1";
    private final PluginBase plugin;

    public NukkitPlatformAdapter(PluginBase plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String getPlatformName() {
        return "Nukkit-MOT";
    }

    @Override
    public String getPlatformVersion() {
        return TARGET_VERSION;
    }

    @Override
    public Collection<PlayerSnapshot> getOnlinePlayers() {
        Collection<PlayerSnapshot> snapshots = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            snapshots.add(snapshot(player));
        }
        return snapshots;
    }

    @Override
    public Optional<PlayerSnapshot> findPlayer(UUID uniqueId) {
        Objects.requireNonNull(uniqueId, "uniqueId");
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (uniqueId.equals(player.getUniqueId())) {
                return Optional.of(snapshot(player));
            }
        }
        return Optional.empty();
    }

    /**
     * Adapts a live Nukkit-MOT sender to the platform-neutral NEXORA permission
     * contract. The returned subject delegates every check to the server's
     * permission manager; it does not cache permission results.
     */
    public PermissionSubject permissionSubject(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return sender::hasPermission;
    }

    /** Returns a bounded, non-secret actor label suitable for security auditing. */
    public String actorLabel(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        String name = sender.getName();
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.length() <= 128 ? name : name.substring(0, 128);
    }

    private static PlayerSnapshot snapshot(Player player) {
        return new PlayerSnapshot(player.getUniqueId(), player.getName());
    }
}
