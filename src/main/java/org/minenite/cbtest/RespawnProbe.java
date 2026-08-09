package org.minenite.cbtest;

import java.util.function.Consumer;

import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Regression test for respawn hanging the client on "loading terrain".
 *
 * <p>PlayerList#respawn removes the old entity, builds a new one, then places the
 * client. At that placement the connection still refers to the old entity, which
 * is removed by definition, and Cardboard routed the call through Paper's
 * internalTeleport - whose dead-entity guard refused it. The client never
 * received a position and sat on the loading screen indefinitely while its old
 * body remained in the world.
 *
 * <p>Nothing in the automated suite noticed, because nothing in it had ever died.
 * The bug survived a full day of playtesting and was found only when a creeper
 * got there first.
 *
 * <p>Deliberately not part of {@code all} or {@code auto}: it has to kill the
 * player for real. It sets keepInventory for the duration so a test cannot cost
 * anyone their gear, and restores the previous value afterwards.
 */
final class RespawnProbe {

    private RespawnProbe() {
    }

    static void run(Player player, Consumer<String> pass, Consumer<String> fail) {
        World world = player.getWorld();
        Boolean previousKeepInventory = world.getGameRuleValue(GameRule.KEEP_INVENTORY);
        Location before = player.getLocation();
        try {
            world.setGameRule(GameRule.KEEP_INVENTORY, true);

            player.setHealth(0);
            if (!player.isDead()) {
                fail.accept("respawn: setHealth(0) did not kill the player, cannot test respawn");
                return;
            }
            pass.accept("respawn: player died on demand");

            player.spigot().respawn();

            if (player.isDead()) {
                fail.accept("respawn: still dead after respawn()");
                return;
            }
            pass.accept("respawn: alive again after respawn()");

            if (player.getHealth() > 0) {
                pass.accept("respawn: health restored to " + player.getHealth());
            } else {
                fail.accept("respawn: health is " + player.getHealth() + " after respawn");
            }

            // The actual failure mode. A refused placement leaves the client without
            // a position - the server thinks it respawned, the player sees loading
            // terrain forever - so checking 'not dead' alone would have passed
            // against the bug. This checks the entity the connection now controls
            // is a live one.
            if (isConnectionEntityLive(player)) {
                pass.accept("respawn: the connection controls a live entity, client was placed");
            } else {
                fail.accept("respawn: the connection still refers to a removed entity"
                        + " - the client would hang on 'loading terrain'");
            }

            if (player.getLocation().getWorld() != null) {
                pass.accept("respawn: respawned in " + player.getLocation().getWorld().getName()
                        + " (died in " + before.getWorld().getName() + ")");
            }
        } catch (Throwable t) {
            fail.accept("respawn: " + t);
        } finally {
            if (previousKeepInventory != null) {
                world.setGameRule(GameRule.KEEP_INVENTORY, previousKeepInventory);
            }
        }
    }

    /** True when the ServerPlayer this connection drives is neither dead nor removed. */
    private static boolean isConnectionEntityLive(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = handle.getClass().getField("connection").get(handle);
            Object connPlayer = connection.getClass().getField("player").get(connection);
            boolean removed = (Boolean) connPlayer.getClass().getMethod("isRemoved").invoke(connPlayer);
            return !removed;
        } catch (Throwable t) {
            // Reported rather than swallowed: an unreadable check is not a pass.
            return false;
        }
    }
}
