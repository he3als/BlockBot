# Server resource-pack translations

BlockBot reads Better Server Packs' `url` from
`config/betterserverpacks/config.properties` and its active lowercase SHA-1 from
`config/betterserverpacks/pack.sha1`. No additional BlockBot configuration or
Better Server Packs dependency is required. `server.properties` is not used.
Missing files or an empty URL leave normal Minecraft translations in place.

This applies to Discord chat serialization, advancement titles and descriptions,
and death messages. It does not change Minecraft's global language, resource
packs, datapacks, clients, or network protocol.

## English fallback

All `assets/<namespace>/lang/en_*.json` files are discovered in the ZIP. Only
JSON string values are accepted. Numbers, booleans, nulls, arrays, and objects
are ignored. One effective map is built in this priority order:

1. `en_us`
2. `en_gb`
3. `en_ca`
4. `en_au`
5. `en_nz`
6. Other discovered `en_*` filenames in alphabetical order
7. Minecraft's existing server translation lookup

Lower-priority locales only fill missing keys. For duplicate keys at the same
locale priority, resource paths are sorted lexicographically and later paths
win. This is deterministic across ZIP insertion orders; there is no dependable
namespace override order inside a single pack. Duplicate ZIP paths are rejected.
Only the root `assets/` language files are read; pack-format overlays are not
evaluated.

The `mcdiscordreserializer` 4.3.0 translation provider returns fully substituted
plain text. BlockBot resolves nested component arguments before Minecraft
processes the surrounding vanilla translation, preserving `%s`, `%1$s`, `%2$s`,
and `%%`. Numeric `%d`/`%f` placeholders are normalized like Minecraft's
language loader. Malformed formats fall back to the literal template, as in
Minecraft.
The serializer still handles Markdown escaping, outer styles, and children.
As with the existing provider, formatting within translation arguments is
flattened to plain text.

## Updates and cache

A single daemon worker checks the local configuration and hash immediately at
server startup, then every 30 seconds after the previous check completes. All
file I/O, networking, hashing, and parsing run on that worker. It is interrupted
when the server stops.

For an unchanged active hash, no download or cache parsing occurs. For a new
hash, BlockBot first checks:

```text
config/blockbot-resourcepack-cache/v1/<sha1>/english-translations.json
```

This UTF-8 JSON object contains the complete effective English map, including
all fallback locales and namespaces. `v1` versions the cache's merge rules.
The three most recently used hashes are retained; older entries are removed
after a successful activation. Cached maps are read without downloading the ZIP
and survive server restarts. The cache can be deleted while the server is
stopped to
force a verified download next time.

On a cache miss, BlockBot downloads to a temporary file, verifies SHA-1, reads
the ZIP, and parses all matching language files before atomically renaming a
temporary JSON cache file. Temporary files are removed on success or failure;
the ZIP is not retained. A corrupt cache triggers a new verified download.

Only a complete successful update replaces the immutable active map through a
volatile reference. The previous map stays available during downloads and after
failures. The source is checked again before activation so a second `/pack
reload` during a download cannot activate an obsolete candidate. Failed updates
retry after five minutes; a different advertised hash or URL can retry sooner.
An absent/disabled Better Server Packs configuration after loading a map also
retains that last good map until restart. Initial startup uses vanilla lookup
until the first cache load or download completes.

Malformed JSON or a corrupt matching ZIP entry rejects the entire candidate
without crashing the server or publishing partial translations. Logs omit URLs
and pack contents. Unchanged checks are silent.

## Limits

- HTTP(S) only, without embedded credentials; up to five redirects, with no
  HTTPS-to-HTTP downgrade.
- 10-second connection timeout, 15-second read timeout, and a two-minute overall
  download deadline checked between reads. An in-progress read may take up to
  its read timeout after the deadline or shutdown.
- 256 MiB download, 100,000 ZIP entries, and 1,024 English language files.
- 4 MiB per English language file, 32 MiB total uncompressed English JSON,
  200,000 effective keys, and 64 MiB cached JSON.
- 1,024 characters per key and 32,768 characters per value.

Entries are read directly from the ZIP and never extracted. Traversal paths are
excluded. Cache publication requires the filesystem to support atomic renames
within the cache directory; failure retains the current map.

## Build and use

This branch follows BlockBot's current Minecraft **26.2** source and requires
**JDK 25**. From the repository root:

```sh
bash gradlew clean build --console=plain
```

Use `build/libs/blockbot-discord-2.0.9+26.2.jar`. Stop the server, replace the
existing BlockBot jar in `mods/` with this jar, retain the required Fabric API
and Fabric Language Kotlin mods, and restart. Keep the usual BlockBot
bot/channel configuration and Better Server Packs configuration. No client mod
is needed.

To run the focused tests:

```sh
bash gradlew test --console=plain
```

Tests exercise language precedence, malformed input, SHA-1 rejection, restart
cache hits, concurrent map replacement, stale downloads, retries, cache pruning,
HTTP redirects/limits, and the actual 4.3.0 Discord serializer with Minecraft
components. They bootstrap Minecraft's registries but do not log into Discord or
launch a Fabric server.

For a live check, wait for `Loaded N translations from server resource pack`,
then trigger an advancement whose title/description use
`adv.kleispack.light_campfire` and `adv.kleispack.light_campfire.desc`. With the
example pack strings, the announcement should read:

```text
Sophed has made the advancement [Light a Campfire]
```

The description should be `Light a campfire for the first time`. Also check a
vanilla advancement such as Stone Age. After updating the hosted pack, run
`/pack reload`; within the next poll plus download time, BlockBot should log the
new hash and load the new translations. Restarting with the same hash should log
`Using cached resource-pack translations` without fetching the pack.
