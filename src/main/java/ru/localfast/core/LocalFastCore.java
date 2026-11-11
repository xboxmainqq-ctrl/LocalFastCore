package ru.localfast.core;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDate;

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
    private static final String SPAWN_WORLD_DEFAULT = "spawn";

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().error("Не удалось создать папку данных LocalFastCore.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        players = new Config(new File(getDataFolder(), "players.yml"), Config.YAML);
        if (!getConfig().exists("spawn-world")) getConfig().set("spawn-world", SPAWN_WORLD_DEFAULT);
        if (!getConfig().exists("main-world")) getConfig().set("main-world", "");
        if (!getConfig().exists("rtp-min-radius")) getConfig().set("rtp-min-radius", 500);
        if (!getConfig().exists("rtp-max-radius")) getConfig().set("rtp-max-radius", 5000);
        saveConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LocalFastCore 0.10.0 включён. RPG, задания, отдельный spawn и RTP готовы.");
    }

    private Level loadConfiguredWorld(String key, String fallback) {
        String worldName = getConfig().getString(key, fallback);
        if (worldName == null || worldName.trim().isEmpty()) return getServer().getDefaultLevel();
        if (!getServer().isLevelLoaded(worldName) && !getServer().loadLevel(worldName)) return null;
        return getServer().getLevelByName(worldName);
    }

    private void teleportToSpawn(Player player) {
        String worldName = getConfig().getString("spawn-world", SPAWN_WORLD_DEFAULT);
        Level world = loadConfiguredWorld("spawn-world", SPAWN_WORLD_DEFAULT);
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
        String configured = getConfig().getString("main-world", "");
        Level world;
        if (configured == null || configured.trim().isEmpty()) {
            world = getServer().getDefaultLevel();
        } else {
            world = loadConfiguredWorld("main-world", "");
        }
        if (world == null) {
            player.sendMessage("§cОсновной мир не найден. Проверь параметр main-world в config.yml.");
            return;
        }
        int minRadius = Math.max(0, getConfig().getInt("rtp-min-radius", 500));
        int maxRadius = Math.max(minRadius + 1, getConfig().getInt("rtp-max-radius", 5000));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            double radius = Math.sqrt(random.nextDouble(minRadius * (double) minRadius,
                    maxRadius * (double) maxRadius));
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            int groundY;
            try {
                groundY = world.getHighestBlockAt(x, z);
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
                // Недоступный или ещё не сгенерированный участок пропускаем.
            }
        }
        player.sendMessage("§cНе удалось найти безопасное место. Попробуй /rtp ещё раз.");
    }

    private String playerKey(Player player) {
        return player.getName().toLowerCase(Locale.ROOT).replace('.', '_');
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

    private void initialize(Player player) {
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
        if (!players.exists(key + ".quest_type")) { players.set(key + ".quest_type", "any"); changed = true; }
        if (!players.exists(key + ".quest_active")) {
            players.set(key + ".quest_active", false);
            changed = true;
        }
        if (!players.exists(key + ".quest_complete")) {
            players.set(key + ".quest_complete", false);
            changed = true;
        }
        if (changed) players.save();
    }


    private String today() { return LocalDate.now().toString(); }
    private void refreshDaily(Player p) {
        initialize(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".daily_date","").equals(d)) {
            players.set(k+".daily_date",d); players.set(k+".daily_kills",0);
            players.set(k+".daily_active",false); players.set(k+".daily_complete",false); players.save();
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); initialize(p); refreshDaily(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".login_reward_date","").equals(d)) {
            players.set(k+".login_reward_date",d); players.save(); giveXp(p,LOGIN_XP_REWARD);
            p.sendMessage("§a[LocalFast] Награда за первый вход сегодня: +25 XP!");
        }
    }
    private void showDaily(Player p) {
        refreshDaily(p); String k=playerKey(p);
        p.sendMessage("§6[Ежедневное задание] §fУбей 10 любых существ.");
        if(players.getBoolean(k+".daily_complete",false)) p.sendMessage("§aВыполнено! Награда 100 XP: /daily claim");
        else if(players.getBoolean(k+".daily_active",false)) p.sendMessage("§eПрогресс: "+players.getInt(k+".daily_kills",0)+"/10");
        else p.sendMessage("§eНаграда 100 XP. Начать: /daily start");
    }
    private void handleDaily(Player p,String[] args) {
        refreshDaily(p); String k=playerKey(p);
        if(args.length==0){showDaily(p);return;}
        String a=args[0].toLowerCase(Locale.ROOT);
        if(a.equals("start")){
            if(players.getBoolean(k+".daily_active",false)||players.getBoolean(k+".daily_complete",false)){showDaily(p);return;}
            players.set(k+".daily_active",true);players.set(k+".daily_kills",0);players.save();
            p.sendMessage("§aНачато! Убей 10 любых существ.");return;
        }
        if(a.equals("claim")){
            if(!players.getBoolean(k+".daily_complete",false)){p.sendMessage("§cСначала выполни задание.");return;}
            players.set(k+".daily_complete",false);players.set(k+".daily_active",false);players.set(k+".daily_kills",0);players.save();
            giveXp(p,DAILY_XP_REWARD);p.sendMessage("§aПолучено +100 XP!");return;
        }
        p.sendMessage("§e/daily, /daily start или /daily claim");
    }

    private int xpForNextLevel(int level) {
        return 100 + (level - 1) * 50;
    }

    private void giveXp(Player player, int amount) {
        initialize(player);
        int level = Math.max(1, Math.min(MAX_LEVEL, get(player, "level", 1)));
        long totalXp = (long) get(player, "xp", 0) + amount;
        int points = Math.max(0, get(player, "points", 0));

        while (level < MAX_LEVEL && totalXp >= xpForNextLevel(level)) {
            totalXp -= xpForNextLevel(level);
            level++;
            points += POINTS_PER_LEVEL;
            player.sendMessage("§a[LocalFast] Новый уровень: §e" + level + " §a( +" + POINTS_PER_LEVEL + " очка характеристик )");
        }

        if (level == MAX_LEVEL) totalXp = Math.min(totalXp, xpForNextLevel(MAX_LEVEL) - 1L);
        set(player, "level", level);
        set(player, "xp", (int) totalXp);
        set(player, "points", points);
        players.save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobKilled(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof EntityCreature)) return;

        EntityCreature mob = (EntityCreature) event.getEntity();
        if (event.getFinalDamage() < mob.getHealth()) return;

        Player killer = (Player) event.getDamager();
        initialize(killer);
        giveXp(killer, MOB_KILL_XP);
        killer.sendMessage("§a[LocalFast] +" + MOB_KILL_XP + " опыта за победу над существом!");


        refreshDaily(killer); String dk=playerKey(killer);
        if(players.getBoolean(dk+".daily_active",false)&&!players.getBoolean(dk+".daily_complete",false)){
            int n=Math.min(DAILY_KILLS_REQUIRED,players.getInt(dk+".daily_kills",0)+1);players.set(dk+".daily_kills",n);
            if(n>=DAILY_KILLS_REQUIRED){players.set(dk+".daily_complete",true);killer.sendMessage("§6Ежедневное задание выполнено! /daily claim");}
            else killer.sendMessage("§eЕжедневное задание: "+n+"/10");
            players.save();
        }
        if (getBoolean(killer, "quest_active", false) && !getBoolean(killer, "quest_complete", false)) {
            String questType = players.getString(playerKey(killer) + ".quest_type", "any");
            String mobName = mob.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            boolean matches = questType.equals("any")
                    || (questType.equals("zombie") && mobName.contains("zombie"))
                    || (questType.equals("skeleton") && mobName.contains("skeleton"))
                    || (questType.equals("spider") && mobName.contains("spider"));
            if (matches) {
                int kills = Math.min(QUEST_KILLS_REQUIRED, get(killer, "quest_kills", 0) + 1);
                set(killer, "quest_kills", kills);
                if (kills >= QUEST_KILLS_REQUIRED) {
                    setBoolean(killer, "quest_complete", true);
                    killer.sendMessage("§6[LocalFast] Задание выполнено! Используй §e/quest claim §6, чтобы получить награду.");
                } else {
                    killer.sendMessage("§e[Квест] " + kills + "/" + QUEST_KILLS_REQUIRED);
                }
                players.save();
            }
        }
    }

    private int questReward(String type) {
        if (type.equals("zombie")) return 50;
        if (type.equals("skeleton")) return 60;
        if (type.equals("spider")) return 70;
        return QUEST_XP_REWARD;
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
            player.sendMessage("§e/quest zombie §f— 5 зомби, награда 50 XP");
            player.sendMessage("§e/quest skeleton §f— 5 скелетов, награда 60 XP");
            player.sendMessage("§e/quest spider §f— 5 пауков, награда 70 XP");
            player.sendMessage("§7Старое задание: /quest start");
            return;
        }
        String type = players.getString(playerKey(player) + ".quest_type", "any");
        if (getBoolean(player, "quest_complete", false)) {
            player.sendMessage("§a[Квест] " + questTitle(type) + " выполнено!");
            player.sendMessage("§eЗабрать " + questReward(type) + " XP: /quest claim");
        } else {
            player.sendMessage("§6[Квест] §f" + questTitle(type));
            player.sendMessage("§eПрогресс: " + get(player, "quest_kills", 0) + "/" + QUEST_KILLS_REQUIRED);
        }
    }

    private void handleQuest(Player player, String[] args) {
        initialize(player);
        if (args.length == 0) { showQuest(player); return; }

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
            players.save();
            player.sendMessage("§a[Квест] Начато: " + questTitle(type) + ". Убей 5 нужных мобов!");
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
            players.save();
            giveXp(player, reward);
            player.sendMessage("§a[Квест] Награда получена: +" + reward + " опыта!");
            return;
        }
        player.sendMessage("§eИспользуй: /quest, /quest zombie, /quest skeleton, /quest spider или /quest claim");
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

            if (args.length != 1) {
                sender.sendMessage("§e/addstat <strength|health|agility|intelligence>");
                return true;
            }
            String stat = args[0].toLowerCase(Locale.ROOT);
            boolean valid = false;
            for (String allowed : STATS) if (allowed.equals(stat)) valid = true;
            if (!valid) {
                sender.sendMessage("§cВыбери: strength, health, agility или intelligence.");
                return true;
            }
            int points = get(player, "points", 0);
            if (points < 1) {
                sender.sendMessage("§cУ тебя нет свободных очков характеристик.");
                return true;
            }
            set(player, stat, get(player, stat, 0) + 1);
            set(player, "points", points - 1);
            players.save();
            sender.sendMessage("§a[LocalFast] Характеристика " + stat + " увеличена на 1.");
            return true;
        }

        if (name.equals("lfadmin")) {
            if (!sender.hasPermission("localfast.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (args.length != 3 || !args[0].equalsIgnoreCase("xp")) {
                sender.sendMessage("§e/lfadmin xp <player> <amount>");
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
        if (players != null) players.save();
    }
}
