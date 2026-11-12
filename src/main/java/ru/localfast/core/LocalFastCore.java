package ru.localfast.core;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import cn.nukkit.block.Block;
import cn.nukkit.level.Level;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.level.Position;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.network.protocol.types.DisplaySlot;
import cn.nukkit.network.protocol.types.SortOrder;
import cn.nukkit.scoreboard.scoreboard.IScoreboard;
import cn.nukkit.scoreboard.scoreboard.Scoreboard;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.scheduler.TaskHandler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.Locale;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public final class LocalFastCore extends PluginBase implements Listener {
    private static final int MAX_LEVEL = 100;
    private static final int CURRENT_PLAYER_DATA_VERSION = 1;
    private static final int POINTS_PER_LEVEL = 3;
    private static final int MAX_XP_GRANT = 1_000_000;
    private static final int MOB_KILL_XP = 10;
    private static final int QUEST_KILLS_REQUIRED = 5;
    private static final int QUEST_XP_REWARD = 50;
    private static final int DAILY_KILLS_REQUIRED = 10;
    private static final int DAILY_XP_REWARD = 100;
    private static final int LOGIN_XP_REWARD = 25;
    private static final String[] STATS = {"strength", "health", "agility", "intelligence"};
    private static final String[] RPG_CLASSES = {"warrior", "mage", "archer", "assassin", "tank"};

    private Config players;
    private Config staffData;
    private File staffLogFile;
    private boolean playersDirty = false;
    private final Map<String, IScoreboard> sidebars = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
    private final Map<UUID, Integer> temporaryAbilityDamage = new HashMap<>();
    private final Map<UUID, Integer> nextAbilityDamage = new HashMap<>();
    private final Map<UUID, ComboState> combatCombos = new HashMap<>();
    private final Map<UUID, Long> tankGuardUntil = new HashMap<>();
    private final java.util.Set<UUID> vanishedPlayers = new java.util.HashSet<>();
    private final java.util.Set<UUID> flyingPlayers = new java.util.HashSet<>();
    private TaskHandler castleBuildTask;
    private boolean castleBuildActive = false;
    private int castleBuildPlaced = 0;
    private int castleBuildTotal = 0;

    private static final class PendingTeleport {
        final Player requester;
        final long expiresAt;
        PendingTeleport(Player requester, long expiresAt) {
            this.requester = requester;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PendingTrade {
        final UUID sender;
        final UUID target;
        final String senderName;
        final long amount;
        final long expiresAt;
        PendingTrade(UUID sender, UUID target, String senderName, long amount, long expiresAt) {
            this.sender = sender; this.target = target; this.senderName = senderName;
            this.amount = amount; this.expiresAt = expiresAt;
        }
    }

    private static final class ComboState {
        final int stage;
        final long lastHitAt;
        ComboState(int stage, long lastHitAt) {
            this.stage = stage;
            this.lastHitAt = lastHitAt;
        }
    }

    private static final class AchievementDefinition {
        final String id, title, description, metric;
        final int target, xpReward;
        final long moneyReward;
        AchievementDefinition(String id, String title, String description, String metric,
                              int target, int xpReward, long moneyReward) {
            this.id = id; this.title = title; this.description = description; this.metric = metric;
            this.target = target; this.xpReward = xpReward; this.moneyReward = moneyReward;
        }
    }

    private static final class QuestDefinition {
        final String id, title, description, mobType;
        final int minLevel, maxLevel, defaultKills, defaultReward;
        QuestDefinition(String id, String title, String description, String mobType,
                        int minLevel, int maxLevel, int defaultKills, int defaultReward) {
            this.id = id; this.title = title; this.description = description; this.mobType = mobType;
            this.minLevel = minLevel; this.maxLevel = maxLevel;
            this.defaultKills = defaultKills; this.defaultReward = defaultReward;
        }
    }

    private List<QuestDefinition> questCatalog() {
        return Arrays.asList(
                new QuestDefinition("rookie_zombies", "Зомби вокруг", "Победи 10 зомби", "zombie", 1, 10, 10, 100),
                new QuestDefinition("rookie_skeletons", "Костяная угроза", "Победи 5 скелетов", "skeleton", 1, 10, 5, 120),
                new QuestDefinition("rookie_hunt", "Первая охота", "Победи 20 любых монстров", "any", 1, 10, 20, 150),
                new QuestDefinition("adventurer_zombies", "Зачистка деревни", "Победи 30 зомби", "zombie", 11, 30, 30, 300),
                new QuestDefinition("adventurer_spiders", "Паучье логово", "Победи 20 пауков", "spider", 11, 30, 20, 350),
                new QuestDefinition("adventurer_hunt", "Опытный охотник", "Победи 40 любых монстров", "any", 11, 30, 40, 400),
                new QuestDefinition("veteran_skeletons", "Костяной легион", "Победи 50 скелетов", "skeleton", 31, 50, 50, 700),
                new QuestDefinition("veteran_spiders", "Гнездо пауков", "Победи 45 пауков", "spider", 31, 50, 45, 750),
                new QuestDefinition("veteran_hunt", "Закалённый боец", "Победи 80 любых монстров", "any", 31, 50, 80, 900),
                new QuestDefinition("elite_zombies", "Орда мертвецов", "Победи 100 зомби", "zombie", 51, 75, 100, 1300),
                new QuestDefinition("elite_skeletons", "Нескончаемый обстрел", "Победи 80 скелетов", "skeleton", 51, 75, 80, 1500),
                new QuestDefinition("elite_hunt", "Элитный истребитель", "Победи 150 любых монстров", "any", 51, 75, 150, 1800),
                new QuestDefinition("legend_spiders", "Король пауков", "Победи 150 пауков", "spider", 76, 100, 150, 2500),
                new QuestDefinition("legend_skeletons", "Последний рубеж", "Победи 150 скелетов", "skeleton", 76, 100, 150, 2800),
                new QuestDefinition("legend_hunt", "Легендарная охота", "Победи 250 любых монстров", "any", 76, 100, 250, 3500)
        );
    }

    private int questKills(QuestDefinition quest) {
        return Math.max(1, getConfig().getInt("quests.catalog." + quest.id + ".kills", quest.defaultKills));
    }

    private int questXp(QuestDefinition quest) {
        return Math.max(0, getConfig().getInt("quests.catalog." + quest.id + ".reward", quest.defaultReward));
    }

    private long questMoney(QuestDefinition quest) {
        return Math.max(0L, getConfig().getLong("quests.catalog." + quest.id + ".money", 0L));
    }

    private long safeAdd(long current, long reward) {
        return reward > Long.MAX_VALUE - current ? Long.MAX_VALUE : current + reward;
    }

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().error("Не удалось создать папку данных LocalFastCore.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        players = new Config(new File(getDataFolder(), "players.yml"), Config.YAML);
        guildData = new Config(new File(getDataFolder(), "guilds.yml"), Config.YAML);
        marketData = new Config(new File(getDataFolder(), "market.yml"), Config.YAML);
        staffData = new Config(new File(getDataFolder(), "staff.yml"), Config.YAML);
        staffLogFile = new File(getDataFolder(), "staff.log");
        getServer().getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        if (!getConfig().exists("operator.uuid")) getConfig().set("operator.uuid", "");
        if (!getConfig().exists("welcome.message") || getConfig().getString("welcome.message", "").trim().isEmpty()) {
            getConfig().set("welcome.message", "§7[Персонал] §fДобро пожаловать, §b{player}§f!");
        }
        if (!getConfig().exists("spawn-world")) getConfig().set("spawn-world", "spawn");
        if (!getConfig().exists("main-world")) getConfig().set("main-world", "");
        if (!getConfig().exists("rtp-min-radius")) getConfig().set("rtp-min-radius", 500);
        if (!getConfig().exists("rtp-max-radius")) getConfig().set("rtp-max-radius", 5000);
        if (!getConfig().exists("combat.combo-window-ms")) getConfig().set("combat.combo-window-ms", 1600);
        if (!getConfig().exists("combat.combo-second-hit-bonus")) getConfig().set("combat.combo-second-hit-bonus", 1);
        if (!getConfig().exists("combat.combo-finisher-bonus")) getConfig().set("combat.combo-finisher-bonus", 2);
        if (!getConfig().exists("combat.tank-guard-percent")) getConfig().set("combat.tank-guard-percent", 20);
        if (!getConfig().exists("combat.tank-guard-duration-ms")) getConfig().set("combat.tank-guard-duration-ms", 2000);
        saveConfig();
        int autosaveSeconds = Math.max(5, Math.min(3600,
                getConfig().getInt("autosave.interval-seconds", 60)));
        getServer().getScheduler().scheduleRepeatingTask(this, this::flushPlayers,
                autosaveSeconds * 20);
        int refreshTicks = Math.max(20, Math.min(1200, getConfig().getInt("scoreboard.refresh-ticks", 40)));
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("scoreboard.enabled", true)) return;
            for (Player online : getServer().getOnlinePlayers().values()) updateSidebar(online);
        }, refreshTicks);
        getLogger().info("LocalFastCore 0.21.0 включён. RPG, гильдии, рынок, аукцион, торговля и система персонала загружены.");
    }

    private void markPlayersDirty() {
        playersDirty = true;
    }

    /**
     * Config is deliberately saved on the server thread: Nukkit Config is not
     * assumed to be thread-safe. Failed writes keep the dirty flag for retry.
     */
    private boolean flushPlayers() {
        if (players == null) return false;
        if (!playersDirty) return true;
        try {
            players.save();
            playersDirty = false;
            return true;
        } catch (RuntimeException exception) {
            getLogger().error("Не удалось сохранить players.yml; повторим при следующем autosave.", exception);
            return false;
        }
    }

    private static final String DEFAULT_SPAWN_WORLD = "spawn";

    private Level loadConfiguredWorld(String key, String fallback) {
        String worldName = getConfig().getString(key, fallback);
        if (worldName == null || worldName.trim().isEmpty()) return getServer().getDefaultLevel();
        if (!getServer().isLevelLoaded(worldName) && !getServer().loadLevel(worldName)) return null;
        return getServer().getLevelByName(worldName);
    }

    private void teleportToSpawn(Player player) {
        String worldName = getConfig().getString("spawn-world", DEFAULT_SPAWN_WORLD);
        Level world = loadConfiguredWorld("spawn-world", DEFAULT_SPAWN_WORLD);
        boolean fallbackToDefault = false;
        if (world == null) {
            // Keep /spawn usable on servers that have not installed a separate lobby world yet.
            world = getServer().getDefaultLevel();
            if (world == null) {
                player.sendMessage("§cМир спавна '" + worldName + "' не найден, основной мир тоже недоступен.");
                return;
            }
            fallbackToDefault = true;
            player.sendMessage("§eМир '" + worldName + "' не найден. Использую основной мир; задай точку через /setspawn.");
        }
        // Saved coordinates belong to the configured spawn world. Never reuse them
        // after falling back to a different world.
        if (!fallbackToDefault && getConfig().exists("spawn-x") && getConfig().exists("spawn-y") && getConfig().exists("spawn-z")) {
            Position target = new Position(getConfig().getDouble("spawn-x"), getConfig().getDouble("spawn-y"),
                    getConfig().getDouble("spawn-z"), world);
            player.teleport(target);
        } else {
            player.teleport(world.getSafeSpawn());
            if (!fallbackToDefault) {
                player.sendMessage("§eТочка спавна ещё не задана. Встань в нужное место и введи /setspawn.");
            }
        }
    }

    private void setSpawn(Player player) {
        getConfig().set("spawn-world", player.getLevel().getFolderName());
        getConfig().set("spawn-x", player.getX());
        getConfig().set("spawn-y", player.getY());
        getConfig().set("spawn-z", player.getZ());
        saveConfig();
        player.sendMessage("§aТочка /spawn сохранена в мире " + player.getLevel().getFolderName() + ".");
    }

    private void teleportRandomly(Player player) {
        Level world = loadConfiguredWorld("main-world", "");
        if (world == null) {
            player.sendMessage("§cОсновной мир не найден. Проверь параметр main-world в config.yml.");
            return;
        }
        int minRadius = Math.max(0, Math.min(100000, getConfig().getInt("rtp-min-radius", 500)));
        int maxRadius = Math.max(minRadius + 1, Math.min(100000, getConfig().getInt("rtp-max-radius", 5000)));
        // Search around the world's safe spawn, not hard-coded X/Z=0:
        // many servers place their playable area far from the origin.
        Position center = world.getSafeSpawn();
        int centerX = center.getFloorX();
        int centerZ = center.getFloorZ();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 200; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            double radius = Math.sqrt(random.nextDouble(minRadius * (double) minRadius,
                    maxRadius * (double) maxRadius));
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int z = centerZ + (int) Math.round(Math.sin(angle) * radius);
            try {
                // Ensure the target chunk exists and is generated before querying its surface.
                if (world.getChunk(x >> 4, z >> 4, true) == null) continue;
                int groundY = world.getHighestBlockAt(x, z);
                if (groundY <= world.getMinBlockY() || groundY + 2 > world.getMaxBlockY()) continue;
                Block ground = world.getBlock(x, groundY, z);
                Block feet = world.getBlock(x, groundY + 1, z);
                Block head = world.getBlock(x, groundY + 2, z);
                // Solid floor and two non-solid blocks above are sufficient.
                // Requiring canPassThrough() rejected some valid custom/air blocks.
                if (!ground.isSolid() || feet.isSolid() || head.isSolid()) continue;
                player.teleport(new Position(x + 0.5, groundY + 1, z + 0.5, world));
                player.sendMessage("§a[RTP] Телепорт в основной мир: X " + x + ", Z " + z + ".");
                return;
            } catch (RuntimeException ignored) {
                // Skip unavailable or invalid terrain and try another location.
            }
        }
        player.sendMessage("§cНе удалось найти безопасное место. Попробуй /rtp ещё раз.");
    }

    private String legacyPlayerKey(Player player) {
        return player.getName().toLowerCase(Locale.ROOT).replace('.', '_');
    }

    private String playerKey(Player player) {
        return "uuid_" + player.getUniqueId().toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Copy an existing name-keyed profile to the UUID key once.
     * The legacy section is deliberately retained as a backup.
     */
    private void migrateLegacyPlayerData(Player player) {
        String uuidKey = playerKey(player);
        String legacyKey = legacyPlayerKey(player);
        if (uuidKey.equals(legacyKey) || players.exists(uuidKey) || !players.exists(legacyKey)) return;

        players.set(uuidKey, players.getSection(legacyKey).getAll());
        markPlayersDirty();
        getLogger().info("Migrated player data from legacy name key '" + legacyKey
                + "' to UUID profile for " + player.getName() + ".");
    }

    private int get(Player player, String field, int fallback) {
        return players.getInt(playerKey(player) + "." + field, fallback);
    }

    private void set(Player player, String field, int value) {
        players.set(playerKey(player) + "." + field, value);
        markPlayersDirty();
    }

    private String getRpgClass(Player player) {
        String value = players.getString(playerKey(player) + ".rpg_class", "none");
        return Arrays.asList(RPG_CLASSES).contains(value) ? value : "none";
    }

    private String rpgClassLabel(String id) {
        switch (id) {
            case "warrior": return "§cВоин";
            case "mage": return "§bМаг";
            case "archer": return "§aЛучник";
            case "assassin": return "§dАссасин";
            case "tank": return "§6Танк";
            default: return "§7Не выбран";
        }
    }

    private String rpgClassDescription(String id) {
        switch (id) {
            case "warrior": return "§7+2 к максимальному здоровью, +1 урон";
            case "mage": return "§7+15% к получаемому опыту";
            case "archer": return "§7+8% к скорости передвижения";
            case "assassin": return "§7+4% к скорости, 15% шанс нанести +3 урона";
            case "tank": return "§7+6 к максимальному здоровью, -5% скорости";
            default: return "§7Выбери один из пяти классов.";
        }
    }

    private void openClassMenu(Player player) {
        initialize(player);
        String current = getRpgClass(player);
        FormWindowSimple form = new FormWindowSimple("§6Выбор класса",
                current.equals("none")
                        ? "§fВыбери свой класс. §cПосле выбора сменить его можно только через оператора."
                        : "§fТвой класс: " + rpgClassLabel(current) + "\n" + rpgClassDescription(current)
                          + "\n\n§7Класс уже выбран. Для смены обратись к оператору.");
        if (current.equals("none")) {
            form.addButton(new ElementButton("§cВоин\n§7Здоровье и урон"));
            form.addButton(new ElementButton("§bМаг\n§7Больше опыта"));
            form.addButton(new ElementButton("§aЛучник\n§7Быстрое передвижение"));
            form.addButton(new ElementButton("§dАссасин\n§7Критические удары"));
            form.addButton(new ElementButton("§6Танк\n§7Повышенное здоровье"));
        } else {
            form.addButton(new ElementButton("§7Закрыть"));
        }
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            if (!current.equals("none")) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < RPG_CLASSES.length) {
                String chosen = RPG_CLASSES[selected];
                players.set(playerKey(player) + ".rpg_class", chosen);
                markPlayersDirty();
                if (!flushPlayers()) {
                    players.set(playerKey(player) + ".rpg_class", "none");
                    markPlayersDirty();
                    player.sendMessage("§cНе удалось сохранить класс. Попробуй ещё раз.");
                    return;
                }
                recalculateStats(player);
                updateSidebar(player);
                player.sendMessage("§aТвой класс: " + rpgClassLabel(chosen) + "§a. " + rpgClassDescription(chosen));
            }
        }));
        player.showFormWindow(form);
    }

    private boolean getBoolean(Player player, String field, boolean fallback) {
        return players.getBoolean(playerKey(player) + "." + field, fallback);
    }

    private void setBoolean(Player player, String field, boolean value) {
        players.set(playerKey(player) + "." + field, value);
        markPlayersDirty();
    }


    private void updateSidebar(Player player) {
        if (!getConfig().getBoolean("scoreboard.enabled", true)) return;
        String key = playerKey(player);
        IScoreboard scoreboard = sidebars.get(key);
        if (scoreboard == null) {
            String uuidSuffix = player.getUniqueId().toString().replace("-", "").substring(0, 12);
            String objective = "lf_" + uuidSuffix;
            scoreboard = new Scoreboard(objective,
                    getConfig().getString("scoreboard.title", "§6§lLOCALFAST"),
                    "dummy", SortOrder.DESCENDING);
            sidebars.put(key, scoreboard);
        }

        int level = Math.max(1, Math.min(MAX_LEVEL, get(player, "level", 1)));
        int xp = Math.max(0, get(player, "xp", 0));
        int nextXp = xpForNextLevel(level);
        int xpPercent = level >= MAX_LEVEL ? 100
                : (int) Math.min(100L, (long) xp * 100L / Math.max(1, nextXp));
        String health = player.getHealth() + "/" + player.getMaxHealth();
        int online = getServer().getOnlinePlayers().size();
        String serverTime = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));

        scoreboard.setLines(Arrays.asList(
                "§7" + player.getName(),
                "§8§m----------------",
                "§fКласс: " + rpgClassLabel(getRpgClass(player)),
                "§fУровень: §e" + level + "§7/§e" + MAX_LEVEL,
                level >= MAX_LEVEL ? "§6Максимальный уровень" : "§fОпыт: §a" + xp + "§7/§a" + nextXp,
                "§fПрогресс: " + (level >= MAX_LEVEL ? "§a■■■■■■■■■■ §e100%" : xpProgressBar(xp, nextXp)),
                "§cЗдоровье: §f" + health,
                "§cСила: §f" + get(player, "strength", 0),
                "§bОчки: §f" + get(player, "points", 0),
                "§8§m----------------",
                "§aОнлайн: §f" + online,
                "§eВремя: §f" + serverTime,
                getConfig().getString("scoreboard.footer", "§8play.localfast")
        ));
        if (!scoreboard.containViewer(player, DisplaySlot.SIDEBAR)) {
            scoreboard.addViewer(player, DisplaySlot.SIDEBAR);
        }
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();

        String mutePath = "punishments.mutes." + player.getUniqueId();
        long muteExpires = staffData.getLong(mutePath + ".expires", 0L);
        if (muteExpires == -1L || muteExpires > System.currentTimeMillis()) {
            event.setCancelled(true);
            player.sendMessage("§cТы не можешь писать в чат: у тебя мут.");
            return;
        }
        if (muteExpires != 0L) {
            staffData.remove(mutePath);
            staffData.save();
        }

        String role = staffRole(player);
        String roleText = staffChatTag(role);
        String chatLine = (roleText.isEmpty() ? "" : roleText + " ") +
                ("operator".equals(role) ? "§e" : "§f") + player.getName() +
                " §8» §f" + event.getMessage();

        // Полностью заменяем стандартный вывод чата одной готовой строкой.
        event.setCancelled(true);
        for (Player online : getServer().getOnlinePlayers().values()) {
            online.sendMessage(chatLine);
        }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        UUID quittingId = quittingPlayer.getUniqueId();
        pendingTeleports.remove(quittingId);
        pendingTeleports.entrySet().removeIf(entry -> entry.getValue().requester.getUniqueId().equals(quittingId));
        temporaryAbilityDamage.remove(quittingId);
        nextAbilityDamage.remove(quittingId);
        combatCombos.remove(quittingId);
        tankGuardUntil.remove(quittingId);
        String key = playerKey(event.getPlayer());
        IScoreboard scoreboard = sidebars.remove(key);
        if (scoreboard != null) scoreboard.removeViewer(event.getPlayer(), DisplaySlot.SIDEBAR);
        flushPlayers();
    }

    /**
     * Recalculate all derived effects from absolute saved attributes.
     * Increasing maximum HP does not heal the player: current HP is kept
     * at its existing absolute value and only clamped if it exceeds the new maximum.
     */
    private void recalculateStats(Player player) {
        String playerClass = getRpgClass(player);
        int classHealthBonus = playerClass.equals("warrior") ? 2 : playerClass.equals("tank") ? 6 : 0;
        int healthBonus = attributeBonus(player, "health",
                "attributes.health.points-per-bonus", 5,
                "attributes.health.hp-per-bonus", 1,
                "attributes.health.max-bonus", 20, 40);
        int maximumHealth = 20 + healthBonus + classHealthBonus;
        player.setMaxHealth(maximumHealth);
        // Increasing max HP does not heal; current HP is preserved in absolute points.
        if (player.getHealth() > maximumHealth) player.setHealth(maximumHealth);

        int agilityPercent = attributeBonus(player, "agility",
                "attributes.agility.points-per-bonus", 5,
                "attributes.agility.speed-percent-per-bonus", 1,
                "attributes.agility.max-bonus-percent", 25, 50);
        int classSpeedPercent = playerClass.equals("archer") ? 8
                : playerClass.equals("assassin") ? 4 : playerClass.equals("tank") ? -5 : 0;
        float movementSpeed = Player.DEFAULT_SPEED * (1.0f + (agilityPercent + classSpeedPercent) / 100.0f);
        player.setMovementSpeed(Math.max(Player.DEFAULT_SPEED * 0.75f,
                Math.min(Player.MAXIMUM_SPEED, movementSpeed)));
    }

    /**
     * Clamp configuration values to hard safety ceilings so a malformed or
     * overly permissive config cannot create unbounded combat/stat bonuses.
     */
    private int boundedConfigInt(String path, int fallback, int minimum, int hardMaximum) {
        int configured = getConfig().getInt(path, fallback);
        return Math.max(minimum, Math.min(hardMaximum, configured));
    }

    private int attributeBonus(Player player, String stat,
                               String pointsPerBonusPath, int defaultPointsPerBonus,
                               String bonusPerStepPath, int defaultBonusPerStep,
                               String maximumBonusPath, int defaultMaximumBonus,
                               int hardMaximumBonus) {
        int pointsPerBonus = boundedConfigInt(pointsPerBonusPath, defaultPointsPerBonus, 1, 10000);
        int bonusPerStep = boundedConfigInt(bonusPerStepPath, defaultBonusPerStep, 0, hardMaximumBonus);
        int maximumBonus = boundedConfigInt(maximumBonusPath, defaultMaximumBonus, 0, hardMaximumBonus);
        long steps = Math.max(0, get(player, stat, 0)) / (long) pointsPerBonus;
        long calculated = steps * bonusPerStep;
        return (int) Math.min((long) maximumBonus, Math.min((long) hardMaximumBonus, calculated));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        getServer().getScheduler().scheduleDelayedTask(this, () -> recalculateStats(player), 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();

        // Tank's finisher briefly creates a defensive stance against the next incoming hits.
        if (event.getEntity() instanceof Player) {
            Player defender = (Player) event.getEntity();
            UUID defenderId = defender.getUniqueId();
            long guardUntil = tankGuardUntil.getOrDefault(defenderId, 0L);
            if (guardUntil > now && getRpgClass(defender).equals("tank")) {
                int reduction = boundedConfigInt("combat.tank-guard-percent", 20, 0, 60);
                float baseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                event.setDamage(Math.max(0.0f, baseDamage * (100 - reduction) / 100.0f),
                        EntityDamageEvent.DamageModifier.BASE);
            } else if (guardUntil != 0L && guardUntil <= now) {
                tankGuardUntil.remove(defenderId);
            }
        }

        if (!(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        UUID attackerId = attacker.getUniqueId();
        String playerClass = getRpgClass(attacker);

        // Three-hit chain. A pause longer than the configured window starts a new combo.
        int comboWindow = boundedConfigInt("combat.combo-window-ms", 1600, 400, 5000);
        ComboState previous = combatCombos.get(attackerId);
        int stage = previous == null || now - previous.lastHitAt > comboWindow
                ? 1 : previous.stage >= 3 ? 1 : previous.stage + 1;
        combatCombos.put(attackerId, new ComboState(stage, now));

        int bonusDamage = attributeBonus(attacker, "strength",
                "attributes.strength.points-per-bonus", 5,
                "attributes.strength.damage-per-bonus", 1,
                "attributes.strength.max-bonus", 10, 20);
        if (playerClass.equals("warrior")) bonusDamage += 1;
        if (playerClass.equals("assassin") && ThreadLocalRandom.current().nextInt(100) < 15) {
            bonusDamage += 3;
            attacker.sendMessage("§d§lКРИТИЧЕСКИЙ УДАР!");
        }

        if (stage == 2) {
            bonusDamage += boundedConfigInt("combat.combo-second-hit-bonus", 1, 0, 5);
            attacker.sendMessage("§eКомбо §6x2§e — продолжай серию!");
        } else if (stage == 3) {
            bonusDamage += boundedConfigInt("combat.combo-finisher-bonus", 2, 0, 8);
            int classFinisherBonus;
            switch (playerClass) {
                case "warrior": classFinisherBonus = 2; break;
                case "mage": classFinisherBonus = 1; break;
                case "archer": classFinisherBonus = 2; break;
                case "assassin": classFinisherBonus = 4; break;
                case "tank": classFinisherBonus = 1; break;
                default: classFinisherBonus = 0;
            }
            bonusDamage += classFinisherBonus;
            attacker.sendTitle("§6§lКОМБО x3!", "§eФИНИШНЫЙ УДАР", 0, 20, 5);
            attacker.sendMessage("§6§lФИНИШЕР! §eДополнительный урон: +" + classFinisherBonus + ".");

            if (playerClass.equals("tank")) {
                int duration = boundedConfigInt("combat.tank-guard-duration-ms", 2000, 500, 5000);
                tankGuardUntil.put(attackerId, now + duration);
                attacker.sendMessage("§6Железная защита: §e-" +
                        boundedConfigInt("combat.tank-guard-percent", 20, 0, 60) +
                        "% входящего урона на " + (duration / 1000.0) + " сек.");
            }
        }

        bonusDamage += temporaryAbilityDamage.getOrDefault(attackerId, 0);
        bonusDamage += nextAbilityDamage.getOrDefault(attackerId, 0);
        nextAbilityDamage.remove(attackerId);
        if (bonusDamage > 0) {
            event.setDamage(event.getDamage(EntityDamageEvent.DamageModifier.BASE) + bonusDamage,
                    EntityDamageEvent.DamageModifier.BASE);
        }
    }

    private void initialize(Player player) {
        migrateLegacyPlayerData(player);
        String key = playerKey(player);
        String[] fields = {"level", "xp", "points", "strength", "health", "agility", "intelligence",
                "quest_kills"};
        int[] defaults = {1, 0, 0, 0, 0, 0, 0, 0};
        boolean changed = false;
        for (int i = 0; i < fields.length; i++) {
            String path = key + "." + fields[i];
            if (!players.exists(path)) {
                players.set(path, defaults[i]);
                changed = true;
            }
        }
        String savedName = players.getString(key + ".last_name", "");
        if (!player.getName().equals(savedName)) {
            players.set(key + ".last_name", player.getName());
            changed = true;
        }
        if (!players.exists(key + ".rpg_class")) {
            players.set(key + ".rpg_class", "none");
            changed = true;
        }
        if (!players.exists(key + ".balance")) {
            long startingBalance = Math.max(0L, parseConfiguredBalance());
            players.set(key + ".balance", Long.toString(startingBalance));
            changed = true;
        }
        if (!players.exists(key + ".daily_claimed")) { players.set(key + ".daily_claimed", false); changed = true; }
        if (!players.exists(key + ".quest_type")) { players.set(key + ".quest_type", "any"); changed = true; }
        if (!players.exists(key + ".quest_active")) {
            players.set(key + ".quest_active", false);
            changed = true;
        }
        if (!players.exists(key + ".quest_complete")) {
            players.set(key + ".quest_complete", false);
            changed = true;
        }
        int storedDataVersion = players.getInt(key + ".data_version", 0);
        if (storedDataVersion < CURRENT_PLAYER_DATA_VERSION) {
            // Additive migration only: existing RPG progress and quest fields are preserved.
            players.set(key + ".data_version", CURRENT_PLAYER_DATA_VERSION);
            changed = true;
        }
        if (changed) markPlayersDirty();
    }


    private List<AchievementDefinition> achievementCatalog() {
        return Arrays.asList(
                new AchievementDefinition("first_blood", "Первая кровь", "Победи первое существо", "kills", 1, 25, 10),
                new AchievementDefinition("hunter_25", "Охотник", "Победи 25 существ", "kills", 25, 100, 50),
                new AchievementDefinition("hunter_100", "Истребитель", "Победи 100 существ", "kills", 100, 300, 150),
                new AchievementDefinition("hunter_500", "Легенда охоты", "Победи 500 существ", "kills", 500, 1000, 500),
                new AchievementDefinition("level_10", "Начинающий герой", "Достигни 10 уровня", "level", 10, 150, 75),
                new AchievementDefinition("level_25", "Опытный герой", "Достигни 25 уровня", "level", 25, 400, 200),
                new AchievementDefinition("level_50", "Герой королевства", "Достигни 50 уровня", "level", 50, 1000, 500)
        );
    }

    private int achievementProgress(Player player, AchievementDefinition achievement) {
        if (achievement.metric.equals("kills")) {
            return Math.max(0, get(player, "achievement_kills", 0));
        }
        if (achievement.metric.equals("level")) {
            return Math.max(1, get(player, "level", 1));
        }
        return 0;
    }

    private void openAchievementsMenu(Player player) {
        initialize(player);
        List<AchievementDefinition> achievements = achievementCatalog();
        FormWindowSimple form = new FormWindowSimple("§6§lДОСТИЖЕНИЯ",
                "§fВыполняй цели, забирай награды и развивай своего персонажа!\n§7Нажми на выполненное достижение, чтобы получить награду.");
        for (AchievementDefinition achievement : achievements) {
            String path = playerKey(player) + ".achievements." + achievement.id;
            boolean claimed = players.getBoolean(path + ".claimed", false);
            int progress = Math.min(achievement.target, achievementProgress(player, achievement));
            String status = claimed ? "§8[Получено]" : progress >= achievement.target ? "§a[Забрать]" : "§e[В процессе]";
            form.addButton(new ElementButton(status + " §f" + achievement.title + "\n§7" + progress + "/" + achievement.target
                    + " §8| §a+" + achievement.xpReward + " XP §6+" + achievement.moneyReward + " монет"));
        }
        form.addButton(new ElementButton("§7Закрыть"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < achievements.size()) {
                claimAchievement(player, achievements.get(selected));
            }
        }));
        player.showFormWindow(form);
    }

    private void claimAchievement(Player player, AchievementDefinition achievement) {
        String path = playerKey(player) + ".achievements." + achievement.id;
        if (players.getBoolean(path + ".claimed", false)) {
            player.sendMessage("§7Эта награда уже получена.");
            openAchievementsMenu(player);
            return;
        }
        if (achievementProgress(player, achievement) < achievement.target) {
            player.sendMessage("§eДостижение ещё не выполнено: " + achievement.description + ".");
            openAchievementsMenu(player);
            return;
        }
        players.set(path + ".claimed", true);
        markPlayersDirty();
        if (!flushPlayers()) {
            players.set(path + ".claimed", false);
            markPlayersDirty();
            player.sendMessage("§cНе удалось сохранить достижение. Награда не выдана; попробуй позже.");
            return;
        }
        giveXp(player, Math.max(0, achievement.xpReward));
        if (achievement.moneyReward > 0L) setBalance(player, safeAdd(getBalance(player), achievement.moneyReward));
        flushPlayers();
        player.sendMessage("§6§lДОСТИЖЕНИЕ ПОЛУЧЕНО! §f" + achievement.title);
        player.sendMessage("§aНаграда: +" + achievement.xpReward + " XP и +" + achievement.moneyReward + " монет.");
        openAchievementsMenu(player);
    }

    private void showLeaderboard(CommandSender sender) {
        Map<String, Object> allProfiles = players.getAll();
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>();
        for (String key : allProfiles.keySet()) {
            if (!key.startsWith("uuid_")) continue;
            int level = Math.max(1, Math.min(MAX_LEVEL, players.getInt(key + ".level", 1)));
            ranking.add(new java.util.AbstractMap.SimpleEntry<>(key, level));
        }
        ranking.sort((left, right) -> {
            int byLevel = Integer.compare(right.getValue(), left.getValue());
            if (byLevel != 0) return byLevel;
            int leftXp = Math.max(0, players.getInt(left.getKey() + ".xp", 0));
            int rightXp = Math.max(0, players.getInt(right.getKey() + ".xp", 0));
            return Integer.compare(rightXp, leftXp);
        });

        sender.sendMessage("§6§l━━━━━━━━ ТОП ИГРОКОВ ━━━━━━━━");
        if (ranking.isEmpty()) {
            sender.sendMessage("§7Пока нет сохранённых персонажей.");
            return;
        }
        int shown = Math.min(10, ranking.size());
        for (int i = 0; i < shown; i++) {
            String key = ranking.get(i).getKey();
            String playerName = players.getString(key + ".last_name", "Неизвестный");
            int level = ranking.get(i).getValue();
            int xp = Math.max(0, players.getInt(key + ".xp", 0));
            String prefix = i == 0 ? "§6①" : i == 1 ? "§f②" : i == 2 ? "§c③" : "§7" + (i + 1) + ".";
            sender.sendMessage(prefix + " §f" + playerName + " §8— §eУр. " + level
                    + " §8| §a" + xp + " XP");
        }
        if (sender instanceof Player) {
            String ownKey = playerKey((Player) sender);
            for (int i = 0; i < ranking.size(); i++) {
                if (ranking.get(i).getKey().equals(ownKey)) {
                    sender.sendMessage("§7Твоё место: §e#" + (i + 1) + " §8из §f" + ranking.size());
                    break;
                }
            }
        }
        sender.sendMessage("§8Сортировка: уровень, затем текущий опыт.");
    }

    private String today() { return LocalDate.now().toString(); }

    // All players receive the same daily objective. The objective rotates by calendar day,
    // so reconnecting or restarting the server cannot reroll it.
    private String dailyTypeForDate(String date) {
        String[] types = {"zombie", "skeleton", "spider", "any"};
        long day = LocalDate.parse(date).toEpochDay();
        return types[(int) Math.floorMod(day, types.length)];
    }

    private String dailyType(Player player) {
        return players.getString(playerKey(player) + ".daily_type", dailyTypeForDate(today()));
    }

    private String dailyTargetLabel(String type) {
        switch (type) {
            case "zombie": return "зомби";
            case "skeleton": return "скелетов";
            case "spider": return "пауков";
            default: return "любых монстров";
        }
    }

    private boolean dailyMatches(String type, String mobName) {
        return type.equals("any") || mobName.contains(type);
    }

    private int dailyRequired(String type) {
        int fallback = type.equals("spider") ? 6 : type.equals("any") ? DAILY_KILLS_REQUIRED : 8;
        return Math.max(1, getConfig().getInt("daily.objectives." + type + ".kills",
                getConfig().getInt("daily.kills-required", fallback)));
    }

    private int dailyXpReward(String type) {
        return Math.max(0, getConfig().getInt("daily.objectives." + type + ".xp",
                getConfig().getInt("daily.xp-reward", DAILY_XP_REWARD)));
    }

    private long dailyMoneyReward(String type) {
        return Math.max(0L, getConfig().getLong("daily.objectives." + type + ".money",
                getConfig().getLong("daily.money-reward", 0L)));
    }

    private void refreshDaily(Player p) {
        initialize(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".daily_date","").equals(d)) {
            players.set(k+".daily_date",d);
            players.set(k+".daily_type",dailyTypeForDate(d));
            players.set(k+".daily_kills",0);
            players.set(k+".daily_active",false); players.set(k+".daily_complete",false);
            players.set(k+".daily_claimed",false); markPlayersDirty();
        } else if (!players.exists(k+".daily_type")) {
            players.set(k+".daily_type",dailyTypeForDate(d)); markPlayersDirty();
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer();
        String knownPath = "knownPlayers." + p.getName().toLowerCase(Locale.ROOT);
        staffData.set(knownPath + ".uuid", p.getUniqueId().toString());
        staffData.set(knownPath + ".name", p.getName());
        staffData.save();
        refreshStaffNameTag(p);
        if (flyingPlayers.contains(p.getUniqueId()) && staffRank(staffRole(p)) >= 7) p.setAllowFlight(true);
        else if (staffRank(staffRole(p)) < 7) flyingPlayers.remove(p.getUniqueId());
        for (UUID vanishedId : vanishedPlayers) {
            Player vanished = getServer().getPlayer(vanishedId).orElse(null);
            if (vanished != null && vanished.isOnline() && !vanished.getUniqueId().equals(p.getUniqueId())) p.hidePlayer(vanished);
        }
        if (vanishedPlayers.contains(p.getUniqueId())) {
            for (Player viewer : getServer().getOnlinePlayers().values()) if (!viewer.getUniqueId().equals(p.getUniqueId())) viewer.hidePlayer(p);
        }
        String banPath = "punishments.bans." + p.getUniqueId();
        long banExpires = staffData.getLong(banPath + ".expires", 0L);
        if (banExpires == -1L || banExpires > System.currentTimeMillis()) {
            String reason = staffData.getString(banPath + ".reason", "Нарушение правил");
            getServer().getScheduler().scheduleDelayedTask(this, () -> { if (p.isOnline()) p.kick("§cБан: " + reason, false); }, 1);
            return;
        } else if (banExpires != 0L) { staffData.remove(banPath); staffData.save(); }
        String role = staffRole(p);
        boolean personalWelcome = staffData.getBoolean("welcome.enabled." + p.getUniqueId(), true);
        if (staffRank(role) >= 1 && personalWelcome) {
            getServer().broadcastMessage("§b[STAFF] " + staffRoleColor(role) + "[" + staffRoleLabel(role)
                    + "] §f" + p.getName() + " §7зашёл на сервер!");
        }
        initialize(p); refreshDaily(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".login_reward_date","").equals(d)) {
            players.set(k+".login_reward_date",d); markPlayersDirty(); giveXp(p, Math.max(0, getConfig().getInt("progression.login-reward-xp", LOGIN_XP_REWARD)));
            p.sendMessage("§a[LocalFast] Награда за первый вход сегодня: +" + Math.max(0, getConfig().getInt("progression.login-reward-xp", LOGIN_XP_REWARD)) + " XP!");
        }
        recalculateStats(p);
        updateSidebar(p);
    }
    private void showDaily(Player p) {
        refreshDaily(p); String k=playerKey(p); String type=dailyType(p);
        int required=dailyRequired(type), reward=dailyXpReward(type);
        long money=dailyMoneyReward(type);
        p.sendMessage("§6[Ежедневный квест] §fУбей " + required + " " + dailyTargetLabel(type) + ".");
        if(players.getBoolean(k+".daily_complete",false)) p.sendMessage("§aВыполнено! Награда " + reward + " XP + " + money + " " + currencyName() + ": /daily claim");
        else if(players.getBoolean(k+".daily_claimed",false)) p.sendMessage("§7Сегодняшняя награда уже получена. Новое задание завтра.");
        else if(players.getBoolean(k+".daily_active",false)) p.sendMessage("§eПрогресс: "+players.getInt(k+".daily_kills",0)+"/"+required);
        else p.sendMessage("§eНаграда " + reward + " XP + " + money + " " + currencyName() + ". Начать: /daily start");
    }
    private void handleDaily(Player p,String[] args) {
        refreshDaily(p); String k=playerKey(p);
        if(args.length==0){showDaily(p);return;}
        String a=args[0].toLowerCase(Locale.ROOT);
        if(a.equals("start")){
            if(players.getBoolean(k+".daily_active",false)||players.getBoolean(k+".daily_complete",false)||players.getBoolean(k+".daily_claimed",false)){showDaily(p);return;}
            players.set(k+".daily_active",true);players.set(k+".daily_kills",0);markPlayersDirty();
            String type=dailyType(p); int required=dailyRequired(type);
            p.sendMessage("§aНачато! Убей " + required + " " + dailyTargetLabel(type) + ".");return;
        }
        if(a.equals("claim")){
            if(players.getBoolean(k+".daily_claimed",false) || !players.getBoolean(k+".daily_complete",false)){
                p.sendMessage("§cЗадание ещё не выполнено или награда уже получена.");return;
            }
            // Persist the claimed state through the centralized save queue before granting XP.
            players.set(k+".daily_complete",false);
            players.set(k+".daily_active",false);
            players.set(k+".daily_claimed",true);
            String claimDate = today();
            String previousClaimDate = players.getString(k + ".daily_last_claim", "");
            int currentStreak = previousClaimDate.equals(LocalDate.now().minusDays(1).toString())
                    ? Math.max(0, players.getInt(k + ".daily_streak", 0)) + 1 : 1;
            players.set(k + ".daily_last_claim", claimDate);
            players.set(k + ".daily_streak", currentStreak);
            markPlayersDirty();
            if (!flushPlayers()) {
                players.set(k + ".daily_complete", true);
                players.set(k + ".daily_active", true);
                players.set(k + ".daily_claimed", false);
                markPlayersDirty();
                p.sendMessage("§cНе удалось сохранить награду. XP не начислен; попробуй позже.");
                return;
            }
            String type=dailyType(p);
            int reward=dailyXpReward(type);
            long money=dailyMoneyReward(type);
            giveXp(p,reward);
            if (money > 0L) setBalance(p, safeAdd(getBalance(p), money));
            int streakBonus = Math.max(0, getConfig().getInt("daily.streak-bonus-xp." + currentStreak,
                    currentStreak >= 14 ? 500 : currentStreak >= 7 ? 200 : currentStreak >= 3 ? 50 : 0));
            if (streakBonus > 0) giveXp(p, streakBonus);
            flushPlayers();
            p.sendMessage("§aПолучено +" + reward + " XP и +" + money + " " + currencyName()
                    + "! §eСерия: " + currentStreak + " дн."
                    + (streakBonus > 0 ? " §6Бонус серии: +" + streakBonus + " XP!" : ""));return;
        }
        p.sendMessage("§e/daily, /daily start или /daily claim");
    }


    private void handleHome(Player player, String command) {
        initialize(player);
        String key = playerKey(player);
        String homePath = key + ".home";
        if (command.equals("sethome")) {
            players.set(homePath + ".world", player.getLevel().getFolderName());
            players.set(homePath + ".x", player.getX());
            players.set(homePath + ".y", player.getY());
            players.set(homePath + ".z", player.getZ());
            markPlayersDirty();
            player.sendMessage("§a[LocalFast] Дом сохранён. Вернуться: /home");
            return;
        }
        if (command.equals("delhome")) {
            if (!players.exists(homePath)) {
                player.sendMessage("§c[LocalFast] У тебя ещё нет дома. Сначала используй /sethome.");
                return;
            }
            players.remove(homePath);
            markPlayersDirty();
            player.sendMessage("§e[LocalFast] Точка дома удалена.");
            return;
        }
        if (!players.exists(homePath + ".world")) {
            player.sendMessage("§c[LocalFast] Дом не установлен. Используй /sethome.");
            return;
        }
        String worldName = players.getString(homePath + ".world", "");
        Level world = loadHomeWorld(worldName);
        if (world == null) {
            player.sendMessage("§c[LocalFast] Мир твоего дома '" + worldName + "' не найден. Дом не удалён.");
            return;
        }
        double x = players.getDouble(homePath + ".x");
        double y = players.getDouble(homePath + ".y");
        double z = players.getDouble(homePath + ".z");
        Position destination = new Position(x, y, z, world);
        if (player.teleport(destination)) {
            player.sendMessage("§a[LocalFast] Ты телепортирован домой.");
        } else {
            player.sendMessage("§c[LocalFast] Не удалось телепортировать тебя домой.");
        }
    }

    private Level loadHomeWorld(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) return null;
        if (!getServer().isLevelLoaded(worldName) && !getServer().loadLevel(worldName)) return null;
        return getServer().getLevelByName(worldName);
    }

    private void handleTpa(Player player, String command, String[] args) {
        UUID playerId = player.getUniqueId();
        if (command.equals("tpa")) {
            if (args.length != 1) {
                player.sendMessage("§eИспользование: /tpa <игрок>");
                return;
            }
            Player target = getServer().getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                player.sendMessage("§c[LocalFast] Игрок не найден или не в сети.");
                return;
            }
            if (target.getUniqueId().equals(playerId)) {
                player.sendMessage("§c[LocalFast] Нельзя отправить запрос самому себе.");
                return;
            }
            UUID targetId = target.getUniqueId();
            PendingTeleport pending = new PendingTeleport(player, System.currentTimeMillis() + 60_000L);
            PendingTeleport replaced = pendingTeleports.put(targetId, pending);
            if (replaced != null && replaced.requester.isOnline()) {
                replaced.requester.sendMessage("§e[LocalFast] Твой запрос заменён более новым запросом к этому игроку.");
            }
            target.sendMessage("§6[LocalFast] Игрок §e" + player.getName()
                    + " §6хочет телепортироваться к тебе.");
            target.sendMessage("§a/tpaccept §7— принять, §c/tpdeny §7— отклонить (60 секунд).");
            player.sendMessage("§a[LocalFast] Запрос отправлен игроку " + target.getName() + ". Он действует 60 секунд.");
            getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (pendingTeleports.get(targetId) == pending) {
                    pendingTeleports.remove(targetId);
                    if (pending.requester.isOnline()) {
                        pending.requester.sendMessage("§e[LocalFast] Запрос телепортации истёк.");
                    }
                    if (target.isOnline()) target.sendMessage("§e[LocalFast] Запрос телепортации истёк.");
                }
            }, 60 * 20);
            return;
        }
        if (args.length != 0) {
            player.sendMessage(command.equals("tpaccept") ? "§eИспользование: /tpaccept" : "§eИспользование: /tpdeny");
            return;
        }
        PendingTeleport pending = pendingTeleports.get(playerId);
        if (pending == null) {
            player.sendMessage("§c[LocalFast] У тебя нет ожидающих запросов.");
            return;
        }
        pendingTeleports.remove(playerId);
        if (pending.expiresAt <= System.currentTimeMillis() || !pending.requester.isOnline()) {
            player.sendMessage("§c[LocalFast] Запрос уже истёк или игрок вышел с сервера.");
            return;
        }
        if (command.equals("tpdeny")) {
            player.sendMessage("§e[LocalFast] Запрос отклонён.");
            pending.requester.sendMessage("§c[LocalFast] Игрок " + player.getName() + " отклонил запрос.");
            return;
        }
        Position destination = new Position(player.getX(), player.getY(), player.getZ(), player.getLevel());
        if (pending.requester.teleport(destination)) {
            player.sendMessage("§a[LocalFast] Запрос принят.");
            pending.requester.sendMessage("§a[LocalFast] " + player.getName() + " принял запрос. Телепортация выполнена.");
        } else {
            player.sendMessage("§c[LocalFast] Не удалось выполнить телепортацию.");
            pending.requester.sendMessage("§c[LocalFast] Не удалось телепортироваться к игроку.");
        }
    }

    private int xpForNextLevel(int level) {
        int safeLevel = Math.max(1, Math.min(MAX_LEVEL, level));
        long base = Math.max(1, getConfig().getInt("progression.xp-base", 100));
        long perLevel = Math.max(0, getConfig().getInt("progression.xp-per-level", 50));
        long required = base + (long) (safeLevel - 1) * perLevel;
        return (int) Math.min(Integer.MAX_VALUE, required);
    }

    private String xpProgressBar(int currentXp, int requiredXp) {
        int safeRequired = Math.max(1, requiredXp);
        int percent = (int) Math.min(100L, Math.max(0L, currentXp) * 100L / safeRequired);
        int filled = percent / 10;
        return "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled) + " §e" + percent + "%";
    }

    private int intelligenceXpBonusPercent(Player player) {
        int legacyStep = Math.max(1, getConfig().getInt("intelligence.xp-bonus-per", 5));
        int legacyPercent = Math.max(0, getConfig().getInt("intelligence.xp-bonus-percent", 1));
        return attributeBonus(player, "intelligence",
                "attributes.intelligence.points-per-bonus", legacyStep,
                "attributes.intelligence.xp-bonus-percent-per-bonus", legacyPercent,
                "attributes.intelligence.max-bonus-percent", 50, 100);
    }

    private void giveXp(Player player, int amount) {
        initialize(player);
        if (amount <= 0) return;
        int level = Math.max(1, Math.min(MAX_LEVEL, get(player, "level", 1)));
        int intelligenceBonus = intelligenceXpBonusPercent(player);
        if (getRpgClass(player).equals("mage")) intelligenceBonus += 15;
        long boostedAmount = (long) amount + ((long) amount * intelligenceBonus / 100L);
        long totalXp = (long) Math.max(0, get(player, "xp", 0)) + boostedAmount;
        int points = Math.max(0, get(player, "points", 0));

        while (level < MAX_LEVEL && totalXp >= xpForNextLevel(level)) {
            totalXp -= xpForNextLevel(level);
            level++;
            int pointsPerLevel = Math.max(0, getConfig().getInt("progression.points-per-level", POINTS_PER_LEVEL));
            points = (int) Math.min(Integer.MAX_VALUE, (long) points + pointsPerLevel);
            if (getConfig().getBoolean("effects.level-up-title", true)) {
                player.sendTitle(getConfig().getString("effects.level-up-title-text", "§6УРОВЕНЬ ПОВЫШЕН!"),
                        getConfig().getString("effects.level-up-subtitle", "§eУровень " + level), 10, 50, 15);
            }
            player.sendMessage("§a[LocalFast] Новый уровень: §e" + level + " §a( +" + pointsPerLevel + " очков характеристик )");
        }

        if (level == MAX_LEVEL) totalXp = Math.min(totalXp, xpForNextLevel(MAX_LEVEL) - 1L);
        set(player, "level", level);
        set(player, "xp", (int) totalXp);
        set(player, "points", points);
        markPlayersDirty();
        updateSidebar(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobKilled(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof EntityCreature)) return;

        EntityCreature mob = (EntityCreature) event.getEntity();
        if (event.getFinalDamage() < mob.getHealth()) return;

        Player killer = (Player) event.getDamager();
        initialize(killer);
        int mobXp = Math.max(0, getConfig().getInt("progression.mob-kill-xp", MOB_KILL_XP));
        giveXp(killer, mobXp);
        set(killer, "achievement_kills", Math.min(Integer.MAX_VALUE, get(killer, "achievement_kills", 0) + 1));
        int xpBonusPercent = intelligenceXpBonusPercent(killer);
        killer.sendMessage("§a[LocalFast] +" + mobXp + " базового XP за победу над существом"
                + (xpBonusPercent > 0 ? " §b(интеллект: +" + xpBonusPercent + "%)" : "") + "§a!");


        String mobName = mob.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        refreshDaily(killer); String dk=playerKey(killer);
        if(players.getBoolean(dk+".daily_active",false)&&!players.getBoolean(dk+".daily_complete",false)){
            String type=dailyType(killer);
            if(dailyMatches(type,mobName)){
                int required=dailyRequired(type);
                int n=Math.min(required,Math.max(0,players.getInt(dk+".daily_kills",0))+1);players.set(dk+".daily_kills",n);
                if(n>=required){players.set(dk+".daily_complete",true);killer.sendMessage("§6Ежедневный квест выполнен! /daily claim");}
                else killer.sendMessage("§eЕжедневный квест: "+n+"/"+required);
                markPlayersDirty();
            }
        }
        updateLevelQuests(killer, mobName);
        updateWeeklyProgress(killer);
        updateQuestChainProgress(killer, mobName);
        if (getBoolean(killer, "quest_active", false) && !getBoolean(killer, "quest_complete", false)) {
            String questType = players.getString(playerKey(killer) + ".quest_type", "any");
            boolean matches = questType.equals("any")
                    || (questType.equals("zombie") && mobName.contains("zombie"))
                    || (questType.equals("skeleton") && mobName.contains("skeleton"))
                    || (questType.equals("spider") && mobName.contains("spider"));
            if (matches) {
                int requiredKills = questKillsRequired();
                int kills = Math.min(requiredKills, Math.max(0, get(killer, "quest_kills", 0)) + 1);
                set(killer, "quest_kills", kills);
                if (kills >= requiredKills) {
                    setBoolean(killer, "quest_complete", true);
                    killer.sendMessage("§6[LocalFast] Задание выполнено! Используй §e/quest claim §6, чтобы получить награду.");
                } else {
                    killer.sendMessage("§e[Квест] " + kills + "/" + requiredKills);
                }
                markPlayersDirty();
            }
        }
    }

    private void updateLevelQuests(Player player, String mobName) {
        String key = playerKey(player);
        boolean changed = false;
        for (QuestDefinition quest : questCatalog()) {
            String path = key + ".quests." + quest.id;
            if (!players.getBoolean(path + ".active", false) || players.getBoolean(path + ".complete", false)) continue;
            boolean matches = quest.mobType.equals("any") || mobName.contains(quest.mobType);
            if (!matches) continue;
            int required = questKills(quest);
            int progress = Math.min(required, Math.max(0, players.getInt(path + ".kills", 0)) + 1);
            players.set(path + ".kills", progress);
            if (progress >= required) {
                players.set(path + ".complete", true);
                player.sendMessage("§6[Квесты] Выполнено: §e" + quest.title + "§6! Забери награду в /quests.");
            } else {
                player.sendMessage("§e[Квесты] " + quest.title + ": " + progress + "/" + required);
            }
            changed = true;
        }
        if (changed) markPlayersDirty();
    }

    private void openQuestsMenu(Player player) {
        initialize(player);
        int level = Math.max(1, get(player, "level", 1));
        FormWindowSimple form = new FormWindowSimple("§6LocalFastCore §8| §lКвесты",
                "§fТвой уровень: §e" + level + "\n§7Выбери диапазон уровней или посмотри свои задания.");
        String[] ranges = {"1–10", "11–30", "31–50", "51–75", "76–100"};
        for (String range : ranges) form.addButton(new ElementButton("§eУровни " + range));
        form.addButton(new ElementButton("§aМои задания"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < 5) openQuestTierMenu(player, selected);
            else if (selected == 5) openMyQuestsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openQuestTierMenu(Player player, int tier) {
        int[][] ranges = {{1,10},{11,30},{31,50},{51,75},{76,100}};
        if (tier < 0 || tier >= ranges.length) {
            openQuestsMenu(player);
            return;
        }
        int min = ranges[tier][0], max = ranges[tier][1];
        FormWindowSimple form = new FormWindowSimple("§6Квесты §8| §e" + min + "–" + max,
                "§fТвой уровень: §e" + get(player, "level", 1) + "\n§7Выбери задание для просмотра.");
        List<QuestDefinition> quests = new ArrayList<>();
        for (QuestDefinition quest : questCatalog()) {
            if (quest.minLevel == min) {
                quests.add(quest);
                String path = playerKey(player) + ".quests." + quest.id;
                String status = players.getBoolean(path + ".claimed", false) ? "§8[Получено]"
                        : players.getBoolean(path + ".complete", false) ? "§a[Готово]"
                        : players.getBoolean(path + ".active", false) ? "§e[В процессе]"
                        : "§7[Доступен]";
                form.addButton(new ElementButton(status + " §f" + quest.title));
            }
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < quests.size()) openQuestDetails(player, quests.get(selected), tier);
            else openQuestsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openQuestDetails(Player player, QuestDefinition quest, int tier) {
        String path = playerKey(player) + ".quests." + quest.id;
        boolean active = players.getBoolean(path + ".active", false);
        boolean complete = players.getBoolean(path + ".complete", false);
        boolean claimed = players.getBoolean(path + ".claimed", false);
        int progress = Math.max(0, players.getInt(path + ".kills", 0));
        int reward = questXp(quest);
        long moneyReward = questMoney(quest);
        FormWindowSimple form = new FormWindowSimple("§6Квест §8| §e" + quest.title,
                "§f" + quest.description + "\n§fПрогресс: §a" + progress + "/" + questKills(quest)
                        + "\n§fНаграда: §6" + reward + " XP §8+ §a" + moneyReward + " " + currencyName()
                        + "\n§fТребуемый уровень: §e" + quest.minLevel + "–" + quest.maxLevel
                        + (claimed ? "\n§8Награда уже получена." : complete ? "\n§aЗадание выполнено!" : ""));
        if (!claimed) {
            if (complete) form.addButton(new ElementButton("§aПолучить награду"));
            else if (!active) form.addButton(new ElementButton("§aПринять задание"));
            else form.addButton(new ElementButton("§eЗадание активно"));
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();

            // Form state may be stale when the player has multiple quest windows open.
            // Always re-read the persisted state at click time before changing or rewarding it.
            boolean currentActive = players.getBoolean(path + ".active", false);
            boolean currentComplete = players.getBoolean(path + ".complete", false);
            boolean currentClaimed = players.getBoolean(path + ".claimed", false);

            if (selected != 0 || currentClaimed || (currentActive && !currentComplete)) {
                openQuestTierMenu(player, tier);
                return;
            }

            if (currentActive && currentComplete) {
                int currentReward = questXp(quest);
                // Persist the claimed state before granting XP so another open form
                // or a later reconnect cannot grant this reward a second time.
                players.set(path + ".active", false);
                players.set(path + ".complete", false);
                players.set(path + ".claimed", true);
                markPlayersDirty();
                if (!flushPlayers()) {
                    players.set(path + ".active", true);
                    players.set(path + ".complete", true);
                    players.set(path + ".claimed", false);
                    markPlayersDirty();
                    player.sendMessage("§cНе удалось сохранить получение награды. XP не начислен; попробуй позже.");
                    return;
                }
                giveXp(player, currentReward);
                long coins = questMoney(quest);
                if (coins > 0L) setBalance(player, safeAdd(getBalance(player), coins));
                flushPlayers();
                player.sendMessage("§a[Квесты] Получено +" + currentReward + " XP и +" + coins + " " + currencyName() + " за " + quest.title + "!");
            } else if (!currentActive && !currentClaimed) {
                int level = get(player, "level", 1);
                if (level < quest.minLevel || level > quest.maxLevel) {
                    player.sendMessage("§cЭтот квест пока недоступен по уровню.");
                } else {
                    players.set(path + ".active", true);
                    players.set(path + ".kills", 0);
                    players.set(path + ".complete", false);
                    players.set(path + ".claimed", false);
                    markPlayersDirty();
                    player.sendMessage("§a[Квесты] Принято задание: " + quest.title);
                }
            }
            openQuestTierMenu(player, tier);
        }));
        player.showFormWindow(form);
    }

    private void openMyQuestsMenu(Player player) {
        String key = playerKey(player);
        FormWindowSimple form = new FormWindowSimple("§6Мои задания", "§7Активные и завершённые задания:");
        List<QuestDefinition> entries = new ArrayList<>();
        for (QuestDefinition quest : questCatalog()) {
            String path = key + ".quests." + quest.id;
            if (players.getBoolean(path + ".active", false) || players.getBoolean(path + ".complete", false)) {
                entries.add(quest);
                int progress = players.getInt(path + ".kills", 0);
                String status = players.getBoolean(path + ".complete", false) ? "§a[Готово] " : "§e";
                form.addButton(new ElementButton(status + quest.title + " §7" + progress + "/" + questKills(quest)));
            }
        }
        if (entries.isEmpty()) form.addButton(new ElementButton("§7Пока нет активных заданий"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (!entries.isEmpty() && selected >= 0 && selected < entries.size()) {
                QuestDefinition quest = entries.get(selected);
                int tier = quest.minLevel == 1 ? 0 : quest.minLevel == 11 ? 1 : quest.minLevel == 31 ? 2 : quest.minLevel == 51 ? 3 : 4;
                openQuestDetails(player, quest, tier);
            } else openQuestsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private int questKillsRequired() {
        return Math.max(1, getConfig().getInt("quests.kills-required", QUEST_KILLS_REQUIRED));
    }

    private int questReward(String type) {
        String safeType = Arrays.asList("zombie", "skeleton", "spider").contains(type) ? type : "any";
        int fallback = safeType.equals("zombie") ? 50
                : safeType.equals("skeleton") ? 60
                : safeType.equals("spider") ? 70 : QUEST_XP_REWARD;
        return Math.max(0, getConfig().getInt("quests.rewards." + safeType, fallback));
    }

    private String questTitle(String type) {
        if (type.equals("zombie")) return "Охотник на зомби";
        if (type.equals("skeleton")) return "Охотник на скелетов";
        if (type.equals("spider")) return "Охотник на пауков";
        return "Охотник на любых существ";
    }

    private void showQuest(Player player) {
        initialize(player);
        if (!getBoolean(player, "quest_active", false)) {
            player.sendMessage("§6[Квесты] Доступные задания:");
            int required = questKillsRequired();
            player.sendMessage("§e/quest zombie §f— " + required + " зомби, награда " + questReward("zombie") + " XP");
            player.sendMessage("§e/quest skeleton §f— " + required + " скелетов, награда " + questReward("skeleton") + " XP");
            player.sendMessage("§e/quest spider §f— " + required + " пауков, награда " + questReward("spider") + " XP");
            player.sendMessage("§7Старое задание: /quest start");
            return;
        }
        String type = players.getString(playerKey(player) + ".quest_type", "any");
        if (getBoolean(player, "quest_complete", false)) {
            player.sendMessage("§a[Квест] " + questTitle(type) + " выполнено!");
            player.sendMessage("§eЗабрать " + questReward(type) + " XP: /quest claim");
        } else {
            player.sendMessage("§6[Квест] §f" + questTitle(type));
            player.sendMessage("§eПрогресс: " + get(player, "quest_kills", 0) + "/" + questKillsRequired());
            player.sendMessage("§7Награда: " + questReward(type) + " XP");
        }
    }


    private String weekKey() { return LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString(); }
    private void refreshWeekly(Player p) {
        String k=playerKey(p), w=weekKey();
        if (!players.getString(k+".weekly.date","").equals(w)) {
            players.set(k+".weekly.date",w); players.set(k+".weekly.kills",0);
            players.set(k+".weekly.active",false); players.set(k+".weekly.complete",false);
            players.set(k+".weekly.claimed",false); markPlayersDirty();
        }
    }
    private void showWeekly(Player p) {
        refreshWeekly(p); String k=playerKey(p);
        int need=Math.max(1,getConfig().getInt("weekly.kills-required",100));
        p.sendMessage("§6[Еженедельное испытание] §fПобеди "+need+" любых монстров.");
        p.sendMessage("§eПрогресс: "+players.getInt(k+".weekly.kills",0)+"/"+need);
        if(players.getBoolean(k+".weekly.claimed",false)) p.sendMessage("§7Награда за неделю уже получена.");
        else if(players.getBoolean(k+".weekly.complete",false)) p.sendMessage("§aВыполнено! /weekly claim");
        else if(!players.getBoolean(k+".weekly.active",false)) p.sendMessage("§eНачать: /weekly start");
    }
    private void handleWeekly(Player p,String[] args) {
        refreshWeekly(p); String k=playerKey(p);
        if(args.length==0){showWeekly(p);return;}
        String a=args[0].toLowerCase(Locale.ROOT);
        if(a.equals("start")) {
            if(players.getBoolean(k+".weekly.claimed",false)){showWeekly(p);return;}
            players.set(k+".weekly.active",true);markPlayersDirty();p.sendMessage("§aЕженедельное испытание начато!");showWeekly(p);return;
        }
        if(a.equals("claim")) {
            if(!players.getBoolean(k+".weekly.complete",false)||players.getBoolean(k+".weekly.claimed",false)){p.sendMessage("§cИспытание ещё не выполнено.");return;}
            int xp=Math.max(0,getConfig().getInt("weekly.xp-reward",750));
            long money=Math.max(0L,getConfig().getLong("weekly.money-reward",300L));
            players.set(k+".weekly.complete",false);players.set(k+".weekly.active",false);players.set(k+".weekly.claimed",true);markPlayersDirty();
            if(!flushPlayers()){players.set(k+".weekly.complete",true);players.set(k+".weekly.active",true);players.set(k+".weekly.claimed",false);markPlayersDirty();p.sendMessage("§cНе удалось сохранить награду.");return;}
            giveXp(p,xp);if(money>0)setBalance(p,safeAdd(getBalance(p),money));flushPlayers();
            p.sendMessage("§aНаграда: +"+xp+" XP и +"+money+" "+currencyName()+"!");return;
        }
        p.sendMessage("§e/weekly [start|claim]");
    }
    private static final String[] CHAIN_TYPES={"zombie","skeleton","spider"};
    private static final String[] CHAIN_LABELS={"зомби","скелетов","пауков"};
    private int chainKills(int stage){String t=CHAIN_TYPES[Math.max(0,Math.min(2,stage))];int d=stage==0?10:stage==1?12:15;return Math.max(1,getConfig().getInt("quest-chain.stages."+t+".kills",d));}
    private int chainXp(int stage){String t=CHAIN_TYPES[Math.max(0,Math.min(2,stage))];int d=stage==0?150:stage==1?200:300;return Math.max(0,getConfig().getInt("quest-chain.stages."+t+".xp",d));}
    private long chainMoney(int stage){String t=CHAIN_TYPES[Math.max(0,Math.min(2,stage))];long d=stage==0?50:stage==1?75:150;return Math.max(0L,getConfig().getLong("quest-chain.stages."+t+".money",d));}
    private void showQuestChain(Player p){
        String k=playerKey(p);int stage=Math.max(0,Math.min(3,players.getInt(k+".quest_chain.stage",0)));
        if(stage>=3){p.sendMessage("§6[Цепочка] §aВсе три этапа пройдены!");return;}
        int need=chainKills(stage);p.sendMessage("§6[Цепочка] §fЭтап "+(stage+1)+"/3: победи "+need+" "+CHAIN_LABELS[stage]+".");
        p.sendMessage("§eПрогресс: "+players.getInt(k+".quest_chain.kills",0)+"/"+need+" §8| §a+"+chainXp(stage)+" XP §8+ §6"+chainMoney(stage)+" "+currencyName());
        if(players.getBoolean(k+".quest_chain.complete",false))p.sendMessage("§aЭтап выполнен! /questchain claim");
        else if(!players.getBoolean(k+".quest_chain.active",false))p.sendMessage("§eНачать: /questchain start");
    }
    private void handleQuestChain(Player p,String[] args){
        String k=playerKey(p);if(args.length==0){showQuestChain(p);return;}
        String a=args[0].toLowerCase(Locale.ROOT);
        if(a.equals("start")){int st=players.getInt(k+".quest_chain.stage",0);if(st>=3||players.getBoolean(k+".quest_chain.complete",false)){showQuestChain(p);return;}players.set(k+".quest_chain.active",true);markPlayersDirty();p.sendMessage("§aЦепочка заданий начата!");showQuestChain(p);return;}
        if(a.equals("claim")){
            int st=Math.max(0,Math.min(2,players.getInt(k+".quest_chain.stage",0)));
            if(!players.getBoolean(k+".quest_chain.complete",false)){p.sendMessage("§cСначала выполни текущий этап.");return;}
            int xp=chainXp(st);long money=chainMoney(st);
            players.set(k+".quest_chain.stage",st+1);players.set(k+".quest_chain.kills",0);players.set(k+".quest_chain.complete",false);players.set(k+".quest_chain.active",st+1<3);markPlayersDirty();
            if(!flushPlayers()){players.set(k+".quest_chain.stage",st);players.set(k+".quest_chain.kills",chainKills(st));players.set(k+".quest_chain.complete",true);players.set(k+".quest_chain.active",true);markPlayersDirty();p.sendMessage("§cНе удалось сохранить этап. Награда не выдана.");return;}
            giveXp(p,xp);if(money>0)setBalance(p,safeAdd(getBalance(p),money));flushPlayers();
            p.sendMessage("§aЭтап "+(st+1)+": +"+xp+" XP и +"+money+" "+currencyName()+"!");
            if(st==2)p.sendMessage("§6§lПоздравляем! §eЦепочка полностью пройдена.");else showQuestChain(p);return;
        }
        p.sendMessage("§e/questchain [start|claim]");
    }
    private void updateWeeklyProgress(Player p){
        refreshWeekly(p);String k=playerKey(p);if(!players.getBoolean(k+".weekly.active",false)||players.getBoolean(k+".weekly.complete",false))return;
        int need=Math.max(1,getConfig().getInt("weekly.kills-required",100));int n=Math.min(need,players.getInt(k+".weekly.kills",0)+1);players.set(k+".weekly.kills",n);
        if(n>=need){players.set(k+".weekly.complete",true);p.sendMessage("§6Еженедельное испытание выполнено! /weekly claim");}
        else if(n%10==0)p.sendMessage("§eЕженедельный прогресс: "+n+"/"+need);markPlayersDirty();
    }
    private void updateQuestChainProgress(Player p,String mob){
        String k=playerKey(p);if(!players.getBoolean(k+".quest_chain.active",false)||players.getBoolean(k+".quest_chain.complete",false))return;
        int st=players.getInt(k+".quest_chain.stage",0);if(st<0||st>=3||!mob.contains(CHAIN_TYPES[st]))return;
        int need=chainKills(st),n=Math.min(need,players.getInt(k+".quest_chain.kills",0)+1);players.set(k+".quest_chain.kills",n);
        if(n>=need){players.set(k+".quest_chain.complete",true);p.sendMessage("§6Этап цепочки выполнен! /questchain claim");}
        else p.sendMessage("§eЦепочка "+(st+1)+"/3: "+n+"/"+need);markPlayersDirty();
    }

    private void handleQuest(Player player, String[] args) {
        initialize(player);
        if (args.length == 0) { openQuestsMenu(player); return; }

        String choice = args[0].toLowerCase(Locale.ROOT);
        if (choice.equals("zombie") || choice.equals("skeleton") || choice.equals("spider") || choice.equals("start")) {
            if (getBoolean(player, "quest_active", false)) {
                player.sendMessage("§c[Квест] Сначала заверши или забери награду за текущее задание.");
                showQuest(player);
                return;
            }
            String type = choice.equals("start") ? "any" : choice;
            players.set(playerKey(player) + ".quest_type", type);
            set(player, "quest_kills", 0);
            setBoolean(player, "quest_active", true);
            setBoolean(player, "quest_complete", false);
            markPlayersDirty();
            player.sendMessage("§a[Квест] Начато: " + questTitle(type) + ". Убей " + questKillsRequired() + " нужных мобов!");
            showQuest(player);
            return;
        }
        if (choice.equals("claim")) {
            if (!getBoolean(player, "quest_active", false) || !getBoolean(player, "quest_complete", false)) {
                player.sendMessage("§c[Квест] Сначала выполни задание.");
                return;
            }
            String type = players.getString(playerKey(player) + ".quest_type", "any");
            int reward = questReward(type);
            int completedKills = get(player, "quest_kills", 0);
            setBoolean(player, "quest_active", false);
            setBoolean(player, "quest_complete", false);
            set(player, "quest_kills", 0);
            players.set(playerKey(player) + ".quest_type", "any");
            markPlayersDirty();
            if (!flushPlayers()) {
                setBoolean(player, "quest_active", true);
                setBoolean(player, "quest_complete", true);
                set(player, "quest_kills", completedKills);
                players.set(playerKey(player) + ".quest_type", type);
                markPlayersDirty();
                player.sendMessage("§cНе удалось сохранить получение награды. XP не начислен; попробуй позже.");
                return;
            }
            giveXp(player, reward);
            player.sendMessage("§a[Квест] Награда получена: +" + reward + " опыта!");
            return;
        }
        player.sendMessage("§eИспользуй: /quest, /quest zombie, /quest skeleton, /quest spider или /quest claim");
    }

    private String abilityName(String playerClass) {
        switch (playerClass) {
            case "warrior": return "Боевой натиск";
            case "mage": return "Магическое восстановление";
            case "archer": return "Стремительный шаг";
            case "assassin": return "Смертельный выпад";
            case "tank": return "Железная стойка";
            default: return "Нет способности";
        }
    }

    private String abilityDescription(String playerClass) {
        switch (playerClass) {
            case "warrior": return "§7На 8 секунд увеличивает урон каждого удара на 3.";
            case "mage": return "§7Мгновенно восстанавливает до 8 единиц здоровья.";
            case "archer": return "§7На 8 секунд увеличивает скорость передвижения на 20%.";
            case "assassin": return "§7Следующий удар наносит дополнительно 7 единиц урона.";
            case "tank": return "§7На 8 секунд повышает максимум здоровья на 6 и лечит на 6.";
            default: return "§7Сначала выбери класс через /class.";
        }
    }

    private long abilityCooldownRemaining(Player player) {
        long until = players.getLong(playerKey(player) + ".ability_cooldown_until", 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    private void openAbilitiesMenu(Player player) {
        initialize(player);
        String playerClass = getRpgClass(player);
        long remaining = abilityCooldownRemaining(player);
        String cooldown = remaining > 0
                ? "§cПерезарядка: " + ((remaining + 999L) / 1000L) + " сек."
                : "§aСпособность готова!";
        FormWindowSimple form = new FormWindowSimple("§6§lСпособности",
                "§fКласс: " + rpgClassLabel(playerClass)
                        + "\n§fСпособность: §e" + abilityName(playerClass)
                        + "\n" + abilityDescription(playerClass)
                        + "\n\n" + cooldown
                        + "\n§7Перезарядка после применения: 30 секунд.");
        form.addButton(new ElementButton("§aИспользовать способность"));
        form.addButton(new ElementButton("§7Обновить"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected == 0) activateClassAbility(player);
            else if (selected == 1) openAbilitiesMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void activateClassAbility(Player player) {
        initialize(player);
        String playerClass = getRpgClass(player);
        if (playerClass.equals("none")) {
            player.sendMessage("§cСначала выбери класс: §e/class");
            return;
        }
        long remaining = abilityCooldownRemaining(player);
        if (remaining > 0) {
            player.sendMessage("§cСпособность перезаряжается. Осталось "
                    + ((remaining + 999L) / 1000L) + " сек.");
            return;
        }

        long cooldownUntil = System.currentTimeMillis() + 30_000L;
        players.set(playerKey(player) + ".ability_cooldown_until", cooldownUntil);
        markPlayersDirty();
        flushPlayers();
        UUID uuid = player.getUniqueId();

        switch (playerClass) {
            case "warrior":
                temporaryAbilityDamage.put(uuid, 3);
                getServer().getScheduler().scheduleDelayedTask(this, () -> temporaryAbilityDamage.remove(uuid), 160);
                player.sendMessage("§c§l[Способность] §fБоевой натиск! §e+3 урона на 8 секунд.");
                break;
            case "mage":
                float healed = Math.min(8.0f, player.getMaxHealth() - player.getHealth());
                if (healed > 0.0f) player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + healed));
                player.sendMessage("§b§l[Способность] §fМагическое восстановление! §a+" + (int) healed + " здоровья.");
                break;
            case "archer":
                player.setMovementSpeed(Math.min(Player.MAXIMUM_SPEED, player.getMovementSpeed() * 1.20f));
                getServer().getScheduler().scheduleDelayedTask(this, () -> {
                    if (player.isOnline()) recalculateStats(player);
                }, 160);
                player.sendMessage("§a§l[Способность] §fСтремительный шаг! §e+20% скорости на 8 секунд.");
                break;
            case "assassin":
                nextAbilityDamage.put(uuid, 7);
                player.sendMessage("§d§l[Способность] §fСмертельный выпад! §eСледующий удар получит +7 урона.");
                break;
            case "tank":
                player.setMaxHealth(player.getMaxHealth() + 6);
                player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + 6.0f));
                getServer().getScheduler().scheduleDelayedTask(this, () -> {
                    if (player.isOnline()) recalculateStats(player);
                }, 160);
                player.sendMessage("§6§l[Способность] §fЖелезная стойка! §e+6 максимального здоровья на 8 секунд.");
                break;
            default:
                players.set(playerKey(player) + ".ability_cooldown_until", 0L);
                markPlayersDirty();
                player.sendMessage("§cДля этого класса способность не настроена.");
                return;
        }
        updateSidebar(player);
    }

    private void openStatsMenu(Player player) {
        initialize(player);
        int level = Math.max(1, Math.min(MAX_LEVEL, get(player, "level", 1)));
        int xp = Math.max(0, get(player, "xp", 0));
        int requiredXp = xpForNextLevel(level);
        String progressText = level >= MAX_LEVEL
                ? "\n§6Максимальный уровень достигнут!"
                : "\n§fОпыт: §a" + xp + "§7/§a" + requiredXp
                    + "\n§fПрогресс: " + xpProgressBar(xp, requiredXp);
        FormWindowSimple form = new FormWindowSimple("§6LocalFastCore §8| §lХарактеристики",
                "§fУровень: §e" + level + "§7/§e" + MAX_LEVEL + progressText
                        + "\n§fСвободные очки: §b" + get(player, "points", 0)
                        + "\n\n§7Выбери характеристику для распределения очков.");
        form.addButton(new ElementButton("§cСила §8| §f" + get(player, "strength", 0)));
        form.addButton(new ElementButton("§cЗдоровье §8| §f" + get(player, "health", 0)));
        form.addButton(new ElementButton("§aЛовкость §8| §f" + get(player, "agility", 0)));
        form.addButton(new ElementButton("§bИнтеллект §8| §f" + get(player, "intelligence", 0)));
        form.addButton(new ElementButton("§6Класс §8| " + rpgClassLabel(getRpgClass(player))));
        form.addButton(new ElementButton("§dСпособности"));
        form.addButton(new ElementButton("§7Обновить"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < STATS.length) openStatAllocationMenu(player, STATS[selected]);
            else if (selected == 4) openClassMenu(player);
            else if (selected == 5) openAbilitiesMenu(player);
            else if (selected == 6) openStatsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openStatAllocationMenu(Player player, String stat) {
        initialize(player);
        int current = get(player, stat, 0);
        int points = get(player, "points", 0);
        FormWindowSimple form = new FormWindowSimple("§6Распределение очков",
                "§fХарактеристика: §e" + stat
                        + "\n§fТекущее значение: §a" + current
                        + "\n§fСвободные очки: §b" + points
                        + "\n\n§7Выбери количество очков.");
        form.addButton(new ElementButton("§a+1"));
        form.addButton(new ElementButton("§a+5"));
        form.addButton(new ElementButton("§aВсе доступные"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected == 3) {
                openStatsMenu(player);
                return;
            }
            int requested = selected == 0 ? 1 : selected == 1 ? 5 : points;
            spendStatPoints(player, stat, requested);
            openStatsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void spendStatPoints(Player player, String stat, int requested) {
        if (requested < 1 || !Arrays.asList(STATS).contains(stat)) return;
        int points = Math.max(0, get(player, "points", 0));
        int current = Math.max(0, get(player, stat, 0));
        int maxStat = Math.max(1, getConfig().getInt("progression.max-stat", 1000));
        int amount = Math.min(requested, Math.min(points, maxStat - current));
        if (amount <= 0) {
            player.sendMessage("§cНедостаточно очков или достигнут предел характеристики.");
            return;
        }
        set(player, stat, current + amount);
        set(player, "points", points - amount);
        markPlayersDirty();
        recalculateStats(player);
        updateSidebar(player);
        player.sendMessage("§a[LocalFast] " + stat + " увеличена на " + amount + ".");
    }

    private long parseConfiguredBalance() {
        try {
            return Long.parseLong(getConfig().getString("economy.starting-balance", "0").trim());
        } catch (RuntimeException exception) {
            getLogger().warning("Некорректный economy.starting-balance; используется 0.");
            return 0L;
        }
    }

    private String currencyName() {
        String configured = getConfig().getString("economy.currency-name", "монет");
        return configured == null || configured.trim().isEmpty() ? "монет" : configured.trim();
    }


    private Config guildData;
    private Config marketData;
    private final Map<UUID, PendingTrade> pendingTrades = new HashMap<>();

    private String guildMemberPath(String guildId, String uuid) {
        return "guilds." + guildId + ".members." + uuid;
    }

    private String currentGuildId(Player player) {
        return guildData.getString("players." + player.getUniqueId().toString(), "");
    }

    private boolean guildExists(String guildId) {
        return guildId != null && !guildId.isEmpty() && guildData.exists("guilds." + guildId + ".name");
    }

    private String guildRole(String guildId, String uuid) {
        return guildData.getString(guildMemberPath(guildId, uuid) + ".role", "member");
    }

    private boolean guildCanManage(String guildId, Player player) {
        String role = guildRole(guildId, player.getUniqueId().toString());
        return role.equals("leader") || role.equals("officer");
    }

    private void showGuildInfo(Player player, String guildId) {
        if (!guildExists(guildId)) {
            player.sendMessage("§cГильдия не найдена.");
            return;
        }
        String base = "guilds." + guildId;
        String name = guildData.getString(base + ".name", "Без названия");
        String owner = guildData.getString(base + ".owner-name", "Неизвестно");
        Map<String, Object> members = guildData.getSection(base + ".members").getAll();
        player.sendMessage("§6§lГИЛЬДИЯ §e" + name);
        player.sendMessage("§7Глава: §f" + owner + " §8| §7Участников: §a" + members.size());
        for (Map.Entry<String, Object> entry : members.entrySet()) {
            String uuid = entry.getKey();
            String memberName = guildData.getString(guildMemberPath(guildId, uuid) + ".name", "Игрок");
            String role = guildRole(guildId, uuid);
            String roleLabel = role.equals("leader") ? "§6Глава" : role.equals("officer") ? "§bОфицер" : "§7Участник";
            player.sendMessage(" §8• §f" + memberName + " §8— " + roleLabel);
        }
    }

    private void handleGuildCommand(Player player, String[] args) {
        String uuid = player.getUniqueId().toString();
        String guildId = currentGuildId(player);
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (guildId.isEmpty()) {
                player.sendMessage("§6[Гильдии] §fТы пока не состоишь в гильдии.");
                player.sendMessage("§e/guild create <название> §7— создать гильдию");
                player.sendMessage("§e/guild list §7— список гильдий");
            } else showGuildInfo(player, guildId);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            Map<String, Object> all = guildData.getSection("guilds").getAll();
            player.sendMessage("§6§lГИЛЬДИИ СЕРВЕРА");
            if (all.isEmpty()) { player.sendMessage("§7Пока нет созданных гильдий."); return; }
            for (String id : all.keySet()) {
                player.sendMessage("§e" + guildData.getString("guilds." + id + ".name", id)
                        + " §8— §7" + guildData.getSection("guilds." + id + ".members").getAll().size() + " участников");
            }
            return;
        }
        if (action.equals("create")) {
            if (!guildId.isEmpty()) { player.sendMessage("§cТы уже состоишь в гильдии. Сначала выйди из неё."); return; }
            if (args.length < 2) { player.sendMessage("§e/guild create <название>"); return; }
            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            if (name.length() < 3 || name.length() > 24 || name.contains("§") || name.contains("\n") || name.contains("\r")) {
                player.sendMessage("§cНазвание должно быть длиной 3–24 символа без цветовых кодов."); return;
            }
            for (String id : guildData.getSection("guilds").getAll().keySet()) {
                if (guildData.getString("guilds." + id + ".name", "").equalsIgnoreCase(name)) {
                    player.sendMessage("§cГильдия с таким названием уже существует."); return;
                }
            }
            long cost = Math.max(0L, getConfig().getLong("guilds.creation-cost", 1000L));
            long balance = getBalance(player);
            if (balance < cost) { player.sendMessage("§cСоздание стоит " + cost + " " + currencyName() + ". Твой баланс: " + balance + "."); return; }
            String id = "g_" + UUID.randomUUID().toString().replace("-", "");
            String base = "guilds." + id;
            guildData.set(base + ".name", name);
            guildData.set(base + ".owner", uuid);
            guildData.set(base + ".owner-name", player.getName());
            guildData.set(base + ".created", System.currentTimeMillis());
            guildData.set(guildMemberPath(id, uuid) + ".name", player.getName());
            guildData.set(guildMemberPath(id, uuid) + ".role", "leader");
            guildData.set("players." + uuid, id);
            setBalance(player, balance - cost);
            try {
                guildData.save();
                if (!flushPlayers()) throw new IllegalStateException("players.yml save failed");
                player.sendMessage("§aГильдия §e" + name + " §aсоздана! Списано " + cost + " " + currencyName() + ".");
            } catch (RuntimeException ex) {
                guildData.remove(base);
                guildData.remove("players." + uuid);
                setBalance(player, balance);
                try { guildData.save(); } catch (RuntimeException ignored) {}
                player.sendMessage("§cНе удалось сохранить гильдию. Деньги возвращены.");
                getLogger().error("Guild creation save failed.", ex);
            }
            return;
        }
        if (action.equals("accept")) {
            String invitePath = "invites." + uuid;
            String invitedGuild = guildData.getString(invitePath + ".guild", "");
            long expires = guildData.getLong(invitePath + ".expires", 0L);
            if (!guildId.isEmpty()) { player.sendMessage("§cСначала выйди из текущей гильдии."); return; }
            if (invitedGuild.isEmpty() || expires < System.currentTimeMillis() || !guildExists(invitedGuild)) {
                guildData.remove(invitePath); guildData.save();
                player.sendMessage("§cУ тебя нет действующего приглашения."); return;
            }
            guildData.set(guildMemberPath(invitedGuild, uuid) + ".name", player.getName());
            guildData.set(guildMemberPath(invitedGuild, uuid) + ".role", "member");
            guildData.set("players." + uuid, invitedGuild);
            guildData.remove(invitePath);
            guildData.save();
            if (!flushPlayers()) {
                guildData.remove(guildMemberPath(invitedGuild, uuid));
                guildData.remove("players." + uuid);
                guildData.save();
                player.sendMessage("§cНе удалось сохранить вступление. Попробуй ещё раз.");
                return;
            }
            player.sendMessage("§aТы вступил в гильдию §e" + guildData.getString("guilds." + invitedGuild + ".name", "") + "§a!");
            return;
        }
        if (action.equals("decline")) {
            guildData.remove("invites." + uuid); guildData.save();
            player.sendMessage("§7Приглашение отклонено."); return;
        }
        if (action.equals("invite")) {
            if (guildId.isEmpty() || !guildCanManage(guildId, player)) { player.sendMessage("§cПриглашать могут глава и офицеры гильдии."); return; }
            if (args.length != 2) { player.sendMessage("§e/guild invite <игрок>"); return; }
            Player target = getServer().getPlayer(args[1]);
            if (target == null || !target.isOnline()) { player.sendMessage("§cИгрок должен быть онлайн."); return; }
            if (target.getUniqueId().equals(player.getUniqueId())) { player.sendMessage("§cНельзя пригласить самого себя."); return; }
            String targetUuid = target.getUniqueId().toString();
            if (!currentGuildId(target).isEmpty()) { player.sendMessage("§cЭтот игрок уже состоит в гильдии."); return; }
            guildData.set("invites." + targetUuid + ".guild", guildId);
            guildData.set("invites." + targetUuid + ".expires", System.currentTimeMillis() + 86400000L);
            guildData.save();
            String name = guildData.getString("guilds." + guildId + ".name", "");
            player.sendMessage("§aПриглашение отправлено игроку " + target.getName() + ".");
            target.sendMessage("§6[Гильдия] §fТебя пригласили в §e" + name + "§f. Введи §a/guild accept §fили §c/guild decline§f (24 часа).");
            return;
        }
        if (action.equals("leave")) {
            if (guildId.isEmpty()) { player.sendMessage("§cТы не состоишь в гильдии."); return; }
            if (guildRole(guildId, uuid).equals("leader")) { player.sendMessage("§cГлава не может выйти. Передай лидерство или распусти гильдию: /guild disband."); return; }
            guildData.remove(guildMemberPath(guildId, uuid));
            guildData.remove("players." + uuid);
            guildData.save();
            flushPlayers();
            player.sendMessage("§eТы покинул гильдию.");
            return;
        }
        if (action.equals("kick")) {
            if (guildId.isEmpty() || !guildRole(guildId, uuid).equals("leader")) { player.sendMessage("§cИсключать участников может только глава."); return; }
            if (args.length != 2) { player.sendMessage("§e/guild kick <игрок>"); return; }
            Player target = getServer().getPlayer(args[1]);
            if (target == null || !target.isOnline() || !currentGuildId(target).equals(guildId)) { player.sendMessage("§cУкажи участника своей гильдии, который сейчас онлайн."); return; }
            if (target.getUniqueId().equals(player.getUniqueId())) { player.sendMessage("§cНельзя исключить самого себя."); return; }
            String targetUuid = target.getUniqueId().toString();
            guildData.remove(guildMemberPath(guildId, targetUuid));
            guildData.remove("players." + targetUuid);
            guildData.save(); flushPlayers();
            player.sendMessage("§aИгрок " + target.getName() + " исключён из гильдии.");
            target.sendMessage("§cТебя исключили из гильдии.");
            return;
        }
        if (action.equals("role")) {
            if (guildId.isEmpty() || !guildRole(guildId, uuid).equals("leader")) { player.sendMessage("§cМенять роли может только глава."); return; }
            if (args.length != 3 || !(args[2].equalsIgnoreCase("officer") || args[2].equalsIgnoreCase("member"))) {
                player.sendMessage("§e/guild role <игрок> <officer|member>"); return;
            }
            Player target = getServer().getPlayer(args[1]);
            if (target == null || !target.isOnline() || !currentGuildId(target).equals(guildId)
                    || target.getUniqueId().equals(player.getUniqueId())) { player.sendMessage("§cИгрок должен быть другим участником твоей гильдии и находиться онлайн."); return; }
            guildData.set(guildMemberPath(guildId, target.getUniqueId().toString()) + ".role", args[2].toLowerCase(Locale.ROOT));
            guildData.save();
            player.sendMessage("§aРоль игрока " + target.getName() + " обновлена.");
            target.sendMessage("§eТвоя роль в гильдии: " + (args[2].equalsIgnoreCase("officer") ? "§bОфицер" : "§7Участник"));
            return;
        }
        if (action.equals("transfer")) {
            if (guildId.isEmpty() || !guildRole(guildId, uuid).equals("leader")) { player.sendMessage("§cПередать лидерство может только глава."); return; }
            if (args.length != 2) { player.sendMessage("§e/guild transfer <игрок>"); return; }
            Player target = getServer().getPlayer(args[1]);
            if (target == null || !target.isOnline() || !currentGuildId(target).equals(guildId) || target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage("§cНужен другой участник твоей гильдии онлайн."); return;
            }
            String targetUuid = target.getUniqueId().toString();
            guildData.set(guildMemberPath(guildId, uuid) + ".role", "officer");
            guildData.set(guildMemberPath(guildId, targetUuid) + ".role", "leader");
            guildData.set("guilds." + guildId + ".owner", targetUuid);
            guildData.set("guilds." + guildId + ".owner-name", target.getName());
            guildData.save();
            player.sendMessage("§aТы передал лидерство игроку " + target.getName() + ".");
            target.sendMessage("§6Теперь ты глава гильдии!");
            return;
        }
        if (action.equals("disband")) {
            if (guildId.isEmpty() || !guildRole(guildId, uuid).equals("leader")) { player.sendMessage("§cРаспустить гильдию может только глава."); return; }
            if (args.length != 1) { player.sendMessage("§e/guild disband"); return; }
            String name = guildData.getString("guilds." + guildId + ".name", "");
            Map<String, Object> members = guildData.getSection("guilds." + guildId + ".members").getAll();
            for (String memberUuid : members.keySet()) guildData.remove("players." + memberUuid);
            guildData.remove("guilds." + guildId);
            guildData.save(); flushPlayers();
            player.sendMessage("§cГильдия §e" + name + " §cраспущена.");
            return;
        }
        if (action.equals("help")) {
            player.sendMessage("§6[Гильдии] §e/guild create <название> §7— создать (цена в config.yml)");
            player.sendMessage("§e/guild list §7— список §8| §e/guild info §7— состав");
            player.sendMessage("§e/guild invite <игрок> §7— пригласить §8| §e/guild accept/decline");
            player.sendMessage("§e/guild leave §7— выйти §8| §e/guild kick <игрок> §7— исключить");
            player.sendMessage("§e/guild role <игрок> <officer|member> §7— роль");
            player.sendMessage("§e/guild transfer <игрок> §7— передать главу §8| §e/guild disband §7— распустить");
            return;
        }
        player.sendMessage("§cНеизвестная команда. Используй §e/guild help§c.");
    }

    private long getBalance(Player player) {
        String value = players.getString(playerKey(player) + ".balance", "0");
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (RuntimeException exception) {
            getLogger().warning("Некорректный баланс в профиле " + player.getName() + "; показан 0.");
            return 0L;
        }
    }

    private void setBalance(Player player, long amount) {
        players.set(playerKey(player) + ".balance", Long.toString(amount));
        markPlayersDirty();
    }

    private long balanceByUuid(String uuid) {
        try { return Math.max(0L, Long.parseLong(players.getString("uuid_" + uuid + ".balance", "0"))); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private void setBalanceByUuid(String uuid, long amount) {
        players.set("uuid_" + uuid + ".balance", Long.toString(Math.max(0L, amount)));
        markPlayersDirty();
    }

    private String listingPath(String id) { return "listings." + id; }

    private long parsePositiveAmount(String value) {
        if (value == null || !value.matches("[0-9]{1,18}")) return -1L;
        try { long amount = Long.parseLong(value); return amount > 0L ? amount : -1L; }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private void handleTrade(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage("§6[Торговля] §e/trade <игрок> <сумма> §7— предложить перевод");
            player.sendMessage("§e/trade accept §7— принять предложение; §e/trade decline §7— отказать");
            return;
        }
        if (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("decline")) {
            PendingTrade offer = pendingTrades.get(player.getUniqueId());
            if (offer == null || offer.expiresAt < System.currentTimeMillis()) {
                pendingTrades.remove(player.getUniqueId());
                player.sendMessage("§cНет действующего предложения.");
                return;
            }
            pendingTrades.remove(player.getUniqueId());
            if (args[0].equalsIgnoreCase("decline")) {
                player.sendMessage("§eПредложение отклонено.");
                Player sender = getServer().getPlayer(offer.senderName);
                if (sender != null) sender.sendMessage("§e" + player.getName() + " отклонил предложение.");
                return;
            }
            String from = offer.sender.toString(), to = offer.target.toString();
            long fromBalance = balanceByUuid(from), toBalance = balanceByUuid(to);
            if (fromBalance < offer.amount || toBalance > Long.MAX_VALUE - offer.amount) {
                player.sendMessage("§cПеревод отменён: баланс одной из сторон изменился.");
                return;
            }
            setBalanceByUuid(from, fromBalance - offer.amount);
            setBalanceByUuid(to, toBalance + offer.amount);
            if (!flushPlayers()) {
                setBalanceByUuid(from, fromBalance);
                setBalanceByUuid(to, toBalance);
                player.sendMessage("§cНе удалось сохранить перевод. Попробуй позже.");
                return;
            }
            player.sendMessage("§aТы получил " + offer.amount + " " + currencyName() + " от " + offer.senderName + ".");
            Player sender = getServer().getPlayer(offer.senderName);
            if (sender != null) sender.sendMessage("§a" + player.getName() + " принял перевод " + offer.amount + ".");
            return;
        }
        if (args.length != 2) {
            player.sendMessage("§eИспользуй: /trade <игрок> <сумма>");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        long amount = parsePositiveAmount(args[1]);
        if (target == null || !target.isOnline() || target.getUniqueId().equals(player.getUniqueId()) || amount < 1L) {
            player.sendMessage("§cПроверь ник игрока и сумму. Нельзя торговать с собой.");
            return;
        }
        initialize(player);
        initialize(target);
        if (getBalance(player) < amount) {
            player.sendMessage("§cНедостаточно средств для предложения.");
            return;
        }
        pendingTrades.put(target.getUniqueId(), new PendingTrade(player.getUniqueId(), target.getUniqueId(),
                player.getName(), amount, System.currentTimeMillis() + 60_000L));
        player.sendMessage("§aПредложение на " + amount + " " + currencyName() + " отправлено игроку " + target.getName() + ".");
        target.sendMessage("§e" + player.getName() + " предлагает тебе " + amount + " " + currencyName() + ".");
        target.sendMessage("§a/trade accept §7— принять; §c/trade decline §7— отклонить (60 секунд).");
    }

    private void handleMarket(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage("§6[Рынок] §e/market list §7— объявления");
            player.sendMessage("§e/market sell <цена> §7— выставить предмет из руки");
            player.sendMessage("§e/market buy <id> §7— купить; §e/market cancel <id> §7— снять своё объявление");
            player.sendMessage("§7/auction create <стартовая_цена> [минуты], /auction bid <id> <ставка>, /auction claim <id>");
            return;
        }
        if (args[0].equalsIgnoreCase("list")) {
            Map<String, Object> listings = marketData.getSection("listings").getAll();
            player.sendMessage("§6§l[РЫНОК] §7Объявлений: " + listings.size());
            int shown = 0;
            for (String id : listings.keySet()) {
                String path = listingPath(id);
                if (marketData.getBoolean(path + ".auction", false)) continue;
                player.sendMessage("§e#" + id + " §f" + marketData.getString(path + ".item-name", "Предмет")
                        + " x" + marketData.getInt(path + ".count", 1) + " §8— §a"
                        + marketData.getLong(path + ".price", 0L) + " " + currencyName()
                        + " §8— §7" + marketData.getString(path + ".seller-name", "?"));
                if (++shown >= 15) break;
            }
            if (shown == 0) player.sendMessage("§7Пока нет объявлений.");
            return;
        }
        if (args[0].equalsIgnoreCase("sell")) {
            if (args.length != 2) { player.sendMessage("§e/market sell <цена>"); return; }
            long price = parsePositiveAmount(args[1]);
            Item hand = player.getInventory().getItemInHand();
            if (price < 1L || hand == null || hand.isNull() || hand.getId() <= 0 || hand.getCount() <= 0) {
                player.sendMessage("§cВозьми предмет в руку и укажи корректную цену.");
                return;
            }
            String id = Integer.toString(Math.max(1, marketData.getInt("next-id", 1)));
            marketData.set("next-id", Integer.parseInt(id) + 1);
            String path = listingPath(id);
            marketData.set(path + ".auction", false);
            marketData.set(path + ".seller", player.getUniqueId().toString());
            marketData.set(path + ".seller-name", player.getName());
            marketData.set(path + ".item-id", hand.getId());
            marketData.set(path + ".item-damage", hand.getDamage());
            marketData.set(path + ".item-name", hand.getName());
            marketData.set(path + ".count", hand.getCount());
            marketData.set(path + ".price", price);
            marketData.set(path + ".created-at", System.currentTimeMillis());
            player.getInventory().setItemInHand(Item.get(0));
            try { marketData.save(); }
            catch (RuntimeException ex) {
                player.getInventory().setItemInHand(hand);
                marketData.remove(path);
                player.sendMessage("§cНе удалось сохранить объявление; предмет возвращён.");
                return;
            }
            player.sendMessage("§aПредмет выставлен на рынок под номером #" + id + " за " + price + " " + currencyName() + ".");
            return;
        }
        if (args[0].equalsIgnoreCase("buy")) {
            if (args.length != 2) { player.sendMessage("§e/market buy <id>"); return; }
            String id = args[1], path = listingPath(id);
            if (!marketData.exists(path + ".seller") || marketData.getBoolean(path + ".auction", false)) {
                player.sendMessage("§cОбъявление не найдено.");
                return;
            }
            String sellerUuid = marketData.getString(path + ".seller", "");
            String sellerName = marketData.getString(path + ".seller-name", "");
            if (sellerUuid.equals(player.getUniqueId().toString())) { player.sendMessage("§cНельзя купить собственный предмет."); return; }
            long price = marketData.getLong(path + ".price", 0L);
            int itemId = marketData.getInt(path + ".item-id", 0), damage = marketData.getInt(path + ".item-damage", 0);
            int count = marketData.getInt(path + ".count", 0);
            Item item = Item.get(itemId, damage, count);
            if (price < 1L || count < 1 || item.isNull() || !player.getInventory().canAddItem(item)) {
                player.sendMessage("§cНедостаточно места в инвентаре или объявление повреждено.");
                return;
            }
            initialize(player);
            long buyerBalance = getBalance(player), sellerBalance = balanceByUuid(sellerUuid);
            if (buyerBalance < price || sellerBalance > Long.MAX_VALUE - price) {
                player.sendMessage("§cНедостаточно монет или баланс продавца переполнен.");
                return;
            }
            setBalance(player, buyerBalance - price);
            setBalanceByUuid(sellerUuid, sellerBalance + price);
            if (!flushPlayers()) {
                setBalance(player, buyerBalance); setBalanceByUuid(sellerUuid, sellerBalance);
                player.sendMessage("§cНе удалось сохранить покупку; попробуй позже.");
                return;
            }
            marketData.remove(path);
            try { marketData.save(); }
            catch (RuntimeException ex) {
                setBalance(player, buyerBalance); setBalanceByUuid(sellerUuid, sellerBalance); flushPlayers();
                player.sendMessage("§cНе удалось завершить покупку; средства возвращены.");
                return;
            }
            player.getInventory().addItem(item);
            player.sendMessage("§aПокупка завершена: " + count + " предмет(ов) за " + price + " " + currencyName() + ".");
            Player seller = getServer().getPlayer(sellerName);
            if (seller != null) seller.sendMessage("§aТвой предмет купили за " + price + " " + currencyName() + ".");
            return;
        }
        if (args[0].equalsIgnoreCase("cancel")) {
            if (args.length != 2) { player.sendMessage("§e/market cancel <id>"); return; }
            String path = listingPath(args[1]);
            if (!marketData.exists(path + ".seller") || marketData.getBoolean(path + ".auction", false)
                    || !marketData.getString(path + ".seller", "").equals(player.getUniqueId().toString())) {
                player.sendMessage("§cТвоё объявление не найдено.");
                return;
            }
            Item item = Item.get(marketData.getInt(path + ".item-id", 0), marketData.getInt(path + ".item-damage", 0),
                    marketData.getInt(path + ".count", 0));
            if (!player.getInventory().canAddItem(item)) { player.sendMessage("§cОсвободи место в инвентаре."); return; }
            marketData.remove(path);
            try { marketData.save(); }
            catch (RuntimeException ex) { player.sendMessage("§cНе удалось снять объявление."); return; }
            player.getInventory().addItem(item);
            player.sendMessage("§aОбъявление снято, предмет возвращён.");
            return;
        }
        player.sendMessage("§e/market help");
    }

    private void handleAuction(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage("§6[Аукцион] §e/auction create <стартовая_цена> [минуты]");
            player.sendMessage("§e/auction list §7— лоты; §e/auction bid <id> <ставка>");
            player.sendMessage("§e/auction claim <id> §7— забрать выигранный предмет; продавец затем использует /auction payout <id>");
            return;
        }
        if (args[0].equalsIgnoreCase("list")) {
            int shown = 0;
            for (String id : marketData.getSection("listings").getAll().keySet()) {
                String path = listingPath(id);
                if (!marketData.getBoolean(path + ".auction", false)) continue;
                long left = Math.max(0L, marketData.getLong(path + ".ends-at", 0L) - System.currentTimeMillis());
                player.sendMessage("§e#" + id + " §f" + marketData.getString(path + ".item-name", "Предмет")
                        + " x" + marketData.getInt(path + ".count", 1) + " §8— ставка §a"
                        + marketData.getLong(path + ".current-bid", 0L) + " §8— §7" + (left / 60000L) + " мин.");
                if (++shown >= 15) break;
            }
            if (shown == 0) player.sendMessage("§7Активных лотов нет.");
            return;
        }
        if (args[0].equalsIgnoreCase("create")) {
            if (args.length < 2 || args.length > 3) { player.sendMessage("§e/auction create <цена> [минуты]"); return; }
            long start = parsePositiveAmount(args[1]);
            int minutes = 30;
            try { if (args.length == 3) minutes = Integer.parseInt(args[2]); }
            catch (NumberFormatException ex) { minutes = -1; }
            Item hand = player.getInventory().getItemInHand();
            if (start < 1L || minutes < 1 || minutes > 10080 || hand == null || hand.isNull() || hand.getId() <= 0) {
                player.sendMessage("§cПроверь предмет, цену и длительность (1–10080 минут)."); return;
            }
            String id = Integer.toString(Math.max(1, marketData.getInt("next-id", 1)));
            marketData.set("next-id", Integer.parseInt(id) + 1);
            String path = listingPath(id);
            marketData.set(path + ".auction", true);
            marketData.set(path + ".seller", player.getUniqueId().toString());
            marketData.set(path + ".seller-name", player.getName());
            marketData.set(path + ".item-id", hand.getId());
            marketData.set(path + ".item-damage", hand.getDamage());
            marketData.set(path + ".item-name", hand.getName());
            marketData.set(path + ".count", hand.getCount());
            marketData.set(path + ".current-bid", start - 1L);
            marketData.set(path + ".bidder", "");
            marketData.set(path + ".ends-at", System.currentTimeMillis() + minutes * 60000L);
            player.getInventory().setItemInHand(Item.get(0));
            try { marketData.save(); }
            catch (RuntimeException ex) {
                player.getInventory().setItemInHand(hand); marketData.remove(path);
                player.sendMessage("§cНе удалось сохранить лот; предмет возвращён."); return;
            }
            player.sendMessage("§aЛот #" + id + " выставлен на " + minutes + " мин. Стартовая ставка: " + start + ".");
            return;
        }
        if (args[0].equalsIgnoreCase("bid")) {
            if (args.length != 3) { player.sendMessage("§e/auction bid <id> <ставка>"); return; }
            String path = listingPath(args[1]);
            long bid = parsePositiveAmount(args[2]);
            if (!marketData.exists(path + ".seller") || !marketData.getBoolean(path + ".auction", false)
                    || marketData.getLong(path + ".ends-at", 0L) <= System.currentTimeMillis()) {
                player.sendMessage("§cЛот не найден или торги завершены."); return;
            }
            String seller = marketData.getString(path + ".seller", "");
            String oldBidder = marketData.getString(path + ".bidder", "");
            long oldBid = marketData.getLong(path + ".current-bid", 0L);
            long minimum = oldBid == Long.MAX_VALUE ? Long.MAX_VALUE : oldBid + 1L;
            if (seller.equals(player.getUniqueId().toString()) || bid < minimum
                    || player.getUniqueId().toString().equals(oldBidder)) {
                player.sendMessage("§cНельзя делать ставку на свой лот или перебивать себя. Минимальная ставка: " + minimum + "."); return;
            }
            initialize(player);
            long bidderBalance = getBalance(player);
            if (bidderBalance < bid) { player.sendMessage("§cНедостаточно монет для ставки."); return; }
            long previousBalance = oldBidder.isEmpty() ? 0L : balanceByUuid(oldBidder);
            if (!oldBidder.isEmpty() && previousBalance > Long.MAX_VALUE - oldBid) {
                player.sendMessage("§cНе удалось вернуть предыдущую ставку: баланс переполнен."); return;
            }
            setBalance(player, bidderBalance - bid);
            if (!oldBidder.isEmpty()) setBalanceByUuid(oldBidder, previousBalance + oldBid);
            marketData.set(path + ".current-bid", bid);
            marketData.set(path + ".bidder", player.getUniqueId().toString());
            try {
                if (!flushPlayers()) throw new IllegalStateException("players save failed");
                marketData.save();
            } catch (RuntimeException ex) {
                setBalance(player, bidderBalance);
                if (!oldBidder.isEmpty()) setBalanceByUuid(oldBidder, previousBalance);
                flushPlayers();
                marketData.set(path + ".current-bid", oldBid);
                marketData.set(path + ".bidder", oldBidder);
                player.sendMessage("§cНе удалось сохранить ставку; средства возвращены."); return;
            }
            player.sendMessage("§aТвоя ставка " + bid + " принята для лота #" + args[1] + ".");
            return;
        }
        if (args[0].equalsIgnoreCase("claim")) {
            if (args.length != 2) { player.sendMessage("§e/auction claim <id>"); return; }
            String path = listingPath(args[1]);
            if (!marketData.exists(path + ".seller") || !marketData.getBoolean(path + ".auction", false)
                    || marketData.getLong(path + ".ends-at", 0L) > System.currentTimeMillis()) {
                player.sendMessage("§cЛот ещё не завершён или не найден."); return;
            }
            String bidder = marketData.getString(path + ".bidder", "");
            String seller = marketData.getString(path + ".seller", "");
            if (bidder.isEmpty()) {
                if (!seller.equals(player.getUniqueId().toString())) { player.sendMessage("§cЗабрать непроданный лот может только продавец."); return; }
            } else if (!bidder.equals(player.getUniqueId().toString())) {
                player.sendMessage("§cЭтот лот выиграл другой игрок."); return;
            }
            Item item = Item.get(marketData.getInt(path + ".item-id", 0), marketData.getInt(path + ".item-damage", 0),
                    marketData.getInt(path + ".count", 0));
            if (!player.getInventory().canAddItem(item)) { player.sendMessage("§cОсвободи место в инвентаре."); return; }
            long winningBid = marketData.getLong(path + ".current-bid", 0L);
            long sellerBalance = bidder.isEmpty() ? 0L : balanceByUuid(seller);
            if (!bidder.isEmpty() && sellerBalance > Long.MAX_VALUE - winningBid) {
                player.sendMessage("§cБаланс продавца переполнен; обратись к администрации."); return;
            }
            if (!bidder.isEmpty()) {
                setBalanceByUuid(seller, sellerBalance + winningBid);
                if (!flushPlayers()) {
                    setBalanceByUuid(seller, sellerBalance);
                    player.sendMessage("§cНе удалось сохранить выплату продавцу."); return;
                }
            }
            marketData.remove(path);
            try { marketData.save(); }
            catch (RuntimeException ex) {
                if (!bidder.isEmpty()) { setBalanceByUuid(seller, sellerBalance); flushPlayers(); }
                player.sendMessage("§cНе удалось завершить получение лота."); return;
            }
            player.getInventory().addItem(item);
            player.sendMessage("§aТы получил предмет с аукциона #" + args[1] + ".");
            return;
        }
        player.sendMessage("§e/auction help");
    }

    private void handleEconomy(Player player, String commandName, String[] args) {
        initialize(player);
        if (commandName.equals("balance")) {
            if (args.length != 0) {
                player.sendMessage("§eИспользуй: /balance");
                return;
            }
            player.sendMessage("§6[Экономика] §fБаланс: §a" + getBalance(player) + " " + currencyName());
            return;
        }
        if (args.length != 2) {
            player.sendMessage("§eИспользуй: /pay <игрок> <сумма>");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§cИгрок не найден или не в сети.");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage("§cНельзя переводить деньги самому себе.");
            return;
        }
        long amount;
        try {
            if (!args[1].matches("[0-9]+")) throw new NumberFormatException();
            amount = Long.parseLong(args[1]);
            if (amount <= 0L) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            player.sendMessage("§cСумма должна быть целым числом больше 0.");
            return;
        }
        initialize(target);
        long senderBalance = getBalance(player);
        long targetBalance = getBalance(target);
        if (senderBalance < amount) {
            player.sendMessage("§cНедостаточно средств. Твой баланс: " + senderBalance + ".");
            return;
        }
        if (targetBalance > Long.MAX_VALUE - amount) {
            player.sendMessage("§cБаланс получателя достиг максимального значения.");
            return;
        }

        setBalance(player, senderBalance - amount);
        setBalance(target, targetBalance + amount);
        if (!flushPlayers()) {
            setBalance(player, senderBalance);
            setBalance(target, targetBalance);
            player.sendMessage("§cНе удалось сохранить перевод. Средства не должны быть списаны; попробуй позже.");
            return;
        }
        String amountText = amount + " " + currencyName();
        player.sendMessage("§aТы перевёл " + amountText + " игроку " + target.getName() + ".");
        target.sendMessage("§a" + player.getName() + " перевёл тебе " + amountText + ".");
    }

    private boolean isOperator(CommandSender sender) {
        if (!(sender instanceof Player)) return false;
        String uuid = getConfig().getString("operator.uuid", "");
        return uuid != null && !uuid.isEmpty() && uuid.equalsIgnoreCase(((Player) sender).getUniqueId().toString());
    }

    private static final String[] STAFF_ROLES = {
            "ml_helper", "helper", "senior_helper", "junior_moderator",
            "moderator", "senior_moderator", "admin"
    };

    private String staffRole(Player player) {
        if (isOperator(player)) {
            String testRole = getConfig().getString("operator.test-role", "");
            if (Arrays.asList(STAFF_ROLES).contains(testRole)) return testRole;
            return "operator";
        }
        return staffData.getString("staff." + player.getUniqueId().toString(), "");
    }

    private int staffRank(String role) {
        if ("operator".equals(role)) return 8;
        for (int i = 0; i < STAFF_ROLES.length; i++) if (STAFF_ROLES[i].equals(role)) return i + 1;
        return 0;
    }

    /**
     * Compact filled-looking rank ribbons for Bedrock chat/name tags.
     * Bedrock chat does not support true background fills, so colored block
     * glyphs frame each title while preserving vanilla text rendering.
     */
    private String staffChatTag(String role) {
        // Rank emblems use Bedrock-friendly Unicode symbols so they appear
        // consistently in both chat and player name tags without a custom font.
        switch (role) {
            case "ml_helper": return "§8[§b✧ §lМЛ. ХЕЛПЕР §r§b✧§8]";
            case "helper": return "§8[§a❖ §lХЕЛПЕР §r§a❖§8]";
            case "senior_helper": return "§8[§6✦ §lСТ. ХЕЛПЕР §r§6✦§8]";
            case "junior_moderator": return "§8[§d⚔ §lМЛ. МОДЕР §r§d⚔§8]";
            case "moderator": return "§8[§5◆ §lМОДЕР §r§5◆§8]";
            case "senior_moderator": return "§8[§9⚜ §lСТ. МОДЕР §r§9⚜§8]";
            case "admin": return "§8[§c♛ §lАДМИНИСТРАТОР §r§c♛§8]";
            case "operator": return "§8[§e★ §lОПЕРАТОР §r§e★§8]";
            default: return "";
        }
    }

    private void refreshStaffNameTag(Player player) {
        String role = staffRole(player);
        String name = "operator".equals(role) ? "§e" + player.getName() : "§f" + player.getName();
        String tag = staffChatTag(role);
        player.setNameTag(tag.isEmpty() ? name : tag + " §f" + name);
    }

    private void refreshStaffModes(Player player) {
        if (staffRank(staffRole(player)) < 7) {
            flyingPlayers.remove(player.getUniqueId());
            player.setAllowFlight(false);
            if (vanishedPlayers.remove(player.getUniqueId())) {
                for (Player viewer : getServer().getOnlinePlayers().values()) {
                    if (!viewer.getUniqueId().equals(player.getUniqueId())) viewer.showPlayer(player);
                }
            }
        }
    }

    private String staffRoleColor(String role) {
        switch (role) {
            case "ml_helper": return "§b";
            case "helper": return "§a";
            case "senior_helper": return "§6";
            case "junior_moderator": return "§d";
            case "moderator": return "§5";
            case "senior_moderator": return "§9";
            case "admin": return "§c";
            case "operator": return "§e";
            default: return "§7";
        }
    }

    private String staffRoleLabel(String role) {
        switch (role) {
            case "ml_helper": return "Мл. хелпер";
            case "helper": return "Хелпер";
            case "senior_helper": return "Ст. хелпер";
            case "junior_moderator": return "Мл. модер";
            case "moderator": return "Модер";
            case "senior_moderator": return "Ст. модер";
            case "admin": return "Админ";
            case "operator": return "Оператор";
            default: return "Игрок";
        }
    }

    private long maxBanMinutes(String role) {
        switch (role) {
            case "junior_moderator": return 60L;
            case "moderator": return 1440L;
            case "senior_moderator": return 10080L;
            case "admin": return -1L;
            case "operator": return -1L;
            default: return 0L;
        }
    }

    private long maxMuteMinutes(String role) {
        switch (role) {
            case "helper": return 30L;
            case "senior_helper": return 120L;
            case "junior_moderator": return 1440L;
            case "moderator": return 10080L;
            case "senior_moderator": return 43200L;
            case "admin": return -1L;
            case "operator": return -1L;
            default: return 0L;
        }
    }

    private boolean canKick(String role) { return staffRank(role) >= 3; }
    private boolean canBan(String role) { return maxBanMinutes(role) != 0L; }
    private boolean canUnban(String role) { return staffRank(role) >= 6; }
    private boolean canUnmute(String role) { return staffRank(role) >= 5; }

    private void staffLog(String actor, String action, String target, String details) {
        try {
            String line = java.time.Instant.now() + " | " + actor + " | " + action + " | " + target + " | " + details.replace("\n", " ") + System.lineSeparator();
            java.nio.file.Files.write(staffLogFile.toPath(), line.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException exception) { getLogger().error("Не удалось записать staff.log.", exception); }
    }

    private void applyBan(Player actor, Player target, long minutes, String reason) {
        String path = "punishments.bans." + target.getUniqueId();
        staffData.set(path + ".expires", minutes < 0 ? -1L : System.currentTimeMillis() + minutes * 60000L);
        staffData.set(path + ".reason", reason);
        staffData.set(path + ".name", target.getName());
        staffData.set(path + ".by", actor.getName());
        staffData.save();
        staffLog(actor.getName(), "ban", target.getName(), minutes + " мин.; " + reason);
        target.kick("§cБан: " + reason, false); actor.sendMessage("§aБан выдан игроку " + target.getName() + ".");
    }

    private void applyMute(Player actor, Player target, long minutes, String reason) {
        String path = "punishments.mutes." + target.getUniqueId();
        staffData.set(path + ".expires", minutes < 0L ? -1L : System.currentTimeMillis() + minutes * 60000L);
        staffData.set(path + ".reason", reason);
        staffData.set(path + ".name", target.getName());
        staffData.set(path + ".by", actor.getName());
        staffData.save();
        staffLog(actor.getName(), "mute", target.getName(), (minutes < 0L ? "навсегда" : minutes + " мин.") + "; " + reason);
        target.sendMessage("§cТебе выдан мут " + (minutes < 0L ? "навсегда" : "на " + minutes + " мин.") + ". Причина: " + reason);
        actor.sendMessage("§aМут выдан игроку " + target.getName() + ".");
    }


    private String remainingPunishmentTime(long expires) {
        if (expires < 0L) return "§cНавсегда";
        long millis = expires - System.currentTimeMillis();
        if (millis <= 0L) return "§7Истёк";
        long minutes = Math.max(1L, (millis + 59999L) / 60000L);
        if (minutes < 60L) return "§e" + minutes + " мин.";
        if (minutes < 1440L) return "§e" + (minutes / 60L) + " ч. " + (minutes % 60L) + " мин.";
        long hours = minutes / 60L;
        if (hours < 48L) return "§e" + (hours / 24L) + " д. " + (hours % 24L) + " ч.";
        return "§e" + (minutes / 1440L) + " дн.";
    }

    private void openActivePunishmentsMenu(Player viewer) {
        String role = staffRole(viewer);
        boolean canBans = canUnban(role);
        boolean canMutes = canUnmute(role);
        if (!canBans && !canMutes) { viewer.sendMessage("§cТвой ранг не может просматривать и снимать наказания."); return; }
        FormWindowSimple form = new FormWindowSimple("§6Активные наказания", "§7Выбери категорию для просмотра.");
        List<String> types = new ArrayList<>();
        if (canBans) { form.addButton(new ElementButton("§cАктивные баны")); types.add("bans"); }
        if (canMutes) { form.addButton(new ElementButton("§eАктивные муты")); types.add("mutes"); }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !viewer.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index >= 0 && index < types.size()) openPunishmentListMenu(viewer, types.get(index));
            else if (index == types.size()) openStaffMenu(viewer);
        }));
        viewer.showFormWindow(form);
    }

    private void openPunishmentListMenu(Player viewer, String type) {
        String role = staffRole(viewer);
        if ((type.equals("bans") && !canUnban(role)) || (type.equals("mutes") && !canUnmute(role))) {
            viewer.sendMessage("§cНедостаточно прав для этой категории."); return;
        }
        String root = "punishments." + type;
        List<String> activeIds = new ArrayList<>();
        List<String> expiredIds = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (staffData.exists(root)) {
            for (String uuid : staffData.getSection(root).getKeys(false)) {
                long expires = staffData.getLong(root + "." + uuid + ".expires", 0L);
                if (expires != -1L && expires <= now) expiredIds.add(uuid);
                else activeIds.add(uuid);
            }
        }
        for (String uuid : expiredIds) staffData.remove(root + "." + uuid);
        if (!expiredIds.isEmpty()) staffData.save();
        FormWindowSimple form = new FormWindowSimple(type.equals("bans") ? "§cАктивные баны" : "§eАктивные муты",
                "§7Выбери игрока для просмотра подробностей. §8Истёкшие записи очищены.");
        for (String uuid : activeIds) {
            String path = root + "." + uuid;
            String name = staffData.getString(path + ".name", uuid);
            long expires = staffData.getLong(path + ".expires", 0L);
            form.addButton(new ElementButton("§f" + name + "\n" + remainingPunishmentTime(expires)));
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !viewer.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index >= 0 && index < activeIds.size()) openPunishmentDetailsMenu(viewer, type, activeIds.get(index));
            else if (index == activeIds.size()) openActivePunishmentsMenu(viewer);
        }));
        viewer.showFormWindow(form);
        if (activeIds.isEmpty()) viewer.sendMessage("§7Активных наказаний в этой категории нет.");
    }

    private void openPunishmentDetailsMenu(Player viewer, String type, String uuid) {
        String role = staffRole(viewer);
        if ((type.equals("bans") && !canUnban(role)) || (type.equals("mutes") && !canUnmute(role))) {
            viewer.sendMessage("§cНедостаточно прав."); return;
        }
        String path = "punishments." + type + "." + uuid;
        if (!staffData.exists(path)) { viewer.sendMessage("§7Это наказание уже снято или истекло."); openPunishmentListMenu(viewer, type); return; }
        long expires = staffData.getLong(path + ".expires", 0L);
        if (expires != -1L && expires <= System.currentTimeMillis()) {
            staffData.remove(path); staffData.save();
            viewer.sendMessage("§7Срок наказания истёк; запись очищена.");
            openPunishmentListMenu(viewer, type); return;
        }
        String name = staffData.getString(path + ".name", uuid);
        String reason = staffData.getString(path + ".reason", "Не указана");
        String by = staffData.getString(path + ".by", "Неизвестно");
        FormWindowSimple form = new FormWindowSimple("§6Наказание: " + name,
                "§fИгрок: §e" + name + "\n§fТип: " + (type.equals("bans") ? "§cБан" : "§eМут")
                        + "\n§fСрок: " + remainingPunishmentTime(expires)
                        + "\n§fПричина: §7" + reason + "\n§fВыдал: §b" + by + "\n\n§7Снять это наказание?");
        form.addButton(new ElementButton("§aСнять наказание"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !viewer.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index == 0) {
                if (!staffData.exists(path)) viewer.sendMessage("§7Наказание уже отсутствует.");
                else {
                    staffData.remove(path); staffData.save();
                    String action = type.equals("bans") ? "unban" : "unmute";
                    staffLog(viewer.getName(), action, name, "снято через меню активных наказаний");
                    Player online = getServer().getPlayer(name);
                    if (online != null) online.sendMessage("§aТвоё наказание снято персоналом.");
                    viewer.sendMessage("§aНаказание игрока " + name + " снято.");
                }
                openPunishmentListMenu(viewer, type);
            } else if (index == 1) openPunishmentListMenu(viewer, type);
        }));
        viewer.showFormWindow(form);
    }

    private void openStaffMenu(Player staff) {
        String role = staffRole(staff);
        if (staffRank(role) == 0) { staff.sendMessage("§cНет доступа к /staff."); return; }
        FormWindowSimple form = new FormWindowSimple("§6LocalFast §8| §lПанель персонала",
                "§fДолжность: §e" + staffRoleLabel(role) + "\n§7Доступ зависит от ранга.");
        List<String> actions = new ArrayList<>();
        form.addButton(new ElementButton("§eПредупредить игрока")); actions.add("warn");
        if (maxMuteMinutes(role) != 0L) { form.addButton(new ElementButton("§6Выдать мут")); actions.add("mute"); }
        if (canKick(role)) { form.addButton(new ElementButton("§cКикнуть игрока")); actions.add("kick"); }
        if (canBan(role)) { form.addButton(new ElementButton("§4Выдать бан")); actions.add("ban"); }
        if (canUnmute(role)) { form.addButton(new ElementButton("§aСнять мут")); actions.add("unmute"); }
        if (canUnban(role)) { form.addButton(new ElementButton("§aСнять бан")); actions.add("unban"); }
        int utilityStart = actions.size();
        boolean welcomeEnabled = staffData.getBoolean("welcome.enabled." + staff.getUniqueId(), true);
        form.addButton(new ElementButton(welcomeEnabled ? "§aПриветствие: ВКЛ" : "§cПриветствие: ВЫКЛ"));
        form.addButton(new ElementButton("§bСтатистика игрока"));
        form.addButton(new ElementButton("§dСписок администрации"));
        form.addButton(new ElementButton("§eДолжности и мои команды"));
        form.addButton(new ElementButton("§bПроверить игрока"));
        form.addButton(new ElementButton("§6История действий"));
        if (canUnban(role) || canUnmute(role)) form.addButton(new ElementButton("§cАктивные баны и муты"));
        if (staffRank(role) >= 5) form.addButton(new ElementButton("§6Warp"));
        if (staffRank(role) >= 7) {
            form.addButton(new ElementButton("§aFly"));
            form.addButton(new ElementButton("§cVanish"));
            form.addButton(new ElementButton("§6Управление должностями"));
        }
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id < 0) return;
            if (id < utilityStart) { if (id < actions.size()) openStaffTargetMenu(staff, actions.get(id)); return; }
            int utility = id - utilityStart;
            if (utility == 0) {
                String path = "welcome.enabled." + staff.getUniqueId();
                boolean enabled = !staffData.getBoolean(path, true);
                staffData.set(path, enabled);
                staffData.save();
                staff.sendMessage(enabled ? "§aПриветствие при входе включено." : "§eПриветствие при входе выключено.");
                openStaffMenu(staff);
            } else if (utility == 1) openStaffStatsTargetMenu(staff);
            else if (utility == 2) showStaffRoster(staff);
            else if (utility == 3) showStaffHelp(staff);
            else if (utility == 4) openStaffCheckTargetMenu(staff);
            else if (utility == 5) showStaffHistory(staff, "");
            else {
                int cursor = 6;
                if (canUnban(role) || canUnmute(role)) {
                    if (utility == cursor) { openActivePunishmentsMenu(staff); return; }
                    cursor++;
                }
                if (staffRank(role) >= 5) {
                    if (utility == cursor) { openWarpMenu(staff); return; }
                    cursor++;
                }
                if (staffRank(role) >= 7) {
                    if (utility == cursor) { toggleFly(staff); return; }
                    if (utility == cursor + 1) { toggleVanish(staff); return; }
                    if (utility == cursor + 2) { openAdminStaffAssignMenu(staff); return; }
                }
            }
        }));
        staff.showFormWindow(form);
    }

    private void openStaffStatsTargetMenu(Player staff) {
        List<Player> online = new ArrayList<>(getServer().getOnlinePlayers().values());
        if (online.isEmpty()) { staff.sendMessage("§cНет игроков онлайн."); return; }
        FormWindowSimple form = new FormWindowSimple("§bСтатистика игрока", "§7Выбери игрока:");
        for (Player target : online) form.addButton(new ElementButton(target.getName()));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id < 0 || id >= online.size()) return;
            Player target = online.get(id); initialize(target);
            String key = playerKey(target);
            staff.sendMessage("§6Статистика §f" + target.getName() + " §7(" + target.getUniqueId() + ")");
            staff.sendMessage("§fУровень: §e" + get(target, "level", 1) + " §fXP: §a" + get(target, "xp", 0) + " §fМонеты: §6" + getBalance(target));
            staff.sendMessage("§cСила: " + get(target, "strength", 0) + " §cЗдоровье: " + get(target, "health", 0)
                    + " §aЛовкость: " + get(target, "agility", 0) + " §bИнтеллект: " + get(target, "intelligence", 0));
            staff.sendMessage("§7Предупреждений: " + staffData.getInt("warnings." + target.getUniqueId(), 0));
        }));
        staff.showFormWindow(form);
    }

    private void showOnlineStaff(Player viewer) {
        viewer.sendMessage("§6§lАдминистрация онлайн:");
        int count = 0;
        for (Player online : getServer().getOnlinePlayers().values()) {
            String role = staffRole(online);
            if (staffRank(role) == 0) continue;
            String nameColor = "operator".equals(role) ? "§e" : "§f";
            viewer.sendMessage(nameColor + online.getName() + staffChatTag(role));
            count++;
        }
        if (count == 0) viewer.sendMessage("§7Сейчас сотрудников нет онлайн.");
    }


    private String staffNameByUuid(String uuid) {
        try {
            Player online = getServer().getPlayer(UUID.fromString(uuid)).orElse(null);
            if (online != null) return online.getName();
        } catch (IllegalArgumentException ignored) { }
        if (staffData.exists("knownPlayers")) {
            for (String known : staffData.getSection("knownPlayers").getKeys(false)) {
                if (uuid.equalsIgnoreCase(staffData.getString("knownPlayers." + known + ".uuid", ""))) {
                    return staffData.getString("knownPlayers." + known + ".name", known);
                }
            }
        }
        return uuid;
    }

    private void showStaffRoster(Player viewer) {
        Map<String, String> roster = new HashMap<>();
        if (staffData.exists("staff")) {
            for (String uuid : staffData.getSection("staff").getKeys(false)) {
                String rank = staffData.getString("staff." + uuid, "");
                if (staffRank(rank) > 0) roster.put(uuid, rank);
            }
        }
        String operatorUuid = getConfig().getString("operator.uuid", "");
        if (operatorUuid != null && !operatorUuid.trim().isEmpty()) roster.put(operatorUuid, "operator");
        viewer.sendMessage("§6§lПолный состав администрации:");
        if (roster.isEmpty()) { viewer.sendMessage("§7Список администрации пуст."); return; }
        for (Map.Entry<String, String> entry : roster.entrySet()) {
            String uuid = entry.getKey();
            String nickname = staffNameByUuid(uuid);
            boolean online = false;
            try {
                Player found = getServer().getPlayer(UUID.fromString(uuid)).orElse(null);
                online = found != null && found.isOnline();
            } catch (IllegalArgumentException ignored) { }
            viewer.sendMessage(staffRoleColor(entry.getValue()) + nickname + " §8— "
                    + staffRoleLabel(entry.getValue()) + (online ? " §a● онлайн" : " §7○ не в сети"));
        }
    }

    private void showPlayerModeration(CommandSender viewer, String requestedName) {
        String uuid = knownPlayerUuid(requestedName);
        if (uuid == null) { viewer.sendMessage("§cИгрок не найден. Он должен хотя бы один раз зайти на сервер."); return; }
        String name = knownPlayerName(requestedName, uuid);
        Player online = getServer().getPlayer(requestedName);
        viewer.sendMessage("§6§lНаказания игрока §f" + name);
        viewer.sendMessage("§eПредупреждения: §f" + staffData.getInt("warnings." + uuid, 0));
        for (String type : new String[]{"bans", "mutes"}) {
            String path = "punishments." + type + "." + uuid;
            if (!staffData.exists(path)) continue;
            long expires = staffData.getLong(path + ".expires", 0L);
            if (expires != -1L && expires <= System.currentTimeMillis()) {
                staffData.remove(path); staffData.save(); continue;
            }
            viewer.sendMessage((type.equals("bans") ? "§cБАН: " : "§6МУТ: ")
                    + remainingPunishmentTime(expires) + " §7| Причина: "
                    + staffData.getString(path + ".reason", "Не указана")
                    + " §7| Выдал: " + staffData.getString(path + ".by", "Неизвестно"));
        }
        viewer.sendMessage(online == null ? "§7Игрок не в сети." : "§aИгрок сейчас онлайн.");
        staffLog(viewer.getName(), "check-player", name, "просмотр статуса наказаний");
    }

    private void openStaffCheckTargetMenu(Player staff) {
        List<Player> online = new ArrayList<>(getServer().getOnlinePlayers().values());
        if (online.isEmpty()) { staff.sendMessage("§cНет игроков онлайн."); return; }
        FormWindowSimple form = new FormWindowSimple("§bПроверка игрока", "§7Выбери игрока:");
        for (Player target : online) form.addButton(new ElementButton(target.getName()));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index >= 0 && index < online.size()) showPlayerModeration(staff, online.get(index).getName());
        }));
        staff.showFormWindow(form);
    }

    private void showStaffHelp(Player viewer) {
        String role = staffRole(viewer);
        viewer.sendMessage("§6§lДолжности LocalFast:");
        for (String rank : STAFF_ROLES) {
            String access = staffRank(rank) >= 7 ? "Бан, мут, кик, снятие наказаний, fly, vanish, warp, статистика"
                    : staffRank(rank) >= 6 ? "Бан, мут, кик, снятие наказаний, статистика, warp"
                    : staffRank(rank) >= 5 ? "Бан, мут, кик, снятие мута, статистика, warp"
                    : staffRank(rank) >= 4 ? "Бан до 60 мин., мут, кик, статистика"
                    : staffRank(rank) >= 3 ? "Мут, кик, статистика"
                    : staffRank(rank) >= 2 ? "Мут, статистика"
                    : "Предупреждения, статистика";
            viewer.sendMessage(staffRoleColor(rank) + staffRank(rank) + ". " + staffRoleLabel(rank) + " §8— §7" + access);
        }
        viewer.sendMessage("§e8. Оператор §8— §7полный доступ к панели и управлению персоналом");
        viewer.sendMessage("§6§lТвои команды:");
        viewer.sendMessage("§b/staff §7— открыть панель");
        viewer.sendMessage("§e/staff warn <игрок> <причина>");
        if (maxMuteMinutes(role) != 0L) viewer.sendMessage("§e/staff mute <игрок> <минуты> <причина>");
        if (canKick(role)) viewer.sendMessage("§e/staff kick <игрок> <причина>");
        if (canBan(role)) viewer.sendMessage("§e/staff ban <игрок> <минуты> <причина>");
        if (canUnmute(role)) viewer.sendMessage("§e/staff unmute <игрок>");
        if (canUnban(role)) viewer.sendMessage("§e/staff unban <игрок>");
        if (staffRank(role) >= 5) viewer.sendMessage("§e/staff warp §7— меню варпов");
        if (staffRank(role) >= 7) viewer.sendMessage("§e/staff fly|vanish");
        viewer.sendMessage("§e/staff check <ник> §7— проверить наказания и предупреждения");
        viewer.sendMessage("§e/staff warnings <ник> §7— число предупреждений");
        if (staffRank(role) >= 7) viewer.sendMessage("§e/staff clearwarns <ник> §7— сбросить предупреждения");
        viewer.sendMessage("§e/staff roster §7— весь состав; /staff online — кто в сети");
        viewer.sendMessage("§e/staff welcome <on|off|status> §7— приветствие о входе сотрудника увидит весь сервер");
        if (role.equals("admin") || role.equals("operator")) viewer.sendMessage("§e/staff promote|demote|setrank ...");
        if (role.equals("operator")) {
            viewer.sendMessage("§6§lКоманды оператора:");
            viewer.sendMessage("§e/lfadmin staff add|remove|list ...");
            viewer.sendMessage("§e/lfadmin money give|take|set|reset ...");
            viewer.sendMessage("§e/lfadmin xp <игрок> <количество>");
            viewer.sendMessage("§e/lfadmin reset <игрок> <level|stats|quests|all>");
            viewer.sendMessage("§e/lfadmin warp set|delete|list ...");

        }
    }

    private void toggleFly(Player player) {
        String role = staffRole(player);
        if (staffRank(role) < 7) { player.sendMessage("§cFly доступен только администратору и оператору."); return; }
        boolean enabled = !flyingPlayers.contains(player.getUniqueId());
        if (enabled) {
            flyingPlayers.add(player.getUniqueId());
            player.setAllowFlight(true);
        } else {
            flyingPlayers.remove(player.getUniqueId());
            player.setAllowFlight(false);
        }
        staffLog(player.getName(), "fly", player.getName(), enabled ? "on" : "off");
        player.sendMessage(enabled ? "§aРежим полёта включён." : "§eРежим полёта выключен.");
    }

    private void toggleVanish(Player player) {
        if (staffRank(staffRole(player)) < 7) { player.sendMessage("§cVanish доступен только администратору и оператору."); return; }
        UUID id = player.getUniqueId();
        if (vanishedPlayers.remove(id)) {
            for (Player viewer : getServer().getOnlinePlayers().values()) viewer.showPlayer(player);
            player.sendMessage("§eVanish выключен.");
            staffLog(player.getName(), "vanish", player.getName(), "off");
        } else {
            vanishedPlayers.add(id);
            for (Player viewer : getServer().getOnlinePlayers().values()) if (!viewer.getUniqueId().equals(id)) viewer.hidePlayer(player);
            player.sendMessage("§aVanish включён.");
            staffLog(player.getName(), "vanish", player.getName(), "on");
        }
    }

    private void openWarpMenu(Player staff) {
        FormWindowSimple form = new FormWindowSimple("§6LocalFast §8| §lWarp", "§7Выбери точку для телепортации:");
        List<String> names = new ArrayList<>();
        if (getConfig().exists("warps")) names.addAll(getConfig().getSection("warps").getKeys(false));
        if (names.isEmpty()) {
            form.addButton(new ElementButton("§eСпавн"));
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> { if (!form.wasClosed() && form.getResponse() != null && form.getResponse().getClickedButtonId() == 0) teleportToSpawn(staff); }));
        } else {
            for (String name : names) form.addButton(new ElementButton("§e" + name));
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed() || form.getResponse() == null || !staff.isOnline()) return;
                int id = form.getResponse().getClickedButtonId();
                if (id < 0 || id >= names.size()) return;
                String path = "warps." + names.get(id);
                Level world = getServer().getLevelByName(getConfig().getString(path + ".world", ""));
                if (world == null) { staff.sendMessage("§cМир варпа не загружен."); return; }
                Position destination = new Position(getConfig().getDouble(path + ".x"), getConfig().getDouble(path + ".y"),
                        getConfig().getDouble(path + ".z"), world);
                if (staff.teleport(destination)) staff.sendMessage("§aТелепортирован на варп " + names.get(id) + ".");
            }));
        }
        staff.showFormWindow(form);
    }

    private void openOperatorStaffAssignMenu(Player operator) {
        List<Player> online = new ArrayList<>(getServer().getOnlinePlayers().values());
        if (online.isEmpty()) { operator.sendMessage("§cНет игроков онлайн."); return; }
        FormWindowSimple form = new FormWindowSimple("§6Назначение персонала", "§7Выбери игрока. Затем выбери его должность.");
        for (Player target : online) {
            String current = staffRole(target);
            form.addButton(new ElementButton("§f" + target.getName() + "\n" + staffRoleColor(current) + staffRoleLabel(current)));
        }
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index < 0 || index >= online.size()) return;
            openOperatorRoleSelectMenu(operator, online.get(index), false);
        }));
        operator.showFormWindow(form);
    }

    private void openOperatorRoleSelectMenu(Player operator, Player target, boolean self) {
        FormWindowSimple form = new FormWindowSimple("§6Должность: " + target.getName(),
                "§fИгрок: §e" + target.getName() + "\n§7Выбери должность ниже:");
        for (String role : STAFF_ROLES) form.addButton(new ElementButton(staffRoleColor(role) + staffRoleLabel(role)));
        form.addButton(new ElementButton("§7Снять должность"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline() || !target.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index < 0) return;
            String path = "staff." + target.getUniqueId();
            if (index < STAFF_ROLES.length) staffData.set(path, STAFF_ROLES[index]);
            else staffData.remove(path);
            staffData.save();
            if (self) {
                getConfig().set("operator.test-role", index < STAFF_ROLES.length ? STAFF_ROLES[index] : "");
                saveConfig();
            }
            refreshStaffNameTag(target);
            refreshStaffModes(target);
            staffLog(operator.getName(), "staff-rank-menu", target.getName(), index < STAFF_ROLES.length ? STAFF_ROLES[index] : "remove");
            operator.sendMessage("§aДолжность обновлена: §f" + target.getName() + " §7— " +
                    (index < STAFF_ROLES.length ? staffRoleColor(STAFF_ROLES[index]) + staffRoleLabel(STAFF_ROLES[index]) : "§7Игрок"));
            if (self) openOperatorMenu(operator);
        }));
        operator.showFormWindow(form);
    }

    private void openOperatorSelfRankMenu(Player operator) {
        FormWindowSimple form = new FormWindowSimple("§dТест должностей", "§fТекущий тестовый ранг: §e" +
                staffRoleLabel(staffRole(operator)) + "\n§7Выбери ранг для проверки прав /staff. Операторский доступ /lfadmin сохранится.");
        for (String role : STAFF_ROLES) form.addButton(new ElementButton(staffRoleColor(role) + staffRoleLabel(role)));
        form.addButton(new ElementButton("§eВернуть ранг оператора"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index < 0) return;
            getConfig().set("operator.test-role", index < STAFF_ROLES.length ? STAFF_ROLES[index] : "");
            saveConfig();
            refreshStaffNameTag(operator);
            refreshStaffModes(operator);
            operator.sendMessage(index < STAFF_ROLES.length
                    ? "§aТестовый ранг: " + staffRoleColor(STAFF_ROLES[index]) + staffRoleLabel(STAFF_ROLES[index])
                    : "§eВозвращён ранг оператора.");
            openOperatorMenu(operator);
        }));
        operator.showFormWindow(form);
    }


    private static final class CastleBlock {
        final int x, y, z, id;
        CastleBlock(int x, int y, int z, int id) { this.x=x; this.y=y; this.z=z; this.id=id; }
    }

    private void openCastleBuilderMenu(Player operator) {
        if (!isOperator(operator)) return;
        String worldName = getConfig().getString("castle.world", "spawn");
        boolean centerSet = getConfig().exists("castle.center-x") && getConfig().exists("castle.center-y")
                && getConfig().exists("castle.center-z");
        String centerText = centerSet ? "§aЦентр: X " + getConfig().getInt("castle.center-x")
                + ", Y " + getConfig().getInt("castle.center-y") + ", Z " + getConfig().getInt("castle.center-z")
                : "§cЦентр не выбран";
        String progress = castleBuildActive ? "§eСтроится: " + castleBuildPlaced + "/" + castleBuildTotal
                : "§7Строитель остановлен";
        FormWindowSimple form = new FormWindowSimple("§6Строитель замка",
                "§fМир: §b" + worldName + "\n" + centerText + "\n" + progress
                        + "\n§7Полный RPG-спавн 1000×1000: замок, районы, дороги, рынок, парки, стены и ворота.");
        form.addButton(new ElementButton("§aВыбрать центр здесь"));
        form.addButton(new ElementButton("§bПроверить площадку"));
        form.addButton(new ElementButton("§6Начать строительство"));
        form.addButton(new ElementButton("§cОстановить строительство"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) {
                if (!operator.getLevel().getFolderName().equalsIgnoreCase("spawn")) {
                    operator.sendMessage("§cСначала перейди в мир spawn: /world tp spawn.");
                    openCastleBuilderMenu(operator);
                    return;
                }
                int x = operator.getFloorX(), z = operator.getFloorZ();
                int y = operator.getLevel().getHighestBlockAt(x, z) + 1;
                getConfig().set("castle.world", "spawn");
                getConfig().set("castle.center-x", x);
                getConfig().set("castle.center-y", y);
                getConfig().set("castle.center-z", z);
                saveConfig();
                operator.sendMessage("§aЦентр замка сохранён: " + x + ", " + y + ", " + z + ".");
                openCastleBuilderMenu(operator);
            } else if (id == 1) {
                validateCastleSite(operator, false);
                openCastleBuilderMenu(operator);
            } else if (id == 2) {
                startCastleBuild(operator);
                openCastleBuilderMenu(operator);
            } else if (id == 3) {
                castleBuildActive = false;
                if (castleBuildTask != null) { castleBuildTask.cancel(); castleBuildTask = null; }
                operator.sendMessage("§eСтроительство замка остановлено. Уже поставленные блоки оставлены на месте.");
                openCastleBuilderMenu(operator);
            } else if (id == 4) openOperatorWorldMenu(operator);
        }));
        operator.showFormWindow(form);
    }

    /**
     * Full fantasy RPG spawn core; extended below into a 1000 x 1000 playable hub.
     * Coordinates are relative to the operator-selected center in the flat spawn.
     */
    private List<CastleBlock> createCastlePlan(int cx, int baseY, int cz) {
        Map<String, CastleBlock> blocks = new java.util.LinkedHashMap<>();

        // Main paved lobby square (81 x 81) with a contrasting border and cross paths.
        for (int x=-40; x<=40; x++) for (int z=-40; z<=40; z++) {
            int id = ((x + z) & 1) == 0 ? 98 : 4;
            if (Math.abs(x)==40 || Math.abs(z)==40) id=24;
            if ((Math.abs(x)<=2 || Math.abs(z)<=2) && Math.abs(x)<39 && Math.abs(z)<39) id=24;
            addCastleBlock(blocks,cx+x,baseY,cz+z,id);
        }

        // Castle outer curtain wall with a proper open entrance and merlons.
        for (int x=-32; x<=32; x++) for (int z=-29; z<=29; z++) {
            if (Math.abs(x)==32 || Math.abs(z)==29) {
                boolean gate = z==29 && Math.abs(x)<=6;
                if (!gate) {
                    for (int y=1; y<=13; y++) addCastleBlock(blocks,cx+x,baseY+y,cz+z,98);
                    for (int y=14; y<=15; y++) if (((x+z)&1)==0) addCastleBlock(blocks,cx+x,baseY+y,cz+z,98);
                }
            }
        }

        // Four round corner towers, with wider stone bases, upper floors and roofs.
        for (int tx : new int[]{-32,32}) for (int tz : new int[]{-29,29}) {
            for (int y=1; y<=30; y++) for (int dx=-7; dx<=7; dx++) for (int dz=-7; dz<=7; dz++) {
                int d=dx*dx+dz*dz;
                if (d<=49 && d>=36) addCastleBlock(blocks,cx+tx+dx,baseY+y,cz+tz+dz, y<24?98:45);
            }
            for (int dx=-7; dx<=7; dx++) for (int dz=-7; dz<=7; dz++) {
                int d=dx*dx+dz*dz;
                if (d<=49 && d>=36) addCastleBlock(blocks,cx+tx+dx,baseY+31,cz+tz+dz,24);
                if (d<=25 && d>=16) addCastleBlock(blocks,cx+tx+dx,baseY+32,cz+tz+dz,45);
            }
            for (int y=8; y<=23; y+=7) for (int dx=-7; dx<=7; dx++) for (int dz=-7; dz<=7; dz++)
                if (dx*dx+dz*dz<=49 && dx*dx+dz*dz>=36)
                    addCastleBlock(blocks,cx+tx+dx,baseY+y,cz+tz+dz,20);
            for (int k=0;k<6;k++) {
                int radius=5-k;
                for (int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++)
                    if(Math.abs(dx)+Math.abs(dz)<=radius)
                        addCastleBlock(blocks,cx+tx+dx,baseY+33+k,cz+tz+dz,45);
            }
            addCastleBlock(blocks,cx+tx,baseY+39,cz+tz,89);
        }

        // Main keep: broad two-storey stone hall, tall central roof and lit windows.
        for (int x=-19;x<=19;x++) for(int z=-17;z<=17;z++) {
            if(Math.abs(x)==19 || Math.abs(z)==17) {
                for(int y=1;y<=23;y++) addCastleBlock(blocks,cx+x,baseY+y,cz-13+z,98);
            }
        }
        for(int y=5;y<=19;y+=7) for(int x=-19;x<=19;x++) {
            addCastleBlock(blocks,cx+x,baseY+y,cz-30,24);
            addCastleBlock(blocks,cx+x,baseY+y,cz+4,24);
        }
        for(int x=-19;x<=19;x++) for(int z=-17;z<=17;z++) {
            if(Math.abs(x)==19 || Math.abs(z)==17) {
                addCastleBlock(blocks,cx+x,baseY+24,cz-13+z,45);
                addCastleBlock(blocks,cx+x,baseY+25,cz-13+z,98);
            }
        }
        // Stepped roof silhouette and ridge.
        for(int layer=0;layer<7;layer++) {
            int rx=18-layer*2, rz=16-layer*2;
            for(int x=-rx;x<=rx;x++) for(int z=-rz;z<=rz;z++)
                if(Math.abs(x)==rx || Math.abs(z)==rz)
                    addCastleBlock(blocks,cx+x,baseY+26+layer,cz-13+z,layer<3?45:98);
        }
        for(int y=1;y<=12;y++) for(int x=-2;x<=2;x++) {
            addCastleBlock(blocks,cx+x,baseY+y,cz+4,45);
        }
        // Grand entrance arch, gold-toned trim and banners.
        for(int y=1;y<=13;y++) {
            addCastleBlock(blocks,cx-7,baseY+y,cz+16,24);
            addCastleBlock(blocks,cx+7,baseY+y,cz+16,24);
        }
        for(int x=-7;x<=7;x++) addCastleBlock(blocks,cx+x,baseY+14,cz+16,45);
        for(int y=4;y<=12;y++) {
            addCastleBlock(blocks,cx-8,baseY+y,cz+17,35);
            addCastleBlock(blocks,cx+8,baseY+y,cz+17,35);
        }
        addCastleBlock(blocks,cx,baseY+16,cz+16,89);

        // Gatehouse towers flank the entrance.
        for(int tx : new int[]{-10,10}) {
            for(int y=1;y<=19;y++) for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++)
                if(Math.abs(dx)==3 || Math.abs(dz)==3) addCastleBlock(blocks,cx+tx+dx,baseY+y,cz+27+dz,98);
            for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++)
                if(Math.abs(dx)==4 || Math.abs(dz)==4) addCastleBlock(blocks,cx+tx+dx,baseY+20,cz+27+dz,45);
            addCastleBlock(blocks,cx+tx,baseY+21,cz+27,89);
        }

        // Arrival fountain in the center of the square.
        for(int x=-7;x<=7;x++) for(int z=-7;z<=7;z++) {
            int d=x*x+z*z;
            if(d<=49) addCastleBlock(blocks,cx+x,baseY+1,cz+22+z, d>=36?24:98);
            if(d<=16) addCastleBlock(blocks,cx+x,baseY+2,cz+22+z,9);
        }
        for(int y=2;y<=6;y++) addCastleBlock(blocks,cx,baseY+y,cz+22,98);
        addCastleBlock(blocks,cx,baseY+7,cz+22,89);

        // Four landscaped garden beds, paths, flower accents and ornamental trees.
        for(int gx : new int[]{-25,25}) for(int gz : new int[]{-20,20}) {
            for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) {
                if(Math.abs(x)==8 || Math.abs(z)==8) addCastleBlock(blocks,cx+gx+x,baseY+1,cz+gz+z,24);
                else if(Math.abs(x)>2 || Math.abs(z)>2) addCastleBlock(blocks,cx+gx+x,baseY+1,cz+gz+z,2);
            }
            for(int y=1;y<=5;y++) addCastleBlock(blocks,cx+gx,baseY+y,cz+gz,17);
            for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++)
                if(Math.abs(dx)+Math.abs(dz)<=5) addCastleBlock(blocks,cx+gx+dx,baseY+6,cz+gz+dz,18);
            addCastleBlock(blocks,cx+gx,baseY+7,cz+gz,18);
        }

        // Small market / guild buildings on both sides of the arrival avenue.
        for(int side : new int[]{-1,1}) for(int n=0;n<2;n++) {
            int hx=side*53, hz=-18+n*34;
            for(int x=-7;x<=7;x++) for(int z=-6;z<=6;z++) {
                if(Math.abs(x)==7 || Math.abs(z)==6)
                    for(int y=1;y<=8;y++) addCastleBlock(blocks,cx+hx+x,baseY+y,cz+hz+z,45);
                addCastleBlock(blocks,cx+hx+x,baseY,cz+hz+z,5);
            }
            for(int y=3;y<=6;y++) {
                addCastleBlock(blocks,cx+hx-7,baseY+y,cz+hz,20);
                addCastleBlock(blocks,cx+hx+7,baseY+y,cz+hz,20);
            }
            for(int layer=0;layer<5;layer++) {
                int rx=7-layer, rz=6-layer;
                for(int x=-rx;x<=rx;x++) for(int z=-rz;z<=rz;z++)
                    if(Math.abs(x)==rx || Math.abs(z)==rz)
                        addCastleBlock(blocks,cx+hx+x,baseY+9+layer,cz+hz+z,5);
            }
            addCastleBlock(blocks,cx+hx,baseY+14,cz+hz,89);
            for(int x=-2;x<=2;x++) addCastleBlock(blocks,cx+hx+x,baseY+1,cz+hz+6,24);
        }

        // Decorative avenue lanterns and perimeter lights.
        for(int z=-35;z<=35;z+=10) for(int side : new int[]{-1,1}) {
            int x=side*7;
            addCastleBlock(blocks,cx+x,baseY+1,cz+z,98);
            for(int y=2;y<=6;y++) addCastleBlock(blocks,cx+x,baseY+y,cz+z,85);
            addCastleBlock(blocks,cx+x,baseY+7,cz+z,89);
        }
        for(int x=-38;x<=38;x+=19) for(int z : new int[]{-38,38}) {
            addCastleBlock(blocks,cx+x,baseY+1,cz+z,98);
            for(int y=2;y<=5;y++) addCastleBlock(blocks,cx+x,baseY+y,cz+z,85);
            addCastleBlock(blocks,cx+x,baseY+6,cz+z,89);
        }


        // Expand the lobby into a complete 1000 x 1000 playable spawn.
        addSpawnRoadNetwork(blocks, cx, baseY, cz);
        addSpawnPerimeter(blocks, cx, baseY, cz);
        addSpawnDistricts(blocks, cx, baseY, cz);

        return new ArrayList<>(blocks.values());
    }

    private void addSpawnRoadNetwork(Map<String, CastleBlock> blocks, int cx, int y, int cz) {
        // Main cardinal avenues, exactly within the 1000 x 1000 footprint.
        for (int p=-500; p<500; p++) {
            for (int w=-8; w<=8; w++) {
                int path = (Math.abs(w)==8) ? 24 : (((p/6 + w/3)&1)==0 ? 98 : 4);
                addCastleBlock(blocks,cx+w,y,cz+p,path);
                addCastleBlock(blocks,cx+p,y,cz+w,path);
            }
            if (p%24==0) {
                addSpawnLamp(blocks,cx-12,y,cz+p);
                addSpawnLamp(blocks,cx+12,y,cz+p);
                addSpawnLamp(blocks,cx+p,y,cz-12);
                addSpawnLamp(blocks,cx+p,y,cz+12);
            }
        }
        // Two rectangular ring boulevards connect all four districts.
        for (int r : new int[]{220, 430}) {
            for (int p=-r; p<=r; p++) {
                for (int w=-6; w<=6; w++) {
                    int id = Math.abs(w)==6 ? 24 : (((p/5+w/3)&1)==0 ? 98 : 4);
                    addCastleBlock(blocks,cx+r+w,y,cz+p,id);
                    addCastleBlock(blocks,cx-r+w,y,cz+p,id);
                    addCastleBlock(blocks,cx+p,y,cz+r+w,id);
                    addCastleBlock(blocks,cx+p,y,cz-r+w,id);
                }
            }
        }
        // Radial connectors from ring road to the outer gate districts.
        for (int sign : new int[]{-1,1}) {
            for (int p=230; p<=430; p++) for (int w=-5; w<=5; w++) {
                int id=Math.abs(w)==5?24:98;
                addCastleBlock(blocks,cx+sign*p,y,cz+w,id);
                addCastleBlock(blocks,cx+w,y,cz+sign*p,id);
            }
        }
    }

    private void addSpawnPerimeter(Map<String, CastleBlock> blocks, int cx, int y, int cz) {
        // Continuous outer boundary with four wide player entrances.
        for (int p=-500; p<500; p++) {
            boolean gate = Math.abs(p)<18;
            if (!gate) {
                for (int h=1;h<=8;h++) {
                    addCastleBlock(blocks,cx-500,y+h,cz+p,98);
                    addCastleBlock(blocks,cx+499,y+h,cz+p,98);
                    addCastleBlock(blocks,cx+p,y+h,cz-500,98);
                    addCastleBlock(blocks,cx+p,y+h,cz+499,98);
                }
                if ((p&1)==0) {
                    addCastleBlock(blocks,cx-500,y+9,cz+p,45);
                    addCastleBlock(blocks,cx+499,y+9,cz+p,45);
                    addCastleBlock(blocks,cx+p,y+9,cz-500,45);
                    addCastleBlock(blocks,cx+p,y+9,cz+499,45);
                }
            }
        }
        // Four corner watchtowers.
        for (int sx : new int[]{-1,1}) for (int sz : new int[]{-1,1}) {
            int tx=sx*478, tz=sz*478;
            for (int h=1;h<=22;h++) for (int dx=-7;dx<=7;dx++) for (int dz=-7;dz<=7;dz++) {
                int d=dx*dx+dz*dz;
                if (d>=36 && d<=49) addCastleBlock(blocks,cx+tx+dx,y+h,cz+tz+dz,98);
            }
            for (int dx=-8;dx<=8;dx++) for (int dz=-8;dz<=8;dz++)
                if (dx*dx+dz*dz>=49 && dx*dx+dz*dz<=64)
                    addCastleBlock(blocks,cx+tx+dx,y+23,cz+tz+dz,45);
            for (int layer=0;layer<6;layer++) {
                int radius=6-layer;
                for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++)
                    if (Math.abs(dx)==radius || Math.abs(dz)==radius)
                        addCastleBlock(blocks,cx+tx+dx,y+24+layer,cz+tz+dz,45);
            }
            addCastleBlock(blocks,cx+tx,y+30,cz+tz,89);
        }
        // Monumental gate arches at the four cardinal entrances.
        for (int sign : new int[]{-1,1}) {
            for (int h=1;h<=14;h++) {
                addCastleBlock(blocks,cx+sign*500,y+h,cz-19,45);
                addCastleBlock(blocks,cx+sign*500,y+h,cz+19,45);
                addCastleBlock(blocks,cx-19,y+h,cz+sign*500,45);
                addCastleBlock(blocks,cx+19,y+h,cz+sign*500,45);
            }
            for (int p=-19;p<=19;p++) {
                addCastleBlock(blocks,cx+sign*500,y+15,cz+p,98);
                addCastleBlock(blocks,cx+p,y+15,cz+sign*500,98);
            }
        }
    }

    private void addSpawnDistricts(Map<String, CastleBlock> blocks, int cx, int y, int cz) {
        // Four playable themed quarters. Buildings are separated by paths and plazas.
        int[][] districts = {
            {-315,-315,0}, {315,-315,1}, {-315,315,2}, {315,315,3}
        };
        for (int[] d : districts) {
            int dx=d[0], dz=d[1], theme=d[2];
            // District plaza with a garden island and a small monument.
            for (int x=-48;x<=48;x++) for (int z=-48;z<=48;z++) {
                if (Math.abs(x)==48 || Math.abs(z)==48)
                    addCastleBlock(blocks,cx+dx+x,y,cz+dz+z,24);
                if ((Math.abs(x)<=2 || Math.abs(z)<=2) && Math.abs(x)<47 && Math.abs(z)<47)
                    addCastleBlock(blocks,cx+dx+x,y,cz+dz+z,98);
            }
            for (int x=-12;x<=12;x++) for (int z=-12;z<=12;z++) {
                if (x*x+z*z<=144) addCastleBlock(blocks,cx+dx+x,y+1,cz+dz+z,2);
                if (x*x+z*z<=64) addCastleBlock(blocks,cx+dx+x,y+2,cz+dz+z,38);
            }
            addCastleBlock(blocks,cx+dx,y+3,cz+dz,89);

            // Eight distinct service buildings around each district square.
            addSpawnBuilding(blocks,cx+dx-29,y,cz+dz-29,theme,19,17);
            addSpawnBuilding(blocks,cx+dx+29,y,cz+dz-29,theme,19,17);
            addSpawnBuilding(blocks,cx+dx-29,y,cz+dz+29,theme,19,17);
            addSpawnBuilding(blocks,cx+dx+29,y,cz+dz+29,theme,19,17);
            addSpawnBuilding(blocks,cx+dx,y,cz+dz-67,theme,23,19);
            addSpawnBuilding(blocks,cx+dx,y,cz+dz+67,theme,23,19);
            addSpawnBuilding(blocks,cx+dx-67,y,cz+dz,theme,19,23);
            addSpawnBuilding(blocks,cx+dx+67,y,cz+dz,theme,19,23);

            // Trees and flower beds on the outer corners of each district.
            for (int sx : new int[]{-1,1}) for (int sz : new int[]{-1,1}) {
                for (int n=0;n<7;n++) {
                    int tx=dx+sx*(82+n*13), tz=dz+sz*(82+((n*17)%7)*13);
                    addSpawnTree(blocks,cx+tx,y,cz+tz,(n+theme)%3);
                }
            }
        }
    }

    private void addSpawnBuilding(Map<String, CastleBlock> blocks, int x, int y, int z,
                                  int theme, int halfW, int halfD) {
        int wall = theme==1 ? 45 : (theme==2 ? 24 : 98);
        int roof = theme==1 ? 5 : (theme==2 ? 45 : 98);
        // Foundation, walkable interior floor and perimeter walls with open doorway.
        for (int dx=-halfW;dx<=halfW;dx++) for (int dz=-halfD;dz<=halfD;dz++) {
            addCastleBlock(blocks,x+dx,y, z+dz,5);
            boolean edge=Math.abs(dx)==halfW || Math.abs(dz)==halfD;
            if (edge) for (int h=1;h<=8;h++) {
                boolean door = dz==halfD && Math.abs(dx)<=2 && h<=4;
                boolean window = h>=4 && h<=6 && ((dx%6==0 && Math.abs(dz)==halfD)
                        || (dz%6==0 && Math.abs(dx)==halfW));
                if (!door) addCastleBlock(blocks,x+dx,y+h,z+dz,window?20:wall);
            }
        }
        // Layered pitched roof silhouette.
        for (int layer=0;layer<5;layer++) {
            int rx=halfW-layer*2, rz=halfD-layer*2;
            if (rx<1 || rz<1) break;
            for (int dx=-rx;dx<=rx;dx++) for (int dz=-rz;dz<=rz;dz++)
                if (Math.abs(dx)==rx || Math.abs(dz)==rz)
                    addCastleBlock(blocks,x+dx,y+9+layer,z+dz,roof);
        }
        for (int dx=-2;dx<=2;dx++) addCastleBlock(blocks,x+dx,y+14,z,roof);
        // Lit entrance and signpost.
        addCastleBlock(blocks,x,y+5,z+halfD,89);
        addCastleBlock(blocks,x,y+1,z+halfD+2,85);
        addCastleBlock(blocks,x,y+2,z+halfD+2,85);
    }

    private void addSpawnTree(Map<String, CastleBlock> blocks, int x, int y, int z, int type) {
        int wood = type==1 ? 162 : 17;
        int leaves = type==1 ? 161 : 18;
        int height = type==2 ? 7 : 5;
        for (int h=1;h<=height;h++) addCastleBlock(blocks,x,y+h,z,wood);
        for (int dy=-2;dy<=2;dy++) {
            int radius=dy==2?1: (dy==-2?1:2);
            for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++)
                if (Math.abs(dx)+Math.abs(dz)<=radius+1)
                    addCastleBlock(blocks,x+dx,y+height-1+dy,z+dz,leaves);
        }
    }

    private void addSpawnLamp(Map<String, CastleBlock> blocks, int x, int y, int z) {
        addCastleBlock(blocks,x,y+1,z,98);
        for (int h=2;h<=5;h++) addCastleBlock(blocks,x,y+h,z,85);
        addCastleBlock(blocks,x,y+6,z,89);
        addCastleBlock(blocks,x+1,y+6,z,89);
        addCastleBlock(blocks,x-1,y+6,z,89);
        addCastleBlock(blocks,x,y+6,z+1,89);
        addCastleBlock(blocks,x,y+6,z-1,89);
    }

    private void addCastleBlock(Map<String, CastleBlock> blocks, int x, int y, int z, int id) {
        blocks.put(x+":"+y+":"+z, new CastleBlock(x,y,z,id));
    }

    private boolean validateCastleSite(Player operator, boolean quiet) {
        if (!getConfig().exists("castle.center-x") || !getConfig().exists("castle.center-y")
                || !getConfig().exists("castle.center-z")) {
            if (!quiet) operator.sendMessage("§cСначала выбери центр замка в мире spawn.");
            return false;
        }
        String worldName = getConfig().getString("castle.world", "spawn");
        if (!worldName.equalsIgnoreCase("spawn")) {
            if (!quiet) operator.sendMessage("§cСтроительство разрешено только в мире spawn.");
            return false;
        }
        if (!getServer().isLevelLoaded("spawn") && !getServer().loadLevel("spawn")) {
            if (!quiet) operator.sendMessage("§cМир spawn не удалось загрузить.");
            return false;
        }
        Level world = getServer().getLevelByName("spawn");
        int cx=getConfig().getInt("castle.center-x"), y=getConfig().getInt("castle.center-y"), cz=getConfig().getInt("castle.center-z");
        if (y<world.getMinBlockY() || y+45>=world.getMaxBlockY()) {
            if (!quiet) operator.sendMessage("§cВысота центра вне допустимых границ мира.");
            return false;
        }
        List<CastleBlock> plan=createCastlePlan(cx,y,cz);
        int checked=0;
        try {
            for (CastleBlock b : plan) {
                if (world.getChunk(b.x >> 4,b.z >> 4,true)==null || world.getBlock(b.x,b.y,b.z).getId()!=0) {
                    if (!quiet) operator.sendMessage("§cПлощадка занята или чанк недоступен. Первый конфликт: "
                            + b.x + ", " + b.y + ", " + b.z + ". Ничего не построено.");
                    return false;
                }
                checked++;
            }
        } catch (RuntimeException ex) {
            if (!quiet) operator.sendMessage("§cНе удалось проверить площадку: " + ex.getMessage());
            return false;
        }
        if (!quiet) operator.sendMessage("§aПлощадка свободна. Проверено блоков: " + checked
                + ". Строительство займёт несколько минут и не заменяет существующие блоки.");
        return true;
    }

    private void startCastleBuild(Player operator) {
        if (castleBuildActive) { operator.sendMessage("§eСтроительство уже запущено."); return; }
        if (!validateCastleSite(operator, true)) {
            operator.sendMessage("§cСначала выбери центр и проверь, что площадка свободна.");
            return;
        }
        Level world=getServer().getLevelByName("spawn");
        List<CastleBlock> plan=createCastlePlan(getConfig().getInt("castle.center-x"),
                getConfig().getInt("castle.center-y"),getConfig().getInt("castle.center-z"));
        castleBuildPlaced=0; castleBuildTotal=plan.size(); castleBuildActive=true;
        final int[] cursor={0};
        operator.sendMessage("§aСтроительство замка началось. Блоков в плане: " + castleBuildTotal + ".");
        castleBuildTask=getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (!castleBuildActive) {
                if (castleBuildTask!=null) { castleBuildTask.cancel(); castleBuildTask=null; }
                return;
            }
            int batch=0;
            while (cursor[0]<plan.size() && batch<250) {
                CastleBlock b=plan.get(cursor[0]++);
                try {
                    if (world.getBlock(b.x,b.y,b.z).getId()==0) {
                        world.setBlock(new Position(b.x,b.y,b.z,world),Block.get(b.id),true,true);
                        castleBuildPlaced++;
                    }
                } catch (RuntimeException ex) {
                    getLogger().warning("Castle builder skipped block " + b.x + "," + b.y + "," + b.z + ": " + ex.getMessage());
                }
                batch++;
            }
            if (cursor[0]>=plan.size()) {
                castleBuildActive=false;
                if (castleBuildTask!=null) { castleBuildTask.cancel(); castleBuildTask=null; }
                getConfig().set("castle.completed",true);
                saveConfig();
                for (Player online : getServer().getOnlinePlayers().values())
                    if (isOperator(online)) online.sendMessage("§aЗамок построен! Установлено блоков: " + castleBuildPlaced + ".");
            }
        },1);
    }

    private void openOperatorWorldMenu(Player operator) {
        if (!isOperator(operator)) return;
        FormWindowSimple form = new FormWindowSimple("§6LocalFast §8| §lМиры",
                "§7Управление игровыми мирами 0.14.0\n§fВыбери действие:");
        form.addButton(new ElementButton("§bСписок миров"));
        form.addButton(new ElementButton("§aСоздать spawn (суперплоский)"));
        form.addButton(new ElementButton("§aСоздать spawn2 (суперплоский)"));
        form.addButton(new ElementButton("§aСоздать dungeons"));
        form.addButton(new ElementButton("§aСоздать bosses"));
        form.addButton(new ElementButton("§eЗагрузить мир"));
        form.addButton(new ElementButton("§dИнформация о мире"));
        form.addButton(new ElementButton("§bТелепортироваться в мир"));
        form.addButton(new ElementButton("§cУдалить мир"));
        form.addButton(new ElementButton("§aУстановить точку /spawn здесь"));
        form.addButton(new ElementButton("§6Построить средневековый город 500×500"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) showWorldList(operator);
            else if (id == 1) createWorldFromMenu(operator, "spawn");
            else if (id == 2) createWorldFromMenu(operator, "spawn2");
            else if (id == 3) createWorldFromMenu(operator, "dungeons");
            else if (id == 4) createWorldFromMenu(operator, "bosses");
            else if (id == 5) openWorldPicker(operator, "load");
            else if (id == 6) openWorldPicker(operator, "info");
            else if (id == 7) openWorldPicker(operator, "tp");
            else if (id == 8) openWorldPicker(operator, "delete");
            else if (id == 9) { setSpawn(operator); openOperatorWorldMenu(operator); }
            else if (id == 10) openSecondSpawnBuilderMenu(operator);
            else if (id == 11) openOperatorMenu(operator);
        }));
        operator.showFormWindow(form);
    }

    private void openWorldDeleteConfirm(Player operator, String name) {
        if (!isOperator(operator)) return;
        if (name.equalsIgnoreCase("world")) {
            operator.sendMessage("§cМир world — основной мир выживания. Его удаление заблокировано для защиты игроков и их построек.");
            openOperatorWorldMenu(operator);
            return;
        }
        FormWindowSimple confirm = new FormWindowSimple("§cУдаление мира",
                "§fТы собираешься удалить мир §e" + name + "§f.\n"
                + "§cВсе постройки и данные этого мира будут удалены без возможности восстановления.\n"
                + "§7Мир world защищён и удалить его нельзя.");
        confirm.addButton(new ElementButton("§cДа, удалить мир"));
        confirm.addButton(new ElementButton("§7Отмена"));
        confirm.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (confirm.wasClosed() || confirm.getResponse() == null || !operator.isOnline()) return;
            if (confirm.getResponse().getClickedButtonId() == 0) {
                handleWorldCommand(operator, new String[]{"delete", name});
            }
            openOperatorWorldMenu(operator);
        }));
        operator.showFormWindow(confirm);
    }

    private boolean deleteWorldFolder(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) if (!deleteWorldFolder(child)) return false;
        }
        return file.delete();
    }

    private void openSecondSpawnBuilderMenu(Player operator) {
        if (!isOperator(operator)) return;
        boolean set = getConfig().exists("spawn2.center-x") && getConfig().exists("spawn2.center-y")
                && getConfig().exists("spawn2.center-z");
        String center = set ? "§aЦентр: X " + getConfig().getInt("spawn2.center-x")
                + ", Y " + getConfig().getInt("spawn2.center-y") + ", Z " + getConfig().getInt("spawn2.center-z")
                : "§cЦентр не выбран";
        FormWindowSimple form = new FormWindowSimple("§6Второй спавн 500×500",
                "§7Мир: spawn2\n" + center + "\n§fОтдельная площадь с дорогами, центральной площадью, зелёными зонами и малыми постройками.");
        form.addButton(new ElementButton("§aВыбрать центр здесь"));
        form.addButton(new ElementButton("§bПроверить площадку"));
        form.addButton(new ElementButton("§6Построить готовый средневековый город"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) {
                if (!operator.getLevel().getFolderName().equalsIgnoreCase("spawn2")) {
                    operator.sendMessage("§cСначала перейди в spawn2: /world tp spawn2.");
                    openSecondSpawnBuilderMenu(operator); return;
                }
                int x=operator.getFloorX(), z=operator.getFloorZ();
                int y=operator.getLevel().getHighestBlockAt(x,z)+1;
                getConfig().set("spawn2.center-x",x); getConfig().set("spawn2.center-y",y);
                getConfig().set("spawn2.center-z",z); saveConfig();
                operator.sendMessage("§aЦентр средневекового города сохранён.");
                openSecondSpawnBuilderMenu(operator);
            } else if (id == 1) {
                validateSecondSpawnSite(operator);
                openSecondSpawnBuilderMenu(operator);
            } else if (id == 2) {
                startSecondSpawnBuild(operator);
                openSecondSpawnBuilderMenu(operator);
            } else openOperatorWorldMenu(operator);
        }));
        operator.showFormWindow(form);
    }


    private void spawnFill(Map<String, CastleBlock> b,int x1,int y,int z1,int x2,int z2,int id) {
        for(int x=Math.min(x1,x2);x<=Math.max(x1,x2);x++) for(int z=Math.min(z1,z2);z<=Math.max(z1,z2);z++) addCastleBlock(b,x,y,z,id);
    }
    private void spawnBuilding(Map<String, CastleBlock> b,int x,int y,int z,int w,int d,int h,int wall,int roof,int accent) {
        int x1=x-w/2,x2=x+w/2,z1=z-d/2,z2=z+d/2;
        spawnFill(b,x1,y,z1,x2,z2,98);
        for(int yy=y+1;yy<=y+h;yy++) {
            for(int xx=x1;xx<=x2;xx++){addCastleBlock(b,xx,yy,z1,wall);addCastleBlock(b,xx,yy,z2,wall);}
            for(int zz=z1;zz<=z2;zz++){addCastleBlock(b,x1,yy,zz,wall);addCastleBlock(b,x2,yy,zz,wall);}
        }
        int wy=y+Math.max(2,h/2);
        for(int xx=x1+2;xx<x2-1;xx+=4){addCastleBlock(b,xx,wy,z1,20);addCastleBlock(b,xx,wy,z2,20);}
        for(int zz=z1+2;zz<z2-1;zz+=4){addCastleBlock(b,x1,wy,zz,20);addCastleBlock(b,x2,wy,zz,20);}
        for(int yy=y+1;yy<=y+3;yy++)addCastleBlock(b,x,yy,z1,0);
        addCastleBlock(b,x,y+h+1,z1,accent);
        for(int layer=0;layer<4;layer++){int in=layer;for(int xx=x1-in;xx<=x2+in;xx++)for(int zz=z1-in;zz<=z2+in;zz++)
            if(xx==x1-in||xx==x2+in||zz==z1-in||zz==z2+in)addCastleBlock(b,xx,y+h+2+layer,zz,roof);}
        addCastleBlock(b,x,y+h+6,z,accent);
        for(int side=-1;side<=1;side++){addCastleBlock(b,x+side*2,y+1,z1-1,89);addCastleBlock(b,x+side*2,y+2,z1-1,89);}
        for(int step=1;step<=3;step++)for(int side=-1;side<=1;side++)addCastleBlock(b,x+side,y+step,z1-step,98);
    }
    private void spawnTower(Map<String, CastleBlock> b,int x,int y,int z,int r,int h,int wall,int roof) {
        for(int yy=y;yy<=y+h;yy++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){int q=dx*dx+dz*dz;if(q<=r*r&&q>=(r-1)*(r-1))addCastleBlock(b,x+dx,yy,z+dz,wall);}
        for(int yy=y+3;yy<y+h-2;yy+=5){addCastleBlock(b,x-r,yy,z,20);addCastleBlock(b,x+r,yy,z,20);addCastleBlock(b,x,yy,z-r,20);addCastleBlock(b,x,yy,z+r,20);}
        for(int layer=0;layer<=r+1;layer++){int rr=r+1-layer;for(int dx=-rr;dx<=rr;dx++)for(int dz=-rr;dz<=rr;dz++)if(Math.abs(dx)==rr||Math.abs(dz)==rr)addCastleBlock(b,x+dx,y+h+layer,z+dz,roof);}
        addCastleBlock(b,x,y+h+r+2,z,89);
    }
    private void spawnTreeDetailed(Map<String, CastleBlock> b,int x,int y,int z,int variant) {
        int trunk=variant==0?17:162;
        for(int yy=0;yy<7;yy++)addCastleBlock(b,x,y+yy,z,trunk);
        for(int yy=4;yy<=8;yy++){int r=yy==8?1:yy==7?2:3;for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(dx*dx+dz*dz<=r*r+1&&!(Math.abs(dx)==r&&Math.abs(dz)==r))addCastleBlock(b,x+dx,y+yy,z+dz,18);}
        addCastleBlock(b,x,y+9,z,18);addCastleBlock(b,x+1,y+5,z,89);addCastleBlock(b,x-1,y+5,z,89);
    }
    /**
     * Finished 500 x 500 medieval town for spawn2.
     * The first layer is a continuous landscaped grass/earth terrain; roads,
     * plazas, two side castles, homes, market tents, gardens and trees are
     * then placed above it. Coordinates are relative to the selected center.
     */
    private List<CastleBlock> createSecondSpawnPlan(int cx,int y,int cz) {
        Map<String,CastleBlock> b=new java.util.LinkedHashMap<>();

        // Continuous natural ground: no hollow voids or exposed superflat grid.
        for(int x=-250;x<=250;x++) for(int z=-250;z<=250;z++) {
            int ground=2;
            if(((x*13+z*7)&31)==0) ground=3; // occasional coarse-earth patches
            if(Math.abs(x)>238 || Math.abs(z)>238) ground=3;
            addCastleBlock(b,cx+x,y,cz+z,ground);
        }

        // Main streets and a smaller cobbled ring, with gravel shoulders.
        for(int p=-238;p<=238;p++) {
            for(int w=-7;w<=7;w++) {
                int road=Math.abs(w)>=6?13:((Math.abs(w)==0 || Math.abs(w)==1)?98:4);
                addCastleBlock(b,cx+w,y+1,cz+p,road);
                addCastleBlock(b,cx+p,y+1,cz+w,road);
            }
        }
        for(int r:new int[]{105,205}) for(int p=-r;p<=r;p++) for(int w=-4;w<=4;w++) {
            int road=Math.abs(w)==4?13:98;
            addCastleBlock(b,cx+r+w,y+1,cz+p,road);
            addCastleBlock(b,cx-r+w,y+1,cz+p,road);
            addCastleBlock(b,cx+p,y+1,cz+r+w,road);
            addCastleBlock(b,cx+p,y+1,cz-r+w,road);
        }

        // Grand central plaza, patterned stone floor, gardens and fountain.
        for(int x=-48;x<=48;x++) for(int z=-48;z<=48;z++) {
            int floor=((x/4+z/4)&1)==0?98:4;
            if(Math.abs(x)==48||Math.abs(z)==48) floor=45;
            if(Math.abs(x)<=3||Math.abs(z)<=3) floor=24;
            addCastleBlock(b,cx+x,y+1,cz+z,floor);
        }
        for(int gx:new int[]{-34,34}) for(int gz:new int[]{-34,34}) {
            for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++) {
                if(dx*dx+dz*dz<=64) addCastleBlock(b,cx+gx+dx,y+1,cz+gz+dz,2);
                if(dx*dx+dz*dz<=25) addCastleBlock(b,cx+gx+dx,y+2,cz+gz+dz,38);
            }
            spawnTreeDetailed(b,cx+gx,y+1,cz+gz,Math.abs(gx+gz)%2);
            for(int a=-9;a<=9;a+=3) {
                addCastleBlock(b,cx+gx+a,y+2,cz+gz-9,89);
                addCastleBlock(b,cx+gx+a,y+2,cz+gz+9,89);
            }
        }
        // Layered fountain basin and centerpiece.
        for(int dx=-10;dx<=10;dx++) for(int dz=-10;dz<=10;dz++) {
            int d=dx*dx+dz*dz;
            if(d<=100) addCastleBlock(b,cx+dx,y+2,cz+dz,d>=81?24:98);
            if(d<=49) addCastleBlock(b,cx+dx,y+3,cz+dz,9);
        }
        for(int h=4;h<=10;h++) addCastleBlock(b,cx,y+h,cz,98);
        addCastleBlock(b,cx,y+11,cz,89);

        // Two distinct side castles facing the central plaza.
        for(int side:new int[]{-1,1}) {
            int kx=side*145, kz=0;
            // Castle courtyard and surrounding curtain wall.
            for(int dx=-48;dx<=48;dx++) for(int dz=-42;dz<=42;dz++) {
                if(Math.abs(dx)==48||Math.abs(dz)==42)
                    for(int h=1;h<=11;h++) addCastleBlock(b,cx+kx+dx,y+1+h,cz+kz+dz,98);
                if(Math.abs(dx)<=46&&Math.abs(dz)<=40)
                    addCastleBlock(b,cx+kx+dx,y+1,cz+kz+dz,((dx+dz)&1)==0?98:4);
            }
            // Front gate faces inward toward the plaza.
            int gateX=-side*48;
            for(int h=1;h<=14;h++) {
                addCastleBlock(b,cx+kx+gateX,y+1+h,cz-9,45);
                addCastleBlock(b,cx+kx+gateX,y+1+h,cz+9,45);
            }
            for(int dx=-9;dx<=9;dx++) addCastleBlock(b,cx+kx+gateX,y+16,cz+dx,45);

            // Main keep, side wings, four corner towers and roof caps.
            spawnBuilding(b,cx+kx,y+2,cz-4,43,35,17,98,45,155);
            spawnBuilding(b,cx+kx,y+2,cz+29,27,17,10,45,5,155);
            for(int tx:new int[]{-40,40}) for(int tz:new int[]{-34,34})
                spawnTower(b,cx+kx+tx,y+2,cz+tz,6,25,98,45);
            // Banners, lamps and courtyard planters.
            for(int p=-28;p<=28;p+=14) {
                addSpawnLamp(b,cx+kx+p,y+1,cz-23);
                addSpawnLamp(b,cx+kx+p,y+1,cz+23);
            }
            for(int p=-24;p<=24;p+=12) {
                spawnTreeDetailed(b,cx+kx+p,y+2,cz-31,(p/12)&1);
                spawnTreeDetailed(b,cx+kx+p,y+2,cz+31,(p/12)&1);
            }
        }

        // Medieval houses in compact, varied little streets around the plaza.
        int[][] homes={
            {-82,-76,0},{-82,76,1},{82,-76,2},{82,76,3},
            {-205,-75,1},{-205,75,2},{205,-75,3},{205,75,0},
            {-190,-165,2},{-125,-190,0},{125,-190,1},{190,-165,3},
            {-190,165,1},{-125,190,3},{125,190,2},{190,165,0}
        };
        for(int[] h:homes) {
            int hx=h[0],hz=h[1],t=h[2];
            spawnBuilding(b,cx+hx,y+1,cz+hz,17+(t%2)*4,15+(t%2)*4,7+(t%3),t==1?45:5,t==2?45:5,89);
            spawnTreeDetailed(b,cx+hx+13,y+1,cz+hz+11,t%2);
            spawnTreeDetailed(b,cx+hx-13,y+1,cz+hz-10,(t+1)%2);
            addSpawnLamp(b,cx+hx+18,y+1,cz+hz);
        }

        // Market lane: colorful wool canopies, counters, posts and lanterns.
        for(int side:new int[]{-1,1}) for(int n=0;n<5;n++) {
            int tx=side*(35+n*13), tz=side==1?-72:72;
            spawnMarketTent(b,cx+tx,y+1,cz+tz,n%3);
            addSpawnLamp(b,cx+tx,y+1,cz+tz+9);
        }
        // Additional guild stalls at the edge of the plaza.
        for(int n=0;n<4;n++) {
            spawnMarketTent(b,cx-24+n*16,y+1,cz+93,n%3);
        }

        // Small park gardens, flowerbeds, benches and trees in the outer ring.
        for(int side:new int[]{-1,1}) for(int n=0;n<7;n++) {
            int tx=side*(205+(n%2)*18), tz=-135+n*45;
            spawnTreeDetailed(b,cx+tx,y+1,cz+tz,n%2);
            for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++)
                if(Math.abs(dx)==4||Math.abs(dz)==4) addCastleBlock(b,cx+tx+dx,y+1,cz+tz+dz,13);
            addCastleBlock(b,cx+tx+2,y+2,cz+tz,89);
        }
        for(int p=-220;p<=220;p+=22) {
            addSpawnLamp(b,cx+12,y+1,cz+p);
            addSpawnLamp(b,cx-12,y+1,cz+p);
            addSpawnLamp(b,cx+p,y+1,cz+12);
            addSpawnLamp(b,cx+p,y+1,cz-12);
        }

        // Decorative boundary: low stone wall, corner watch posts and entrance arches.
        for(int p=-250;p<=250;p++) {
            if(Math.abs(p)>18) {
                addCastleBlock(b,cx-250,y+1,cz+p,98);
                addCastleBlock(b,cx+250,y+1,cz+p,98);
                addCastleBlock(b,cx+p,y+1,cz-250,98);
                addCastleBlock(b,cx+p,y+1,cz+250,98);
                if((p&1)==0) {
                    addCastleBlock(b,cx-250,y+2,cz+p,45);
                    addCastleBlock(b,cx+250,y+2,cz+p,45);
                    addCastleBlock(b,cx+p,y+2,cz-250,45);
                    addCastleBlock(b,cx+p,y+2,cz+250,45);
                }
            }
        }
        for(int sx:new int[]{-1,1}) for(int sz:new int[]{-1,1}) {
            spawnTower(b,cx+sx*235,y+1,cz+sz*235,4,13,98,45);
        }
        return new ArrayList<>(b.values());
    }

    private void spawnMarketTent(Map<String,CastleBlock> b,int x,int y,int z,int style) {
        int canopy=style==0?35:(style==1?45:5);
        // Dirt/stone pad, wooden counter, four posts and a sloped canopy.
        for(int dx=-5;dx<=5;dx++) for(int dz=-4;dz<=4;dz++)
            addCastleBlock(b,x+dx,y, z+dz, dx==0?98:13);
        for(int h=1;h<=4;h++) {
            for(int dx:new int[]{-5,5}) for(int dz:new int[]{-4,4})
                addCastleBlock(b,x+dx,y+h,z+dz,17);
        }
        for(int dx=-3;dx<=3;dx++) addCastleBlock(b,x+dx,y+2,z+2,5);
        for(int dx=-5;dx<=5;dx++) for(int dz=-4;dz<=4;dz++) {
            if(Math.abs(dx)+Math.abs(dz)<=8)
                addCastleBlock(b,x+dx,y+5-Math.max(0,(Math.abs(dx)+Math.abs(dz)-4)/3),z+dz,canopy);
        }
        addCastleBlock(b,x,y+6,z,89);
    }

    private boolean validateSecondSpawnSite(Player operator) {
        if (!getServer().isLevelLoaded("spawn2") && !getServer().loadLevel("spawn2")) {
            operator.sendMessage("§cСначала создай мир spawn2.");
            return false;
        }
        Level world=getServer().getLevelByName("spawn2");
        if (!getConfig().exists("spawn2.center-x") || !getConfig().exists("spawn2.center-y")
                || !getConfig().exists("spawn2.center-z")) {
            operator.sendMessage("§cСначала выбери центр в мире spawn2."); return false;
        }
        int cx=getConfig().getInt("spawn2.center-x"), y=getConfig().getInt("spawn2.center-y"), cz=getConfig().getInt("spawn2.center-z");
        if (y<world.getMinBlockY() || y+20>=world.getMaxBlockY()) {
            operator.sendMessage("§cВысота центра вне границ мира."); return false;
        }
        List<CastleBlock> plan=createSecondSpawnPlan(cx,y,cz);
        try {
            for (CastleBlock b:plan) {
                if (world.getChunk(b.x>>4,b.z>>4,true)==null || world.getBlock(b.x,b.y,b.z).getId()!=0) {
                    operator.sendMessage("§cПлощадка занята. Конфликт: "+b.x+", "+b.y+", "+b.z+". Ничего не построено.");
                    return false;
                }
            }
        } catch (RuntimeException ex) {
            operator.sendMessage("§cОшибка проверки площадки: "+ex.getMessage()); return false;
        }
        operator.sendMessage("§aПлощадка spawn2 свободна. Проверено блоков: "+plan.size()+".");
        return true;
    }

    private void startSecondSpawnBuild(Player operator) {
        if (castleBuildActive) { operator.sendMessage("§eДругое строительство уже выполняется."); return; }
        if (!validateSecondSpawnSite(operator)) return;
        Level world=getServer().getLevelByName("spawn2");
        List<CastleBlock> plan=createSecondSpawnPlan(getConfig().getInt("spawn2.center-x"),
                getConfig().getInt("spawn2.center-y"),getConfig().getInt("spawn2.center-z"));
        castleBuildActive=true; castleBuildPlaced=0; castleBuildTotal=plan.size();
        final int[] cursor={0};
        operator.sendMessage("§aСтроительство средневекового города началось. Блоков: "+castleBuildTotal+".");
        castleBuildTask=getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (!castleBuildActive) { if (castleBuildTask!=null) { castleBuildTask.cancel(); castleBuildTask=null; } return; }
            int batch=0;
            while (cursor[0]<plan.size() && batch<250) {
                CastleBlock b=plan.get(cursor[0]++);
                try {
                    if (world.getBlock(b.x,b.y,b.z).getId()==0) {
                        world.setBlock(new Position(b.x,b.y,b.z,world),Block.get(b.id),true,true);
                        castleBuildPlaced++;
                    }
                } catch (RuntimeException ex) {
                    getLogger().warning("Second spawn builder skipped block: "+ex.getMessage());
                }
                batch++;
            }
            if (cursor[0]>=plan.size()) {
                castleBuildActive=false;
                if (castleBuildTask!=null) { castleBuildTask.cancel(); castleBuildTask=null; }
                getConfig().set("spawn2.completed",true); saveConfig();
                for (Player online:getServer().getOnlinePlayers().values())
                    if (isOperator(online)) online.sendMessage("§aСредневековый город построен. Блоков: "+castleBuildPlaced+".");
            }
        },1);
    }

    private void createWorldFromMenu(Player operator, String name) {
        if (getServer().isLevelGenerated(name)) {
            operator.sendMessage("§eМир " + name + " уже существует. Открой «Загрузить мир».");
            openWorldPicker(operator, "load");
            return;
        }
        handleWorldCommand(operator, new String[]{"create", name, (name.equalsIgnoreCase("spawn") || name.equalsIgnoreCase("spawn2")) ? "flat" : "normal"});
        openOperatorWorldMenu(operator);
    }

    private void openWorldPicker(Player operator, String action) {
        List<String> names = new ArrayList<>();
        for (String name : new String[]{"spawn", "spawn2", "world", "dungeons", "bosses"}) {
            if (getServer().isLevelGenerated(name) || getServer().isLevelLoaded(name)) names.add(name);
        }
        if (getConfig().exists("managed-worlds")) {
            for (String name : getConfig().getSection("managed-worlds").getKeys(false)) {
                if (!names.contains(name)) names.add(name);
            }
        }
        if (names.isEmpty()) {
            operator.sendMessage("§7Пока нет созданных миров.");
            openOperatorWorldMenu(operator);
            return;
        }
        FormWindowSimple form = new FormWindowSimple(
                action.equals("load") ? "§6Загрузить мир" : action.equals("tp") ? "§6Перейти в мир" : action.equals("delete") ? "§cВыбрать мир для удаления" : "§6Информация о мире",
                "§7Выбери мир:");
        for (String name : names) {
            boolean loaded = getServer().isLevelLoaded(name);
            form.addButton(new ElementButton((loaded ? "§a● " : "§e○ ") + name
                    + "\n§7" + worldRole(name) + (loaded ? " · загружен" : " · не загружен")));
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == names.size()) { openOperatorWorldMenu(operator); return; }
            if (id < 0 || id >= names.size()) return;
            String selected = names.get(id);
            if (action.equals("load")) {
                handleWorldCommand(operator, new String[]{"load", selected});
                openOperatorWorldMenu(operator);
            } else if (action.equals("tp")) {
                handleWorldCommand(operator, new String[]{"tp", selected});
                openOperatorWorldMenu(operator);
            } else if (action.equals("delete")) {
                openWorldDeleteConfirm(operator, selected);
            } else {
                handleWorldCommand(operator, new String[]{"info", selected});
                openWorldPicker(operator, "info");
            }
        }));
        operator.showFormWindow(form);
    }

    private void openOperatorMenu(Player operator) {
        if (!isOperator(operator)) { operator.sendMessage("§cТолько оператор может открыть эту панель."); return; }
        FormWindowSimple form = new FormWindowSimple("§6LocalFast §8| §lОператорская панель",
                "§fОператор: §e" + operator.getName() + "\n§7Управление сервером и персоналом.");
        form.addButton(new ElementButton("§bСписок администрации"));
        form.addButton(new ElementButton("§aНазначить сотрудника"));
        form.addButton(new ElementButton("§eДолжности и команды"));
        form.addButton(new ElementButton("§aСтатистика игрока"));
        form.addButton(new ElementButton("§6Сохранить варп здесь (operator)"));
        form.addButton(new ElementButton("§dМоё приветствие: вкл/выкл"));
        form.addButton(new ElementButton("§cFly"));
        form.addButton(new ElementButton("§cVanish"));
        form.addButton(new ElementButton("§cМодерация: Ban / Mute / Kick"));
        form.addButton(new ElementButton("§cАктивные баны и муты"));
        form.addButton(new ElementButton("§dТестировать должность на себе"));
        form.addButton(new ElementButton("§bУправление мирами"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) showStaffRoster(operator);
            else if (id == 1) openOperatorStaffAssignMenu(operator);
            else if (id == 2) showStaffHelp(operator);
            else if (id == 3) openStaffStatsTargetMenu(operator);
            else if (id == 4) {
                String path = "warps.operator";
                getConfig().set(path + ".world", operator.getLevel().getFolderName());
                getConfig().set(path + ".x", operator.getX()); getConfig().set(path + ".y", operator.getY()); getConfig().set(path + ".z", operator.getZ());
                saveConfig(); operator.sendMessage("§aВарп operator сохранён в твоей текущей точке.");
            } else if (id == 5) {
                String path = "welcome.enabled." + operator.getUniqueId();
                boolean enabled = !staffData.getBoolean(path, true);
                staffData.set(path, enabled);
                staffData.save();
                operator.sendMessage(enabled ? "§aТвоё приветствие при входе включено." : "§eТвоё приветствие при входе выключено.");
            } else if (id == 6) toggleFly(operator);
            else if (id == 7) toggleVanish(operator);
            else if (id == 8) openStaffMenu(operator);
            else if (id == 9) openActivePunishmentsMenu(operator);
            else if (id == 10) openOperatorSelfRankMenu(operator);
            else if (id == 11) openOperatorWorldMenu(operator);
        }));
        operator.showFormWindow(form);
    }

    private void openAdminStaffAssignMenu(Player admin) {
        String adminRole = staffRole(admin);
        if (staffRank(adminRole) < 7) {
            admin.sendMessage("§cУправление должностями доступно только администратору и оператору.");
            return;
        }
        List<Player> candidates = new ArrayList<>(getServer().getOnlinePlayers().values());
        candidates.removeIf(target -> target.getUniqueId().equals(admin.getUniqueId())
                || staffRank(staffRole(target)) >= staffRank(adminRole));
        if (candidates.isEmpty()) {
            admin.sendMessage("§7Нет сотрудников или игроков, которым можно изменить должность.");
            return;
        }
        FormWindowSimple form = new FormWindowSimple("§6Управление должностями",
                "§7Выбери игрока. Администратор может назначать должности до модера включительно.");
        for (Player target : candidates) {
            String current = staffRole(target);
            form.addButton(new ElementButton("§f" + target.getName() + "\n" + staffRoleColor(current) + staffRoleLabel(current)));
        }
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !admin.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index < 0 || index >= candidates.size()) return;
            Player target = candidates.get(index);
            if (!target.isOnline() || staffRank(staffRole(target)) >= staffRank(staffRole(admin))) {
                admin.sendMessage("§cИгрок уже имеет равную или более высокую должность.");
                return;
            }
            openAdminRoleSelectMenu(admin, target);
        }));
        admin.showFormWindow(form);
    }

    private void openAdminRoleSelectMenu(Player admin, Player target) {
        boolean operator = staffRole(admin).equals("operator");
        int maxRank = operator ? STAFF_ROLES.length : 5;
        FormWindowSimple form = new FormWindowSimple("§6Должность: " + target.getName(),
                "§fИгрок: §e" + target.getName() + "\n§7Выбери должность. Администратору доступны ранги до модера.");
        for (int i = 0; i < maxRank; i++) {
            String rank = STAFF_ROLES[i];
            form.addButton(new ElementButton(staffRoleColor(rank) + staffRoleLabel(rank)));
        }
        form.addButton(new ElementButton("§7Снять должность"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !admin.isOnline() || !target.isOnline()) return;
            if (staffRank(staffRole(target)) >= staffRank(staffRole(admin))) {
                admin.sendMessage("§cНельзя менять должность равного или более высокого сотрудника.");
                return;
            }
            int index = form.getResponse().getClickedButtonId();
            if (index < 0 || index > maxRank) return;
            String path = "staff." + target.getUniqueId();
            String newRole = index < maxRank ? STAFF_ROLES[index] : "";
            if (newRole.isEmpty()) staffData.remove(path);
            else staffData.set(path, newRole);
            staffData.save();
            refreshStaffNameTag(target);
            refreshStaffModes(target);
            staffLog(admin.getName(), "staff-rank-menu", target.getName(),
                    staffRoleLabel(staffRole(target)) + " -> " + (newRole.isEmpty() ? "Игрок" : staffRoleLabel(newRole)));
            admin.sendMessage("§aДолжность обновлена: §f" + target.getName() + " §7— "
                    + (newRole.isEmpty() ? "§7Игрок" : staffRoleColor(newRole) + staffRoleLabel(newRole)));
            target.sendMessage("§eТвоя должность изменена: §a" + (newRole.isEmpty() ? "Игрок" : staffRoleLabel(newRole)) + "§e.");
            openStaffMenu(admin);
        }));
        admin.showFormWindow(form);
    }

    private void openStaffTargetMenu(Player staff, String action) {
        List<Player> online = new ArrayList<>(getServer().getOnlinePlayers().values());
        online.removeIf(target -> staffRank(staffRole(target)) >= staffRank(staffRole(staff)));
        if (online.isEmpty()) { staff.sendMessage("§cНет подходящих игроков онлайн."); return; }
        FormWindowSimple form = new FormWindowSimple("§6Персонал §8| §lИгрок", "§7Выбери игрока.");
        for (Player target : online) form.addButton(new ElementButton(target.getName()));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id < 0 || id >= online.size()) return;
            Player target = online.get(id);
            if (action.equals("warn")) {
                String path = "warnings." + target.getUniqueId();
                int count = staffData.getInt(path, 0) + 1;
                staffData.set(path, count); staffData.save();
                staffLog(staff.getName(), "warn", target.getName(), "Предупреждение №" + count);
                target.sendMessage("§eТебе выдано предупреждение №" + count + ".");
                staff.sendMessage("§aПредупреждение выдано. Всего: " + count);
            } else if (action.equals("unban") || action.equals("unmute")) {
                staffData.remove("punishments." + (action.equals("unban") ? "bans." : "mutes.") + target.getUniqueId());
                staffData.save(); staffLog(staff.getName(), action, target.getName(), "через меню");
                staff.sendMessage("§aНаказание снято для " + target.getName() + ".");
                target.sendMessage("§aТвоё наказание снято.");
            } else openStaffActionMenu(staff, target, action);
        }));
        staff.showFormWindow(form);
    }

    private void openStaffActionMenu(Player staff, Player target, String action) {
        String role = staffRole(staff);
        if (staffRank(staffRole(target)) >= staffRank(role)) {
            staff.sendMessage("§cНельзя воздействовать на сотрудника равного или более высокого ранга.");
            return;
        }
        if (action.equals("kick")) {
            openStaffReasonMenu(staff, target, action, 0L);
            return;
        }
        long maximum = action.equals("ban") ? maxBanMinutes(role) : maxMuteMinutes(role);
        if (maximum == 0L) {
            staff.sendMessage("§cТвой ранг не может выдавать это наказание.");
            return;
        }
        FormWindowSimple form = new FormWindowSimple("§6Срок наказания",
                "§fИгрок: §e" + target.getName() + "\n§7Выбери продолжительность.");
        List<Long> durations = new ArrayList<>();
        long[] presets = new long[]{5L, 15L, 30L, 60L, 180L, 1440L, 10080L, 43200L};
        for (long minutes : presets) {
            if (maximum == -1L || minutes <= maximum) {
                durations.add(minutes);
                form.addButton(new ElementButton("§e" + formatPunishmentDuration(minutes)));
            }
        }
        if ((action.equals("ban") || action.equals("mute")) && maximum == -1L) {
            durations.add(-1L);
            form.addButton(new ElementButton("§cНавсегда"));
        }
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline() || !target.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index < 0 || index >= durations.size()) return;
            openStaffReasonMenu(staff, target, action, durations.get(index));
        }));
        staff.showFormWindow(form);
    }

    private String formatPunishmentDuration(long minutes) {
        if (minutes == -1L) return "Навсегда";
        if (minutes < 60L) return minutes + " мин.";
        if (minutes < 1440L) return (minutes / 60L) + " ч.";
        if (minutes < 10080L) return (minutes / 1440L) + " дн.";
        return (minutes / 10080L) + " нед.";
    }

    private void openStaffReasonMenu(Player staff, Player target, String action, long minutes) {
        FormWindowSimple form = new FormWindowSimple("§6Причина наказания",
                "§fИгрок: §e" + target.getName() + "\n§7Выбери причину.");
        String[] reasons = new String[]{"Нарушение правил", "Оскорбления", "Флуд / спам", "Читы / использование багов", "Реклама", "Другое нарушение"};
        for (String reason : reasons) form.addButton(new ElementButton("§f" + reason));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline() || !target.isOnline()) return;
            int index = form.getResponse().getClickedButtonId();
            if (index < 0 || index >= reasons.length) return;
            String reason = reasons[index];
            if (action.equals("ban")) applyBan(staff, target, minutes, reason);
            else if (action.equals("mute")) applyMute(staff, target, minutes, reason);
            else if (action.equals("kick")) {
                target.kick("§cКик: " + reason, false);
                staffLog(staff.getName(), "kick", target.getName(), reason);
                staff.sendMessage("§aИгрок " + target.getName() + " кикнут. Причина: " + reason);
            }
        }));
        staff.showFormWindow(form);
    }

    private String knownPlayerUuid(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        Player online = getServer().getPlayer(name);
        if (online != null) return online.getUniqueId().toString();
        String uuid = staffData.getString("knownPlayers." + name.toLowerCase(Locale.ROOT) + ".uuid", "");
        if (uuid == null || uuid.isEmpty()) return null;
        try { return UUID.fromString(uuid).toString(); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private String knownPlayerName(String name, String uuid) {
        Player online = getServer().getPlayer(name);
        if (online != null) return online.getName();
        String stored = staffData.getString("knownPlayers." + name.toLowerCase(Locale.ROOT) + ".name", "");
        if (!stored.isEmpty()) return stored;
        String banName = staffData.getString("punishments.bans." + uuid + ".name", "");
        if (!banName.isEmpty()) return banName;
        String muteName = staffData.getString("punishments.mutes." + uuid + ".name", "");
        return muteName.isEmpty() ? name : muteName;
    }

    private void showStaffHistory(CommandSender sender, String filter) {
        try {
            if (!staffLogFile.exists()) { sender.sendMessage("§7История действий персонала пока пуста."); return; }
            List<String> lines = java.nio.file.Files.readAllLines(staffLogFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            int shown = 0;
            String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
            sender.sendMessage("§6§lИстория действий персонала §7(последние записи)");
            for (int i = lines.size() - 1; i >= 0 && shown < 10; i--) {
                String line = lines.get(i);
                if (!needle.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                sender.sendMessage("§7" + line);
                shown++;
            }
            if (shown == 0) sender.sendMessage("§7Подходящих записей нет.");
        } catch (java.io.IOException exception) {
            sender.sendMessage("§cНе удалось прочитать историю.");
            getLogger().error("Не удалось прочитать staff.log.", exception);
        }
    }

    private boolean handleStaffCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Команда доступна игроку."); return true; }
        Player actor = (Player) sender;
        String role = staffRole(actor);
        if (staffRank(role) == 0) { sender.sendMessage("§cКоманда /staff доступна только персоналу сервера."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("welcome")) {
            String path = "welcome.enabled." + actor.getUniqueId();
            if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
                boolean enabled = staffData.getBoolean(path, true);
                sender.sendMessage(enabled ? "§aПриветствие при входе включено для тебя." : "§eПриветствие при входе выключено для тебя.");
                sender.sendMessage("§7Используй /staff welcome on или /staff welcome off.");
                return true;
            }
            if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                sender.sendMessage("§e/staff welcome <on|off|status>");
                return true;
            }
            boolean enabled = args[1].equalsIgnoreCase("on");
            staffData.set(path, enabled);
            staffData.save();
            sender.sendMessage(enabled ? "§aПриветствие при входе включено для тебя." : "§eПриветствие при входе выключено для тебя.");
            return true;
        }
        if (args.length == 0) { openStaffMenu(actor); return true; }
        if (args[0].equalsIgnoreCase("history")) {
            if (args.length > 2) { sender.sendMessage("§e/staff history [ник]"); return true; }
            showStaffHistory(sender, args.length == 2 ? args[1] : "");
            return true;
        }
        if (args[0].equalsIgnoreCase("punishments") || args[0].equalsIgnoreCase("active")) {
            if (args.length > 1) { sender.sendMessage("§e/staff punishments"); return true; }
            openActivePunishmentsMenu(actor);
            return true;
        }
        if (args[0].equalsIgnoreCase("fly")) { toggleFly(actor); return true; }
        if (args[0].equalsIgnoreCase("vanish")) { toggleVanish(actor); return true; }
        if (args[0].equalsIgnoreCase("warp")) { if (staffRank(role) < 5) sender.sendMessage("§cWarp доступен с ранга модератора."); else openWarpMenu(actor); return true; }
        if (args[0].equalsIgnoreCase("online")) { showOnlineStaff(actor); return true; }
        if (args[0].equalsIgnoreCase("roster") || args[0].equalsIgnoreCase("list")) { showStaffRoster(actor); return true; }
        if (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("warnings")) {
            if (args.length != 2) { sender.sendMessage("§e/staff " + args[0] + " <игрок>"); return true; }
            String uuid = knownPlayerUuid(args[1]);
            if (uuid == null) { sender.sendMessage("§cИгрок не найден в истории сервера."); return true; }
            if (args[0].equalsIgnoreCase("warnings")) {
                sender.sendMessage("§eПредупреждения игрока §f" + knownPlayerName(args[1], uuid)
                        + "§e: §c" + staffData.getInt("warnings." + uuid, 0));
            } else showPlayerModeration(sender, args[1]);
            return true;
        }
        if (args[0].equalsIgnoreCase("clearwarns")) {
            if (staffRank(role) < 7) { sender.sendMessage("§cСбрасывать предупреждения может только администратор или оператор."); return true; }
            if (args.length != 2) { sender.sendMessage("§e/staff clearwarns <игрок>"); return true; }
            String uuid = knownPlayerUuid(args[1]);
            if (uuid == null) { sender.sendMessage("§cИгрок не найден в истории сервера."); return true; }
            String targetName = knownPlayerName(args[1], uuid);
            int oldWarnings = staffData.getInt("warnings." + uuid, 0);
            staffData.remove("warnings." + uuid); staffData.save();
            staffLog(actor.getName(), "clear-warnings", targetName, "было: " + oldWarnings);
            sender.sendMessage("§aПредупреждения игрока " + targetName + " сброшены.");
            return true;
        }
        if (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("ranks")) { showStaffHelp(actor); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("promote") || action.equals("demote") || action.equals("setrank")) {
            boolean operator = role.equals("operator");
            if (!operator && !role.equals("admin")) { sender.sendMessage("§cПовышать и понижать персонал могут администратор и оператор."); return true; }
            if ((action.equals("promote") || action.equals("demote")) && args.length != 2) {
                sender.sendMessage("§e/staff " + action + " <игрок>"); return true;
            }
            if (action.equals("setrank") && args.length != 3) {
                sender.sendMessage("§e/staff setrank <игрок> <ml_helper|helper|senior_helper|junior_moderator|moderator|senior_moderator|admin>"); return true;
            }
            Player target = getServer().getPlayer(args[1]);
            String targetUuid = target == null ? knownPlayerUuid(args[1]) : target.getUniqueId().toString();
            if (targetUuid == null) { sender.sendMessage("§cИгрок не найден. Он должен хотя бы один раз зайти на сервер."); return true; }
            String oldRole = target == null ? staffData.getString("staff." + targetUuid, "") : staffRole(target);
            if (targetUuid.equalsIgnoreCase(getConfig().getString("operator.uuid", ""))) oldRole = "operator";
            String targetName = target == null ? knownPlayerName(args[1], targetUuid) : target.getName();
            int oldRank = staffRank(oldRole);
            if (oldRank == 8 || oldRank >= staffRank(role)) {
                sender.sendMessage("§cНельзя менять должность сотрудника равного или более высокого ранга."); return true;
            }
            int newRank;
            if (action.equals("promote")) newRank = oldRank + 1;
            else if (action.equals("demote")) newRank = oldRank - 1;
            else {
                String wanted = args[2].toLowerCase(Locale.ROOT);
                newRank = staffRank(wanted);
                if (newRank == 0 || newRank == 8) { sender.sendMessage("§cУкажи существующую должность персонала."); return true; }
            }
            if (newRank < 0 || newRank > 7 || (!operator && newRank > 5)) {
                sender.sendMessage("§cАдминистратор может назначать должности только до модера включительно. Старшие должности доступны оператору."); return true;
            }
            if (newRank == 0) staffData.remove("staff." + targetUuid);
            else staffData.set("staff." + targetUuid, STAFF_ROLES[newRank - 1]);
            staffData.save();
            if (target != null) { refreshStaffNameTag(target); refreshStaffModes(target); }
            String newRole = newRank == 0 ? "" : STAFF_ROLES[newRank - 1];
            staffLog(actor.getName(), action, targetName, staffRoleLabel(oldRole) + " -> " + staffRoleLabel(newRole));
            sender.sendMessage("§aДолжность " + targetName + ": " + staffRoleLabel(oldRole) + " -> " + staffRoleLabel(newRole) + ".");
            if (target != null) target.sendMessage("§eТвоя должность изменена: §a" + staffRoleLabel(newRole) + "§e.");
            return true;
        }
        if (action.equals("warn")) {
            if (args.length < 3) { sender.sendMessage("§e/staff warn <игрок> <причина>"); return true; }
            Player target = getServer().getPlayer(args[1]);
            String targetUuid = target == null ? knownPlayerUuid(args[1]) : target.getUniqueId().toString();
            if (targetUuid == null) { sender.sendMessage("§cИгрок не найден. Он должен хотя бы один раз зайти на сервер."); return true; }
            String targetRole = target == null ? staffData.getString("staff." + targetUuid, "") : staffRole(target);
            if (targetUuid.equalsIgnoreCase(getConfig().getString("operator.uuid", ""))) targetRole = "operator";
            if (staffRank(targetRole) >= staffRank(role)) { sender.sendMessage("§cНельзя наказать сотрудника равного или более высокого ранга."); return true; }
            StringBuilder reason = new StringBuilder(); for (int i=2;i<args.length;i++){if(reason.length()>0)reason.append(' ');reason.append(args[i]);}
            String targetName = target == null ? knownPlayerName(args[1], targetUuid) : target.getName();
            String path = "warnings." + targetUuid;
            int count = staffData.getInt(path, 0) + 1;
            staffData.set(path, count); staffData.save();
            staffLog(actor.getName(), "warn", targetName, reason.toString());
            if (target != null) target.sendMessage("§eТебе выдано предупреждение №" + count + ": " + reason);
            sender.sendMessage("§aПредупреждение выдано игроку " + targetName + ". Всего: " + count);
            return true;
        }
        if (action.equals("ban") || action.equals("mute") || action.equals("kick")) {
            if (action.equals("ban") && !canBan(role)) { sender.sendMessage("§cТвой ранг не может выдавать баны."); return true; }
            if (action.equals("mute") && maxMuteMinutes(role) == 0L) { sender.sendMessage("§cТвой ранг не может выдавать муты."); return true; }
            if (action.equals("kick") && !canKick(role)) { sender.sendMessage("§cТвой ранг не может кикать игроков."); return true; }
            int min = action.equals("kick") ? 3 : 4;
            if (args.length < min) { sender.sendMessage(action.equals("kick") ? "§e/staff kick <игрок> <причина>" : "§e/staff " + action + " <игрок> <минуты> <причина>"); return true; }
            Player target = getServer().getPlayer(args[1]);
            String targetUuid = target == null ? knownPlayerUuid(args[1]) : target.getUniqueId().toString();
            if (targetUuid == null) { sender.sendMessage("§cИгрок не найден. Он должен хотя бы один раз зайти на сервер."); return true; }
            String targetRole = target == null ? staffData.getString("staff." + targetUuid, "") : staffRole(target);
            if (targetUuid.equalsIgnoreCase(getConfig().getString("operator.uuid", ""))) targetRole = "operator";
            if (staffRank(targetRole) >= staffRank(role)) { sender.sendMessage("§cНельзя наказать сотрудника равного или более высокого ранга."); return true; }
            if (action.equals("kick") && target == null) { sender.sendMessage("§cКик доступен только игроку онлайн."); return true; }
            long minutes = 0L; int ri = 2;
            if (!action.equals("kick")) {
                try { minutes = Long.parseLong(args[2]); } catch (NumberFormatException ex) { sender.sendMessage("§cСрок указывается в минутах."); return true; }
                long max = action.equals("ban") ? maxBanMinutes(role) : maxMuteMinutes(role);
                boolean permanentAllowed = (action.equals("ban") || action.equals("mute")) && minutes == -1L && max == -1L;
                if ((!permanentAllowed && minutes < 1L) || minutes > 525600L || (max != -1L && minutes > max)) {
                    sender.sendMessage("§cМаксимальный срок для твоего ранга: " + (max == -1L ? "без ограничения (бан навсегда: -1)" : max + " мин.") + "."); return true;
                }
                ri = 3;
            }
            StringBuilder reason = new StringBuilder(); for (int i=ri;i<args.length;i++){if(reason.length()>0)reason.append(' ');reason.append(args[i]);}
            if (reason.length()==0) { sender.sendMessage("§cУкажи причину."); return true; }
            String targetName = target == null ? knownPlayerName(args[1], targetUuid) : target.getName();
            if (action.equals("ban")) {
                String path = "punishments.bans." + targetUuid;
                staffData.set(path + ".expires", minutes < 0 ? -1L : System.currentTimeMillis() + minutes * 60000L);
                staffData.set(path + ".reason", reason.toString());
                staffData.set(path + ".name", targetName);
                staffData.set(path + ".by", actor.getName());
                staffData.save();
                staffLog(actor.getName(), "ban", targetName, minutes + " мин.; " + reason);
                if (target != null) target.kick("§cБан: " + reason, false);
                sender.sendMessage("§aБан выдан игроку " + targetName + ".");
            } else if (action.equals("mute")) {
                String path = "punishments.mutes." + targetUuid;
                staffData.set(path + ".expires", minutes < 0L ? -1L : System.currentTimeMillis() + minutes * 60000L);
                staffData.set(path + ".reason", reason.toString());
                staffData.set(path + ".name", targetName);
                staffData.set(path + ".by", actor.getName());
                staffData.save();
                staffLog(actor.getName(), "mute", targetName, (minutes < 0L ? "навсегда" : minutes + " мин.") + "; " + reason);
                if (target != null) target.sendMessage("§cТебе выдан мут " + (minutes < 0L ? "навсегда" : "на " + minutes + " мин.") + ". Причина: " + reason);
                sender.sendMessage("§aМут выдан игроку " + targetName + ".");
            } else { target.kick("§cКик: "+reason,false); staffLog(actor.getName(),"kick",targetName,reason.toString()); sender.sendMessage("§aИгрок кикнут."); }
            return true;
        }
        if (action.equals("unban") || action.equals("unmute")) {
            if (action.equals("unban") && !canUnban(role)) { sender.sendMessage("§cТвой ранг не может снимать баны."); return true; }
            if (action.equals("unmute") && !canUnmute(role)) { sender.sendMessage("§cТвой ранг не может снимать муты."); return true; }
            if (args.length != 2) { sender.sendMessage("§e/staff " + action + " <игрок>"); return true; }
            Player target = getServer().getPlayer(args[1]);
            String targetUuid = target == null ? knownPlayerUuid(args[1]) : target.getUniqueId().toString();
            if (targetUuid == null) { sender.sendMessage("§cИгрок не найден в истории сервера."); return true; }
            String targetName = target == null ? knownPlayerName(args[1], targetUuid) : target.getName();
            String punishmentPath = "punishments." + (action.equals("unban") ? "bans." : "mutes.") + targetUuid;
            if (!staffData.exists(punishmentPath)) { sender.sendMessage("§cУ игрока нет такого активного наказания."); return true; }
            staffData.remove(punishmentPath);
            staffData.save();
            staffLog(actor.getName(),action,targetName,"command");
            sender.sendMessage("§aНаказание снято: " + targetName + ".");
            if (target != null) target.sendMessage("§aТвоё наказание снято персоналом.");
            return true;
        }
        sender.sendMessage("§e/staff — меню; /staff warn|ban|mute|kick|unban|unmute ..."); return true;
    }


    private String worldRole(String name) {
        String configured = getConfig().getString("managed-worlds." + name + ".type", "");
        if (configured != null && !configured.trim().isEmpty()) return configured;
        if (name.equalsIgnoreCase("spawn")) return "главный спавн 1000×1000";
        if (name.equalsIgnoreCase("spawn2")) return "второй спавн 500×500";
        if (name.equalsIgnoreCase("world")) return "survival";
        if (name.equalsIgnoreCase("dungeons")) return "dungeons";
        if (name.equalsIgnoreCase("bosses")) return "bosses";
        return "обычный";
    }

    private void showWorldList(CommandSender sender) {
        sender.sendMessage("§6§l[LocalFast] Миры сервера:");
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Level level : getServer().getLevels().values()) names.add(level.getFolderName());
        if (getConfig().exists("managed-worlds")) {
            names.addAll(getConfig().getSection("managed-worlds").getKeys(false));
        }
        if (names.isEmpty()) {
            sender.sendMessage("§7Загруженных или зарегистрированных миров пока нет.");
            return;
        }
        for (String name : names) {
            boolean loaded = getServer().isLevelLoaded(name);
            boolean generated = loaded || getServer().isLevelGenerated(name);
            sender.sendMessage((loaded ? "§a● " : generated ? "§e○ " : "§8○ ")
                    + "§f" + name + " §7— " + worldRole(name)
                    + (loaded ? " §a(загружен)" : generated ? " §e(не загружен)" : " §8(только запись)"));
        }
        sender.sendMessage("§7Телепорт: /world tp <имя>");
    }

    private boolean handleWorldCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (args.length > 1) sender.sendMessage("§e/world list");
            else showWorldList(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("tp") || action.equals("teleport")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cТелепортация между мирами доступна игроку.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage("§e/world tp <имя>");
                return true;
            }
            String name = args[1];
            if (!getServer().isLevelLoaded(name) && !getServer().loadLevel(name)) {
                sender.sendMessage("§cМир '" + name + "' не найден или не удалось его загрузить.");
                return true;
            }
            Level target = getServer().getLevelByName(name);
            if (target == null) {
                sender.sendMessage("§cМир '" + name + "' не удалось получить после загрузки.");
                return true;
            }
            Player player = (Player) sender;
            if (player.teleport(target.getSafeSpawn())) {
                player.sendMessage("§aТы перемещён в мир §f" + target.getFolderName() + "§a.");
            } else {
                player.sendMessage("§cНе удалось телепортировать тебя в этот мир.");
            }
            return true;
        }
        if (action.equals("load")) {
            if (!isOperator(sender)) {
                sender.sendMessage("§cЗагружать миры может только оператор.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage("§e/world load <имя>");
                return true;
            }
            String name = args[1];
            if (getServer().isLevelLoaded(name)) {
                sender.sendMessage("§eМир '" + name + "' уже загружен.");
                return true;
            }
            if (!getServer().loadLevel(name)) {
                sender.sendMessage("§cНе удалось загрузить мир '" + name + "'. Проверь папку worlds/" + name + ".");
                return true;
            }
            sender.sendMessage("§aМир '" + name + "' загружен.");
            return true;
        }
        if (action.equals("create")) {
            if (!isOperator(sender)) {
                sender.sendMessage("§cСоздавать миры может только оператор.");
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage("§e/world create <имя> <normal|flat|void>");
                return true;
            }
            String name = args[1].toLowerCase(Locale.ROOT);
            String generatorName = args[2].toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]{1,32}")) {
                sender.sendMessage("§cИмя мира: 1–32 символа, латиница, цифры, _ или -.");
                return true;
            }
            if (!Arrays.asList("normal", "flat", "void").contains(generatorName)) {
                sender.sendMessage("§cГенератор: normal, flat или void.");
                return true;
            }
            if (getServer().isLevelGenerated(name)) {
                sender.sendMessage("§cМир '" + name + "' уже существует. Используй /world load " + name + ".");
                return true;
            }
            Class<? extends Generator> generator = Generator.getGenerator(generatorName);
            if (generator == null) {
                sender.sendMessage("§cГенератор '" + generatorName + "' не зарегистрирован на сервере.");
                return true;
            }
            Map<String, Object> options = new HashMap<>();
            if (generatorName.equals("flat")) {
                options.put("preset", "2;7,2x3,2;1;");
                options.put("decoration", false);
            }
            long seed = ThreadLocalRandom.current().nextLong();
            sender.sendMessage("§eСоздаю мир '" + name + "' (" + generatorName + ")...");
            boolean created;
            try {
                created = getServer().generateLevel(name, seed, generator, options);
            } catch (RuntimeException exception) {
                getLogger().error("Не удалось создать мир " + name, exception);
                sender.sendMessage("§cОшибка при создании мира. Подробности записаны в консоль.");
                return true;
            }
            if (!created || getServer().getLevelByName(name) == null) {
                sender.sendMessage("§cСервер не смог создать мир '" + name + "'.");
                return true;
            }
            String type = name.equals("spawn") ? "spawn"
                    : name.equals("world") ? "survival"
                    : name.equals("dungeons") ? "dungeons"
                    : name.equals("bosses") ? "bosses" : "обычный";
            getConfig().set("managed-worlds." + name + ".type", type);
            getConfig().set("managed-worlds." + name + ".generator", generatorName);
            getConfig().set("managed-worlds." + name + ".seed", seed);
            saveConfig();
            sender.sendMessage("§aМир '" + name + "' создан и загружен.");
            sender.sendMessage("§7Телепортация: /world tp " + name);
            if (name.equals("spawn")) {
                getConfig().set("spawn-world", name);
                saveConfig();
                sender.sendMessage("§aМир назначен миром спавна. Установи точку командой /setspawn.");
            }
            return true;
        }
        if (action.equals("delete")) {
            if (!isOperator(sender)) { sender.sendMessage("§cУдалять миры может только оператор."); return true; }
            if (args.length != 2) { sender.sendMessage("§e/world delete <имя>"); return true; }
            String name=args[1].toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]{1,32}")) { sender.sendMessage("§cНекорректное имя мира."); return true; }
            if (name.equals("world")) { sender.sendMessage("§cУдаление основного мира world заблокировано для защиты игроков."); return true; }
            if (!getServer().isLevelGenerated(name) && !getServer().isLevelLoaded(name)) {
                sender.sendMessage("§cМир не найден."); return true;
            }
            Level level=getServer().getLevelByName(name);
            if (level!=null && !level.getPlayers().isEmpty()) {
                sender.sendMessage("§cВ мире ещё есть игроки. Выведи всех игроков из него и повтори удаление."); return true;
            }
            if (level!=null && !getServer().unloadLevel(level)) {
                sender.sendMessage("§cНе удалось выгрузить мир. Удаление отменено."); return true;
            }
            File worldFolder=new File(getServer().getDataPath(),"worlds"+File.separator+name);
            if (!deleteWorldFolder(worldFolder)) {
                sender.sendMessage("§cНе удалось полностью удалить папку мира: "+worldFolder.getAbsolutePath()); return true;
            }
            getConfig().remove("managed-worlds."+name);
            if (name.equals("spawn2")) getConfig().remove("spawn2");
            saveConfig();
            sender.sendMessage("§aМир '"+name+"' удалён.");
            staffLog(sender.getName(),"world-delete",name,"operator panel");
            return true;
        }
        if (action.equals("info")) {
            if (args.length != 2) {
                sender.sendMessage("§e/world info <имя>");
                return true;
            }
            String name = args[1];
            boolean loaded = getServer().isLevelLoaded(name);
            boolean generated = loaded || getServer().isLevelGenerated(name);
            sender.sendMessage("§6Мир: §f" + name);
            sender.sendMessage("§7Тип: §f" + worldRole(name));
            sender.sendMessage("§7Существует: " + (generated ? "§aда" : "§cнет"));
            sender.sendMessage("§7Загружен: " + (loaded ? "§aда" : "§cнет"));
            if (loaded) {
                Level level = getServer().getLevelByName(name);
                sender.sendMessage("§7Папка: §f" + level.getFolderName());
                sender.sendMessage("§7Игроков: §f" + level.getPlayers().size());
            }
            return true;
        }
        sender.sendMessage("§e/world list");
        sender.sendMessage("§e/world create <имя> <normal|flat|void> §7(оператор)");
        sender.sendMessage("§e/world load <имя> §7(оператор)");
        sender.sendMessage("§e/world tp <имя>");
        sender.sendMessage("§e/world info <имя>");
        return true;
    }

    private boolean handleOperatorCommand(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("setoperator")) {
            if (sender instanceof Player) { sender.sendMessage("§cПервое назначение оператора выполняется только из консоли."); return true; }
            String configuredOperator = getConfig().getString("operator.uuid", "");
            if (configuredOperator != null && !configuredOperator.trim().isEmpty()) {
                sender.sendMessage("§cОператор уже назначен. Для смены измени operator.uuid в config.yml вручную.");
                return true;
            }
            if (args.length != 2) { sender.sendMessage("§e/lfadmin setoperator <игрок>"); return true; }
            Player target = getServer().getPlayer(args[1]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            getConfig().set("operator.uuid", target.getUniqueId().toString()); saveConfig(); refreshStaffNameTag(target); staffLog("CONSOLE", "setoperator", target.getName(), target.getUniqueId().toString());
            sender.sendMessage("Оператор назначен: " + target.getName()); target.sendMessage("§6Ты назначен оператором LocalFastCore."); return true;
        }
        if (!isOperator(sender)) { sender.sendMessage("§cТолько оператор может использовать /lfadmin."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("world")) {
            return handleWorldCommand(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("quest")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("§6[Квесты] §fСписок заданий и наград:");
                for (QuestDefinition quest : questCatalog()) sender.sendMessage("§e" + quest.id + " §7— " + quest.title + " §8| §6" + questXp(quest) + " XP §8+ §a" + questMoney(quest) + " " + currencyName());
                sender.sendMessage("§7Изменить: /lfadmin quest reward <id> <xp> <монеты>"); return true;
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("reward")) {
                QuestDefinition selected = null;
                for (QuestDefinition quest : questCatalog()) if (quest.id.equalsIgnoreCase(args[2])) selected = quest;
                if (selected == null) { sender.sendMessage("§cКвест не найден. Используй /lfadmin quest list."); return true; }
                try {
                    int xp = Integer.parseInt(args[3]); long money = Long.parseLong(args[4]);
                    if (xp < 0 || xp > MAX_XP_GRANT || money < 0L) throw new NumberFormatException();
                    getConfig().set("quests.catalog." + selected.id + ".reward", xp);
                    getConfig().set("quests.catalog." + selected.id + ".money", money);
                    saveConfig(); staffLog(sender.getName(), "quest-reward", selected.id, xp + " XP; " + money + " " + currencyName());
                    sender.sendMessage("§aНаграды квеста " + selected.title + " обновлены: §6" + xp + " XP §8+ §a" + money + " " + currencyName());
                } catch (NumberFormatException ex) { sender.sendMessage("§cXP: 0–" + MAX_XP_GRANT + "; монеты: целое число от 0."); }
                return true;
            }
            sender.sendMessage("§e/lfadmin quest list | /lfadmin quest reward <id> <xp> <монеты>"); return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("moderation")) {
            if (args.length == 1) {
                if (sender instanceof Player) openStaffMenu((Player) sender);
                else sender.sendMessage("§eИспользуй /lfadmin ban|mute|kick ... из игры.");
                return true;
            }
            return handleStaffCommand(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 0 && Arrays.asList("warn", "ban", "mute", "kick", "unban", "unmute", "history", "punishments", "active", "fly", "vanish", "warp", "online", "roster", "list", "check", "warnings", "clearwarns", "promote", "demote", "setrank", "help", "ranks").contains(args[0].toLowerCase(Locale.ROOT))) {
            return handleStaffCommand(sender, args);
        }
        if (args.length == 0) {
            if (sender instanceof Player) openOperatorMenu((Player) sender);
            else sender.sendMessage("§6/lfadmin moderation; ban|mute|kick; staff add|remove|list; money; xp; reset");
            return true;
        }
        if (args[0].equalsIgnoreCase("class")) {
            if (args.length != 4 || !args[1].equalsIgnoreCase("set")) {
                sender.sendMessage("§e/lfadmin class set <игрок> <warrior|mage|archer|assassin|tank>");
                return true;
            }
            Player target = getServer().getPlayer(args[2]);
            String chosen = args[3].toLowerCase(Locale.ROOT);
            if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            if (!Arrays.asList(RPG_CLASSES).contains(chosen)) {
                sender.sendMessage("§cКласс: warrior, mage, archer, assassin или tank.");
                return true;
            }
            initialize(target);
            players.set(playerKey(target) + ".rpg_class", chosen);
            markPlayersDirty();
            if (!flushPlayers()) { sender.sendMessage("§cНе удалось сохранить класс."); return true; }
            recalculateStats(target);
            updateSidebar(target);
            staffLog(sender.getName(), "class-set", target.getName(), chosen);
            target.sendMessage("§aОператор установил тебе класс: " + rpgClassLabel(chosen));
            sender.sendMessage("§aКласс игрока " + target.getName() + ": " + rpgClassLabel(chosen));
            return true;
        }
        if (args[0].equalsIgnoreCase("staff")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
                if (sender instanceof Player) showStaffRoster((Player) sender);
                else {
                    sender.sendMessage("§6§lПолный состав администрации:");
                    Map<String, String> roster = new HashMap<>();
                    if (staffData.exists("staff")) for (String id : staffData.getSection("staff").getKeys(false)) roster.put(id, staffData.getString("staff." + id, ""));
                    String opUuid = getConfig().getString("operator.uuid", "");
                    if (opUuid != null && !opUuid.trim().isEmpty()) roster.put(opUuid, "operator");
                    for (Map.Entry<String, String> entry : roster.entrySet()) sender.sendMessage("§f" + staffNameByUuid(entry.getKey())
                            + " §8— " + staffRoleColor(entry.getValue()) + staffRoleLabel(entry.getValue()));
                }
                return true;
            }
            if (args.length < 3) { sender.sendMessage("§e/lfadmin staff add <игрок> <ml_helper|helper|senior_helper|junior_moderator|moderator|senior_moderator|admin> или remove <игрок>"); return true; }
            Player target = getServer().getPlayer(args[2]);
            String targetUuid = target == null ? knownPlayerUuid(args[2]) : target.getUniqueId().toString();
            if (targetUuid == null) { sender.sendMessage("§cИгрок не найден. Он должен хотя бы один раз зайти на сервер."); return true; }
            if (targetUuid.equalsIgnoreCase(getConfig().getString("operator.uuid", ""))) { sender.sendMessage("§cНельзя изменить главного оператора через этот пункт."); return true; }
            String path = "staff." + targetUuid;
            if (args[1].equalsIgnoreCase("remove")) staffData.remove(path);
            else if (args[1].equalsIgnoreCase("add") && args.length == 4 && Arrays.asList(STAFF_ROLES).contains(args[3].toLowerCase(Locale.ROOT))) staffData.set(path, args[3].toLowerCase(Locale.ROOT));
            else { sender.sendMessage("§eУкажи add|remove и роль: ml_helper|helper|senior_helper|junior_moderator|moderator|senior_moderator|admin."); return true; }
            staffData.save();
            if (target != null) { refreshStaffNameTag(target); refreshStaffModes(target); }
            String targetName = target == null ? knownPlayerName(args[2], targetUuid) : target.getName();
            String newRole = staffData.getString(path, "");
            staffLog(sender.getName(), "staff-" + args[1], targetName, args.length == 4 ? args[3] : "");
            sender.sendMessage("§aРоль обновлена: §f" + targetName + " §7— " + staffRoleColor(newRole) + staffRoleLabel(newRole));
            if (target != null) target.sendMessage("§eТвоя должность изменена: §a" + staffRoleLabel(newRole) + "§e.");
            return true;
        }
        if (args[0].equalsIgnoreCase("warp")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("§6Сохранённые варпы:");
                if (!getConfig().exists("warps") || getConfig().getSection("warps").getKeys(false).isEmpty()) sender.sendMessage("§7Варпов пока нет.");
                else for (String warp : getConfig().getSection("warps").getKeys(false)) sender.sendMessage("§e" + warp);
                return true;
            }
            if (args.length != 3 || !(args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("delete"))) {
                sender.sendMessage("§e/lfadmin warp <set|delete|list> <название>");
                return true;
            }
            String warpName = args[2].toLowerCase(Locale.ROOT);
            if (!warpName.matches("[a-z0-9_-]{1,24}")) { sender.sendMessage("§cНазвание: 1–24 символа, латиница, цифры, _ или -."); return true; }
            if (args[1].equalsIgnoreCase("delete")) {
                if (!getConfig().exists("warps." + warpName)) { sender.sendMessage("§cТакого варпа нет."); return true; }
                getConfig().remove("warps." + warpName); saveConfig();
                staffLog(sender.getName(), "warp-delete", warpName, "operator");
                sender.sendMessage("§aВарп удалён: " + warpName); return true;
            }
            if (!(sender instanceof Player)) { sender.sendMessage("§cСоздать варп можно только находясь в игре."); return true; }
            Player operator = (Player) sender;
            String path = "warps." + warpName;
            getConfig().set(path + ".world", operator.getLevel().getFolderName());
            getConfig().set(path + ".x", operator.getX()); getConfig().set(path + ".y", operator.getY()); getConfig().set(path + ".z", operator.getZ());
            saveConfig(); staffLog(operator.getName(), "warp-set", warpName, operator.getLevel().getFolderName());
            sender.sendMessage("§aВарп сохранён: " + warpName); return true;
        }
        if (args[0].equalsIgnoreCase("money")) {
            if (args.length < 3) { sender.sendMessage("§e/lfadmin money <give|take|set|reset> <игрок> [сумма]"); return true; }
            Player target = getServer().getPlayer(args[2]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; } initialize(target);
            String mode = args[1].toLowerCase(Locale.ROOT); long old = getBalance(target), amount = 0L;
            if (!mode.equals("reset")) { if (args.length != 4) { sender.sendMessage("§eУкажи сумму."); return true; } try { amount = Long.parseLong(args[3]); } catch (NumberFormatException ex) { sender.sendMessage("§cНекорректная сумма."); return true; } if (amount < 0) { sender.sendMessage("§cСумма не может быть отрицательной."); return true; } }
            long next; if (mode.equals("give")) { if (amount > Long.MAX_VALUE - old) { sender.sendMessage("§cПереполнение баланса."); return true; } next = old + amount; } else if (mode.equals("take")) next = Math.max(0L, old - amount); else if (mode.equals("set")) next = amount; else if (mode.equals("reset")) next = 0L; else { sender.sendMessage("§cРежим: give, take, set, reset."); return true; }
            setBalance(target, next); if (!flushPlayers()) { setBalance(target, old); sender.sendMessage("§cНе удалось сохранить баланс; изменение отменено в памяти."); return true; }
            staffLog(((Player)sender).getName(), "money-" + mode, target.getName(), old + " -> " + next); sender.sendMessage("§aБаланс обновлён: " + next); target.sendMessage("§eБаланс изменён оператором: §a" + next); return true;
        }
        if (args[0].equalsIgnoreCase("xp") && args.length == 3) { Player target = getServer().getPlayer(args[1]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; } try { int amount = Integer.parseInt(args[2]); if (amount < 1 || amount > MAX_XP_GRANT) throw new NumberFormatException(); giveXp(target, amount); staffLog(((Player)sender).getName(), "give-xp", target.getName(), String.valueOf(amount)); sender.sendMessage("§aXP начислен."); } catch (NumberFormatException ex) { sender.sendMessage("§cНекорректное количество XP."); } return true; }
        if (args[0].equalsIgnoreCase("reset") && args.length == 3) { Player target = getServer().getPlayer(args[1]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; } String mode = args[2].toLowerCase(Locale.ROOT); if (!Arrays.asList("level", "stats", "quests", "all").contains(mode)) { sender.sendMessage("§clevel|stats|quests|all"); return true; } String key = playerKey(target); if (mode.equals("level") || mode.equals("all")) { set(target,"level",1); set(target,"xp",0); set(target,"points",0); } if (mode.equals("stats") || mode.equals("all")) for (String stat : STATS) set(target,stat,0); if (mode.equals("quests") || mode.equals("all")) { set(target,"quest_kills",0); setBoolean(target,"quest_active",false); setBoolean(target,"quest_complete",false); players.set(key+".quest_type","any"); set(target,"daily_kills",0); setBoolean(target,"daily_active",false); setBoolean(target,"daily_complete",false); setBoolean(target,"daily_claimed",false); for (QuestDefinition q : questCatalog()) { String p=key+".quests."+q.id; players.set(p+".kills",0); players.set(p+".active",false); players.set(p+".complete",false); players.set(p+".claimed",false); } } markPlayersDirty(); flushPlayers(); recalculateStats(target); updateSidebar(target); staffLog(((Player)sender).getName(),"reset-"+mode,target.getName(),"RPG"); sender.sendMessage("§aПрогресс сброшен."); return true; }
        sender.sendMessage("§eНеизвестная команда /lfadmin."); return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("world") || name.equals("worlds")) { handleWorldCommand(sender, args); return true; }

        if (name.equals("trade") || name.equals("market") || name.equals("auction")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            Player player = (Player) sender;
            if (name.equals("trade")) handleTrade(player, args);
            else if (name.equals("market")) handleMarket(player, args);
            else handleAuction(player, args);
            return true;
        }

        if (name.equals("guild")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Команда доступна только игроку."); return true; }
            handleGuildCommand((Player) sender, args);
            return true;
        }

        if (name.equals("balance") || name.equals("pay")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            handleEconomy((Player) sender, name, args);
            return true;
        }

        if (name.equals("sethome") || name.equals("home") || name.equals("delhome")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            handleHome((Player) sender, name);
            return true;
        }

        if (name.equals("tpa") || name.equals("tpaccept") || name.equals("tpdeny")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            handleTpa((Player) sender, name, args);
            return true;
        }

        if (name.equals("spawn") || name.equals("setspawn") || name.equals("rtp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            Player player = (Player) sender;
            if (name.equals("spawn")) teleportToSpawn(player);
            else if (name.equals("setspawn")) {
                if (!player.hasPermission("localfast.admin")) {
                    player.sendMessage("§cНедостаточно прав. Нужна localfast.admin.");
                    return true;
                }
                setSpawn(player);
            } else teleportRandomly(player);
            return true;
        }

        if (name.equals("weekly")) { if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; } handleWeekly((Player)sender,args); return true; }
        if (name.equals("questchain")) { if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; } handleQuestChain((Player)sender,args); return true; }
        if (name.equals("quests")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            openQuestsMenu((Player) sender); return true;
        }
        if(name.equals("daily")){if(!(sender instanceof Player)){sender.sendMessage("Только для игроков.");return true;}handleDaily((Player)sender,args);return true;}
        if (name.equals("quest")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            handleQuest((Player) sender, args);
            return true;
        }

        if (name.equals("achievements") || name.equals("achieve")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            openAchievementsMenu((Player) sender);
            return true;
        }

        if (name.equals("top") || name.equals("leaderboard")) {
            showLeaderboard(sender);
            return true;
        }

        if (name.equals("level") || name.equals("stats") || name.equals("addstat")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            Player player = (Player) sender;
            initialize(player);

            if (name.equals("level")) {
                int level = Math.max(1, Math.min(MAX_LEVEL, get(player, "level", 1)));
                int xp = Math.max(0, get(player, "xp", 0));
                int requiredXp = xpForNextLevel(level);
                sender.sendMessage("§6§l[LocalFast] §fПрогресс персонажа");
                sender.sendMessage("§fУровень: §e" + level + "§7/§e" + MAX_LEVEL);
                if (level >= MAX_LEVEL) {
                    sender.sendMessage("§6Ты достиг максимального уровня!");
                } else {
                    sender.sendMessage("§fОпыт: §a" + xp + "§7/§a" + requiredXp
                            + " §8(осталось " + Math.max(0, requiredXp - xp) + ")");
                    sender.sendMessage("§fПрогресс: " + xpProgressBar(xp, requiredXp));
                }
                sender.sendMessage("§fСвободные очки: §b" + get(player, "points", 0));
                sender.sendMessage("§7За каждый новый уровень начисляется "
                        + Math.max(0, getConfig().getInt("progression.points-per-level", POINTS_PER_LEVEL))
                        + " очка характеристик.");
                return true;
            }

            if (name.equals("stats")) {
                int level = Math.max(1, Math.min(MAX_LEVEL, get(player, "level", 1)));
                int xp = Math.max(0, get(player, "xp", 0));
                sender.sendMessage("§6[LocalFast] §eХарактеристики");
                sender.sendMessage("§fУровень: §e" + level + "§7/§e" + MAX_LEVEL
                        + " §8| §fXP: §a" + xp + "§7/§a" + xpForNextLevel(level));
                if (level < MAX_LEVEL) sender.sendMessage("§fПрогресс: " + xpProgressBar(xp, xpForNextLevel(level)));
                sender.sendMessage("§fСила: §c" + get(player, "strength", 0)
                        + " §fЗдоровье: §c" + get(player, "health", 0));
                sender.sendMessage("§fЛовкость: §a" + get(player, "agility", 0)
                        + " §fИнтеллект: §b" + get(player, "intelligence", 0));
                sender.sendMessage("§fСвободные очки: §e" + get(player, "points", 0));
                return true;
            }

            if (args.length < 1 || args.length > 2) {
                sender.sendMessage("§e/addstat <strength|health|agility|intelligence> [количество]");
                return true;
            }
            String stat = args[0].toLowerCase(Locale.ROOT);
            boolean valid = false;
            for (String allowed : STATS) if (allowed.equals(stat)) valid = true;
            if (!valid) {
                sender.sendMessage("§cВыбери: strength, health, agility или intelligence.");
                return true;
            }
            int amount = 1;
            if (args.length == 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                    if (amount < 1) throw new NumberFormatException();
                } catch (NumberFormatException exception) {
                    sender.sendMessage("§cКоличество должно быть целым числом больше 0.");
                    return true;
                }
            }
            int points = Math.max(0, get(player, "points", 0));
            int currentStat = Math.max(0, get(player, stat, 0));
            int maxStat = Math.max(1, getConfig().getInt("progression.max-stat", 1000));
            if ((long) currentStat + amount > maxStat) {
                sender.sendMessage("§cМаксимум характеристики: " + maxStat + ".");
                return true;
            }
            if (points < amount) {
                sender.sendMessage("§cНедостаточно очков. У тебя есть: " + points + ".");
                return true;
            }
            set(player, stat, currentStat + amount);
            set(player, "points", points - amount);
            markPlayersDirty();
            recalculateStats(player);
            updateSidebar(player);
            sender.sendMessage("§a[LocalFast] Характеристика " + stat + " увеличена на " + amount + ".");
            return true;
        }

        if (name.equals("ability") || name.equals("abilities")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            Player player = (Player) sender;
            if (name.equals("ability")) activateClassAbility(player);
            else openAbilitiesMenu(player);
            return true;
        }

        if (name.equals("class")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            Player player = (Player) sender;
            initialize(player);
            if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
                player.sendMessage("§6Твой класс: " + rpgClassLabel(getRpgClass(player)));
                player.sendMessage(rpgClassDescription(getRpgClass(player)));
                if (getRpgClass(player).equals("none")) openClassMenu(player);
                return true;
            }
            player.sendMessage("§eИспользуй /class для выбора или просмотра класса.");
            return true;
        }

        if (name.equals("lfmenu")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            openStatsMenu((Player) sender);
            return true;
        }

        if (name.equals("lfadmin")) return handleOperatorCommand(sender, args);
        if (name.equals("staff")) return handleStaffCommand(sender, args);
        return false;
    }

    @Override
    public void onDisable() {
        if (players != null) {
            // Force a final flush even if no mutation was observed by the dirty tracker.
            playersDirty = true;
            flushPlayers();
        }
    }
}