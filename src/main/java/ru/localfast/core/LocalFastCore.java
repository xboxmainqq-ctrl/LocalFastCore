package ru.localfast.core;

import cn.nukkit.Player;
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
        int refreshTicks = Math.max(20, Math.min(1200, getConfig().getInt("scoreboard.refresh-ticks", 40)));
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("scoreboard.enabled", true)) return;
            for (Player online : getServer().getOnlinePlayers().values()) updateSidebar(online);
        }, refreshTicks);
        getLogger().info("LocalFastCore 0.9.3 включён. RPG, GUI и уровневые квесты готовы.");
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


    private void updateSidebar(Player player) {
        if (!getConfig().getBoolean("scoreboard.enabled", true)) return;
        String key = playerKey(player);
        IScoreboard scoreboard = sidebars.get(key);
        if (scoreboard == null) {
            String objective = "localfast_" + key.replaceAll("[^a-z0-9_]", "_");
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
    }

    /**
     * Recalculate bonuses from saved stats using absolute values.
     * Vanilla Nukkit-MOT player base values: 20 HP and 0.1 movement speed.
     */
    private void recalculateStats(Player player) {
        int healthBonus = get(player, "health", 0) / 5;
        int maximumHealth = 20 + healthBonus;
        player.setMaxHealth(maximumHealth);
        if (player.getHealth() > maximumHealth) player.setHealth(maximumHealth);
        int agilityPercent = get(player, "agility", 0) / 5;
        float movementSpeed = Player.DEFAULT_SPEED * (1.0f + agilityPercent / 100.0f);
        player.setMovementSpeed(Math.min(Player.MAXIMUM_SPEED, movementSpeed));
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
        int bonusDamage = get(attacker, "strength", 0) / 5;
        if (bonusDamage <= 0) return;
        event.setDamage(event.getDamage(EntityDamageEvent.DamageModifier.BASE) + bonusDamage,
                EntityDamageEvent.DamageModifier.BASE);
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
        if (changed) players.save();
    }


    private String today() { return LocalDate.now().toString(); }
    private void refreshDaily(Player p) {
        initialize(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".daily_date","").equals(d)) {
            players.set(k+".daily_date",d); players.set(k+".daily_kills",0);
            players.set(k+".daily_active",false); players.set(k+".daily_complete",false);
            players.set(k+".daily_claimed",false); players.save();
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); initialize(p); refreshDaily(p); String k=playerKey(p); String d=today();
        if (!players.getString(k+".login_reward_date","").equals(d)) {
            players.set(k+".login_reward_date",d); players.save(); giveXp(p, Math.max(0, getConfig().getInt("progression.login-reward-xp", LOGIN_XP_REWARD)));
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
            players.set(k+".daily_active",true);players.set(k+".daily_kills",0);players.save();
            int required = Math.max(1, getConfig().getInt("daily.kills-required", DAILY_KILLS_REQUIRED));
            p.sendMessage("§aНачато! Убей " + required + " любых существ.");return;
        }
        if(a.equals("claim")){
            if(!players.getBoolean(k+".daily_complete",false)){p.sendMessage("§cСначала выполни задание.");return;}
            players.set(k+".daily_complete",false);players.set(k+".daily_active",false);players.set(k+".daily_claimed",true);players.save();
            int reward = Math.max(0, getConfig().getInt("daily.xp-reward", DAILY_XP_REWARD));
            giveXp(p,reward);p.sendMessage("§aПолучено +" + reward + " XP!");return;
        }
        p.sendMessage("§e/daily, /daily start или /daily claim");
    }

    private int xpForNextLevel(int level) {
        return 100 + (level - 1) * 50;
    }

    private int intelligenceXpBonusPercent(Player player) {
        int intelligenceStep = Math.max(1, getConfig().getInt("intelligence.xp-bonus-per", 5));
        long bonus = (long) Math.max(0, get(player, "intelligence", 0) / intelligenceStep)
                * Math.max(0, getConfig().getInt("intelligence.xp-bonus-percent", 1));
        return (int) Math.min(100L, bonus);
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
        players.save();
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
            players.save();
        }
        updateLevelQuests(killer, mobName);
        if (getBoolean(killer, "quest_active", false) && !getBoolean(killer, "quest_complete", false)) {
            String questType = players.getString(playerKey(killer) + ".quest_type", "any");
            String mobName = mob.getClass().getSimpleName().toLowerCase(Locale.ROOT);
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
                players.save();
            }
        }
    }

    private void updateLevelQuests(Player player, String mobName) {
        String key = playerKey(player);
        int level = Math.max(1, get(player, "level", 1));
        boolean changed = false;
        for (QuestDefinition quest : questCatalog()) {
            if (level < quest.minLevel || level > quest.maxLevel) continue;
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
        if (changed) players.save();
    }

    private void openQuestsMenu(Player player) {
        initialize(player);
        int level = Math.max(1, get(player, "level", 1));
        FormWindowSimple form = new FormWindowSimple("§6LocalFastCore §8| §lКвесты",
                "§fТвой уровень: §e" + level + "\\n§7Выбери диапазон уровней или посмотри свои задания.");
        String[] ranges = {"1–10", "11–30", "31–50", "51–75", "76–100"};
        for (String range : ranges) form.addButton(new ElementButton("§eУровни " + range));
        form.addButton(new ElementButton("§aМои задания"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            int selected = form.getResponse().getClickedButtonId();
            if (selected >= 0 && selected < 5) openQuestTierMenu(player, selected);
            else if (selected == 5) openMyQuestsMenu(player);
        }));
        player.showFormWindow(form);
    }

    private void openQuestTierMenu(Player player, int tier) {
        int[][] ranges = {{1,10},{11,30},{31,50},{51,75},{76,100}};
        int min = ranges[tier][0], max = ranges[tier][1];
        FormWindowSimple form = new FormWindowSimple("§6Квесты §8| §e" + min + "–" + max,
                "§fТвой уровень: §e" + get(player, "level", 1) + "\\n§7Выбери задание для просмотра.");
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
        FormWindowSimple form = new FormWindowSimple("§6Квест §8| §e" + quest.title,
                "§f" + quest.description + "\\n§fПрогресс: §a" + progress + "/" + questKills(quest)
                        + "\\n§fНаграда: §6" + questXp(quest) + " XP"
                        + "\\n§fТребуемый уровень: §e" + quest.minLevel + "–" + quest.maxLevel
                        + (claimed ? "\\n§8Награда уже получена." : complete ? "\\n§aЗадание выполнено!" : ""));
        if (!claimed) {
            if (complete) form.addButton(new ElementButton("§aПолучить награду"));
            else if (!active) form.addButton(new ElementButton("§aПринять задание"));
            else form.addButton(new ElementButton("§eЗадание активно"));
        }
        form.addButton(new ElementButton("§7Назад"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null) return;
            int selected = form.getResponse().getClickedButtonId();
            if (claimed || (active && !complete)) {
                openQuestTierMenu(player, tier);
                return;
            }
            if (selected == 0 && complete && !claimed) {
                players.set(path + ".active", false);
                players.set(path + ".complete", false);
                players.set(path + ".claimed", true);
                players.save();
                giveXp(player, questXp(quest));
                player.sendMessage("§a[Квесты] Получено +" + questXp(quest) + " XP за " + quest.title + "!");
            } else if (selected == 0 && !active && !claimed) {
                if (get(player, "level", 1) < quest.minLevel || get(player, "level", 1) > quest.maxLevel) {
                    player.sendMessage("§cЭтот квест пока недоступен по уровню.");
                } else {
                    players.set(path + ".active", true);
                    players.set(path + ".kills", 0);
                    players.set(path + ".complete", false);
                    players.save();
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
            players.save();
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
            players.save();
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
        players.save();
        recalculateStats(player);
        updateSidebar(player);
        player.sendMessage("§a[LocalFast] " + stat + " увеличена на " + amount + ".");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("quests")) {\n            if (!(sender instanceof Player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }\n            openQuestsMenu((Player) sender); return true;\n        }\n        if(name.equals("daily")){if(!(sender instanceof Player)){sender.sendMessage("Только для игроков.");return true;}handleDaily((Player)sender,args);return true;}
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
            players.save();
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
