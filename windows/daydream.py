"""Daydream for Windows: a fullscreen StandBy-style display (photos, clocks, widgets).

Run:  pip install -r requirements.txt  &&  python daydream.py  [--config PATH] [--windowed]
Keys: ←/→ pages · ↑/↓ clock face / widget stack · N night mode · F11 window · Esc/Q quit
"""
from __future__ import annotations

import calendar
import ctypes
import datetime as dt
import functools
import io
import math
import os
import queue
import random
import sys
import threading
import time

import pygame
from PIL import Image, ImageEnhance, ImageFilter, ImageOps

import core

FPS = 30
CROSSFADE = 1.2  # seconds
GAP, RADIUS = 10, 22
CURSOR_HIDE_AFTER = 3.0
RETRY_SECONDS = 30
BATCH = 50
WHITE, GREY, BLACK = (255, 255, 255), (142, 142, 147), (0, 0, 0)
CARD, ORANGE, RED, GREEN, BLUE = (28, 28, 30), (255, 159, 10), (255, 69, 58), (48, 209, 88), (100, 210, 255)
NIGHT_TINT, NIGHT_DIM = (255, 42, 26), (150, 150, 150)
PAGES = ("widgets", "photos", "clock")


# ----------------------------------------------------------------------------- platform bits
def win32_setup() -> None:
    """Native resolution despite display scaling, and keep the display awake."""
    if sys.platform != "win32":
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except (AttributeError, OSError):
        ctypes.windll.user32.SetProcessDPIAware()
    ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000002)  # ES_CONTINUOUS | ES_DISPLAY_REQUIRED


def battery() -> tuple[int, bool] | None:
    """(percent, charging) on Windows laptops; None on desktops and other platforms."""
    if sys.platform != "win32":
        return None

    class Status(ctypes.Structure):
        _fields_ = [("ac", ctypes.c_ubyte), ("flag", ctypes.c_ubyte), ("percent", ctypes.c_ubyte),
                    ("saver", ctypes.c_ubyte), ("life", ctypes.c_ulong), ("full", ctypes.c_ulong)]

    s = Status()
    if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)) or s.flag & 128 or not 0 <= s.percent <= 100:
        return None  # flag 128 = no system battery
    return s.percent, s.ac == 1


# ----------------------------------------------------------------------------- photo feed
def layout_rects(layout: str, w: int, h: int) -> list[tuple[int, int, int, int]]:
    """Tile rectangles for a layout; on a portrait screen rows and columns swap."""
    if layout in (core.SINGLE, core.SINGLE_PORTRAIT):
        return [(0, 0, w, h)]
    vertical = h > w
    W, H = (h, w) if vertical else (w, h)
    g = GAP
    if layout in (core.DUO, core.TRIO):
        n = core.CAPACITY[layout]
        tw = (W - g * (n + 1)) // n
        rects = [(g + i * (tw + g), g, tw, H - 2 * g) for i in range(n)]
    else:  # mosaic: narrow tile, then two wide tiles stacked
        tw = (W - 3 * g) // 2
        th = (H - 3 * g) // 2
        rects = [(g, g, tw, H - 2 * g), (2 * g + tw, g, tw, th), (2 * g + tw, 2 * g + th, tw, th)]
    return [(y, x, rh, rw) for x, y, rw, rh in rects] if vertical else rects


class Feed(threading.Thread):
    """Background worker: fetches photos, plans slides, decodes and pre-scales them.
    Holds one finished slide in [ready], so the next slide is prepared while one is on screen."""

    def __init__(self, cfg: dict, size: tuple[int, int], stop: threading.Event | None = None):
        super().__init__(daemon=True)
        self.cfg, self.size = cfg, size
        self.stop = stop or threading.Event()
        self.ready: queue.Queue = queue.Queue(maxsize=1)
        self.status = "Loading photos…"
        self.queue: list[core.Item] = []
        self.pending: list = []
        self.last_ids: set[str] = set()
        self.server = None
        self.album_filter: bool | None = None

    # -- sources
    def _refill(self) -> None:
        src = self.cfg["photo_source"]
        if src == "local":
            paths = core.scan_folders(self.cfg["folders"])
            if not paths:
                raise core.ImmichError("No photos found in: " + ", ".join(self.cfg["folders"]))
            random.shuffle(paths)
            self.pending += paths
        elif src == "immich":
            im = self.cfg["immich"]
            self.server = self.server or core.Immich.from_config(im)
            if im.get("mode") == "albums":
                albums = im.get("albums") or []
                if not albums:
                    raise core.ImmichError("Set immich.albums (album ids) for albums mode")
                if self.album_filter is None:  # random search honours albumIds from v1.138
                    self.album_filter = self.server.version() >= (1, 138, 0)
                per = max(10, BATCH // len(albums))
                assets = []
                for alb in albums:
                    found = self.server.random(per, album=alb) if self.album_filter else self.server.album_assets(alb)
                    assets += random.sample(found, min(per, len(found)))
                random.shuffle(assets)
            else:
                assets = self.server.random(BATCH, favorites=im.get("mode") == "favorites")
            if not assets:
                raise core.ImmichError("Immich returned no photos")
            self.pending += assets
        else:
            raise core.ImmichError("Set photo_source to \"local\" or \"immich\" in the config")

    def _resolve(self, raw) -> core.Item | None:
        """Turn a path or Immich asset into an Item with its exact displayed aspect."""
        try:
            if isinstance(raw, str):
                aspect = core.image_aspect(raw)
                return core.Item(raw, aspect, raw, _exif_date(raw)) if aspect else None
            data = self.server.preview(raw["id"])
            aspect = core.image_aspect(data)
            return core.Item("immich:" + raw["id"], aspect, data, core.immich_caption(raw)) if aspect else None
        except Exception:
            return None

    def _fill(self) -> None:
        failures = 0
        while len(self.queue) <= core.LOOKAHEAD and not self.stop.is_set():
            if not self.pending:
                if self.queue:
                    return
                self._refill()
            item = self._resolve(self.pending.pop(0))
            if item:
                self.queue.append(item)
                failures = 0
            elif (failures := failures + 1) >= 5:
                if self.queue:
                    return  # server/disk trouble: show what's queued rather than retrying every pending photo
                raise core.ImmichError("Couldn't load photos")

    # -- slides
    def run(self) -> None:
        collages = self.cfg["layout"] != "single"
        while not self.stop.is_set():
            w, h = self.size  # re-read each slide: F11 changes it
            try:
                self._fill()
                layout, items = core.next_slide(self.queue, self.last_ids, w / h, collages, random.randrange(1000))
            except Exception as e:  # network down, bad config, unreadable folder…
                self.status = str(e) or e.__class__.__name__
                self.stop.wait(RETRY_SECONDS)
                continue
            try:
                tiles = [self._prepare(it, r, layout) for it, r in zip(items, layout_rects(layout, w, h))]
            except Exception as e:  # read fine but won't decode: its items are already dropped, plan again
                self.status = f"Skipping a photo that won't decode ({e.__class__.__name__})"
                self.stop.wait(1)
                continue
            self.last_ids = {it.id for it in items}
            self.status = ""
            slide = {"layout": layout, "tiles": tiles, "caption": items[0].caption}
            while not self.stop.is_set():
                try:
                    self.ready.put(slide, timeout=0.2)
                    break
                except queue.Full:
                    pass

    def _prepare(self, item: core.Item, rect, layout: str) -> dict:
        x, y, w, h = rect
        portrait = layout == core.SINGLE_PORTRAIT
        intensity = (0.4 if portrait else 1.0 if layout == core.SINGLE else 0.6) if self.cfg["ken_burns"] else 0
        kb = core.KenBurns.random(item.id, intensity)
        zoom = max(kb.s0, kb.s1)
        with Image.open(io.BytesIO(item.load) if isinstance(item.load, bytes) else item.load) as src:
            src.draft("RGB", (w * 2, h * 2))  # fast JPEG downscale while decoding
            im = ImageOps.exif_transpose(src).convert("RGB")
        tile = {"kb": kb, "zoom": zoom, "backdrop": None}
        if portrait:  # whole photo centred over a blurred, dimmed copy of itself
            small = ImageOps.fit(im, (max(1, w // 12), max(1, h // 12)))
            back = ImageEnhance.Brightness(small.filter(ImageFilter.GaussianBlur(4))).enhance(0.55)
            tile["backdrop"] = _surface(back.resize((w, h), Image.BILINEAR))
            s = min(w / im.width, h / im.height)
            fw, fh = round(im.width * s), round(im.height * s)
            rect = (x + (w - fw) // 2, y + (h - fh) // 2, fw, fh)
            w, h = fw, fh
        s = max(w / im.width, h / im.height) * zoom  # cover the frame at the deepest zoom
        tile["image"] = _surface(im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS))
        tile["rect"] = rect
        return tile


def _surface(im: Image.Image) -> pygame.Surface:
    return pygame.image.frombytes(im.tobytes(), im.size, "RGB")


def _exif_date(path: str) -> str:
    try:
        with Image.open(path) as im:
            raw = im.getexif().get_ifd(0x8769).get(36867) or ""
        return dt.datetime.strptime(str(raw)[:10], "%Y:%m:%d").strftime("%B %-d, %Y" if sys.platform != "win32" else "%B %#d, %Y")
    except Exception:
        return ""


# ----------------------------------------------------------------------------- drawing helpers
@functools.lru_cache(maxsize=32)
def _font(size: int, bold: bool) -> pygame.font.Font:
    return pygame.font.SysFont("segoeui,helveticaneue,helvetica,arial", size, bold=bold)


class Fonts:
    # Bounded: a full-screen clock surface is several MB, so an unbounded cache grows by GBs over hours.
    @staticmethod
    @functools.lru_cache(maxsize=96)
    def text(s: str, size: int, color=WHITE, bold=True) -> pygame.Surface:
        return _font(size, bold).render(s, True, color)

    def fitted(self, s: str, size: int, max_w: float, color=WHITE, bold=True) -> pygame.Surface:
        """Like text(), shrunk until it fits within max_w pixels (measured without rendering)."""
        w = _font(size, bold).size(s)[0]
        return self.text(s, max(8, int(size * max_w / w)) if w > max_w else size, color, bold)


def blit_shadowed(screen, fonts, s, size, pos, color=WHITE):
    screen.blit(fonts.text(s, size, BLACK), (pos[0] + 2, pos[1] + 3))
    screen.blit(fonts.text(s, size, color), pos)


def corner_masks(r: int) -> list[pygame.Surface]:
    """Black corners outside a quarter circle, blitted over tiles to round them cheaply."""
    m = pygame.Surface((r, r), pygame.SRCALPHA)
    m.fill(BLACK)
    pygame.draw.circle(m, (0, 0, 0, 0), (r, r), r)
    return [m, pygame.transform.flip(m, True, False), pygame.transform.flip(m, False, True), pygame.transform.flip(m, True, True)]


def draw_tile(screen, tile: dict, t: float) -> None:
    """Ken Burns: crop the pre-scaled image for zoom s and pan, then scale it into the frame."""
    x, y, w, h = tile["rect"]
    img, zoom = tile["image"], tile["zoom"]
    s, dx, dy = tile["kb"].at(t)
    cw, ch = img.get_size()
    vw, vh = w * zoom / s, h * zoom / s
    cx, cy = cw / 2 - dx * w * zoom / s, ch / 2 - dy * h * zoom / s
    left = max(0, min(cw - vw, cx - vw / 2))
    top = max(0, min(ch - vh, cy - vh / 2))
    crop = pygame.Rect(int(left), int(top), max(1, min(cw - int(left), round(vw))), max(1, min(ch - int(top), round(vh))))
    sub = img.subsurface(crop)
    screen.blit(sub if crop.size == (w, h) else pygame.transform.smoothscale(sub, (w, h)), (x, y))


def draw_slide(screen, slide: dict, t: float, corners) -> None:
    for tile in slide["tiles"]:
        if tile["backdrop"]:
            screen.blit(tile["backdrop"], (0, 0))
        draw_tile(screen, tile, t)
        if slide["layout"] not in (core.SINGLE, core.SINGLE_PORTRAIT):
            x, y, w, h = tile["rect"]
            r = corners[0].get_width()
            for m, pos in zip(corners, ((x, y), (x + w - r, y), (x, y + h - r), (x + w - r, y + h - r))):
                screen.blit(m, pos)


def draw_analog(screen, center, radius, now, face=WHITE, hand=BLACK, seconds=True):
    cx, cy = center

    def point(deg, dist):
        a = math.radians(deg - 90)
        return cx + dist * math.cos(a), cy + dist * math.sin(a)

    pygame.draw.circle(screen, face, center, radius)
    for i in range(60):
        major = i % 5 == 0
        pygame.draw.line(screen, hand, point(i * 6, radius * 0.94), point(i * 6, radius * (0.85 if major else 0.9)),
                         max(1, int(radius * (0.022 if major else 0.01))))
    hours = (now.hour % 12 + now.minute / 60) * 30
    minutes = (now.minute + now.second / 60) * 6
    pygame.draw.line(screen, hand, center, point(hours, radius * 0.5), max(2, int(radius * 0.055)))
    pygame.draw.line(screen, hand, center, point(minutes, radius * 0.78), max(2, int(radius * 0.04)))
    if seconds:
        pygame.draw.line(screen, ORANGE, point(now.second * 6 + 180, radius * 0.15), point(now.second * 6, radius * 0.86), max(1, int(radius * 0.014)))
        pygame.draw.circle(screen, ORANGE, center, max(2, int(radius * 0.045)))


def fmt_time(now: dt.datetime, h24: bool) -> tuple[str, str]:
    if h24:
        return now.strftime("%H:%M"), ""
    return f"{(now.hour % 12) or 12}:{now.minute:02d}", "AM" if now.hour < 12 else "PM"


# ----------------------------------------------------------------------------- app
class App:
    def __init__(self, cfg: dict, cfg_error: str | None, windowed: bool):
        self.cfg, self.cfg_error = cfg, cfg_error
        self.stop = threading.Event()
        self.settings_requested = False
        pygame.init()
        sizes = pygame.display.get_desktop_sizes()
        self.display = cfg["display"] if isinstance(cfg["display"], int) and 0 <= cfg["display"] < len(sizes) else 0
        self.windowed = windowed
        self._set_mode()
        pygame.display.set_caption("Daydream")
        self.fonts, self.clock = Fonts(), pygame.time.Clock()
        self.page, self.face, self.stack = 1, 0, 0
        self.night_override = {"on": True, "off": False}.get(cfg["night_mode"])
        self.slide = self.prev = None
        self.slide_at = 0.0
        self.weather: dict | str = "Set weather.city in the config" if not cfg["weather"]["city"] else "Loading…"
        self.last_input, self.drag_x = time.monotonic(), None
        self.feed = Feed(cfg, self.screen.get_size(), self.stop) if cfg["photo_source"] != "none" else None
        if self.feed:
            self.feed.start()
        if cfg["weather"]["city"]:
            threading.Thread(target=self._weather_loop, daemon=True).start()
        self.corners = corner_masks(RADIUS)
        self.layer = pygame.Surface(self.screen.get_size()).convert()

    def _set_mode(self):
        if self.windowed:
            self.screen = pygame.display.set_mode((1280, 720))
        else:
            self.screen = pygame.display.set_mode((0, 0), pygame.FULLSCREEN, display=self.display)

    def _weather_loop(self):
        place = None
        while not self.stop.is_set():
            try:
                place = place or core.geocode(self.cfg["weather"]["city"])
                if not place:
                    self.weather = "City not found"
                    return
                self.weather = {"name": place[0], **core.forecast(place[1], place[2], self.cfg["weather"]["fahrenheit"])}
                self.stop.wait(30 * 60)
            except Exception as e:
                if not isinstance(self.weather, dict):
                    self.weather = f"Weather unavailable ({e.__class__.__name__})"
                self.stop.wait(5 * 60)

    @property
    def night(self) -> bool:
        if self.night_override is not None:
            return self.night_override
        h = dt.datetime.now().hour
        return h >= 22 or h < 6

    # -- loop
    def run(self):
        while True:
            for e in pygame.event.get():
                if not self._handle(e):
                    return
            if self.settings_requested:
                return "settings"
            self._advance()
            self.screen.fill(BLACK)
            getattr(self, "draw_" + PAGES[self.page])()
            if time.monotonic() - self.last_input < CURSOR_HIDE_AFTER or not self.slide and self.page == 1:
                button = self.settings_rect()
                pygame.draw.rect(self.screen, CARD, button, border_radius=8)
                label = self.fonts.text('Settings (S)', 20, WHITE, bold=False)
                self.screen.blit(label, label.get_rect(center=button.center))
            if self.cfg_error:
                blit_shadowed(self.screen, self.fonts, self.cfg_error, 22, (24, self.screen.get_height() - 44), RED)
            if self.night:
                self.screen.fill(NIGHT_TINT, special_flags=pygame.BLEND_MULT)
                self.screen.fill(NIGHT_DIM, special_flags=pygame.BLEND_MULT)
            pygame.mouse.set_visible(time.monotonic() - self.last_input < CURSOR_HIDE_AFTER)
            pygame.display.flip()
            self.clock.tick(FPS)

    def _handle(self, e) -> bool:
        if e.type == pygame.QUIT:
            return False
        if e.type in (pygame.MOUSEMOTION, pygame.MOUSEBUTTONDOWN, pygame.KEYDOWN):
            self.last_input = time.monotonic()
        if e.type == pygame.KEYDOWN:
            if e.key in (pygame.K_ESCAPE, pygame.K_q):
                return False
            if e.key == pygame.K_s:
                self.settings_requested = True
                return True
            if e.key in (pygame.K_LEFT, pygame.K_RIGHT):
                self.page = max(0, min(len(PAGES) - 1, self.page + (1 if e.key == pygame.K_RIGHT else -1)))
            elif e.key in (pygame.K_UP, pygame.K_DOWN):
                step = 1 if e.key == pygame.K_DOWN else -1
                if PAGES[self.page] == "clock":
                    self.face = (self.face + step) % 3
                else:
                    self.stack += step
            elif e.key == pygame.K_n:
                self.night_override = {None: True, True: False, False: None}[self.night_override]
            elif e.key == pygame.K_F11:  # the slide already prepared keeps the old size; the next ones match
                self.windowed = not self.windowed
                self._set_mode()
                self.layer = pygame.Surface(self.screen.get_size()).convert()
                if self.feed:
                    self.feed.size = self.screen.get_size()
        elif e.type == pygame.MOUSEBUTTONDOWN and e.button == 1:
            if self.settings_rect().collidepoint(e.pos):
                self.settings_requested = True
                return True
            self.drag_x = e.pos[0]
        elif e.type == pygame.MOUSEBUTTONUP and e.button == 1 and self.drag_x is not None:
            dx, self.drag_x = e.pos[0] - self.drag_x, None
            if abs(dx) > 80:
                self.page = max(0, min(len(PAGES) - 1, self.page + (-1 if dx > 0 else 1)))
        return True

    def settings_rect(self) -> pygame.Rect:
        return pygame.Rect(self.screen.get_width() - 164, 16, 148, 42)

    def _advance(self):
        """Swap to the feed's prepared slide once the interval has passed."""
        if not self.feed:
            return
        now = time.monotonic()
        if (self.slide is None or now - self.slide_at >= self.cfg["interval_seconds"]) and not self.feed.ready.empty():
            nxt = self.feed.ready.get_nowait()
            for tile in nxt["tiles"]:
                tile["image"] = tile["image"].convert()
                tile["backdrop"] = tile["backdrop"] and tile["backdrop"].convert()
            self.prev, self.prev_at = self.slide, self.slide_at
            self.slide, self.slide_at = nxt, now

    # -- pages
    def _motion(self, started: float) -> float:
        return (time.monotonic() - started) / (self.cfg["interval_seconds"] + 2 * CROSSFADE)

    def draw_photos(self):
        sw, sh = self.screen.get_size()
        if self.slide:
            fade = (time.monotonic() - self.slide_at) / CROSSFADE
            if self.prev and fade < 1:
                draw_slide(self.screen, self.prev, self._motion(self.prev_at), self.corners)
                self.layer.fill(BLACK)
                draw_slide(self.layer, self.slide, self._motion(self.slide_at), self.corners)
                self.layer.set_alpha(int(255 * fade))
                self.screen.blit(self.layer, (0, 0))
            else:
                draw_slide(self.screen, self.slide, self._motion(self.slide_at), self.corners)
            if self.slide["caption"]:  # sanitised by core.clean_text / _exif_date
                blit_shadowed(self.screen, self.fonts, self.slide["caption"], max(16, sh // 50), (40, sh - sh // 16), WHITE)
        else:
            msg = self.feed.status if self.feed else "Set photo_source in the config to show photos"
            title = self.fonts.text("Daydream", sh // 14)
            self.screen.blit(title, ((sw - title.get_width()) // 2, sh // 2 - title.get_height()))
            body = self.fonts.text(msg or "Loading photos…", max(16, sh // 40), GREY, bold=False)
            self.screen.blit(body, ((sw - body.get_width()) // 2, sh // 2 + 10))
        now = dt.datetime.now()
        hm, period = fmt_time(now, self.cfg["clock_24h"])
        big = sh // 7
        blit_shadowed(self.screen, self.fonts, now.strftime("%A, %B ") + str(now.day), sh // 40, (40, sh // 30))
        blit_shadowed(self.screen, self.fonts, hm, big, (36, sh // 30 + sh // 30))
        if period:
            w = self.fonts.text(hm, big).get_width()
            blit_shadowed(self.screen, self.fonts, period, sh // 30, (46 + w, sh // 30 + big - sh // 40))

    def draw_clock(self):
        sw, sh = self.screen.get_size()
        now = dt.datetime.now()
        hm, period = fmt_time(now, self.cfg["clock_24h"])
        if self.face == 0:
            extra = f"{now.second:02d}" if self.cfg["show_seconds"] else period
            size = int(sh * 0.7)
            t = self.fonts.text(hm, size)
            e = self.fonts.text(extra, size // 6, ORANGE if self.cfg["show_seconds"] else GREY) if extra else None
            total = t.get_width() + (e.get_width() + size // 20 if e else 0)
            if total > sw * 0.94:  # shrink the whole group to fit the width
                size = int(size * sw * 0.94 / total)
                t = self.fonts.text(hm, size)
                e = e and self.fonts.text(extra, size // 6, ORANGE if self.cfg["show_seconds"] else GREY)
                total = t.get_width() + (e.get_width() + size // 20 if e else 0)
            x, y = (sw - total) // 2, (sh - t.get_height()) // 2
            self.screen.blit(self.fonts.text(now.strftime("%a %d %b").upper(), size // 9, ORANGE), (x + size // 20, y - size // 12))
            self.screen.blit(t, (x, y))
            if e:
                self.screen.blit(e, (x + t.get_width() + size // 20, y + t.get_height() * 0.62))
        elif self.face == 1:
            draw_analog(self.screen, (sw // 2, sh // 2), int(sh * 0.45), now, face=(17, 17, 20), hand=WHITE, seconds=True)
        else:
            digit = int(sh * 0.42)
            h = f"{int(hm.split(':')[0]):02d}"
            self.screen.blit(self.fonts.text(h, digit, BLUE, bold=False), (sw // 12, sh * 0.04))
            self.screen.blit(self.fonts.text(now.strftime("%M"), digit, WHITE, bold=False), (sw // 12, sh * 0.48))
            for i, (s, c) in enumerate(((now.strftime("%A").upper(), BLUE), (now.strftime("%B ") + str(now.day), WHITE), (period, GREY))):
                if s:
                    t = self.fonts.text(s, sh // 14, c, bold=i == 0)
                    self.screen.blit(t, (sw - sw // 12 - t.get_width(), sh * 0.38 + i * sh // 11))

    def draw_widgets(self):
        sw, sh = self.screen.get_size()
        pad = min(sw, sh) // 24
        bat = battery()
        left = ["analog"] + (["battery"] if bat else [])
        right = ["calendar", "weather"]
        for i, stack in enumerate((left, right)):
            if sh > sw:  # portrait monitor: stack the two cards vertically
                ch = (sh - 3 * pad) // 2
                rect = pygame.Rect(pad, pad + i * (ch + pad), sw - 2 * pad, ch)
            else:
                cw = (sw - 3 * pad) // 2
                rect = pygame.Rect(pad + i * (cw + pad), pad, cw, sh - 2 * pad)
            pygame.draw.rect(self.screen, CARD, rect, border_radius=min(sw, sh) // 18)
            getattr(self, "widget_" + stack[self.stack % len(stack)])(rect, bat)

    def widget_analog(self, r, _):
        draw_analog(self.screen, r.center, int(min(r.w, r.h) * 0.42), dt.datetime.now(), seconds=True)

    def widget_battery(self, r, bat):
        pct, charging = bat
        color = GREEN if charging else RED if pct <= 20 else WHITE
        rad = int(min(r.w, r.h) * 0.3)
        box = pygame.Rect(0, 0, rad * 2, rad * 2)
        box.center = (r.centerx, r.centery - r.h // 12)
        pygame.draw.circle(self.screen, (60, 60, 64), box.center, rad, max(4, rad // 6))
        pygame.draw.arc(self.screen, color, box, math.pi / 2 - 2 * math.pi * pct / 100, math.pi / 2, max(4, rad // 6))
        t = self.fonts.text(f"{pct}%" + (" · charging" if charging else ""), r.h // 9)
        self.screen.blit(t, (r.centerx - t.get_width() // 2, box.bottom + r.h // 20))

    def widget_calendar(self, r, _):
        today = dt.date.today()
        p = r.h // 12
        self.screen.blit(self.fonts.fitted(today.strftime("%A").upper(), r.h // 12, r.w * 0.46 - 2 * p, RED), (r.x + p, r.y + p))
        self.screen.blit(self.fonts.text(str(today.day), int(r.h * 0.42), WHITE, bold=False), (r.x + p, r.y + p + r.h // 9))
        gx, gy, cell = r.x + int(r.w * 0.47), r.y + p, (r.w * 0.5) / 7
        fs = int(min(cell * 0.62, r.h / 16))
        self.screen.blit(self.fonts.text(today.strftime("%B").upper(), fs, RED), (gx, gy))
        for i, d in enumerate("SMTWTFS"):
            t = self.fonts.text(d, fs, GREY)
            self.screen.blit(t, (gx + i * cell + (cell - t.get_width()) / 2, gy + fs * 1.6))
        for row, week in enumerate(calendar.Calendar(firstweekday=6).monthdayscalendar(today.year, today.month)):
            for col, day in enumerate(week):
                if not day:
                    continue
                cx, cy = gx + col * cell + cell / 2, gy + fs * 3.4 + row * fs * 1.6
                if day == today.day:
                    pygame.draw.circle(self.screen, RED, (cx, cy + fs * 0.1), fs * 0.8)
                t = self.fonts.text(str(day), fs, WHITE, bold=day == today.day)
                self.screen.blit(t, (cx - t.get_width() / 2, cy - t.get_height() / 2))

    def widget_weather(self, r, _):
        p = r.h // 10
        w = self.weather
        if not isinstance(w, dict):
            self.screen.blit(self.fonts.text("WEATHER", r.h // 14, BLUE), (r.x + p, r.y + p))
            self.screen.blit(self.fonts.fitted(w, r.h // 16, r.w - 2 * p, GREY, bold=False), (r.x + p, r.y + p + r.h // 9))
            return
        self.screen.blit(self.fonts.fitted(w["name"], r.h // 13, r.w - 2 * p), (r.x + p, r.y + p))
        self.screen.blit(self.fonts.text(f"{w['temp']}°", int(r.h * 0.36), WHITE, bold=False), (r.x + p, r.y + p + r.h // 10))
        self.screen.blit(self.fonts.text(w["label"], r.h // 13), (r.x + p, r.bottom - p - r.h // 6))
        if w["high"] is not None:
            self.screen.blit(self.fonts.text(f"H:{w['high']}°  L:{w['low']}°", r.h // 15, GREY, bold=False), (r.x + p, r.bottom - p - r.h // 14))


def main(argv: list[str]) -> None:
    path = argv[argv.index("--config") + 1] if "--config" in argv else core.config_path()
    cfg, error = core.load_config(path)
    core.harden_pillow()
    win32_setup()
    try:
        windowed = "--windowed" in argv
        page, face, stack = 1, 0, 0
        while True:
            app = App(cfg, error, windowed=windowed)
            app.page, app.face, app.stack = page, face, stack
            try:
                action = app.run()
            finally:
                app.stop.set()
            if action != "settings":
                break
            windowed = app.windowed
            page, face, stack = app.page, app.face, app.stack
            if not windowed:
                app.windowed = True
                app._set_mode()
            pygame.mouse.set_visible(True)
            import settings_ui
            settings_ui.show_settings(cfg, path, error, len(pygame.display.get_desktop_sizes()))
            cfg, error = core.load_config(path)
    finally:
        if sys.platform == "win32":
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)  # let the display sleep again
        pygame.quit()
        Fonts.text.cache_clear()  # cached fonts/surfaces die with pygame.quit()
        _font.cache_clear()


if __name__ == "__main__":
    main(sys.argv[1:])
