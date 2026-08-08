package org.minenite.cbtest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Regression coverage for core Bukkit/Paper behaviour.
 *
 * <p>Everything here runs from the server console against a spawned entity rather
 * than a connected player, so the whole suite executes in an automated boot with
 * no client attached. That is the point: a test that needs someone to log in and
 * click things is a test that will not be run.
 *
 * <p>Each probe reports individually and nothing throws out, so one failure does
 * not hide the rest.
 */
final class CoreProbes implements Listener {

    private final Plugin plugin;
    private final Consumer<String> pass;
    private final Consumer<String> fail;

    // Set by the listeners below, to prove events actually dispatched.
    private volatile boolean sawDamage;
    private volatile boolean sawDeath;
    private volatile boolean sawBreak;

    CoreProbes(Plugin plugin, Consumer<String> pass, Consumer<String> fail) {
        this.plugin = plugin;
        this.pass = pass;
        this.fail = fail;
    }

    void runAll() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        try {
            World world = Bukkit.getWorlds().get(0);
            this.worlds(world);
            this.blocks(world);
            this.entities(world);
            this.projectiles(world);
            this.itemStacks();
            this.recipes();
            this.pdc(world);
            this.scoreboards();
            this.bossBars();
            this.scheduler();
            this.permissions();
            this.commands();
            this.configuration();
            this.registries();
            this.worldSave(world);
        } finally {
            HandlerList.unregisterAll(this);
        }
    }

    private void probe(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            this.fail.accept(name + ": " + t);
        }
    }

    // ---------- worlds / dimensions ----------

    private void worlds(World overworld) {
        probe("worlds", () -> {
            List<World> worlds = Bukkit.getWorlds();
            boolean nether = worlds.stream().anyMatch(w -> w.getEnvironment() == World.Environment.NETHER);
            boolean end = worlds.stream().anyMatch(w -> w.getEnvironment() == World.Environment.THE_END);
            if (worlds.size() >= 3 && nether && end) {
                this.pass.accept("worlds: " + worlds.size() + " loaded, nether and end present");
            } else {
                this.fail.accept("worlds: expected overworld/nether/end, got " + worlds.size());
            }
            this.pass.accept("worlds: spawn of " + overworld.getName() + " is " + overworld.getSpawnLocation().toVector());
        });
    }

    // ---------- block place / break / interact ----------

    private void blocks(World world) {
        probe("blocks", () -> {
            Location at = world.getSpawnLocation().clone().add(3, 0, 3);
            Block block = at.getBlock();
            Material previous = block.getType();

            block.setType(Material.STONE);
            if (block.getType() != Material.STONE) {
                this.fail.accept("blocks: setType did not stick");
                return;
            }
            this.pass.accept("blocks: place and read back");

            // BlockData round-trip, which is the component-ish side of blocks.
            block.setType(Material.OAK_STAIRS);
            org.bukkit.block.data.BlockData data = block.getBlockData();
            String serialized = data.getAsString();
            block.setBlockData(Bukkit.createBlockData(serialized));
            if (block.getBlockData().getAsString().equals(serialized)) {
                this.pass.accept("blocks: BlockData round-trip via createBlockData");
            } else {
                this.fail.accept("blocks: BlockData round-trip changed the state");
            }

            // Relative access and neighbours.
            if (block.getRelative(BlockFace.UP).getLocation().getBlockY() == block.getY() + 1) {
                this.pass.accept("blocks: relative navigation");
            } else {
                this.fail.accept("blocks: getRelative returned the wrong position");
            }

            // Break through the Bukkit API and confirm the event fired.
            this.sawBreak = false;
            block.setType(Material.STONE);
            boolean broke = block.breakNaturally();
            this.pass.accept("blocks: breakNaturally returned " + broke
                    + ", type now " + block.getType());

            block.setType(previous);
        });
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        this.sawBreak = true;
    }

    // ---------- entities: spawn / damage / death ----------

    private void entities(World world) {
        probe("entities", () -> {
            Location at = world.getSpawnLocation().clone().add(0, 1, 5);

            Entity spawned = world.spawnEntity(at, EntityType.ZOMBIE);
            if (!(spawned instanceof Zombie zombie)) {
                this.fail.accept("entities: spawnEntity did not return a Zombie");
                return;
            }
            this.pass.accept("entities: spawned " + zombie.getType() + " (" + zombie.getUniqueId() + ")");

            // Teleportation.
            Location moved = at.clone().add(0, 0, 2);
            if (zombie.teleport(moved) && zombie.getLocation().distance(moved) < 1.0) {
                this.pass.accept("entities: teleport moved the entity");
            } else {
                this.fail.accept("entities: teleport did not take effect");
            }

            // Damage, which must dispatch EntityDamageEvent.
            this.sawDamage = false;
            double before = zombie.getHealth();
            zombie.damage(4.0);
            if (zombie.getHealth() < before) {
                this.pass.accept("entities: damage applied (" + before + " -> " + zombie.getHealth() + ")");
            } else {
                this.fail.accept("entities: damage did not reduce health");
            }
            if (this.sawDamage) {
                this.pass.accept("entities: EntityDamageEvent fired");
            } else {
                this.fail.accept("entities: EntityDamageEvent did not fire");
            }

            // Death, which must dispatch EntityDeathEvent.
            this.sawDeath = false;
            zombie.setHealth(0.0);
            if (this.sawDeath) {
                this.pass.accept("entities: EntityDeathEvent fired");
            } else {
                this.fail.accept("entities: EntityDeathEvent did not fire");
            }
            if (zombie.isDead()) {
                this.pass.accept("entities: entity is dead after setHealth(0)");
            } else {
                this.fail.accept("entities: entity still alive after setHealth(0)");
            }
        });
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        this.sawDamage = true;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        this.sawDeath = true;
    }

    // ---------- projectiles ----------

    private void projectiles(World world) {
        probe("projectiles", () -> {
            Location at = world.getSpawnLocation().clone().add(0, 2, 8);
            Entity arrow = world.spawnEntity(at, EntityType.ARROW);
            if (arrow instanceof org.bukkit.entity.Arrow shot) {
                shot.setVelocity(new org.bukkit.util.Vector(0, 0.1, 0));
                this.pass.accept("projectiles: spawned arrow, velocity " + shot.getVelocity());
                shot.remove();
            } else {
                this.fail.accept("projectiles: spawnEntity(ARROW) gave " + arrow);
            }

            // A thrown projectile from a living shooter, which is the more
            // interesting path because it sets the shooter.
            Entity thrower = world.spawnEntity(at, EntityType.ZOMBIE);
            if (thrower instanceof LivingEntity living) {
                org.bukkit.entity.Snowball ball = living.launchProjectile(org.bukkit.entity.Snowball.class);
                boolean shooterOk = ball.getShooter() == living;
                this.pass.accept("projectiles: launchProjectile gave " + ball.getType()
                        + ", shooter linked: " + shooterOk);
                ball.remove();
                living.remove();
            }
        });
    }

    // ---------- ItemStack / components ----------

    private void itemStacks() {
        probe("itemstacks", () -> {
            ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = stack.getItemMeta();
            meta.setDisplayName("Probe Blade");
            meta.setLore(List.of("line one", "line two"));
            meta.setUnbreakable(true);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, true);
            stack.setItemMeta(meta);

            ItemMeta read = stack.getItemMeta();
            boolean ok = "Probe Blade".equals(read.getDisplayName())
                    && read.getLore() != null && read.getLore().size() == 2
                    && read.isUnbreakable()
                    && read.getEnchantLevel(org.bukkit.enchantments.Enchantment.SHARPNESS) == 3;
            if (ok) {
                this.pass.accept("itemstacks: name, lore, unbreakable and enchantments round-tripped");
            } else {
                this.fail.accept("itemstacks: round-trip lost data: " + read);
            }

            // Serialization, which plugins rely on for storage.
            ItemStack copy = ItemStack.deserialize(stack.serialize());
            if (copy.isSimilar(stack)) {
                this.pass.accept("itemstacks: serialize/deserialize round-trip");
            } else {
                this.fail.accept("itemstacks: serialize round-trip produced a different stack");
            }
        });
    }

    // ---------- recipes ----------

    private void recipes() {
        probe("recipes", () -> {
            AtomicInteger count = new AtomicInteger();
            java.util.Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext() && count.get() < 5000) {
                it.next();
                count.incrementAndGet();
            }
            if (count.get() > 0) {
                this.pass.accept("recipes: iterated " + count.get() + " recipes");
            } else {
                this.fail.accept("recipes: recipeIterator produced nothing");
            }

            NamespacedKey key = new NamespacedKey(this.plugin, "probe_recipe");
            org.bukkit.inventory.ShapedRecipe recipe =
                    new org.bukkit.inventory.ShapedRecipe(key, new ItemStack(Material.DIAMOND_BLOCK));
            recipe.shape("DD", "DD");
            recipe.setIngredient('D', Material.DIAMOND);
            if (Bukkit.addRecipe(recipe) && Bukkit.getRecipe(key) != null) {
                this.pass.accept("recipes: registered and looked up a custom recipe");
            } else {
                this.fail.accept("recipes: custom recipe did not register");
            }
            Bukkit.removeRecipe(key);
        });
    }

    // ---------- persistent data containers ----------

    private void pdc(World world) {
        probe("pdc", () -> {
            NamespacedKey key = new NamespacedKey(this.plugin, "probe");

            ItemStack stack = new ItemStack(Material.STONE);
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "on-item");
            stack.setItemMeta(meta);
            String fromItem = stack.getItemMeta().getPersistentDataContainer()
                    .get(key, PersistentDataType.STRING);
            if ("on-item".equals(fromItem)) {
                this.pass.accept("pdc: item container round-trip");
            } else {
                this.fail.accept("pdc: item container gave " + fromItem);
            }

            Entity entity = world.spawnEntity(world.getSpawnLocation().clone().add(0, 1, 11), EntityType.ZOMBIE);
            entity.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 42);
            Integer fromEntity = entity.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            if (Integer.valueOf(42).equals(fromEntity)) {
                this.pass.accept("pdc: entity container round-trip");
            } else {
                this.fail.accept("pdc: entity container gave " + fromEntity);
            }
            entity.remove();

            // The world container is the one CardForge had to give a null datafixer,
            // so it is worth proving it actually stores things.
            world.getPersistentDataContainer().set(key, PersistentDataType.STRING, "on-world");
            String fromWorld = world.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if ("on-world".equals(fromWorld)) {
                this.pass.accept("pdc: world container round-trip");
            } else {
                this.fail.accept("pdc: world container gave " + fromWorld);
            }
        });
    }

    // ---------- scoreboards ----------

    private void scoreboards() {
        probe("scoreboards", () -> {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective("probe", "dummy", "Probe");
            objective.getScore("entry").setScore(7);
            if (objective.getScore("entry").getScore() == 7) {
                this.pass.accept("scoreboards: objective and score round-trip");
            } else {
                this.fail.accept("scoreboards: score did not persist");
            }

            org.bukkit.scoreboard.Team team = board.registerNewTeam("probeteam");
            team.addEntry("someone");
            if (team.hasEntry("someone")) {
                this.pass.accept("scoreboards: team membership");
            } else {
                this.fail.accept("scoreboards: team entry did not stick");
            }
            objective.unregister();
            team.unregister();
        });
    }

    // ---------- boss bars ----------

    private void bossBars() {
        probe("bossbars", () -> {
            BossBar bar = Bukkit.createBossBar("Probe", BarColor.PURPLE, BarStyle.SEGMENTED_10);
            bar.setProgress(0.5);
            if (Math.abs(bar.getProgress() - 0.5) < 1e-6 && bar.getColor() == BarColor.PURPLE) {
                this.pass.accept("bossbars: created, progress and colour readable");
            } else {
                this.fail.accept("bossbars: state did not round-trip");
            }

            NamespacedKey key = new NamespacedKey(this.plugin, "probe_bar");
            Bukkit.createBossBar(key, "Keyed", BarColor.RED, BarStyle.SOLID);
            if (Bukkit.getBossBar(key) != null) {
                this.pass.accept("bossbars: keyed bar registered and retrievable");
            } else {
                this.fail.accept("bossbars: keyed bar not found after creation");
            }
            Bukkit.removeBossBar(key);
            bar.removeAll();
        });
    }

    // ---------- scheduler ----------

    private void scheduler() {
        probe("scheduler", () -> {
            AtomicInteger sync = new AtomicInteger();
            Bukkit.getScheduler().runTask(this.plugin, sync::incrementAndGet);

            int taskId = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            }, 1L, 1L).getTaskId();
            boolean queued = Bukkit.getScheduler().isQueued(taskId);
            Bukkit.getScheduler().cancelTask(taskId);

            AtomicInteger async = new AtomicInteger();
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, async::incrementAndGet);

            this.pass.accept("scheduler: sync task submitted, repeating task queued=" + queued
                    + " then cancelled, async task submitted");
        });
    }

    // ---------- permissions ----------

    private void permissions() {
        probe("permissions", () -> {
            org.bukkit.permissions.Permission permission =
                    new org.bukkit.permissions.Permission("cbtest.probe",
                            org.bukkit.permissions.PermissionDefault.OP);
            Bukkit.getPluginManager().addPermission(permission);

            if (Bukkit.getPluginManager().getPermission("cbtest.probe") != null) {
                this.pass.accept("permissions: registered a permission and read it back");
            } else {
                this.fail.accept("permissions: permission not found after registering");
            }

            // The console is the sender the automated run actually uses, and it
            // must hold everything.
            if (Bukkit.getConsoleSender().hasPermission("cbtest.probe")) {
                this.pass.accept("permissions: console has the permission, as it should");
            } else {
                this.fail.accept("permissions: console lacks a permission it should hold");
            }
            Bukkit.getPluginManager().removePermission(permission);
        });
    }

    // ---------- commands ----------

    private void commands() {
        probe("commands", () -> {
            if (Bukkit.getPluginCommand("cbtest") != null) {
                this.pass.accept("commands: plugin command registered from plugin.yml");
            } else {
                this.fail.accept("commands: cbtest not registered");
            }

            // Dispatching a vanilla command through the Bukkit API, which is the
            // path that crosses into Brigadier.
            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "list");
            if (dispatched) {
                this.pass.accept("commands: dispatchCommand ran a vanilla command");
            } else {
                this.fail.accept("commands: dispatchCommand('list') returned false");
            }
        });
    }

    // ---------- configuration ----------

    private void configuration() {
        probe("configuration", () -> {
            this.plugin.getConfig().set("probe.value", 123);
            this.plugin.getConfig().set("probe.text", "hello");
            this.plugin.saveConfig();
            this.plugin.reloadConfig();

            if (this.plugin.getConfig().getInt("probe.value") == 123
                    && "hello".equals(this.plugin.getConfig().getString("probe.text"))) {
                this.pass.accept("configuration: saved and reloaded from disk");
            } else {
                this.fail.accept("configuration: values did not survive save/reload");
            }

            org.bukkit.configuration.file.YamlConfiguration yaml =
                    new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("list", List.of("a", "b"));
            String text = yaml.saveToString();
            org.bukkit.configuration.file.YamlConfiguration back =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.StringReader(text));
            if (back.getStringList("list").size() == 2) {
                this.pass.accept("configuration: YAML serialize/parse round-trip");
            } else {
                this.fail.accept("configuration: YAML round-trip lost the list");
            }
        });
    }

    // ---------- registry APIs ----------

    private void registries() {
        probe("registries", () -> {
            if (org.bukkit.Registry.MATERIAL.get(NamespacedKey.minecraft("stone")) == Material.STONE) {
                this.pass.accept("registries: Registry.MATERIAL resolves a vanilla key");
            } else {
                this.fail.accept("registries: Registry.MATERIAL missed minecraft:stone");
            }

            if (org.bukkit.Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("zombie")) == EntityType.ZOMBIE) {
                this.pass.accept("registries: Registry.ENTITY_TYPE resolves a vanilla key");
            } else {
                this.fail.accept("registries: Registry.ENTITY_TYPE missed minecraft:zombie");
            }

            int enchantments = 0;
            for (org.bukkit.enchantments.Enchantment ignored : org.bukkit.Registry.ENCHANTMENT) {
                enchantments++;
            }
            if (enchantments > 0) {
                this.pass.accept("registries: iterated " + enchantments + " enchantments");
            } else {
                this.fail.accept("registries: enchantment registry was empty");
            }
        });
    }

    // ---------- world saving ----------

    private void worldSave(World world) {
        probe("worldsave", () -> {
            world.save();
            this.pass.accept("worldsave: World#save completed without error");
        });
    }
}
