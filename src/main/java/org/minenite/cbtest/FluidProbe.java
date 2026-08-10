package org.minenite.cbtest;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Asks the server what a fluid block's collision actually is.
 *
 * <p>Players report floating on modded fluids, unable to walk in. Three
 * explanations were argued from source and all three were wrong. This measures
 * the one property that decides it: whether the placed block is passable.
 *
 * <p>If a modded fluid reports non-passable while water reports passable, the
 * block is solid and the mod registered it wrongly. If they all report passable,
 * the blocks are fine and the fault is in fluid detection or on the client -
 * which is where the evidence already pointed, since this reproduces in
 * single-player with no server involved.
 */
final class FluidProbe {

    private FluidProbe() {
    }

    /** Ids to try; absent ones are reported rather than silently skipped. */
    private static final List<String> CANDIDATES = List.of(
            "minecraft:water",
            "minecraft:lava",
            "biomesoplenty:blood",
            "mekanism:hydrogen",
            "mekanism:sodium",
            "mekanism:lithium",
            "mekanism:uranium_oxide",
            "mekanism:heavy_water");

    /**
     * Registry.MATERIAL only answers for vanilla keys; CardForge exposes modded
     * content under a mangled enum name, so a namespaced id has to be matched
     * against that too.
     */
    private static Material resolve(String id) {
        Material byKey = Registry.MATERIAL.get(NamespacedKey.fromString(id));
        if (byKey != null) {
            return byKey;
        }
        String mangled = id.replace(':', '_').replace('/', '_').toUpperCase(java.util.Locale.ROOT);
        for (Material m : Material.values()) {
            if (m.name().equals(mangled)) {
                return m;
            }
        }
        return null;
    }

    /** Every modded material whose name hints at a fluid, so ids need not be guessed. */
    static void listCandidates(Consumer<String> pass) {
        StringBuilder found = new StringBuilder();
        int n = 0;
        for (Material m : Material.values()) {
            String name = m.name();
            if (name.startsWith("MINECRAFT_") || !name.contains("_")) continue;
            if (!m.isBlock()) continue;
            if (name.matches(".*(WATER|LAVA|BLOOD|OIL|FLUID|LIQUID|HYDROGEN|OXYGEN|SODIUM|LITHIUM|BRINE|ACID|ETHENE|CHLORINE|URANIUM_OXIDE).*")) {
                if (n++ < 40) found.append(name).append(' ');
            }
        }
        pass.accept("fluids: " + n + " fluid-looking modded blocks: " + found);
    }

    static void run(World world, Consumer<String> pass, Consumer<String> fail) {
        listCandidates(pass);
        Location at = world.getSpawnLocation().clone().add(0, 6, 0);
        Block block = at.getBlock();
        Material original = block.getType();
        try {
            for (String id : CANDIDATES) {
                Material material = resolve(id);
                if (material == null || !material.isBlock()) {
                    pass.accept("fluids: " + id + " -> not registered here, skipped");
                    continue;
                }
                block.setType(material, false);
                Block placed = at.getBlock();
                pass.accept(String.format(
                        "fluids: %-28s passable=%-5s liquid=%-5s solid=%-5s type=%s",
                        id, placed.isPassable(), placed.isLiquid(),
                        placed.getType().isSolid(), placed.getType().name()));
            }
        } catch (Throwable t) {
            fail.accept("fluids: " + t);
        } finally {
            block.setType(original, false);
        }
    }
}
