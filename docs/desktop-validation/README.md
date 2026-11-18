# Linux Iced runtime evidence — 2026-11-18

These summaries come from the actual `target/debug/proton-gallery` process with
100,000 fixture catalog items and the normal `MediaProvider`/image decoding path.
Commands:

```sh
python3 scripts/desktop-smoke.py --backend wayland
python3 scripts/desktop-smoke.py --backend wayland --cycles 1  # final rebuilt executable
python3 scripts/desktop-smoke.py --backend wayland --cycles 6
python3 scripts/desktop-smoke.py --backend x11 --scale 1.25 --check-theme
```

The `evidence` paths in JSON point to the full logs and app-only PNGs under `/tmp`
on the validation host. Selected screenshots are archived in `../images/desktop-*`.
The helper restores the original desktop theme and closes only its child process.
Redraw subscription intervals include idle/scheduling stalls and cause ongoing redraws;
widget timing covers CPU construction only. RSS excludes GPU memory. These development,
partly concurrent smoke runs do not close release performance, mixed-DPI, Windows,
screen-reader or real-account acceptance. See `../VALIDATION.md` for exact checks and gaps.
