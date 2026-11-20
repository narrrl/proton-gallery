# Desktop benchmark

`proton-gallery --bench` times the desktop's hot paths on a synthetic library,
offline: no account, network or display. It builds a catalog in a throwaway
directory, measures, prints a table and removes the directory. Use it to hold
performance changes to numbers: run it before and after, on the same machine,
in a release build.

```sh
cargo build --release -p pg-desktop --locked
target/release/proton-gallery --bench            # 50,000 items, 100,000 map points
target/release/proton-gallery --bench --json     # the same, durations in µs
target/release/proton-gallery --bench --count 200000 --points 20000 --quick
```

| Option | Meaning |
|---|---|
| `--count N` | Items in the synthetic library (default 50,000, at most 1,000,000) |
| `--points N` | Located photos for Places clustering (default 100,000) |
| `--quick` | A tenth of the repeats and a smaller original, for a quick check |
| `--json` | JSON instead of the table |

Each row reports p50, p95 and the maximum over its samples.

| Row | What runs |
|---|---|
| open catalog + first page (cold-ish) | `App::open` on the existing catalog, the timeline outline and the records of the first page (100 items). The OS file cache stays warm. |
| outline + first page (warm) | The same on an already open `App`. |
| plan justified grid | `grid::Layout::plan` for the whole timeline at 1010 px, the grid width of the default 1280 px window. |
| visible rows + items (per scroll) | `Layout::visible` and `Layout::items_in` at random scroll positions: what each scroll event computes. |
| cluster N points, zoom Z | `places::bubbles`, the projection and `proton_gallery_rs::places::cluster` the map runs per draw, on a 1000×700 map at four zoom levels. |
| decode W×H JPEG to tile | `thumbs::decode` to the 320 px tile size: a Proton grid thumbnail, and an original standing in for a missing one. |
| shrink W×H JPEG for disk cache | `proton_media_rs::render::thumbnail`, what `DiskThumbs::store` does to a picture larger than 256 KiB. |

`cargo test -p pg-desktop` runs the whole benchmark on a tiny library
(`bench::tests`), so it keeps working.

For timing the real widget tree, `PG_METRICS=1` makes the running app print
`view_us` per view and `frame_us` per frame to stderr.

## Baseline

Release build, Linux, 8 threads. The machine was under heavy unrelated load
(load average about 20), so treat the p95 and max columns as noisy and compare
p50 first.

```text
proton-gallery bench: 50000 items, 100000 located photos
operation                                      samples         p50         p95         max
open catalog + first page (cold-ish)                10    367.2 ms    456.5 ms    456.5 ms
outline + first page (warm)                         20    222.4 ms    367.8 ms    379.8 ms
plan justified grid (1010 px)                       20     10.9 ms     11.5 ms     11.6 ms
visible rows + items (per scroll)                20000      281 ns      470 ns    133.7 µs
cluster 100000 points, zoom 1 (96 markers)          10     52.4 ms     63.9 ms     63.9 ms
cluster 100000 points, zoom 16 (7 markers)          10     11.8 ms     13.1 ms     13.1 ms
cluster 100000 points, zoom 256 (44 markers)        10     26.0 ms     29.4 ms     29.4 ms
cluster 100000 points, zoom 4096 (21 markers)       10     24.0 ms     26.7 ms     26.7 ms
decode 512x384 JPEG to tile                        200     17.8 ms     35.7 ms     77.8 ms
decode 4032x3024 JPEG to tile                       10    866.9 ms   1141.7 ms   1141.7 ms
shrink 4032x3024 JPEG for disk cache                10    916.9 ms   1030.4 ms   1030.4 ms
```
