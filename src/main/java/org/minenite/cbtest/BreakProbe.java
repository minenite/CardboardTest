package org.minenite.cbtest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

/**
 * Proves the BlockBreakEvent bridge fires once and composes in both directions.
 *
 * <p>Cardboard fired the Bukkit event from a HEAD inject on
 * {@code ServerPlayerGameMode#destroyBlock}, while NeoForge fires its own
 * {@code BreakBlockEvent} inside the same method. Two events for one break, with
 * nothing reconciling them. The count check below is the regression test for
 * that specific defect: it would have read 2.
 *
 * <p>The cancel check is the other half. A plugin cancelling has to reach
 * NeoForge's event, or protection plugins would appear to work while the mod
 * side of the break carried on.
 *
 * <p>Drives {@code destroyBlock} reflectively because Bukkit's
 * {@code Player#breakBlock} is not implemented here, and going through the real
 * game-mode method is the point - a synthetic event would test nothing.
 */
final class BreakProbe implements Listener {

    private int fired;
    private boolean cancelNext;

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        this.fired++;
        if (this.cancelNext) {
            event.setCancelled(true);
        }
    }

    static void run(Plugin plugin, Player player, Consumer<String> pass, Consumer<String> fail) {
        BreakProbe probe = new BreakProbe();
        Location at = player.getLocation().add(0, 3, 2).getBlock().getLocation();
        Block block = at.getBlock();
        Material original = block.getType();
        // Spectator and adventure make NeoForge pre-cancel the break, which is a
        // different path; it gets its own check below. The main assertions need a
        // mode where a break is actually allowed.
        org.bukkit.GameMode mode = player.getGameMode();
        try {
            plugin.getServer().getPluginManager().registerEvents(probe, plugin);
            if (mode != org.bukkit.GameMode.SURVIVAL) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }

            // 1. An uncancelled break: exactly one event, and the block goes.
            block.setType(Material.STONE);
            probe.fired = 0;
            probe.cancelNext = false;
            Boolean destroyed = destroyBlock(player, block);
            if (destroyed == null) {
                fail.accept("blockbreak: could not reach ServerPlayerGameMode#destroyBlock");
                return;
            }
            if (probe.fired == 1) {
                pass.accept("blockbreak: BlockBreakEvent fired exactly once (was 2 before the bridge)");
            } else {
                fail.accept("blockbreak: BlockBreakEvent fired " + probe.fired + " times, expected 1");
            }
            if (block.getType() == Material.AIR) {
                pass.accept("blockbreak: an uncancelled break removed the block");
            } else {
                fail.accept("blockbreak: block survived an uncancelled break: " + block.getType());
            }

            // 2. A cancelled break has to reach NeoForge's event, or the mod side
            // proceeds while the plugin believes it stopped the break.
            block.setType(Material.STONE);
            probe.fired = 0;
            probe.cancelNext = true;
            Boolean second = destroyBlock(player, block);
            if (Boolean.FALSE.equals(second) && block.getType() == Material.STONE) {
                pass.accept("blockbreak: plugin cancellation reached NeoForge, block intact");
            } else {
                fail.accept("blockbreak: cancellation not honoured - destroyBlock returned " + second
                        + ", block is " + block.getType());
            }
            // 3. A break NeoForge pre-cancels - here by putting the player in
            // spectator, which trips blockActionRestricted. NeoForge posts the event
            // already cancelled, and a listener that does not opt into cancelled
            // events never runs, so the Bukkit event would silently not fire at all.
            // Paper fires it cancelled and lets a plugin overturn it, so this is the
            // regression test for that difference.
            block.setType(Material.STONE);
            probe.fired = 0;
            probe.cancelNext = false;
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
            destroyBlock(player, block);
            if (probe.fired == 1) {
                pass.accept("blockbreak: a NeoForge pre-cancelled break still notifies plugins");
            } else {
                fail.accept("blockbreak: pre-cancelled break fired " + probe.fired
                        + " events, expected 1 - listener is not receiving cancelled events");
            }
            if (block.getType() == Material.STONE) {
                pass.accept("blockbreak: pre-cancelled break left the block intact");
            } else {
                fail.accept("blockbreak: pre-cancelled break removed the block");
            }
        } catch (Throwable t) {
            fail.accept("blockbreak: " + t);
        } finally {
            HandlerList.unregisterAll(probe);
            block.setType(original == Material.AIR ? Material.AIR : original);
            player.setGameMode(mode);
        }
    }

    /**
     * Damages a held tool through {@code ItemStack#hurtAndBreak(int, LivingEntity,
     * EquipmentSlot)} and checks PlayerItemDamageEvent fires.
     *
     * <p>That method is the path every tool, weapon and armour piece takes.
     * Vanilla routed it to the ServerPlayer overload of hurtAndBreak; NeoForge
     * passes the LivingEntity directly, which resolves to a wider overload, so a
     * hook on the narrow signature applied cleanly and stopped firing. Nothing
     * else notices: durability still ticks down, so the only symptom is that
     * plugins cancelling item damage are silently ignored.
     */
    static void runItemDamage(Plugin plugin, Player player, Consumer<String> pass, Consumer<String> fail) {
        ItemDamageListener probe = new ItemDamageListener();
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        // A creative player takes no durability damage at all - NeoForge returns 0
        // from processDurabilityChange for hasInfiniteMaterials - so the event
        // correctly does not fire and the probe would report a false failure.
        org.bukkit.GameMode mode = player.getGameMode();
        try {
            plugin.getServer().getPluginManager().registerEvents(probe, plugin);
            if (mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_PICKAXE));

            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object nmsStack = handle.getClass().getMethod("getMainHandItem").invoke(handle);
            Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity");
            Class<?> slotType = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            Object mainHand = Enum.valueOf((Class<Enum>) slotType.asSubclass(Enum.class), "MAINHAND");
            Method hurt = nmsStack.getClass().getMethod("hurtAndBreak", int.class, living, slotType);
            hurt.invoke(nmsStack, 1, handle, mainHand);

            if (probe.fired > 0) {
                pass.accept("itemdamage: PlayerItemDamageEvent fired from hurtAndBreak(LivingEntity, slot)"
                        + " (game mode was " + mode + ")");
            } else {
                fail.accept("itemdamage: PlayerItemDamageEvent did not fire from"
                        + " hurtAndBreak(LivingEntity, slot); tested in "
                        + player.getGameMode() + ", original mode " + mode);
            }
        } catch (Throwable t) {
            fail.accept("itemdamage: " + t);
        } finally {
            HandlerList.unregisterAll(probe);
            player.getInventory().setItemInMainHand(held);
            player.setGameMode(mode);
        }
    }

    static final class ItemDamageListener implements Listener {
        int fired;

        @EventHandler
        public void onDamage(org.bukkit.event.player.PlayerItemDamageEvent event) {
            this.fired++;
        }
    }

    /** Calls the real game-mode method; null if it could not be reached. */
    private static Boolean destroyBlock(Player player, Block block) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object gameMode = null;
        for (Class<?> c = handle.getClass(); c != null && gameMode == null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("ServerPlayerGameMode")) {
                    f.setAccessible(true);
                    gameMode = f.get(handle);
                    break;
                }
            }
        }
        if (gameMode == null) {
            return null;
        }
        Class<?> posType = Class.forName("net.minecraft.core.BlockPos");
        Object pos = posType.getConstructor(int.class, int.class, int.class)
                .newInstance(block.getX(), block.getY(), block.getZ());
        for (Method m : gameMode.getClass().getMethods()) {
            if (m.getName().equals("destroyBlock") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return (Boolean) m.invoke(gameMode, pos);
            }
        }
        return null;
    }
}
