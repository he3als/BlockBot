# Server resource-pack translations

BlockBot automatically selects one server resource-pack source:

1. **Better Server Packs:** `url` in
   `config/betterserverpacks/config.properties`, with SHA-1 from
   `config/betterserverpacks/pack.sha1`.
2. **Vanilla:** `resource-pack` and `resource-pack-sha1` in `server.properties`
   in the server's game directory, when Better Server Packs has no URL set.

Better Server Packs takes priority when both are configured. Once its URL is
set, a missing/invalid hash or failed download retains the previous map;
BlockBot does not switch to a potentially different vanilla pack. Clear the
Better Server Packs URL to select vanilla instead. No extra BlockBot
configuration or Better Server Packs dependency is required.

Both URL and SHA-1 are required for verification. SHA-1 hex is accepted in
either case and normalized to lowercase for cache keys. Properties use UTF-8
decoding with legacy ISO-8859-1 fallback. Source selection or missing settings
are logged on startup and when they change, without repeating every check.

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

A single daemon worker checks both source configurations immediately at
server startup, then every 30 seconds after the previous check completes. All
file I/O, networking, hashing, and parsing run on that worker. It is interrupted
when the server stops.

For an unchanged active hash, no download or cache parsing occurs. For a new
hash, BlockBot first checks:

```text
config/blockbot-resourcepack-cache/v2/<sha1>/english-translations.json
config/blockbot-resourcepack-cache/v2/<sha1>/font-glyphs.json
```

The first UTF-8 JSON object contains the complete effective English map,
including all fallback locales and namespaces. The second maps decimal Unicode
code points to base64-encoded PNG icons (an empty string represents spacing).
Both are built from the same SHA-1-verified ZIP and published together in memory.
`v2` adds font artwork to the cache; upgrading from `v1` downloads the pack once
to obtain its textures. Old `v1` cache directories can safely be deleted.
The three most recently used hashes are retained; older entries are removed
after a successful activation. Cached maps are read without downloading the ZIP
and survive server restarts. The cache can be deleted while the server is
stopped to force a verified download next time.

On a cache miss, BlockBot downloads to a temporary file, verifies SHA-1, reads
the ZIP, and parses all matching language files before atomically renaming a
temporary JSON cache file. Temporary files are removed on success or failure;
the ZIP is not retained. A corrupt cache triggers a new verified download.

Only a complete successful update replaces the immutable active map through a
volatile reference. The previous map stays available during downloads and after
failures. The source is checked again before activation so another configuration
or hash change during a download cannot activate an obsolete candidate.
Failed updates retry after five minutes; a different hash or URL can retry
sooner. Missing or cleared resource-pack settings after loading a map retain
that last good map until restart. Initial startup uses vanilla lookup until
the first cache load or download completes.

Malformed JSON or a corrupt matching ZIP entry rejects the entire candidate
without crashing the server or publishing partial translations. Logs omit URLs
and pack contents. Unchanged checks are silent. An empty English map is reported
so a pack with no matching language files is distinguishable from a cache miss.

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
- 64 KiB per properties file and 128 bytes for `pack.sha1`.

Entries are read directly from the ZIP and never extracted. Traversal paths are
excluded. Cache publication requires the filesystem to support atomic renames
within the cache directory; failure retains the current map.

## Resource-pack icons on Discord

Private-use characters such as `\ue00f` are Minecraft font glyphs, not standard
Unicode emoji. BlockBot follows bitmap and reference providers from
`assets/minecraft/font/default.json`, including references to other namespaces,
and crops the corresponding PNG texture cells. Transparent padding is trimmed
and each icon is scaled to 128×128 with nearest-neighbor sampling.
PNG transparency (including translucent pixels) is preserved, and any added
padding is transparent. Opaque pixels in the source artwork are left intact;
BlockBot does not guess which colors might be a background.
The first provider defining a character wins; space providers produce no icon.

The following optional section in `config/blockbot-discord.toml` controls this
feature. Existing configurations use these defaults without needing edits:

```toml
[ResourcePackEmojis]
enabled = true
prefix = "bb_"
```

Set `enabled = false` to omit custom glyphs without making Discord emoji API
requests; translation handling continues normally. `prefix` accepts 1–16 ASCII
letters, digits, or underscores. An artwork hash is appended to produce a name
at most 32 characters long. An invalid prefix logs a warning and uses `bb_`.
Settings follow BlockBot's existing config-file reload behavior. Changing the
prefix creates newly named emoji on demand. Reserve the configured prefix for
BlockBot's generated emoji.

When an icon appears in chat or an advancement, BlockBot creates a Discord
**application-owned emoji** from its artwork. These use the bot application's
emoji slots, not the guild's slots, and do not require additional guild emoji
permissions. See [Discord's application emoji documentation](https://docs.discord.com/developers/resources/emoji#application-owned-emoji).
Uploads happen on the bot coroutine, never on the Minecraft server thread.
Only used icons are uploaded, with at most 16 new emoji per message and a
10-second time budget. Artwork hashes determine the prefixed names (by default
`bb_...`), so identical icons are reused across pack updates and server restarts.
The remote list is refreshed after five minutes of use.

When the application's 2,000 slots are full (or Discord reports a full emoji
inventory), BlockBot refreshes the inventory and replaces the **least frequently
used managed emoji** on demand. Counts include icon occurrences in successfully
sent messages; failed/cancelled sends and icons omitted for length limits do not
increase them. Ties prefer the least recently used icon, then its name. Unused
icons discovered at startup have a count of zero.

Counts, recency, and previously configured prefixes persist in:

```text
config/blockbot-resourcepack-cache/discord/<application-id>/emoji-usage.json
```

This versioned JSON contains `sequence`, `prefixes`, and an `emojis` object
mapping generated names to `{ "count": N, "lastUse": N }`. It is independent
of resource-pack hashes, so pack-cache pruning does not reset usage. After each
successful emoji-enabled send, changed state is written on the I/O dispatcher
to a temporary file, flushed, and atomically renamed. Failed writes retain the
previous JSON and in-memory counts and retry with the next message. Missing or
invalid JSON starts with empty counts; invalid input is logged. Files are
bounded to 2 MiB. Entries are removed when their emoji is evicted or disappears
from Discord's inventory. Counts and tie-breaking recency survive restarts.

Emoji-enabled relay messages share a queue covering preparation **and the
Discord send response**. Each replacement awaits deletion and successful
upload before the message is sent. The next message waits until the previous
send and usage update finish, so it cannot evict an icon between preparation
and sending. Cancellation releases the queue and reservations. A failed or
ambiguous deletion invalidates the old cached ID immediately so subsequent
messages cannot reuse an ID which Discord might already have deleted.

Only names matching a recorded BlockBot prefix plus its generated hash are
eligible for eviction. Unrelated emoji are never deleted. Icons needed by the
current message remain protected. If no eligible icon exists, the new glyph is
omitted and subsequent messages can try again. Eviction is logged and the
requested icon is uploaded immediately. These guarantees apply to one BlockBot
instance; another bot process or administrator can independently change the
application's emoji inventory.
Deleting an emoji also removes its artwork from older Discord messages.

Advancement descriptions containing glyphs move from the footer into the embed
description, where Discord renders custom emoji. Announcements with glyphs in
the title also move into the description. Ordinary advancements retain their
existing layout. Markdown in the original text remains escaped; generated emoji
markup is inserted afterward. Normal Unicode emoji are unchanged.
If emoji markup would exceed Discord's message/description length limit,
excess icons are omitted while preserving the surrounding text.

If artwork is missing or unsupported, or Discord rejects/times out an upload,
the message is sent without that glyph so its text remains readable. Previously
uploaded icons remain available during an API outage. Failures are logged with
a five-minute retry delay; the relay and translation cache continue working.

Supported artwork is limited to private-use bitmap glyphs reachable from the
default font. Explicit component-specific fonts, TTF/Unihex glyph rasterization,
pack overlays, animated textures, and client-specific font options are not
emulated. A malformed font graph disables icons for that candidate pack while
retaining its translations. Font processing is bounded to 128 referenced fonts,
16 reference levels, 2,048 providers, 512 glyphs, 32 MiB of font input, and
16 million decoded pixels total. PNG sheets are limited to 4096 pixels per axis
and four million pixels each. Entries are read directly without extraction.

At startup, `Loaded N resource-pack icons for Discord` reports the available
artwork. Replacing the jar and restarting is sufficient; no extra configuration
is needed. On the first occurrence of `\ue00f Silver…`, its image is uploaded
and used inline in the advancement description. Tests cover font references,
Unicode code points, atlas cropping, cache reloads, concurrent upload reuse,
API failure/cancellation, and actual Kord emoji creation and webhook payloads
using mocked HTTP. Slot-limit tests cover least-used replacement, recency ties,
unrelated emoji, messages still being sent, and Discord's full-inventory error.
Persistence and concurrency tests cover restart counts/recency, atomic write
failure, simultaneous increments, delayed deletion/upload/send responses,
queue cancellation, and ambiguous deletion failures.
Live rendering still needs verification against your pack and Discord server.

## Build and use

This branch follows BlockBot's current Minecraft **26.2** source and requires
**JDK 25**. From the repository root:

```sh
bash gradlew clean build --console=plain
```

Use `build/libs/blockbot-discord-2.0.9+26.2.jar`. Stop the server, replace the
existing BlockBot jar in `mods/` with this jar, retain the required Fabric API
and Fabric Language Kotlin mods, and restart. Keep the usual BlockBot
bot/channel configuration. Configure Better Server Packs, or set `resource-pack`
and `resource-pack-sha1` in `server.properties`. No client mod is needed.

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
vanilla advancement such as Stone Age. With Better Server Packs, run
`/pack reload` after updating the hosted pack. With vanilla settings, update
`resource-pack-sha1` (and `resource-pack` if the URL changed) in
`server.properties`. Within the next poll plus download time, BlockBot should
log the new hash and load the new translations. This polling updates BlockBot;
it does not reload vanilla's client resource-pack settings, which normally
require a server restart. Restarting with the same hash should log
`Using cached resource-pack translations` without fetching the pack.

This translates Discord messages and embeds. Minecraft's own server log can
still show custom translation keys, since its global language is unchanged.

## Discord startup compatibility

Kord 0.19.0-SNAPSHOT includes the upstream fix for Discord's nullable channel
`application_id` field
([kordlib/kord#1072](https://github.com/kordlib/kord/pull/1072)).
Kord modules are aligned through the direct core-voice dependency. Tests decode
absent, null, and populated IDs, including a GUILD_CREATE dispatch. A mock HTTP
test exercises KordEx webhook setup, recovery from a 503, webhook reuse, and
sending a message with the updated Kord dependency, without contacting Discord.

Relay setup retries HTTP 5xx and network failures up to four times, waiting
1, 2, 4, and 8 seconds. Retries happen before event-handler registration and
recheck for an existing webhook; message sends are not retried. Authentication
and permission errors are not retried. If setup still fails, the extension is
not registered with Minecraft's relay API as though it were ready. A persistent
Discord outage or invalid configuration still needs to be resolved before
restarting the bot/server.
