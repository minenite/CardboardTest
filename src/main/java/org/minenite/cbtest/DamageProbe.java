package org.minenite.cbtest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;

/**
 * An isolated EntityDamageEvent probe.
 *
 * <p>Deliberately spawns exactly one entity and touches nothing else. The core
 * suite's entity checks share a run with probes that spawn and remove their own
 * mobs, and its subject kept being removed before the checks ran with no damage
 * event to explain it. Rather than assume that was contamination, this isolates
 * the question: one entity, one listener, one sequence, and a report of the
 * entity's identity at every step so a wrapper pointing at the wrong NMS entity
 * would be visible rather than inferred.
 *
 * <p>Runs entirely from the console so it can be part of the automated suite.
 */
final class DamageProbe implements Listener {

    private final Plugin plugin;
    private final List<String> out = new ArrayList<>();

    private final AtomicInteger damageCount = new AtomicInteger();
    private final AtomicInteger deathCount = new AtomicInteger();
    private final List<String> damageDetail = new ArrayList<>();
    private volatile UUID subject;
    private volatile boolean cancelDamage;

    DamageProbe(Plugin plugin) {
        this.plugin = plugin;
    }

    void run() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        World world = Bukkit.getWorlds().get(0);
        Location at = world.getSpawnLocation().clone().add(0, 1, 20);

        LivingEntity subject = (LivingEntity) world.spawnEntity(at, EntityType.COW);
        this.subject = subject.getUniqueId();
        subject.setRemoveWhenFarAway(false);

        say("subject spawned: " + subject.getType() + " " + this.subject);

        // Give it a few ticks to become live, then run the sequence step by step so
        // each stage reports before the next one can disturb it.
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> guarded("step1", () -> step1(subject)), 10L);
    }

    // 1. identity and liveness
    private void step1(LivingEntity subject) {
        check("subject still alive after 10 ticks", !subject.isDead());
        check("subject uuid stable", this.subject.equals(subject.getUniqueId()));

        // Wrapper identity. Bukkit's contract is that two wrappers for one entity
        // are equal - CraftEntity#equals compares the underlying NMS entity - so
        // equality, not instance identity, is the property that must hold. What
        // must never happen is a wrapper pointing at a different NMS entity.
        // Distinguish "the lookup is wrong" from "the entity is not indexed".
        java.util.Collection<org.bukkit.entity.Entity> all = subject.getWorld().getEntities();
        boolean inWorldList = all.stream().anyMatch(e -> e.getUniqueId().equals(this.subject));
        say("world.getEntities() size=" + all.size() + " containsSubject=" + inWorldList);
        org.bukkit.entity.Entity byWorld = null;
        try {
            byWorld = subject.getWorld().getEntity(this.subject);
        } catch (Throwable t) {
            say("World#getEntity(uuid) threw " + t);
        }
        say("World#getEntity(uuid) -> " + byWorld);

        org.bukkit.entity.Entity byUuid = Bukkit.getEntity(this.subject);
        check("Bukkit.getEntity(uuid) is non-null", byUuid != null);
        check("Bukkit.getEntity(uuid) equals the spawned wrapper", subject.equals(byUuid));
        check("Bukkit.getEntity(uuid) has the same uuid",
                byUuid != null && byUuid.getUniqueId().equals(this.subject));
        check("repeated getEntity(uuid) is stable",
                java.util.Objects.equals(Bukkit.getEntity(this.subject), Bukkit.getEntity(this.subject)));

        // A second entity of the same type must never be confused with the first.
        LivingEntity other = (LivingEntity) subject.getWorld()
                .spawnEntity(subject.getLocation().clone().add(0, 0, 4), EntityType.COW);
        other.setRemoveWhenFarAway(false);
        check("second cow has a different uuid", !other.getUniqueId().equals(this.subject));
        check("second cow is not equal to the first", !other.equals(subject));
        check("getEntity still resolves the first correctly",
                subject.equals(Bukkit.getEntity(this.subject)));
        other.remove();
        check("removing the second cow left the first alive", !subject.isDead());
        check("removing the second cow left the first resolvable",
                Bukkit.getEntity(this.subject) != null);

        say("state: dead=" + subject.isDead() + " valid=" + subject.isValid()
                + " health=" + subject.getHealth() + " maxHealth=" + subject.getMaxHealth());

        Bukkit.getScheduler().runTaskLater(this.plugin, () -> guarded("step2", () -> step2(subject)), 2L);
    }

    // 2. plain damage: health drops, exactly one event
    private void step2(LivingEntity subject) {
        this.damageCount.set(0);
        this.damageDetail.clear();
        double before = subject.getHealth();
        subject.damage(3.0);
        double after = subject.getHealth();

        check("damage reduced health (" + before + " -> " + after + ")", after < before);
        check("exactly one EntityDamageEvent fired (got " + this.damageCount.get() + ")",
                this.damageCount.get() == 1);
        say("damage detail: " + this.damageDetail);

        Bukkit.getScheduler().runTaskLater(this.plugin, () -> guarded("step3", () -> step3(subject)), 2L);
    }

    // 3. cancelled damage: event fires, health unchanged, entity alive
    private void step3(LivingEntity subject) {
        this.damageCount.set(0);
        this.cancelDamage = true;
        double before = subject.getHealth();
        subject.damage(5.0);
        double after = subject.getHealth();
        this.cancelDamage = false;

        check("cancelled damage still fired the event (got " + this.damageCount.get() + ")",
                this.damageCount.get() == 1);
        check("cancelled damage left health unchanged (" + before + " -> " + after + ")",
                after == before);
        check("subject alive after cancelled damage", !subject.isDead());

        Bukkit.getScheduler().runTaskLater(this.plugin, () -> guarded("step4", () -> step4(subject)), 2L);
    }

    // 4. a real vanilla damage source rather than the plugin API.
    //    An explosion goes through the normal NMS damage path, unlike
    //    LivingEntity#damage which is the API entry point. Fire was tried first and
    //    proved useless as a test: the entity took no fire damage at all, so an
    //    absent event was the correct outcome and proved nothing.
    private void step4(LivingEntity subject) {
        this.damageCount.set(0);
        this.damageDetail.clear();
        double before = subject.getHealth();
        subject.setMaxHealth(200.0);
        subject.setHealth(200.0);
        subject.getWorld().createExplosion(subject.getLocation(), 2.0F, false, false);
        double after = subject.getHealth();

        say("vanilla explosion: events=" + this.damageCount.get()
                + " detail=" + this.damageDetail + " health " + before + " -> " + after);
        if (after < before) {
            check("a vanilla damage source fired EntityDamageEvent", this.damageCount.get() > 0);
        } else {
            say("explosion did no damage, so this proves nothing either way");
        }

        // Restore it so the later steps test what they mean to test rather than
        // measuring an already-dead entity.
        if (!subject.isDead()) {
            subject.setHealth(200.0);
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> guarded("step4b", () -> step4b(subject)), 2L);
    }

    /** The other entity APIs fixed alongside damage, tested where nothing else runs. */
    private void step4b(LivingEntity subject) {
        // setMaxHealth must move the maximum, not the current health.
        double oldMax = subject.getMaxHealth();
        double oldHealth = subject.getHealth();
        subject.setMaxHealth(oldMax + 10);
        check("setMaxHealth raised the maximum (" + oldMax + " -> " + subject.getMaxHealth() + ")",
                subject.getMaxHealth() == oldMax + 10);
        check("setMaxHealth did not silently heal the entity", subject.getHealth() == oldHealth);
        subject.setMaxHealth(oldMax);

        // teleport, and confirm the world actually moved it.
        Location target = subject.getLocation().clone().add(0, 0, 6);
        boolean teleported = subject.teleport(target);
        check("teleport returned true", teleported);
        check("teleport moved the entity", subject.getLocation().distance(target) < 1.0);

        // persistence, checked against the NMS flag rather than only the setter.
        subject.setRemoveWhenFarAway(true);
        check("setRemoveWhenFarAway(true) reads back true", subject.getRemoveWhenFarAway());
        subject.setRemoveWhenFarAway(false);
        check("setRemoveWhenFarAway(false) reads back false", !subject.getRemoveWhenFarAway());

        Bukkit.getScheduler().runTaskLater(this.plugin, () -> guarded("step5", () -> step5(subject)), 2L);
    }

    // 5. lethal damage: damage event before death, exactly one death event
    private void step5(LivingEntity subject) {
        this.damageCount.set(0);
        this.deathCount.set(0);
        this.damageDetail.clear();

        subject.damage(1000.0);

        check("lethal damage fired EntityDamageEvent (got " + this.damageCount.get() + ")",
                this.damageCount.get() >= 1);
        check("lethal damage fired exactly one EntityDeathEvent (got " + this.deathCount.get() + ")",
                this.deathCount.get() == 1);
        check("subject is dead after lethal damage", subject.isDead());
        say("lethal detail: " + this.damageDetail);

        // Entity#remove must take it out of the world for good.
        LivingEntity spare = (LivingEntity) subject.getWorld()
                .spawnEntity(subject.getLocation().clone().add(0, 1, 0), EntityType.COW);
        java.util.UUID spareId = spare.getUniqueId();
        spare.remove();
        check("remove() marks the entity dead/invalid", spare.isDead() || !spare.isValid());
        check("remove() makes it unresolvable by uuid", Bukkit.getEntity(spareId) == null);

        finish();
    }

    private void finish() {
        HandlerList.unregisterAll(this);
        for (String line : this.out) {
            this.plugin.getLogger().info(line);
        }
        this.plugin.getLogger().info("[INFO] damage probe complete");
    }

    // ---------- listeners ----------

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!event.getEntity().getUniqueId().equals(this.subject)) {
            return;
        }
        this.damageCount.incrementAndGet();
        this.damageDetail.add(event.getCause() + "=" + event.getDamage());
        if (this.cancelDamage) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getUniqueId().equals(this.subject)) {
            this.deathCount.incrementAndGet();
        }
    }

    // ---------- reporting ----------

    /**
     * Cardboard's scheduler logs a task failure without a stack trace, so an
     * exception inside a step silently ended the run with no output at all.
     */
    private void guarded(String step, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            this.out.add("[FAIL] damage: " + step + " threw " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("org.bukkit") || e.getClassName().startsWith("org.cardboard")
                        || e.getClassName().startsWith("org.minenite")) {
                    this.out.add("[INFO] damage:     at " + e);
                }
            }
            finish();
        }
    }

    private void check(String what, boolean ok) {
        this.out.add((ok ? "[PASS] damage: " : "[FAIL] damage: ") + what);
    }

    private void say(String what) {
        this.out.add("[INFO] damage: " + what);
    }
}
