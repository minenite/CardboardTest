# CardboardTest

A small compatibility probe for [Cardboard](https://github.com/minenite/cardboard).

It covers the two areas that headless testing cannot reach:

- **NMS reflection** — `CraftPlayer#getHandle`, `ServerPlayer#connection` by field
  reflection, `CraftServer#getServer`, `Class.forName` on unobfuscated NMS names,
  and `CraftWorld#getHandle`.
- **Custom inventories** — creating a GUI, item display names and lore, the
  metadata round trip, and `InventoryClickEvent` / `InventoryCloseEvent`
  (including closing from inside the click handler).

Every probe is caught and reported individually as `[PASS]` / `[FAIL]`, so a
partial failure still tells you which surface broke.

## Usage

```
/cbtest all     # everything
/cbtest nms     # NMS reflection only
/cbtest meta    # item metadata only
/cbtest gui     # open the inventory probe
```

## Building

```
./gradlew jar     # -> out/CardboardTest.jar
```

Requires JDK 25 (auto-provisioned) because paper-api 26.2 targets Java 25.

## Result on Cardboard 26.2

All probes pass. See the
[Cardboard 26.2 release](https://github.com/minenite/cardboard/releases/tag/v26.2).
