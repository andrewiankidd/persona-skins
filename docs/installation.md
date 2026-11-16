# Installing PersonaSkins

## What you need

- A Paper server, 26.3 or later, on Java 25.
- [Geyser-Spigot](https://geysermc.org/download) and
  [Floodgate](https://geysermc.org/download#floodgate) installed on that same
  server, so Bedrock players join it directly. PersonaSkins reads each Bedrock
  player's skin from Geyser and replaces the one Floodgate applies; it does
  not work behind a proxy where Geyser runs elsewhere.
- Outbound HTTPS to `api.mineskin.org`, which signs the rendered skins.

## Quick start

1. Download `persona-skins-<version>.jar` from the
   [releases](../../../releases) into the server's `plugins/` folder.
2. Restart the server. The console shows:

   ```
   [PersonaSkins] Loaded 0 rendered skins; rendering persona and custom-geometry skins
   ```

3. Join on Bedrock wearing a persona (Character Creator) skin or a marketplace
   skin with its own model. Within a few seconds the console shows:

   ```
   [PersonaSkins] Rendered <player>'s persona skin (c9e3b2fc46e3)
   ```

4. Look at that player from a Java client: hair, hats and the like now show,
   flattened onto the skin's outer layer. Bedrock players watching still see
   the original 3D skin.

A skin is rendered and uploaded once. The result is kept in
`plugins/PersonaSkins/cache/`, so the next time anyone joins wearing it, it
applies at once.

## Running in a container

With the [itzg/minecraft-server](https://github.com/itzg/docker-minecraft-server)
image, add the release jar to the plugin downloads beside Geyser and
Floodgate:

```yaml
environment:
  TYPE: PAPER
  PLUGINS: |
    https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot
    https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot
    https://github.com/andrewiankidd/persona-skins/releases/download/v0.1.0/persona-skins-0.1.0.jar
  # Optional: see "MineSkin" below.
  MINESKIN_API_KEY: ""
```

In the image's Helm chart the same list is `minecraftServer.pluginUrls`. Pin
a release rather than following the latest, so a restart never changes the
plugin under you.

Keep `plugins/PersonaSkins/` on the server's persistent volume (it is, under
`/data`), or every skin is uploaded again after each restart.

## Configuration

`plugins/PersonaSkins/config.yml`, written on first start:

| Key | Default | |
|---|---|---|
| `render` | `custom` | `custom`: persona skins, and skins whose model differs from the standard player model in any way. `all`: every Bedrock skin. |
| `mineskin.api-key` | empty | Optional; raises MineSkin's rate limit. Also read from the `MINESKIN_API_KEY` environment variable. |
| `mineskin.visibility` | `unlisted` | `unlisted` or `public`; `private` needs a key. |
| `keep-pngs` | `true` | Keep each render's PNG beside its cache entry, to look at. |

## MineSkin

A Java client only shows another player's skin from a texture signed by
Mojang, so every rendered image has to be worn once by a real Minecraft
account. [MineSkin](https://mineskin.org) does that and returns the signed
texture. It works without an account, a few skins a minute, which suits a
small server since each skin is uploaded only once. For more, create a key
at [mineskin.org](https://mineskin.org) and set `mineskin.api-key` or
`MINESKIN_API_KEY`.

Uploads are `unlisted` by default: not in MineSkin's public gallery, but
anyone with the link can see them.

## Who sees what

| Viewer | Sees a Bedrock player wearing a 3D skin as |
|---|---|
| Java | the PersonaSkins render: the whole model flattened onto both layers of a Java skin |
| Bedrock | the player's own skin, model and all |

Without PersonaSkins, Java players get Floodgate's conversion, which keeps
only the parts of the standard player model, so hair, hats and other extra
pieces disappear.

## Checking a render

`/personaskins dump <player>` (ops, or `personaskins.admin`) writes to
`plugins/PersonaSkins/dumps/<player>/`:

| File | |
|---|---|
| `bedrock.png` | the texture the Bedrock client sent |
| `geometry.json` | its model |
| `info.txt` | geometry name, persona or not, slim arms, cache key |
| `java.png` | what PersonaSkins makes of it |

`/personaskins refresh <player>` renders and uploads the skin again, ignoring
the cache; use it after changing the plugin.

A dump can also be rendered off the server, from a checkout of this repo:

```sh
./gradlew test -Ppersona.dump=/path/to/dumps/<player>
```

The render lands in `build/test-renders/dump.png`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Nothing in the console when a Bedrock player joins | Their skin is the standard model, which Floodgate already converts. Set `render: all` to render it anyway. |
| `Could not read <player>'s Bedrock skin` | The Geyser version renamed the client-data getters PersonaSkins reads. Report it with the Geyser build number. |
| `MineSkin answered 429` | MineSkin's rate limit; PersonaSkins waits and retries. Add an API key if it keeps happening. |
| Java players still see the bald version | The render may still be uploading; check the console. Java clients also cache skins briefly. |
| The tall parts of a model are missing | Java's outer layer sits half a pixel from the body, so anything further out is flattened onto the nearest face, and a part reaching well beyond the head (tall spiked hair, say) shows only where it overlaps it. |
