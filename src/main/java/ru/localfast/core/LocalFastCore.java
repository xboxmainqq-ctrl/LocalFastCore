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

public final class LocalFastCore extends PluginBase implements Listener {
    private static final int MAX_LEVEL = 100;
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
    private boolean playersDirty = false;
    private final Map<String, IScoreboard> sidebars = new HashMap<>();

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
        getServer().getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
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
        getLogger().info("LocalFastCore 0.10.0 включён. RPG, GUI, задания, spawn и RTP готовы.");
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
        if (world == null) {
            player.sendMessage("§cМир спавна '" + worldName + "' не найден. Создай/загрузи его в папке worlds.");
            return;
        }
        if (getConfig().exists("spawn-x") && getConfig().exists("spawn-y") && getConfig().exists("spawn-z")) {
            Position target = new Position(getConfig().getDouble("spawn-x"), getConfig().getDouble("spawn-y"),
                    getConfig().getDouble("spawn-z"), world);
            player.teleport(target);
        } else {
            player.teleport(world.getSafeSpawn());
            player.sendMessage("§eТочка спавна ещё не задана. Встань в нужное место и введи /setspawn.");
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            double radius = Math.sqrt(random.nextDouble(minRadius * (double) minRadius,
                    maxRadius * (double) maxRadius));
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            try {
                int groundY = world.getHighestBlockAt(x, z);
                if (groundY <= world.getMinBlockY() || groundY + 2 > world.getMaxBlockY()) continue;
                Block ground = world.getBlock(x, groundY, z);
                Block feet = world.getBlock(x, groundY + 1, z);
                Block head = world.getBlock(x, groundY + 2, z);
                if (!ground.isSolid() || feet.isSolid() || head.isSolid()
                        || !feet.canPassThrough() || !head.canPassThrough()) continue;
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
    }

    private boolean getBoolean(Player player, String field, boolean fallback) {
        return players.getBoolean(playerKey(player) + "." + field, fallback);
    }

    private void setBoolean(Player player, String field, boolean value) {
        players.set(playerKey(player) + "." + field, value);
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
    public void onQuit(PlayerQuitEvent event) {
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
        Player p=e.getPlayer(); initialize(p); refreshDaily(p); String k=playerKey(p); String d=today();
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
                p.sendMessage("§cНе удалось сохранить награду. XP не начислен; попробуй позже или обратись к администратору.");
                return;
            }
            int reward = Math.max(0, getConfig().getInt("daily.xp-reward", DAILY_XP_REWARD));
            giveXp(p,reward);p.sendMessage("§aПолучено +" + reward + " XP!");return;
        }
        p.sendMessage("§e/daily, /daily start или /daily claim");
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
                    player.sendMessage("§cНе удалось сохранить получение награды. XP не начислен; попробуй позже или обратись к администратору.");
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
            setBoolean(player, "quest_active", false);
            setBoolean(player, "quest_complete", false);
            set(player, "quest_kills", 0);
            players.set(playerKey(player) + ".quest_type", "any");
            markPlayersDirty();
            if (!flushPlayers()) {
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

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

        if (name.equals("lfadmin")) {
            if (!sender.hasPermission("localfast.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("§e/lfadmin xp <player> <amount>");
                sender.sendMessage("§e/lfadmin reset <player> <level|stats|quests|all>");
                return true;
            }

            if (args[0].equalsIgnoreCase("reset")) {
                if (args.length != 3) {
                    sender.sendMessage("§e/lfadmin reset <player> <level|stats|quests|all>");
                    return true;
                }
                Player target = getServer().getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cИгрок не найден или не в сети.");
                    return true;
                }
                String mode = args[2].toLowerCase(Locale.ROOT);
                if (!Arrays.asList("level", "stats", "quests", "all").contains(mode)) {
                    sender.sendMessage("§cВыбери: level, stats, quests или all.");
                    return true;
                }

                String key = playerKey(target);
                if (mode.equals("level") || mode.equals("all")) {
                    set(target, "level", 1);
                    set(target, "xp", 0);
                    set(target, "points", 0);
                }
                if (mode.equals("stats") || mode.equals("all")) {
                    for (String stat : STATS) set(target, stat, 0);
                }
                if (mode.equals("quests") || mode.equals("all")) {
                    set(target, "quest_kills", 0);
                    setBoolean(target, "quest_active", false);
                    setBoolean(target, "quest_complete", false);
                    players.set(key + ".quest_type", "any");
                    set(target, "daily_kills", 0);
                    setBoolean(target, "daily_active", false);
                    setBoolean(target, "daily_complete", false);
                    setBoolean(target, "daily_claimed", false);
                    for (QuestDefinition quest : questCatalog()) {
                        String questPath = key + ".quests." + quest.id;
                        players.set(questPath + ".kills", 0);
                        players.set(questPath + ".active", false);
                        players.set(questPath + ".complete", false);
                        players.set(questPath + ".claimed", false);
                    }
                }
                markPlayersDirty();
                if (!flushPlayers()) {
                    sender.sendMessage("§cСброс применён в памяти, но players.yml не удалось сохранить; повторная попытка будет выполнена автоматически.");
                }
                recalculateStats(target);
                updateSidebar(target);
                String description = mode.equals("level") ? "уровень, XP и свободные очки"
                        : mode.equals("stats") ? "характеристики"
                        : mode.equals("quests") ? "прогресс квестов и ежедневное задание"
                        : "весь прогресс";
                target.sendMessage("§e[LocalFast] Администратор сбросил: " + description + ".");
                sender.sendMessage("§aДля игрока " + target.getName() + " сброшено: " + description + ".");
                return true;
            }

            if (args.length != 3 || !args[0].equalsIgnoreCase("xp")) {
                sender.sendMessage("§e/lfadmin xp <player> <amount>");
                sender.sendMessage("§e/lfadmin reset <player> <level|stats|quests|all>");
                return true;
            }
            Player target = getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cИгрок не найден или не в сети.");
                return true;
            }
            try {
                int amount = Integer.parseInt(args[2]);
                if (amount < 1 || amount > MAX_XP_GRANT) throw new NumberFormatException();
                giveXp(target, amount);
                target.sendMessage("§a[LocalFast] Начислено опыта: " + amount);
                sender.sendMessage("§aИгроку " + target.getName() + " начислено " + amount + " опыта.");
            } catch (NumberFormatException exception) {
                sender.sendMessage("§cУкажи целое число от 1 до " + MAX_XP_GRANT + ".");
            }
            return true;
        }
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
