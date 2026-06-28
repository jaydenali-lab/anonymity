# Anonymity

A Paper/Spigot plugin that adds a single command, **`/anonymous`** (alias `/anon`),
which toggles "anonymous mode" on yourself:

- **Scrambled name** — your name renders as ever-changing *unintelligible letters*
  (the Minecraft magic/obfuscated formatting code) in chat, the tab list and above
  your head.
- **Hidden skin** — your skin is swapped to
  [_lotu's skin](https://namemc.com/skin/35e202697eb4000e) so nobody recognises you.
- **Enchantment aura** — enchantment-table particles continuously pour off your body.
- **Anonymous kills** — when you kill someone, the death message shows your
  scrambled name instead of your username.

Run `/anonymous` again to turn everything back off.

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
- **The over-head nameplate is prefixed**, not fully replaced. Bukkit can't replace
  the entity name tag without a packet library, so the scrambled token is prepended
  to your name via a scoreboard team. Chat, tab and death messages are fully
  scrambled. For a fully-replaced nameplate, add ProtocolLib and send a custom
  player-info packet.
