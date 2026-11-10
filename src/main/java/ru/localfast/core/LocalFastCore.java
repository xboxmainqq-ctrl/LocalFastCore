package ru.localfast.core;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.Locale;

public final class LocalFastCore extends PluginBase implements Listener {
    private static final int MAX_LEVEL = 100;
    private static final int POINTS_PER_LEVEL = 3;
    private static final int MAX_XP_GRANT = 1_000_000;
    private static final int MOB_KILL_XP = 10;
    private static final int QUEST_KILLS_REQUIRED = 5;
    private static final int QUEST_XP_REWARD = 50;
    private static final String[] STATS = {"strength", "health", "agility", "intelligence"};

    private Config players;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().error("Не удалось создать папку данных LocalFastCore.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        players = new Config(new File(getDataFolder(), "players.yml"), Config.YAML);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LocalFastCore 0.5.0 включён. Прогрессия и охотничьи задания готовы.");
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
