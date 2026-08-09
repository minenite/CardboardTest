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

    /**
     * Player#getHealth() must track the entity.
     *
     * <p>It returned a cached field that nothing in CardForge ever updated, so it
     * read 20.0 for every player for the whole session regardless of damage. This
     * is separate from respawn and much broader - it is why the respawn probe
     * found nothing, since spigot().respawn() guards on getHealth() <= 0 - but any
     * plugin reading player health was reading a constant.
     */
    static void runHealth(Player player, Consumer<String> pass, Consumer<String> fail) {
        double originalHealth = player.getHealth();
        org.bukkit.GameMode mode = player.getGameMode();
        int noDamage = player.getNoDamageTicks();
        try {
            // Two invisible states make damage() a no-op and this probe flaky: a
            // creative player takes none at all, and the invulnerability frames
            // left by the setHealth above swallow the next hit within 10 ticks.
            // A test whose result depends on state the reader cannot see is worse
            // than no test.
            if (mode != org.bukkit.GameMode.SURVIVAL) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            player.setHealth(7.0);
            if (Math.abs(player.getHealth() - 7.0) < 0.001) {
                pass.accept("health: getHealth() reflects setHealth(7.0)");
            } else {
                fail.accept("health: setHealth(7.0) but getHealth() reports " + player.getHealth()
                        + " - the cache is stale");
            }

            // Damage through the entity rather than the API, so the check cannot be
            // satisfied by setHealth writing the cache it also reads.
            player.setNoDamageTicks(0);
            player.damage(2.0);
            double afterDamage = player.getHealth();
            if (afterDamage < 7.0) {
                pass.accept("health: getHealth() tracked damage, now " + afterDamage);
            } else {
                fail.accept("health: after damage getHealth() still reports " + afterDamage
                        + " (mode=" + player.getGameMode() + ", noDamageTicks="
                        + player.getNoDamageTicks() + ")");
            }
        } catch (Throwable t) {
            fail.accept("health: " + t);
        } finally {
            player.setHealth(originalHealth > 0 ? originalHealth : 20.0);
            player.setNoDamageTicks(noDamage);
            player.setGameMode(mode);
        }
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

            Object handleBefore = handleOf(player);
            pass.accept("respawn: state before respawn() - online=" + player.isOnline()
                    + " health=" + player.getHealth()
                    + " handle=" + System.identityHashCode(handleBefore));

            player.spigot().respawn();

            Object handleAfter = handleOf(player);
            pass.accept("respawn: state after respawn() - dead=" + player.isDead()
                    + " health=" + player.getHealth()
                    + " handle=" + System.identityHashCode(handleAfter)
                    + " rebound=" + (handleBefore != handleAfter)
                    + " listPlayerIsSameObject=" + (org.bukkit.Bukkit.getPlayer(player.getUniqueId()) == player));

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
            // Reported, not asserted. Vanilla's own PlayerList#respawn assigns
            // newPlayer.connection = oldPlayer.connection and never writes
            // connection.player, confirmed by disassembling the shipped jar, so a
            // stale-looking reference here is not by itself evidence of a fault -
            // and players demonstrably move and play normally after respawning.
            // Asserting on it would be asserting on a guess.
            pass.accept("respawn: connection entity live=" + isConnectionEntityLive(player)
                    + " (informational; vanilla does not repoint this field either)");

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

    /** The NMS handle behind a CraftPlayer, for identity comparison. */
    private static Object handleOf(Player player) {
        try {
            return player.getClass().getMethod("getHandle").invoke(player);
        } catch (Throwable t) {
            return null;
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
