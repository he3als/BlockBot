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
flattened to plain text, but icon colours are captured before flattening.

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
padding is transparent. Opaque pixels stay opaque; BlockBot does not guess
which colours might be a background.
The first provider defining a character wins; space providers produce no icon.

The following optional section in `config/blockbot-discord.toml` controls this
feature. Existing configurations use these defaults without needing edits:

```toml
[ResourcePackEmojis]
enabled = true
prefix = "bb_"
coloured = true
automaticLayout = true
scale = 1.0
baselineOffset = 0.0
padding = 23.1667
```

With `coloured = true`, icons in Minecraft components match their text colour,
including colours inherited from a parent and colours on translation arguments.
Like Minecraft, BlockBot multiplies the texture's RGB channels by the text
colour while preserving alpha. White or unstyled text retains the original
artwork. Set `coloured = false` to always use the texture's original colours.
This affects icons in chat and advancement titles/descriptions; ordinary Discord
text is unchanged. Plain-string messages have no Minecraft colour information
and use the original artwork. Both modes use nearest-neighbour scaling, with
no smoothing or blended pixels.

Automatic layout sizes the **visible artwork** to capital-letter height and
anchors its bottom to the text baseline. It chooses the appropriate layout for
ordinary chat or advancement embed descriptions without any configuration.
Transparent margins in the original texture do not affect alignment. Wide
artwork is scaled down further if necessary to fit the emoji box; aspect ratio,
transparency, tint and nearest-neighbour sampling are preserved.

Optional adjustments apply to both contexts:

- `scale` multiplies the artwork size (`0.25`–`2`), keeping its bottom anchored.
- `baselineOffset` moves the artwork vertically in CSS pixels (`-16`–`16`);
  positive values move it down. Artwork stays within the upload canvas.

Invalid adjustments warn and use the default layout. `padding` is a legacy
setting, used **only** with `automaticLayout = false`: it adds centred transparent
space on each side of the 128×128 canvas (`0`–`49` percent). Existing configurations
automatically receive the new alignment even if they contain an old padding value.

The automatic calculation uses Discord's desktop/web assets and actual chat and
advancement DOM inspected on 2026-09-23, checked against the font's own tables:

- [gg sans Regular 2.001](https://discord.com/assets/66d715454104d24e.woff2)
  has `unitsPerEm = 1000`, `sCapHeight = 690`, ascent `1018` and descent `316`.
  The `H` outline spans `y = 0..690`; capital height is `0.69 × font-size`.
- [Emoji images](https://discord.com/assets/552653.83d0e2a7d9e8b38f.css)
  are bottom-aligned inside a baseline-aligned inline-block. Font ascent,
  descent and [line leading](https://www.w3.org/TR/CSS2/visudet.html#leading)
  determine where the text baseline falls inside that image.
- [Embed text](https://discord.com/assets/603779.863bc0c4e4c6ff49.css)
  is 14px with an 18px line box and
  [18px emoji](https://discord.com/assets/198496.7ea2af35bfe94977.css).
  Chat uses 16px text, a 22px line box and
  [22px emoji](https://discord.com/assets/419070.51520158c0dc856e.css).

| Context | Capital height | Baseline below emoji top | Square artwork in 128px upload |
| --- | --- | --- | --- |
| Advancement description | 9.66 CSS px | 14 CSS px | 69px high, bottom at 100px |
| Ordinary chat | 11.04 CSS px | 16 CSS px | 64px high, bottom at 93px |

The source-metric calculation was independently checked with DOM baseline markers
at six font sizes and a local before/after preview using actual advancement icons.
PNG coordinates must round to whole pixels. Viewer fonts, mobile styling and
Discord image resampling can still affect appearance; a server-side PNG cannot
query every viewer's settings. Rounded letters such as `O` also exceed cap height.

Layout is applied after tinting and before hashing, slot reservation and upload.
Different layouts create variants on demand without downloading the pack again
or deleting its cache. Each size/colour variant has its own persisted usage count;
old variants remain until normal least-used eviction removes them. Replacement
still completes before sending the message.

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
Each distinct tinted image uses its own slot and usage count; identical colours
reuse the same emoji across messages and restarts. The on-disk font cache keeps
the original artwork, so changing this setting requires no pack download.
Tint generation is bounded to 128 variants per message; additional variants
or failed tint operations use the original artwork.

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
Colour tests cover inherited styles, translated arguments, per-colour upload
reuse, alpha preservation, disabled tinting, and nearest-neighbour pixel output.
Layout tests cover source-font metrics against measured baselines, transparent
bounds, wide/tall artwork, scaling and vertical adjustment, nearest-neighbour
pixels, invalid settings, legacy padding, persisted upload reuse, and replacement
before sending when no free slots remain. A local Discord browser preview verified
the generated PNGs in chat and embed styles; tests do not post to Discord.

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
