"""Daydream desktop core: layout planning, Ken Burns, photo sources, Immich, weather, config.

Pure logic with no pygame imports, so it is unit-testable (see test_core.py). Ported from the
Android app's SlidePlanner.kt, KenBurns.kt, Slideshow.kt and ImmichServer.kt.
"""
from __future__ import annotations

import io
import ipaddress
import json
import math
import os
import random
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

# --------------------------------------------------------------------------- layout planning
TALL_MAX = 0.5  # narrower than this (phone screenshots) is never squeezed into a tile
WIDE_MIN = 1.1  # at or above this a photo fills a landscape screen on its own
TRIO_MIN_VIEWPORT = 1.9  # three tiles only on screens at least this wide
FIT_TOLERANCE = 0.12  # layouts this close to the best fit are picked between at random
LOOKAHEAD = 8

SINGLE, SINGLE_PORTRAIT, DUO, TRIO, MOSAIC = "single", "single_portrait", "duo", "trio", "mosaic"
CAPACITY = {SINGLE: 1, SINGLE_PORTRAIT: 1, DUO: 2, TRIO: 3, MOSAIC: 3}


def _shape(aspect: float) -> str:
    return "tall" if aspect < TALL_MAX else "narrow" if aspect < WIDE_MIN else "wide"


def _norm(aspect: float, viewport: float) -> float:
    """Plan in landscape terms: a portrait viewport is the same problem with axes swapped."""
    return 1 / aspect if viewport < 1 else aspect


def _fit(layout: str, aspects: list[float], viewport: float) -> float:
    """Visible fraction of the worst-cropped tile (1 = nothing cropped)."""
    tiles = {DUO: [viewport / 2] * 2, TRIO: [viewport / 3] * 3, MOSAIC: [viewport / 2, viewport, viewport]}[layout]
    return min(min(a / t, t / a) for a, t in zip(aspects, tiles))


def plan(aspects: list[float | None], viewport: float, collages: bool = True, choice: int = 0) -> tuple[str, list[int]]:
    """Pick a layout for aspects[0] plus partners from aspects[1:]; returns (layout, indices)."""
    if not aspects or not aspects[0] or aspects[0] <= 0 or viewport <= 0:
        raise ValueError("first aspect and viewport must be positive")
    vp = 1 / viewport if viewport < 1 else viewport
    norm = [_norm(a, viewport) if a and a > 0 else None for a in aspects]
    first = _shape(norm[0])
    if first == "wide":
        return SINGLE, [0]
    if not collages or first == "tall":
        return SINGLE_PORTRAIT, [0]
    narrow = [i for i in range(1, len(norm)) if norm[i] and _shape(norm[i]) == "narrow"]
    wide = [i for i in range(1, len(norm)) if norm[i] and _shape(norm[i]) == "wide"]
    options = []
    if vp >= TRIO_MIN_VIEWPORT and len(narrow) >= 2:
        options.append((TRIO, [0] + narrow[:2]))
    if narrow:
        options.append((DUO, [0, narrow[0]]))
    if len(wide) >= 2:
        options.append((MOSAIC, [0] + wide[:2]))
    if not options:
        return SINGLE_PORTRAIT, [0]
    fits = [_fit(layout, [norm[i] for i in idx], vp) for layout, idx in options]
    good = [o for o, f in zip(options, fits) if f >= max(fits) - FIT_TOLERANCE]
    return good[choice % len(good)]


@dataclass
class Item:
    """A photo in the queue: a unique id, its displayed aspect, and how to fetch its bytes."""
    id: str
    aspect: float
    load: object  # () -> bytes | str (file path)
    caption: str = ""


def next_slide(queue: list[Item], last_ids: set[str], viewport: float, collages: bool, choice: int) -> tuple[str, list[Item]]:
    """Take the next slide's photos out of [queue] (mutated). Mirrors Slideshow.kt's rules:
    don't start with a photo from the slide on screen, prefer fresh partners, never repeat a set."""
    if not queue:
        raise ValueError("empty queue")
    first = queue.pop(next((i for i, it in enumerate(queue) if it.id not in last_ids), 0))
    seen, fresh, recent = {first.id}, [], []
    for it in queue:
        if it.id not in seen:
            seen.add(it.id)
            (recent if it.id in last_ids else fresh).append(it)
    candidates = (fresh + recent)[:LOOKAHEAD]
    layout, idx = plan([first.aspect] + [c.aspect for c in candidates], viewport, collages, choice)
    partners = [candidates[i - 1] for i in idx[1:]]
    if partners and {first.id, *(p.id for p in partners)} == last_ids:  # tiny library
        layout, _ = plan([first.aspect], viewport, collages)
        partners = []
    for p in partners:
        queue.remove(p)
    return layout, [first] + partners


# --------------------------------------------------------------------------- Ken Burns
MIN_ZOOM, MAX_ZOOM = 1.02, 1.18


@dataclass(frozen=True)
class KenBurns:
    """Pan-and-zoom; offsets in [-1, 1] are fractions of the zoom overflow, so edges never show."""
    s0: float = 1.0
    s1: float = 1.0
    x0: float = 0.0
    y0: float = 0.0
    x1: float = 0.0
    y1: float = 0.0

    @staticmethod
    def random(seed: str, intensity: float = 1.0) -> "KenBurns":
        k = max(0.0, min(1.0, intensity))
        if k == 0:
            return KenBurns()
        r = random.Random(seed)
        lo, hi = 1 + (MIN_ZOOM - 1) * k, 1 + (MAX_ZOOM - 1) * k
        s0, s1 = (lo, hi) if r.random() < 0.5 else (hi, lo)
        return KenBurns(s0, s1, *(r.uniform(-1, 1) for _ in range(4)))

    def at(self, t: float) -> tuple[float, float, float]:
        """(scale, dx, dy) at progress t; dx/dy are fractions of the frame size."""
        t = max(0.0, min(1.0, t))
        s = self.s0 + (self.s1 - self.s0) * t
        over = (s - 1) / 2
        return s, (self.x0 + (self.x1 - self.x0) * t) * over, (self.y0 + (self.y1 - self.y0) * t) * over


# --------------------------------------------------------------------------- image metadata
def display_aspect(width: int, height: int, orientation: int | None) -> float | None:
    """Aspect as displayed; EXIF orientations 5-8 are stored rotated 90 degrees."""
    if not width or not height or width <= 0 or height <= 0:
        return None
    return height / width if orientation in (5, 6, 7, 8) else width / height


MAX_PIXELS = 50_000_000  # ~50 MP: anything bigger is refused before decoding (decompression bombs)


def harden_pillow() -> None:
    import warnings

    from PIL import Image

    Image.MAX_IMAGE_PIXELS = MAX_PIXELS  # past this Pillow warns; we make the warning an error
    warnings.simplefilter("error", Image.DecompressionBombWarning)


def image_aspect(src) -> float | None:
    """Aspect from the image header only (no full decode). src: path or bytes."""
    from PIL import Image  # local import keeps the planner tests free of Pillow

    try:
        with Image.open(io.BytesIO(src) if isinstance(src, bytes) else src) as im:
            if im.width * im.height > MAX_PIXELS:
                return None
            return display_aspect(im.width, im.height, im.getexif().get(0x0112))
    except Exception:
        return None


IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def scan_folders(folders: list[str]) -> list[str]:
    paths = []
    for folder in folders:
        for root, _, files in os.walk(os.path.expanduser(os.path.expandvars(folder))):
            paths += [os.path.join(root, f) for f in files if os.path.splitext(f)[1].lower() in IMAGE_EXTS]
    return paths


# --------------------------------------------------------------------------- Immich
MAX_BODY = 32 * 1024 * 1024
API_KEY_HEADER = "x-api-key"
_ID = re.compile(r"^[A-Za-z0-9-]{1,64}$")
_CGNAT = ipaddress.ip_network("100.64.0.0/10")


class ImmichError(Exception):
    pass


def normalize_base_url(text: str) -> str | None:
    """'photos.example.com' -> https, '192.168.1.10:2283' -> http, strips a trailing /api."""
    text = (text or "").strip()
    if not text or any(c.isspace() for c in text):
        return None
    if "://" not in text:
        host = urllib.parse.urlsplit("//" + text).hostname or ""
        text = ("http://" if is_local_host(host) else "https://") + text
    try:
        u = urllib.parse.urlsplit(text)
        u.port  # raises on a bad port
    except ValueError:
        return None
    if u.scheme not in ("http", "https") or not u.hostname:
        return None
    parts = [p for p in u.path.split("/") if p]
    if parts and parts[-1].lower() == "api":
        parts.pop()
    path = "/" + "/".join(parts) + ("/" if parts else "")
    return urllib.parse.urlunsplit((u.scheme, u.netloc, path, "", ""))


def is_local_host(host: str) -> bool:
    h = (host or "").lower().strip("[]")
    if h == "localhost" or h.endswith((".local", ".lan", ".home.arpa")):
        return True
    try:
        ip = ipaddress.ip_address(h)
    except ValueError:
        return bool(h) and "." not in h  # single-label names only resolve on the LAN
    return ip.is_private or ip.is_loopback or ip.is_link_local or (ip.version == 4 and ip in _CGNAT)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """urllib forwards custom headers (the API key) on redirects, so refuse them outright."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        raise ImmichError("Server redirected the request — use the final URL (e.g. https://…)")


_opener = urllib.request.build_opener(_NoRedirect)


def _read(req: urllib.request.Request, timeout: float = 30) -> bytes:
    with _opener.open(req, timeout=timeout) as resp:
        body = resp.read(MAX_BODY + 1)
    if len(body) > MAX_BODY:
        raise ImmichError("Response too large")
    return body


@dataclass
class Immich:
    base: str
    key: str
    allow_insecure_http: bool = False

    @staticmethod
    def from_config(cfg: dict) -> "Immich":
        base = normalize_base_url(cfg.get("url", ""))
        key = (cfg.get("api_key") or "").strip()
        if not base:
            raise ImmichError("Set immich.url in the config")
        if not key or any(not (0x21 <= ord(c) <= 0x7E) for c in key):
            raise ImmichError("Set a valid immich.api_key in the config")
        server = Immich(base, key, bool(cfg.get("allow_insecure_http")))
        host = urllib.parse.urlsplit(base).hostname or ""
        if base.startswith("http://") and not is_local_host(host) and not server.allow_insecure_http:
            raise ImmichError(f"{host} uses plain HTTP over the internet; use HTTPS or set allow_insecure_http")
        return server

    def _call(self, path: str, body: dict | None = None, raw: bool = False):
        req = urllib.request.Request(
            self.base + "api/" + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={API_KEY_HEADER: self.key, "Accept": "application/json", "Content-Type": "application/json"},
            method="POST" if body is not None else "GET",
        )
        try:
            data = _read(req)
        except urllib.error.HTTPError as e:
            e.close()
            raise ImmichError({401: "Invalid API key", 403: "API key is missing a permission (asset.read, asset.view, album.read)",
                               404: "Not found — check the server URL"}.get(e.code, f"Immich request failed ({e.code})")) from e
        except (urllib.error.URLError, OSError) as e:
            raise ImmichError(f"Can't reach server: {getattr(e, 'reason', e)}") from e
        if raw:
            return data
        try:
            return json.loads(data)
        except ValueError as e:
            raise ImmichError("Unexpected response — is this an Immich URL?") from e

    def version(self) -> tuple[int, int, int]:
        v = self._call("server/version")
        return v.get("major", 0), v.get("minor", 0), v.get("patch", 0)

    def random(self, count: int, favorites: bool = False, album: str | None = None) -> list[dict]:
        body = {"size": count, "type": "IMAGE", "withExif": True}
        if favorites:
            body["isFavorite"] = True
        if album:
            body["albumIds"] = [_check_id(album)]
        return [a for a in self._call("search/random", body) if _usable(a)]

    def album_assets(self, album: str) -> list[dict]:
        return [a for a in self._call("albums/" + _check_id(album)).get("assets", []) if _usable(a)]

    def preview(self, asset_id: str) -> bytes:
        return self._call(f"assets/{_check_id(asset_id)}/thumbnail?size=preview", raw=True)


def _check_id(value: str) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise ImmichError("Invalid id")
    return value


def _usable(asset: dict) -> bool:
    return (isinstance(asset, dict) and asset.get("type", "IMAGE") == "IMAGE" and not asset.get("isTrashed")
            and isinstance(asset.get("id"), str) and bool(_ID.fullmatch(asset["id"])))


def clean_text(value, limit: int = 120) -> str:
    """Server/EXIF text is untrusted: drop control characters and cap the length before drawing."""
    return "".join(c for c in str(value or "") if c.isprintable())[:limit]


def immich_caption(asset: dict) -> str:
    exif = asset.get("exifInfo") if isinstance(asset.get("exifInfo"), dict) else {}
    place = ", ".join(clean_text(p, 40) for p in (exif.get("city"), exif.get("country") or exif.get("state")) if p)
    date = clean_text(exif.get("dateTimeOriginal") or asset.get("localDateTime"), 10)
    return "  ·  ".join(p for p in (place, date) if p)


# --------------------------------------------------------------------------- weather (Open-Meteo)
WMO = [((0,), "Clear"), ((1, 2), "Partly Cloudy"), ((3,), "Cloudy"), ((45, 48), "Fog"),
       ((51, 53, 55, 56, 57), "Drizzle"), ((61, 63, 65, 66, 67, 80, 81, 82), "Rain"),
       ((71, 73, 75, 77, 85, 86), "Snow"), ((95, 96, 99), "Thunderstorm")]


def weather_label(code: int) -> str:
    return next((label for codes, label in WMO if code in codes), "—")


def _get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=20) as resp:
        return json.loads(resp.read(1024 * 1024))


def geocode(city: str) -> tuple[str, float, float] | None:
    q = urllib.parse.urlencode({"name": city.strip(), "count": 1, "format": "json"})
    r = (_get_json("https://geocoding-api.open-meteo.com/v1/search?" + q).get("results") or [None])[0]
    return (r["name"], r["latitude"], r["longitude"]) if r else None


def forecast(lat: float, lon: float, fahrenheit: bool) -> dict:
    q = {"latitude": lat, "longitude": lon, "current": "temperature_2m,weather_code,is_day",
         "daily": "temperature_2m_max,temperature_2m_min", "timezone": "auto", "forecast_days": 1}
    if fahrenheit:
        q["temperature_unit"] = "fahrenheit"
    d = _get_json("https://api.open-meteo.com/v1/forecast?" + urllib.parse.urlencode(q))
    cur, daily = d["current"], d.get("daily", {})
    return {"temp": round(cur["temperature_2m"]), "label": weather_label(cur["weather_code"]),
            "high": round(daily["temperature_2m_max"][0]) if daily.get("temperature_2m_max") else None,
            "low": round(daily["temperature_2m_min"][0]) if daily.get("temperature_2m_min") else None}


# --------------------------------------------------------------------------- config
DEFAULTS = {
    "photo_source": "local",  # local | immich | none
    "folders": ["~/Pictures"],
    "immich": {"url": "", "api_key": "", "mode": "random", "albums": [], "allow_insecure_http": False},
    "interval_seconds": 15,
    "layout": "collages",  # collages | single
    "ken_burns": True,
    "clock_24h": False,
    "show_seconds": False,
    "night_mode": "auto",  # auto (22:00-06:00) | on | off
    "weather": {"city": "", "fahrenheit": False},
    "display": 0,
}


def _same_type(value, default) -> bool:
    """Type check for config values; numbers accept int or float, but never bool."""
    if isinstance(default, bool):
        return isinstance(value, bool)
    if isinstance(default, (int, float)):
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
    return isinstance(value, type(default))


CHOICES = {"photo_source": {"local", "immich", "none"}, "layout": {"collages", "single"}, "night_mode": {"auto", "on", "off"}}


def config_path() -> str:
    base = os.environ.get("APPDATA") if sys.platform == "win32" else os.path.expanduser("~/.config")
    return os.path.join(base or os.path.expanduser("~"), "Daydream", "config.json")


def load_config(path: str) -> tuple[dict, str | None]:
    """Returns (config merged over defaults, error message or None). Never overwrites a bad file."""
    cfg = json.loads(json.dumps(DEFAULTS))
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(DEFAULTS, f, indent=2)
        if sys.platform != "win32":
            os.chmod(path, 0o600)  # holds the Immich API key; %APPDATA% is already per-user on Windows
        return cfg, None
    try:
        with open(path, encoding="utf-8") as f:
            user = json.load(f)
        if not isinstance(user, dict):
            raise ValueError("top level must be an object")
    except (OSError, ValueError) as e:
        return cfg, f"Config error in {path}: {e}"
    for k, v in user.items():
        cfg[k] = {**cfg[k], **v} if isinstance(cfg.get(k), dict) and isinstance(v, dict) else v
    problems = [k for k in DEFAULTS if not _same_type(cfg[k], DEFAULTS[k])
                or (isinstance(DEFAULTS[k], dict) and any(not _same_type(cfg[k].get(n), v) for n, v in DEFAULTS[k].items()))]
    if "folders" not in problems and not all(isinstance(f, str) for f in cfg["folders"]):
        problems.append("folders")
    problems += [k for k, allowed in CHOICES.items() if k not in problems and cfg[k] not in allowed]
    for k in problems:  # fall back per key so one typo doesn't discard the whole file
        cfg[k] = json.loads(json.dumps(DEFAULTS[k]))
    cfg["interval_seconds"] = max(5, min(300, round(cfg["interval_seconds"])))
    return cfg, f"Config error: invalid {', '.join(dict.fromkeys(problems))} (using defaults)" if problems else None
