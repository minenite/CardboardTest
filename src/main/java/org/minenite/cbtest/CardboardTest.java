package org.minenite.cbtest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("CardboardTest must be run by a player.");
            return true;
        }

        this.results.clear();
        switch (mode) {
            case "gui" -> this.openGui(player);
            case "nms" -> this.probeNms(player);
            case "meta" -> this.probeItemMeta(player);
            default -> {
                this.probeNms(player);
                this.probeItemMeta(player);
                this.openGui(player);
            }
        }

        for (String line : this.results) {
            player.sendMessage(line);
            getLogger().info(ChatColor.stripColor(line));
        }
        return true;
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

    private void fail(String message) {
        this.results.add(ChatColor.RED + "[FAIL] " + ChatColor.RESET + message);
    }
}
