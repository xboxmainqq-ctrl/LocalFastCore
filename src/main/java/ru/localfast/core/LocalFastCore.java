package ru.localfast.core;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockChest;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityChest;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.level.Level;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.level.Position;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
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
    private final Map<UUID, Long> legendaryWeaponCooldowns = new HashMap<>();
    private final Map<UUID, Long> legendaryArmorCooldowns = new HashMap<>();
    private final Map<UUID, Long> petAbilityCooldowns = new HashMap<>();
    private final Map<UUID, Long> petWolfDamageUntil = new HashMap<>();
    private final Map<UUID, Long> petGuardUntil = new HashMap<>();
    private final Map<UUID, Long> petSnowXpUntil = new HashMap<>();
    private final Map<UUID, Long> dungeonBossAbilityCooldowns = new HashMap<>();
    private final Map<String, Long> dungeonBossSummonCooldowns = new HashMap<>();
    private final Map<String, Long> dungeonBossAreaCooldowns = new HashMap<>();
    private final Map<Long, Long> dungeonBossSoulSiphonCooldowns = new HashMap<>();
    private final java.util.Set<Long> dungeonBossSecondPhase = new java.util.HashSet<>();
    private final java.util.Set<Long> dungeonBossThirdPhase = new java.util.HashSet<>();
    private final Map<UUID, Long> dungeonTrapCooldowns = new HashMap<>();
    private final Map<UUID, java.util.Set<UUID>> dungeonParties = new HashMap<>();
    private final Map<UUID, UUID> dungeonPartyLeader = new HashMap<>();
    private final Map<UUID, UUID> dungeonPartyInvites = new HashMap<>();
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
        getLogger().info("LocalFastCore 0.54.0 включён. Меню рынка, аукциона, гильдий и переводов, кузница реликвий Разлома, питомцы, экипировка, катакомбы, RPG и система персонала загружены.");
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
        removePet(quittingPlayer);
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
        getServer().getScheduler().scheduleDelayedTask(this, () -> {
            recalculateStats(player);
            UUID id = player.getUniqueId();
            if (dungeonPartyMembers(id).size() < 2) return;
            String key = playerKey(player) + ".dungeon";
            if (!players.getBoolean(key + ".active", false)) return;
            String worldName = getConfig().getString("dungeons.world", "dungeons");
            Level dungeon = getServer().getLevelByName(worldName);
            if (dungeon == null) return;
            if (player.teleport(new Position(0.5, 6, -25.5, dungeon))) {
                player.sendTitle("§a§lВОЗРОЖДЕНИЕ", "§7Товарищи продолжают бой", 5, 30, 10);
                player.sendMessage("§aТы вернулся в командное подземелье. Не оставляй команду!");
            }
        }, 2);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();

        if (activePets.containsValue(event.getEntity())) { event.setCancelled(true); return; }

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

        if (event.getEntity() instanceof Player && event.getDamager() instanceof EntityCreature) {
            Player defender = (Player) event.getEntity();
            int petReduction = selectedPet(defender).equals("golem") ? petGolemReductionPercent(defender) : 0;
            petReduction += petArmorReductionPercent(defender);
            if (petReduction > 0) {
                float petBaseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                event.setDamage(Math.max(0.0f, petBaseDamage * (100 - Math.min(25, petReduction)) / 100.0f),
                        EntityDamageEvent.DamageModifier.BASE);
            }
            if (hasRiftArmorEquipped(defender)) {
                float riftArmorDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                event.setDamage(Math.max(0.0f, riftArmorDamage * 0.90f),
                        EntityDamageEvent.DamageModifier.BASE);
            }
            if (hasKeeperSetEquipped(defender) || hasRiftSetEquipped(defender)) {
                float setDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                event.setDamage(Math.max(0.0f, setDamage * 0.95f),
                        EntityDamageEvent.DamageModifier.BASE);
            }
            long guardUntil = petGuardUntil.getOrDefault(defender.getUniqueId(), 0L);
            if (guardUntil > now) {
                float guardedDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                event.setDamage(Math.max(0.0f, guardedDamage * 0.60f), EntityDamageEvent.DamageModifier.BASE);
            } else if (guardUntil != 0L) {
                petGuardUntil.remove(defender.getUniqueId());
            }
        }

        // The Catacombs Keeper gains three escalating phases on hard difficulty.
        if (event.getEntity() instanceof Player && event.getDamager() instanceof EntityCreature) {
            Player target = (Player) event.getEntity();
            EntityCreature boss = (EntityCreature) event.getDamager();
            String targetPath = playerKey(target);
            if (isDungeonWorld(target.getLevel())
                    && players.getBoolean(targetPath + ".dungeon.active", false)
                    && players.getInt(targetPath + ".dungeon.wave", 1) >= 4
                    && "hard".equals(normalizeDungeonDifficulty(players.getString(targetPath + ".dungeon.difficulty", "normal")))
                    && boss.getNameTag() != null && boss.getNameTag().contains("ХРАНИТЕЛЬ КАТАКОМБ")) {
                boolean secondPhase = boss.getMaxHealth() > 0
                        && boss.getHealth() <= boss.getMaxHealth() * 0.5f;
                boolean thirdPhase = boss.getMaxHealth() > 0
                        && boss.getHealth() <= boss.getMaxHealth() * 0.2f;
                if (secondPhase && dungeonBossSecondPhase.add(boss.getId())) {
                    for (Player member : getServer().getOnlinePlayers().values()) {
                        if (member.getLevel() == target.getLevel()
                                && players.getBoolean(playerKey(member) + ".dungeon.active", false)
                                && players.getInt(playerKey(member) + ".dungeon.wave", 1) >= 4
                                && "hard".equals(normalizeDungeonDifficulty(
                                        players.getString(playerKey(member) + ".dungeon.difficulty", "normal")))) {
                            member.sendTitle("§4§lЯРОСТЬ ХРАНИТЕЛЯ!", "§cВторая фаза — босс усилился!", 5, 45, 10);
                            member.sendMessage("§4Хранитель катакомб впал в ярость! Его атаки стали сильнее.");
                        }
                    }
                }
                if (thirdPhase && dungeonBossThirdPhase.add(boss.getId())) {
                    for (Player member : getServer().getOnlinePlayers().values()) {
                        if (member.getLevel() == target.getLevel()
                                && players.getBoolean(playerKey(member) + ".dungeon.active", false)
                                && players.getInt(playerKey(member) + ".dungeon.wave", 1) >= 4
                                && "hard".equals(normalizeDungeonDifficulty(
                                        players.getString(playerKey(member) + ".dungeon.difficulty", "normal")))) {
                            member.sendTitle("§4§lПОСЛЕДНИЙ РУБЕЖ!", "§cХранитель на грани — берегись!", 0, 50, 10);
                            member.sendMessage("§4Хранитель катакомб перешёл в последнюю фазу! Он призывает больше стражей и наносит усиленные удары.");
                        }
                    }
                }

                long lastAbility = dungeonBossAbilityCooldowns.getOrDefault(target.getUniqueId(), 0L);
                long abilityCooldown = thirdPhase ? 8000L : 12000L;
                if (now - lastAbility >= abilityCooldown) {
                    dungeonBossAbilityCooldowns.put(target.getUniqueId(), now);
                    float baseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                    event.setDamage(baseDamage + 8.0f, EntityDamageEvent.DamageModifier.BASE);
                    target.sendTitle("§4§lСОКРУШИТЕЛЬНЫЙ УДАР", "§cХранитель пробивает защиту!", 0, 25, 5);
                    target.sendMessage("§cХранитель катакомб использовал особую способность: §4Сокрушительный удар§c! Дополнительный урон: 8.");
                }
                if (secondPhase) {
                    float phaseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                    event.setDamage(phaseDamage + 4.0f, EntityDamageEvent.DamageModifier.BASE);
                }
                if (thirdPhase) {
                    float finalPhaseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                    event.setDamage(finalPhaseDamage + 5.0f, EntityDamageEvent.DamageModifier.BASE);
                }
                if (secondPhase) {
                    long lastSiphon = dungeonBossSoulSiphonCooldowns.getOrDefault(boss.getId(), 0L);
                    long siphonCooldown = thirdPhase ? 20000L : 30000L;
                    if (now - lastSiphon >= siphonCooldown) {
                        dungeonBossSoulSiphonCooldowns.put(boss.getId(), now);
                        float siphonDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                        int heal = thirdPhase ? 12 : 8;
                        event.setDamage(siphonDamage + (thirdPhase ? 8.0f : 6.0f), EntityDamageEvent.DamageModifier.BASE);
                        boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + heal));
                        target.sendTitle("§5§lПОХИЩЕНИЕ ЖИЗНИ!", "§dХранитель восстановил " + heal + " HP", 0, 30, 5);
                        target.sendMessage("§5Хранитель использовал «Похищение жизни»: +" + (thirdPhase ? 8 : 6)
                                + " урона и восстановил себе до " + heal + " HP!");
                    }
                }

                String dungeonKey = target.getLevel().getFolderName();
                long lastSummon = dungeonBossSummonCooldowns.getOrDefault(dungeonKey, 0L);
                long summonCooldown = thirdPhase ? 14000L : (secondPhase ? 18000L : 25000L);
                if (now - lastSummon >= summonCooldown) {
                    dungeonBossSummonCooldowns.put(dungeonKey, now);
                    int summoned = 0;
                    int guardCount = thirdPhase ? 4 : (secondPhase ? 3 : 2);
                    for (int i = 0; i < guardCount; i++) {
                        try {
                            double[] xOffsets = {-3.0, 3.0, -3.0, 3.0};
                            double[] zOffsets = {2.0, 2.0, -2.0, -2.0};
                            Entity guard = Entity.createEntity("Zombie",
                                    new Position(boss.getX() + xOffsets[i], boss.getY(), boss.getZ() + zOffsets[i], target.getLevel()));
                            if (guard == null) continue;
                            int guardHealth = thirdPhase ? 35 : 30;
                            guard.setMaxHealth(guardHealth);
                            guard.setHealth(guardHealth);
                            guard.setNameTag("§cПризванный страж §8| §c" + guardHealth + " HP");
                            guard.setNameTagVisible(true);
                            guard.setNameTagAlwaysVisible(true);
                            guard.spawnToAll();
                            summoned++;
                        } catch (RuntimeException exception) {
                            getLogger().warning("Dungeon boss reinforcement spawn failed: " + exception.getMessage());
                        }
                    }
                    if (summoned > 0) {
                        for (Player member : getServer().getOnlinePlayers().values()) {
                            if (member.getLevel().getFolderName().equalsIgnoreCase(dungeonKey)
                                    && players.getBoolean(playerKey(member) + ".dungeon.active", false)) {
                                member.sendTitle("§c§lПОДКРЕПЛЕНИЕ!", "§7Хранитель призвал стражей", 0, 25, 5);
                                member.sendMessage("§cХранитель катакомб призвал " + summoned + " стражей!");
                            }
                        }
                    }
                }

                long lastAreaStrike = dungeonBossAreaCooldowns.getOrDefault(dungeonKey, 0L);
                long areaCooldown = thirdPhase ? 9000L : (secondPhase ? 12000L : 20000L);
                if (now - lastAreaStrike >= areaCooldown) {
                    dungeonBossAreaCooldowns.put(dungeonKey, now);
                    final Level strikeLevel = target.getLevel();
                    final double strikeX = boss.getX();
                    final double strikeY = boss.getY();
                    final double strikeZ = boss.getZ();
                    for (Player member : getServer().getOnlinePlayers().values()) {
                        if (member.getLevel() == strikeLevel
                                && players.getBoolean(playerKey(member) + ".dungeon.active", false)) {
                            String warningTitle = thirdPhase ? "§4§lСМЕРТЕЛЬНАЯ ВОЛНА!"
                                    : (secondPhase ? "§4§lЯРОСТНАЯ ВОЛНА!" : "§4§lУДАРНАЯ ВОЛНА!");
                            String warningText = thirdPhase ? "§cУклоняйся немедленно!"
                                    : (secondPhase ? "§cУклоняйся! У тебя 1 секунда!" : "§cОтойди от Хранителя за 2 секунды!");
                            member.sendTitle(warningTitle, warningText, 0, 40, 5);
                            member.sendMessage(thirdPhase
                                    ? "§4Хранитель готовит смертельную волну! Срочно отойди!"
                                    : (secondPhase ? "§4Хранитель готовит усиленную волну! Срочно уклоняйся!"
                                            : "§cХранитель готовит ударную волну! Срочно отойди!"));
                        }
                    }
                    getServer().getScheduler().scheduleDelayedTask(this, () -> {
                        if (boss.getHealth() <= 0.0f) return;
                        int hitCount = 0;
                        for (Player member : getServer().getOnlinePlayers().values()) {
                            if (member.getLevel() != strikeLevel) continue;
                            String memberPath = playerKey(member);
                            if (!players.getBoolean(memberPath + ".dungeon.active", false)
                                    || players.getInt(memberPath + ".dungeon.wave", 1) < 4
                                    || !"hard".equals(normalizeDungeonDifficulty(
                                            players.getString(memberPath + ".dungeon.difficulty", "normal")))) continue;
                            double dx = member.getX() - strikeX;
                            double dy = member.getY() - strikeY;
                            double dz = member.getZ() - strikeZ;
                            double radius = thirdPhase ? 8.5 : (secondPhase ? 7.0 : 5.5);
                            if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                            int strikeDamage = thirdPhase ? 15 : (secondPhase ? 12 : 10);
                            member.setHealth(Math.max(1.0f, member.getHealth() - strikeDamage));
                            member.sendTitle("§4§lПОПАДАНИЕ!", "§cУдарная волна: -" + strikeDamage + " HP", 0, 25, 5);
                            member.sendMessage("§cУдарная волна Хранителя нанесла " + strikeDamage + " урона. В следующий раз уклонись!");
                            hitCount++;
                        }
                        if (hitCount > 0) {
                            getLogger().info("Dungeon Keeper area strike hit " + hitCount + " player(s).");
                        }
                    }, thirdPhase ? 10 : (secondPhase ? 20 : 40));
                }
            }
        }

        // Dungeon creatures hit harder as the player advances through the waves.
        if (event.getEntity() instanceof Player && event.getDamager() instanceof EntityCreature) {
            Player victim = (Player) event.getEntity();
            String victimPath = playerKey(victim);
            int legendaryArmorLevel = Math.max(0, Math.min(5,
                    players.getInt(victimPath + ".forge.armor-level", 0)));
            long armorReadyAt = legendaryArmorCooldowns.getOrDefault(victim.getUniqueId(), 0L);
            if (legendaryArmorLevel >= 5 && hasLegendaryArmorEquipped(victim)
                    && now >= armorReadyAt && ThreadLocalRandom.current().nextInt(100) < 20) {
                legendaryArmorCooldowns.put(victim.getUniqueId(), now + 25000L);
                event.setDamage(0.0f, EntityDamageEvent.DamageModifier.BASE);
                victim.sendTitle("§b§lЛЕГЕНДАРНАЯ ЗАЩИТА", "§fУдар полностью отражён", 0, 25, 5);
                victim.sendMessage("§bПечать Хранителя заблокировала удар существа! §7Перезарядка: 25 сек.");
                return;
            }
            if (isDungeonWorld(victim.getLevel())) {
                float multiplier = 1.0f;
                if (players.getBoolean(victimPath + ".dungeon.active", false)) {
                    int wave = Math.max(1, players.getInt(victimPath + ".dungeon.wave", 1));
                    if (wave == 1) multiplier = 1.15f;
                    else if (wave == 2) multiplier = 1.45f;
                    else if (wave == 3) multiplier = 1.85f;
                    else multiplier = 2.2f;
                    multiplier *= dungeonDamageMultiplier(players.getString(victimPath + ".dungeon.difficulty", "normal"));
                } else if (players.getBoolean(victimPath + ".boss_hunt.active", false)
                        && victim.getX() >= 99 && victim.getX() <= 141
                        && victim.getZ() >= -21 && victim.getZ() <= 21) {
                    multiplier = 2.2f;
                }
                int forgeArmorLevel = Math.max(0, Math.min(5,
                        players.getInt(victimPath + ".forge.armor-level", 0)));
                multiplier *= (1.0f - forgeArmorLevel * 0.03f);
                if (Math.abs(multiplier - 1.0f) > 0.0001f) {
                    float baseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                    event.setDamage(baseDamage * multiplier, EntityDamageEvent.DamageModifier.BASE);
                }
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
        int forgeWeaponLevel = Math.max(0, Math.min(5,
                players.getInt(playerKey(attacker) + ".forge.weapon-level", 0)));
        bonusDamage += forgeWeaponLevel;
        if (selectedPet(attacker).equals("wolf")) bonusDamage += petWolfBonusDamage(attacker);
        long weaponReadyAt = legendaryWeaponCooldowns.getOrDefault(attackerId, 0L);
        Item heldWeapon = attacker.getInventory().getItemInHand();
        boolean hasLegendaryWeaponEquipped = heldWeapon != null && heldWeapon.getId() == 276
                && "§6Клинок Хранителя".equals(heldWeapon.getCustomName());
        if (heldWeapon != null && heldWeapon.getId() == 276
                && "§5Клинок Разлома".equals(heldWeapon.getCustomName())) {
            bonusDamage += 6;
        }
        if (hasKeeperSetEquipped(attacker)) {
            bonusDamage += 3;
        }
        if (hasRiftSetEquipped(attacker)) {
            bonusDamage += 4;
        }
        if (forgeWeaponLevel >= 5 && hasLegendaryWeaponEquipped && now >= weaponReadyAt
                && ThreadLocalRandom.current().nextInt(100) < 15) {
            legendaryWeaponCooldowns.put(attackerId, now + 10000L);
            bonusDamage += 5;
            attacker.sendTitle("§6§lЛЕГЕНДАРНЫЙ КРИТ", "§c+5 дополнительного урона", 0, 25, 5);
            attacker.sendMessage("§6Печать Хранителя усилила твой удар! §7Перезарядка: 10 сек.");
        }
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

        if (petWolfDamageUntil.getOrDefault(attackerId, 0L) > now) {
            int wolfStrike = hasPetCollar(attacker) ? 12 : 8;
            bonusDamage += wolfStrike;
            petWolfDamageUntil.remove(attackerId);
            attacker.sendMessage("§aВолк усилил твой удар на " + wolfStrike + " урона!");
        }
        bonusDamage += temporaryAbilityDamage.getOrDefault(attackerId, 0);
        bonusDamage += nextAbilityDamage.getOrDefault(attackerId, 0);
        nextAbilityDamage.remove(attackerId);
        if (bonusDamage > 0) {
            event.setDamage(event.getDamage(EntityDamageEvent.DamageModifier.BASE) + bonusDamage,
                    EntityDamageEvent.DamageModifier.BASE);
        }
    }

    private Item createForgeMaterial(int itemId, String customName, int amount) {
        Item item = Item.get(itemId, 0, amount);
        item.setCustomName(customName);
        return item;
    }

    private int countForgeMaterial(Player player, String customName) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            Item item = player.getInventory().getItem(slot);
            if (item != null && item.getId() > 0 && customName.equals(item.getCustomName())) {
                total += item.getCount();
            }
        }
        return total;
    }

    private boolean consumeForgeMaterial(Player player, String customName, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getSize() && remaining > 0; slot++) {
            Item item = player.getInventory().getItem(slot);
            if (item == null || item.getId() <= 0 || !customName.equals(item.getCustomName())) continue;
            int take = Math.min(remaining, item.getCount());
            remaining -= take;
            if (take >= item.getCount()) player.getInventory().setItem(slot, Item.get(0));
            else {
                item.setCount(item.getCount() - take);
                player.getInventory().setItem(slot, item);
            }
        }
        return remaining == 0;
    }

    private boolean hasNamedItem(Player player, String customName) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            Item item = player.getInventory().getItem(slot);
            if (item != null && item.getId() > 0 && customName.equals(item.getCustomName())) return true;
        }
        for (Item item : player.getInventory().getArmorContents()) {
            if (item != null && item.getId() > 0 && customName.equals(item.getCustomName())) return true;
        }
        return false;
    }

    private Item createLegendaryGear(int itemId, String customName) {
        Item item = Item.get(itemId, 0, 1);
        item.setCustomName(customName);
        item.setLore(new String[]{"§7Реликвия Хранителя катакомб", "§dЛегендарное снаряжение"});
        return item;
    }

    private boolean hasLegendaryArmorEquipped(Player player) {
        for (Item item : player.getInventory().getArmorContents()) {
            if (item != null && item.getId() == 311
                    && "§bДоспех Хранителя".equals(item.getCustomName())) return true;
        }
        return false;
    }

    private boolean hasRiftArmorEquipped(Player player) {
        for (Item item : player.getInventory().getArmorContents()) {
            if (item != null && item.getId() == 311
                    && "§5Броня Бездны".equals(item.getCustomName())) return true;
        }
        return false;
    }

    private boolean hasNamedArmorEquipped(Player player, String customName) {
        for (Item item : player.getInventory().getArmorContents()) {
            if (item != null && item.getId() == 311 && customName.equals(item.getCustomName())) return true;
        }
        return false;
    }

    private boolean hasNamedWeaponInHand(Player player, int itemId, String customName) {
        Item held = player.getInventory().getItemInHand();
        return held != null && held.getId() == itemId && customName.equals(held.getCustomName());
    }

    private boolean hasKeeperSetEquipped(Player player) {
        return hasNamedWeaponInHand(player, 276, "§6Клинок Хранителя")
                && hasNamedArmorEquipped(player, "§bДоспех Хранителя");
    }

    private boolean hasRiftSetEquipped(Player player) {
        return hasNamedWeaponInHand(player, 276, "§5Клинок Разлома")
                && hasRiftArmorEquipped(player)
                && hasRiftAmulet(player);
    }

    private void showGearCollection(Player player) {
        initialize(player);
        boolean keeperBlade = hasNamedItem(player, "§6Клинок Хранителя");
        boolean keeperArmor = hasNamedItem(player, "§bДоспех Хранителя");
        boolean riftBlade = hasNamedItem(player, "§5Клинок Разлома");
        boolean riftArmor = hasNamedItem(player, "§5Броня Бездны");
        boolean riftAmulet = hasNamedItem(player, "§dСердце Разлома");
        player.sendMessage("§6§lКОЛЛЕКЦИЯ ЭКИПИРОВКИ");
        player.sendMessage("§eКомплект Хранителя: " + (keeperBlade ? "§a✓" : "§7○")
                + " §6Клинок §8| " + (keeperArmor ? "§a✓" : "§7○") + " §bДоспех");
        player.sendMessage("§7Бонус полного комплекта: §a+3 урона и -5% входящего урона.");
        player.sendMessage("§5Комплект Разлома: " + (riftBlade ? "§a✓" : "§7○")
                + " §5Клинок §8| " + (riftArmor ? "§a✓" : "§7○") + " §5Броня"
                + " §8| " + (riftAmulet ? "§a✓" : "§7○") + " §dСердце");
        player.sendMessage("§7Бонус полного комплекта: §c+4 урона, §a-5% входящего урона и +5% XP.");
        player.sendMessage("§8Бонусы активны, когда клинок в руке, а броня надета.");
        player.sendMessage("§e/rift forge §7— создать реликвии Разлома.");
    }

    private boolean hasRiftAmulet(Player player) {
        return hasNamedItem(player, "§dСердце Разлома");
    }

    private Item createRiftRelic(int itemId, String name, String... lore) {
        Item item = Item.get(itemId, 0, 1);
        item.setCustomName(name);
        item.setLore(lore);
        return item;
    }

    private String selectedDungeonDifficulty(String playerKey) {
        return normalizeDungeonDifficulty(players.getString(playerKey + ".dungeon.difficulty", "normal"));
    }

    private final Map<UUID, Entity> activePets = new HashMap<>();

    private String petPath(Player player) { return playerKey(player) + ".pets"; }
    private int petUnlockClears(Player player) { return Math.max(0, players.getInt(playerKey(player) + ".dungeon.clears", 0)); }
    private int petLevel(Player player, String pet) {
        return Math.max(1, Math.min(5, players.getInt(petPath(player) + "." + pet + ".level", 1)));
    }
    private int petXp(Player player, String pet) {
        return Math.max(0, players.getInt(petPath(player) + "." + pet + ".xp", 0));
    }
    private int petXpForNextLevel(int level) { return Math.max(100, level * 100); }
    private boolean hasPetCollar(Player player) {
        return players.getBoolean(petPath(player) + ".gear.collar", false);
    }
    private boolean hasPetArmor(Player player) {
        return players.getBoolean(petPath(player) + ".gear.armor", false);
    }
    private int petWolfBonusDamage(Player player) {
        return 1 + (petLevel(player, "wolf") - 1) / 2 + (hasPetCollar(player) ? 1 : 0);
    }
    private int petGolemReductionPercent(Player player) {
        return Math.min(20, 8 + (petLevel(player, "golem") - 1) * 2 + (hasPetCollar(player) ? 2 : 0));
    }
    private int petSnowXpPercent(Player player) {
        return Math.min(20, 10 + (petLevel(player, "snow") - 1) * 2 + (hasPetCollar(player) ? 2 : 0));
    }
    private int petArmorReductionPercent(Player player) {
        return hasPetArmor(player) ? 5 : 0;
    }
    private void grantPetXp(Player player, int amount) {
        String pet = selectedPet(player);
        if (pet.equals("none") || amount <= 0) return;
        String path = petPath(player) + "." + pet;
        int level = petLevel(player, pet);
        if (level >= 5) return;
        int xp = petXp(player, pet) + amount;
        boolean leveled = false;
        while (level < 5 && xp >= petXpForNextLevel(level)) {
            xp -= petXpForNextLevel(level);
            level++;
            leveled = true;
        }
        if (level >= 5) xp = 0;
        players.set(path + ".level", level);
        players.set(path + ".xp", xp);
        markPlayersDirty();
        if (leveled) {
            player.sendTitle("§6§lПИТОМЕЦ ПОВЫСИЛ УРОВЕНЬ!", petLabel(pet) + " §eур. " + level, 5, 35, 5);
            player.sendMessage("§aТвой питомец достиг " + level + "-го уровня! Его бонус стал сильнее.");
            Entity companion = activePets.get(player.getUniqueId());
            if (companion != null && !companion.isClosed()) companion.setNameTag(petLabel(pet) + " §eур. " + level);
        }
    }
    private boolean isPetUnlocked(Player player, String pet) {
        if (pet.equals("wolf")) return true;
        int clears = petUnlockClears(player);
        return pet.equals("golem") ? clears >= 3 : pet.equals("snow") && clears >= 5;
    }
    private String petLabel(String pet) {
        switch (pet) {
            case "wolf": return "§aВолк-компаньон";
            case "golem": return "§6Железный голем";
            case "snow": return "§bСнежный дух";
            default: return "§7Нет питомца";
        }
    }
    private String petEntityType(String pet) {
        switch (pet) {
            case "wolf": return "Wolf";
            case "golem": return "IronGolem";
            case "snow": return "SnowGolem";
            default: return null;
        }
    }
    private String selectedPet(Player player) {
        String pet = players.getString(petPath(player) + ".selected", "wolf");
        return pet.equals("wolf") || pet.equals("golem") || pet.equals("snow") ? pet : "none";
    }
    private void removePet(Player player) {
        Entity old = activePets.remove(player.getUniqueId());
        if (old != null && !old.isClosed()) old.close();
    }
    private void summonPet(Player player) {
        removePet(player);
        String pet = selectedPet(player);
        if (pet.equals("none") || !isPetUnlocked(player, pet)) return;
        try {
            Entity companion = Entity.createEntity(petEntityType(pet), new Position(player.getX() - 1.2, player.getY(), player.getZ() - 1.2, player.getLevel()));
            if (companion == null) { player.sendMessage("§cНе удалось создать питомца этого типа на текущем ядре."); return; }
            companion.setNameTag(petLabel(pet) + " §eур. " + petLevel(player, pet));
            companion.setNameTagVisible(true);
            companion.setNameTagAlwaysVisible(true);
            companion.spawnToAll();
            activePets.put(player.getUniqueId(), companion);
        } catch (RuntimeException exception) {
            getLogger().warning("Pet spawn failed for " + player.getName() + ": " + exception.getMessage());
            player.sendMessage("§cНе удалось призвать питомца. Проверь поддержку сущностей на ядре.");
        }
    }
    private void openPetsMenu(Player player) {
        initialize(player);
        String selected = selectedPet(player);
        int clears = petUnlockClears(player);
        String petProgress = selected.equals("none") ? "§7Выбери питомца, чтобы увидеть его прогресс."
                : "§eУровень: §f" + petLevel(player, selected) + "§7/§f5  §eОпыт: §f"
                + (petLevel(player, selected) >= 5 ? "МАКС." : petXp(player, selected) + "/" + petXpForNextLevel(petLevel(player, selected)));
        FormWindowSimple form = new FormWindowSimple("§6§lПИТОМЦЫ",
                "§fВыбери спутника. За убийства выбранный питомец получает §e5 XP§f.\n"
                + "§7Максимальный уровень — 5, прогресс сохраняется.\n\n"
                + "§aВолк: §f+" + petWolfBonusDamage(player) + " урон (ур. " + petLevel(player, "wolf") + ")\n"
                + "§6Голем: §f-" + petGolemReductionPercent(player) + "% урона (ур. " + petLevel(player, "golem") + ", 3 победы)\n"
                + "§bСнежный дух: §f+" + petSnowXpPercent(player) + "% XP (ур. " + petLevel(player, "snow") + ", 5 побед)\n"
                + petProgress + "\n§7Ошейник: " + (hasPetCollar(player) ? "§aУстановлен (+сила бонуса)" : "§7Нет")
                + "\n§7Броня: " + (hasPetArmor(player) ? "§aУстановлена (-5% урона)" : "§7Нет")
                + "\n§8Открой кнопку снаряжения, чтобы создать и установить предметы."
                + "\n§7Побед в катакомбах: §e" + clears);
        String[] pets = {"wolf", "golem", "snow"};
        for (String pet : pets) {
            String state = !isPetUnlocked(player, pet) ? "§c[Закрыт]" : selected.equals(pet) ? "§a[Выбран]" : "§e[Выбрать]";
            form.addButton(new ElementButton(state + " " + petLabel(pet)));
        }
        form.addButton(new ElementButton("§7Убрать питомца"));
        form.addButton(new ElementButton("§6Использовать способность питомца"));
        form.addButton(new ElementButton("§eСнаряжение питомца"));
        form.addButton(new ElementButton("§7Главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int choice = form.getResponse().getClickedButtonId();
            if (choice >= 0 && choice < pets.length) {
                String chosen = pets[choice];
                if (!isPetUnlocked(player, chosen)) {
                    int required = chosen.equals("golem") ? 3 : 5;
                    player.sendMessage("§cНужно пройти катакомбы ещё " + Math.max(0, required - clears) + " раз.");
                    return;
                }
                players.set(petPath(player) + ".selected", chosen);
                markPlayersDirty();
                if (!flushPlayers()) { player.sendMessage("§cНе удалось сохранить выбор питомца."); return; }
                summonPet(player);
                player.sendTitle("§6ПИТОМЕЦ ВЫБРАН", petLabel(chosen), 5, 30, 5);
            } else if (choice == pets.length) {
                players.set(petPath(player) + ".selected", "none");
                markPlayersDirty();
                flushPlayers();
                removePet(player);
                player.sendMessage("§7Питомец убран.");
            } else if (choice == pets.length + 1) {
                activatePetAbility(player);
            } else if (choice == pets.length + 2) {
                openPlayerPetGearMenu(player);
            } else if (choice == pets.length + 3) {
                openPlayerMainMenu(player);
            }
        }));
        player.showFormWindow(form);
    }


    private void openPlayerPetGearMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§eСнаряжение питомца",
                "§7Ошейник: " + (hasPetCollar(player) ? "§aустановлен" : "§cне установлен")
                + "\n§7Броня: " + (hasPetArmor(player) ? "§aустановлена" : "§cне установлена")
                + "\n§8Создание требует осколки и кристаллы Катакомб.");
        form.addButton(new ElementButton("§aСоздать ошейник"));
        form.addButton(new ElementButton("§aУстановить ошейник"));
        form.addButton(new ElementButton("§cСнять ошейник"));
        form.addButton(new ElementButton("§bСоздать броню"));
        form.addButton(new ElementButton("§bУстановить броню"));
        form.addButton(new ElementButton("§cСнять броню"));
        form.addButton(new ElementButton("§7Назад к питомцам"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id <= 5) {
                String type = id < 3 ? "collar" : "armor";
                String action = new String[]{"craft","equip","unequip"}[id % 3];
                handlePetGear(player, new String[]{"gear", action, type});
                openPlayerPetGearMenu(player);
            } else openPetsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void handlePetGear(Player player, String[] args) {
        initialize(player);
        String collarName = "§eОшейник спутника";
        String armorName = "§6Броня спутника";
        if (args.length == 1 || args[1].equalsIgnoreCase("info")) {
            player.sendMessage("§6§lЭКИПИРОВКА ПИТОМЦА");
            player.sendMessage("§7Ошейник: " + (hasPetCollar(player) ? "§aустановлен" : "§cне установлен")
                    + " §8— усиливает пассивный бонус и способность питомца.");
            player.sendMessage("§7Броня: " + (hasPetArmor(player) ? "§aустановлена" : "§cне установлена")
                    + " §8— снижает урон от существ на 5%.");
            player.sendMessage("§eСоздание: §f/pets gear craft collar|armor");
            player.sendMessage("§eУстановка: §f/pets gear equip collar|armor");
            player.sendMessage("§7Ошейник: 4 осколка + 2 кристалла. Броня: 8 осколков + 3 кристалла.");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length != 3 || (!args[2].equalsIgnoreCase("collar") && !args[2].equalsIgnoreCase("armor"))) {
            player.sendMessage("§e/pets gear [info|craft collar|armor|equip collar|armor|unequip collar|armor]");
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        String itemName = type.equals("collar") ? collarName : armorName;
        String profileKey = petPath(player) + ".gear." + type;
        if (action.equals("craft")) {
            if (players.getBoolean(profileKey, false) || hasNamedItem(player, itemName)) {
                player.sendMessage("§eЭта экипировка уже установлена или уже лежит в инвентаре.");
                return;
            }
            int shards = type.equals("collar") ? 4 : 8;
            int crystals = type.equals("collar") ? 2 : 3;
            if (countForgeMaterial(player, "§bОсколок катакомб") < shards
                    || countForgeMaterial(player, "§dКристалл катакомб") < crystals) {
                player.sendMessage("§cНе хватает материалов: нужно " + shards + " осколков катакомб и "
                        + crystals + " кристаллов.");
                return;
            }
            if (!consumeForgeMaterial(player, "§bОсколок катакомб", shards)
                    || !consumeForgeMaterial(player, "§dКристалл катакомб", crystals)) {
                player.sendMessage("§cНе удалось списать материалы. Повтори попытку.");
                return;
            }
            Item gear = type.equals("collar") ? Item.get(264, 0, 1) : Item.get(311, 0, 1);
            gear.setCustomName(itemName);
            gear.setLore(new String[]{"§7Экипировка спутника", type.equals("collar")
                    ? "§eУсиливает способности и пассивные бонусы" : "§6Снижает урон хозяина от существ на 5%"});
            Item[] leftovers = player.getInventory().addItem(gear);
            if (leftovers != null && leftovers.length > 0) {
                player.getInventory().addItem(
                        createForgeMaterial(406, "§bОсколок катакомб", shards),
                        createForgeMaterial(388, "§dКристалл катакомб", crystals));
                player.sendMessage("§cОсвободи место в инвентаре. Материалы возвращены.");
                return;
            }
            player.sendMessage("§aСоздано: " + itemName + "§a. Установи: /pets gear equip " + type);
            return;
        }
        if (action.equals("equip")) {
            if (players.getBoolean(profileKey, false)) {
                player.sendMessage("§eЭта экипировка уже установлена.");
                return;
            }
            if (!hasNamedItem(player, itemName)) {
                player.sendMessage("§cСначала создай экипировку: /pets gear craft " + type);
                return;
            }
            if (!consumeForgeMaterial(player, itemName, 1)) {
                player.sendMessage("§cНе удалось установить предмет.");
                return;
            }
            players.set(profileKey, true);
            markPlayersDirty();
            if (!flushPlayers()) {
                players.set(profileKey, false);
                Item returnedGear = Item.get(type.equals("collar") ? 264 : 311, 0, 1);
                returnedGear.setCustomName(itemName);
                player.getInventory().addItem(returnedGear);
                markPlayersDirty();
                player.sendMessage("§cНе удалось сохранить экипировку. Предмет возвращён.");
                return;
            }
            player.sendTitle("§6ЭКИПИРОВКА УСТАНОВЛЕНА", itemName, 5, 30, 5);
            player.sendMessage("§aЭкипировка питомца установлена и сохранена.");
            return;
        }
        if (action.equals("unequip")) {
            if (!players.getBoolean(profileKey, false)) {
                player.sendMessage("§cЭтот слот уже пуст.");
                return;
            }
            players.set(profileKey, false);
            markPlayersDirty();
            flushPlayers();
            player.sendMessage("§7Экипировка снята. Предмет не возвращается.");
            return;
        }
        player.sendMessage("§e/pets gear [info|craft collar|armor|equip collar|armor|unequip collar|armor]");
    }

    private void activatePetAbility(Player player) {
        initialize(player);
        String pet = selectedPet(player);
        if (pet.equals("none") || !isPetUnlocked(player, pet)) {
            player.sendMessage("§cСначала выбери доступного питомца в меню /pets.");
            return;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long readyAt = petAbilityCooldowns.getOrDefault(id, 0L);
        if (readyAt > now) {
            player.sendMessage("§eСпособность питомца перезаряжается: §f" + ((readyAt - now + 999) / 1000) + " сек.");
            return;
        }
        petAbilityCooldowns.put(id, now + 60000L);
        switch (pet) {
            case "wolf":
                int wolfStrike = hasPetCollar(player) ? 12 : 8;
                petWolfDamageUntil.put(id, now + 10000L);
                player.sendTitle("§a§lВОЛЧИЙ РЫВОК", "§fСледующий удар: §c+" + wolfStrike + " урона", 5, 35, 5);
                player.sendMessage("§aВолк усилил следующий удар на " + wolfStrike + " урона. Эффект действует 10 секунд.");
                getServer().getScheduler().scheduleDelayedTask(this, () -> petWolfDamageUntil.remove(id), 200);
                break;
            case "golem":
                int guardDuration = hasPetCollar(player) ? 12000 : 8000;
                petGuardUntil.put(id, now + guardDuration);
                player.sendTitle("§6§lКАМЕННАЯ ЗАЩИТА", "§f-40% входящего урона на " + (guardDuration / 1000) + " сек.", 5, 35, 5);
                player.sendMessage("§6Железный голем защищает тебя: входящий урон от существ снижен на 40% на " + (guardDuration / 1000) + " секунд.");
                getServer().getScheduler().scheduleDelayedTask(this, () -> petGuardUntil.remove(id), guardDuration / 50);
                break;
            case "snow":
                int snowDuration = hasPetCollar(player) ? 90000 : 60000;
                petSnowXpUntil.put(id, now + snowDuration);
                player.sendTitle("§b§lЛЕДЯНОЕ ВДОХНОВЕНИЕ", "§f+50% XP за убийства на " + (snowDuration / 1000) + " сек.", 5, 35, 5);
                player.sendMessage("§bСнежный дух усилил получение XP за убийства на 50% на " + (snowDuration / 1000) + " секунд.");
                getServer().getScheduler().scheduleDelayedTask(this, () -> {
                    if (petSnowXpUntil.getOrDefault(id, 0L) <= System.currentTimeMillis()) {
                        petSnowXpUntil.remove(id);
                    }
                }, snowDuration / 50);
                break;
            default:
                petAbilityCooldowns.remove(id);
                player.sendMessage("§cУ этого питомца пока нет способности.");
        }
    }

    private void handleForge(Player player, String[] args) {
        initialize(player);
        String key = playerKey(player) + ".forge";
        int weaponLevel = Math.max(0, Math.min(5, players.getInt(key + ".weapon-level", 0)));
        int armorLevel = Math.max(0, Math.min(5, players.getInt(key + ".armor-level", 0)));
        String shardName = "§bОсколок катакомб";
        String crystalName = "§dКристалл катакомб";
        String heartName = "§5Сердце Хранителя";
        String weaponName = "§6Клинок Хранителя";
        String armorName = "§bДоспех Хранителя";

        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            player.sendMessage("§6§lКУЗНИЦА КАТАКОМБ");
            player.sendMessage("§7Оружие: §c+" + weaponLevel + " урона §8(макс. 5)");
            player.sendMessage("§7Броня: §a-" + (armorLevel * 3) + "% входящего урона §8(макс. 15%)");
            if (weaponLevel >= 5) player.sendMessage("§6✦ Легендарный эффект: 15% шанс нанести ещё +5 урона (перезарядка 10 сек.)");
            if (armorLevel >= 5) player.sendMessage("§b✦ Легендарный эффект: 20% шанс полностью заблокировать удар существа (перезарядка 25 сек.)");
            player.sendMessage("§7Материалы: §b" + countForgeMaterial(player, shardName) + " осколков §7и §d"
                    + countForgeMaterial(player, crystalName) + " кристаллов");
            player.sendMessage("§7Сердца Хранителя: §5" + countForgeMaterial(player, heartName));
            player.sendMessage("§e/forge weapon §7— улучшить оружие");
            player.sendMessage("§e/forge armor §7— улучшить броню");
            player.sendMessage("§e/forge legendary-weapon §7— создать Клинок Хранителя (ур. оружия 5)");
            player.sendMessage("§e/forge legendary-armor §7— создать Доспех Хранителя (ур. брони 5)");
            player.sendMessage("§e/forge collection §7— коллекция реликвий");
            player.sendMessage("§8Следующий уровень: " + (4 + 2 * Math.max(weaponLevel, armorLevel))
                    + " осколка и " + (1 + Math.max(weaponLevel, armorLevel) / 2) + " кристалл(а).");
            return;
        }

        String type = args[0].toLowerCase(Locale.ROOT);
        if (type.equals("collection")) {
            player.sendMessage("§6§lРЕЛИКВИИ ХРАНИТЕЛЯ");
            player.sendMessage((hasNamedItem(player, weaponName) ? "§a✓ " : "§7○ ") + weaponName);
            player.sendMessage((hasNamedItem(player, armorName) ? "§a✓ " : "§7○ ") + armorName);
            player.sendMessage("§7Клинок активен только в руке, доспех — только в слоте нагрудника.");
            return;
        }
        boolean craftWeapon = type.equals("legendary-weapon");
        boolean craftArmor = type.equals("legendary-armor");
        if (craftWeapon || craftArmor) {
            int requiredLevel = craftWeapon ? weaponLevel : armorLevel;
            String gearName = craftWeapon ? weaponName : armorName;
            Item gear = createLegendaryGear(craftWeapon ? 276 : 311, gearName);
            if (requiredLevel < 5) {
                player.sendMessage("§cСначала улучши " + (craftWeapon ? "оружие" : "броню") + " до 5 уровня.");
                return;
            }
            if (hasNamedItem(player, gearName)) {
                player.sendMessage("§eЭта реликвия уже есть в твоей коллекции.");
                return;
            }
            if (countForgeMaterial(player, heartName) < 1
                    || countForgeMaterial(player, shardName) < 12
                    || countForgeMaterial(player, crystalName) < 4
                    || !player.getInventory().canAddItem(gear)) {
                player.sendMessage("§cНужно: 1 Сердце Хранителя, 12 осколков, 4 кристалла и свободное место.");
                return;
            }
            if (!consumeForgeMaterial(player, heartName, 1)
                    || !consumeForgeMaterial(player, shardName, 12)
                    || !consumeForgeMaterial(player, crystalName, 4)) {
                player.sendMessage("§cНе удалось списать материалы. Проверь инвентарь и повтори.");
                return;
            }
            player.getInventory().addItem(gear);
            player.sendTitle("§6§lРЕЛИКВИЯ СОЗДАНА", gearName, 5, 45, 10);
            player.sendMessage("§aСоздано легендарное снаряжение! Открой §e/forge collection§a.");
            return;
        }
        boolean weapon = type.equals("weapon") || type.equals("sword");
        boolean armor = type.equals("armor");
        if (!weapon && !armor) {
            player.sendMessage("§eИспользуй: /forge [info|weapon|armor|legendary-weapon|legendary-armor|collection]");
            return;
        }
        int current = weapon ? weaponLevel : armorLevel;
        if (current >= 5) {
            player.sendMessage("§aЭто улучшение уже достигло максимального уровня!");
            return;
        }
        int shardCost = 4 + current * 2;
        int crystalCost = 1 + current / 2;
        if (countForgeMaterial(player, shardName) < shardCost
                || countForgeMaterial(player, crystalName) < crystalCost) {
            player.sendMessage("§cНедостаточно материалов. Нужно: §b" + shardCost + " осколка §7и §d"
                    + crystalCost + " кристалл(а). §7Побеждай Хранителя катакомб!");
            return;
        }
        if (!consumeForgeMaterial(player, shardName, shardCost)
                || !consumeForgeMaterial(player, crystalName, crystalCost)) {
            player.getInventory().addItem(createForgeMaterial(406, shardName, shardCost));
            player.sendMessage("§cНе удалось списать материалы. Попробуй ещё раз.");
            return;
        }

        int next = current + 1;
        String field = weapon ? ".weapon-level" : ".armor-level";
        players.set(key + field, next);
        markPlayersDirty();
        if (!flushPlayers()) {
            players.set(key + field, current);
            player.getInventory().addItem(createForgeMaterial(406, shardName, shardCost));
            player.getInventory().addItem(createForgeMaterial(388, crystalName, crystalCost));
            markPlayersDirty();
            player.sendMessage("§cНе удалось сохранить улучшение. Материалы возвращены.");
            return;
        }
        if (weapon) {
            player.sendTitle("§6§lОРУЖИЕ УЛУЧШЕНО", "§c+" + next + " к урону", 5, 35, 10);
            player.sendMessage("§aОружие улучшено до уровня §e" + next + "§a. Постоянный бонус: +" + next + " урона.");
            if (next == 5) player.sendMessage("§6✦ ЛЕГЕНДАРНОЕ ОРУЖИЕ! Теперь доступен усиленный критический удар.");
        } else {
            player.sendTitle("§a§lБРОНЯ УЛУЧШЕНА", "§a-" + (next * 3) + "% урона", 5, 35, 10);
            player.sendMessage("§aБроня улучшена до уровня §e" + next + "§a. Снижение урона от существ: " + (next * 3) + "%.");
            if (next == 5) player.sendMessage("§b✦ ЛЕГЕНДАРНАЯ БРОНЯ! Теперь доступна полная блокировка удара.");
        }
    }

    private void showDungeon(Player p) {
        String k=playerKey(p);
        int wave=Math.max(1,players.getInt(k+".dungeon.wave",1));
        int kills=Math.max(0,players.getInt(k+".dungeon.kills",0));
        boolean active=players.getBoolean(k+".dungeon.active",false);
        boolean complete=players.getBoolean(k+".dungeon.complete",false);
        boolean claimed=players.getBoolean(k+".dungeon.claimed",false);
        p.sendMessage("§6§l[ПОДЗЕМЕЛЬЕ] §f«Затерянные катакомбы»");
        p.sendMessage("§7Этап: §e"+(complete?"Босс повержен": "волна "+Math.min(3,wave)+"/3") + " §8| §7Убийств: §e"+kills+"/3");
        p.sendMessage("§7Финал: §cРазоритель §7| Награда: §a"+getConfig().getInt("dungeons.xp-reward",600)+" XP §7и §e"+getConfig().getLong("dungeons.money-reward",250)+" "+currencyName()+" §7+ лук и стрелы");
        if(claimed) p.sendMessage("§7Награда уже получена сегодня.");
        else if(complete) p.sendMessage("§aСундук открыт! Забери награду в меню приключений.");
        else if(active) p.sendMessage("§eПрохождение активно. /dungeon leave — вернуться.");
        else p.sendMessage("§eОткрой /dungeon и выбери «Затерянные катакомбы».");
    }

    private void openAdventureMenu(Player player) {
        initialize(player);
        String dk=playerKey(player)+".dungeon";
        String bk=playerKey(player)+".boss_hunt";
        boolean dungeonReady=players.getBoolean(dk+".complete",false) && !players.getBoolean(dk+".claimed",false);
        boolean bossReady=players.getBoolean(bk+".complete",false) && !players.getBoolean(bk+".claimed",false);
        FormWindowSimple form=new FormWindowSimple("§6§lПРИКЛЮЧЕНИЯ",
                "§fВыбери место для путешествия. Здесь можно посмотреть награды и отправиться в бой.\n"
                +"§7Катакомбы: 3 волны монстров и финальный босс.\n"
                +"§7Арена: отдельный бой с мировым боссом.");
        form.addButton(new ElementButton((dungeonReady?"§a[НАГРАДА ГОТОВА] ":"§6")+"§fЗатерянные катакомбы\n§7Монстры, босс и сундук с луком"));
        form.addButton(new ElementButton((bossReady?"§a[НАГРАДА ГОТОВА] ":"§c")+"§fАрена мирового босса\n§7Разоритель и ежедневная награда"));
        form.addButton(new ElementButton("§7Закрыть"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if(form.wasClosed()||form.getResponse()==null||!player.isOnline()) return;
            int selected=form.getResponse().getClickedButtonId();
            if(selected==0) openAdventureDetails(player,false);
            else if(selected==1) openAdventureDetails(player,true);
        }));
        player.showFormWindow(form);
    }

    private void openAdventureDetails(Player player, boolean boss) {
        initialize(player);
        String path=playerKey(player)+(boss?".boss_hunt":".dungeon");
        boolean active=players.getBoolean(path+".active",false);
        boolean complete=players.getBoolean(path+".complete",false);
        boolean claimed=players.getBoolean(path+".claimed",false);
        int xp=Math.max(0,getConfig().getInt(boss?"boss-hunt.xp-reward":"dungeons.xp-reward",boss?1200:600));
        long money=Math.max(0L,getConfig().getLong(boss?"boss-hunt.money-reward":"dungeons.money-reward",boss?750L:250L));
        String title=boss?"§c§lАРЕНА МИРОВОГО БОССА":"§6§lЗАТЕРЯННЫЕ КАТАКОМБЫ";
        String description=boss
                ?"§fБой с призванным Разорителем.\n§7Награда: §a"+xp+" XP §7и §e"+money+" "+currencyName()
                :"§fТри волны: зомби, скелеты и пауки. Затем Разоритель и сундук.\n§7Награда: §a"+xp+" XP §7и §e"+money+" "+currencyName()+"\n§7Дополнительно: §fлук, стрелы и Печать Хранителя";
        if(claimed) description+="\n§8Награда за сегодня уже получена.";
        else if(complete) description+="\n§aПобеда! Награда готова к получению.";
        else if(active) description+="\n§eПрохождение уже начато.";
        FormWindowSimple form=new FormWindowSimple(title,description);
        form.addButton(new ElementButton(boss?"§cТелепорт на арену":"§6Телепорт в катакомбы"));
        form.addButton(new ElementButton("§aЗабрать награду"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if(form.wasClosed()||form.getResponse()==null||!player.isOnline()) return;
            int selected=form.getResponse().getClickedButtonId();
            if(selected==0) {
                if(boss) handleBossHunt(player,new String[]{"start"});
                else handleDungeon(player,new String[]{"start"});
            } else if(selected==1) {
                if(boss) handleBossHunt(player,new String[]{"claim"});
                else handleDungeon(player,new String[]{"claim"});
                openAdventureMenu(player);
            } else openAdventureMenu(player);
        }));
        player.showFormWindow(form);
    }

    private String normalizeDungeonDifficulty(String value) {
        if (value == null) return "normal";
        String difficulty = value.toLowerCase(Locale.ROOT);
        return Arrays.asList("easy", "normal", "hard").contains(difficulty) ? difficulty : "normal";
    }

    private String dungeonDifficultyLabel(String difficulty) {
        switch (normalizeDungeonDifficulty(difficulty)) {
            case "easy": return "§aЛёгкая";
            case "hard": return "§cСложная";
            default: return "§eОбычная";
        }
    }

    private double dungeonHealthMultiplier(String difficulty) {
        switch (normalizeDungeonDifficulty(difficulty)) {
            case "easy": return 0.75;
            case "hard": return 1.5;
            default: return 1.0;
        }
    }

    private double dungeonDamageMultiplier(String difficulty) {
        switch (normalizeDungeonDifficulty(difficulty)) {
            case "easy": return 0.75;
            case "hard": return 1.45;
            default: return 1.0;
        }
    }

    private double dungeonRewardMultiplier(String difficulty) {
        switch (normalizeDungeonDifficulty(difficulty)) {
            case "easy": return 0.8;
            case "hard": return 1.5;
            default: return 1.0;
        }
    }


    private void buildRiftArena(Level level) {
        List<CastleBlock> blocks = new ArrayList<>();
        for (int x = 200; x <= 240; x++) {
            for (int z = -20; z <= 20; z++) {
                blocks.add(new CastleBlock(x, 5, z, (Math.abs(x - 220) > 17 || Math.abs(z) > 17) ? 49 : 112));
                blocks.add(new CastleBlock(x, 14, z, 87));
            }
        }
        for (int y = 6; y <= 13; y++) {
            for (int x = 200; x <= 240; x++) {
                blocks.add(new CastleBlock(x, y, -20, 49));
                blocks.add(new CastleBlock(x, y, 20, 49));
            }
            for (int z = -20; z <= 20; z++) {
                blocks.add(new CastleBlock(200, y, z, 49));
                blocks.add(new CastleBlock(240, y, z, 49));
            }
        }
        for (int x : new int[]{207, 233}) {
            for (int z : new int[]{-12, 12}) {
                for (int y = 6; y <= 10; y++) blocks.add(new CastleBlock(x, y, z, 49));
                blocks.add(new CastleBlock(x, 11, z, 89));
            }
        }
        for (CastleBlock block : blocks) {
            try {
                level.getChunk(block.x >> 4, block.z >> 4, true);
                level.setBlock(new Position(block.x, block.y, block.z, level), Block.get(block.id), true, true);
            } catch (RuntimeException exception) {
                getLogger().warning("Rift arena block skipped: " + exception.getMessage());
            }
        }
        getLogger().info("Арена Разлома Бездны построена: " + blocks.size() + " блоков.");
    }

    private Level ensureRiftArena(Player player) {
        Level level = ensureDungeonWorld(player);
        if (level == null) return null;
        if (!getConfig().getBoolean("dungeons.rift-arena-built", false)) {
            buildRiftArena(level);
            getConfig().set("dungeons.rift-arena-built", true);
            saveConfig();
        }
        return level;
    }

    private void spawnRiftWave(Player player, Level level, int wave) {
        String[] types;
        int health;
        double z;
        if (wave == 1) { types = new String[]{"Zombie", "Zombie", "Zombie"}; health = 35; z = -5.5; }
        else if (wave == 2) { types = new String[]{"Skeleton", "Skeleton", "Skeleton"}; health = 55; z = 0.5; }
        else if (wave == 3) { types = new String[]{"Spider", "Spider", "Spider"}; health = 80; z = 6.5; }
        else { types = new String[]{"Ravager"}; health = 650; z = 12.5; }
        for (int i = 0; i < types.length; i++) {
            try {
                Entity mob = Entity.createEntity(types[i],
                        new Position(wave == 4 ? 220.5 : 215.5 + i * 5.0, 6, z, level));
                if (mob == null) {
                    player.sendMessage("§cНе удалось создать противника Разлома: " + types[i]);
                    continue;
                }
                mob.setMaxHealth(health);
                mob.setHealth(health);
                mob.setNameTag(wave == 4
                        ? "§5§lАВАТАР БЕЗДНЫ §7| §d" + health + " HP"
                        : "§5Искажённый страж §8| §d" + health + " HP");
                mob.setNameTagVisible(true);
                mob.setNameTagAlwaysVisible(true);
                mob.spawnToAll();
            } catch (RuntimeException exception) {
                getLogger().warning("Rift enemy spawn failed: " + exception.getMessage());
            }
        }
        players.set(playerKey(player) + ".rift.spawned-wave", wave);
        markPlayersDirty();
        if (wave == 4) {
            player.sendTitle("§5§lАВАТАР БЕЗДНЫ", "§d650 HP • финальное сражение", 5, 50, 10);
            player.sendMessage("§5Разлом раскрылся! Победи Аватара Бездны.");
        } else {
            player.sendTitle("§5§lРАЗЛОМ • ВОЛНА " + wave + "/3", "§dПротивники появились!", 5, 35, 10);
            player.sendMessage("§5Волна " + wave + "/3: уничтожь всех трёх искажённых стражей.");
        }
    }

    private void handleRiftKill(Player killer, String mobName) {
        String key = playerKey(killer) + ".rift";
        if (!players.getBoolean(key + ".active", false)
                || !killer.getLevel().getFolderName().equalsIgnoreCase(getConfig().getString("dungeons.world", "dungeons"))
                || killer.getX() < 199 || killer.getX() > 241 || killer.getZ() < -21 || killer.getZ() > 21) return;
        int wave = Math.max(1, players.getInt(key + ".wave", 1));
        if (wave == 4) {
            if (!mobName.contains("ravager")) return;
            players.set(key + ".active", false);
            players.set(key + ".complete", true);
            players.set(key + ".completed-at", System.currentTimeMillis());
            players.set(key + ".wave", 5);
            markPlayersDirty();
            killer.sendTitle("§5§lРАЗЛОМ ЗАКРЫТ!", "§dАватар Бездны повержен", 10, 60, 15);
            killer.sendMessage("§5§lПОБЕДА! §dНаграда ждёт тебя: §e/dungeon rift claim");
            return;
        }
        String expected = wave == 1 ? "zombie" : wave == 2 ? "skeleton" : "spider";
        if (!mobName.contains(expected)) return;
        int kills = Math.min(3, players.getInt(key + ".kills", 0) + 1);
        players.set(key + ".kills", kills);
        if (kills >= 3) {
            wave++;
            players.set(key + ".wave", wave);
            players.set(key + ".kills", 0);
            players.set(key + ".spawned-wave", wave);
            markPlayersDirty();
            killer.sendTitle("§5§lВОЛНА ПРОЙДЕНА", "§dГотовься к следующей!", 5, 35, 10);
            spawnRiftWave(killer, killer.getLevel(), wave);
        } else {
            markPlayersDirty();
            killer.sendMessage("§5Разлом: волна " + wave + "/3 §d— " + kills + "/3");
        }
    }

    private void handleRiftForge(Player player, String[] args) {
        initialize(player);
        String profile = playerKey(player) + ".rift.gear";
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        String[] names = {"§5Клинок Разлома", "§5Броня Бездны", "§dСердце Разлома"};
        String[] keys = {"weapon", "armor", "amulet"};
        int[] ids = {276, 311, 399};
        int[] costs = {8, 8, 5};
        String shardName = "§5Осколок Бездны";

        if (action.equals("info") || action.equals("help")) {
            player.sendMessage("§5§lКУЗНИЦА РАЗЛОМА");
            player.sendMessage("§7Твои осколки Бездны: §d" + countForgeMaterial(player, shardName));
            player.sendMessage("§e/rift forge weapon §7— Клинок Разлома (8 осколков, +6 урона)");
            player.sendMessage("§e/rift forge armor §7— Броня Бездны (8 осколков, -10% урона существ)");
            player.sendMessage("§e/rift forge amulet §7— Сердце Разлома (5 осколков, +15% XP)");
            player.sendMessage("§e/rift collection §7— коллекция реликвий");
            return;
        }
        if (action.equals("collection")) {
            player.sendMessage("§5§lКОЛЛЕКЦИЯ РАЗЛОМА");
            for (int i = 0; i < names.length; i++) {
                boolean owned = players.getBoolean(profile + "." + keys[i] + "-crafted", false)
                        || hasNamedItem(player, names[i]);
                player.sendMessage((owned ? "§a✓ " : "§7○ ") + names[i]);
            }
            player.sendMessage("§8Клинок действует в руке, броня — в слоте нагрудника, амулет — пока находится в инвентаре.");
            return;
        }
        int index = -1;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(action)) index = i;
        if (index < 0) {
            player.sendMessage("§e/rift forge [info|weapon|armor|amulet|collection]");
            return;
        }
        String flag = profile + "." + keys[index] + "-crafted";
        Item relic = createRiftRelic(ids[index], names[index],
                "§7Реликвия Разлома Бездны",
                index == 0 ? "§c+6 постоянного урона при удержании" :
                index == 1 ? "§a-10% урона от существ при ношении" :
                "§b+15% опыта за победу над существами");
        if (players.getBoolean(flag, false) || hasNamedItem(player, names[index])) {
            player.sendMessage("§eЭта реликвия уже есть в твоей коллекции.");
            return;
        }
        if (!player.getInventory().canAddItem(relic)) {
            player.sendMessage("§cОсвободи место в инвентаре.");
            return;
        }
        if (countForgeMaterial(player, shardName) < costs[index]) {
            player.sendMessage("§cНужно " + costs[index] + " осколков Бездны. Сейчас: "
                    + countForgeMaterial(player, shardName) + ".");
            return;
        }
        if (!consumeForgeMaterial(player, shardName, costs[index])) {
            player.sendMessage("§cНе удалось списать осколки. Повтори попытку.");
            return;
        }
        players.set(flag, true);
        markPlayersDirty();
        if (!flushPlayers()) {
            players.set(flag, false);
            player.getInventory().addItem(createForgeMaterial(399, shardName, costs[index]));
            markPlayersDirty();
            player.sendMessage("§cНе удалось сохранить реликвию. Осколки возвращены.");
            return;
        }
        player.getInventory().addItem(relic);
        player.sendTitle("§5§lРЕЛИКВИЯ СОЗДАНА", names[index], 5, 45, 10);
        player.sendMessage("§aСоздано: " + names[index] + "§a. Реликвия добавлена в коллекцию.");
    }

    private void handleRiftCommand(Player player, String[] args) {
        initialize(player);
        String key = playerKey(player) + ".rift";
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("forge")) {
            handleRiftForge(player, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("collection")) {
            handleRiftForge(player, new String[]{"collection"});
            return;
        }
        if (action.equals("info") || action.equals("help")) {
            player.sendMessage("§5§lРАЗЛОМ БЕЗДНЫ");
            player.sendMessage("§7Три волны искажённых стражей, затем Аватар Бездны.");
            player.sendMessage("§e/dungeon rift start §7— начать; §e/dungeon rift leave §7— выйти.");
            player.sendMessage("§e/dungeon rift claim §7— забрать награду после победы.");
            player.sendMessage("§e/rift forge §7— создать оружие, броню и амулет из осколков.");
            long readyAt = players.getLong(key + ".next-at", 0L);
            if (readyAt > System.currentTimeMillis()) player.sendMessage("§7Следующий вход: §e" + Math.max(1, (readyAt - System.currentTimeMillis() + 59999) / 60000) + " мин.");
            else player.sendMessage("§aВход доступен.");
            return;
        }
        if (action.equals("start")) {
            if (players.getBoolean(key + ".active", false)) { player.sendMessage("§cТы уже внутри Разлома."); return; }
            if (players.getBoolean(key + ".complete", false)) { player.sendMessage("§eСначала забери награду: /dungeon rift claim"); return; }
            if (players.getBoolean(playerKey(player) + ".dungeon.active", false)
                    || players.getBoolean(playerKey(player) + ".boss_hunt.active", false)) {
                player.sendMessage("§cСначала заверши или покинь другое подземелье.");
                return;
            }
            long readyAt = players.getLong(key + ".next-at", 0L);
            if (readyAt > System.currentTimeMillis()) {
                player.sendMessage("§cРазлом ещё восстанавливается. Осталось примерно " + Math.max(1, (readyAt - System.currentTimeMillis() + 59999) / 60000) + " мин.");
                return;
            }
            Level level = ensureRiftArena(player);
            if (level == null) return;
            players.set(key + ".return-world", player.getLevel().getFolderName());
            players.set(key + ".return-x", player.getX());
            players.set(key + ".return-y", player.getY());
            players.set(key + ".return-z", player.getZ());
            players.set(key + ".active", true);
            players.set(key + ".complete", false);
            players.set(key + ".claimed", false);
            players.set(key + ".wave", 1);
            players.set(key + ".kills", 0);
            players.set(key + ".spawned-wave", 0);
            markPlayersDirty();
            if (!player.teleport(new Position(220.5, 6, -14.5, level))) {
                players.set(key + ".active", false);
                markPlayersDirty();
                player.sendMessage("§cНе удалось войти в Разлом.");
                return;
            }
            player.sendTitle("§5§lРАЗЛОМ БЕЗДНЫ", "§dЗакрой разлом и победи Аватара", 10, 50, 10);
            spawnRiftWave(player, level, 1);
            return;
        }
        if (action.equals("leave")) {
            if (!players.getBoolean(key + ".active", false)) { player.sendMessage("§cТы не находишься в Разломе."); return; }
            players.set(key + ".active", false);
            markPlayersDirty();
            String worldName = players.getString(key + ".return-world", "");
            Level world = worldName.isEmpty() ? null : getServer().getLevelByName(worldName);
            if (world == null) world = getServer().getDefaultLevel();
            if (world == null || !player.teleport(new Position(
                    players.getDouble(key + ".return-x", 0.5),
                    players.getDouble(key + ".return-y", 64),
                    players.getDouble(key + ".return-z", 0.5), world))) {
                player.sendMessage("§cНе удалось вернуться. Используй /spawn.");
            } else player.sendMessage("§7Ты покинул Разлом. Прогресс текущего забега сброшен.");
            players.set(key + ".wave", 1);
            players.set(key + ".kills", 0);
            players.set(key + ".spawned-wave", 0);
            markPlayersDirty();
            return;
        }
        if (action.equals("claim")) {
            if (!players.getBoolean(key + ".complete", false) || players.getBoolean(key + ".claimed", false)) {
                player.sendMessage("§cСначала победи Аватара Бездны.");
                return;
            }
            players.set(key + ".complete", false);
            players.set(key + ".claimed", true);
            players.set(key + ".next-at", System.currentTimeMillis() + 24L * 60L * 60L * 1000L);
            giveXp(player, 900);
            setBalance(player, safeAdd(getBalance(player), 500L));
            Item shard = Item.get(399, 0, 3);
            shard.setCustomName("§5Осколок Бездны");
            player.getInventory().addItem(shard);
            markPlayersDirty();
            if (!flushPlayers()) { player.sendMessage("§cНаграда выдана, но сохранение профиля не удалось. Сообщи оператору."); return; }
            player.sendTitle("§5§lНАГРАДА РАЗЛОМА", "§d+900 XP • +500 монет", 5, 45, 10);
            player.sendMessage("§5Получено: §dОсколки Бездны §7(3), §e900 XP §7и §6500 монет§7. Повторный вход через 24 часа.");
            return;
        }
        player.sendMessage("§e/dungeon rift info|start|leave|claim");
    }

    private void spawnDungeonWave(Player player, Level level, int wave) {
        String[] types;
        double z;
        int maxHealth;
        if (wave == 1) { types = new String[]{"Zombie","Zombie","Zombie"}; z = -20.5; maxHealth = 22; }
        else if (wave == 2) { types = new String[]{"Skeleton","Skeleton","Skeleton"}; z = 0.5; maxHealth = 42; }
        else if (wave == 3) { types = new String[]{"Spider","Spider","Spider"}; z = 20.5; maxHealth = 68; }
        else { types = new String[]{"Ravager"}; z = 25.5; maxHealth = 500; }
        String difficulty = normalizeDungeonDifficulty(players.getString(playerKey(player) + ".dungeon.difficulty", "normal"));
        maxHealth = Math.max(1, (int) Math.round(maxHealth * dungeonHealthMultiplier(difficulty)));
        for (int i = 0; i < types.length; i++) {
            try {
                double x = wave == 4 ? 0.5 : (i - 1) * 5.0 + 0.5;
                Entity mob = Entity.createEntity(types[i], new Position(x, 6, z, level));
                if (mob == null) {
                    player.sendMessage("§cНе удалось создать существо: " + types[i] + ". Проверь поддержку мобов сервером.");
                    continue;
                }
                mob.setMaxHealth(maxHealth);
                mob.setHealth(maxHealth);
                mob.setNameTag(wave == 4
                        ? "§c§lХРАНИТЕЛЬ КАТАКОМБ §7| §c" + maxHealth + " HP"
                        : "§7Страж катакомб §8| §c" + maxHealth + " HP");
                mob.setNameTagVisible(true);
                mob.setNameTagAlwaysVisible(true);
                mob.spawnToAll();
            } catch (RuntimeException exception) {
                getLogger().warning("Dungeon mob spawn failed (" + types[i] + "): " + exception.getMessage());
            }
        }
        players.set(playerKey(player) + ".dungeon.spawned-wave", wave);
        markPlayersDirty();
        if (wave == 4) {
            player.sendTitle("§c§lФИНАЛЬНЫЙ БОСС", "§7Хранитель катакомб • " + maxHealth + " HP", 5, 45, 10);
            player.sendMessage("§c§lФИНАЛЬНЫЙ БОСС ВЫШЕЛ! §7У него " + maxHealth + " HP. Сложность: " + dungeonDifficultyLabel(difficulty) + "§7.");
        } else {
            player.sendTitle("§6§lВОЛНА " + wave + "/3", "§7Противники: " + types.length + " • " + maxHealth + " HP", 5, 35, 10);
            player.sendMessage("§6Волна " + wave + "/3 §7— монстры появились! §cHP: " + maxHealth + " §7| Сложность: " + dungeonDifficultyLabel(difficulty));
        }
    }

    private boolean prepareDungeonRewardChest(Level level) {
        try {
            Position chestPosition = new Position(0, 6, 45, level);
            cn.nukkit.level.format.FullChunk chunk = level.getChunk(0, 2, true);
            level.setBlock(chestPosition, Block.get(54), true, true);
            if (!(level.getBlock(chestPosition) instanceof BlockChest)) {
                getLogger().warning("Не удалось установить блок сундука награды катакомб.");
                return false;
            }

            BlockEntity blockEntity = level.getBlockEntity(chestPosition);
            if (!(blockEntity instanceof BlockEntityChest)) {
                // setBlock() alone does not always create a tile entity for a chest.
                CompoundTag nbt = new CompoundTag("")
                        .putList(new ListTag<>("Items"))
                        .putString("id", BlockEntity.CHEST)
                        .putInt("x", 0).putInt("y", 6).putInt("z", 45);
                blockEntity = BlockEntity.createBlockEntity(BlockEntity.CHEST, chunk, nbt);
            }
            if (!(blockEntity instanceof BlockEntityChest)) {
                getLogger().warning("Не удалось создать BlockEntityChest для награды катакомб.");
                return false;
            }

            BlockEntityChest chest = (BlockEntityChest) blockEntity;
            chest.getInventory().clearAll();
            chest.getInventory().addItem(Item.get(261, 0, 1), Item.get(262, 0, 16),
                    Item.get(264, 0, 2), Item.get(266, 0, 8));
            chest.saveNBT();
            getLogger().info("Сундук катакомб заполнен: лук, стрелы, 2 алмаза и 8 золотых слитков.");
            return true;
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось заполнить сундук награды: " + exception.getMessage());
            return false;
        }
    }

    private boolean isDungeonWorld(Level level) {
        return level != null && level.getFolderName().equalsIgnoreCase(
                getConfig().getString("dungeons.world", "dungeons"));
    }

    private void setDungeonGate(Level level, int z, boolean open) {
        for (int x = -2; x <= 2; x++) {
            for (int y = 6; y <= 11; y++) {
                level.setBlock(new Position(x, y, z, level), open ? Block.get(0) : Block.get(98), true, true);
            }
        }
    }

    private void closeDungeonGates(Level level) {
        setDungeonGate(level, -11, false);
        setDungeonGate(level, 10, false);
        setDungeonGate(level, 32, false);
    }

    private void openDungeonGate(Level level, int z) {
        setDungeonGate(level, z, true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void handleDungeonTraps(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Entity companion = activePets.get(player.getUniqueId());
        if (companion != null && !companion.isClosed()
                && (companion.getLevel() != player.getLevel()
                || Math.pow(companion.getX() - player.getX(), 2)
                + Math.pow(companion.getY() - player.getY(), 2)
                + Math.pow(companion.getZ() - player.getZ(), 2) > 36.0)) {
            companion.teleport(new Position(player.getX() - 1.2, player.getY(), player.getZ() - 1.2, player.getLevel()));
        }
        if (!isDungeonWorld(player.getLevel())) return;
        String key = playerKey(player);
        if (!players.getBoolean(key + ".dungeon.active", false)) return;
        int wave = players.getInt(key + ".dungeon.wave", 1);
        if (wave < 1 || wave > 4) return;
        int x = player.getFloorX();
        int z = player.getFloorZ();
        boolean onTrap = x >= -8 && x <= 8
                && (Math.abs(z + 17) <= 1 || Math.abs(z - 4) <= 1 || Math.abs(z - 24) <= 1);
        if (!onTrap) return;
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (now - dungeonTrapCooldowns.getOrDefault(id, 0L) < 2500L) return;
        dungeonTrapCooldowns.put(id, now);
        player.setHealth(Math.max(1.0f, player.getHealth() - 4.0f));
        player.sendMessage("§c§lЛОВУШКА! §7Пол катакомб ранил тебя на 2 сердца.");
        player.sendTitle("§cЛОВУШКА!", "§7Смотри под ноги", 0, 20, 5);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protectDungeonFromExplosions(EntityExplodeEvent event) {
        Position position = event.getPosition();
        if (position != null && isDungeonWorld(position.getLevel())) {
            // Explosions may still deal damage, but never remove dungeon structure blocks.
            event.setBlockList(new ArrayList<>());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protectDungeonBlocks(BlockBreakEvent event) {
        if (isDungeonWorld(event.getPlayer().getLevel())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВ катакомбах нельзя ломать блоки.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protectDungeonPlacement(BlockPlaceEvent event) {
        if (isDungeonWorld(event.getPlayer().getLevel())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВ катакомбах нельзя ставить блоки.");
        }
    }

    private void rewardDungeonWave(Player player, int completedWave) {
        String key = "dungeons.wave-rewards.wave-" + completedWave;
        double multiplier = dungeonRewardMultiplier(players.getString(playerKey(player) + ".dungeon.difficulty", "normal"));
        int xp = Math.max(0, (int) Math.round(getConfig().getInt(key + ".xp", 40 * completedWave) * multiplier));
        long money = Math.max(0L, Math.round(getConfig().getLong(key + ".money", 25L * completedWave) * multiplier));
        if (xp > 0) giveXp(player, xp);
        if (money > 0) setBalance(player, safeAdd(getBalance(player), money));
        markPlayersDirty();
        player.sendMessage("§a[Катакомбы] Волна " + completedWave + " очищена! §e+" + xp
                + " XP §7и §6+" + money + " " + currencyName() + "§a.");
    }

    private void rollDungeonRareLoot(Player player) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 3) {
            player.getInventory().addItem(Item.get(264, 0, 1));
            player.sendMessage("§b✦ РЕДКАЯ ДОБЫЧА: §fАлмаз!");
        } else if (roll < 12) {
            player.getInventory().addItem(Item.get(388, 0, 1));
            player.sendMessage("§a✦ РЕДКАЯ ДОБЫЧА: §fИзумруд!");
        } else if (roll < 30) {
            player.getInventory().addItem(Item.get(266, 0, 2));
            player.sendMessage("§6Добыча: §f2 золотых слитка.");
        }
    }

    private void giveDungeonBossLoot(Player player) {
        player.getInventory().addItem(Item.get(261, 0, 1), Item.get(262, 0, 16),
                Item.get(264, 0, 2), Item.get(266, 0, 8));
        player.sendMessage("§6§lСОКРОВИЩА БОССА: §b2 алмаза §7и §e8 золотых слитков§7, лук и 16 стрел. Печать Хранителя выдаётся отдельно при /dungeon claim.");
    }

    private Level ensureDungeonWorld(Player player) {
        String worldName = getConfig().getString("dungeons.world", "dungeons");
        if (!getServer().isLevelLoaded(worldName)) {
            if (!getServer().isLevelGenerated(worldName)) {
                Class<? extends Generator> generator = Generator.getGenerator("flat");
                if (generator == null) {
                    player.sendMessage("§cНа сервере не найден flat-генератор для подземелья.");
                    return null;
                }
                Map<String, Object> options = new HashMap<>();
                options.put("preset", "2;7,2x3,2;1;");
                options.put("decoration", false);
                if (!getServer().generateLevel(worldName, ThreadLocalRandom.current().nextLong(), generator, options)) {
                    player.sendMessage("§cНе удалось создать отдельный мир подземелья.");
                    return null;
                }
            } else if (!getServer().loadLevel(worldName)) {
                player.sendMessage("§cНе удалось загрузить мир подземелья.");
                return null;
            }
        }
        Level level = getServer().getLevelByName(worldName);
        if (level == null) {
            player.sendMessage("§cМир подземелья недоступен.");
            return null;
        }
        // A previous build may have saved the flag before its scheduled block batches finished.
        // Verify the actual floor block as well as the config flag before trusting the location.
        boolean floorExists = false;
        try {
            level.getChunk(0, 0, true);
            floorExists = level.getBlock(0, 5, 0).getId() == 98;
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось проверить площадку подземелья: " + exception.getMessage());
        }
        boolean refreshCavernStyle = getConfig().getInt("dungeons.visual-style-version", 0) < 1;
        if (refreshCavernStyle) {
            getConfig().set("dungeons.location-built", false);
            getConfig().set("dungeons.wave-gates-built", false);
            getConfig().set("dungeons.treasure-room-built", false);
            getConfig().set("dungeons.traps-built", false);
        }
        if (!getConfig().getBoolean("dungeons.location-built", false) || !floorExists
                || !getConfig().getBoolean("dungeons.wave-gates-built", false)) {
            buildDungeonLocation(level);
            getConfig().set("dungeons.location-built", true);
            getConfig().set("dungeons.wave-gates-built", true);
            getConfig().set("managed-worlds." + worldName + ".type", "dungeons");
            saveConfig();
        }
        if (!getConfig().getBoolean("dungeons.treasure-room-built", false)) {
            buildDungeonTreasureRoom(level);
            getConfig().set("dungeons.treasure-room-built", true);
            saveConfig();
        }
        if (!getConfig().getBoolean("dungeons.traps-built", false)) {
            buildDungeonTraps(level);
            getConfig().set("dungeons.traps-built", true);
            saveConfig();
        }
        if (refreshCavernStyle) {
            getConfig().set("dungeons.visual-style-version", 1);
            saveConfig();
        }
        return level;
    }

    // Width changes along the route to create a natural cave silhouette instead of a box.
    private int dungeonCavernWidth(int z) {
        double t = Math.max(0.0, Math.min(1.0, (z + 32.0) / 64.0));
        return Math.max(7, (int) Math.round(8.0 + 6.0 * Math.sin(Math.PI * t)
                + 1.4 * Math.sin(3.0 * Math.PI * t)));
    }

    private void buildDungeonLocation(Level level) {
        List<CastleBlock> blocks = new ArrayList<>();
        int floorY = 5;
        // Remove the previous rectangular footprint before laying out the new cave silhouette.
        for (int x = -16; x <= 16; x++) {
            for (int z = -32; z <= 32; z++) {
                for (int y = floorY; y <= floorY + 7; y++)
                    level.setBlock(new Position(x, y, z, level), Block.get(0), true, true);
            }
        }
        // Organic cave route: a narrow entrance, broad winding hall and a rounded boss cavern.
        for (int z = -32; z <= 32; z++) {
            int width = dungeonCavernWidth(z);
            for (int x = -width; x <= width; x++) {
                blocks.add(new CastleBlock(x, floorY, z, (Math.abs(x) == width || z % 9 == 0) ? 98 : 1));
                blocks.add(new CastleBlock(x, floorY + 7, z, 4));
            }
            for (int y = floorY + 1; y <= floorY + 6; y++) {
                blocks.add(new CastleBlock(-width, y, z, 98));
                blocks.add(new CastleBlock(width, y, z, 98));
            }
        }
        // End walls close the entrance and the boss cavern; wave gates remain in their original positions.
        for (int y = floorY + 1; y <= floorY + 6; y++) {
            for (int x = -dungeonCavernWidth(-32); x <= dungeonCavernWidth(-32); x++)
                blocks.add(new CastleBlock(x, y, -32, 98));
            for (int x = -dungeonCavernWidth(32); x <= dungeonCavernWidth(32); x++)
                blocks.add(new CastleBlock(x, y, 32, 98));
        }
        // Reinforced gates seal the two wave transitions until their wave is cleared.
        for (int z : new int[]{-11, 10}) {
            for (int x = -2; x <= 2; x++)
                for (int y = floorY + 1; y <= floorY + 6; y++)
                    blocks.add(new CastleBlock(x, y, z, 98));
        }
        // Carved support pillars, overhead ribs and warm glowstone sconces.
        for (int z : new int[]{-26, -19, -3, 4, 19, 27}) {
            int width = dungeonCavernWidth(z);
            for (int side : new int[]{-1, 1}) {
                int px = side * Math.max(4, width - 3);
                for (int y = floorY + 1; y <= floorY + 4; y++)
                    blocks.add(new CastleBlock(px, y, z, 4));
                blocks.add(new CastleBlock(px, floorY + 4, z, 48));
                blocks.add(new CastleBlock(px - side, floorY + 3, z, 89));
                blocks.add(new CastleBlock(px - side, floorY + 4, z, 89));
                // Small stalactites hang from the roof near the cave walls, clear of the path.
                blocks.add(new CastleBlock(side * Math.max(3, width - 5), floorY + 6, z + 1, 4));
                blocks.add(new CastleBlock(side * Math.max(3, width - 5), floorY + 5, z + 1, 4));
            }
            // A broken rib across the ceiling gives each chamber a different silhouette.
            for (int x = -width + 2; x <= width - 2; x++) {
                if (Math.abs(x) > 2)
                    blocks.add(new CastleBlock(x, floorY + 6, z, 4));
            }
        }
        // Rubble and moss along the edges make the floor less uniform without blocking combat.
        for (int z = -30; z <= 30; z += 5) {
            int width = dungeonCavernWidth(z);
            for (int side : new int[]{-1, 1}) {
                blocks.add(new CastleBlock(side * Math.max(2, width - 1), floorY, z + 1, 48));
                blocks.add(new CastleBlock(side * Math.max(2, width - 2), floorY, z + 2, 4));
            }
        }

        int placed = 0;
        for (CastleBlock block : blocks) {
            try {
                if (level.getChunk(block.x >> 4, block.z >> 4, true) == null)
                    throw new IllegalStateException("chunk unavailable");
                level.setBlock(new Position(block.x, block.y, block.z, level),
                        Block.get(block.id), true, true);
                placed++;
            } catch (RuntimeException exception) {
                getLogger().warning("Dungeon builder skipped block " + block.x + ", "
                        + block.y + ", " + block.z + ": " + exception.getMessage());
            }
        }
        getLogger().info("Органические катакомбы построены: " + placed + "/" + blocks.size() + " блоков.");
    }

    private void buildDungeonTraps(Level level) {
        for (int x = -8; x <= 8; x++) {
            for (int z : new int[]{-17, 4, 24}) {
                try {
                    level.getChunk(x >> 4, z >> 4, true);
                    level.setBlock(new Position(x, 5, z, level), Block.get(152), true, true);
                } catch (RuntimeException exception) {
                    getLogger().warning("Не удалось установить ловушку катакомб на " + x + ", " + z + ": " + exception.getMessage());
                }
            }
        }
        getLogger().info("Ловушки катакомб установлены в трёх залах.");
    }

    private void buildDungeonTreasureRoom(Level level) {
        List<CastleBlock> blocks = new ArrayList<>();
        int floorY = 5;
        // Clear the old square vault, then shape a rounded reliquary chamber.
        for (int x = -15; x <= 15; x++) {
            for (int z = 33; z <= 55; z++) {
                for (int y = floorY; y <= floorY + 7; y++)
                    level.setBlock(new Position(x, y, z, level), Block.get(0), true, true);
            }
        }
        for (int z = 33; z <= 55; z++) {
            double t = (z - 33.0) / 22.0;
            int width = Math.max(8, (int) Math.round(9 + 4 * Math.sin(Math.PI * t)
                    + 0.8 * Math.sin(3 * Math.PI * t)));
            for (int x = -width; x <= width; x++) {
                blocks.add(new CastleBlock(x, floorY, z, 98));
                blocks.add(new CastleBlock(x, floorY + 7, z, 4));
            }
            for (int y = floorY + 1; y <= floorY + 6; y++) {
                blocks.add(new CastleBlock(-width, y, z, 98));
                blocks.add(new CastleBlock(width, y, z, 98));
            }
        }
        for (int y = floorY + 1; y <= floorY + 6; y++) {
            for (int x = -8; x <= 8; x++) blocks.add(new CastleBlock(x, y, 55, 98));
            for (int x = -2; x <= 2; x++) blocks.add(new CastleBlock(x, y, 32, 98));
        }
        // Gold-trimmed pillars and glowstone around the central treasure platform.
        for (int z : new int[]{37, 42, 49, 53}) {
            double t = (z - 33.0) / 22.0;
            int width = Math.max(8, (int) Math.round(9 + 4 * Math.sin(Math.PI * t)
                    + 0.8 * Math.sin(3 * Math.PI * t)));
            for (int side : new int[]{-1, 1}) {
                int x = side * Math.max(5, width - 2);
                for (int y = floorY + 1; y <= floorY + 4; y++)
                    blocks.add(new CastleBlock(x, y, z, 4));
                blocks.add(new CastleBlock(x, floorY + 4, z, 41));
                blocks.add(new CastleBlock(x - side, floorY + 3, z, 89));
            }
        }
        for (int x = -2; x <= 2; x++) {
            blocks.add(new CastleBlock(x, floorY, 47, 41));
            blocks.add(new CastleBlock(x, floorY, 48, 41));
        }
        for (CastleBlock block : blocks) {
            try {
                level.getChunk(block.x >> 4, block.z >> 4, true);
                level.setBlock(new Position(block.x, block.y, block.z, level), Block.get(block.id), true, true);
            } catch (RuntimeException exception) {
                getLogger().warning("Treasure room skipped block " + block.x + ", " + block.y + ", " + block.z + ": " + exception.getMessage());
            }
        }
        getLogger().info("Органический зал сокровищ построен и запечатан до победы над боссом.");
    }

    private void buildBossArena(Level level) {
        List<CastleBlock> blocks = new ArrayList<>();
        int floorY = 5;
        for (int x = 100; x <= 140; x++) {
            for (int z = -20; z <= 20; z++) {
                blocks.add(new CastleBlock(x, floorY, z, 98));
                blocks.add(new CastleBlock(x, floorY + 9, z, 4));
            }
        }
        for (int y = floorY + 1; y <= floorY + 8; y++) {
            for (int x = 100; x <= 140; x++) {
                blocks.add(new CastleBlock(x, y, -20, 98));
                blocks.add(new CastleBlock(x, y, 20, 98));
            }
            for (int z = -20; z <= 20; z++) {
                blocks.add(new CastleBlock(100, y, z, 98));
                blocks.add(new CastleBlock(140, y, z, 98));
            }
        }
        int placed = 0;
        for (CastleBlock block : blocks) {
            try {
                level.getChunk(block.x >> 4, block.z >> 4, true);
                level.setBlock(new Position(block.x, block.y, block.z, level), Block.get(block.id), true, true);
                placed++;
            } catch (RuntimeException exception) {
                getLogger().warning("Boss arena skipped block: " + exception.getMessage());
            }
        }
        getLogger().info("Арена мирового босса построена: " + placed + "/" + blocks.size() + " блоков.");
    }

    private void ensureBossArena(Level level) {
        if (!getConfig().getBoolean("boss-hunt.arena-built", false)) {
            buildBossArena(level);
            getConfig().set("boss-hunt.arena-built", true);
            saveConfig();
        }
    }

    private void spawnArenaBoss(Player player, Level level) {
        try {
            Position spawn = new Position(120.5, 6, 0.5, level);
            Entity boss = Entity.createEntity("Ravager", spawn);
            if (boss == null) {
                player.sendMessage("§cНе удалось создать босса. Проверь поддержку Ravager на сервере.");
                players.set(playerKey(player) + ".boss_hunt.active", false);
                markPlayersDirty();
                return;
            }
            boss.setMaxHealth(500);
            boss.setHealth(500);
            boss.setNameTag("§c§lАРЕННЫЙ БОСС §7| §c500 HP");
            boss.setNameTagVisible(true);
            boss.setNameTagAlwaysVisible(true);
            boss.spawnToAll();
            player.sendMessage("§c§lБОСС ВЫШЕЛ НА АРЕНУ!");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось создать босса на арене: " + exception.getMessage());
            players.set(playerKey(player) + ".boss_hunt.active", false);
            markPlayersDirty();
            player.sendMessage("§cНе удалось призвать босса: " + exception.getMessage());
        }
    }

    private void returnFromBossArena(Player player) {
        String key = playerKey(player);
        String worldName = players.getString(key + ".boss_hunt.return-world", "");
        Level returnLevel = worldName.isEmpty() ? null : getServer().getLevelByName(worldName);
        if (returnLevel == null) returnLevel = getServer().getDefaultLevel();
        if (returnLevel != null) {
            double x = players.getDouble(key + ".boss_hunt.return-x", returnLevel.getSpawnLocation().getX());
            double y = players.getDouble(key + ".boss_hunt.return-y", returnLevel.getSpawnLocation().getY());
            double z = players.getDouble(key + ".boss_hunt.return-z", returnLevel.getSpawnLocation().getZ());
            player.teleport(new Position(x, y, z, returnLevel));
        }
    }

    private void returnFromDungeon(Player player) {
        String key = playerKey(player);
        if (!players.exists(key + ".dungeon.return-world")) {
            player.sendMessage("§cТочка возвращения не сохранена. Используй /spawn.");
            return;
        }
        String worldName = players.getString(key + ".dungeon.return-world", "");
        if (!getServer().isLevelLoaded(worldName) && !getServer().loadLevel(worldName)) {
            player.sendMessage("§cНе удалось загрузить прежний мир. Используй /spawn.");
            return;
        }
        Level world = getServer().getLevelByName(worldName);
        if (world == null) return;
        Position target = new Position(players.getDouble(key + ".dungeon.return-x"),
                players.getDouble(key + ".dungeon.return-y"),
                players.getDouble(key + ".dungeon.return-z"), world);
        if (player.teleport(target)) {
            players.remove(key + ".dungeon.return-world");
            players.remove(key + ".dungeon.return-x");
            players.remove(key + ".dungeon.return-y");
            players.remove(key + ".dungeon.return-z");
            markPlayersDirty();
            player.sendMessage("§aТы покинул подземелье и вернулся в прежнее место.");
        } else player.sendMessage("§cНе удалось вернуться в прежний мир.");
    }

    private java.util.Set<UUID> dungeonPartyMembers(UUID playerId) {
        UUID leader = dungeonPartyLeader.getOrDefault(playerId, playerId);
        java.util.Set<UUID> members = dungeonParties.get(leader);
        if (members == null) {
            members = new java.util.HashSet<>();
            members.add(leader);
        }
        return new java.util.HashSet<>(members);
    }

    private Player onlinePlayer(UUID id) {
        for (Player online : getServer().getOnlinePlayers().values()) {
            if (online.getUniqueId().equals(id)) return online;
        }
        return null;
    }

    private void handleDungeonParty(Player player, String[] args) {
        UUID id = player.getUniqueId();
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        UUID leader = dungeonPartyLeader.getOrDefault(id, id);
        if (action.equals("create")) {
            if (dungeonPartyLeader.containsKey(id)) {
                player.sendMessage("§cТы уже состоишь в группе подземелья.");
                return;
            }
            java.util.Set<UUID> members = new java.util.HashSet<>();
            members.add(id);
            dungeonParties.put(id, members);
            dungeonPartyLeader.put(id, id);
            player.sendMessage("§aГруппа создана. Приглашай игроков: §e/dungeon party invite <ник>§a.");
        } else if (action.equals("invite")) {
            if (args.length < 2) { player.sendMessage("§e/dungeon party invite <ник>"); return; }
            if (!id.equals(leader)) { player.sendMessage("§cПриглашать может только лидер группы."); return; }
            java.util.Set<UUID> members = dungeonParties.computeIfAbsent(id, key -> {
                java.util.Set<UUID> set = new java.util.HashSet<>(); set.add(id); dungeonPartyLeader.put(id,id); return set;
            });
            if (members.size() >= 4) { player.sendMessage("§cВ группе уже максимум 4 игрока."); return; }
            Player target = getServer().getPlayer(args[1]);
            if (target == null || !target.isOnline()) { player.sendMessage("§cИгрок должен быть онлайн."); return; }
            if (members.contains(target.getUniqueId())) { player.sendMessage("§eИгрок уже в группе."); return; }
            dungeonPartyInvites.put(target.getUniqueId(), id);
            target.sendMessage("§6" + player.getName() + " §eприглашает тебя в группу подземелья. §a/dungeon party accept");
            player.sendMessage("§aПриглашение отправлено игроку §e" + target.getName() + "§a.");
        } else if (action.equals("accept")) {
            UUID inviter = dungeonPartyInvites.remove(id);
            if (inviter == null || !dungeonParties.containsKey(inviter)) {
                player.sendMessage("§cУ тебя нет действующего приглашения.");
                return;
            }
            java.util.Set<UUID> members = dungeonParties.get(inviter);
            if (members.size() >= 4) { player.sendMessage("§cГруппа уже заполнена."); return; }
            if (dungeonPartyLeader.containsKey(id)) { player.sendMessage("§cСначала выйди из своей группы."); return; }
            members.add(id);
            dungeonPartyLeader.put(id, inviter);
            Player owner = onlinePlayer(inviter);
            player.sendMessage("§aТы вступил в группу подземелья.");
            if (owner != null) owner.sendMessage("§e" + player.getName() + " §aвступил в группу.");
        } else if (action.equals("leave")) {
            java.util.Set<UUID> members = dungeonParties.get(leader);
            if (members == null) { player.sendMessage("§cТы не состоишь в группе."); return; }
            if (id.equals(leader)) {
                for (UUID member : members) dungeonPartyLeader.remove(member);
                dungeonParties.remove(leader);
                player.sendMessage("§eГруппа расформирована.");
            } else {
                members.remove(id);
                dungeonPartyLeader.remove(id);
                player.sendMessage("§eТы покинул группу подземелья.");
                Player owner = onlinePlayer(leader);
                if (owner != null) owner.sendMessage("§e" + player.getName() + " покинул группу.");
            }
        } else {
            java.util.Set<UUID> members = dungeonPartyMembers(id);
            player.sendMessage("§6§lГРУППА ПОДЗЕМЕЛЬЯ §7(" + members.size() + "/4)");
            for (UUID member : members) {
                Player online = onlinePlayer(member);
                player.sendMessage((member.equals(leader) ? "§6★ " : "§7• ")
                        + (online == null ? member.toString().substring(0, 8) : online.getName())
                        + (member.equals(id) ? " §a(ты)" : ""));
            }
            player.sendMessage("§7Команды: create, invite <ник>, accept, leave");
        }
    }

    private String formatDungeonTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long tenths = (Math.max(0L, millis) % 1000L) / 100L;
        return String.format(Locale.ROOT, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    private void showDungeonStats(Player player) {
        String key = playerKey(player);
        int clears = Math.max(0, players.getInt(key + ".dungeon.total-clears", 0));
        int wave = Math.max(1, players.getInt(key + ".dungeon.wave", 1));
        boolean active = players.getBoolean(key + ".dungeon.active", false);
        String lastClear = players.getString(key + ".dungeon.last-clear-date", "ещё не было");
        long bestTime = Math.max(0L, players.getLong(key + ".dungeon.best-clear-ms", 0L));
        player.sendMessage("§6§lТВОИ РЕКОРДЫ КАТАКОМБ");
        player.sendMessage("§7Полных прохождений: §e" + clears);
        player.sendMessage("§7Лучшее время: " + (bestTime > 0L ? "§b" + formatDungeonTime(bestTime) : "§7ещё нет"));
        player.sendMessage("§7Последняя победа: §e" + lastClear);
        player.sendMessage("§7Текущий прогресс: " + (active ? "§aволна " + wave + "/4" : "§7нет активного прохождения"));
        player.sendMessage("§8Топ по скорости: /dungeon speed");
        player.sendMessage("§8Статистика сохраняется между перезапусками сервера.");
    }

    private void showDungeonSpeedLeaderboard(CommandSender sender) {
        Map<String, Object> allProfiles = players.getAll();
        List<Map.Entry<String, Long>> ranking = new ArrayList<>();
        for (String key : allProfiles.keySet()) {
            if (!key.startsWith("uuid_")) continue;
            long best = Math.max(0L, players.getLong(key + ".dungeon.best-clear-ms", 0L));
            if (best > 0L) ranking.add(new java.util.AbstractMap.SimpleEntry<>(key, best));
        }
        ranking.sort((left, right) -> Long.compare(left.getValue(), right.getValue()));
        sender.sendMessage("§b§l━━━━━━━━ ТОП СКОРОСТИ КАТАКОМБ ━━━━━━━━");
        if (ranking.isEmpty()) {
            sender.sendMessage("§7Пока нет завершённых забегов с записанным временем.");
            return;
        }
        int shown = Math.min(10, ranking.size());
        for (int i = 0; i < shown; i++) {
            String key = ranking.get(i).getKey();
            String playerName = players.getString(key + ".last_name", "Неизвестный");
            String prefix = i == 0 ? "§6①" : i == 1 ? "§f②" : i == 2 ? "§c③" : "§7" + (i + 1) + ".";
            sender.sendMessage(prefix + " §f" + playerName + " §8— §b" + formatDungeonTime(ranking.get(i).getValue()));
        }
        if (sender instanceof Player) {
            String ownKey = playerKey((Player) sender);
            for (int i = 0; i < ranking.size(); i++) {
                if (ranking.get(i).getKey().equals(ownKey)) {
                    sender.sendMessage("§7Твоё место по скорости: §b#" + (i + 1) + " §8из §f" + ranking.size());
                    break;
                }
            }
        }
        sender.sendMessage("§8Учитывается лучшее время полного прохождения; меньше — лучше.");
    }

    private void showDungeonLeaderboard(CommandSender sender) {
        Map<String, Object> allProfiles = players.getAll();
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>();
        for (String key : allProfiles.keySet()) {
            if (!key.startsWith("uuid_")) continue;
            int clears = Math.max(0, players.getInt(key + ".dungeon.total-clears", 0));
            if (clears > 0) ranking.add(new java.util.AbstractMap.SimpleEntry<>(key, clears));
        }
        ranking.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        sender.sendMessage("§6§l━━━━━━━━ ТОП КАТАКОМБ ━━━━━━━━");
        if (ranking.isEmpty()) {
            sender.sendMessage("§7Пока никто не завершил катакомбы.");
            return;
        }
        int shown = Math.min(10, ranking.size());
        for (int i = 0; i < shown; i++) {
            String key = ranking.get(i).getKey();
            String playerName = players.getString(key + ".last_name", "Неизвестный");
            int clears = ranking.get(i).getValue();
            String prefix = i == 0 ? "§6①" : i == 1 ? "§f②" : i == 2 ? "§c③" : "§7" + (i + 1) + ".";
            sender.sendMessage(prefix + " §f" + playerName + " §8— §e" + clears + " побед");
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
        sender.sendMessage("§8Сортировка: количество полных побед над Хранителем.");
    }

    private void recordDungeonClear(Player player, long elapsedMillis) {
        String key = playerKey(player);
        int clears = Math.max(0, players.getInt(key + ".dungeon.total-clears", 0));
        players.set(key + ".dungeon.total-clears", clears == Integer.MAX_VALUE ? clears : clears + 1);
        long previousBest = Math.max(0L, players.getLong(key + ".dungeon.best-clear-ms", 0L));
        if (elapsedMillis > 0L && (previousBest == 0L || elapsedMillis < previousBest)) {
            players.set(key + ".dungeon.best-clear-ms", elapsedMillis);
            players.set(key + ".dungeon.last-clear-ms", elapsedMillis);
        }
        players.set(key + ".dungeon.last-clear-date", today());
        players.set(key + ".last_name", player.getName());
    }

    private void handleDungeon(Player p,String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("rift")) {
            handleRiftCommand(p, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("party")) {
            handleDungeonParty(p, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        initialize(p); String k=playerKey(p);
        if(!players.getString(k+".dungeon.date","").equals(today())) {
            players.set(k+".dungeon.date",today()); players.set(k+".dungeon.kills",0);
            players.set(k+".dungeon.active",false); players.set(k+".dungeon.complete",false);
            players.set(k+".dungeon.claimed",false); players.set(k+".dungeon.keeper-trophy-given",false); markPlayersDirty();
        }
        // Bare /dungeon opens the unified adventure menu.
        String action=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("menu")) { openAdventureMenu(p); return; }
        if(action.equals("info")||action.equals("help")) { showDungeon(p); return; }
        if(action.equals("stats")) { showDungeonStats(p); return; }
        if(action.equals("top")) { showDungeonLeaderboard(p); return; }
        if(action.equals("speed") || action.equals("fastest")) { showDungeonSpeedLeaderboard(p); return; }
        if(action.equals("difficulty")) {
            if (players.getBoolean(k + ".dungeon.active", false)) {
                p.sendMessage("§cНельзя менять сложность во время прохождения.");
                return;
            }
            if (args.length < 2) {
                p.sendMessage("§eВыбери сложность: §a/dungeon difficulty easy §7| §e/dungeon difficulty normal §7| §c/dungeon difficulty hard");
                p.sendMessage("§7Сейчас: " + dungeonDifficultyLabel(players.getString(k + ".dungeon.difficulty", "normal")));
                return;
            }
            String requested = args[1].toLowerCase(Locale.ROOT);
            if (!Arrays.asList("easy", "normal", "hard").contains(requested)) {
                p.sendMessage("§cНеизвестная сложность. Используй easy, normal или hard.");
                return;
            }
            UUID leader = dungeonPartyLeader.getOrDefault(p.getUniqueId(), p.getUniqueId());
            if (!leader.equals(p.getUniqueId())) {
                p.sendMessage("§cСложность группы может менять только лидер.");
                return;
            }
            players.set(k + ".dungeon.difficulty", requested);
            markPlayersDirty();
            p.sendMessage("§aСложность катакомб установлена: " + dungeonDifficultyLabel(requested) + "§a.");
            return;
        }
        if(action.equals("start")) {
            UUID requestedLeader = dungeonPartyLeader.getOrDefault(p.getUniqueId(), p.getUniqueId());
            if (!requestedLeader.equals(p.getUniqueId())) {
                p.sendMessage("§cНачать подземелье может только лидер группы.");
                return;
            }
            if (players.getBoolean(k + ".dungeon.active", false)) {
                p.sendMessage("§cПрохождение уже активно.");
                return;
            }
            if (args.length > 1) {
                String requested = args[1].toLowerCase(Locale.ROOT);
                if (!Arrays.asList("easy", "normal", "hard").contains(requested)) {
                    p.sendMessage("§cИспользуй: /dungeon start [easy|normal|hard]");
                    return;
                }
                players.set(k + ".dungeon.difficulty", requested);
            }
            String selectedDifficulty = normalizeDungeonDifficulty(players.getString(k + ".dungeon.difficulty", "normal"));
            players.set(k + ".dungeon.difficulty", selectedDifficulty);
            Level dungeon = ensureDungeonWorld(p);
            if (dungeon == null) return;

            boolean firstLocationEntry = !players.getBoolean(k + ".dungeon.location-entered", false);
            boolean alreadyClaimed = players.getBoolean(k + ".dungeon.claimed", false);
            if ((alreadyClaimed && !firstLocationEntry)
                    || players.getBoolean(k + ".dungeon.complete", false)) {
                showDungeon(p);
                return;
            }

            // 0.22.0 stored a daily "kill 20 mobs" quest in these same fields.
            // Migrate that old state once so it cannot block entry to the real dungeon.
            if (firstLocationEntry) {
                players.set(k + ".dungeon.location-entered", true);
                players.set(k + ".dungeon.kills", 0);
                players.set(k + ".dungeon.complete", false);
                players.set(k + ".dungeon.claimed", false);
            }

            String dungeonWorldName = getConfig().getString("dungeons.world", "dungeons");
            if (!p.getLevel().getFolderName().equalsIgnoreCase(dungeonWorldName)) {
                players.set(k + ".dungeon.return-world", p.getLevel().getFolderName());
                players.set(k + ".dungeon.return-x", p.getX());
                players.set(k + ".dungeon.return-y", p.getY());
                players.set(k + ".dungeon.return-z", p.getZ());
            }
            UUID partyLeader = dungeonPartyLeader.getOrDefault(p.getUniqueId(), p.getUniqueId());
            if (!partyLeader.equals(p.getUniqueId())) {
                p.sendMessage("§cНачать подземелье может только лидер группы.");
                return;
            }
            java.util.Set<UUID> partyMembers = dungeonPartyMembers(p.getUniqueId());
            if (!players.getBoolean(k + ".dungeon.active", false) && !players.getBoolean(k + ".dungeon.complete", false)) {
                closeDungeonGates(dungeon);
                players.set(k + ".dungeon.active", true);
                players.set(k + ".dungeon.wave", 1);
                players.set(k + ".dungeon.kills", 0);
                players.set(k + ".dungeon.spawned-wave", 0);
                players.set(k + ".dungeon.started-at", System.currentTimeMillis());
                players.set(k + ".dungeon.chest-ready", false);
                players.set(k + ".dungeon.loot-fallback-given", false);
                players.set(k + ".dungeon.keeper-trophy-given", false);
            }
            markPlayersDirty();

            for (UUID memberId : partyMembers) {
                Player member = onlinePlayer(memberId);
                if (member == null) continue;
                initialize(member);
                String memberKey = playerKey(member);
                if (member != p && !member.getLevel().getFolderName().equalsIgnoreCase(dungeonWorldName)) {
                    players.set(memberKey + ".dungeon.return-world", member.getLevel().getFolderName());
                    players.set(memberKey + ".dungeon.return-x", member.getX());
                    players.set(memberKey + ".dungeon.return-y", member.getY());
                    players.set(memberKey + ".dungeon.return-z", member.getZ());
                }
                for (String field : new String[]{"active","wave","kills","spawned-wave","started-at","complete","claimed","chest-ready","loot-fallback-given","difficulty","keeper-trophy-given"}) {
                    Object value = players.get(k + ".dungeon." + field);
                    if (value != null) players.set(memberKey + ".dungeon." + field, value);
                }
                if (member != p) member.teleport(new Position(0.5, 6, -25.5, dungeon));
                member.sendMessage("§6§lКОМАНДНОЕ ПОДЗЕМЕЛЬЕ §7— лидер: §e" + p.getName());
            }
            if (!p.teleport(new Position(0.5, 6, -25.5, dungeon))) {
                players.set(k + ".dungeon.active", false);
                markPlayersDirty();
                p.sendMessage("§cНе удалось телепортировать тебя в подземелье.");
                return;
            }
            p.sendMessage("§6§lЗАТЕРЯННЫЕ КАТАКОМБЫ");
            p.sendMessage("§7Команда: " + partyMembers.size() + "/4 игроков. Волны и босс общие.");
            p.sendMessage("§7Сложность: " + dungeonDifficultyLabel(selectedDifficulty) + "§7. Три волны и финальный босс.");
            p.sendMessage("§7Сложность можно выбрать через /dungeon difficulty easy|normal|hard.");
            p.sendMessage("§e/dungeon leave §7— вернуться обратно.");
            int currentWave=Math.max(1,players.getInt(k+".dungeon.wave",1));
            if(players.getInt(k+".dungeon.spawned-wave",0)<currentWave) spawnDungeonWave(p,dungeon,currentWave);
            return;
        }
        if(action.equals("leave")) {
            returnFromDungeon(p);
            return;
        }
        if(action.equals("claim")) {
            if(!players.getBoolean(k+".dungeon.complete",false)||players.getBoolean(k+".dungeon.claimed",false)) { p.sendMessage("§cСначала заверши подземелье."); return; }
            double rewardMultiplier = dungeonRewardMultiplier(players.getString(k + ".dungeon.difficulty", "normal"));
            int xp=Math.max(0,(int)Math.round(getConfig().getInt("dungeons.xp-reward",600) * rewardMultiplier));
            long money=Math.max(0L,Math.round(getConfig().getLong("dungeons.money-reward",250L) * rewardMultiplier));
            players.set(k+".dungeon.complete",false); players.set(k+".dungeon.active",false); players.set(k+".dungeon.claimed",true);
            giveXp(p,xp); setBalance(p,safeAdd(getBalance(p),money));
            boolean trophyGiven = players.getBoolean(k + ".dungeon.keeper-trophy-given", false);
            if (!trophyGiven) {
                p.getInventory().addItem(Item.get(399, 0, 1));
                players.set(k + ".dungeon.keeper-trophy-given", true);
            }
            int shardCount = "hard".equals(selectedDungeonDifficulty(k)) ? 6 : ("easy".equals(selectedDungeonDifficulty(k)) ? 3 : 4);
            int crystalCount = "hard".equals(selectedDungeonDifficulty(k)) ? 2 : 1;
            p.getInventory().addItem(createForgeMaterial(406, "§bОсколок катакомб", shardCount));
            p.getInventory().addItem(createForgeMaterial(388, "§dКристалл катакомб", crystalCount));
            if ("hard".equals(selectedDungeonDifficulty(k))) {
                p.getInventory().addItem(createForgeMaterial(399, "§5Сердце Хранителя", 1));
                p.sendMessage("§5✦ РЕДКАЯ ДОБЫЧА: Сердце Хранителя! Оно нужно для создания легендарной экипировки.");
            }
            markPlayersDirty();
            p.sendMessage("§6Награда подземелья: §a+"+xp+" XP §7и §e+"+money+" "+currencyName()
                    +"§6! §bПечать Хранителя§6 — особая добыча за победу.");
            p.sendMessage("§bКузница: §f+" + shardCount + " осколков и +" + crystalCount
                    + " кристаллов катакомб. Используй §e/forge§f.");
            returnFromDungeon(p);
            return;
        }
        p.sendMessage("§e/dungeon [info|start [easy|normal|hard]|difficulty <easy|normal|hard>|leave|claim|party|stats|top|speed]");
    }

    private boolean isDungeonWaveTarget(String mobName, int wave) {
        if (mobName == null) return false;
        switch (wave) {
            case 1: return mobName.contains("zombie");
            case 2: return mobName.contains("skeleton");
            case 3: return mobName.contains("spider");
            case 4: return mobName.contains("ravager");
            default: return false;
        }
    }

    private boolean isBossMob(String mobName) {
        return mobName.contains("wither") || mobName.contains("enderdragon")
                || mobName.contains("elderguardian") || mobName.contains("ravager");
    }

    private void showBossHunt(Player p) {
        String k=playerKey(p);
        boolean active=players.getBoolean(k+".boss_hunt.active",false);
        boolean complete=players.getBoolean(k+".boss_hunt.complete",false);
        boolean claimed=players.getBoolean(k+".boss_hunt.claimed",false);
        p.sendMessage("§c§l[МИРОВОЙ БОСС] §fАрена легендарного существа");
        p.sendMessage("§7Цель: войти на арену и победить призванного разорителя.");
        p.sendMessage("§7Награда: §a"+getConfig().getInt("boss-hunt.xp-reward",1200)+" XP §7и §e"+getConfig().getLong("boss-hunt.money-reward",750)+" "+currencyName());
        if(claimed) p.sendMessage("§7Награда уже получена. Новая охота доступна после ежедневного сброса.");
        else if(complete) p.sendMessage("§aБосс повержен! §e/boss claim");
        else if(active) p.sendMessage("§eОхота активна. §e/boss leave §7— вернуться обратно.");
        else p.sendMessage("§eНачать бой: /boss start");
    }

    private void handleBossHunt(Player p,String[] args) {
        initialize(p); String k=playerKey(p);
        if(!players.getString(k+".boss_hunt.date","").equals(today())) {
            players.set(k+".boss_hunt.date",today()); players.set(k+".boss_hunt.active",false);
            players.set(k+".boss_hunt.complete",false); players.set(k+".boss_hunt.claimed",false);
            markPlayersDirty();
        }
        String action=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("menu")) { openAdventureMenu(p); return; }
        if(action.equals("info")||action.equals("help")) { showBossHunt(p); return; }
        if(action.equals("start")) {
            if(players.getBoolean(k+".boss_hunt.claimed",false)) { showBossHunt(p); return; }
            Level arena = ensureDungeonWorld(p);
            if(arena == null) return;
            ensureBossArena(arena);
            if (!players.getBoolean(k+".boss_hunt.active",false)) {
                players.set(k+".boss_hunt.return-world",p.getLevel().getFolderName());
                players.set(k+".boss_hunt.return-x",p.getX());
                players.set(k+".boss_hunt.return-y",p.getY());
                players.set(k+".boss_hunt.return-z",p.getZ());
                players.set(k+".boss_hunt.active",true);
                players.set(k+".boss_hunt.complete",false);
                markPlayersDirty();
            }
            if (!p.teleport(new Position(120.5,6,0.5,arena))) {
                players.set(k+".boss_hunt.active",false); markPlayersDirty();
                p.sendMessage("§cНе удалось перенести тебя на арену босса.");
                return;
            }
            if (!players.getBoolean(k+".boss_hunt.spawned",false)) {
                players.set(k+".boss_hunt.spawned",true);
                markPlayersDirty();
                spawnArenaBoss(p,arena);
            } else {
                p.sendMessage("§c§lАРЕНА БОССА §7— продолжай бой.");
            }
            p.sendMessage("§e/boss leave §7— покинуть арену.");
            return;
        }
        if(action.equals("leave")) {
            if(!players.getBoolean(k+".boss_hunt.active",false)) { p.sendMessage("§cТы не участвуешь в охоте."); return; }
            players.set(k+".boss_hunt.active",false);
            players.set(k+".boss_hunt.spawned",false);
            markPlayersDirty();
            returnFromBossArena(p);
            p.sendMessage("§7Ты покинул арену мирового босса.");
            return;
        }
        if(action.equals("claim")) {
            if(!players.getBoolean(k+".boss_hunt.complete",false)||players.getBoolean(k+".boss_hunt.claimed",false)) { p.sendMessage("§cСначала победи босса на арене."); return; }
            int xp=Math.max(0,getConfig().getInt("boss-hunt.xp-reward",1200));
            long money=Math.max(0L,getConfig().getLong("boss-hunt.money-reward",750L));
            players.set(k+".boss_hunt.complete",false); players.set(k+".boss_hunt.active",false); players.set(k+".boss_hunt.claimed",true);
            players.set(k+".boss_hunt.spawned",false);
            giveXp(p,xp); setBalance(p,safeAdd(getBalance(p),money)); markPlayersDirty();
            p.sendMessage("§6Награда за босса: §a+"+xp+" XP §7и §e+"+money+" "+currencyName()+"§6!");
            returnFromBossArena(p);
            return;
        }
        p.sendMessage("§e/boss [info|start|leave|claim]");
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
        initialize(p); refreshDaily(p); summonPet(p); String k=playerKey(p); String d=today();
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
        grantPetXp(killer, 5);
        int mobXp = Math.max(0, getConfig().getInt("progression.mob-kill-xp", MOB_KILL_XP));
        if (selectedPet(killer).equals("snow")) {
            mobXp += Math.max(1, (int) Math.floor(mobXp * petSnowXpPercent(killer) / 100.0));
        }
        if (petSnowXpUntil.getOrDefault(killer.getUniqueId(), 0L) > System.currentTimeMillis()) {
            mobXp += Math.max(1, (int) Math.floor(mobXp * 0.50));
        }
        if (hasRiftAmulet(killer)) {
            mobXp += Math.max(1, (int) Math.floor(mobXp * 0.15));
        }
        if (hasRiftSetEquipped(killer)) {
            mobXp += Math.max(1, (int) Math.floor(mobXp * 0.05));
        }
        giveXp(killer, mobXp);
        set(killer, "achievement_kills", Math.min(Integer.MAX_VALUE, get(killer, "achievement_kills", 0) + 1));
        int xpBonusPercent = intelligenceXpBonusPercent(killer);
        killer.sendMessage("§a[LocalFast] +" + mobXp + " базового XP за победу над существом"
                + (xpBonusPercent > 0 ? " §b(интеллект: +" + xpBonusPercent + "%)" : "") + "§a!");


        String mobName = mob.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String playerPath = playerKey(killer);
        handleRiftKill(killer, mobName);
        if(players.getBoolean(playerPath+".dungeon.active",false)
                && killer.getLevel().getFolderName().equalsIgnoreCase(getConfig().getString("dungeons.world", "dungeons"))
                && killer.getX() >= -14 && killer.getX() <= 14 && killer.getZ() >= -33 && killer.getZ() <= 33
                && !players.getBoolean(playerPath+".dungeon.complete",false)) {
            int wave=Math.max(1,players.getInt(playerPath+".dungeon.wave",1));
            if (!isDungeonWaveTarget(mobName, wave)) {
                // Only the intended creature type can advance this room or drop dungeon bonus loot.
                refreshDaily(killer);
            } else {
            rollDungeonRareLoot(killer);
            if(wave<=3) {
                int kills=Math.min(3,Math.max(0,players.getInt(playerPath+".dungeon.kills",0))+1);
                players.set(playerPath+".dungeon.kills",kills);
                UUID partyLeader = dungeonPartyLeader.getOrDefault(killer.getUniqueId(), killer.getUniqueId());
                java.util.Set<UUID> partyMembers = dungeonPartyMembers(killer.getUniqueId());
                for (UUID memberId : partyMembers) {
                    Player member = onlinePlayer(memberId);
                    if (member == null) continue;
                    String memberPath = playerKey(member) + ".dungeon";
                    players.set(memberPath + ".kills", kills);
                    players.set(memberPath + ".wave", wave);
                    players.set(memberPath + ".active", true);
                    if (kills >= 3) rewardDungeonWave(member, wave);
                }
                if (kills >= 3) {
                    if (wave == 1) {
                        openDungeonGate(killer.getLevel(), -11);
                        killer.sendMessage("§aПервая волна пройдена! Проход во второй зал открыт.");
                    } else if (wave == 2) {
                        openDungeonGate(killer.getLevel(), 10);
                        killer.sendMessage("§aВторая волна пройдена! Проход в третий зал открыт.");
                    }
                    wave++;
                    players.set(playerPath + ".dungeon.wave", wave);
                    players.set(playerPath + ".dungeon.kills", 0);
                    for (UUID memberId : partyMembers) {
                        Player member = onlinePlayer(memberId);
                        if (member != null) {
                            String memberPath = playerKey(member) + ".dungeon";
                            players.set(memberPath + ".wave", wave);
                            players.set(memberPath + ".kills", 0);
                            players.set(memberPath + ".spawned-wave", wave);
                            member.sendTitle("§6§lВОЛНА " + wave + "/3", "§7Команда открыла следующий зал", 5, 35, 10);
                        }
                    }
                    spawnDungeonWave(killer, killer.getLevel(), wave);
                } else {
                    killer.sendMessage("§6[Катакомбы] §eВолна " + wave + "/3: " + kills + "/3");
                    killer.sendTitle("§6ВОЛНА " + wave + "/3", "§eУничтожено: " + kills + "/3", 0, 18, 5);
                }
                markPlayersDirty();
            } else if (wave == 4 && mobName.contains("ravager")) {
                players.set(playerPath + ".dungeon.complete", true);
                players.set(playerPath + ".dungeon.active", false);
                long startedAt = Math.max(0L, players.getLong(playerPath + ".dungeon.started-at", 0L));
                long elapsedMillis = startedAt > 0L ? Math.max(1L, System.currentTimeMillis() - startedAt) : 0L;
                java.util.Set<UUID> winners = dungeonPartyMembers(killer.getUniqueId());
                players.set(playerPath + ".dungeon.wave", 5);
                openDungeonGate(killer.getLevel(), 32);
                boolean chestReady = prepareDungeonRewardChest(killer.getLevel());
                for (UUID memberId : winners) {
                    Player member = onlinePlayer(memberId);
                    if (member == null) continue;
                    String memberPath = playerKey(member) + ".dungeon";
                    players.set(memberPath + ".complete", true);
                    players.set(memberPath + ".active", false);
                    recordDungeonClear(member, elapsedMillis);
                    players.set(memberPath + ".wave", 5);
                    players.set(memberPath + ".chest-ready", chestReady);
                    if (!chestReady) {
                        giveDungeonBossLoot(member);
                        players.set(memberPath + ".loot-fallback-given", true);
                    }
                    member.sendTitle("§6§lПОБЕДА!", "§eКоманда победила Хранителя катакомб", 10, 60, 15);
                    member.sendMessage(chestReady
                            ? "§6Командное подземелье пройдено! §eСокровищница открыта, награда: /dungeon claim."
                            : "§6Командное подземелье пройдено! §eДобыча выдана в инвентарь, награда: /dungeon claim.");
                }
                markPlayersDirty();
                killer.sendTitle("§6§lПОБЕДА!", "§eКатакомбы полностью зачищены", 10, 60, 15);
                killer.sendMessage(chestReady
                        ? "§6§lКАТАКОМБЫ ЗАЧИЩЕНЫ! §eТайная дверь открылась — войди в комнату сокровищ за ареной и открой сундук!"
                        : "§6§lКАТАКОМБЫ ЗАЧИЩЕНЫ! §eДверь в сокровищницу открыта, добыча выдана в инвентарь.");
            }
            }
        }
        if(mobName.contains("ravager") && players.getBoolean(playerPath+".boss_hunt.active",false)
                && killer.getLevel().getFolderName().equalsIgnoreCase(getConfig().getString("dungeons.world", "dungeons"))
                && killer.getX() >= 99 && killer.getX() <= 141 && killer.getZ() >= -21 && killer.getZ() <= 21
                && !players.getBoolean(playerPath+".boss_hunt.complete",false)) {
            players.set(playerPath+".boss_hunt.complete",true);
            players.set(playerPath+".boss_hunt.active",false);
            players.set(playerPath+".boss_hunt.target",mobName);
            players.set(playerPath+".boss_hunt.spawned",false);
            markPlayersDirty();
            killer.sendMessage("§c§lМИРОВОЙ БОСС ПОВЕРЖЕН! §eНаграда: /boss claim");
        }
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
            player.sendMessage("§e/auction claim <id> §7— получить предмет; выплата продавцу проходит автоматически.");
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


    private void openOperatorRpgPlayersMenu(Player operator) {
        if (!isOperator(operator)) return;
        List<Player> online = new ArrayList<>(getServer().getOnlinePlayers().values());
        if (online.isEmpty()) { operator.sendMessage("§cНет игроков онлайн."); return; }
        FormWindowSimple form = new FormWindowSimple("§6RPG §8| §lУправление игроками",
                "§7Выбери игрока, которому нужно изменить RPG-прогресс.");
        for (Player target : online) form.addButton(new ElementButton("§f" + target.getName()
                + "\n§7Ур. " + get(target, "level", 1) + " §8| §6" + getBalance(target) + " монет"));
        form.addButton(new ElementButton("§8Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < online.size() && online.get(id).isOnline())
                openOperatorRpgTargetMenu(operator, online.get(id));
            else openOperatorMenu(operator);
        }));
        operator.showFormWindow(form);
    }

    private void openOperatorRpgTargetMenu(Player operator, Player target) {
        if (!isOperator(operator) || !target.isOnline()) return;
        initialize(target);
        String key = playerKey(target);
        FormWindowSimple form = new FormWindowSimple("§6RPG §8| §f" + target.getName(),
                "§7Уровень: §e" + get(target, "level", 1) + " §8| §7XP: §a" + get(target, "xp", 0)
                + "\n§7Монеты: §6" + getBalance(target) + "\n§7Победы в катакомбах: §e" + petUnlockClears(target));
        form.addButton(new ElementButton("§aВыдать осколки Катакомб"));
        form.addButton(new ElementButton("§5Выдать осколки Бездны"));
        form.addButton(new ElementButton("§dУправление питомцами"));
        form.addButton(new ElementButton("§bСбросить таймеры подземелий"));
        form.addButton(new ElementButton("§cСбросить прогресс Разлома"));
        form.addButton(new ElementButton("§eУстановить уровень питомца"));
        form.addButton(new ElementButton("§6Назад к игрокам"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline() || !target.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) openOperatorMaterialAmountMenu(operator, target, false);
            else if (id == 1) openOperatorMaterialAmountMenu(operator, target, true);
            else if (id == 2) openOperatorPetMenu(operator, target);
            else if (id == 3) {
                String d = key + ".dungeon.";
                players.set(d + "date", "2000-01-01");
                players.set(d + "active", false); players.set(d + "complete", false);
                players.set(d + "claimed", false); players.set(d + "chest-ready", false);
                players.set(d + "loot-fallback-given", false); players.set(d + "keeper-trophy-given", false);
                players.set(d + "wave", 1); players.set(d + "spawned-wave", 0); players.set(d + "started-at", 0L);
                players.set(key + ".boss_hunt.date", "2000-01-01");
                players.set(key + ".boss_hunt.active", false); players.set(key + ".boss_hunt.complete", false);
                players.set(key + ".boss_hunt.claimed", false);
                markPlayersDirty();
                if (flushPlayers()) { staffLog(operator.getName(), "rpg-dungeon-reset-menu", target.getName(), "daily timers"); operator.sendMessage("§aТаймеры подземелий сброшены для " + target.getName()); target.sendMessage("§eОператор сбросил твои таймеры подземелий."); }
                else operator.sendMessage("§cНе удалось сохранить изменения.");
                openOperatorRpgTargetMenu(operator, target);
            } else if (id == 4) {
                players.set(key + ".rift.active", false); players.set(key + ".rift.complete", false);
                players.set(key + ".rift.claimed", false); players.set(key + ".rift.wave", 1);
                players.set(key + ".rift.kills", 0); players.set(key + ".rift.spawned-wave", 0);
                players.set(key + ".rift.next-at", 0L);
                markPlayersDirty();
                if (flushPlayers()) { staffLog(operator.getName(), "rift-reset-menu", target.getName(), "progress and cooldown"); operator.sendMessage("§aПрогресс и таймер Разлома сброшены."); target.sendMessage("§eОператор сбросил твой прогресс Разлома."); }
                else operator.sendMessage("§cНе удалось сохранить изменения.");
                openOperatorRpgTargetMenu(operator, target);
            } else if (id == 5) openOperatorPetMenu(operator, target);
            else openOperatorRpgPlayersMenu(operator);
        }));
        operator.showFormWindow(form);
    }

    private void openOperatorMaterialAmountMenu(Player operator, Player target, boolean rift) {
        String label = rift ? "§5Осколок Бездны" : "§bОсколок катакомб";
        FormWindowSimple form = new FormWindowSimple("§6Выдача ресурса", "§fИгрок: §e" + target.getName()
                + "\n§fРесурс: " + label + "\n§7Выбери действие и количество.");
        int[] amounts = {1, 5, 10, 32, 64};
        for (int amount : amounts) form.addButton(new ElementButton("§aВыдать " + amount));
        for (int amount : amounts) form.addButton(new ElementButton("§cЗабрать " + amount));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline() || !target.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id < 0 || id >= 10) { openOperatorRpgTargetMenu(operator, target); return; }
            int amount = amounts[id % 5];
            boolean give = id < 5;
            Item resource = createForgeMaterial(rift ? 399 : 406, label, amount);
            if (give) {
                Item[] leftovers = target.getInventory().addItem(resource);
                if (leftovers != null && leftovers.length > 0) operator.sendMessage("§cНе всё поместилось в инвентарь.");
            } else {
                int remaining = amount;
                for (Map.Entry<Integer, Item> entry : target.getInventory().getContents().entrySet()) {
                    if (remaining <= 0) break;
                    Item item = entry.getValue();
                    if (item != null && item.getId() == resource.getId() && label.equals(item.getCustomName())) {
                        int take = Math.min(remaining, item.getCount());
                        int left = item.getCount() - take;
                        if (left > 0) { Item reduced = item.clone(); reduced.setCount(left); target.getInventory().setItem(entry.getKey(), reduced); }
                        else target.getInventory().setItem(entry.getKey(), Item.get(0));
                        remaining -= take;
                    }
                }
                if (remaining > 0) operator.sendMessage("§eНе хватило предметов для полного изъятия.");
            }
            staffLog(operator.getName(), (give ? "give-" : "take-") + (rift ? "rift-shard" : "catacomb-shard"), target.getName(), String.valueOf(amount));
            operator.sendMessage((give ? "§aВыдано: " : "§cИзъято: ") + amount + " × " + label);
            openOperatorRpgTargetMenu(operator, target);
        }));
        operator.showFormWindow(form);
    }

    private void openOperatorPetMenu(Player operator, Player target) {
        String[] pets = {"wolf", "golem", "snow"};
        FormWindowSimple form = new FormWindowSimple("§dПитомцы §8| §f" + target.getName(),
                "§7Выбери питомца для настройки. Текущий выбранный: §e" + selectedPet(target));
        for (String pet : pets) form.addButton(new ElementButton(petLabel(pet) + "\n§7Ур. " + petLevel(target, pet)
                + " §8| §7XP " + petXp(target, pet)));
        form.addButton(new ElementButton("§aВыдать ошейник"));
        form.addButton(new ElementButton("§aВыдать броню питомца"));
        form.addButton(new ElementButton("§cСбросить уровни питомцев"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline() || !target.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            String path = petPath(target);
            if (id >= 0 && id < 3) {
                String pet = pets[id];
                players.set(path + "." + pet + ".level", 5);
                players.set(path + "." + pet + ".xp", 0);
                players.set(playerKey(target) + ".dungeon.clears", Math.max(5, petUnlockClears(target)));
                markPlayersDirty();
                flushPlayers();
                staffLog(operator.getName(), "pet-max-level", target.getName(), pet);
                operator.sendMessage("§a" + petLabel(pet) + " установлен 5 уровень.");
                openOperatorPetMenu(operator, target);
            } else if (id == 3 || id == 4) {
                players.set(path + ".gear." + (id == 3 ? "collar" : "armor"), true);
                markPlayersDirty(); flushPlayers();
                staffLog(operator.getName(), "pet-gear-give", target.getName(), id == 3 ? "collar" : "armor");
                operator.sendMessage("§aПредмет питомца выдан.");
                openOperatorPetMenu(operator, target);
            } else if (id == 5) {
                for (String pet : pets) { players.set(path + "." + pet + ".level", 1); players.set(path + "." + pet + ".xp", 0); }
                markPlayersDirty(); flushPlayers();
                staffLog(operator.getName(), "pet-reset", target.getName(), "all pets");
                operator.sendMessage("§eУровни питомцев сброшены.");
                openOperatorPetMenu(operator, target);
            } else openOperatorRpgTargetMenu(operator, target);
        }));
        operator.showFormWindow(form);
    }

    private void openPlayerMainMenu(Player player) {
        initialize(player);
        FormWindowSimple form = new FormWindowSimple("§6§lLOCALFAST §8| §fГлавное меню",
                "§7Твой персонаж: §e" + player.getName() + "\n§7Уровень: §e" + get(player, "level", 1)
                + " §8| §7Монеты: §6" + getBalance(player) + "\n§8Выбери нужный раздел.");
        form.addButton(new ElementButton("§bПерсонаж и характеристики"));
        form.addButton(new ElementButton("§dКласс и способности"));
        form.addButton(new ElementButton("§aПитомцы и их снаряжение"));
        form.addButton(new ElementButton("§6Приключения и подземелья"));
        form.addButton(new ElementButton("§5Разлом Бездны"));
        form.addButton(new ElementButton("§eКузница и улучшение"));
        form.addButton(new ElementButton("§bКоллекция экипировки"));
        form.addButton(new ElementButton("§aКвесты и достижения"));
        form.addButton(new ElementButton("§6Экономика: рынок и переводы"));
        form.addButton(new ElementButton("§dГильдии"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) openStatsMenu(player);
            else if (id == 1) { if (getRpgClass(player).equals("none")) openClassMenu(player); else openAbilitiesMenu(player); }
            else if (id == 2) openPetsMenu(player);
            else if (id == 3) openAdventureMenu(player);
            else if (id == 4) openPlayerRiftMenu(player);
            else if (id == 5) openPlayerForgeMenu(player);
            else if (id == 6) showGearCollection(player);
            else if (id == 7) openPlayerQuestMenu(player);
            else if (id == 8) openPlayerEconomyMenu(player);
            else if (id == 9) openPlayerGuildMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerEconomyMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§6Экономика", "§7Баланс: §e" + getBalance(player) + " " + currencyName() + "\n§7Выбери раздел.");
        form.addButton(new ElementButton("§aРынок предметов"));
        form.addButton(new ElementButton("§eАукцион"));
        form.addButton(new ElementButton("§bПеревести монеты игроку"));
        form.addButton(new ElementButton("§dПринять входящий перевод"));
        form.addButton(new ElementButton("§7Главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) openPlayerMarketMenu(player);
            else if (id == 1) openPlayerAuctionMenu(player);
            else if (id == 2) openPlayerTradeTargetsMenu(player);
            else if (id == 3) {
                handleTrade(player, new String[]{"accept"});
                openPlayerEconomyMenu(player);
            } else openPlayerMainMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerMarketMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§6Рынок", "§7Покупай и выставляй предметы. Продажа выставляет весь предмет из руки.");
        List<String> listingIds = new ArrayList<>();
        for (String id : marketData.getSection("listings").getAll().keySet()) {
            String path = listingPath(id);
            if (marketData.getBoolean(path + ".auction", false)) continue;
            listingIds.add(id);
            form.addButton(new ElementButton("§e#" + id + " §f" + marketData.getString(path + ".item-name", "Предмет")
                    + " x" + marketData.getInt(path + ".count", 1) + " §a" + marketData.getLong(path + ".price", 0L)));
            if (listingIds.size() >= 12) break;
        }
        form.addButton(new ElementButton("§aПродать из руки: 100 монет"));
        form.addButton(new ElementButton("§aПродать из руки: 500 монет"));
        form.addButton(new ElementButton("§aПродать из руки: 1000 монет"));
        form.addButton(new ElementButton("§aПродать из руки: 5000 монет"));
        form.addButton(new ElementButton("§7Обновить"));
        form.addButton(new ElementButton("§8Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < listingIds.size()) {
                handleMarket(player, new String[]{"buy", listingIds.get(id)});
                openPlayerMarketMenu(player);
            } else {
                int sell = id - listingIds.size();
                if (sell >= 0 && sell <= 3) {
                    handleMarket(player, new String[]{"sell", new String[]{"100","500","1000","5000"}[sell]});
                    openPlayerMarketMenu(player);
                } else if (sell == 4) openPlayerMarketMenu(player);
                else openPlayerEconomyMenu(player);
            }
        }));
        player.showFormWindow(form);
    }

    private void openPlayerAuctionMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§eАукцион", "§7Активные торги и завершённые лоты. Выбери лот для ставки или получения предмета.");
        List<String> lotIds = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String id : marketData.getSection("listings").getAll().keySet()) {
            String path = listingPath(id);
            if (!marketData.getBoolean(path + ".auction", false)) continue;
            lotIds.add(id);
            long ends = marketData.getLong(path + ".ends-at", 0L);
            long left = Math.max(0L, ends - now);
            String status = ends > now ? "§a" + (left / 60000L) + " мин." : "§cЗавершён";
            form.addButton(new ElementButton("§e#" + id + " §f" + marketData.getString(path + ".item-name", "Предмет")
                    + " x" + marketData.getInt(path + ".count", 1) + "\n§7Ставка: §a"
                    + marketData.getLong(path + ".current-bid", 0L) + " §8| " + status));
            if (lotIds.size() >= 12) break;
        }
        form.addButton(new ElementButton("§aСоздать лот: 100 монет / 30 мин."));
        form.addButton(new ElementButton("§aСоздать лот: 500 монет / 1 час"));
        form.addButton(new ElementButton("§aСоздать лот: 1000 монет / 24 часа"));
        form.addButton(new ElementButton("§7Обновить"));
        form.addButton(new ElementButton("§8Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < lotIds.size()) openPlayerAuctionLotMenu(player, lotIds.get(id));
            else {
                int action = id - lotIds.size();
                if (action == 0) handleAuction(player, new String[]{"create","100","30"});
                else if (action == 1) handleAuction(player, new String[]{"create","500","60"});
                else if (action == 2) handleAuction(player, new String[]{"create","1000","1440"});
                if (action >= 0 && action <= 3) openPlayerAuctionMenu(player);
                else openPlayerEconomyMenu(player);
            }
        }));
        player.showFormWindow(form);
    }

    private void openPlayerAuctionLotMenu(Player player, String lotId) {
        String path = listingPath(lotId);
        if (!marketData.exists(path + ".seller") || !marketData.getBoolean(path + ".auction", false)) {
            player.sendMessage("§cЛот уже недоступен.");
            openPlayerAuctionMenu(player);
            return;
        }
        String itemName = marketData.getString(path + ".item-name", "Предмет");
        long current = marketData.getLong(path + ".current-bid", 0L);
        long minimum = current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
        long ends = marketData.getLong(path + ".ends-at", 0L);
        boolean active = ends > System.currentTimeMillis();
        FormWindowSimple form = new FormWindowSimple("§eЛот #" + lotId,
                "§f" + itemName + " x" + marketData.getInt(path + ".count", 1)
                + "\n§7Текущая ставка: §a" + current + " " + currencyName()
                + "\n§7Минимальная ставка: §e" + minimum
                + (active ? "\n§7До конца: §a" + Math.max(0L, ends - System.currentTimeMillis()) / 60000L + " мин."
                          : "\n§cТорги завершены."));
        if (active) {
            form.addButton(new ElementButton("§aСтавка: " + minimum));
            long step = Math.max(10L, current / 10L);
            long higher = safeAdd(minimum, step);
            form.addButton(new ElementButton("§eСтавка: " + higher));
            long premium = safeAdd(minimum, 500L);
            form.addButton(new ElementButton("§6Ставка: " + premium));
        } else {
            form.addButton(new ElementButton("§aПолучить предмет / забрать непроданный лот"));
        }
        form.addButton(new ElementButton("§7Назад к аукциону"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (active && id >= 0 && id <= 2) {
                long bid = id == 0 ? minimum : id == 1 ? higher : premium;
                handleAuction(player, new String[]{"bid", lotId, Long.toString(bid)});
            } else if (!active && id == 0) {
                handleAuction(player, new String[]{"claim", lotId});
            }
            openPlayerAuctionMenu(player);
        }));
        player.showFormWindow(form);
    }


        FormWindowSimple form = new FormWindowSimple("§eАукцион", "§7Выставь предмет из руки на торги. Ставки и завершённые лоты пока доступны через команды /auction.");
        form.addButton(new ElementButton("§aСоздать лот: старт 100 монет, 30 минут"));
        form.addButton(new ElementButton("§aСоздать лот: старт 500 монет, 60 минут"));
        form.addButton(new ElementButton("§aСоздать лот: старт 1000 монет, 24 часа"));
        form.addButton(new ElementButton("§eПоказать активные лоты"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) handleAuction(player, new String[]{"create","100","30"});
            else if (id == 1) handleAuction(player, new String[]{"create","500","60"});
            else if (id == 2) handleAuction(player, new String[]{"create","1000","1440"});
            else if (id == 3) handleAuction(player, new String[]{"list"});
            openPlayerEconomyMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerTradeTargetsMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§bПеревод монет", "§7Выбери получателя. Сумму можно выбрать на следующем экране.");
        List<Player> targets = new ArrayList<>();
        for (Player target : getServer().getOnlinePlayers().values()) {
            if (target.isOnline() && !target.getUniqueId().equals(player.getUniqueId())) {
                targets.add(target);
                form.addButton(new ElementButton("§f" + target.getName()));
                if (targets.size() >= 20) break;
            }
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < targets.size() && targets.get(id).isOnline()) openPlayerTradeAmountMenu(player, targets.get(id));
            else openPlayerEconomyMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerTradeAmountMenu(Player player, Player target) {
        FormWindowSimple form = new FormWindowSimple("§bПеревод игроку", "§7Получатель: §e" + target.getName() + "\n§7Выбери сумму предложения.");
        String[] amounts = {"10","50","100","500","1000","5000"};
        for (String amount : amounts) form.addButton(new ElementButton("§a" + amount + " " + currencyName()));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < amounts.length && target.isOnline()) handleTrade(player, new String[]{target.getName(), amounts[id]});
            openPlayerEconomyMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerGuildMenu(Player player) {
        String guildId = currentGuildId(player);
        String invitePath = "invites." + player.getUniqueId().toString();
        boolean hasInvite = !guildData.getString(invitePath + ".guild", "").isEmpty()
                && guildData.getLong(invitePath + ".expires", 0L) >= System.currentTimeMillis();
        FormWindowSimple form = new FormWindowSimple("§dГильдии", guildId.isEmpty()
                ? "§7Ты пока без гильдии. Создание стоит §e" + getConfig().getLong("guilds.creation-cost", 1000L) + " " + currencyName() + "."
                : "§7Текущая гильдия: §e" + guildData.getString("guilds." + guildId + ".name", guildId)
                  + "\n§7Твоя роль: §b" + guildRole(guildId, player.getUniqueId().toString()));
        form.addButton(new ElementButton("§eСписок гильдий сервера"));
        if (guildId.isEmpty()) {
            form.addButton(new ElementButton("§aСоздать гильдию"));
            form.addButton(new ElementButton(hasInvite ? "§bПринять приглашение" : "§7Нет приглашений"));
            if (hasInvite) form.addButton(new ElementButton("§cОтклонить приглашение"));
        } else {
            form.addButton(new ElementButton("§aСостав и управление участниками"));
            if (guildCanManage(guildId, player)) form.addButton(new ElementButton("§bПригласить игрока"));
            form.addButton(new ElementButton("§cПокинуть гильдию"));
            if (guildRole(guildId, player.getUniqueId().toString()).equals("leader")) {
                form.addButton(new ElementButton("§4Распустить гильдию"));
            }
        }
        form.addButton(new ElementButton("§7Главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) openPlayerGuildListMenu(player);
            else if (guildId.isEmpty()) {
                if (id == 1) {
                    String suffix = player.getName().replaceAll("[^A-Za-z0-9_]", "");
                    if (suffix.length() > 12) suffix = suffix.substring(0, 12);
                    handleGuildCommand(player, new String[]{"create", "Guild-" + suffix});
                    openPlayerGuildMenu(player);
                } else if (id == 2 && hasInvite) {
                    handleGuildCommand(player, new String[]{"accept"});
                    openPlayerGuildMenu(player);
                } else if (id == 3 && hasInvite) {
                    handleGuildCommand(player, new String[]{"decline"});
                    openPlayerGuildMenu(player);
                } else openPlayerMainMenu(player);
            } else {
                boolean leader = guildRole(guildId, player.getUniqueId().toString()).equals("leader");
                int cursor = 1;
                if (id == cursor++) openPlayerGuildMembersMenu(player);
                else {
                    if (guildCanManage(guildId, player)) {
                        if (id == cursor++) { openPlayerGuildInviteMenu(player); return; }
                    }
                    if (id == cursor++) {
                        handleGuildCommand(player, new String[]{"leave"});
                        openPlayerGuildMenu(player);
                    } else if (leader && id == cursor++) {
                        handleGuildCommand(player, new String[]{"disband"});
                        openPlayerGuildMenu(player);
                    } else openPlayerMainMenu(player);
                }
            }
        }));
        player.showFormWindow(form);
    }

    private void openPlayerGuildListMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§dГильдии сервера", "§7Выбери гильдию, чтобы посмотреть её состав.");
        List<String> ids = new ArrayList<>();
        for (String id : guildData.getSection("guilds").getAll().keySet()) {
            ids.add(id);
            String name = guildData.getString("guilds." + id + ".name", id);
            int count = guildData.getSection("guilds." + id + ".members").getAll().size();
            form.addButton(new ElementButton("§e" + name + "\n§7Участников: §f" + count));
            if (ids.size() >= 18) break;
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < ids.size()) openPlayerGuildPublicInfoMenu(player, ids.get(id));
            else openPlayerGuildMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerGuildPublicInfoMenu(Player player, String guildId) {
        if (!guildExists(guildId)) { openPlayerGuildListMenu(player); return; }
        String name = guildData.getString("guilds." + guildId + ".name", guildId);
        Map<String, Object> members = guildData.getSection("guilds." + guildId + ".members").getAll();
        StringBuilder description = new StringBuilder("§7Гильдия: §e").append(name)
                .append("\n§7Участников: §f").append(members.size()).append("\n§7Состав:");
        int shown = 0;
        for (String member : members.keySet()) {
            description.append("\n§f").append(guildData.getString(guildMemberPath(guildId, member) + ".name", member))
                    .append(" §8— §b").append(guildData.getString(guildMemberPath(guildId, member) + ".role", "member"));
            if (++shown >= 8) break;
        }
        FormWindowSimple form = new FormWindowSimple("§d" + name, description.toString());
        form.addButton(new ElementButton("§7Назад к списку"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (!form.wasClosed() && player.isOnline()) openPlayerGuildListMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerGuildMembersMenu(Player player) {
        String guildId = currentGuildId(player);
        if (guildId.isEmpty() || !guildExists(guildId)) { openPlayerGuildMenu(player); return; }
        Map<String, Object> members = guildData.getSection("guilds." + guildId + ".members").getAll();
        FormWindowSimple form = new FormWindowSimple("§dСостав гильдии",
                "§7Выбери участника. Управление ролями и исключение доступны главе.");
        List<String> memberIds = new ArrayList<>();
        for (String uuid : members.keySet()) {
            memberIds.add(uuid);
            String memberName = guildData.getString(guildMemberPath(guildId, uuid) + ".name", uuid);
            String role = guildData.getString(guildMemberPath(guildId, uuid) + ".role", "member");
            Player online = getServer().getPlayer(memberName);
            form.addButton(new ElementButton("§f" + memberName + "\n§b" + role + (online != null && online.isOnline() ? " §a● онлайн" : " §8● офлайн")));
            if (memberIds.size() >= 18) break;
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < memberIds.size()) openPlayerGuildMemberActions(player, memberIds.get(id));
            else openPlayerGuildMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerGuildMemberActions(Player player, String memberUuid) {
        String guildId = currentGuildId(player);
        if (guildId.isEmpty() || !guildExists(guildId)) { openPlayerGuildMenu(player); return; }
        String memberPath = guildMemberPath(guildId, memberUuid);
        String name = guildData.getString(memberPath + ".name", memberUuid);
        String role = guildData.getString(memberPath + ".role", "member");
        Player target = getServer().getPlayer(name);
        boolean leader = guildRole(guildId, player.getUniqueId().toString()).equals("leader");
        boolean self = memberUuid.equals(player.getUniqueId().toString());
        FormWindowSimple form = new FormWindowSimple("§dУчастник: " + name,
                "§7Роль: §b" + role + "\n§7Статус: " + (target != null && target.isOnline() ? "§aонлайн" : "§8офлайн"));
        if (leader && !self) {
            form.addButton(new ElementButton("§aНазначить офицером"));
            form.addButton(new ElementButton("§eСделать участником"));
            form.addButton(new ElementButton("§cИсключить из гильдии"));
            form.addButton(new ElementButton("§6Передать лидерство"));
        }
        form.addButton(new ElementButton("§7Назад к составу"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (leader && !self && id >= 0 && id <= 3) {
                if (target == null || !target.isOnline()) {
                    player.sendMessage("§cДля этого действия участник должен быть онлайн.");
                } else if (id == 0) handleGuildCommand(player, new String[]{"role", name, "officer"});
                else if (id == 1) handleGuildCommand(player, new String[]{"role", name, "member"});
                else if (id == 2) handleGuildCommand(player, new String[]{"kick", name});
                else if (id == 3) handleGuildCommand(player, new String[]{"transfer", name});
                openPlayerGuildMembersMenu(player);
            } else openPlayerGuildMembersMenu(player);
        }));
        player.showFormWindow(form);
    }


        String guildId = currentGuildId(player);
        FormWindowSimple form = new FormWindowSimple("§dГильдии", guildId.isEmpty()
                ? "§7Ты пока без гильдии. Создание стоит §e" + getConfig().getLong("guilds.creation-cost", 1000L) + " " + currencyName() + "."
                : "§7Текущая гильдия: §e" + guildData.getString("guilds." + guildId + ".name", guildId));
        form.addButton(new ElementButton("§eСписок гильдий"));
        form.addButton(new ElementButton("§aИнформация о моей гильдии"));
        if (guildId.isEmpty()) {
            form.addButton(new ElementButton("§aСоздать гильдию"));
            form.addButton(new ElementButton("§bПринять приглашение"));
        } else {
            form.addButton(new ElementButton("§bПригласить игрока"));
            form.addButton(new ElementButton("§cПокинуть гильдию"));
        }
        form.addButton(new ElementButton("§7Главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) handleGuildCommand(player, new String[]{"list"});
            else if (id == 1) handleGuildCommand(player, new String[]{"info"});
            else if (guildId.isEmpty() && id == 2) {
                String suffix = player.getName().replaceAll("[^A-Za-z0-9_]", "");
                if (suffix.length() > 12) suffix = suffix.substring(0, 12);
                handleGuildCommand(player, new String[]{"create", "Г-" + suffix});
                openPlayerGuildMenu(player);
            } else if (guildId.isEmpty() && id == 3) {
                handleGuildCommand(player, new String[]{"accept"});
                openPlayerGuildMenu(player);
            } else if (!guildId.isEmpty() && id == 2) openPlayerGuildInviteMenu(player);
            else if (!guildId.isEmpty() && id == 3) {
                handleGuildCommand(player, new String[]{"leave"});
                openPlayerGuildMenu(player);
            } else openPlayerMainMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerGuildInviteMenu(Player player) {
        String guildId = currentGuildId(player);
        if (guildId.isEmpty() || !guildCanManage(guildId, player)) {
            player.sendMessage("§cПриглашать могут глава и офицеры гильдии.");
            return;
        }
        FormWindowSimple form = new FormWindowSimple("§dПригласить в гильдию", "§7Выбери игрока онлайн.");
        List<Player> targets = new ArrayList<>();
        for (Player target : getServer().getOnlinePlayers().values()) {
            if (target.isOnline() && !target.getUniqueId().equals(player.getUniqueId())
                    && currentGuildId(target).isEmpty()) {
                targets.add(target);
                form.addButton(new ElementButton("§f" + target.getName()));
                if (targets.size() >= 20) break;
            }
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id < targets.size() && targets.get(id).isOnline()) {
                handleGuildCommand(player, new String[]{"invite", targets.get(id).getName()});
            }
            openPlayerGuildMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerRiftMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§5§lРАЗЛОМ БЕЗДНЫ",
                "§7Отдельное испытание, награды и реликвии Разлома.");
        form.addButton(new ElementButton("§aИнформация"));
        form.addButton(new ElementButton("§cНачать испытание"));
        form.addButton(new ElementButton("§eЗабрать награду"));
        form.addButton(new ElementButton("§bКузница реликвий"));
        form.addButton(new ElementButton("§dКоллекция Разлома"));
        form.addButton(new ElementButton("§7В главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) handleRiftCommand(player, new String[]{"info"});
            else if (id == 1) handleRiftCommand(player, new String[]{"start"});
            else if (id == 2) handleRiftCommand(player, new String[]{"claim"});
            else if (id == 3) openPlayerRiftForgeMenu(player);
            else if (id == 4) handleRiftCommand(player, new String[]{"collection"});
            else openPlayerMainMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerRiftForgeMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§5Кузница Разлома", "§7Создание постоянных реликвий за осколки.");
        form.addButton(new ElementButton("§5Клинок Разлома §8(8 осколков)"));
        form.addButton(new ElementButton("§5Броня Бездны §8(8 осколков)"));
        form.addButton(new ElementButton("§dСердце Разлома §8(5 осколков)"));
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id >= 0 && id <= 2) handleRiftCommand(player, new String[]{"forge", new String[]{"weapon","armor","amulet"}[id]});
            else openPlayerRiftMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerForgeMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§6Кузница", "§7Улучшай снаряжение и смотри доступные рецепты.");
        form.addButton(new ElementButton("§eИнформация о кузнице"));
        form.addButton(new ElementButton("§aУлучшить оружие"));
        form.addButton(new ElementButton("§bУлучшить броню"));
        form.addButton(new ElementButton("§dКоллекция кузницы"));
        form.addButton(new ElementButton("§7Главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) handleForge(player, new String[]{"info"});
            else if (id == 1) handleForge(player, new String[]{"weapon"});
            else if (id == 2) handleForge(player, new String[]{"armor"});
            else if (id == 3) handleForge(player, new String[]{"collection"});
            else openPlayerMainMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openPlayerQuestMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("§aКвесты и достижения", "§7Выбери приключение.");
        form.addButton(new ElementButton("§eЕжедневное задание"));
        form.addButton(new ElementButton("§6Еженедельное испытание"));
        form.addButton(new ElementButton("§bЦепочка заданий"));
        form.addButton(new ElementButton("§dДостижения"));
        form.addButton(new ElementButton("§7Главное меню"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) handleDaily(player, new String[0]);
            else if (id == 1) handleWeekly(player, new String[0]);
            else if (id == 2) handleQuestChain(player, new String[0]);
            else if (id == 3) openAchievementsMenu(player);
            else openPlayerMainMenu(player);
        }));
        player.showFormWindow(form);
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
        form.addButton(new ElementButton("§dRPG: выдача, питомцы и сбросы"));
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
            else if (id == 12) openOperatorRpgPlayersMenu(operator);
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
        if (args.length > 0 && args[0].equalsIgnoreCase("dungeon")) {
            if (args.length != 3 || !args[1].equalsIgnoreCase("reset")) {
                sender.sendMessage("§eИспользуй: /lfadmin dungeon reset <игрок>");
                return true;
            }
            Player target = getServer().getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage("§cИгрок должен быть онлайн для сброса.");
                return true;
            }
            initialize(target);
            String targetKey = playerKey(target);
            if (players.getBoolean(targetKey + ".dungeon.active", false)) {
                sender.sendMessage("§cСначала игрок должен выйти из активного подземелья: /dungeon leave.");
                return true;
            }
            players.set(targetKey + ".dungeon.date", today());
            players.set(targetKey + ".dungeon.kills", 0);
            players.set(targetKey + ".dungeon.active", false);
            players.set(targetKey + ".dungeon.complete", false);
            players.set(targetKey + ".dungeon.claimed", false);
            players.set(targetKey + ".dungeon.chest-ready", false);
            players.set(targetKey + ".dungeon.loot-fallback-given", false);
            players.set(targetKey + ".dungeon.keeper-trophy-given", false);
            players.set(targetKey + ".dungeon.wave", 1);
            players.set(targetKey + ".dungeon.spawned-wave", 0);
            players.set(targetKey + ".dungeon.started-at", 0L);
            markPlayersDirty();
            if (!flushPlayers()) {
                sender.sendMessage("§cНе удалось сохранить сброс. Проверь players.yml.");
                return true;
            }
            staffLog(sender.getName(), "dungeon-reset", target.getName(), "daily entry and rewards");
            sender.sendMessage("§aДневной доступ в подземелье сброшен для §e" + target.getName() + "§a.");
            target.sendMessage("§6§lПОДЗЕМЕЛЬЕ §7— оператор сбросил твой дневной таймер. Можешь начать новый забег: §e/dungeon start");
            return true;
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

        if (name.equals("menu") || name.equals("lfmenu")) { if (!(sender instanceof Player)) { sender.sendMessage("Меню доступно только игроку."); return true; } openPlayerMainMenu((Player)sender); return true; }

        if (name.equals("world") || name.equals("worlds")) { handleWorldCommand(sender, args); return true; }

        if (name.equals("rift")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Разлом доступен только игроку."); return true; }
            handleRiftCommand((Player) sender, args);
            return true;
        }

        if (name.equals("gear")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Коллекция доступна только игроку."); return true; }
            showGearCollection((Player) sender);
            return true;
        }

        if (name.equals("dungeon") || name.equals("boss")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Команда доступна только игроку."); return true; }
            Player player=(Player)sender;
            if(args.length==0 || args[0].equalsIgnoreCase("menu")) openAdventureMenu(player);
            else if(name.equals("dungeon")) handleDungeon(player,args);
            else handleBossHunt(player,args);
            return true;
        }

        if (name.equals("pets") || name.equals("pet")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Питомцы доступны только игроку."); return true; }
            Player player = (Player) sender;
            if (args.length > 0 && (args[0].equalsIgnoreCase("ability") || args[0].equalsIgnoreCase("use"))) {
                activatePetAbility(player);
            } else if (args.length > 0 && args[0].equalsIgnoreCase("gear")) {
                handlePetGear(player, args);
            } else {
                openPetsMenu(player);
            }
            return true;
        }

        if (name.equals("forge")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Кузница доступна только игроку."); return true; }
            handleForge((Player) sender, args);
            return true;
        }

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