# Server resource packs

BlockBot uses your server resource pack to translate custom text in Discord
chat, advancements and death messages. Bitmap icons from the pack can also
appear as Discord emoji. No client mod or resource-pack changes are needed.

## Setup

Configure either of these sources:

- **Better Server Packs:** BlockBot reads `url` from
  `config/betterserverpacks/config.properties` and the hash from
  `config/betterserverpacks/pack.sha1`.
- **Vanilla:** Set `resource-pack` and `resource-pack-sha1` in `server.properties`.

Better Server Packs takes priority when its URL is set. If its pack cannot be
loaded, BlockBot keeps the last working translations. Clear that URL to use
vanilla settings instead. Both sources require the pack's SHA-1 hash.

## Language

These options in `config/blockbot-discord.toml` work without changes:

```toml
[ResourcePackTranslations]
enabled = true
language = "en_us"
```

Set `language` to a Minecraft language code, such as `en_gb`, `fr_fr` or `de_de`.
BlockBot uses that language from the resource pack first. Missing keys fall back
to `en_us`, `en_gb`, `en_ca`, `en_au`, `en_nz`, then any other `en_*` languages in
alphabetical order. Keys still missing use Minecraft's usual server translation.
This selects resource-pack text; it does not change Minecraft's server language.

Set `enabled = false` to turn off resource-pack translations and icons. Existing
Discord messages and uploaded emoji are left in place. Configuration changes
are picked up automatically; language changes take up to 30 seconds.

## Icons

```toml
[ResourcePackEmojis]
enabled = true
prefix = "bb_"
coloured = true
automaticLayout = true
scale = 1.0
baselineOffset = 0.0
```

- `enabled`: show pack icons as Discord emoji. Turn this off to omit icons while
  keeping translations.
- `prefix`: name prefix for generated emoji; 1–16 letters, digits or underscores.
  Reserve this prefix for BlockBot.
- `coloured`: tint icons with their Minecraft text colour. Turn this off to keep
  the texture's original colours.
- `automaticLayout`: align icons with the text in chat and advancement descriptions.
- `scale`: adjust icon size, from `0.25` to `2`.
- `baselineOffset`: move icons up (negative) or down (positive), from `-16` to `16` pixels.

Icons keep their transparency and use nearest-neighbour scaling for pixel art.
Automatic layout uses Discord's gg sans font metrics; individual client fonts
or styling may affect the result. Existing `padding` settings still work when
`automaticLayout = false` (0–49 percent per side, default `23.1667`).

BlockBot uploads icons as needed using the bot application's emoji slots. When
full, it replaces its least-used emoji before sending the message. Usage counts
persist across restarts. Replacing an emoji also removes its artwork from older
messages. Unrelated emoji are never removed. If upload fails or no managed slot
can be freed, BlockBot sends the text without the missing icon.

Only bitmap icons reachable from the pack's default font are supported. TTF,
Unihex, animations, pack overlays and component-specific fonts are not supported.
Normal Unicode emoji are unchanged.

## Updates and cache

BlockBot checks the configured pack hash every 30 seconds. Downloads and parsing
run in the background; the previous translations remain available until the new
pack is verified and ready. Failed updates retry after five minutes.

Translations and icons are cached under:

```text
config/blockbot-resourcepack-cache/v3/<sha1>/languages.json
config/blockbot-resourcepack-cache/v3/<sha1>/font-glyphs.json
```

The language cache stores all discovered languages. Restarting or changing the
language reuses the cache without downloading the pack. Three recent pack hashes
are kept. Upgrading from the older English-only cache downloads the pack once;
old `v1` and `v2` directories can be removed while the server is stopped.

Emoji usage is stored separately in
`config/blockbot-resourcepack-cache/discord/<application-id>/emoji-usage.json`.
Do not delete it if you want to keep usage counts.

Downloads require HTTP(S), time out, and must match the advertised SHA-1.
Language files are read directly from the ZIP without extraction. Malformed
files reject the update; non-string translation values are ignored. Limits are
256 MiB per download, 4 MiB per language file, 32 MiB of language data and 200,000
translation entries across all languages. Cached JSON files are limited to
64 MiB. Filesystem support for atomic renames is required.

All namespaces are included. Within one language, later alphabetical resource
paths win duplicate keys. A lower-priority language only fills missing keys.
Translation arguments and Minecraft's normal fallback behaviour are preserved.

## Troubleshooting

Look for `Loaded N translations from server resource pack` in the server log.
A restart with the same pack should report `Using cached resource-pack translations`.

If a key still appears in Discord, check that the selected language or an English
fallback defines it, and that the configured hash matches the hosted ZIP. With
Better Server Packs, run `/pack reload` after changing the pack. With vanilla,
update the URL and hash in `server.properties`. Vanilla may need a server restart
to send the updated pack to Minecraft clients.

Minecraft's own console may still show raw custom keys; this feature translates
Discord output. If an icon is missing, check for icon-loading or upload warnings.
Deleting the pack's cache directory while stopped forces a fresh download.

## Building

Use JDK 25 for Minecraft 26.3:

```sh
bash gradlew clean build --console=plain
```

Stop the server, replace the existing BlockBot jar in `mods/` with
`build/libs/blockbot-discord-2.0.9+26.3.jar`, and restart. Use Fabric Loader
0.19.5 or newer and Fabric API 0.164.0+26.3 or newer for Minecraft 26.3.
Keep Fabric Language Kotlin and your existing bot configuration.

Run tests with `bash gradlew test --console=plain`. Tests use local packs and
mock Discord requests; they do not send messages to Discord.
