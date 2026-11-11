package ru.localfast.core;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.level.Level;
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

    private Config players;
    private Config staffData;
    private File staffLogFile;
    private boolean playersDirty = false;
    private final Map<String, IScoreboard> sidebars = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
    private final java.util.Set<UUID> vanishedPlayers = new java.util.HashSet<>();
    private final java.util.Set<UUID> flyingPlayers = new java.util.HashSet<>();

    private static final class PendingTeleport {
        final Player requester;
        final long expiresAt;
        PendingTeleport(Player requester, long expiresAt) {
            this.requester = requester;
            this.expiresAt = expiresAt;
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

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().error("Не удалось создать папку данных LocalFastCore.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        players = new Config(new File(getDataFolder(), "players.yml"), Config.YAML);
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
        getLogger().info("LocalFastCore 0.12.0 включён. RPG, экономика и система персонала загружены.");
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
            scoreboard = new Scoreboard(objective, getConfig().getString("scoreboard.title", "§6§lLOCALFAST"), "dummy", SortOrder.DESCENDING);
            sidebars.put(key, scoreboard);
        }

        int level = get(player, "level", 1);
        int xp = get(player, "xp", 0);
        int nextXp = xpForNextLevel(level);
        scoreboard.setLines(Arrays.asList(
                "§7" + player.getName(),
                "§fУровень: §e" + level + "§7/§e" + MAX_LEVEL,
                "§fОпыт: §a" + xp + "§7/§a" + nextXp,
                "§fОчки: §b" + get(player, "points", 0),
                "§8§m----------------",
                "§cСила: §f" + get(player, "strength", 0),
                "§cЗдоровье: §f" + get(player, "health", 0),
                "§aЛовкость: §f" + get(player, "agility", 0),
                "§bИнтеллект: §f" + get(player, "intelligence", 0),
                getConfig().getString("scoreboard.footer", "§8play.localfast")
        ));
        if (!scoreboard.containViewer(player, DisplaySlot.SIDEBAR)) {
            scoreboard.addViewer(player, DisplaySlot.SIDEBAR);
        }
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String roleTag = staffChatTag(staffRole(player));
        event.setFormat("%s" + roleTag + " §7: §f%s");
        String path = "punishments.mutes." + player.getUniqueId();
        long expires = staffData.getLong(path + ".expires", 0L);
        if (expires == -1L || expires > System.currentTimeMillis()) { event.setCancelled(true); player.sendMessage("§cЧат закрыт: у тебя активен мут."); }
        else if (expires != 0L) { staffData.remove(path); staffData.save(); }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        UUID quittingId = quittingPlayer.getUniqueId();
        pendingTeleports.remove(quittingId);
        pendingTeleports.entrySet().removeIf(entry -> entry.getValue().requester.getUniqueId().equals(quittingId));
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
        int healthBonus = attributeBonus(player, "health",
                "attributes.health.points-per-bonus", 5,
                "attributes.health.hp-per-bonus", 1,
                "attributes.health.max-bonus", 20, 40);
        int maximumHealth = 20 + healthBonus;
        player.setMaxHealth(maximumHealth);
        // Increasing max HP does not heal; current HP is preserved in absolute points.
        if (player.getHealth() > maximumHealth) player.setHealth(maximumHealth);

        int agilityPercent = attributeBonus(player, "agility",
                "attributes.agility.points-per-bonus", 5,
                "attributes.agility.speed-percent-per-bonus", 1,
                "attributes.agility.max-bonus-percent", 25, 50);
        float movementSpeed = Player.DEFAULT_SPEED * (1.0f + agilityPercent / 100.0f);
        player.setMovementSpeed(Math.min(Player.MAXIMUM_SPEED, movementSpeed));
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
        if (!(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        int bonusDamage = attributeBonus(attacker, "strength",
                "attributes.strength.points-per-bonus", 5,
                "attributes.strength.damage-per-bonus", 1,
                "attributes.strength.max-bonus", 10, 20);
        if (bonusDamage <= 0) return;
        event.setDamage(event.getDamage(EntityDamageEvent.DamageModifier.BASE) + bonusDamage,
                EntityDamageEvent.DamageModifier.BASE);
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


    private String today() { return LocalDate.now().toString(); }
    private void refreshDaily(Player p) {
        initialize(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".daily_date","").equals(d)) {
            players.set(k+".daily_date",d); players.set(k+".daily_kills",0);
            players.set(k+".daily_active",false); players.set(k+".daily_complete",false);
            players.set(k+".daily_claimed",false); markPlayersDirty();
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer();
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
        String welcome = getConfig().getString("welcome.message", "");
        boolean personalWelcome = staffData.getBoolean("welcome.enabled." + p.getUniqueId(), true);
        if (staffRank(staffRole(p)) >= 7 && personalWelcome && welcome != null && !welcome.trim().isEmpty()) {
            p.sendMessage(welcome.replace("{player}", p.getName()).replace("&", "§"));
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
        refreshDaily(p); String k=playerKey(p);
        int required = Math.max(1, getConfig().getInt("daily.kills-required", DAILY_KILLS_REQUIRED));
        int reward = Math.max(0, getConfig().getInt("daily.xp-reward", DAILY_XP_REWARD));
        p.sendMessage("§6[Ежедневное задание] §fУбей " + required + " любых существ.");
        if(players.getBoolean(k+".daily_complete",false)) p.sendMessage("§aВыполнено! Награда " + reward + " XP: /daily claim");
        else if(players.getBoolean(k+".daily_claimed",false)) p.sendMessage("§7Сегодняшняя награда уже получена. Возвращайся завтра.");
        else if(players.getBoolean(k+".daily_active",false)) p.sendMessage("§eПрогресс: "+players.getInt(k+".daily_kills",0)+"/"+required);
        else p.sendMessage("§eНаграда " + reward + " XP. Начать: /daily start");
    }
    private void handleDaily(Player p,String[] args) {
        refreshDaily(p); String k=playerKey(p);
        if(args.length==0){showDaily(p);return;}
        String a=args[0].toLowerCase(Locale.ROOT);
        if(a.equals("start")){
            if(players.getBoolean(k+".daily_active",false)||players.getBoolean(k+".daily_complete",false)||players.getBoolean(k+".daily_claimed",false)){showDaily(p);return;}
            players.set(k+".daily_active",true);players.set(k+".daily_kills",0);markPlayersDirty();
            int required = Math.max(1, getConfig().getInt("daily.kills-required", DAILY_KILLS_REQUIRED));
            p.sendMessage("§aНачато! Убей " + required + " любых существ.");return;
        }
        if(a.equals("claim")){
            if(players.getBoolean(k+".daily_claimed",false) || !players.getBoolean(k+".daily_complete",false)){
                p.sendMessage("§cЗадание ещё не выполнено или награда уже получена.");return;
            }
            // Persist the claimed state through the centralized save queue before granting XP.
            players.set(k+".daily_complete",false);
            players.set(k+".daily_active",false);
            players.set(k+".daily_claimed",true);
            markPlayersDirty();
            if (!flushPlayers()) {
                players.set(k + ".daily_complete", true);
                players.set(k + ".daily_active", true);
                players.set(k + ".daily_claimed", false);
                markPlayersDirty();
                p.sendMessage("§cНе удалось сохранить награду. XP не начислен; попробуй позже.");
                return;
            }
            int reward = Math.max(0, getConfig().getInt("daily.xp-reward", DAILY_XP_REWARD));
            giveXp(p,reward);p.sendMessage("§aПолучено +" + reward + " XP!");return;
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
        return 100 + (level - 1) * 50;
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
        int xpBonusPercent = intelligenceXpBonusPercent(killer);
        killer.sendMessage("§a[LocalFast] +" + mobXp + " базового XP за победу над существом"
                + (xpBonusPercent > 0 ? " §b(интеллект: +" + xpBonusPercent + "%)" : "") + "§a!");


        refreshDaily(killer); String dk=playerKey(killer);
        if(players.getBoolean(dk+".daily_active",false)&&!players.getBoolean(dk+".daily_complete",false)){
            int required = Math.max(1, getConfig().getInt("daily.kills-required", DAILY_KILLS_REQUIRED));
            int n=Math.min(required,Math.max(0,players.getInt(dk+".daily_kills",0))+1);players.set(dk+".daily_kills",n);
            if(n>=required){players.set(dk+".daily_complete",true);killer.sendMessage("§6Ежедневное задание выполнено! /daily claim");}
            else killer.sendMessage("§eЕжедневное задание: "+n+"/"+required);
            markPlayersDirty();
        }
        String mobName = mob.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        updateLevelQuests(killer, mobName);
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
        FormWindowSimple form = new FormWindowSimple("§6Квест §8| §e" + quest.title,
                "§f" + quest.description + "\n§fПрогресс: §a" + progress + "/" + questKills(quest)
                        + "\n§fНаграда: §6" + reward + " XP"
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
                player.sendMessage("§a[Квесты] Получено +" + currentReward + " XP за " + quest.title + "!");
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

    private void openStatsMenu(Player player) {
        initialize(player);
        FormWindowSimple form = new FormWindowSimple("§6LocalFastCore §8| §lХарактеристики",
                "§fУровень: §e" + get(player, "level", 1)
                        + "\n§fСвободные очки: §b" + get(player, "points", 0)
                        + "\n\n§7Выбери характеристику для распределения очков.");
        form.addButton(new ElementButton("§cСила §8| §f" + get(player, "strength", 0)));
        form.addButton(new ElementButton("§cЗдоровье §8| §f" + get(player, "health", 0)));
        form.addButton(new ElementButton("§aЛовкость §8| §f" + get(player, "agility", 0)));
        form.addButton(new ElementButton("§bИнтеллект §8| §f" + get(player, "intelligence", 0)));
        form.addButton(new ElementButton("§7Обновить"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            if (!player.isOnline()) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < STATS.length) openStatAllocationMenu(player, STATS[selected]);
            else if (selected == 4) openStatsMenu(player);
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

    private String staffChatTag(String role) {
        switch (role) {
            case "ml_helper": return " §bМл. хелпер";
            case "helper": return " §aХелпер";
            case "senior_helper": return " §6Ст. хелпер";
            case "junior_moderator": return " §dМл. модер";
            case "moderator": return " §5Модер";
            case "senior_moderator": return " §9Ст. модер";
            case "admin": return " §aAdmin";
            case "operator": return " §eОператор";
            default: return "";
        }
    }

    private void refreshStaffNameTag(Player player) {
        String role = staffRole(player);
        String name = "operator".equals(role) ? "§e" + player.getName() : "§f" + player.getName();
        player.setNameTag(name + staffChatTag(role));
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
        staffData.set(path + ".reason", reason); staffData.save();
        staffLog(actor.getName(), "ban", target.getName(), minutes + " мин.; " + reason);
        target.kick("§cБан: " + reason, false); actor.sendMessage("§aБан выдан игроку " + target.getName() + ".");
    }

    private void applyMute(Player actor, Player target, long minutes, String reason) {
        String path = "punishments.mutes." + target.getUniqueId();
        staffData.set(path + ".expires", System.currentTimeMillis() + minutes * 60000L);
        staffData.set(path + ".reason", reason); staffData.save();
        staffLog(actor.getName(), "mute", target.getName(), minutes + " мин.; " + reason);
        target.sendMessage("§cТебе выдан мут на " + minutes + " мин. Причина: " + reason);
        actor.sendMessage("§aМут выдан игроку " + target.getName() + ".");
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
        form.addButton(new ElementButton("§bСтатистика игрока"));
        form.addButton(new ElementButton("§dАдминистрация онлайн"));
        form.addButton(new ElementButton("§eДолжности и мои команды"));
        if (staffRank(role) >= 5) form.addButton(new ElementButton("§6Warp"));
        if (staffRank(role) >= 7) {
            form.addButton(new ElementButton("§aFly"));
            form.addButton(new ElementButton("§cVanish"));
        }
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !staff.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id < 0) return;
            if (id < utilityStart) { if (id < actions.size()) openStaffTargetMenu(staff, actions.get(id)); return; }
            int utility = id - utilityStart;
            if (utility == 0) openStaffStatsTargetMenu(staff);
            else if (utility == 1) showOnlineStaff(staff);
            else if (utility == 2) showStaffHelp(staff);
            else {
                int cursor = 3;
                if (staffRank(role) >= 5) {
                    if (utility == cursor) { openWarpMenu(staff); return; }
                    cursor++;
                }
                if (staffRank(role) >= 7) {
                    if (utility == cursor) { toggleFly(staff); return; }
                    if (utility == cursor + 1) { toggleVanish(staff); return; }
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
        if (role.equals("admin") || role.equals("operator")) viewer.sendMessage("§e/staff promote|demote|setrank ...");
        if (role.equals("operator")) {
            viewer.sendMessage("§6§lКоманды оператора:");
            viewer.sendMessage("§e/lfadmin staff add|remove|list ...");
            viewer.sendMessage("§e/lfadmin money give|take|set|reset ...");
            viewer.sendMessage("§e/lfadmin xp <игрок> <количество>");
            viewer.sendMessage("§e/lfadmin reset <игрок> <level|stats|quests|all>");
            viewer.sendMessage("§e/lfadmin warp set|delete|list ...");
            viewer.sendMessage("§e/staff welcome <on|off|status> §7— личное приветствие при входе");
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

    private void openOperatorMenu(Player operator) {
        if (!isOperator(operator)) { operator.sendMessage("§cТолько оператор может открыть эту панель."); return; }
        FormWindowSimple form = new FormWindowSimple("§6LocalFast §8| §lОператорская панель",
                "§fОператор: §e" + operator.getName() + "\n§7Управление сервером и персоналом.");
        form.addButton(new ElementButton("§bАдминистрация онлайн"));
        form.addButton(new ElementButton("§aНазначить администрацию"));
        form.addButton(new ElementButton("§eДолжности и команды"));
        form.addButton(new ElementButton("§aСтатистика игрока"));
        form.addButton(new ElementButton("§6Сохранить варп здесь (operator)"));
        form.addButton(new ElementButton("§dМоё приветствие: вкл/выкл"));
        form.addButton(new ElementButton("§cFly"));
        form.addButton(new ElementButton("§cVanish"));
        form.addButton(new ElementButton("§6Открыть панель /staff"));
        form.addButton(new ElementButton("§dТестировать должность на себе"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !operator.isOnline()) return;
            int id = form.getResponse().getClickedButtonId();
            if (id == 0) showOnlineStaff(operator);
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
            else if (id == 9) openOperatorSelfRankMenu(operator);
        }));
        operator.showFormWindow(form);
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
        if (staffRank(staffRole(target)) >= staffRank(role)) { staff.sendMessage("§cНельзя воздействовать на сотрудника равного или более высокого ранга."); return; }
        if (action.equals("ban")) applyBan(staff, target, Math.max(1L, maxBanMinutes(role) == -1L ? 1440L : maxBanMinutes(role)), "Нарушение правил");
        else if (action.equals("mute")) applyMute(staff, target, Math.max(1L, maxMuteMinutes(role) == -1L ? 30L : maxMuteMinutes(role)), "Нарушение правил");
        else if (action.equals("kick")) {
            target.kick("§cКик: нарушение правил", false);
            staffLog(staff.getName(), "kick", target.getName(), "Нарушение правил");
            staff.sendMessage("§aИгрок кикнут.");
        }
    }

    private boolean handleStaffCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Команда доступна игроку."); return true; }
        Player actor = (Player) sender;
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
        String role = staffRole(actor);
        if (staffRank(role) == 0) { sender.sendMessage("§cНет доступа к /staff. Команда /staff welcome доступна всем."); return true; }
        if (args.length == 0) { openStaffMenu(actor); return true; }
        if (args[0].equalsIgnoreCase("fly")) { toggleFly(actor); return true; }
        if (args[0].equalsIgnoreCase("vanish")) { toggleVanish(actor); return true; }
        if (args[0].equalsIgnoreCase("warp")) { if (staffRank(role) < 5) sender.sendMessage("§cWarp доступен с ранга модератора."); else openWarpMenu(actor); return true; }
        if (args[0].equalsIgnoreCase("online")) { showOnlineStaff(actor); return true; }
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
            if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            String oldRole = staffRole(target);
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
            if (newRank == 0) staffData.remove("staff." + target.getUniqueId());
            else staffData.set("staff." + target.getUniqueId(), STAFF_ROLES[newRank - 1]);
            staffData.save();
            refreshStaffNameTag(target);
            refreshStaffModes(target);
            String newRole = newRank == 0 ? "" : STAFF_ROLES[newRank - 1];
            staffLog(actor.getName(), action, target.getName(), staffRoleLabel(oldRole) + " -> " + staffRoleLabel(newRole));
            sender.sendMessage("§aДолжность " + target.getName() + ": " + staffRoleLabel(oldRole) + " -> " + staffRoleLabel(newRole) + ".");
            target.sendMessage("§eТвоя должность изменена: §a" + staffRoleLabel(newRole) + "§e.");
            return true;
        }
        if (action.equals("warn")) {
            if (args.length < 3) { sender.sendMessage("§e/staff warn <игрок> <причина>"); return true; }
            Player target = getServer().getPlayer(args[1]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            if (staffRank(staffRole(target)) >= staffRank(role)) { sender.sendMessage("§cНельзя наказать сотрудника равного или более высокого ранга."); return true; }
            StringBuilder reason = new StringBuilder(); for (int i=2;i<args.length;i++){if(reason.length()>0)reason.append(' ');reason.append(args[i]);}
            String path = "warnings." + target.getUniqueId(); int count = staffData.getInt(path,0)+1; staffData.set(path,count); staffData.save();
            staffLog(actor.getName(),"warn",target.getName(),reason.toString()); target.sendMessage("§eПредупреждение №"+count+": "+reason); sender.sendMessage("§aПредупреждение выдано. Всего: "+count); return true;
        }
        if (action.equals("ban") || action.equals("mute") || action.equals("kick")) {
            if (action.equals("ban") && !canBan(role)) { sender.sendMessage("§cТвой ранг не может выдавать баны."); return true; }
            if (action.equals("mute") && maxMuteMinutes(role) == 0L) { sender.sendMessage("§cТвой ранг не может выдавать муты."); return true; }
            if (action.equals("kick") && !canKick(role)) { sender.sendMessage("§cТвой ранг не может кикать игроков."); return true; }
            int min = action.equals("kick") ? 3 : 4;
            if (args.length < min) { sender.sendMessage(action.equals("kick") ? "§e/staff kick <игрок> <причина>" : "§e/staff " + action + " <игрок> <минуты> <причина>"); return true; }
            Player target = getServer().getPlayer(args[1]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            if (staffRank(staffRole(target)) >= staffRank(role)) { sender.sendMessage("§cНельзя наказать сотрудника равного или более высокого ранга."); return true; }
            long minutes = 0L; int ri = 2;
            if (!action.equals("kick")) {
                try { minutes = Long.parseLong(args[2]); } catch (NumberFormatException ex) { sender.sendMessage("§cСрок указывается в минутах."); return true; }
                long max = action.equals("ban") ? maxBanMinutes(role) : maxMuteMinutes(role);
                boolean permanentBan = action.equals("ban") && minutes == -1L && max == -1L;
                if ((!permanentBan && minutes < 1L) || minutes > 525600L || (max != -1L && minutes > max)) {
                    sender.sendMessage("§cМаксимальный срок для твоего ранга: " + (max == -1L ? "без ограничения (бан навсегда: -1)" : max + " мин.") + "."); return true;
                }
                ri = 3;
            }
            StringBuilder reason = new StringBuilder(); for (int i=ri;i<args.length;i++){if(reason.length()>0)reason.append(' ');reason.append(args[i]);}
            if (reason.length()==0) { sender.sendMessage("§cУкажи причину."); return true; }
            if (action.equals("ban")) applyBan(actor,target,minutes,reason.toString());
            else if (action.equals("mute")) applyMute(actor,target,minutes,reason.toString());
            else { target.kick("§cКик: "+reason,false); staffLog(actor.getName(),"kick",target.getName(),reason.toString()); sender.sendMessage("§aИгрок кикнут."); }
            return true;
        }
        if (action.equals("unban") || action.equals("unmute")) {
            if (action.equals("unban") && !canUnban(role)) { sender.sendMessage("§cТвой ранг не может снимать баны."); return true; }
            if (action.equals("unmute") && !canUnmute(role)) { sender.sendMessage("§cТвой ранг не может снимать муты."); return true; }
            if (args.length != 2) { sender.sendMessage("§e/staff " + action + " <игрок>"); return true; }
            Player target = getServer().getPlayer(args[1]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            staffData.remove("punishments." + (action.equals("unban") ? "bans." : "mutes.") + target.getUniqueId());
            staffData.save(); staffLog(actor.getName(),action,target.getName(),"command"); sender.sendMessage("§aНаказание снято."); return true;
        }
        sender.sendMessage("§e/staff — меню; /staff warn|ban|mute|kick|unban|unmute ..."); return true;
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
        if (args.length == 0) {
            if (sender instanceof Player) openOperatorMenu((Player) sender);
            else sender.sendMessage("§6/lfadmin staff add|remove|list; money give|take|set|reset; xp; reset");
            return true;
        }
        if (args[0].equalsIgnoreCase("staff")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("§6§lПерсонал LocalFast:");
                for (String id : staffData.getSection("staff").getKeys(false)) {
                    String role = staffData.getString("staff." + id, "");
                    Player online = getServer().getPlayer(UUID.fromString(id)).orElse(null);
                    String nickname = online == null ? id : online.getName();
                    sender.sendMessage("§f" + nickname + " §8— " + staffRoleColor(role) + staffRoleLabel(role));
                }
                return true;
            }
            if (args.length < 3) { sender.sendMessage("§e/lfadmin staff add <игрок> <ml_helper|helper|senior_helper|junior_moderator|moderator|senior_moderator|admin> или remove <игрок>"); return true; }
            Player target = getServer().getPlayer(args[2]); if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            String path = "staff." + target.getUniqueId();
            if (args[1].equalsIgnoreCase("remove")) staffData.remove(path);
            else if (args[1].equalsIgnoreCase("add") && args.length == 4 && Arrays.asList(STAFF_ROLES).contains(args[3].toLowerCase(Locale.ROOT))) staffData.set(path, args[3].toLowerCase(Locale.ROOT));
            else { sender.sendMessage("§eУкажи add|remove и роль: ml_helper|helper|senior_helper|junior_moderator|moderator|senior_moderator|admin."); return true; }
            staffData.save(); refreshStaffNameTag(target); refreshStaffModes(target); staffLog(sender.getName(), "staff-" + args[1], target.getName(), args.length == 4 ? args[3] : ""); sender.sendMessage("§aРоль обновлена: §f" + target.getName() + " §7— " + staffRoleColor(staffRole(target)) + staffRoleLabel(staffRole(target))); return true;
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

        if (name.equals("staff") && args.length > 0 && args[0].equalsIgnoreCase("welcome")) {
            return handleStaffCommand(sender, args);
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

        if (name.equals("level") || name.equals("stats") || name.equals("addstat")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Эта команда доступна только игроку.");
                return true;
            }
            Player player = (Player) sender;
            initialize(player);

            if (name.equals("level")) {
                int level = get(player, "level", 1);
                sender.sendMessage("§6[LocalFast] §fУровень: §e" + level + "/" + MAX_LEVEL);
                sender.sendMessage("§fОпыт: §a" + get(player, "xp", 0) + "§7/§a" + xpForNextLevel(level)
                        + " §f| Очки: §b" + get(player, "points", 0));
                return true;
            }

            if (name.equals("stats")) {
                sender.sendMessage("§6[LocalFast] §eХарактеристики");
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
