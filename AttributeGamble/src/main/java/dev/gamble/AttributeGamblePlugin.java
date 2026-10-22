package dev.gamble;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class AttributeGamblePlugin extends JavaPlugin implements TabExecutor, Listener {

    /** MULT: factor of normal. ABS: absolute final value. ADD: flat amount added to normal. */
    enum Kind { MULT, ABS, ADD }

    /** HIGH/LOW: better at that end. NEUTRAL: ignored by balance and pity. */
    enum Good { HIGH, LOW, NEUTRAL }

    static final int LEVELS = 5;

    static final class Stat {
        final String id;
        final String group;          // null = independent
        final Kind kind;
        final Good defGood;
        final double[] defLevels;
        double defWeight = 1.0;
        double defUnit = 1.0;

        Attribute attr;              // null if this server doesn't have the attribute
        double[] levels;             // 5 possible rolls, ascending
        double weight = 1.0;
        double unit = 1.0;           // for ADD stats with base 0: how much counts as +100% in balance maths
        Good good;
        String linkedTo;             // if set, follows another stat instead of rolling

        Stat(String id, String group, Kind kind, Good good, double[] levels) {
            this.id = id;
            this.group = group;
            this.kind = kind;
            this.defGood = good;
            this.defLevels = levels;
        }

        boolean counted() {
            return group != null && good != Good.NEUTRAL && attr != null;
        }

        double sign() {
            return good == Good.LOW ? -1.0 : 1.0;
        }

        double lo() { return levels[0]; }
        double hi() { return levels[LEVELS - 1]; }
    }

    static final class Snapshot {
        final Map<String, Double> vals = new HashMap<>();
        final Map<String, Double> dbl = new HashMap<>();
        final Map<String, String> str = new HashMap<>();
    }

    static final class StatsMenu implements InventoryHolder {
        final Player viewer, target;
        final Map<Integer, Stat> slots = new HashMap<>();
        Inventory inv;

        StatsMenu(Player viewer, Player target) {
            this.viewer = viewer;
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static final class RollMenu implements InventoryHolder {
        Inventory inv;
        volatile boolean closed;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private record GroupLayout(String label, NamedTextColor color, int labelSlot, int[] slots, String[] ids) {}

    private static final List<GroupLayout> LAYOUT = List.of(
            new GroupLayout("Combat", NamedTextColor.RED, 9, new int[]{10, 11, 12, 13, 14},
                    new String[]{"entity_interaction_range", "block_interaction_range", "attack_speed", "attack_knockback", "max_health"}),
            new GroupLayout("Mobility", NamedTextColor.GREEN, 18, new int[]{19, 20, 21, 22},
                    new String[]{"gravity", "jump_strength", "movement_speed", "scale"}),
            new GroupLayout("Utility", NamedTextColor.YELLOW, 27, new int[]{28, 29, 30, 31},
                    new String[]{"safe_fall_distance", "fall_damage_multiplier", "burning_time", "sneaking_speed"}),
            new GroupLayout("Other", NamedTextColor.AQUA, 36, new int[]{37, 38, 39},
                    new String[]{"block_break_speed", "oxygen_bonus", "submerged_mining_speed"}));

    private static final Material[] PANES = {
            Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE};

    private static final long ANIMATION_TICKS = 140L;

    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("entity_interaction_range", "FISHING_ROD"),
            Map.entry("block_interaction_range", "SCAFFOLDING"),
            Map.entry("attack_speed", "CLOCK"),
            Map.entry("attack_knockback", "PISTON"),
            Map.entry("max_health", "GOLDEN_APPLE"),
            Map.entry("gravity", "FEATHER"),
            Map.entry("jump_strength", "RABBIT_FOOT"),
            Map.entry("movement_speed", "LEATHER_BOOTS"),
            Map.entry("scale", "BROWN_MUSHROOM"),
            Map.entry("safe_fall_distance", "SLIME_BLOCK"),
            Map.entry("fall_damage_multiplier", "HAY_BLOCK"),
            Map.entry("burning_time", "BLAZE_POWDER"),
            Map.entry("sneaking_speed", "LEATHER_LEGGINGS"),
            Map.entry("block_break_speed", "IRON_PICKAXE"),
            Map.entry("oxygen_bonus", "HEART_OF_THE_SEA"),
            Map.entry("submerged_mining_speed", "PRISMARINE_SHARD"));

    private static final Map<String, String> DESC = Map.ofEntries(
            Map.entry("entity_interaction_range", "How far you can hit mobs and players."),
            Map.entry("block_interaction_range", "How far you can mine and place blocks."),
            Map.entry("attack_speed", "How fast your weapon recharges."),
            Map.entry("attack_knockback", "How hard you knock enemies back."),
            Map.entry("max_health", "How many hearts you have."),
            Map.entry("gravity", "How fast you fall."),
            Map.entry("jump_strength", "How high you jump."),
            Map.entry("movement_speed", "How fast you run."),
            Map.entry("scale", "How big your body is."),
            Map.entry("safe_fall_distance", "How far you can fall unharmed."),
            Map.entry("fall_damage_multiplier", "How much damage falls do to you."),
            Map.entry("burning_time", "How long you burn."),
            Map.entry("sneaking_speed", "How fast you move while sneaking."),
            Map.entry("block_break_speed", "How fast you break blocks."),
            Map.entry("oxygen_bonus", "Extra time underwater."),
            Map.entry("submerged_mining_speed", "How fast you mine underwater."));

    private static final String[] RATING_WORDS = {"Poor", "Below average", "Average", "Good", "Excellent"};

    private static final List<String> GROUPS = List.of("combat", "mobility", "utility");
    private static final String STEP_ID = "step_height";
    private static final String SCALE_BONUS_ID = "scale_speed_bonus";
    private static final String LOW_HP_ID = "low_health_bonus";
    private static final String JUMP_FALL_ID = "jump_safe_fall";

    private final List<Stat> stats = new ArrayList<>();
    private final Map<UUID, Snapshot> pending = new HashMap<>();
    private final Map<UUID, Long> pillarCooldown = new HashMap<>();
    private final Map<UUID, Component> titleCache = new ConcurrentHashMap<>();
    /** Custom penalties for rolls the game can't represent: [knockback penalty, oxygen penalty] (positive numbers). */
    private final Map<UUID, double[]> negatives = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadStats();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("gamble");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::periodicTick, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, this::negativeTick, 40L, 2L);
    }

    private Stat def(String id, String group, Kind kind, Good good, double... levels) {
        Stat s = new Stat(id, group, kind, good, levels);
        stats.add(s);
        return s;
    }

    private void loadStats() {
        stats.clear();

        // combat
        def("entity_interaction_range", "combat", Kind.ABS, Good.HIGH, 2.0, 2.5, 3.0, 3.5, 4.0).defWeight = 2.0;
        def("attack_speed", "combat", Kind.MULT, Good.HIGH, 0.6, 0.8, 1.0, 1.2, 1.4);
        Stat kb = def("attack_knockback", "combat", Kind.ADD, Good.HIGH, -1.0, -0.5, 0.0, 0.5, 1.0);
        kb.defUnit = 4.0;
        def("max_health", "combat", Kind.MULT, Good.HIGH, 0.6, 0.8, 1.0, 1.2, 1.4);

        // mobility
        def("gravity", "mobility", Kind.MULT, Good.LOW, 0.5, 0.75, 1.0, 1.25, 1.5);
        def("jump_strength", "mobility", Kind.MULT, Good.HIGH, 0.5, 0.75, 1.0, 1.25, 1.5);
        def("movement_speed", "mobility", Kind.MULT, Good.HIGH, 0.6, 0.8, 1.0, 1.2, 1.4);
        def("scale", "mobility", Kind.MULT, Good.NEUTRAL, 0.6, 0.8, 1.0, 1.2, 1.4);

        // utility quirks
        def("safe_fall_distance", "utility", Kind.MULT, Good.HIGH, 0.5, 0.75, 1.0, 1.25, 1.5);
        def("fall_damage_multiplier", "utility", Kind.MULT, Good.LOW, 0.5, 0.75, 1.0, 1.25, 1.5);
        def("burning_time", "utility", Kind.MULT, Good.LOW, 0.5, 0.75, 1.0, 1.25, 1.5);
        def("sneaking_speed", "utility", Kind.MULT, Good.HIGH, 0.6, 0.8, 1.0, 1.2, 1.4);

        // independent
        def("block_break_speed", null, Kind.MULT, Good.HIGH, 0.5, 0.75, 1.0, 1.25, 1.5);
        def("oxygen_bonus", null, Kind.ADD, Good.HIGH, -2.0, -1.0, 0.0, 1.0, 2.0);
        def("submerged_mining_speed", null, Kind.MULT, Good.HIGH, 0.5, 0.75, 1.0, 1.25, 1.5);

        // linked: block reach follows entity reach (+ bonus)
        Stat block = def("block_interaction_range", null, Kind.ADD, Good.HIGH, 0.0, 0.0, 0.0, 0.0, 0.0);
        block.linkedTo = "entity_interaction_range";

        for (Stat s : stats) {
            s.attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(s.id));
            String path = "stats." + s.id + ".";

            double[] lv = s.defLevels.clone();
            List<Double> cfg = getConfig().getDoubleList(path + "levels");
            if (s.linkedTo == null && cfg.size() == LEVELS) {
                for (int i = 0; i < LEVELS; i++) lv[i] = cfg.get(i);
                Arrays.sort(lv);
            }
            s.levels = lv;
            s.weight = Math.max(0.01, getConfig().getDouble(path + "weight", s.defWeight));
            s.unit = Math.max(0.01, getConfig().getDouble(path + "unit", s.defUnit));
            try {
                s.good = Good.valueOf(getConfig().getString(path + "good", s.defGood.name()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                s.good = s.defGood;
            }
            if (s.attr == null) {
                getLogger().warning("Attribute minecraft:" + s.id + " does not exist on this server - skipping.");
            }
        }
    }

    private Stat findStat(String id) {
        for (Stat s : stats) if (s.id.equalsIgnoreCase(id)) return s;
        return null;
    }

    private List<Stat> available() {
        return stats.stream().filter(s -> s.attr != null).toList();
    }

    /** Stats rolled on their own (everything except linked stats). */
    private List<Stat> rollable() {
        return stats.stream().filter(s -> s.attr != null && s.linkedTo == null).toList();
    }

    private List<Stat> members(String group) {
        return stats.stream().filter(s -> s.counted() && group.equals(s.group)).toList();
    }

    private double tolerance(String group) {
        double def = group.equals("combat") ? 0.05 : group.equals("mobility") ? 0.15 : 0.10;
        return Math.max(0.0, getConfig().getDouble("groups." + group + ".tolerance", def));
    }

    private double pityStrength() {
        return Math.max(0.0, getConfig().getDouble("pity-strength", 2.0));
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore() && getConfig().getBoolean("gamble-on-first-join", true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            if (first) {
                rollAll(p);
                p.sendMessage(Component.text("Welcome! You gambled your attributes - use /gamble to see them.", NamedTextColor.GOLD));
                boolean showMenu = getConfig().getBoolean("menu-on-first-join", true);
                if (animationEnabled()) playFullRoll(p, () -> { if (showMenu) openMenu(p, p); });
                else if (showMenu) openMenu(p, p);
            } else {
                applyTitle(p);
                updateNegatives(p);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pending.remove(id); // unanswered reroll = keep
        pillarCooldown.remove(id);
        titleCache.remove(id);
        negatives.remove(id);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!getConfig().getBoolean("reroll-on-death.enabled", false)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            if ("all".equalsIgnoreCase(getConfig().getString("reroll-on-death.mode", "one"))) {
                rollAll(p);
                p.sendMessage(Component.text("You died - all your stats were rerolled!", NamedTextColor.GOLD));
            } else {
                List<Stat> r = rollable();
                if (r.isEmpty()) return;
                Stat st = r.get(ThreadLocalRandom.current().nextInt(r.size()));
                rollOne(p, st);
                p.sendMessage(Component.text("You died - " + pretty(st.id) + " was rerolled!", NamedTextColor.GOLD));
            }
        });
    }

    /** No auto-step while sneaking. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        Attribute stepAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(STEP_ID));
        if (stepAttr == null) return;
        if (e.isSneaking()) {
            removeMod(p, stepAttr, STEP_ID);
        } else {
            Bukkit.getScheduler().runTask(this, () -> fixJump(p));
        }
    }

    /** Title as a chat prefix. */
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("titles.chat-prefix", true)) return;
        Component tag = titleCache.get(e.getPlayer().getUniqueId());
        if (tag == null) return;
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) ->
                Component.text().append(tag).append(Component.text(" <")).append(name)
                        .append(Component.text("> ")).append(message).build()));
    }

    private void periodicTick() {
        if (!getConfig().getBoolean("periodic-reroll.enabled", false)) return;
        int days = Math.max(1, getConfig().getInt("periodic-reroll.days", 3));
        NamespacedKey k = keyFor("last_reroll_day");
        for (Player p : Bukkit.getOnlinePlayers()) {
            long day = p.getWorld().getFullTime() / 24000L;
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            Long last = pdc.get(k, PersistentDataType.LONG);
            if (last == null) {
                pdc.set(k, PersistentDataType.LONG, day);
            } else if (day - last >= days) {
                pdc.set(k, PersistentDataType.LONG, day);
                rollAll(p);
                p.sendMessage(Component.text("A new era begins - all your stats were rerolled!", NamedTextColor.GOLD));
            }
        }
    }

    // ------------------------------------------------------------------ negative rolls (custom effects)

    /**
     * Minecraft attributes can't go below 0 for attack knockback and oxygen bonus, so negative rolls are
     * emulated: weaker knockback on your hits, faster air loss underwater.
     */
    private void updateNegatives(Player p) {
        double kb = 0, ox = 0;
        Stat k = findStat("attack_knockback"), o = findStat("oxygen_bonus");
        if (k != null && k.attr != null && isRolled(p, k)) kb = Math.min(0.0, current(p, k));
        if (o != null && o.attr != null && isRolled(p, o)) ox = Math.min(0.0, current(p, o));
        if (kb < 0 || ox < 0) negatives.put(p.getUniqueId(), new double[]{-kb, -ox});
        else negatives.remove(p.getUniqueId());
    }

    private void negativeTick() {
        if (negatives.isEmpty()) return;
        double perPoint = getConfig().getDouble("negative-stats.oxygen-drain-per-point", 1.0);
        for (var e : negatives.entrySet()) {
            double ox = e.getValue()[1];
            if (ox <= 0) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) continue;
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.getEyeLocation().getBlock().getType() != Material.WATER) continue;
            int air = p.getRemainingAir();
            if (air >= p.getMaximumAir()) continue;
            int extra = (int) Math.round(ox * perPoint);
            if (extra > 0) p.setRemainingAir(Math.max(-19, air - extra));
        }
    }

    /** Negative attack knockback: your melee hits push enemies back less. */
    @EventHandler(ignoreCancelled = true)
    public void onKnockbackPenalty(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        double[] n = negatives.get(attacker.getUniqueId());
        if (n == null || n[0] <= 0) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        double cut = Math.min(0.9, n[0] * getConfig().getDouble("negative-stats.knockback-cut-per-point", 0.5));
        Bukkit.getScheduler().runTask(this, () -> {
            if (!victim.isValid()) return;
            Vector v = victim.getVelocity();
            victim.setVelocity(new Vector(v.getX() * (1.0 - cut), v.getY(), v.getZ() * (1.0 - cut)));
        });
    }

    // ------------------------------------------------------------------ pillar assist

    private boolean needsPillarAssist(Player p) {
        Stat g = findStat("gravity"), j = findStat("jump_strength");
        if (g == null || j == null || g.attr == null || j.attr == null) return false;
        AttributeInstance gi = p.getAttribute(g.attr), ji = p.getAttribute(j.attr);
        if (gi == null || ji == null) return false;
        return jumpPeak(gi.getValue(), ji.getValue()) < getConfig().getDouble("min-jump-height", 1.05);
    }

    /** Sneak + look down + right-click with a solid block: place it under you and step up onto it. */
    @EventHandler
    public void onPillarAssist(PlayerInteractEvent e) {
        if (!getConfig().getBoolean("pillar-assist.enabled", true)) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!p.isSneaking() || p.getLocation().getPitch() < 70f) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !hand.getType().isBlock() || !hand.getType().isSolid()) return;
        if (!needsPillarAssist(p) || !p.isOnGround()) return;

        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("pillar-assist.cooldown-ms", 250);
        Long last = pillarCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            e.setCancelled(true);
            return;
        }

        Location loc = p.getLocation();
        Block feet = loc.getBlock();
        Block up1 = feet.getRelative(BlockFace.UP);
        Block up2 = up1.getRelative(BlockFace.UP);
        if (loc.getY() - feet.getY() > 0.01) return;
        if (!feet.isReplaceable() || !up1.isPassable() || !up2.isPassable()) return;

        e.setCancelled(true);
        pillarCooldown.put(p.getUniqueId(), now);

        BlockState replaced = feet.getState();
        BlockData data = hand.getType().createBlockData();
        feet.setBlockData(data, false);

        BlockPlaceEvent place = new BlockPlaceEvent(feet, replaced, feet.getRelative(BlockFace.DOWN),
                hand, p, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(place);
        if (place.isCancelled() || !place.canBuild()) {
            replaced.update(true, false);
            return;
        }

        if (p.getGameMode() != GameMode.CREATIVE) {
            int left = hand.getAmount() - 1;
            if (left <= 0) {
                p.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(left);
                p.getInventory().setItemInMainHand(hand);
            }
        }

        Location dest = loc.clone();
        dest.setY(feet.getY() + 1.0);
        p.teleport(dest);
        p.setVelocity(new Vector(0, 0, 0));
        feet.getWorld().playSound(feet.getLocation(), data.getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------ attribute helpers

    private NamespacedKey keyFor(String id) {
        return new NamespacedKey(this, "gamble_" + id);
    }

    private AttributeModifier findModifier(AttributeInstance inst, NamespacedKey key) {
        for (AttributeModifier m : inst.getModifiers()) {
            if (key.equals(m.getKey())) return m;
        }
        return null;
    }

    private void removeMod(Player p, Attribute attr, String id) {
        if (attr == null) return;
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier old = findModifier(inst, keyFor(id));
        if (old != null) inst.removeModifier(old);
    }

    private double baseOf(Player p, Stat s) {
        AttributeInstance i = p.getAttribute(s.attr);
        return i == null ? 0.0 : i.getBaseValue();
    }

    private double neutral(Stat s, double base) {
        return switch (s.kind) {
            case MULT -> 1.0;
            case ABS -> base;
            case ADD -> s.levels[LEVELS / 2];
        };
    }

    /** Fractional deviation from normal (0.1 = +10%). */
    private double devOf(Stat s, double v, double base) {
        return switch (s.kind) {
            case MULT -> v - 1.0;
            case ABS -> base == 0 ? 0 : v / base - 1.0;
            case ADD -> (v - s.levels[LEVELS / 2]) / s.unit;
        };
    }

    private double ln(double m) {
        return Math.log(Math.max(1e-6, m));
    }

    private double current(Player p, Stat s) {
        AttributeInstance inst = p.getAttribute(s.attr);
        if (inst == null) return neutral(s, 0);
        double base = inst.getBaseValue();
        AttributeModifier m = findModifier(inst, keyFor(s.id));
        if (m == null) return neutral(s, base);
        return switch (s.kind) {
            case MULT -> 1.0 + m.getAmount();
            case ABS -> base + m.getAmount();
            case ADD -> m.getAmount();
        };
    }

    private boolean isRolled(Player p, Stat s) {
        AttributeInstance inst = p.getAttribute(s.attr);
        return inst != null && findModifier(inst, keyFor(s.id)) != null;
    }

    private void applyStat(Player p, Stat s, double v) {
        AttributeInstance inst = p.getAttribute(s.attr);
        if (inst == null) return;
        v = Math.max(extLo(s), Math.min(extHi(s), v));
        v = Math.round(v * 1000.0) / 1000.0;

        NamespacedKey key = keyFor(s.id);
        AttributeModifier old = findModifier(inst, key);
        if (old != null) inst.removeModifier(old);

        double base = inst.getBaseValue();
        AttributeModifier mod = switch (s.kind) {
            case MULT -> new AttributeModifier(key, v - 1.0, AttributeModifier.Operation.ADD_SCALAR);
            case ABS -> new AttributeModifier(key, v - base, AttributeModifier.Operation.ADD_NUMBER);
            case ADD -> new AttributeModifier(key, v, AttributeModifier.Operation.ADD_NUMBER);
        };
        inst.addModifier(mod);
    }

    // ------------------------------------------------------------------ levels

    private double rand() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private double uniform(double lo, double hi) {
        return hi <= lo ? lo : lo + rand() * (hi - lo);
    }

    /** Index of the level closest to v. Ties go towards the middle (standard) level. */
    private int nearestIdx(Stat s, double v) {
        int[] order = {2, 1, 3, 0, 4};
        int best = 2;
        for (int i : order) {
            if (Math.abs(s.levels[i] - v) < Math.abs(s.levels[best] - v) - 1e-12) best = i;
        }
        return best;
    }

    /** 0 (poor) .. 4 (excellent) for level index idx. A level equal to the standard value always counts as average. */
    private int quality(Stat s, int idx) {
        if (Math.abs(s.levels[idx] - s.levels[LEVELS / 2]) < 1e-9) return LEVELS / 2;
        return s.good == Good.LOW ? (LEVELS - 1 - idx) : idx;
    }

    /** One step beyond the lowest / highest level (same gap as between levels). Used by jackpot rolls. */
    private double extHi(Stat s) {
        return s.hi() + (s.levels[LEVELS - 1] - s.levels[LEVELS - 2]);
    }

    private double extLo(Stat s) {
        return s.lo() - (s.levels[1] - s.levels[0]);
    }

    private double jackpotValue(Stat s) {
        return s.good == Good.LOW ? extLo(s) : extHi(s);
    }

    private boolean sameLevel(Stat s, int a, int b) {
        return Math.abs(s.levels[a] - s.levels[b]) < 1e-9;
    }

    /** -1 .. +1 */
    private double posOfIdx(Stat s, int idx) {
        return (quality(s, idx) - 2) / 2.0;
    }

    private int neutralIdx(Player p, Stat s) {
        return nearestIdx(s, neutral(s, baseOf(p, s)));
    }

    private int bestIdx(Stat s) {
        return s.good == Good.LOW ? 0 : LEVELS - 1;
    }

    private double pityOf(double avgPos) {
        return Math.max(0.0, -avgPos) * pityStrength();
    }

    /** Picks a level index. Pity skews towards better levels; avoid (>=0) makes that level unlikely. */
    private int pickIndex(Stat s, double pity, int avoid) {
        double[] w = new double[LEVELS];
        double total = 0;
        for (int i = 0; i < LEVELS; i++) {
            double k = (s.good == Good.NEUTRAL) ? 0 : pity * posOfIdx(s, i) * 2.0;
            w[i] = Math.exp(k);
            if (avoid >= 0 && sameLevel(s, i, avoid)) w[i] *= 0.02;
            total += w[i];
        }
        double r = rand() * total;
        for (int i = 0; i < LEVELS; i++) {
            r -= w[i];
            if (r <= 0) return i;
        }
        return LEVELS - 1;
    }

    // ------------------------------------------------------------------ balancing (5 levels, weighted, multiplicative)

    /**
     * Enumerates every combination of levels of a group whose weighted product stays within 1 +/- tol:
     * |sum(weight * sign * ln(multiplier))| <= ln(1 + tol)   (good-low stats count inverted).
     * Picks one at random. Pity favours better combinations. If {@code current} is given (single reroll)
     * combos that change fewer other stats are preferred and the target stat should change.
     * Returns null if no combination qualifies.
     */
    private int[] pickCombo(Player p, List<Stat> members, double tol, int[] fixed, double pity, int[] current, int target,
                            int jpPos, double jpVal) {
        int n = members.size();
        double[][] x = new double[n][LEVELS];
        for (int i = 0; i < n; i++) {
            Stat st = members.get(i);
            double base = baseOf(p, st);
            for (int l = 0; l < LEVELS; l++) {
                x[i][l] = st.weight * st.sign() * ln(1.0 + devOf(st, (i == jpPos) ? jpVal : st.levels[l], base));
            }
        }
        double limit = Math.log(1.0 + tol);

        int total = 1;
        for (int i = 0; i < n; i++) total *= LEVELS;

        List<int[]> combos = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double wsum = 0;
        for (int code = 0; code < total; code++) {
            int[] idx = new int[n];
            int c = code;
            boolean ok = true;
            double sum = 0, posSum = 0;
            for (int i = 0; i < n; i++) {
                idx[i] = c % LEVELS;
                c /= LEVELS;
                if (fixed != null && fixed[i] >= 0 && fixed[i] != idx[i]) { ok = false; break; }
                sum += x[i][idx[i]];
                posSum += posOfIdx(members.get(i), idx[i]);
            }
            if (!ok || Math.abs(sum) > limit) continue;

            double w = Math.exp(pity * 2.0 * (posSum / n));
            if (current != null) {
                int changed = 0;
                for (int i = 0; i < n; i++) if (i != target && !sameLevel(members.get(i), idx[i], current[i])) changed++;
                w *= Math.exp(-2.0 * changed);
                if (target >= 0 && sameLevel(members.get(target), idx[target], current[target])) w *= 0.02;
            }
            combos.add(idx);
            weights.add(w);
            wsum += w;
        }
        if (combos.isEmpty()) return null;

        double r = rand() * wsum;
        for (int i = 0; i < combos.size(); i++) {
            r -= weights.get(i);
            if (r <= 0) return combos.get(i);
        }
        return combos.get(combos.size() - 1);
    }

    private double netOf(Player p, String group) {
        double net = 0;
        for (Stat st : members(group)) {
            net += st.weight * st.sign() * ln(1.0 + devOf(st, current(p, st), baseOf(p, st)));
        }
        return Math.exp(net) - 1.0;
    }

    private double power(Player p, String group) {
        double sum = 0;
        for (Stat s : stats) {
            if (!s.counted()) continue;
            if (group != null && !group.equals(s.group)) continue;
            AttributeInstance inst = p.getAttribute(s.attr);
            if (inst == null) continue;
            double base = inst.getBaseValue();
            double m = (s.kind == Kind.ADD) ? 1.0 + (inst.getValue() - s.levels[LEVELS / 2]) / s.unit
                    : (base != 0 ? inst.getValue() / base : 1.0);
            sum += s.weight * s.sign() * ln(m);
        }
        return Math.exp(sum);
    }

    // ------------------------------------------------------------------ rolling

    private void rollAll(Player p) {
        Map<Stat, Integer> picked = new LinkedHashMap<>();
        double posSum = 0;
        int posN = 0;

        // independent and neutral stats first
        List<Stat> loose = new ArrayList<>(rollable().stream().filter(s -> !s.counted()).toList());
        Collections.shuffle(loose);
        for (Stat s : loose) {
            double pity = posN == 0 ? 0 : pityOf(posSum / posN);
            int idx = pickIndex(s, pity, -1);
            picked.put(s, idx);
            if (s.good != Good.NEUTRAL) {
                posSum += posOfIdx(s, idx);
                posN++;
            }
        }

        // jackpot: one stat is locked to its best level, the rest of its group has to pay for it
        Stat jackpot = null;
        if (getConfig().getBoolean("jackpot.enabled", true) && rand() < getConfig().getDouble("jackpot.chance", 0.03)) {
            List<Stat> cands = rollable().stream().filter(Stat::counted).toList();
            if (!cands.isEmpty()) jackpot = cands.get(ThreadLocalRandom.current().nextInt(cands.size()));
        }

        List<String> order = new ArrayList<>(GROUPS);
        Collections.shuffle(order);
        for (String group : order) {
            List<Stat> mem = members(group);
            if (mem.isEmpty()) continue;
            double pity = posN == 0 ? 0 : pityOf(posSum / posN);

            int[] fixed = new int[mem.size()];
            Arrays.fill(fixed, -1);
            boolean hasJackpot = jackpot != null && group.equals(jackpot.group);
            if (hasJackpot) fixed[mem.indexOf(jackpot)] = bestIdx(jackpot);

            int[] pick = pickCombo(p, mem, tolerance(group), fixed, pity, null, -1,
                    hasJackpot ? mem.indexOf(jackpot) : -1, hasJackpot ? jackpotValue(jackpot) : 0.0);
            if (pick == null && hasJackpot) { // the group cannot afford this jackpot
                jackpot = null;
                Arrays.fill(fixed, -1);
                pick = pickCombo(p, mem, tolerance(group), fixed, pity, null, -1, -1, 0.0);
            }
            if (pick == null) {
                pick = new int[mem.size()];
                for (int i = 0; i < pick.length; i++) pick[i] = neutralIdx(p, mem.get(i));
            }
            for (int i = 0; i < pick.length; i++) {
                picked.put(mem.get(i), pick[i]);
                posSum += posOfIdx(mem.get(i), pick[i]);
                posN++;
            }
        }

        Stat jp = jackpot;
        for (var e : picked.entrySet()) {
            Stat s = e.getKey();
            applyStat(p, s, s == jp ? jackpotValue(s) : s.levels[e.getValue()]);
        }
        rerollLinkOffsets(p);
        clearLowHealthChoice(p);

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (jackpot != null) pdc.set(keyFor("jackpot_stat"), PersistentDataType.STRING, jackpot.id);
        else pdc.remove(keyFor("jackpot_stat"));
        finish(p);

        if (jackpot != null) {
            p.sendMessage(Component.text("JACKPOT! " + pretty(jackpot.id) + " rolled beyond its normal range (the others paid for it).",
                    NamedTextColor.LIGHT_PURPLE));
            if (getConfig().getBoolean("jackpot.broadcast", true)) {
                Bukkit.getServer().broadcast(Component.text(p.getName() + " hit the JACKPOT on " + pretty(jackpot.id) + "!",
                        NamedTextColor.LIGHT_PURPLE));
            }
        }
    }

    private void rollOne(Player p, Stat st) {
        if (st.linkedTo != null) st = findStat(st.linkedTo); // rerolling one reach rerolls both

        double posSum = 0;
        int posN = 0;
        for (Stat o : rollable()) {
            if (o == st || o.good == Good.NEUTRAL) continue;
            posSum += posOfIdx(o, nearestIdx(o, current(p, o)));
            posN++;
        }
        double pity = posN == 0 ? 0 : pityOf(posSum / posN);

        if (st.counted()) {
            List<Stat> mem = members(st.group);
            int[] cur = new int[mem.size()];
            for (int i = 0; i < cur.length; i++) cur[i] = nearestIdx(mem.get(i), current(p, mem.get(i)));
            int[] pick = pickCombo(p, mem, tolerance(st.group), null, pity, cur, mem.indexOf(st), -1, 0.0);
            if (pick == null) {
                rollAll(p);
                return;
            }
            for (int i = 0; i < pick.length; i++) applyStat(p, mem.get(i), mem.get(i).levels[pick[i]]);
        } else {
            int avoid = isRolled(p, st) ? nearestIdx(st, current(p, st)) : -1;
            applyStat(p, st, st.levels[pickIndex(st, pity, avoid)]);
        }

        final String rolledId = st.id;
        if (stats.stream().anyMatch(o -> rolledId.equals(o.linkedTo))) rerollLinkOffsets(p);
        clearLowHealthChoice(p);
        p.getPersistentDataContainer().remove(keyFor("jackpot_stat"));
        finish(p);
    }

    private void resetPlayer(Player p) {
        clearLinkOffsets(p);
        clearLowHealthChoice(p);
        p.getPersistentDataContainer().remove(keyFor("jackpot_stat"));
        for (Stat s : available()) removeMod(p, s.attr, s.id);
        finish(p);
    }

    /** Recomputes everything derived from the rolls, then heals. */
    private void finish(Player p) {
        linkStats(p);
        applyScaleBonus(p);
        applyScaleTradeoffs(p);
        applyLowHealthBonus(p);
        applyJumpSafeFall(p);
        fixJump(p);
        applyTitle(p);
        updateNegatives(p);
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) p.setHealth(hp.getValue());
    }

    // ------------------------------------------------------------------ linked reach

    private double linkOffset(Player p, Stat s, boolean reroll) {
        NamespacedKey key = keyFor("offset_" + s.id);
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Double stored = pdc.get(key, PersistentDataType.DOUBLE);
        if (stored == null || reroll) {
            double lo = getConfig().getDouble("block-reach-bonus.min", 1.0);
            double hi = getConfig().getDouble("block-reach-bonus.max", 2.0);
            if (hi < lo) { double tmp = lo; lo = hi; hi = tmp; }
            stored = Math.round(uniform(lo, hi) * 100.0) / 100.0;
            pdc.set(key, PersistentDataType.DOUBLE, stored);
        }
        return stored;
    }

    private void rerollLinkOffsets(Player p) {
        for (Stat s : stats) if (s.linkedTo != null) linkOffset(p, s, true);
    }

    private void clearLinkOffsets(Player p) {
        for (Stat s : stats) if (s.linkedTo != null) p.getPersistentDataContainer().remove(keyFor("offset_" + s.id));
    }

    /** Block reach = entity reach + a rolled bonus (halved if entity reach is above normal). */
    private void linkStats(Player p) {
        for (Stat s : stats) {
            if (s.linkedTo == null || s.attr == null) continue;
            Stat src = findStat(s.linkedTo);
            if (src == null || src.attr == null) continue;
            AttributeInstance inst = p.getAttribute(s.attr), srcInst = p.getAttribute(src.attr);
            if (inst == null || srcInst == null) continue;

            removeMod(p, s.attr, s.id);

            if (findModifier(srcInst, keyFor(src.id)) != null) {
                double srcValue = current(p, src);
                double bonus = linkOffset(p, s, false);
                if (srcValue > srcInst.getBaseValue() + 1e-9) {
                    bonus *= getConfig().getDouble("block-reach-bonus.above-normal-multiplier", 0.5);
                }
                inst.addModifier(new AttributeModifier(keyFor(s.id), srcValue + bonus - inst.getBaseValue(),
                        AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    // ------------------------------------------------------------------ compensation rules

    private double scaleBonusAmount(Player p) {
        Stat sc = findStat("scale"), sp = findStat("movement_speed");
        if (sc == null || sp == null || sc.attr == null || sp.attr == null) return 0.0;
        if (current(p, sc) <= 1.0) return 0.0;
        double below = getConfig().getDouble("scale-speed-bonus.speed-at-or-below-normal", 0.10);
        double above = getConfig().getDouble("scale-speed-bonus.speed-above-normal", 0.05);
        return current(p, sp) <= 1.0 ? below : above;
    }

    private void applyScaleBonus(Player p) {
        Stat sp = findStat("movement_speed");
        if (sp == null || sp.attr == null) return;
        AttributeInstance inst = p.getAttribute(sp.attr);
        if (inst == null) return;
        removeMod(p, sp.attr, SCALE_BONUS_ID);
        double bonus = scaleBonusAmount(p);
        if (bonus > 0) {
            inst.addModifier(new AttributeModifier(keyFor(SCALE_BONUS_ID), bonus, AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    /** Big players resist knockback; small players are more fragile. */
    private void applyScaleTradeoffs(Player p) {
        Attribute kb = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("knockback_resistance"));
        Stat hp = findStat("max_health");
        removeMod(p, kb, "scale_kb");
        if (hp != null) {
            removeMod(p, hp.attr, "scale_hp");
            removeMod(p, hp.attr, "giant_hp");
        }

        Stat sc = findStat("scale");
        if (sc == null || sc.attr == null) return;
        double s = current(p, sc);

        if (isRolled(p, sc) && nearestIdx(sc, s) == LEVELS - 1 && hp != null && hp.attr != null) {
            AttributeInstance inst = p.getAttribute(hp.attr);
            double bonus = getConfig().getDouble("scale-tradeoffs.giant-health-bonus", 0.20);
            if (inst != null && bonus > 0) {
                inst.addModifier(new AttributeModifier(keyFor("giant_hp"), bonus, AttributeModifier.Operation.ADD_SCALAR));
            }
        }

        if (s > 1.0 && kb != null) {
            AttributeInstance inst = p.getAttribute(kb);
            double per = getConfig().getDouble("scale-tradeoffs.knockback-resistance-per-scale", 0.8);
            if (inst != null && per > 0) {
                inst.addModifier(new AttributeModifier(keyFor("scale_kb"), (s - 1.0) * per, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
        if (s < 1.0 && hp != null && hp.attr != null) {
            AttributeInstance inst = p.getAttribute(hp.attr);
            double pen = getConfig().getDouble("scale-tradeoffs.small-health-penalty-per-scale", 0.4);
            if (inst != null && pen > 0) {
                inst.addModifier(new AttributeModifier(keyFor("scale_hp"), -(1.0 - s) * pen, AttributeModifier.Operation.ADD_SCALAR));
            }
        }
    }

    private List<Stat> lowHealthCandidates() {
        List<String> ids = getConfig().getStringList("low-health-bonus.stats");
        if (ids.isEmpty()) ids = List.of("attack_knockback", "attack_speed");
        List<Stat> out = new ArrayList<>();
        for (String id : ids) {
            Stat s = findStat(id);
            if (s != null && s.attr != null) out.add(s);
        }
        return out;
    }

    private void clearLowHealthChoice(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.remove(keyFor("low_hp_stat"));
        pdc.remove(keyFor("low_hp_value"));
    }

    private boolean lowHealth(Player p) {
        Stat hp = findStat("max_health");
        if (hp == null || hp.attr == null) return false;
        return current(p, hp) < getConfig().getDouble("low-health-bonus.threshold", 0.8);
    }

    private void applyLowHealthBonus(Player p) {
        for (Stat s : stats) if (s.attr != null) removeMod(p, s.attr, LOW_HP_ID);

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        NamespacedKey kStat = keyFor("low_hp_stat"), kVal = keyFor("low_hp_value");
        if (!lowHealth(p)) {
            clearLowHealthChoice(p);
            return;
        }

        List<Stat> cands = lowHealthCandidates();
        if (cands.isEmpty()) return;

        String id = pdc.get(kStat, PersistentDataType.STRING);
        Double val = pdc.get(kVal, PersistentDataType.DOUBLE);
        Stat chosen = id == null ? null : findStat(id);
        if (chosen == null || val == null || !cands.contains(chosen)) {
            chosen = cands.get(ThreadLocalRandom.current().nextInt(cands.size()));
            double lo = getConfig().getDouble("low-health-bonus.min", 0.10);
            double hi = getConfig().getDouble("low-health-bonus.max", 0.20);
            if (hi < lo) { double tmp = lo; lo = hi; hi = tmp; }
            val = Math.round(uniform(lo, hi) * 100.0) / 100.0;
            pdc.set(kStat, PersistentDataType.STRING, chosen.id);
            pdc.set(kVal, PersistentDataType.DOUBLE, val);
        }

        AttributeInstance inst = p.getAttribute(chosen.attr);
        if (inst != null) {
            AttributeModifier mod = (chosen.kind == Kind.ADD)
                    ? new AttributeModifier(keyFor(LOW_HP_ID), val * chosen.unit, AttributeModifier.Operation.ADD_NUMBER)
                    : new AttributeModifier(keyFor(LOW_HP_ID), val, AttributeModifier.Operation.ADD_SCALAR);
            inst.addModifier(mod);
        }
    }

    /**
     * With a jump boost (jump strength above normal), fall damage only starts after your jump height + 3 blocks
     * (at least; a better safe-fall roll still counts).
     */
    private void applyJumpSafeFall(Player p) {
        Attribute fallAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("safe_fall_distance"));
        if (fallAttr == null) return;
        AttributeInstance fall = p.getAttribute(fallAttr);
        if (fall == null) return;
        removeMod(p, fallAttr, JUMP_FALL_ID);

        Stat g = findStat("gravity"), j = findStat("jump_strength");
        if (g == null || j == null || g.attr == null || j.attr == null || !isRolled(p, j)) return;
        if (current(p, j) <= 1.0) return;
        AttributeInstance gi = p.getAttribute(g.attr), ji = p.getAttribute(j.attr);
        if (gi == null || ji == null) return;

        double need = jumpPeak(gi.getValue(), ji.getValue()) + getConfig().getDouble("jump-safe-fall.extra-blocks", 3.0);
        double base = fall.getBaseValue();
        double now = fall.getValue();
        if (now <= 0 || base <= 0 || need <= now) return;
        // final = (base + add) * (now / base)  ->  solve for add
        double add = need * base / now - base;
        fall.addModifier(new AttributeModifier(keyFor(JUMP_FALL_ID), add, AttributeModifier.Operation.ADD_NUMBER));
    }

    // ------------------------------------------------------------------ jump guarantee

    private double jumpPeak(double gravity, double jump) {
        double y = 0, vy = jump, peak = 0;
        for (int i = 0; i < 2000 && vy > 0; i++) {
            y += vy;
            peak = Math.max(peak, y);
            vy = (vy - gravity) * 0.98;
        }
        return peak;
    }

    /** Raises step height to 1.0 for weak jumpers - but never while sneaking. */
    private void fixJump(Player p) {
        Attribute stepAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(STEP_ID));
        if (stepAttr == null) return;
        AttributeInstance step = p.getAttribute(stepAttr);
        if (step == null) return;

        removeMod(p, stepAttr, STEP_ID);
        if (p.isSneaking()) return;

        Stat g = findStat("gravity"), j = findStat("jump_strength");
        if (g == null || j == null || g.attr == null || j.attr == null) return;
        AttributeInstance gi = p.getAttribute(g.attr), ji = p.getAttribute(j.attr);
        if (gi == null || ji == null) return;

        double peak = jumpPeak(gi.getValue(), ji.getValue());
        if (peak < getConfig().getDouble("min-jump-height", 1.05)) {
            double need = 1.0 - step.getBaseValue();
            if (need > 0) {
                step.addModifier(new AttributeModifier(keyFor(STEP_ID), need, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    // ------------------------------------------------------------------ titles

    private boolean hasRolls(Player p) {
        for (Stat s : rollable()) if (isRolled(p, s)) return true;
        return false;
    }

    /**
     * Archetype title. Only the main combat and mobility stats can earn one, and only when that stat is
     * clearly strong (titles.min-strength). Utility quirks and independent stats never give a title.
     */
    private String computeTitle(Player p) {
        double minStrength = getConfig().getDouble("titles.min-strength", 0.15);
        Stat dom = null, worst = null;
        double domX = 0, worstX = 0;
        for (Stat s : stats) {
            if (!s.counted() || !("combat".equals(s.group) || "mobility".equals(s.group))) continue;
            double x = s.weight * s.sign() * ln(1.0 + devOf(s, current(p, s), baseOf(p, s)));
            if (worst == null || x < worstX) { worst = s; worstX = x; }
            if (dom == null || x > domX) { dom = s; domX = x; }
        }

        String name;
        if (dom == null || domX < minStrength) {
            name = "Everyman";
        } else {
            String top = dom.id;
            String low = (worst != null && worstX < -0.05) ? worst.id : "";
            boolean offence = top.equals("attack_knockback") || top.equals("attack_speed") || top.equals("entity_interaction_range");
            boolean agile = top.equals("movement_speed") || top.equals("jump_strength") || top.equals("gravity");
            if (offence && low.equals("max_health")) {
                name = "Glass Cannon";
            } else if (top.equals("max_health")
                    && (low.equals("movement_speed") || low.equals("attack_speed") || low.equals("jump_strength"))) {
                name = "Juggernaut";
            } else if (agile && low.equals("max_health")) {
                name = "Scout";
            } else {
                name = switch (top) {
                    case "entity_interaction_range" -> "Lancer";
                    case "attack_speed" -> "Duelist";
                    case "attack_knockback" -> "Bruiser";
                    case "max_health" -> "Tank";
                    case "gravity" -> "Featherweight";
                    case "jump_strength" -> "Leaper";
                    case "movement_speed" -> "Sprinter";
                    default -> "Wanderer";
                };
            }
        }

        String prefix = "";
        Stat sc = findStat("scale");
        if (sc != null && sc.attr != null && isRolled(p, sc)) {
            int si = nearestIdx(sc, current(p, sc));
            if (si == LEVELS - 1) prefix = "Giant ";
            else if (si == 0) prefix = "Tiny ";
        }
        if (p.getPersistentDataContainer().get(keyFor("jackpot_stat"), PersistentDataType.STRING) != null) {
            prefix = "JACKPOT " + prefix;
        }
        return prefix + name;
    }

    private NamedTextColor titleColor(String title) {
        if (title.startsWith("JACKPOT")) return NamedTextColor.LIGHT_PURPLE;
        if (title.contains("Glass Cannon")) return NamedTextColor.DARK_RED;
        if (title.contains("Juggernaut")) return NamedTextColor.DARK_PURPLE;
        if (title.contains("Scout")) return NamedTextColor.AQUA;
        if (title.contains("Bruiser")) return NamedTextColor.RED;
        if (title.contains("Lancer")) return NamedTextColor.DARK_AQUA;
        if (title.contains("Duelist")) return NamedTextColor.GOLD;
        if (title.contains("Tank")) return NamedTextColor.DARK_GREEN;
        if (title.contains("Featherweight")) return NamedTextColor.WHITE;
        if (title.contains("Leaper")) return NamedTextColor.GREEN;
        if (title.contains("Sprinter")) return NamedTextColor.YELLOW;
        if (title.contains("Acrobat")) return NamedTextColor.BLUE;
        if (title.contains("Stuntman")) return NamedTextColor.DARK_BLUE;
        if (title.contains("Fireproof")) return NamedTextColor.RED;
        if (title.contains("Shadow")) return NamedTextColor.DARK_GRAY;
        if (title.contains("Digger")) return NamedTextColor.GOLD;
        if (title.contains("Diver")) return NamedTextColor.BLUE;
        if (title.contains("Everyman")) return NamedTextColor.GRAY;
        return NamedTextColor.AQUA;
    }

    /** "[Title]" in the title's colour. */
    private Component titleTag(Player p) {
        String title = computeTitle(p);
        Component c = Component.text("[" + title + "]", titleColor(title));
        if (title.startsWith("JACKPOT")) c = c.decorate(TextDecoration.BOLD);
        return c;
    }

    private void applyTitle(Player p) {
        if (!hasRolls(p)) {
            titleCache.remove(p.getUniqueId());
            if (getConfig().getBoolean("titles.tab-list", true)) p.playerListName(null);
            return;
        }
        Component tag = titleTag(p);
        titleCache.put(p.getUniqueId(), tag);
        if (getConfig().getBoolean("titles.tab-list", true)) {
            p.playerListName(Component.text(p.getName() + " ").append(tag));
        }
    }

    // ------------------------------------------------------------------ player-driven reroll

    private List<String> dblKeys() {
        List<String> l = new ArrayList<>(List.of("low_hp_value"));
        for (Stat st : stats) if (st.linkedTo != null) l.add("offset_" + st.id);
        return l;
    }

    private List<String> strKeys() {
        return List.of("low_hp_stat", "jackpot_stat");
    }

    private Snapshot takeSnapshot(Player p) {
        Snapshot s = new Snapshot();
        for (Stat st : rollable()) {
            if (isRolled(p, st)) s.vals.put(st.id, current(p, st));
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        for (String k : dblKeys()) {
            Double d = pdc.get(keyFor(k), PersistentDataType.DOUBLE);
            if (d != null) s.dbl.put(k, d);
        }
        for (String k : strKeys()) {
            String v = pdc.get(keyFor(k), PersistentDataType.STRING);
            if (v != null) s.str.put(k, v);
        }
        return s;
    }

    private void restore(Player p, Snapshot s) {
        for (Stat st : rollable()) {
            Double v = s.vals.get(st.id);
            if (v != null) applyStat(p, st, v);
            else removeMod(p, st.attr, st.id);
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        for (String k : dblKeys()) {
            Double d = s.dbl.get(k);
            if (d != null) pdc.set(keyFor(k), PersistentDataType.DOUBLE, d);
            else pdc.remove(keyFor(k));
        }
        for (String k : strKeys()) {
            String v = s.str.get(k);
            if (v != null) pdc.set(keyFor(k), PersistentDataType.STRING, v);
            else pdc.remove(keyFor(k));
        }
        finish(p);
    }

    private int rerollCost() {
        return getConfig().getInt("player-reroll.xp-levels", 100);
    }

    private void handleRoll(Player p, String what) {
        if (!getConfig().getBoolean("player-reroll.enabled", true)) {
            p.sendMessage(Component.text("Rerolling is disabled on this server.", NamedTextColor.RED));
            return;
        }
        Stat st = findStat(what);
        if (st == null || st.attr == null) {
            p.sendMessage(Component.text("Unknown stat: " + what, NamedTextColor.RED));
            return;
        }
        UUID id = p.getUniqueId();
        if (pending.containsKey(id)) {
            p.sendMessage(Component.text("Choose KEEP or REVERT for your last reroll first (open /gamble).", NamedTextColor.RED));
            return;
        }

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        NamespacedKey dayKey = keyFor("reroll_day"), cntKey = keyFor("reroll_count");
        long today = System.currentTimeMillis() / 86_400_000L;
        Long day = pdc.get(dayKey, PersistentDataType.LONG);
        Integer cnt = pdc.get(cntKey, PersistentDataType.INTEGER);
        int used = (day != null && day == today && cnt != null) ? cnt : 0;
        int limit = getConfig().getInt("player-reroll.daily-limit", 3);
        if (limit > 0 && used >= limit) {
            p.sendMessage(Component.text("You used all " + limit + " rerolls for today.", NamedTextColor.RED));
            return;
        }

        int cost = rerollCost();
        boolean pays = cost > 0 && p.getGameMode() != GameMode.CREATIVE;
        if (pays && p.getLevel() < cost) {
            p.sendMessage(Component.text("A reroll costs " + cost + " XP levels.", NamedTextColor.RED));
            return;
        }
        if (pays) p.giveExpLevels(-cost);

        pdc.set(dayKey, PersistentDataType.LONG, today);
        pdc.set(cntKey, PersistentDataType.INTEGER, used + 1);

        Snapshot snap = takeSnapshot(p);
        pending.put(id, snap);
        rollOne(p, st);

        int secs = Math.max(3, getConfig().getInt("player-reroll.decision-seconds", 10));
        Stat shown = st.linkedTo != null ? findStat(st.linkedTo) : st;
        if (animationEnabled() && shown != null) playReel(p, shown, () -> openMenu(p, p));
        else Bukkit.getScheduler().runTask(this, () -> openMenu(p, p));
        p.sendMessage(Component.text("Keep the new roll? (" + secs + "s)  ", NamedTextColor.GOLD)
                .append(Component.text("[KEEP]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/gamble keep")))
                .append(Component.text("  "))
                .append(Component.text("[REVERT]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/gamble revert"))));

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pending.get(id) == snap) {
                pending.remove(id);
                if (p.isOnline()) p.sendMessage(Component.text("Time's up - you kept the new roll.", NamedTextColor.GRAY));
            }
        }, secs * 20L + (animationEnabled() ? ANIMATION_TICKS : 0L));
    }

    // ------------------------------------------------------------------ stats menu

    private Component line(String text, NamedTextColor c) {
        return Component.text(text, c).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack icon(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private NamedTextColor ratingColor(int level) {
        return switch (level) {
            case 0 -> NamedTextColor.RED;
            case 1 -> NamedTextColor.GOLD;
            case 2 -> NamedTextColor.YELLOW;
            case 3 -> NamedTextColor.GREEN;
            default -> NamedTextColor.AQUA;
        };
    }

    /** 0 (poor) .. 4 (excellent), -1 if not rolled yet. */
    private int ratingLevel(Player p, Stat s) {
        Stat src = s.linkedTo != null ? findStat(s.linkedTo) : s;
        if (src == null || src.attr == null || !isRolled(p, src)) return -1;
        return quality(src, nearestIdx(src, current(p, src)));
    }

    private int remainingRerolls(Player p) {
        int limit = getConfig().getInt("player-reroll.daily-limit", 3);
        if (limit <= 0) return -1;
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        long today = System.currentTimeMillis() / 86_400_000L;
        Long day = pdc.get(keyFor("reroll_day"), PersistentDataType.LONG);
        Integer cnt = pdc.get(keyFor("reroll_count"), PersistentDataType.INTEGER);
        int used = (day != null && day == today && cnt != null) ? cnt : 0;
        return Math.max(0, limit - used);
    }

    private ItemStack statItem(Stat s, Player target, boolean admin, boolean canRoll) {
        Material mat = Material.matchMaterial(ICONS.getOrDefault(s.id, "PAPER"));
        if (mat == null) mat = Material.PAPER;

        List<Component> lore = new ArrayList<>();
        lore.add(line(DESC.getOrDefault(s.id, ""), NamedTextColor.GRAY));

        if (s.good == Good.NEUTRAL) {
            String[] sizes = {"Tiny", "Small", "Medium", "Large", "Giant"};
            int si = nearestIdx(s, current(target, s));
            lore.add(line("Size: " + sizes[si], NamedTextColor.AQUA));
        } else {
            int level = ratingLevel(target, s);
            if (level < 0) {
                lore.add(line("Not rolled yet", NamedTextColor.DARK_GRAY));
            } else {
                NamedTextColor c = ratingColor(level);
                lore.add(Component.text("Roll: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("\u25A0".repeat(level + 1), c))
                        .append(Component.text("\u25A1".repeat(4 - level), NamedTextColor.DARK_GRAY))
                        .append(Component.text(" " + RATING_WORDS[level], c)));
            }
        }

        String jpStat = target.getPersistentDataContainer().get(keyFor("jackpot_stat"), PersistentDataType.STRING);
        if (s.id.equals(jpStat)) lore.add(line("JACKPOT - beyond the normal range", NamedTextColor.LIGHT_PURPLE));

        if (admin) {
            AttributeInstance inst = target.getAttribute(s.attr);
            if (inst != null) {
                double normal = inst.getBaseValue(), now = inst.getValue();
                lore.add(line("", NamedTextColor.GRAY));
                if (normal != 0) {
                    lore.add(line(String.format("Normal %.3f -> now %.3f (%+.0f%%)", normal, now, (now / normal - 1.0) * 100.0),
                            NamedTextColor.WHITE));
                } else {
                    lore.add(line(String.format("Now %.2f (%+.2f)", now, now - normal), NamedTextColor.WHITE));
                }
                if (s.kind == Kind.ADD && s.linkedTo == null) {
                    lore.add(line(String.format("Roll value %+.2f (negative = custom penalty)", current(target, s)),
                            NamedTextColor.WHITE));
                }
                if (s.linkedTo != null) {
                    lore.add(line("Follows " + pretty(s.linkedTo) + " + bonus", NamedTextColor.DARK_AQUA));
                } else {
                    StringBuilder lv = new StringBuilder("Levels ");
                    for (int i = 0; i < LEVELS; i++) {
                        if (i > 0) lv.append(" / ");
                        lv.append(String.format("%.2f", s.levels[i]).replaceAll("\\.?0+$", ""));
                    }
                    lore.add(line(lv.toString(), NamedTextColor.DARK_AQUA));
                    lore.add(line(String.format("Weight %.1f, group %s", s.weight,
                            s.group == null ? "none" : s.group), NamedTextColor.DARK_AQUA));
                }
            }
        }
        if (canRoll && s.attr != null) {
            lore.add(line("", NamedTextColor.GRAY));
            lore.add(line("Shift-click to reroll", NamedTextColor.DARK_GRAY));
        }
        return icon(mat, line(pretty(s.id), NamedTextColor.WHITE), lore);
    }

    private List<Component> adminLines(Player t) {
        List<Component> out = new ArrayList<>();
        out.add(line("Operator details", NamedTextColor.GOLD));
        for (String group : GROUPS) {
            if (members(group).isEmpty()) continue;
            out.add(line(String.format("%s: balance %+.1f%% (limit +/-%.0f%%), power x%.2f", pretty(group),
                    netOf(t, group) * 100.0, tolerance(group) * 100.0, power(t, group)), NamedTextColor.AQUA));
        }
        out.add(line(String.format("Total power: x%.2f", power(t, null)), NamedTextColor.GREEN));

        double sb = scaleBonusAmount(t);
        if (sb > 0) out.add(line(String.format("Scale bonus: +%.0f%% movement speed", sb * 100.0), NamedTextColor.YELLOW));

        if (lowHealth(t)) {
            PersistentDataContainer pdc = t.getPersistentDataContainer();
            String id = pdc.get(keyFor("low_hp_stat"), PersistentDataType.STRING);
            Double val = pdc.get(keyFor("low_hp_value"), PersistentDataType.DOUBLE);
            if (id != null && val != null) {
                out.add(line(String.format("Low health bonus: +%.0f%% %s", val * 100.0, pretty(id)), NamedTextColor.YELLOW));
            }
        }

        Stat g = findStat("gravity"), j = findStat("jump_strength");
        if (g != null && j != null && g.attr != null && j.attr != null) {
            AttributeInstance gi = t.getAttribute(g.attr), ji = t.getAttribute(j.attr);
            if (gi != null && ji != null) {
                String extra = "";
                Attribute stepAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(STEP_ID));
                AttributeInstance st = stepAttr == null ? null : t.getAttribute(stepAttr);
                if (st != null) extra = String.format(", step %.2f", st.getValue());
                out.add(line(String.format("Jump ~%.2f blocks%s%s", jumpPeak(gi.getValue(), ji.getValue()), extra,
                        needsPillarAssist(t) ? ", pillar assist on" : ""), NamedTextColor.YELLOW));
            }
        }
        return out;
    }

    private void placeGroup(StatsMenu menu, Player target, boolean admin, boolean canRoll,
                            int labelSlot, String label, NamedTextColor color, int[] slots, String... ids) {
        menu.inv.setItem(labelSlot, icon(Material.PAPER, line(label, color), null));
        for (int i = 0; i < ids.length && i < slots.length; i++) {
            Stat s = findStat(ids[i]);
            if (s == null || s.attr == null) continue;
            menu.slots.put(slots[i], s);
            menu.inv.setItem(slots[i], statItem(s, target, admin, canRoll));
        }
    }

    private void openMenu(Player viewer, Player target) {
        boolean admin = viewer.hasPermission("gamble.admin");
        boolean own = viewer.getUniqueId().equals(target.getUniqueId());
        boolean canRoll = own && getConfig().getBoolean("player-reroll.enabled", true) && viewer.hasPermission("gamble.roll");

        StatsMenu menu = new StatsMenu(viewer, target);
        Inventory inv = Bukkit.createInventory(menu, 54, Component.text(target.getName() + "'s Stats"));
        menu.inv = inv;

        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        List<Component> head = new ArrayList<>();
        if (hasRolls(target)) {
            head.add(Component.text("Title: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(titleTag(target).decoration(TextDecoration.ITALIC, false)));
            String jp = target.getPersistentDataContainer().get(keyFor("jackpot_stat"), PersistentDataType.STRING);
            if (jp != null) head.add(line("JACKPOT: " + pretty(jp), NamedTextColor.LIGHT_PURPLE));
        } else {
            head.add(line("Not gambled yet", NamedTextColor.GRAY));
        }
        inv.setItem(4, icon(Material.NETHER_STAR, line(target.getName(), NamedTextColor.GOLD), head));

        for (GroupLayout g : LAYOUT) {
            placeGroup(menu, target, admin, canRoll, g.labelSlot(), g.label(), g.color(), g.slots(), g.ids());
        }

        List<Component> info = new ArrayList<>();
        info.add(line("Fuller bars = better rolls.", NamedTextColor.GRAY));
        info.add(line("Stats are balanced: a great roll", NamedTextColor.GRAY));
        info.add(line("somewhere usually costs you elsewhere.", NamedTextColor.GRAY));
        if (needsPillarAssist(target) && getConfig().getBoolean("pillar-assist.enabled", true)) {
            info.add(line("", NamedTextColor.GRAY));
            info.add(line("Low jump: sneak + look down +", NamedTextColor.YELLOW));
            info.add(line("right-click a block to build up.", NamedTextColor.YELLOW));
        }
        if (canRoll) {
            int left = remainingRerolls(target);
            info.add(line("", NamedTextColor.GRAY));
            info.add(line("Shift-click a stat to reroll it", NamedTextColor.GOLD));
            info.add(line("Cost: " + rerollCost() + " XP levels", NamedTextColor.GOLD));
            info.add(line(left < 0 ? "Rerolls today: unlimited" : "Rerolls left today: " + left, NamedTextColor.GOLD));
        }
        inv.setItem(45, icon(Material.BOOK, line("How it works", NamedTextColor.WHITE), info));

        if (admin) {
            inv.setItem(49, icon(Material.COMMAND_BLOCK, line("Operator info", NamedTextColor.GOLD), adminLines(target)));
        }

        if (own && pending.containsKey(target.getUniqueId())) {
            inv.setItem(48, icon(Material.LIME_CONCRETE, line("KEEP the new roll", NamedTextColor.GREEN), null));
            inv.setItem(50, icon(Material.RED_CONCRETE, line("REVERT to the old roll", NamedTextColor.RED), null));
        }
        inv.setItem(53, icon(Material.BARRIER, line("Close", NamedTextColor.RED), null));

        viewer.openInventory(inv);
    }

    private void keepRoll(Player p) {
        if (pending.remove(p.getUniqueId()) == null) {
            p.sendMessage(Component.text("Nothing to keep.", NamedTextColor.RED));
            return;
        }
        p.sendMessage(Component.text("Kept your new roll.", NamedTextColor.GREEN));
    }

    private void revertRoll(Player p) {
        Snapshot snap = pending.remove(p.getUniqueId());
        if (snap == null) {
            p.sendMessage(Component.text("Nothing to revert.", NamedTextColor.RED));
            return;
        }
        restore(p, snap);
        p.sendMessage(Component.text("Reverted to your previous stats (the reroll still counts).", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RollMenu) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getView().getTopInventory().getHolder() instanceof StatsMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getRawSlot();
        if (slot == 53) {
            p.closeInventory();
            return;
        }
        if (!menu.viewer.getUniqueId().equals(menu.target.getUniqueId())) return; // read-only view

        if (slot == 48 && pending.containsKey(p.getUniqueId())) {
            keepRoll(p);
            Bukkit.getScheduler().runTask(this, () -> openMenu(p, p));
            return;
        }
        if (slot == 50 && pending.containsKey(p.getUniqueId())) {
            revertRoll(p);
            Bukkit.getScheduler().runTask(this, () -> openMenu(p, p));
            return;
        }

        Stat s = menu.slots.get(slot);
        if (s != null && e.getClick() == ClickType.SHIFT_LEFT && p.hasPermission("gamble.roll")) {
            p.closeInventory();
            Bukkit.getScheduler().runTask(this, () -> handleRoll(p, s.id));
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof StatsMenu || holder instanceof RollMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onRollClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof RollMenu m) m.closed = true;
    }

    // ------------------------------------------------------------------ roulette animations

    private boolean animationEnabled() {
        return getConfig().getBoolean("animation.enabled", true);
    }

    private ItemStack reelPane(Stat s, int idx) {
        if (s.good == Good.NEUTRAL) {
            String[] sizes = {"Tiny", "Small", "Medium", "Large", "Giant"};
            return icon(Material.PURPLE_STAINED_GLASS_PANE, line(sizes[idx], NamedTextColor.LIGHT_PURPLE), null);
        }
        int q = quality(s, idx);
        NamedTextColor c = ratingColor(q);
        Component name = Component.text("\u25A0".repeat(q + 1), c)
                .append(Component.text("\u25A1".repeat(4 - q), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + RATING_WORDS[q], c))
                .decoration(TextDecoration.ITALIC, false);
        return icon(PANES[q], name, null);
    }

    private ItemStack spinPane() {
        int q = ThreadLocalRandom.current().nextInt(LEVELS);
        return icon(PANES[q], line("Rolling...", NamedTextColor.GRAY), null);
    }

    /** Slot-machine style reel for one stat. The new value is already applied; this only reveals it. */
    private void playReel(Player p, Stat s, Runnable after) {
        if (!animationEnabled()) {
            after.run();
            return;
        }
        RollMenu menu = new RollMenu();
        Inventory inv = Bukkit.createInventory(menu, 27, Component.text("Rolling " + pretty(s.id) + "..."));
        menu.inv = inv;

        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(4, icon(Material.YELLOW_STAINED_GLASS_PANE, line("\u25BC", NamedTextColor.YELLOW), null));
        inv.setItem(22, icon(Material.YELLOW_STAINED_GLASS_PANE, line("\u25B2", NamedTextColor.YELLOW), null));
        Material mat = Material.matchMaterial(ICONS.getOrDefault(s.id, "PAPER"));
        inv.setItem(0, icon(mat == null ? Material.PAPER : mat, line(pretty(s.id), NamedTextColor.WHITE), null));

        int total = 36;
        int[] strip = new int[total];
        for (int i = 0; i < total; i++) strip[i] = ThreadLocalRandom.current().nextInt(LEVELS);
        int result = nearestIdx(s, current(p, s));
        strip[total - 5] = result; // lands in the centre slot of the last window

        p.openInventory(inv);
        reelStep(p, menu, s, strip, 0, result, after);
    }

    private void reelStep(Player p, RollMenu menu, Stat s, int[] strip, int f, int result, Runnable after) {
        if (menu.closed || !p.isOnline()) return;
        for (int i = 0; i < 9; i++) menu.inv.setItem(9 + i, reelPane(s, strip[f + i]));

        int last = strip.length - 9;
        if (f >= last) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, quality(s, result) >= 3 ? 1.4f : 0.9f);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!menu.closed && p.isOnline()) after.run();
            }, 30L);
            return;
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f + 0.6f * f / last);
        long delay = 1L + (long) ((f + 1) * 4.0 / last); // spins fast, slows to a stop
        Bukkit.getScheduler().runTaskLater(this, () -> reelStep(p, menu, s, strip, f + 1, result, after), delay);
    }

    /** All stats spin at once and stop one after another. Results are already applied. */
    private void playFullRoll(Player p, Runnable after) {
        if (!animationEnabled()) {
            after.run();
            return;
        }
        RollMenu menu = new RollMenu();
        Inventory inv = Bukkit.createInventory(menu, 54, Component.text("Rolling your stats..."));
        menu.inv = inv;

        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(4, icon(Material.NETHER_STAR, line("Rolling...", NamedTextColor.GOLD), null));

        List<Integer> slots = new ArrayList<>();
        List<Stat> cells = new ArrayList<>();
        for (GroupLayout g : LAYOUT) {
            inv.setItem(g.labelSlot(), icon(Material.PAPER, line(g.label(), g.color()), null));
            for (int i = 0; i < g.ids().length && i < g.slots().length; i++) {
                Stat s = findStat(g.ids()[i]);
                if (s == null || s.attr == null) continue;
                slots.add(g.slots()[i]);
                cells.add(s);
            }
        }

        final int n = cells.size();
        final int[] tick = {0};
        p.openInventory(inv);
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            int t = tick[0]++;
            if (menu.closed || !p.isOnline()) {
                task.cancel();
                return;
            }
            for (int k = 0; k < n; k++) {
                int stopAt = 8 + 2 * k;
                if (t < stopAt) {
                    inv.setItem(slots.get(k), spinPane());
                } else if (t == stopAt) {
                    inv.setItem(slots.get(k), statItem(cells.get(k), p, false, false));
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f + 0.8f * k / n);
                }
            }
            int end = 8 + 2 * n + 10;
            if (t >= end) {
                task.cancel();
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.0f);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!menu.closed && p.isOnline()) after.run();
                }, 20L);
            }
        }, 0L, 2L);
    }

    /** Plain-text fallback for the console. */
    private void sendStatsText(CommandSender to, Player target) {
        to.sendMessage("--- " + target.getName() + " ---");
        for (Stat s : available()) {
            AttributeInstance inst = target.getAttribute(s.attr);
            if (inst == null) continue;
            to.sendMessage(String.format("%s: %.3f (normal %.3f)", s.id, inst.getValue(), inst.getBaseValue()));
        }
    }

    // ------------------------------------------------------------------ command

    private boolean deny(CommandSender s, String msg) {
        s.sendMessage(Component.text(msg, NamedTextColor.RED));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);

        // stats menu
        if (sub.equals("stats")) {
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("gamble.admin")) return deny(sender, "Only operators can view other players.");
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) return deny(sender, "Player not online: " + args[1]);
            } else if (sender instanceof Player self) {
                if (!sender.hasPermission("gamble.stats")) return deny(sender, "You can't view stats.");
                target = self;
            } else {
                sender.sendMessage("Usage: /gamble stats <player>");
                return true;
            }
            if (sender instanceof Player viewer) openMenu(viewer, target);
            else sendStatsText(sender, target);
            return true;
        }

        // players: roll / keep / revert
        if (sub.equals("roll") || sub.equals("keep") || sub.equals("revert")) {
            if (!(sender instanceof Player p)) return deny(sender, "Players only.");
            if (!sender.hasPermission("gamble.roll")) return deny(sender, "You can't reroll.");
            switch (sub) {
                case "roll" -> {
                    if (args.length < 2) return deny(sender, "Usage: /gamble roll <stat>");
                    handleRoll(p, args[1]);
                }
                case "keep" -> keepRoll(p);
                default -> revertRoll(p);
            }
            return true;
        }

        // operators
        if (!sender.hasPermission("gamble.admin")) {
            return deny(sender, "Unknown command. Try /gamble or /gamble roll <stat>.");
        }

        if (sub.equals("reload")) {
            reloadConfig();
            loadStats();
            sender.sendMessage(Component.text("Config reloaded (existing rolls are unchanged until rerolled).", NamedTextColor.GREEN));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /gamble reroll <player> [all|stat]  |  /gamble reset <player>  |  /gamble stats [player]  |  /gamble reload",
                    NamedTextColor.YELLOW));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return deny(sender, "Player not online: " + args[1]);

        switch (sub) {
            case "reroll" -> {
                String what = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "all";
                if (what.equals("all")) {
                    rollAll(target);
                } else {
                    Stat st = findStat(what);
                    if (st == null || st.attr == null) return deny(sender, "Unknown or unavailable stat: " + what);
                    rollOne(target, st);
                }
                sender.sendMessage(Component.text("Rerolled " + what + " for " + target.getName() + ".", NamedTextColor.GREEN));
                if (target != sender) {
                    target.sendMessage(Component.text("An operator rerolled your attributes! Use /gamble to see them.", NamedTextColor.GOLD));
                }
                boolean anim = animationEnabled() && getConfig().getBoolean("animation.admin-rerolls", true);
                boolean selfView = sender instanceof Player sv && sv.getUniqueId().equals(target.getUniqueId());
                if (anim) {
                    Runnable after = () -> { if (selfView) openMenu(target, target); };
                    if (what.equals("all")) {
                        playFullRoll(target, after);
                    } else {
                        Stat shown = findStat(what);
                        if (shown != null && shown.linkedTo != null) shown = findStat(shown.linkedTo);
                        if (shown != null) playReel(target, shown, after);
                        else after.run();
                    }
                }
                if (sender instanceof Player viewer && !(anim && selfView)) openMenu(viewer, target);
            }
            case "reset" -> {
                resetPlayer(target);
                sender.sendMessage(Component.text("Reset attributes for " + target.getName() + ".", NamedTextColor.GREEN));
                if (target != sender) {
                    target.sendMessage(Component.text("Your attributes were reset to normal.", NamedTextColor.GOLD));
                }
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("gamble.admin");
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> opts = new ArrayList<>();

        if (args.length == 1) {
            opts.addAll(List.of("stats", "roll", "keep", "revert"));
            if (admin) opts.addAll(List.of("reroll", "reset", "reload"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("roll")) {
                for (Stat s : rollable()) opts.add(s.id);
            } else if (admin && !sub.equals("reload") && !sub.equals("keep") && !sub.equals("revert")) {
                for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
            }
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("reroll")) {
            opts.add("all");
            for (Stat s : available()) opts.add(s.id);
        }
        return opts.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
