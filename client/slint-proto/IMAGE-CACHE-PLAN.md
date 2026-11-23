# Image disk cache plan (Slint launcher)

Written 2026-11-23. Status: **approved by the owner 2026-11-23** (decisions below);
**implemented 2026-11-23, uncommitted, awaiting the owner's run** (results at the end).

## Why

The Rust launcher has no HTTP cache. Every image (pack icons, mod icons, skins, wardrobe
previews, room tiles) is downloaded again on each page visit, search and launcher start. A
version page with 431 mods downloads up to 431 icons every time it opens. Decoded images are
only kept in memory per page (`ModCards`, `Plaza`, `HeadCache`). Compose has the same gap
(`HttpImageState` is memory-only, 10 min TTL), so this is new behaviour, not a port.

## Scope

- In: everything that goes through `images::download` (one choke point): `images::icon`,
  player heads (`HeadCache`, `rooms_ui::player`, `online_crowd`), `background_appearance`,
  wardrobe thumbnails, the game window icon (`play/room.rs`), room tiles (`app.rs`), local pack
  icons (`packs.rs`).
- Out: JSON API responses (must stay current), mod/pack/Java downloads (they have the
  content store `dlc/`), the mod catalog metadata (own memory cache).
- No Kotlin or server change. No Slint types in the new module.

## Library choice

Checked on crates.io 2026-11-23:

| Option | Fit |
|---|---|
| `http-cache-reqwest` 0.16 (stable) | Needs reqwest 0.12; we are on 0.13.5. Would pull a second reqwest/hyper stack. Rejected. |
| `http-cache-reqwest` 1.0.0-alpha.11 + `http-cache` (cacache) | Supports reqwest 0.13, but alpha; needs `reqwest-middleware`; the cacache backend has no size limit or LRU, and the default runtime feature is async-std. We would still write eviction and offline fallback ourselves. |
| `http-cache-semantics` 3.0.0 (stable, BSD-2-Clause) | RFC 9111 rules only: is a stored response fresh, how to revalidate, how to merge a 304. No storage. |
| `cacache` 13.1 | Content-addressed disk store, no eviction; last release 2024-11. |

**Recommendation:** `http-cache-semantics` for the HTTP rules plus a small own disk store
(about 250 lines). The rules are the error-prone part and come from a stable crate; the store
is simple because entries are small, keyed by URL, and need our own size cap, offline
fallback and 清理缓存 integration anyway. Pin the exact version like other deps.

## Design

New module `src/image_cache.rs` (no Slint types):

- **Location:** `<data_dir>/image-cache/`. Each entry is `<sha256(url)>.img` (body) and
  `<sha256(url)>.json` (the serialized `CachePolicy`, URL, size, stored time). Writes go to a
  temp file then rename, body first and metadata last, so a crash never leaves metadata
  pointing at a partial body. An entry without valid metadata is ignored and deleted.
- **Lookup in `images::download(http, url)`:**
  1. Fresh entry (`CachePolicy::before_request` says fresh): return the body, no network.
  2. Stale entry: send the conditional request the policy builds (`If-None-Match` /
     `If-Modified-Since`). 304 → update metadata, return the stored body. 200 → replace.
  3. No entry: plain GET; store when the policy says storable (`no-store` is never stored).
  4. Network error or 5xx with a stale entry: return the stale body and log at debug
     (D3).
- **Freshness:** D2 below; otherwise server headers decide. Measured: forgecdn and i.mcmod.cn send
  `max-age=3600` + ETag, littleskin `max-age=31536000`, Modrinth only ETag + Last-Modified
  (heuristic freshness: 10% of the age since Last-Modified). Revalidations are cheap
  304s. D2 sets an optional floor.
- **Single flight:** one in-process download per URL (`HashMap<key, Arc<Mutex<()>>>`), like
  `HeadCache`, so 431 cards and several pages never fetch the same icon twice at once.
  Several launcher processes stay safe through atomic renames.
- **Bad cached bytes:** `images::icon` and the head decoders decode after download. If a
  cached body fails to decode, drop the entry and download once more without the cache.
- **Size cap and eviction (D1, 1 GiB):** a running byte total in memory. A cache hit touches the
  body's modified time (no metadata rewrite). On startup (in `spawn_blocking`, after the
  first frame) and whenever the total passes the cap, delete least recently used entries
  down to 90% of the cap. The existing 16 MiB per-image limit stays.
- **Privacy:** cached URLs are public image URLs; no auth headers are sent by
  `images::download` today, and requests that carry `Authorization` are never stored
  (guard in code + test).
- **清理缓存 (D4):** the settings page's cache cleanup also empties `image-cache/` and
  reports the freed size in the same message.

## Steps

1. Add `http-cache-semantics` (exact pin), check it compiles for Windows, Linux and mac with
   `cargo-zigbuild` like other deps (no new native code expected).
2. `image_cache.rs`: store, lookup/revalidate/fallback, single flight, eviction.
3. Route `images::download` through it; decode-failure retry in `images::icon` and the head
   helpers.
4. Settings cleanup integration.
5. Tests (local `TcpListener` server, as in `wardrobe.rs` tests): fresh hit makes no request;
   stale entry sends `If-None-Match` and a 304 returns the stored body; 200 replaces;
   `no-store` and `Authorization` requests are not stored; offline returns stale; truncated
   or missing metadata is ignored; corrupt body is dropped and refetched; eviction keeps the
   total under the cap and removes the oldest first; concurrent requests for one URL make one
   request.
6. Live check through `cargo preview-run` + MCP on the real server: open the 431-mod version
   page, restart, open it again; report request counts (debug counters in the log) and the
   time until all icons show, first vs. second run. Clippy clean, workspace tests green.

## Owner decisions (2026-11-23)

- **D1 size cap: 1 GiB.** Evict down to 90% when exceeded.
- **D2 freshness:** images from forgecdn (`*.forgecdn.net`), mcmod (`*.mcmod.cn`) and
  Modrinth (`cdn.modrinth.com`) count as fresh for 15 days after they were stored or last
  revalidated, whatever the headers say, because pack and mod icons rarely change. After
  15 days they are revalidated with ETag/Last-Modified. Every other host follows the response
  headers and ETag revalidation.
- **D3 offline fallback** to stale images: yes.
- **D4 清理缓存 includes the image cache:** yes.
- **D5 library:** `http-cache-semantics` + own store.

## Related, not part of this plan

`paths::data_dir()` and `Catalog::new` (`mod_catalog.db`) default to paths under
`CARGO_MANIFEST_DIR`, which do not exist on players' machines. To be solved in U5 packaging;
this plan only uses `data_dir()` as it is.

## Results (2026-11-23)

- Code: `src/image_cache.rs` (+ `src/image_cache/tests.rs`), `images::download` and the new
  `images::download_image` (decode failure drops the entry and downloads once more), callers in
  `images.rs`, `rooms_ui.rs`, `wardrobe.rs`; 清理缓存 in `settings_ui.rs`.
- `http-cache-semantics` =3.0.0 with features `serde` and `reqwest`; new lock entries
  `http-serde`, `time` and its helpers, all pure Rust. Linux/mac zigbuild not run.
- Clippy clean; tests 263 + 62 + 11 pass (11 new cache tests, local HTTP server, pinned
  hosts through `ClientBuilder::resolve`). The `Authorization` guard has no dedicated test.
- Live, real server, isolated data folder, same pages twice with a restart in between:

  | Page | Cold run | Warm run |
  |---|---|---|
  | Plaza (59 pack icons) | 58 downloaded, 9.3 s | 59 from disk, 0.1 s |
  | 乌托邦探险之旅 3.5.2 (430 mod icons) | 430 downloaded, 90.5 s | 430 from disk, 0.4 s |

  The cold run stored 490 images in 19 MB. Unrelated to images: in both runs the mod catalog
  lookup took about 50 s before the icons were requested, because catalog metadata is only
  cached in memory.

## Follow-up: mod data lookups (owner, 2026-11-23)

Measured the ~50 s wait before mod icons: `Catalog::get_mods` already batched, but every
request goes to the mirror `mod.mcimirror.top` first (`preferModMirror` defaults to true), and
the mirror costs about 0.27 s per id whatever the batch size, with no mirror-side cache
(100 ids 28-31 s; 388 ids in one request 105.6 s). The official APIs answer 100 ids in about
2.5 s and accept a 43 KB URL (2388 ids).

- **No fixed batch size** (owner: 取消100个的限制): CurseForge `mods` / `mods/files` and
  Modrinth `version_files` send all ids in one request body; Modrinth `projects` / `versions`
  put ids in the URL and split only above 7000 bytes of encoded ids (`adapter.rs`
  `url_batches`).
- **Mod data disk cache** (owner chose option 3): found projects are kept 15 days in
  `<data_dir>/catalog-cache.db` (SQLite, `catalog/disk.rs`), checked after the memory cache
  and before the network; 清理缓存 empties it.
- **Known risk, not addressed (owner chose only option 3):** the server client's total
  timeout is 60 s. One mirror request for a large pack now takes longer than that, so it
  times out and falls back to the official API. A player who cannot reach the official APIs
  gets no mod names on the first visit of a large pack (before, 100-id batches of about 30 s
  each stayed under the timeout). Options left open: a longer timeout for catalog requests,
  or racing mirror and official.
