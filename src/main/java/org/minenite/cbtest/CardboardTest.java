package org.minenite.cbtest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A deliberately small compatibility probe for Cardboard.
 *
 * It covers the two areas that headless testing cannot reach:
 *   - custom inventories (creation, item metadata, click and close events)
 *   - NMS reflection (getHandle, server internals, field access)
 *
 * Every probe reports PASS/FAIL individually so a partial failure is still
 * informative, and nothing throws out of a command handler.
 */
public final class CardboardTest extends JavaPlugin implements Listener, CommandExecutor {

    private static final String GUI_TITLE = ChatColor.GOLD + "Cardboard Probe";

    private final List<String> results = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("cbtest").setExecutor(this);
        getLogger().info("CardboardTest enabled. Run /cbtest all");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String mode = args.length == 0 ? "all" : args[0].toLowerCase();

        // The cross-ecosystem probes only need a world, so they can run from the
        // console. That matters because it lets the whole mod-compatibility check
        // run in an automated boot test, with no client attached.
        if (!(sender instanceof Player player)) {
            if (mode.equals("mods")) {
                this.results.clear();
                this.probeModdedContent(null);
                this.report(sender);
                return true;
            }
            sender.sendMessage("CardboardTest: only 'mods' can run from the console.");
            return true;
        }

        this.results.clear();
        switch (mode) {
            case "gui" -> this.openGui(player);
            case "nms" -> this.probeNms(player);
            case "meta" -> this.probeItemMeta(player);
            case "mods" -> this.probeModdedContent(player);
            default -> {
                this.probeNms(player);
                this.probeItemMeta(player);
                this.probeModdedContent(player);
                this.openGui(player);
            }
        }

        this.report(player);
        return true;
    }

    private void report(CommandSender sender) {
        for (String line : this.results) {
            sender.sendMessage(line);
            getLogger().info(ChatColor.stripColor(line));
        }
    }

    // ---------- custom inventory ----------

    private void openGui(Player player) {
        try {
            Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

            inv.setItem(11, named(Material.DIAMOND, ChatColor.AQUA + "Click me",
                    List.of(ChatColor.GRAY + "Fires InventoryClickEvent")));
            inv.setItem(13, named(Material.GOLD_INGOT, ChatColor.YELLOW + "Lore probe",
                    List.of(ChatColor.GRAY + "line one", ChatColor.DARK_GRAY + "line two")));
            inv.setItem(15, named(Material.REDSTONE, ChatColor.RED + "Close me",
                    List.of(ChatColor.GRAY + "Closes the GUI from the click handler")));

            player.openInventory(inv);
            pass("inventory: created 27-slot GUI and opened it");
        } catch (Throwable t) {
            fail("inventory: " + t);
        }
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------- item metadata round-trip ----------

    private void probeItemMeta(Player player) {
        try {
            ItemStack stack = named(Material.PAPER, ChatColor.LIGHT_PURPLE + "Round trip",
                    List.of(ChatColor.GRAY + "lore a", ChatColor.GRAY + "lore b"));
            ItemMeta meta = stack.getItemMeta();

            String name = meta.getDisplayName();
            List<String> lore = meta.getLore();
            boolean nameOk = name != null && name.contains("Round trip");
            boolean loreOk = lore != null && lore.size() == 2 && lore.get(1).contains("lore b");

            if (nameOk && loreOk) {
                pass("item meta: display name and " + lore.size() + " lore lines survived the round trip");
            } else {
                fail("item meta: name=" + name + " lore=" + lore);
            }

            player.getInventory().addItem(stack);
            pass("item meta: added the probe item to the player inventory");
        } catch (Throwable t) {
            fail("item meta: " + t);
        }
    }

    // ---------- cross-ecosystem: NeoForge mod content through the Bukkit API ----------

    /**
     * The point of CardForge is that Bukkit plugins and NeoForge mods share one
     * server, so the interesting question is not whether either works alone but
     * whether a plugin can see and manipulate content a mod registered.
     *
     * These probes are skipped, not failed, when the mod is absent, so the plugin
     * stays useful on a server with no mods installed.
     */
    private void probeModdedContent(Player player) {
        final String moddedBlockId = "waystones:andesite_waystone";
        // Deliberately an item with no block form, so this probe cannot be
        // satisfied by the block registration path.
        final String moddedItemId = "waystones:bound_scroll";

        // 1. A modded block should have been injected into the Material registry.
        Material block = matchModded(moddedBlockId);
        if (block == null) {
            skip("modded: " + moddedBlockId + " not present (mod not installed?) - skipping mod probes");
            this.describeRegistry("waystones");
            return;
        }
        pass("modded: block " + moddedBlockId + " -> Material." + block.name());

        // Does Paper's own type registry already know the mod's content? If it
        // does, the only thing wrong with modded Materials is their key field.
        for (String id : new String[]{moddedBlockId, "waystones:bound_scroll"}) {
            NamespacedKey k = NamespacedKey.fromString(id);
            try {
                pass("modded: Registry.ITEM.get(" + id + ") -> " + org.bukkit.Registry.ITEM.get(k));
            } catch (Throwable t) {
                fail("modded: Registry.ITEM.get(" + id + "): " + t);
            }
            try {
                pass("modded: Registry.BLOCK.get(" + id + ") -> " + org.bukkit.Registry.BLOCK.get(k));
            } catch (Throwable t) {
                fail("modded: Registry.BLOCK.get(" + id + "): " + t);
            }
        }

        // 2. NamespacedKey should round-trip back to the mod's own id.
        try {
            NamespacedKey key = block.getKey();
            if (moddedBlockId.equals(key.toString())) {
                pass("modded: Material#getKey round-tripped to " + key);
            } else {
                fail("modded: key round-trip gave " + key + ", expected " + moddedBlockId);
            }
        } catch (Throwable t) {
            fail("modded: Material#getKey: " + t);
        }

        // 3. A modded item should resolve and survive being made into an ItemStack.
        Material item = matchModded(moddedItemId);
        if (item == null) {
            fail("modded: item " + moddedItemId + " did not resolve to a Material"
                    + " (blocks registered but items did not)");
        } else {
            try {
                ItemStack stack = new ItemStack(item);
                String where = "";
                if (player != null) {
                    player.getInventory().addItem(stack);
                    where = ", given to player";
                }
                pass("modded: item " + moddedItemId + " -> Material." + item.name()
                        + ", ItemStack created (amount " + stack.getAmount() + ")" + where);
            } catch (Throwable t) {
                fail("modded: ItemStack of " + moddedItemId + ": " + t);
            }
        }

        // 4. The real test: place the modded block through the Bukkit API and read
        //    it back. This exercises Bukkit -> NMS block state conversion for a
        //    block that only exists because a NeoForge mod registered it.
        try {
            org.bukkit.Location at = player != null
                    ? player.getLocation().add(0, -1, 2)
                    : Bukkit.getWorlds().get(0).getSpawnLocation().add(0, -1, 2);
            org.bukkit.block.Block target = at.getBlock();
            Material previous = target.getType();
            target.setType(block);

            Material readBack = target.getType();
            if (readBack == block) {
                pass("modded: placed " + block.name() + " via Bukkit and read it back");
            } else {
                fail("modded: placed " + block.name() + " but read back " + readBack);
            }

            // A modded block placed by a plugin should also carry its mod's block
            // entity, which is what makes the mod's own behaviour work.
            org.bukkit.block.BlockState state = target.getState();
            pass("modded: block state -> " + state.getClass().getSimpleName());

            target.setType(previous);
        } catch (Throwable t) {
            fail("modded: place/read modded block: " + t);
        }

        // 5. Material.values() must show vanilla and modded content together.
        //    This is the regression check for the values() call-site rewrite: this
        //    plugin jar is precompiled against stock paper-api, so the call was
        //    emitted as a plain invokestatic to org/bukkit/Material.values() and is
        //    only redirected if CardForge rewrote it at class-load time.
        Material[] all = Material.values();
        int moddedInValues = 0;
        boolean vanillaInValues = false;
        for (Material m : all) {
            if (m.name().startsWith("WAYSTONES_")) {
                moddedInValues++;
            } else if (m == Material.STONE) {
                vanillaInValues = true;
            }
        }

        // Duplicates would mean the extended set was appended to a snapshot that
        // already contained it - plugins would then see every modded material twice.
        java.util.Set<Material> unique = new java.util.HashSet<>(java.util.Arrays.asList(all));
        if (unique.size() != all.length) {
            fail("values: Material.values() has " + all.length + " entries but only "
                    + unique.size() + " distinct - duplicates present");
        } else {
            pass("values: Material.values() has no duplicates");
        }

        if (!vanillaInValues) {
            fail("values: vanilla STONE missing from Material.values() (length " + all.length + ")");
        } else if (moddedInValues == 0) {
            fail("values: Material.values() has " + all.length
                    + " entries but no modded materials - call-site rewrite did not apply");
        } else {
            pass("values: Material.values() has " + all.length + " entries, including vanilla"
                    + " and " + moddedInValues + " waystones materials");
        }

        // 6. Ordinary lookup must be untouched by the rewrite. Only values() is
        //    redirected; valueOf, getMaterial and the registries must behave
        //    exactly as before, for vanilla and modded alike.
        try {
            boolean ok = Material.valueOf("STONE") == Material.STONE
                    && Material.getMaterial("STONE") == Material.STONE
                    && Material.matchMaterial("stone") == Material.STONE
                    && org.bukkit.Registry.MATERIAL.get(NamespacedKey.minecraft("stone")) == Material.STONE
                    && Material.STONE.getKey().toString().equals("minecraft:stone")
                    && Material.STONE.isBlock()
                    && Material.getMaterial("NOT_A_REAL_MATERIAL") == null;
            if (ok) {
                pass("values: vanilla lookup unchanged (valueOf, getMaterial, matchMaterial, Registry, getKey, isBlock)");
            } else {
                fail("values: vanilla lookup behaviour changed");
            }
        } catch (Throwable t) {
            fail("values: vanilla lookup threw " + t);
        }

        try {
            boolean ok = Material.valueOf(block.name()) == block
                    && Material.getMaterial(block.name()) == block;
            if (ok) {
                pass("values: modded lookup by name agrees with values()");
            } else {
                fail("values: modded lookup by name disagrees with values()");
            }
        } catch (Throwable t) {
            fail("values: modded lookup threw " + t);
        }
    }

    /** Resolves a namespaced mod id to whatever Material name CardForge gave it. */
    private Material matchModded(String namespacedId) {
        String expected = namespacedId.replace(':', '_').toUpperCase();

        Material byName = Material.getMaterial(expected);
        if (byName != null) {
            return byName;
        }
        // getMaterial() reads a name map that enum extension may not have updated,
        // so fall back to scanning values(), and vice versa.
        for (Material m : Material.values()) {
            if (m.name().equals(expected)) {
                return m;
            }
        }
        return null;
    }

    /** Reports what modded content is actually reachable, so a miss is diagnosable. */
    private void describeRegistry(String namespace) {
        int total = 0;
        List<String> sample = new ArrayList<>();
        for (Material m : Material.values()) {
            total++;
            if (m.name().toUpperCase().contains(namespace.toUpperCase()) && sample.size() < 5) {
                sample.add(m.name());
            }
        }
        skip("modded: Material.values() has " + total + " entries; matching '"
                + namespace + "': " + (sample.isEmpty() ? "none" : String.join(", ", sample)));

        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(namespace + ":andesite_waystone");
            skip("modded: Registry.MATERIAL lookup of " + key + " -> "
                    + org.bukkit.Registry.MATERIAL.get(key));
        } catch (Throwable t) {
            skip("modded: Registry.MATERIAL lookup threw " + t);
        }
    }

    // ---------- NMS reflection ----------

    private void probeNms(Player player) {
        // 1. CraftPlayer#getHandle via reflection - the classic NMS entry point
        Object handle = null;
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            handle = getHandle.invoke(player);
            pass("nms: CraftPlayer#getHandle -> " + handle.getClass().getName());
        } catch (Throwable t) {
            fail("nms: getHandle failed: " + t);
        }

        // 2. Walk to a known NMS field on the handle
        if (handle != null) {
            try {
                Field connection = findField(handle.getClass(), "connection");
                connection.setAccessible(true);
                Object conn = connection.get(handle);
                pass("nms: ServerPlayer#connection -> " + (conn == null ? "null" : conn.getClass().getSimpleName()));
            } catch (Throwable t) {
                fail("nms: connection field: " + t);
            }
        }

        // 3. Reach the dedicated server through the Bukkit server implementation
        try {
            Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            Object nmsServer = getServer.invoke(Bukkit.getServer());
            pass("nms: CraftServer#getServer -> " + nmsServer.getClass().getName());
        } catch (Throwable t) {
            fail("nms: CraftServer#getServer: " + t);
        }

        // 4. Load an NMS class by name, proving the unobfuscated names resolve
        try {
            Class<?> level = Class.forName("net.minecraft.server.level.ServerLevel");
            pass("nms: Class.forName ServerLevel -> " + level.getSimpleName());
        } catch (Throwable t) {
            fail("nms: Class.forName ServerLevel: " + t);
        }

        // 5. CraftWorld#getHandle, the world-side equivalent of getHandle
        try {
            Method getHandle = player.getWorld().getClass().getMethod("getHandle");
            Object nmsWorld = getHandle.invoke(player.getWorld());
            pass("nms: CraftWorld#getHandle -> " + nmsWorld.getClass().getSimpleName());
        } catch (Throwable t) {
            fail("nms: CraftWorld#getHandle: " + t);
        }
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name + " not found on " + type.getName());
    }

    // ---------- events ----------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!GUI_TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        String what = clicked == null ? "empty slot"
                : clicked.getType() + (clicked.hasItemMeta() && clicked.getItemMeta().hasDisplayName()
                        ? " (" + ChatColor.stripColor(clicked.getItemMeta().getDisplayName()) + ")" : "");
        event.getWhoClicked().sendMessage(ChatColor.GREEN + "[PASS] " + ChatColor.RESET
                + "click event fired on slot " + event.getSlot() + ": " + what);
        getLogger().info("InventoryClickEvent slot=" + event.getSlot() + " item=" + what);

        // Closing from inside the click handler is its own probe: it exercises
        // closeInventory() being called during event dispatch.
        if (clicked != null && clicked.getType() == Material.REDSTONE) {
            Bukkit.getScheduler().runTask(this, () -> event.getWhoClicked().closeInventory());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!GUI_TITLE.equals(event.getView().getTitle())) return;
        event.getPlayer().sendMessage(ChatColor.GREEN + "[PASS] " + ChatColor.RESET + "close event fired");
        getLogger().info("InventoryCloseEvent fired");
    }

    // ---------- reporting ----------

    private void pass(String message) {
        this.results.add(ChatColor.GREEN + "[PASS] " + ChatColor.RESET + message);
    }

    private void skip(String message) {
        this.results.add(ChatColor.YELLOW + "[SKIP] " + ChatColor.RESET + message);
    }

    private void fail(String message) {
        this.results.add(ChatColor.RED + "[FAIL] " + ChatColor.RESET + message);
    }
}
