# Anonymity — Red Fragment resource pack

Recolors **only** the Fragment Of Anonymity (an amethyst shard with
`custom_model_data` = 1) to red. Normal amethyst shards are unaffected, and
players without the pack just see a normal shard.

Built for Minecraft **1.21.11** (`pack_format` 75).

## Option A — serve it from the server (recommended)

Upload `AnonymityRedFragment.zip` to a direct-download URL (e.g. a GitHub
release asset, Dropbox with `?dl=1`, etc.), then in `server.properties`:

```properties
resource-pack=PASTE_DIRECT_DOWNLOAD_URL_HERE
resource-pack-sha1=f1ed2cdc219d19a49e2dc701e4fff5a1cde0d156
require-resource-pack=true
```

Restart the server. Players are prompted to apply the pack on join.

## Option B — install client-side

Put `AnonymityRedFragment.zip` in
`.minecraft/resourcepacks/` and enable it in
**Options → Resource Packs**.
