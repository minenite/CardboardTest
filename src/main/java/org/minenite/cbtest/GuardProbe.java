package org.minenite.cbtest;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

/**
 * A protection plugin in miniature, for testing cancellation across the bridge.
 *
 * <p>The question this answers is not "did the Bukkit event fire" - that much is
 * already covered - but "did cancelling it actually stop the NeoForge operation".
 * Those are different claims, and only the second one is what a protection plugin
 * is really promising. An event that fires and is then ignored is arguably worse
 * than one that never fires, because the plugin reports success.
 *
 * <p>Toggle with {@code /cbtest guard}. While armed it cancels block placement,
 * block breaking, right-click interaction and entity damage, and reports every
 * cancellation so the server side can be compared against what the client saw.
 */
final class GuardProbe implements Listener {

    private static GuardProbe active;

    private final Plugin plugin;
    private final List<String> seen = new ArrayList<>();

    private GuardProbe(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Arms or disarms the guard. Returns true if it is now armed. */
    static boolean toggle(Plugin plugin) {
        if (active != null) {
            HandlerList.unregisterAll(active);
            active.report();
            active = null;
            return false;
        }
        active = new GuardProbe(plugin);
        Bukkit.getPluginManager().registerEvents(active, plugin);
        return true;
    }

    // Highest priority so nothing else has already decided, and ignoreCancelled
    // deliberately false so an already-cancelled event is still recorded.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
        note("BlockPlaceEvent", event.getBlockPlaced().getType().name()
                + " at " + brief(event.getBlockPlaced().getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
        note("BlockBreakEvent", event.getBlock().getType().name()
                + " at " + brief(event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        note("PlayerInteractEvent", event.getAction() + " on "
                + event.getClickedBlock().getType().name());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        event.setCancelled(true);
        note("EntityDamageEvent", event.getEntity().getType() + " cause=" + event.getCause());
    }

    private void note(String event, String detail) {
        String line = "[GUARD] cancelled " + event + ": " + detail;
        this.seen.add(line);
        this.plugin.getLogger().info(line);
    }

    private static String brief(org.bukkit.Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private void report() {
        this.plugin.getLogger().info("[GUARD] disarmed after " + this.seen.size() + " cancellations");
        for (String line : this.seen) {
            this.plugin.getLogger().info("[GUARD]   " + line);
        }
    }
}
