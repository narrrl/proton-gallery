#!/usr/bin/env python3
"""Exercise the actual Linux Iced window with fixture media; keep evidence in /tmp.

Requires Pillow; X11 also needs Xvfb and libXtst. Wayland uses Hyprland's Lua IPC.
Never enters credentials or operates a window belonging to another process.
"""
import argparse
import ctypes as c
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess as sp
import tempfile
import time


def command(*args):
    return sp.check_output(args, text=True).strip()


class X11:
    def __init__(self, display, pid):
        self.x = c.CDLL("libX11.so.6")
        self.xt = c.CDLL("libXtst.so.6")
        self.x.XOpenDisplay.argtypes = [c.c_char_p]
        self.x.XOpenDisplay.restype = c.c_void_p
        self.d = self.x.XOpenDisplay(display.encode())
        assert self.d
        self.x.XDefaultRootWindow.argtypes = [c.c_void_p]
        self.x.XDefaultRootWindow.restype = c.c_ulong
        root = self.x.XDefaultRootWindow(self.d)
        self.x.XGetGeometry.argtypes = [c.c_void_p, c.c_ulong, c.POINTER(c.c_ulong), c.POINTER(c.c_int), c.POINTER(c.c_int), c.POINTER(c.c_uint), c.POINTER(c.c_uint), c.POINTER(c.c_uint), c.POINTER(c.c_uint)]
        self.x.XQueryTree.argtypes = [c.c_void_p, c.c_ulong, c.POINTER(c.c_ulong), c.POINTER(c.c_ulong), c.POINTER(c.POINTER(c.c_ulong)), c.POINTER(c.c_uint)]
        self.x.XFetchName.argtypes = [c.c_void_p, c.c_ulong, c.POINTER(c.c_char_p)]
        self.x.XFree.argtypes = [c.c_void_p]
        self.x.XSetInputFocus.argtypes = [c.c_void_p, c.c_ulong, c.c_int, c.c_ulong]
        self.x.XMapRaised.argtypes = [c.c_void_p, c.c_ulong]
        self.x.XSync.argtypes = [c.c_void_p, c.c_int]
        self.x.XStringToKeysym.argtypes = [c.c_char_p]
        self.x.XStringToKeysym.restype = c.c_ulong
        self.x.XKeysymToKeycode.argtypes = [c.c_void_p, c.c_ulong]
        self.x.XFlush.argtypes = [c.c_void_p]
        self.x.XResizeWindow.argtypes = [c.c_void_p, c.c_ulong, c.c_uint, c.c_uint]
        self.xt.XTestFakeKeyEvent.argtypes = [c.c_void_p, c.c_uint, c.c_int, c.c_ulong]
        self.xt.XTestFakeButtonEvent.argtypes = [c.c_void_p, c.c_uint, c.c_int, c.c_ulong]
        self.xt.XTestFakeMotionEvent.argtypes = [c.c_void_p, c.c_int, c.c_int, c.c_int, c.c_ulong]
        children = c.POINTER(c.c_ulong)()
        n, outroot, parent = c.c_uint(), c.c_ulong(), c.c_ulong()
        self.x.XQueryTree(self.d, root, c.byref(outroot), c.byref(parent), c.byref(children), c.byref(n))
        self.w = None
        for child in children[:n.value]:
            name = c.c_char_p()
            if self.x.XFetchName(self.d, child, c.byref(name)):
                title = name.value.decode(errors="replace")
                self.x.XFree(name)
                if "Proton Gallery" in title:
                    self.w = child
        self.x.XFree(children)
        assert self.w, f"No Gallery window for {pid} on the isolated X server"
        self.x.XMapRaised(self.d, self.w)
        self.x.XSync(self.d, 0)
        self.x.XSetInputFocus(self.d, self.w, 1, 0)
        self.x.XFlush(self.d)
        self.display = display

    def key(self, key):
        code = self.x.XKeysymToKeycode(self.d, self.x.XStringToKeysym(key.encode()))
        assert code, key
        self.xt.XTestFakeKeyEvent(self.d, code, 1, 0)
        self.xt.XTestFakeKeyEvent(self.d, code, 0, 0)
        self.x.XFlush(self.d)

    def capture(self, path):
        from PIL import ImageGrab
        root = c.c_ulong()
        x, y, width, height, border, depth = c.c_int(), c.c_int(), c.c_uint(), c.c_uint(), c.c_uint(), c.c_uint()
        assert self.x.XGetGeometry(self.d, self.w, c.byref(root), c.byref(x), c.byref(y), c.byref(width), c.byref(height), c.byref(border), c.byref(depth))
        ImageGrab.grab(bbox=(x.value, y.value, x.value + width.value, y.value + height.value), xdisplay=self.display).save(path)

    def resize(self, width, height):
        self.x.XResizeWindow(self.d, self.w, width, height)
        self.x.XFlush(self.d)

    def close(self):
        class Data(c.Union):
            _fields_ = [("longs", c.c_long * 5)]
        class Client(c.Structure):
            _fields_ = [("type", c.c_int), ("serial", c.c_ulong), ("send_event", c.c_int), ("display", c.c_void_p), ("window", c.c_ulong), ("message_type", c.c_ulong), ("format", c.c_int), ("data", Data)]
        class Event(c.Union):
            _fields_ = [("client", Client), ("pad", c.c_long * 24)]
        self.x.XInternAtom.argtypes = [c.c_void_p, c.c_char_p, c.c_int]
        self.x.XInternAtom.restype = c.c_ulong
        self.x.XSendEvent.argtypes = [c.c_void_p, c.c_ulong, c.c_int, c.c_long, c.POINTER(Event)]
        event = Event()
        event.client = Client(33, 0, 1, self.d, self.w, self.x.XInternAtom(self.d, b"WM_PROTOCOLS", 0), 32)
        event.client.data.longs[0] = self.x.XInternAtom(self.d, b"WM_DELETE_WINDOW", 0)
        self.x.XSendEvent(self.d, self.w, 0, 0, c.byref(event))
        self.x.XFlush(self.d)

    def mouse(self, x, y, button):
        self.xt.XTestFakeMotionEvent(self.d, -1, x, y, 0)
        self.xt.XTestFakeButtonEvent(self.d, button, 1, 0)
        self.xt.XTestFakeButtonEvent(self.d, button, 0, 0)
        self.x.XFlush(self.d)

    def drag(self, start, end):
        self.xt.XTestFakeMotionEvent(self.d, -1, *start, 0)
        self.xt.XTestFakeButtonEvent(self.d, 1, 1, 0)
        self.xt.XTestFakeMotionEvent(self.d, -1, *end, 0)
        self.xt.XTestFakeButtonEvent(self.d, 1, 0, 0)
        self.x.XFlush(self.d)


class Wayland:
    def __init__(self, display, pid):
        windows = json.loads(command("hyprctl", "-j", "clients"))
        self.window = next(w for w in windows if w["pid"] == pid)
        self.address = "address:" + self.window["address"]
        self.dispatch("focus", {"window": self.address})

    def dispatch(self, name, args):
        fields = ",".join(f"{key}={json.dumps(value)}" for key, value in args.items())
        result = command("hyprctl", "eval", f"hl.dispatch(hl.dsp.{name}({{{fields}}}))")
        if result != "ok":
            raise RuntimeError(result)

    def key(self, key):
        self.dispatch("send_shortcut", {"mods": "", "key": key, "window": self.address})

    def capture(self, path):
        window = next(w for w in json.loads(command("hyprctl", "-j", "clients")) if w["address"] == self.window["address"])
        x, y = window["at"]
        width, height = window["size"]
        sp.run(["grim", "-g", f"{x},{y} {width}x{height}", str(path)], check=True)


def percentile(samples, fraction):
    return sorted(samples)[min(len(samples) - 1, int(len(samples) * fraction))] if samples else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=["x11", "wayland"], required=True)
    parser.add_argument("--binary", type=Path, default=Path("target/debug/proton-gallery"),
                        help="Executable to exercise, including an extracted Arch package")
    parser.add_argument("--cycles", type=int, default=3, choices=range(1, 21), help="Repeated browsing/viewer cycles (1–20)")
    parser.add_argument("--check-theme", action="store_true", help="Temporarily toggle and restore the desktop color-scheme preference")
    parser.add_argument("--scale", type=float, default=1.0)
    args = parser.parse_args()
    evidence = Path(tempfile.mkdtemp(prefix=f"pg-{args.backend}-{args.scale}-"))
    env = dict(os.environ, RUST_BACKTRACE="1", PG_METRICS="1", PG_SCALE_FACTOR=str(args.scale))
    server = None
    if args.backend == "x11":
        env.pop("WAYLAND_DISPLAY", None)
        env["DISPLAY"] = ":98"
        server = sp.Popen(["Xvfb", env["DISPLAY"], "-screen", "0", "2000x1300x24", "-nolisten", "tcp"], stdout=sp.DEVNULL, stderr=sp.DEVNULL)
        time.sleep(0.5)
        assert server.poll() is None, "Xvfb :98 could not start"
    rss = []
    cycle_rss = []
    theme_pixels = {}
    old_theme = None
    with tempfile.TemporaryDirectory(prefix="pg-smoke-data-") as data:
        sentinel = Path(data) / "preserve-explicit-directory"
        sentinel.write_text("preserve me")
        log = evidence / "app.log"
        with log.open("w") as output:
            app = sp.Popen([str(args.binary.resolve()), "--demo", "100000", "--data-dir", data], env=env, stdout=output, stderr=output)
            def sample():
                assert app.poll() is None, log.read_text()[-2000:]
                status = Path(f"/proc/{app.pid}/status").read_text()
                memory = re.search(r"VmRSS:\s+(\d+)", status)
                assert memory, log.read_text()[-2000:]
                rss.append(int(memory[1]))
            try:
                # Activate our mapped window before waiting for the async UI outline:
                # some compositors suspend redraw-driven updates for hidden group tabs.
                window_deadline = time.monotonic() + 15
                while True:
                    try:
                        driver = (X11 if args.backend == "x11" else Wayland)(env.get("DISPLAY"), app.pid)
                        break
                    except (AssertionError, StopIteration):
                        sample()
                        assert time.monotonic() < window_deadline, "Gallery window did not map"
                        time.sleep(0.1)
                # Wait for committed fixture records; runtime still loads pages/bytes normally.
                started = time.monotonic()
                deadline = started + 180
                while time.monotonic() < deadline:
                    sample()
                    try:
                        with sqlite3.connect(f"file:{data}/catalog.db?mode=ro", uri=True) as db:
                            if db.execute("select count(*) from assets").fetchone()[0] == 100000:
                                break
                    except sqlite3.Error:
                        pass
                    time.sleep(0.25)
                else:
                    raise RuntimeError("Fixture indexing did not finish in 180s")
                while "PG_METRICS outline_items=100000" not in log.read_text():
                    sample()
                    assert time.monotonic() < deadline, "UI outline did not arrive"
                    time.sleep(0.1)
                outline_ready_seconds = round(time.monotonic() - started, 3)
                time.sleep(1)
                time.sleep(0.8)
                driver.capture(evidence / "grid.png")
                for cycle in range(args.cycles):
                    for key in ["Next"] * 25 + ["End", "Home"]:
                        driver.key(key)
                        time.sleep(0.025)
                        sample()
                    driver.key("Home")
                    driver.key("Right")
                    driver.key("Return")
                    for key in ["Right"] * 30 + ["Left"] * 20:
                        driver.key(key)
                        time.sleep(0.035)
                        sample()
                    time.sleep(0.4)
                    driver.capture(evidence / f"viewer-{cycle}.png")
                    if args.backend == "x11":
                        for _ in range(6):
                            driver.mouse(600, 350, 4)
                        driver.drag((600, 350), (780, 450))
                        time.sleep(0.3)
                        driver.capture(evidence / f"zoom-pan-{cycle}.png")
                    driver.key("Escape")
                    sample()
                    cycle_rss.append(rss[-1])
                driver.key("Home")
                for _ in range(4):
                    driver.key("Right")
                driver.key("Return")
                time.sleep(0.5)
                driver.capture(evidence / "viewer-error.png")
                driver.key("r")
                time.sleep(0.5)
                driver.capture(evidence / "viewer-retry.png")
                driver.key("Escape")
                driver.key("F5")
                time.sleep(0.7)
                driver.capture(evidence / "refresh.png")
                if args.check_theme:
                    from PIL import Image
                    old_theme = command("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                    for theme in ["prefer-light", "prefer-dark"]:
                        command("gsettings", "set", "org.gnome.desktop.interface", "color-scheme", theme)
                        time.sleep(1.2)
                        picture = evidence / f"{theme}.png"
                        driver.capture(picture)
                        theme_pixels[theme] = Image.open(picture).getpixel((5, 100))
                if args.backend == "x11":
                    driver.resize(640, 480)
                    time.sleep(0.6)
                    driver.key("End")
                    time.sleep(0.5)
                    driver.capture(evidence / "small-grid.png")
                    driver.key("Return")
                    time.sleep(0.6)
                    driver.capture(evidence / "small-viewer.png")
                    sample()
                driver.key("Escape")
                driver.capture(evidence / "final-grid.png")
                # Close through the window manager so the application's own teardown runs.
                if args.backend == "wayland":
                    driver.dispatch("window.close", {"window": driver.address})
                else:
                    driver.close()
                app.wait(timeout=10)
                assert sentinel.exists() and (Path(data) / "catalog.db").exists(), "Explicit demo directory was deleted"
            finally:
                if old_theme is not None:
                    command("gsettings", "set", "org.gnome.desktop.interface", "color-scheme", old_theme)
                if app.poll() is None:
                    app.terminate()
                    app.wait(timeout=10)
                if server:
                    server.terminate()
                    server.wait(timeout=10)
        contents = log.read_text()
        frames = [int(n) for n in re.findall(r"frame_us=(\d+)", contents)]
        views = [int(n) for n in re.findall(r"view_us=(\d+)", contents)]
        summary = dict(binary=str(args.binary.resolve()), cycles=args.cycles, outline_ready_seconds=outline_ready_seconds, backend=args.backend, scale=args.scale, evidence=str(evidence),
                       rss_peak_kib=max(rss), rss_after_browsing_kib=rss[-1], cycle_rss_kib=cycle_rss,
                       theme_pixels=theme_pixels, explicit_directory_preserved=sentinel.exists(),
                       redraw_interval_us={"samples": len(frames), "p50": percentile(frames, .5), "p95": percentile(frames, .95), "max": max(frames, default=None)},
                       widget_construction_us={"samples": len(views), "p50": percentile(views, .5), "p95": percentile(views, .95), "max": max(views, default=None)},
                       page_work_peak=max(map(int, re.findall(r"pages=(\d+)", contents)), default=0),
                       thumbnail_work_peak=max(map(int, re.findall(r"thumbnails=(\d+)", contents)), default=0),
                       exit_code=app.returncode)
        (evidence / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
