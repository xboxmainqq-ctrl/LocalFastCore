package ru.localfast.abilities;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.item.Item;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ClassAbilities extends PluginBase implements Listener {
    private static final String ITEM_NAME = "§d✦ Суперспособность";
    private static final long COOLDOWN_MS = 35_000L;
    private static final long TANK_DURATION_MS = 5_000L;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> tankUntil = new HashMap<>();
    private final Set<UUID> autoIssued = new HashSet<>();
    private File playerDataFile;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("LocalFastCore") == null) {
            getLogger().error("Не найден LocalFastCore. ClassAbilities отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        playerDataFile = new File(getServer().getPluginManager()
                .getPlugin("LocalFastCore").getDataFolder(), "players.yml");
        getServer().getPluginManager().registerEvents(this, this);
        // Detect a class chosen after login and issue the item once automatically.
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            for (Player player : getServer().getOnlinePlayers().values()) {
                UUID id = player.getUniqueId();
                if (playerClass(player).equals("none")) continue;
                if (hasAbilityItem(player)) {
                    autoIssued.add(id);
                } else if (autoIssued.add(id)) {
                    giveAbilityItem(player);
                }
            }
        }, 20);
        getLogger().info("ClassAbilities 0.13.0 включён. Команда: /abilities");
    }

    private String playerClass(Player player) {
        if (playerDataFile == null || !playerDataFile.isFile()) return "none";
        try {
            Config profiles = new Config(playerDataFile, Config.YAML);
            String key = "uuid_" + player.getUniqueId().toString().toLowerCase(Locale.ROOT);
            String value = profiles.getString(key + ".rpg_class", "none");
            if (value.equals("warrior") || value.equals("mage") || value.equals("archer")
                    || value.equals("assassin") || value.equals("tank")) return value;
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось прочитать класс игрока " + player.getName() + ".");
        }
        return "none";
    }

    private String className(String id) {
        switch (id) {
            case "warrior": return "§cВоин";
            case "mage": return "§bМаг";
            case "archer": return "§aЛучник";
            case "assassin": return "§dАссасин";
            case "tank": return "§6Танк";
            default: return "§7Не выбран";
        }
    }

    private String stats(String id) {
        switch (id) {
            case "warrior": return "§7+2 здоровья, +1 к урону";
            case "mage": return "§7+15% к получаемому опыту";
            case "archer": return "§7+8% к скорости передвижения";
            case "assassin": return "§7+4% к скорости, шанс критического удара 15%";
            case "tank": return "§7+6 здоровья, -5% к скорости";
            default: return "§7Класс ещё не выбран в /class.";
        }
    }

    private String ability(String id) {
        switch (id) {
            case "warrior": return "§cУдар титана§f — мощный удар по врагам вокруг с отбрасыванием";
            case "mage": return "§bМетеорит§f — магический взрыв, наносящий урон врагам рядом";
            case "archer": return "§aГрад стрел§f — поражает врагов перед тобой на расстоянии";
            case "assassin": return "§dТеневой рывок§f — быстрый рывок вперёд и удар ближайшей цели";
            case "tank": return "§6Несокрушимость§f — снижает входящий урон на 50% на 5 секунд";
            default: return "§7Нет доступной способности.";
        }
    }

    private Item abilityItem() {
        Item item = Item.get(399, 0, 1);
        item.setCustomName(ITEM_NAME);
        item.setLore("§7Предмет активации класса", "§fНажми правой кнопкой", "§8Перезарядка: 35 секунд");
        return item;
    }

    private boolean hasAbilityItem(Player player) {
        for (Item item : player.getInventory().getContents().values()) {
            if (item != null && item.getId() == 399 && ITEM_NAME.equals(item.getCustomName())) return true;
        }
        return false;
    }

    private void giveAbilityItem(Player player) {
        if (playerClass(player).equals("none") || hasAbilityItem(player)) return;
        player.getInventory().addItem(abilityItem());
        player.sendMessage("§d✦ §fТебе выдан предмет суперспособности. Нажми им правой кнопкой!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        autoIssued.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        autoIssued.remove(id);
        cooldowns.remove(id);
        tankUntil.remove(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("abilities")) return false;
        if (!(sender instanceof Player)) {
            sender.sendMessage("Эта команда доступна только игроку.");
            return true;
        }
        openMenu((Player) sender);
        return true;
    }

    private void openMenu(Player player) {
        String current = playerClass(player);
        FormWindowSimple form = new FormWindowSimple("§6§lКлассы и способности",
                "§fТвой класс: " + className(current) + "\n"
                        + stats(current) + "\n\n§fСуперспособность:\n" + ability(current)
                        + "\n\n§8Перезарядка: 35 секунд");
        form.addButton(new ElementButton("§cВоин\n§7Удар титана"));
        form.addButton(new ElementButton("§bМаг\n§7Метеорит"));
        form.addButton(new ElementButton("§aЛучник\n§7Град стрел"));
        form.addButton(new ElementButton("§dАссасин\n§7Теневой рывок"));
        form.addButton(new ElementButton("§6Танк\n§7Несокрушимость"));
        form.addButton(new ElementButton("§dПолучить предмет способности"));
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed() || form.getResponse() == null || !player.isOnline()) return;
            int button = form.getResponse().getClickedButtonId();
            if (button == 5) {
                if (playerClass(player).equals("none")) {
                    player.sendMessage("§cСначала выбери класс через /class.");
                } else if (hasAbilityItem(player)) {
                    player.sendMessage("§eПредмет способности уже есть в инвентаре.");
                } else {
                    giveAbilityItem(player);
                }
                return;
            }
            String[] ids = {"warrior", "mage", "archer", "assassin", "tank"};
            if (button >= 0 && button < ids.length) {
                String id = ids[button];
                player.sendMessage("§6" + className(id) + "\n" + stats(id) + "\n" + ability(id));
                if (!id.equals(current)) player.sendMessage("§7Это описание класса. Твой выбранный класс менять здесь нельзя.");
            }
        }));
        player.showFormWindow(form);
    }

    @EventHandler
    public void onAbilityUse(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_AIR
                && event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        Item used = event.getItem();
        if (used == null || used.getId() != 399 || !ITEM_NAME.equals(used.getCustomName())) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        String id = playerClass(player);
        if (id.equals("none")) {
            player.sendMessage("§cСначала выбери класс через /class.");
            return;
        }
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (readyAt > now) {
            player.sendMessage("§eСпособность перезаряжается: §f" + ((readyAt - now + 999) / 1000) + " сек.");
            return;
        }
        activate(player, id);
        cooldowns.put(player.getUniqueId(), now + COOLDOWN_MS);
    }

    private void activate(Player player, String id) {
        switch (id) {
            case "warrior":
                damageAround(player, 4.0, 8.0f, true);
                player.sendMessage("§c✦ Удар титана!");
                break;
            case "mage":
                damageAround(player, 5.0, 7.0f, false);
                player.sendMessage("§b✦ Метеорит обрушился на врагов!");
                break;
            case "archer":
                damageInFront(player, 10.0, 5.0f);
                player.sendMessage("§a✦ Град стрел!");
                break;
            case "assassin":
                Vector3 direction = player.getDirectionVector();
                player.setMotion(new Vector3(direction.x * 1.8, Math.max(0.15, direction.y * 1.2), direction.z * 1.8));
                damageNearest(player, 3.0, 9.0f);
                player.sendMessage("§d✦ Теневой рывок!");
                break;
            case "tank":
                tankUntil.put(player.getUniqueId(), System.currentTimeMillis() + TANK_DURATION_MS);
                player.sendMessage("§6✦ Несокрушимость! §fУрон снижен на 50% на 5 секунд.");
                break;
            default:
                player.sendMessage("§cНеизвестный класс.");
        }
    }

    private boolean isCombatTarget(Entity entity, Player source) {
        return entity != source && !entity.isClosed()
                && (entity instanceof Player || entity instanceof EntityCreature);
    }

    private double distanceSquared(Player player, Entity entity) {
        double dx = player.getX() - entity.getX();
        double dy = player.getY() - entity.getY();
        double dz = player.getZ() - entity.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void hit(Player player, Entity target, float damage, float knockback) {
        EntityDamageByEntityEvent damageEvent = new EntityDamageByEntityEvent(
                player, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage, knockback);
        target.attack(damageEvent);
    }

    private void damageAround(Player player, double radius, float damage, boolean knockback) {
        double radiusSquared = radius * radius;
        for (Entity entity : player.getLevel().getEntities()) {
            if (!isCombatTarget(entity, player) || distanceSquared(player, entity) > radiusSquared) continue;
            hit(player, entity, damage, knockback ? 0.9f : 0.25f);
            if (knockback) {
                double dx = entity.getX() - player.getX();
                double dz = entity.getZ() - player.getZ();
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length > 0.001) entity.setMotion(new Vector3(dx / length * 0.9, 0.35, dz / length * 0.9));
            }
        }
    }

    private void damageInFront(Player player, double range, float damage) {
        Vector3 direction = player.getDirectionVector();
        for (Entity entity : player.getLevel().getEntities()) {
            if (!isCombatTarget(entity, player)) continue;
            double dx = entity.getX() - player.getX();
            double dy = entity.getY() - player.getY();
            double dz = entity.getZ() - player.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 0.1 || distance > range) continue;
            double dot = (dx * direction.x + dy * direction.y + dz * direction.z) / distance;
            if (dot >= 0.72) hit(player, entity, damage, 0.15f);
        }
    }

    private void damageNearest(Player player, double radius, float damage) {
        Entity nearest = null;
        double best = radius * radius;
        for (Entity entity : player.getLevel().getEntities()) {
            if (!isCombatTarget(entity, player)) continue;
            double distance = distanceSquared(player, entity);
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        if (nearest != null) hit(player, nearest, damage, 0.35f);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        UUID id = event.getEntity().getUniqueId();
        long until = tankUntil.getOrDefault(id, 0L);
        if (until <= System.currentTimeMillis()) {
            tankUntil.remove(id);
            return;
        }
        event.setDamage(event.getDamage() * 0.5f);
    }
}
