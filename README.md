# Anonymity

A Paper/Spigot plugin built around the **Fragment Of Anonymity** — a red-named
amethyst shard. Right-click it to toggle "anonymous mode" on yourself. Operators
get one with `/fragment`. The Fragment never drops on death (it vanishes, like
Curse of Vanishing but without the visible enchant).

For a genuinely **red** Fragment texture, apply the optional resource pack in
[`resourcepack/`](resourcepack/) (Minecraft can't recolor item textures from the
server alone). It recolors only the Fragment; without the pack the item still
works, it just looks like a normal amethyst shard with a red name. See
[`resourcepack/README.md`](resourcepack/README.md) for setup.

Anonymous mode does the following:

- **Scrambled name** — your name renders as ever-changing *unintelligible letters*
  (the Minecraft magic/obfuscated formatting code) in chat, the tab list and death
  messages. Your over-head nametag is hidden so your real username never leaks.
- **Hidden skin** — your skin is swapped to
  [_lotu's skin](https://namemc.com/skin/35e202697eb4000e) so nobody recognises you.
- **Enchantment aura** — enchantment-table particles continuously pour off your body.
- **Transform cutscene** — toggling spins red cloud particles around you like a
  tornado that converges and then explodes as the change lands (and plays in
  reverse when you turn it off), with layered wind-up/explosion sounds.
- **Combat buffs** — infinite **Strength II** and **Speed II**, with the potion
  particles hidden.
- **Sneak to dash** — tap shift to dash forward (a ~1 second velocity burst in the
  direction you're looking) with a whoosh sound and a trail of red particles
  following you; short cooldown.
- **Disguise armour** — your armour is replaced with a trimmed netherite set
  (Vex/Raiser/Tide trims, Redstone material, Protection/Unbreaking/Mending, and
  Feather Falling on the boots) and your own armour is restored when you leave
  anonymous mode.
- **Anonymous kills** — when you kill someone, the death message shows your
  scrambled name instead of your username.

Right-click the Fragment again to turn everything back off.

## Building

Requires JDK 21 and Maven.

```bash
mvn package
```

The plugin jar is written to `target/Anonymity-1.0.0.jar`. Drop it into your
server's `plugins/` folder and restart.

Built against the Paper API for Minecraft **1.21.4**. The enchantment particle is
resolved at runtime (`ENCHANT` on 1.20.5+, `ENCHANTMENT_TABLE` on older builds),
so it tolerates a range of versions, but 1.21.x is what it's tested against.

## Configuration

`config.yml` (generated on first run) lets you change the skin, the scrambled-name
length, the particle aura and whether death messages are rewritten:

```yaml
skin:
  value: "..."      # Mojang-signed texture value
  signature: "..."  # matching signature
name:
  min-length: 7
  max-length: 9
particles:
  enabled: true
  interval-ticks: 4
  count: 10
  radius: 0.6
death-messages: true
```

The default `skin.value`/`skin.signature` is a Mojang-signed texture for
`namemc.com/skin/35e202697eb4000e`, so other players actually render it. To use a
different skin, generate a new signed pair (e.g. via <https://mineskin.org>) and
paste it in.

## How the skin swap works

The plugin mutates the `textures` property on the live `GameProfile` backing your
player entity and then re-sends you to every other online player (via Bukkit's
`hidePlayer`/`showPlayer`), so their clients re-read the new texture. This avoids
version-specific NMS packet classes — the only reflection is locating the
`GameProfile`.

## Known limitations

These are Bukkit-API constraints, not bugs:

- **You won't see your own new skin** until you reconnect — that's a vanilla client
  limitation. Everyone *else* sees it immediately.
- **The over-head nameplate is hidden** while anonymous, not replaced. Bukkit can't
  replace the entity name tag without a packet library, so rather than leak your real
  username the nametag is hidden entirely (scoreboard team with
  `NAME_TAG_VISIBILITY = NEVER`). Chat, the tab list and death messages still show the
  animated scrambled name. For a fully-replaced (still-visible) nameplate, add
  ProtocolLib and send a custom player-info packet.
