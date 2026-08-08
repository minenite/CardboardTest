package org.minenite.cbtest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Traces Paper's ItemStack.craftDelegate through the whole lifecycle.
 *
 * <p>serialize() is a one-liner - {@code return this.craftDelegate.serialize()} -
 * so an NPE there says only that the delegate is null, not when it became null.
 * Reasoning about the constructor did not settle it, so this measures the field
 * directly at each step and across each construction path.
 */
final class ItemStackProbe {

    private final Plugin plugin;
    private final List<String> out = new ArrayList<>();
    private Field delegateField;

    ItemStackProbe(Plugin plugin) {
        this.plugin = plugin;
    }

    void run() {
        try {
            this.delegateField = ItemStack.class.getDeclaredField("craftDelegate");
            this.delegateField.setAccessible(true);
        } catch (Throwable t) {
            fail("cannot reflect craftDelegate: " + t);
            report();
            return;
        }

        // Where does it come from, and does it survive each step?
        trace("new ItemStack(DIAMOND_SWORD)", new ItemStack(Material.DIAMOND_SWORD));
        trace("new ItemStack(STONE, 5)", new ItemStack(Material.STONE, 5));
        trace("new ItemStack(AIR)", new ItemStack(Material.AIR));
        trace("ItemStack.of(STONE, 1)", ItemStack.of(Material.STONE, 1));
        trace("Material.STONE.asItemType().createItemStack(1)",
                Material.STONE.asItemType() == null ? null : Material.STONE.asItemType().createItemStack(1));

        // The construction path the failing probe used.
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        trace("after construction", stack);
        ItemMeta meta = stack.getItemMeta();
        trace("after getItemMeta", stack);
        meta.setDisplayName("Probe Blade");
        meta.setLore(List.of("a", "b"));
        stack.setItemMeta(meta);
        trace("after setItemMeta", stack);
        stack.setAmount(3);
        trace("after setAmount", stack);
        trace("after clone", stack.clone());

        // Is a modded stack different from a vanilla one?
        Material modded = null;
        for (Material m : Material.values()) {
            if (m.name().startsWith("WAYSTONES_") && m.isItem()) {
                modded = m;
                break;
            }
        }
        if (modded != null) {
            trace("modded " + modded.name(), new ItemStack(modded));
        } else {
            say("no modded item material available to compare");
        }

        // And the actual contract.
        roundTrip("vanilla", new ItemStack(Material.DIAMOND_SWORD, 3), true);
        if (modded != null) {
            roundTrip("modded", new ItemStack(modded, 2), false);
        }

        report();
    }

    /** Reports whether the delegate is present, and what it is. */
    private void trace(String label, ItemStack stack) {
        if (stack == null) {
            fail(label + " -> null stack");
            return;
        }
        Object delegate;
        try {
            delegate = this.delegateField.get(stack);
        } catch (Throwable t) {
            fail(label + " -> cannot read delegate: " + t);
            return;
        }
        String desc = stack.getClass().getSimpleName() + " delegate="
                + (delegate == null ? "NULL" : delegate.getClass().getSimpleName());
        // Named rather than imported: CraftItemStack is a server class, not API.
        if (stack.getClass().getName().endsWith(".CraftItemStack")) {
            // A CraftItemStack is its own delegate by design; null is expected.
            say(label + " -> " + desc + " (CraftItemStack, delegate not required)");
        } else if (delegate == null) {
            fail(label + " -> " + desc);
        } else {
            pass(label + " -> " + desc);
        }
    }

    private void roundTrip(String label, ItemStack stack, boolean expectMeta) {
        try {
            if (expectMeta) {
                ItemMeta meta = stack.getItemMeta();
                meta.setDisplayName("Round Trip");
                meta.setLore(List.of("lore one", "lore two"));
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 2, true);
                meta.getPersistentDataContainer().set(
                        new NamespacedKey(this.plugin, "probe"), PersistentDataType.STRING, "kept");
                stack.setItemMeta(meta);
            }

            java.util.Map<String, Object> map = stack.serialize();
            pass(label + " serialize() produced " + map.size() + " keys: " + map.keySet());

            ItemStack back = ItemStack.deserialize(map);
            check(label + " round-trip type", back.getType() == stack.getType());
            check(label + " round-trip amount (" + back.getAmount() + ")",
                    back.getAmount() == stack.getAmount());
            check(label + " round-trip namespaced identity",
                    back.getType().getKey().equals(stack.getType().getKey()));

            if (expectMeta) {
                ItemMeta m = back.getItemMeta();
                check(label + " round-trip display name",
                        m != null && "Round Trip".equals(m.getDisplayName()));
                check(label + " round-trip lore",
                        m != null && m.getLore() != null && m.getLore().size() == 2);
                check(label + " round-trip enchantment",
                        m != null && m.getEnchantLevel(org.bukkit.enchantments.Enchantment.SHARPNESS) == 2);
                check(label + " round-trip PDC",
                        m != null && "kept".equals(m.getPersistentDataContainer()
                                .get(new NamespacedKey(this.plugin, "probe"), PersistentDataType.STRING)));
            }
            check(label + " round-trip isSimilar", back.isSimilar(stack));
        } catch (Throwable t) {
            fail(label + " round-trip threw " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("org.bukkit") || e.getClassName().startsWith("org.cardboard")) {
                    say("    at " + e);
                }
            }
        }
    }

    private void check(String what, boolean ok) {
        if (ok) pass(what); else fail(what);
    }

    private void pass(String m) { this.out.add("[PASS] itemstack: " + m); }
    private void fail(String m) { this.out.add("[FAIL] itemstack: " + m); }
    private void say(String m) { this.out.add("[INFO] itemstack: " + m); }

    private void report() {
        for (String line : this.out) {
            this.plugin.getLogger().info(line);
        }
    }
}
