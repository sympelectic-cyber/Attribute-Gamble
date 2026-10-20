package dev.gamble;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class AttributeGamblePlugin extends JavaPlugin implements TabExecutor, Listener {

    /** MULT: factor of normal. ABS: absolute final value. ADD: flat amount added to normal. */
    enum Kind { MULT, ABS, ADD }

    /** HIGH/LOW: better at that end. CENTER: best at normal. NEUTRAL: ignored by balance + pity. */
    enum Good { HIGH, LOW, CENTER, NEUTRAL }

    static final class Stat {
        final String id;
        final String group;          // null = independent
        final Kind kind;
        final double defLo, defHi;
        final Good defGood;
        double defWeight = 1.0;

        Attribute attr;              // null if this server doesn't have the attribute
        double lo, hi, weight = 1.0;
        Good good;
        String linkedTo;             // if set, follows another stat instead of rolling

        Stat(String id, String group, Kind kind, double lo, double hi, Good good) {
            this.id = id;
            this.group = group;
            this.kind = kind;
            this.defLo = lo;
            this.defHi = hi;
            this.defGood = good;
        }

        boolean counted() {
            return group != null && (good == Good.HIGH || good == Good.LOW) && attr != null;
        }

        double sign() {
            return good == Good.LOW ? -1.0 : 1.0;
        }
    }

    static final class Snapshot {
        final Map<String, Double> vals = new HashMap<>();
        final Map<String, Double> dbl = new HashMap<>();
        final Map<String, String> str = new HashMap<>();
    }

    private static final List<String> GROUPS = List.of("combat", "mobility", "utility");
    private static final String STEP_ID = "step_height";
    private static final String SCALE_BONUS_ID = "scale_speed_bonus";
    private static final String LOW_HP_ID = "low_health_bonus";

    private final List<Stat> stats = new ArrayList<>();
    private final Map<UUID, Snapshot> pending = new HashMap<>();
    private final Map<UUID, Long> pillarCooldown = new HashMap<>();

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
    }

    private Stat def(String id, String group, Kind kind, double lo, double hi, Good good) {
        Stat s = new Stat(id, group, kind, lo, hi, good);
        stats.add(s);
        return s;
    }

    private void loadStats() {
        stats.clear();

        // combat
        def("entity_interaction_range", "combat", Kind.ABS, 3.0, 5.0, Good.HIGH).defWeight = 2.0;
        def("attack_speed", "combat", Kind.MULT, 0.6, 1.5, Good.HIGH);
        def("attack_damage", "combat", Kind.MULT, 0.6, 1.5, Good.HIGH);
        def("max_health", "combat", Kind.MULT, 0.6, 1.5, Good.HIGH);

        // mobility
        def("gravity", "mobility", Kind.MULT, 0.4, 1.6, Good.LOW);
        def("jump_strength", "mobility", Kind.MULT, 0.3, 1.8, Good.HIGH);
        def("movement_speed", "mobility", Kind.MULT, 0.6, 1.5, Good.HIGH);
        def("friction_modifier", "mobility", Kind.MULT, 0.8, 1.2, Good.CENTER);
        def("scale", "mobility", Kind.MULT, 0.75, 1.25, Good.NEUTRAL);

        // utility quirks
        def("safe_fall_distance", "utility", Kind.MULT, 0.5, 2.0, Good.HIGH);
        def("fall_damage_multiplier", "utility", Kind.MULT, 0.5, 1.5, Good.LOW);
        def("burning_time", "utility", Kind.MULT, 0.5, 1.5, Good.LOW);
        def("sneaking_speed", "utility", Kind.MULT, 0.6, 1.5, Good.HIGH);

        // independent
        def("mining_efficiency", null, Kind.ADD, 0.0, 1.5, Good.HIGH);
        def("oxygen_bonus", null, Kind.ADD, 0.0, 5.0, Good.HIGH);
        def("water_movement_efficiency", null, Kind.ADD, 0.0, 1.0, Good.HIGH);

        // linked: block reach follows entity reach (+ bonus)
        Stat block = def("block_interaction_range", null, Kind.ADD, 0.0, 0.0, Good.HIGH);
        block.linkedTo = "entity_interaction_range";

        for (Stat s : stats) {
            s.attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(s.id));
            String path = "stats." + s.id + ".";
            double a = getConfig().getDouble(path + "min", s.defLo);
            double b = getConfig().getDouble(path + "max", s.defHi);
            s.lo = Math.min(a, b);
            s.hi = Math.max(a, b);
            s.weight = Math.max(0.01, getConfig().getDouble(path + "weight", s.defWeight));
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
                p.sendMessage(Component.text("Welcome! You gambled your attributes:", NamedTextColor.GOLD));
                sendStats(p, p);
            } else {
                applyTitle(p);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId()); // unanswered reroll = keep
        pillarCooldown.remove(e.getPlayer().getUniqueId());
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
                p.sendMessage(Component.text("You died - " + st.id + " was rerolled!", NamedTextColor.GOLD));
            }
        });
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

    // ------------------------------------------------------------------ pillar assist

    /** True if this player's jump can't clear a full block. */
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
            case ADD -> 0.0;
        };
    }

    private double devOf(Stat s, double v, double base) {
        return switch (s.kind) {
            case MULT -> v - 1.0;
            case ABS -> base == 0 ? 0 : v / base - 1.0;
            case ADD -> base == 0 ? 0 : v / base;
        };
    }

    private double valueOfDev(Stat s, double dev, double base) {
        return switch (s.kind) {
            case MULT -> 1.0 + dev;
            case ABS -> base * (1.0 + dev);
            case ADD -> dev * base;
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

    private void applyStat(Player p, Stat s, double v) {
        AttributeInstance inst = p.getAttribute(s.attr);
        if (inst == null) return;
        v = Math.max(s.lo, Math.min(s.hi, v));
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

    // ------------------------------------------------------------------ randomness / pity

    private double rand() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private double uniform(double lo, double hi) {
        return hi <= lo ? lo : lo + rand() * (hi - lo);
    }

    /** -1 (worst end of the range) .. +1 (best end). */
    private double pos(Stat s, double v) {
        if (s.hi <= s.lo) return 0;
        if (s.good == Good.CENTER) {
            double n = neutral(s, 1.0);
            double maxDist = Math.max(Math.abs(s.lo - n), Math.abs(s.hi - n));
            if (maxDist <= 0) return 1.0;
            return (1.0 - Math.min(1.0, Math.abs(v - n) / maxDist)) * 2.0 - 1.0;
        }
        double t = (v - s.lo) / (s.hi - s.lo);
        t = Math.max(0, Math.min(1, t));
        return (s.good == Good.LOW ? 1.0 - t : t) * 2.0 - 1.0;
    }

    private double pityOf(double avgPos) {
        return Math.max(0.0, -avgPos) * pityStrength();
    }

    private double draw(Stat s, double pity) {
        double u = rand();
        if (s.good == Good.CENTER) {
            double v = s.lo + u * (s.hi - s.lo);
            double f = pity / (1.0 + pity);
            return v + (neutral(s, 1.0) - v) * f;
        }
        if (pity > 0 && s.good != Good.NEUTRAL) u = Math.pow(u, 1.0 / (1.0 + pity));
        return s.good == Good.LOW ? s.hi - u * (s.hi - s.lo) : s.lo + u * (s.hi - s.lo);
    }

    // ------------------------------------------------------------------ balancing (weighted, multiplicative)

    private double sum(double[] a) {
        double t = 0;
        for (double x : a) t += x;
        return t;
    }

    private void fit(double[] s, double[] lo, double[] hi, boolean[] lock, double target) {
        for (int it = 0; it < 64; it++) {
            double diff = target - sum(s);
            if (Math.abs(diff) < 1e-9) return;
            int free = 0;
            for (int i = 0; i < s.length; i++) {
                if (!lock[i] && (diff > 0 ? s[i] < hi[i] - 1e-12 : s[i] > lo[i] + 1e-12)) free++;
            }
            if (free == 0) return;
            double d = diff / free;
            for (int i = 0; i < s.length; i++) {
                if (!lock[i] && (diff > 0 ? s[i] < hi[i] - 1e-12 : s[i] > lo[i] + 1e-12)) {
                    s[i] = Math.max(lo[i], Math.min(hi[i], s[i] + d));
                }
            }
        }
    }

    /**
     * Keeps a group's weighted product within 1 +/- tol: sum(weight * sign * ln(multiplier)) within +/- ln(1+tol).
     * Good-low stats (gravity etc.) count inverted. A locked stat keeps its value and the others absorb the change.
     */
    private void balance(Player p, Map<Stat, Double> vals, List<Stat> members, double tol, double pity, Stat locked) {
        int n = members.size();
        if (n == 0) return;
        double[] x = new double[n], lo = new double[n], hi = new double[n], base = new double[n], k = new double[n];
        boolean[] lock = new boolean[n];

        for (int i = 0; i < n; i++) {
            Stat st = members.get(i);
            base[i] = baseOf(p, st);
            k[i] = st.weight * st.sign();
            x[i] = k[i] * ln(1.0 + devOf(st, vals.get(st), base[i]));
            double a = k[i] * ln(1.0 + devOf(st, st.lo, base[i]));
            double b = k[i] * ln(1.0 + devOf(st, st.hi, base[i]));
            lo[i] = Math.min(a, b);
            hi[i] = Math.max(a, b);
            lock[i] = (st == locked);
        }

        double limit = Math.log(1.0 + tol);
        if (Math.abs(sum(x)) > limit) {
            double target;
            if (locked == null) {
                double u = Math.pow(rand(), 1.0 / (1.0 + pity));
                target = -limit + u * 2.0 * limit;
            } else {
                target = Math.max(-limit, Math.min(limit, sum(x)));
            }
            fit(x, lo, hi, lock, target);
            if (Math.abs(sum(x)) > limit + 1e-9) {
                Arrays.fill(lock, false);
                fit(x, lo, hi, lock, target);
            }
            if (Math.abs(sum(x)) > limit + 1e-9) {
                for (int i = 0; i < n; i++) x[i] = Math.max(lo[i], Math.min(hi[i], 0.0));
            }
        }

        for (int i = 0; i < n; i++) {
            Stat st = members.get(i);
            double m = Math.exp(x[i] / k[i]);
            vals.put(st, valueOfDev(st, m - 1.0, base[i]));
        }
    }

    /** Net balance of the rolled values of a group, as a fraction (0.03 = +3%). */
    private double netOf(Player p, String group) {
        double net = 0;
        for (Stat st : members(group)) {
            net += st.weight * st.sign() * ln(1.0 + devOf(st, current(p, st), baseOf(p, st)));
        }
        return Math.exp(net) - 1.0;
    }

    /** Power of final (bonus-included) values: product of weighted multipliers. */
    private double power(Player p, String group) {
        double sum = 0;
        for (Stat s : stats) {
            if (!s.counted()) continue;
            if (group != null && !group.equals(s.group)) continue;
            AttributeInstance inst = p.getAttribute(s.attr);
            if (inst == null || inst.getBaseValue() == 0) continue;
            sum += s.weight * s.sign() * ln(inst.getValue() / inst.getBaseValue());
        }
        return Math.exp(sum);
    }

    // ------------------------------------------------------------------ rolling

    private void rollAll(Player p) {
        List<Stat> order = new ArrayList<>(rollable());
        Collections.shuffle(order);

        Map<Stat, Double> vals = new LinkedHashMap<>();
        double posSum = 0;
        int posN = 0;
        for (Stat st : order) {
            double pity = (posN == 0) ? 0 : pityOf(posSum / posN);
            double v = draw(st, pity);
            vals.put(st, v);
            if (st.good != Good.NEUTRAL) {
                posSum += pos(st, v);
                posN++;
            }
        }
        double finalPity = posN == 0 ? 0 : pityOf(posSum / posN);

        // Jackpot: one stat is pushed to its best possible value; the balance step makes the others pay for it.
        Stat jackpot = null;
        if (getConfig().getBoolean("jackpot.enabled", true) && rand() < getConfig().getDouble("jackpot.chance", 0.03)) {
            List<Stat> cands = order.stream().filter(Stat::counted).toList();
            if (!cands.isEmpty()) {
                jackpot = cands.get(ThreadLocalRandom.current().nextInt(cands.size()));
                vals.put(jackpot, jackpot.good == Good.LOW ? jackpot.lo : jackpot.hi);
            }
        }

        for (String group : GROUPS) {
            Stat lockedHere = (jackpot != null && group.equals(jackpot.group)) ? jackpot : null;
            balance(p, vals, members(group), tolerance(group), finalPity, lockedHere);
        }

        for (var e : vals.entrySet()) applyStat(p, e.getKey(), e.getValue());
        rerollLinkOffsets(p);
        clearLowHealthChoice(p);

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (jackpot != null) {
            pdc.set(keyFor("jackpot_stat"), PersistentDataType.STRING, jackpot.id);
        } else {
            pdc.remove(keyFor("jackpot_stat"));
        }
        finish(p);

        if (jackpot != null) {
            p.sendMessage(Component.text("JACKPOT! " + jackpot.id + " rolled its best possible value (the others paid for it).",
                    NamedTextColor.LIGHT_PURPLE));
            if (getConfig().getBoolean("jackpot.broadcast", true)) {
                Bukkit.getServer().broadcast(Component.text(p.getName() + " hit the JACKPOT on " + jackpot.id + "!",
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
            posSum += pos(o, current(p, o));
            posN++;
        }
        double pity = posN == 0 ? 0 : pityOf(posSum / posN);
        double v = draw(st, pity);

        if (st.counted()) {
            List<Stat> members = members(st.group);
            Map<Stat, Double> vals = new LinkedHashMap<>();
            for (Stat m : members) vals.put(m, m == st ? v : current(p, m));
            balance(p, vals, members, tolerance(st.group), pity, st);
            for (var e : vals.entrySet()) applyStat(p, e.getKey(), e.getValue());
        } else {
            applyStat(p, st, v);
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
        fixJump(p);
        applyTitle(p);
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) p.setHealth(hp.getValue());
    }

    // ------------------------------------------------------------------ linked reach

    private double linkOffset(Player p, Stat s, boolean reroll) {
        NamespacedKey key = keyFor("offset_" + s.id);
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Double stored = pdc.get(key, PersistentDataType.DOUBLE);
        if (stored == null || reroll) {
            double lo = getConfig().getDouble("block-reach-bonus.min", 0.5);
            double hi = getConfig().getDouble("block-reach-bonus.max", 1.0);
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
        if (hp != null) removeMod(p, hp.attr, "scale_hp");

        Stat sc = findStat("scale");
        if (sc == null || sc.attr == null) return;
        double s = current(p, sc);

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
        if (ids.isEmpty()) ids = List.of("attack_damage", "attack_speed");
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
            inst.addModifier(new AttributeModifier(keyFor(LOW_HP_ID), val, AttributeModifier.Operation.ADD_SCALAR));
        }
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

    private void fixJump(Player p) {
        Attribute stepAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(STEP_ID));
        if (stepAttr == null) return;
        AttributeInstance step = p.getAttribute(stepAttr);
        if (step == null) return;

        removeMod(p, stepAttr, STEP_ID);

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
        for (Stat s : rollable()) {
            AttributeInstance inst = p.getAttribute(s.attr);
            if (inst != null && findModifier(inst, keyFor(s.id)) != null) return true;
        }
        return false;
    }

    private String computeTitle(Player p) {
        Stat dom = null, worst = null;
        double domX = 0, worstX = 0;
        for (Stat s : stats) {
            if (!s.counted()) continue;
            double x = s.weight * s.sign() * ln(1.0 + devOf(s, current(p, s), baseOf(p, s)));
            if (dom == null || x > domX) { dom = s; domX = x; }
            if (worst == null || x < worstX) { worst = s; worstX = x; }
        }

        String name;
        if (dom == null || domX < 0.06) {
            name = "Everyman";
        } else {
            name = switch (dom.id) {
                case "entity_interaction_range" -> "Lancer";
                case "attack_speed" -> "Duelist";
                case "attack_damage" -> (worst != null && worst.id.equals("max_health")) ? "Glass Cannon" : "Brute";
                case "max_health" -> "Tank";
                case "gravity" -> "Feather";
                case "jump_strength" -> "Leaper";
                case "movement_speed" -> "Sprinter";
                case "safe_fall_distance" -> "Acrobat";
                case "fall_damage_multiplier" -> "Cushioned";
                case "burning_time" -> "Fireproof";
                case "sneaking_speed" -> "Shadow";
                default -> "Wanderer";
            };
        }

        String prefix = "";
        Stat sc = findStat("scale");
        if (sc != null && sc.attr != null) {
            double v = current(p, sc);
            if (v >= 1.1) prefix = "Giant ";
            else if (v <= 0.9) prefix = "Tiny ";
        }
        if (p.getPersistentDataContainer().get(keyFor("jackpot_stat"), PersistentDataType.STRING) != null) {
            prefix = "JACKPOT " + prefix;
        }
        return prefix + name;
    }

    private void applyTitle(Player p) {
        if (!getConfig().getBoolean("titles.tab-list", true)) return;
        if (!hasRolls(p)) {
            p.playerListName(null);
            return;
        }
        p.playerListName(Component.text(p.getName() + " [" + computeTitle(p) + "]"));
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
            AttributeInstance inst = p.getAttribute(st.attr);
            if (inst != null && findModifier(inst, keyFor(st.id)) != null) s.vals.put(st.id, current(p, st));
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
            p.sendMessage(Component.text("Choose /gamble keep or /gamble revert for your last reroll first.", NamedTextColor.RED));
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

        int cost = getConfig().getInt("player-reroll.xp-levels", 5);
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
        sendStats(p, p);
        p.sendMessage(Component.text("Keep the new roll? (" + secs + "s)  ", NamedTextColor.GOLD)
                .append(Component.text("[KEEP]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/gamble keep")))
                .append(Component.text("  "))
                .append(Component.text("[REVERT]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/gamble revert"))));

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pending.get(id) == snap) {
                pending.remove(id);
                if (p.isOnline()) p.sendMessage(Component.text("Time's up - you kept the new roll.", NamedTextColor.GRAY));
            }
        }, secs * 20L);
    }

    // ------------------------------------------------------------------ display

    private void sendStats(CommandSender to, Player target) {
        to.sendMessage(Component.text("--- " + target.getName() + "'s gambled stats ---", NamedTextColor.GOLD));
        if (hasRolls(target)) {
            to.sendMessage(Component.text("Title: " + computeTitle(target), NamedTextColor.LIGHT_PURPLE));
        }

        for (Stat s : available()) {
            AttributeInstance inst = target.getAttribute(s.attr);
            if (inst == null) continue;
            double normal = inst.getBaseValue();
            double now = inst.getValue();
            double delta = now - normal;

            String text;
            if (normal != 0) {
                text = String.format("%s: %.3f -> %.3f (%+.0f%%)", s.id, normal, now, (now / normal - 1.0) * 100.0);
            } else {
                text = String.format("%s: %.2f (%+.2f)", s.id, now, delta);
            }

            NamedTextColor c = NamedTextColor.GRAY;
            if (Math.abs(delta) > 1e-6) {
                boolean up = delta > 0;
                if (s.good == Good.NEUTRAL) {
                    c = NamedTextColor.AQUA;
                } else if (s.good == Good.CENTER) {
                    double off = normal == 0 ? 0 : Math.abs(delta / normal);
                    c = off <= 0.05 ? NamedTextColor.GREEN : off <= 0.12 ? NamedTextColor.YELLOW : NamedTextColor.RED;
                } else if (s.good == Good.HIGH) {
                    c = up ? NamedTextColor.GREEN : NamedTextColor.RED;
                } else {
                    c = up ? NamedTextColor.RED : NamedTextColor.GREEN;
                }
            }
            to.sendMessage(Component.text(text, c));
        }

        double scaleBonus = scaleBonusAmount(target);
        if (scaleBonus > 0) {
            to.sendMessage(Component.text(String.format("scale bonus: +%.0f%% movement speed (scale above 1.0)",
                    scaleBonus * 100.0), NamedTextColor.YELLOW));
        }
        if (lowHealth(target)) {
            PersistentDataContainer pdc = target.getPersistentDataContainer();
            String id = pdc.get(keyFor("low_hp_stat"), PersistentDataType.STRING);
            Double val = pdc.get(keyFor("low_hp_value"), PersistentDataType.DOUBLE);
            if (id != null && val != null) {
                to.sendMessage(Component.text(String.format("low health bonus: +%.0f%% %s", val * 100.0, id), NamedTextColor.YELLOW));
            }
        }

        Stat g = findStat("gravity"), j = findStat("jump_strength");
        if (g != null && j != null && g.attr != null && j.attr != null) {
            AttributeInstance gi = target.getAttribute(g.attr), ji = target.getAttribute(j.attr);
            if (gi != null && ji != null) {
                double peak = jumpPeak(gi.getValue(), ji.getValue());
                String extra = "";
                Attribute stepAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(STEP_ID));
                if (stepAttr != null) {
                    AttributeInstance st = target.getAttribute(stepAttr);
                    if (st != null) extra = String.format(", step height %.2f", st.getValue());
                }
                to.sendMessage(Component.text(String.format("jump height ~%.2f blocks%s", peak, extra), NamedTextColor.YELLOW));
                if (needsPillarAssist(target) && getConfig().getBoolean("pillar-assist.enabled", true)) {
                    to.sendMessage(Component.text("pillar assist: sneak + look down + right-click with a block to build up",
                            NamedTextColor.YELLOW));
                }
            }
        }

        for (String group : GROUPS) {
            if (members(group).isEmpty()) continue;
            to.sendMessage(Component.text(String.format("%s balance: %+.1f%% (limit +/-%.0f%%)",
                    group, netOf(target, group) * 100.0, tolerance(group) * 100.0), NamedTextColor.DARK_AQUA));
        }
    }

    private void sendPower(CommandSender to, Player target) {
        to.sendMessage(Component.text("--- " + target.getName() + "'s power (1.00 = average) ---", NamedTextColor.GOLD));
        for (String group : GROUPS) {
            if (members(group).isEmpty()) continue;
            to.sendMessage(Component.text(String.format("%s power: x%.2f", group, power(target, group)), NamedTextColor.GRAY));
        }
        to.sendMessage(Component.text(String.format("total power: x%.2f", power(target, null)), NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ command

    private boolean deny(CommandSender s, String msg) {
        s.sendMessage(Component.text(msg, NamedTextColor.RED));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);

        // everyone: stats / power
        if (sub.equals("stats") || sub.equals("power")) {
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("gamble.admin")) return deny(sender, "Only operators can view other players.");
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) return deny(sender, "Player not online: " + args[1]);
            } else if (sender instanceof Player self) {
                if (!sender.hasPermission("gamble.stats")) return deny(sender, "You can't view stats.");
                target = self;
            } else {
                sender.sendMessage("Usage: /gamble " + sub + " <player>");
                return true;
            }
            if (sub.equals("stats")) sendStats(sender, target);
            else sendPower(sender, target);
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
                case "keep" -> {
                    if (pending.remove(p.getUniqueId()) == null) return deny(sender, "Nothing to keep.");
                    p.sendMessage(Component.text("Kept your new roll.", NamedTextColor.GREEN));
                }
                default -> {
                    Snapshot snap = pending.remove(p.getUniqueId());
                    if (snap == null) return deny(sender, "Nothing to revert.");
                    restore(p, snap);
                    p.sendMessage(Component.text("Reverted to your previous stats (the reroll still counts).", NamedTextColor.YELLOW));
                }
            }
            return true;
        }

        // operators
        if (!sender.hasPermission("gamble.admin")) {
            return deny(sender, "Unknown command. Try /gamble, /gamble power or /gamble roll <stat>.");
        }

        if (sub.equals("reload")) {
            reloadConfig();
            loadStats();
            sender.sendMessage(Component.text("Config reloaded (existing rolls are unchanged until rerolled).", NamedTextColor.GREEN));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /gamble reroll <player> [all|stat]  |  /gamble reset <player>  |  /gamble stats|power [player]  |  /gamble reload",
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
                    target.sendMessage(Component.text("An operator rerolled your attributes!", NamedTextColor.GOLD));
                }
                sendStats(target, target);
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
            opts.addAll(List.of("stats", "power", "roll", "keep", "revert"));
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
