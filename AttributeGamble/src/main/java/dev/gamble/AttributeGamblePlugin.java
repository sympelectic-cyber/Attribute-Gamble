package dev.gamble;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class AttributeGamblePlugin extends JavaPlugin implements TabExecutor, Listener {

    /** MULT: factor of normal. ABS: absolute final value. ADD: flat amount added to normal. */
    enum Kind { MULT, ABS, ADD }

    /** HIGH/LOW: better at that end. CENTER: best at normal (x1.0), worse further away either side. NEUTRAL: ignored. */
    enum Good { HIGH, LOW, CENTER, NEUTRAL }

    static final class Stat {
        final String id;
        final String group;          // null = independent
        final Kind kind;
        final double defLo, defHi;
        final Good defGood;

        Attribute attr;              // null if this server doesn't have the attribute
        double lo, hi;
        Good good;
        String linkedTo;             // if set, this stat copies another stat's final value instead of rolling

        Stat(String id, String group, Kind kind, double lo, double hi, Good good) {
            this.id = id;
            this.group = group;
            this.kind = kind;
            this.defLo = lo;
            this.defHi = hi;
            this.defGood = good;
        }

        /** Takes part in a group's balance sum. */
        boolean counted() {
            return group != null && (good == Good.HIGH || good == Good.LOW) && attr != null;
        }

        double sign() {
            return good == Good.LOW ? -1.0 : 1.0;
        }
    }

    private final List<Stat> stats = new ArrayList<>();
    private static final String STEP_ID = "step_height";

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
    }

    private void loadStats() {
        stats.clear();
        stats.add(new Stat("entity_interaction_range", "combat", Kind.ABS, 3.0, 5.0, Good.HIGH));
        stats.add(new Stat("attack_speed", "combat", Kind.MULT, 0.5, 1.5, Good.HIGH));
        stats.add(new Stat("attack_damage", "combat", Kind.MULT, 0.5, 1.5, Good.HIGH));
        stats.add(new Stat("max_health", "combat", Kind.MULT, 0.5, 1.5, Good.HIGH));

        stats.add(new Stat("gravity", "mobility", Kind.MULT, 0.1, 2.0, Good.LOW));
        stats.add(new Stat("jump_strength", "mobility", Kind.MULT, 0.1, 2.0, Good.HIGH));
        stats.add(new Stat("movement_speed", "mobility", Kind.MULT, 0.25, 2.0, Good.HIGH));
        Stat block = new Stat("block_interaction_range", null, Kind.ADD, 0.0, 0.0, Good.HIGH);
        block.linkedTo = "entity_interaction_range"; // both reaches are always the same
        stats.add(block);
        stats.add(new Stat("friction_modifier", "mobility", Kind.MULT, 0.8, 1.2, Good.CENTER));
        stats.add(new Stat("scale", "mobility", Kind.MULT, 0.75, 1.25, Good.NEUTRAL));

        stats.add(new Stat("oxygen_bonus", null, Kind.ADD, 0.0, 5.0, Good.HIGH));
        stats.add(new Stat("water_movement_efficiency", null, Kind.ADD, 0.0, 1.0, Good.HIGH));

        for (Stat s : stats) {
            // Resolved by key so the plugin still loads on servers lacking a newer attribute.
            s.attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(s.id));
            String path = "stats." + s.id + ".";
            double a = getConfig().getDouble(path + "min", s.defLo);
            double b = getConfig().getDouble(path + "max", s.defHi);
            s.lo = Math.min(a, b);
            s.hi = Math.max(a, b);
            String g = getConfig().getString(path + "good", s.defGood.name()).toUpperCase(Locale.ROOT);
            try {
                s.good = Good.valueOf(g);
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

    /** Stats that are rolled on their own (everything except linked stats). */
    private List<Stat> rollable() {
        return stats.stream().filter(s -> s.attr != null && s.linkedTo == null).toList();
    }

    private List<Stat> members(String group) {
        return stats.stream().filter(s -> s.counted() && group.equals(s.group)).toList();
    }

    private double tolerance(String group) {
        return Math.max(0.0, getConfig().getDouble("groups." + group + ".tolerance", group.equals("combat") ? 0.05 : 0.15));
    }

    private double pityStrength() {
        return Math.max(0.0, getConfig().getDouble("pity-strength", 2.0));
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("gamble-on-first-join", true)) return;
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) return;

        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            rollAll(p);
            p.sendMessage(Component.text("Welcome! You gambled your attributes:", NamedTextColor.GOLD));
            sendStats(p, p);
        });
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

    /** Fractional deviation from normal (0.1 = +10%). */
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

    /** Currently rolled value (neutral if none). */
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

    /** -1 (worst end of the range) .. +1 (best end of the range). */
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

    /** Draws a value; pity > 0 skews it towards the good end of the range. */
    private double draw(Stat s, double pity) {
        double u = rand();
        if (s.good == Good.CENTER) {
            double v = s.lo + u * (s.hi - s.lo);
            double f = pity / (1.0 + pity);          // pity pulls the roll towards normal
            return v + (neutral(s, 1.0) - v) * f;
        }
        if (pity > 0 && s.good != Good.NEUTRAL) u = Math.pow(u, 1.0 / (1.0 + pity));
        return s.good == Good.LOW ? s.hi - u * (s.hi - s.lo) : s.lo + u * (s.hi - s.lo);
    }

    // ------------------------------------------------------------------ balancing

    private double sum(double[] a) {
        double t = 0;
        for (double x : a) t += x;
        return t;
    }

    /** Shifts the unlocked entries evenly (water-filling, respecting bounds) until sum == target. */
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
     * Makes sure the signed deviations of a group's members sum to within +/- tol.
     * Good-high stats count +dev, good-low stats (gravity) count -dev.
     * If {@code locked} is non-null that stat keeps its value and the others absorb the change.
     */
    private void balance(Player p, Map<Stat, Double> vals, List<Stat> members, double tol, double pity, Stat locked) {
        int n = members.size();
        if (n == 0) return;
        double[] s = new double[n], lo = new double[n], hi = new double[n], base = new double[n];
        boolean[] lock = new boolean[n];

        for (int i = 0; i < n; i++) {
            Stat st = members.get(i);
            base[i] = baseOf(p, st);
            double sg = st.sign();
            s[i] = sg * devOf(st, vals.get(st), base[i]);
            double a = sg * devOf(st, st.lo, base[i]);
            double b = sg * devOf(st, st.hi, base[i]);
            lo[i] = Math.min(a, b);
            hi[i] = Math.max(a, b);
            lock[i] = (st == locked);
        }

        if (Math.abs(sum(s)) > tol) {
            double target;
            if (locked == null) {
                double u = Math.pow(rand(), 1.0 / (1.0 + pity)); // pity pushes the net result positive
                target = -tol + u * 2.0 * tol;
            } else {
                target = Math.max(-tol, Math.min(tol, sum(s)));
            }
            fit(s, lo, hi, lock, target);
            if (Math.abs(sum(s)) > tol + 1e-9) { // locked stat can't be kept - let it move too
                Arrays.fill(lock, false);
                fit(s, lo, hi, lock, target);
            }
            if (Math.abs(sum(s)) > tol + 1e-9) { // should be unreachable: fall back to normal values
                for (int i = 0; i < n; i++) s[i] = Math.max(lo[i], Math.min(hi[i], 0.0));
            }
        }

        for (int i = 0; i < n; i++) {
            Stat st = members.get(i);
            vals.put(st, valueOfDev(st, st.sign() * s[i], base[i]));
        }
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

        for (String group : List.of("combat", "mobility")) {
            balance(p, vals, members(group), tolerance(group), finalPity, null);
        }

        for (var e : vals.entrySet()) applyStat(p, e.getKey(), e.getValue());
        rerollLinkOffsets(p);
        finish(p);
    }

    private void rollOne(Player p, Stat st) {
        if (st.linkedTo != null) st = findStat(st.linkedTo); // rerolling one reach rerolls both
        // pity from the player's other (non-neutral) stats
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
        finish(p);
    }

    private void resetPlayer(Player p) {
        clearLinkOffsets(p);
        for (Stat s : available()) {
            AttributeInstance inst = p.getAttribute(s.attr);
            if (inst == null) continue;
            AttributeModifier m = findModifier(inst, keyFor(s.id));
            if (m != null) inst.removeModifier(m);
        }
        finish(p);
    }

    /** Jump guarantee + heal. */
    private void finish(Player p) {
        linkStats(p);
        applyScaleBonus(p);
        fixJump(p);
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) p.setHealth(hp.getValue());
    }

    /** Stored bonus a linked stat gets on top of its source (e.g. block reach = entity reach + 0.5..1.0). */
    private double linkOffset(Player p, Stat s, boolean reroll) {
        NamespacedKey key = keyFor("offset_" + s.id);
        var pdc = p.getPersistentDataContainer();
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

    private double uniform(double lo, double hi) {
        return hi <= lo ? lo : lo + rand() * (hi - lo);
    }

    private void rerollLinkOffsets(Player p) {
        for (Stat s : stats) if (s.linkedTo != null) linkOffset(p, s, true);
    }

    private void clearLinkOffsets(Player p) {
        for (Stat s : stats) if (s.linkedTo != null) p.getPersistentDataContainer().remove(keyFor("offset_" + s.id));
    }

    /** Linked stats (block reach) follow their source (entity reach) plus a rolled bonus. */
    private void linkStats(Player p) {
        for (Stat s : stats) {
            if (s.linkedTo == null || s.attr == null) continue;
            Stat src = findStat(s.linkedTo);
            if (src == null || src.attr == null) continue;
            AttributeInstance inst = p.getAttribute(s.attr), srcInst = p.getAttribute(src.attr);
            if (inst == null || srcInst == null) continue;

            NamespacedKey key = keyFor(s.id);
            AttributeModifier old = findModifier(inst, key);
            if (old != null) inst.removeModifier(old);

            if (findModifier(srcInst, keyFor(src.id)) != null) { // source has been rolled
                double srcValue = current(p, src);
                double bonus = linkOffset(p, s, false);
                if (srcValue > srcInst.getBaseValue() + 1e-9) { // reach above normal -> smaller bonus
                    bonus *= getConfig().getDouble("block-reach-bonus.above-normal-multiplier", 0.5);
                }
                double target = srcValue + bonus;
                inst.addModifier(new AttributeModifier(key, target - inst.getBaseValue(),
                        AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    private static final String SCALE_BONUS_ID = "scale_speed_bonus";

    /**
     * Movement speed bonus for a bigger-than-normal player. Scale must be above 1.0.
     * +10% if the speed roll is at or below normal, +5% if it is already above normal.
     */
    private double scaleBonusAmount(Player p) {
        Stat sc = findStat("scale"), sp = findStat("movement_speed");
        if (sc == null || sp == null || sc.attr == null || sp.attr == null) return 0.0;
        if (current(p, sc) <= 1.0) return 0.0;
        double below = getConfig().getDouble("scale-speed-bonus.speed-at-or-below-normal", 0.10);
        double above = getConfig().getDouble("scale-speed-bonus.speed-above-normal", 0.05);
        return current(p, sp) <= 1.0 ? below : above;
    }

    /** Applies the scale speed bonus on top of the movement speed roll. */
    private void applyScaleBonus(Player p) {
        Stat sp = findStat("movement_speed");
        if (sp == null || sp.attr == null) return;
        AttributeInstance inst = p.getAttribute(sp.attr);
        if (inst == null) return;

        NamespacedKey key = keyFor(SCALE_BONUS_ID);
        AttributeModifier old = findModifier(inst, key);
        if (old != null) inst.removeModifier(old);

        double bonus = scaleBonusAmount(p);
        if (bonus > 0) {
            inst.addModifier(new AttributeModifier(key, bonus, AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    /** Peak height (blocks) of a jump with the given jump strength and gravity (vanilla tick physics). */
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

        NamespacedKey key = keyFor(STEP_ID);
        AttributeModifier old = findModifier(step, key);
        if (old != null) step.removeModifier(old);

        Stat g = findStat("gravity"), j = findStat("jump_strength");
        if (g == null || j == null || g.attr == null || j.attr == null) return;
        AttributeInstance gi = p.getAttribute(g.attr), ji = p.getAttribute(j.attr);
        if (gi == null || ji == null) return;

        double peak = jumpPeak(gi.getValue(), ji.getValue());
        double min = getConfig().getDouble("min-jump-height", 1.05);
        if (peak < min) {
            double need = 1.0 - step.getBaseValue();
            if (need > 0) {
                step.addModifier(new AttributeModifier(key, need, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    // ------------------------------------------------------------------ stats display

    private void sendStats(CommandSender to, Player target) {
        to.sendMessage(Component.text("--- " + target.getName() + "'s gambled stats ---", NamedTextColor.GOLD));

        for (Stat s : available()) {
            AttributeInstance inst = target.getAttribute(s.attr);
            if (inst == null) continue;
            double normal = inst.getBaseValue();
            double now = inst.getValue();

            String text;
            double delta = now - normal;
            if (normal != 0) {
                text = String.format("%s: %.3f -> %.3f (%+.0f%%)", s.id, normal, now, (now / normal - 1.0) * 100.0);
            } else {
                text = String.format("%s: %.2f (%+.2f)", s.id, now, delta);
            }

            NamedTextColor c = NamedTextColor.GRAY;
            if (Math.abs(delta) > 1e-6) {
                boolean up = delta > 0;
                if (s.good == Good.NEUTRAL) c = NamedTextColor.AQUA;
                else if (s.good == Good.CENTER) {
                    double off = normal == 0 ? 0 : Math.abs(delta / normal);
                    c = off <= 0.05 ? NamedTextColor.GREEN : off <= 0.12 ? NamedTextColor.YELLOW : NamedTextColor.RED;
                }
                else if (s.good == Good.HIGH) c = up ? NamedTextColor.GREEN : NamedTextColor.RED;
                else c = up ? NamedTextColor.RED : NamedTextColor.GREEN;
            }
            to.sendMessage(Component.text(text, c));
        }

        double scaleBonus = scaleBonusAmount(target);
        if (scaleBonus > 0) {
            to.sendMessage(Component.text(String.format("scale bonus: +%.0f%% movement speed (scale above 1.0)",
                    scaleBonus * 100.0), NamedTextColor.YELLOW));
        }

        // jump info
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
            }
        }

        // group balance
        for (String group : List.of("combat", "mobility")) {
            List<Stat> m = members(group);
            if (m.isEmpty()) continue;
            double net = 0;
            for (Stat st : m) net += st.sign() * devOf(st, current(target, st), baseOf(target, st));
            to.sendMessage(Component.text(String.format("%s balance: %+.1f%% (limit +/-%.0f%%)",
                    group, net * 100.0, tolerance(group) * 100.0), NamedTextColor.DARK_AQUA));
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /gamble  or  /gamble stats [player]
        if (args.length == 0 || args[0].equalsIgnoreCase("stats")) {
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("gamble.admin")) {
                    sender.sendMessage(Component.text("Only operators can view other players' stats.", NamedTextColor.RED));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not online: " + args[1], NamedTextColor.RED));
                    return true;
                }
            } else if (sender instanceof Player self) {
                if (!sender.hasPermission("gamble.stats")) {
                    sender.sendMessage(Component.text("You can't view stats.", NamedTextColor.RED));
                    return true;
                }
                target = self;
            } else {
                sender.sendMessage("Usage: /gamble stats <player>");
                return true;
            }
            sendStats(sender, target);
            return true;
        }

        if (!sender.hasPermission("gamble.admin")) {
            sender.sendMessage(Component.text("Only operators can use this. Use /gamble to see your stats.", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

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
        if (target == null) {
            sender.sendMessage(Component.text("Player not online: " + args[1], NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "reroll" -> {
                String what = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "all";
                if (what.equals("all")) {
                    rollAll(target);
                } else {
                    Stat st = findStat(what);
                    if (st == null || st.attr == null) {
                        sender.sendMessage(Component.text("Unknown or unavailable stat: " + what, NamedTextColor.RED));
                        return true;
                    }
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
            opts.add("stats");
            if (admin) opts.addAll(List.of("reroll", "reset", "reload"));
        } else if (args.length == 2 && admin && !args[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("reroll")) {
            opts.add("all");
            for (Stat s : available()) opts.add(s.id);
        }
        return opts.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
