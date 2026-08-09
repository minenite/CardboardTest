package org.minenite.cbtest;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;

/**
 * Locates why permission checks always succeed.
 *
 * <p>Reports each link in Bukkit's permission chain separately, because
 * "hasPermission returned true" does not say whether the fault is the op check,
 * the permission map, the registered default, or an injected Permissible from
 * another plugin.
 */
final class PermProbe {

    static void run(Plugin plugin, Player player) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String missing = "cbtest.definitely.not.registered.node";

        out.add("[INFO] perm: player=" + player.getName() + " uuid=" + player.getUniqueId()
                + " isOp=" + player.isOp());
        StringBuilder ops = new StringBuilder();
        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) {
            ops.append(op.getName()).append('/').append(op.getUniqueId()).append(' ');
        }
        out.add("[INFO] perm: Bukkit.getOperators() = " + (ops.length() == 0 ? "<empty>" : ops));

        // Ask NMS directly, the same way CraftPlayer#isOp does, and show what
        // identity it is matching on.
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object nameAndId = handle.getClass().getMethod("nameAndId").invoke(handle);
            out.add("[INFO] perm: handle.nameAndId() = " + nameAndId);
        } catch (Throwable t) {
            out.add("[INFO] perm: nameAndId reflection failed: " + t);
        }
        out.add("[INFO] perm: hasPermission(missing)=" + player.hasPermission(missing));

        // The op field on CraftHumanEntity was never seeded from the op list, so a
        // player listed in ops.json read as not-op through the Bukkit API until
        // something called setOp. PermissibleBase resolves an unset permission from
        // isOp(), so every plugin permission defaulting to op was denied - quietly,
        // because "denied" is a normal answer. Vanilla commands kept working, which
        // is what made the two disagree and hid it.
        boolean listOp = false;
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object nameAndId = handle.getClass().getMethod("nameAndId").invoke(handle);
            Object playerList = org.bukkit.Bukkit.getServer().getClass()
                    .getMethod("getHandle").invoke(org.bukkit.Bukkit.getServer());
            for (java.lang.reflect.Method m : playerList.getClass().getMethods()) {
                if (m.getName().equals("isOp") && m.getParameterCount() == 1) {
                    listOp = (Boolean) m.invoke(playerList, nameAndId);
                    break;
                }
            }
        } catch (Throwable t) {
            out.add("[INFO] perm: could not read the server op list: " + t);
        }
        if (player.isOp() == listOp) {
            out.add("[PASS] perm: Player#isOp() agrees with the server op list (" + listOp + ")");
        } else {
            out.add("[FAIL] perm: Player#isOp()=" + player.isOp()
                    + " but the server op list says " + listOp
                    + " - every op-default plugin permission is resolving wrongly");
        }

        // An op must resolve a permission that defaults to op. Registered here so
        // the check does not depend on any particular plugin being installed.
        String opDefault = "cardboardtest.opdefault";
        if (org.bukkit.Bukkit.getPluginManager().getPermission(opDefault) == null) {
            org.bukkit.Bukkit.getPluginManager().addPermission(
                    new org.bukkit.permissions.Permission(opDefault,
                            org.bukkit.permissions.PermissionDefault.OP));
        }
        player.recalculatePermissions();
        boolean got = player.hasPermission(opDefault);
        if (got == listOp) {
            out.add("[PASS] perm: an op-default permission resolves to " + got + " for this player");
        } else {
            out.add("[FAIL] perm: op-default permission resolved " + got
                    + " but the player's op state is " + listOp);
        }
        out.add("[INFO] perm: isPermissionSet(missing)=" + player.isPermissionSet(missing));
        out.add("[INFO] perm: getPluginManager().getPermission(missing)="
                + Bukkit.getPluginManager().getPermission(missing));
        out.add("[INFO] perm: Permission.DEFAULT_PERMISSION=" + Permission.DEFAULT_PERMISSION
                + " value(op=false)=" + Permission.DEFAULT_PERMISSION.getValue(false));

        // Go straight at the NMS op list: the raw keys, and what contains() says
        // for this exact player. Everything else has been inference.
        try {
            Object craftServer = Bukkit.getServer();
            Object dedicated = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object playerList = dedicated.getClass().getMethod("getPlayerList").invoke(dedicated);
            Object opList = playerList.getClass().getMethod("getOps").invoke(playerList);

            Object rawList = opList.getClass().getMethod("getUserList").invoke(opList);
            out.add("[INFO] perm: ops.getUserList() = " + java.util.Arrays.toString((Object[]) rawList));

            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object nameAndId = handle.getClass().getMethod("nameAndId").invoke(handle);

            java.lang.reflect.Method contains = null;
            for (java.lang.reflect.Method m : opList.getClass().getMethods()) {
                if (m.getName().equals("contains") && m.getParameterCount() == 1) { contains = m; break; }
            }
            if (contains != null) {
                contains.setAccessible(true);
                out.add("[INFO] perm: ops.contains(nameAndId) = " + contains.invoke(opList, nameAndId));
            }

            java.lang.reflect.Method isOpM = null;
            for (java.lang.reflect.Method m : playerList.getClass().getMethods()) {
                if (m.getName().equals("isOp") && m.getParameterCount() == 1) { isOpM = m; break; }
            }
            if (isOpM != null) {
                out.add("[INFO] perm: PlayerList.isOp(nameAndId) = " + isOpM.invoke(playerList, nameAndId));
            }
        } catch (Throwable t) {
            out.add("[FAIL] perm: NMS op-list probe failed: " + t);
        }

        // Which Permissible is actually in the field? LuckPerms replaces it.
        try {
            Class<?> c = player.getClass();
            Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("perm");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Object permissible = f.get(player);
                out.add("[INFO] perm: perm field = " + (permissible == null ? "null"
                        : permissible.getClass().getName()));
                if (permissible instanceof org.bukkit.permissions.Permissible p) {
                    out.add("[INFO] perm: perm.hasPermission(missing)=" + p.hasPermission(missing));
                    out.add("[INFO] perm: perm.isPermissionSet(missing)=" + p.isPermissionSet(missing));
                    out.add("[INFO] perm: perm.getEffectivePermissions().size()="
                            + p.getEffectivePermissions().size());
                }
            } else {
                out.add("[FAIL] perm: no 'perm' field found on " + player.getClass().getName());
            }
        } catch (Throwable t) {
            out.add("[FAIL] perm: reflection failed: " + t);
        }

        // A registered NOT_OP permission should be false for an op and true otherwise.
        Permission probe = new Permission("cbtest.notop.probe",
                org.bukkit.permissions.PermissionDefault.FALSE);
        Bukkit.getPluginManager().addPermission(probe);
        out.add("[INFO] perm: hasPermission(FALSE-default node)="
                + player.hasPermission("cbtest.notop.probe") + " (expected false)");
        Bukkit.getPluginManager().removePermission(probe);

        for (String line : out) {
            plugin.getLogger().info(line);
            player.sendMessage(line);
        }
    }
}
