"""Run: python -m unittest test_core   (from the windows/ folder; stdlib only)."""
import http.server
import json
import os
import tempfile
import threading
import unittest

import core
from core import DUO, MOSAIC, SINGLE, SINGLE_PORTRAIT, TRIO, Item, KenBurns

P, SQ, L = 0.75, 1.0, 1.5
PHONE, TABLET = 20 / 9, 16 / 10


class PlannerTest(unittest.TestCase):
    def layout(self, *aspects, vp=PHONE, choice=0, collages=True):
        return core.plan(list(aspects), vp, collages, choice)

    def test_rules(self):
        self.assertEqual(self.layout(L, P, P), (SINGLE, [0]))
        self.assertEqual(self.layout(4.0, P)[0], SINGLE)
        self.assertEqual(self.layout(P, L)[0], SINGLE_PORTRAIT)
        self.assertEqual(self.layout(P, L, P, vp=TABLET), (DUO, [0, 2]))
        self.assertEqual(self.layout(P, SQ, L, P), (TRIO, [0, 1, 3]))
        self.assertEqual(self.layout(P, P, P, vp=TABLET)[0], DUO)
        self.assertEqual(self.layout(P, L, L), (MOSAIC, [0, 1, 2]))
        self.assertEqual(self.layout(P, None, P, vp=TABLET), (DUO, [0, 2]))
        self.assertEqual(self.layout(0.45, P, P)[0], SINGLE_PORTRAIT)
        self.assertEqual(self.layout(P, 0.4)[0], SINGLE_PORTRAIT)
        self.assertEqual(self.layout(P, P, P, collages=False)[0], SINGLE_PORTRAIT)

    def test_fit_ranking(self):
        both = (P, P, P, L, L)
        self.assertEqual({self.layout(*both, choice=c)[0] for c in range(6)}, {TRIO})  # wide phone: trio fits best
        self.assertEqual([self.layout(P, P, L, L, vp=TABLET, choice=c)[0] for c in range(4)], [DUO, MOSAIC, DUO, MOSAIC])

    def test_portrait_viewport_swaps_axes(self):
        self.assertEqual(self.layout(L, L, vp=9 / 20), (DUO, [0, 1]))
        self.assertEqual(self.layout(P, L, vp=9 / 20)[0], SINGLE)

    def test_rejects_unknown_first(self):
        with self.assertRaises(ValueError):
            core.plan([None, P], PHONE)


class NextSlideTest(unittest.TestCase):
    def q(self, *specs):
        return [Item(i, a, None) for i, a in specs]

    def test_pairs_and_keeps_order(self):
        queue = self.q(("p1", P), ("l1", L), ("p2", P), ("l2", L))
        layout, items = core.next_slide(queue, set(), TABLET, True, 0)
        self.assertEqual((layout, [i.id for i in items]), (DUO, ["p1", "p2"]))
        self.assertEqual([i.id for i in queue], ["l1", "l2"])

    def test_skips_photo_on_screen_and_prefers_fresh_partners(self):
        queue = self.q(("a", P), ("b", P), ("c", P), ("d", P))
        _, items = core.next_slide(queue, {"a", "b"}, TABLET, True, 0)
        self.assertEqual([i.id for i in items], ["c", "d"])

    def test_tiny_library_never_repeats_a_set(self):
        layout, items = core.next_slide(self.q(("a", P), ("b", P)), {"a", "b"}, TABLET, True, 0)
        self.assertEqual((layout, [i.id for i in items]), (SINGLE_PORTRAIT, ["a"]))

    def test_no_duplicate_in_slide(self):
        _, items = core.next_slide(self.q(("p", P), ("p", P), ("q", P)), set(), TABLET, True, 0)
        self.assertEqual([i.id for i in items], ["p", "q"])


class KenBurnsTest(unittest.TestCase):
    def test_deterministic_and_bounded(self):
        self.assertEqual(KenBurns.random("x"), KenBurns.random("x"))
        for seed in range(300):
            kb = KenBurns.random(str(seed))
            for step in range(11):
                s, dx, dy = kb.at(step / 10)
                over = (s - 1) / 2
                self.assertTrue(1 <= s <= core.MAX_ZOOM + 1e-9)
                self.assertLessEqual(abs(dx), over + 1e-9)
                self.assertLessEqual(abs(dy), over + 1e-9)
        self.assertEqual(KenBurns.random("x", 0).at(0.5), (1.0, 0.0, 0.0))


class UrlTest(unittest.TestCase):
    def test_normalize(self):
        n = core.normalize_base_url
        self.assertEqual(n("photos.example.com"), "https://photos.example.com/")
        self.assertEqual(n("192.168.1.10:2283"), "http://192.168.1.10:2283/")
        self.assertEqual(n("nas:2283"), "http://nas:2283/")
        self.assertEqual(n(" https://host/immich/api/ "), "https://host/immich/")
        self.assertEqual(n("https://host/?x=1#f"), "https://host/")
        for bad in ("", "not a url", "ftp://host", "http://host:99999"):
            self.assertIsNone(n(bad), bad)

    def test_local_host(self):
        for h in ("localhost", "10.1.2.3", "192.168.1.1", "172.16.0.1", "100.64.0.1", "nas", "box.lan", "::1", "fd12::1", "fe80::1"):
            self.assertTrue(core.is_local_host(h), h)
        for h in ("8.8.8.8", "example.com", "10.0.0.5.nip.io", "2606:4700:4700::1111", ""):
            self.assertFalse(core.is_local_host(h), h)

    def test_ids_and_captions_are_sanitised(self):
        with self.assertRaises(core.ImmichError):
            core._check_id("abc\n")
        self.assertFalse(core._usable({"id": "abc\n", "type": "IMAGE"}))
        self.assertFalse(core._usable({"id": 123, "type": "IMAGE"}))
        self.assertFalse(core._usable("not a dict"))
        cap = core.immich_caption({"exifInfo": {"city": "Ma\x00nila" + "x" * 200, "country": "PH"}, "localDateTime": "2024-01-02T03:04"})
        self.assertNotIn("\x00", cap)
        self.assertLess(len(cap), 80)
        self.assertTrue(cap.endswith("2024-01-02"))

    def test_display_aspect(self):
        self.assertEqual(core.display_aspect(4000, 3000, 6), 0.75)
        self.assertEqual(core.display_aspect(4000, 3000, 1), 4000 / 3000)
        self.assertIsNone(core.display_aspect(0, 10, None))


class PillowTest(unittest.TestCase):
    def test_bomb_limit_matches_max_pixels(self):
        from PIL import Image
        core.harden_pillow()
        self.assertEqual(Image.MAX_IMAGE_PIXELS, core.MAX_PIXELS)  # 48 MP phone photos must still load


class ImmichHttpTest(unittest.TestCase):
    """Real HTTP against a local server: the security-relevant paths."""

    @classmethod
    def setUpClass(cls):
        cls.seen = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self):
                cls.seen.append((self.path, self.headers.get("x-api-key")))
                if self.path.endswith("/redirect/api/server/version"):
                    self.send_response(302)
                    self.send_header("Location", "http://evil.example/steal")
                    self.end_headers()
                    return
                if self.headers.get("x-api-key") != "good":
                    self.send_response(401)
                    self.end_headers()
                    return
                body = b"x" * 2048 if "big" in self.path else json.dumps(
                    [{"id": "a1", "type": "IMAGE"}, {"id": "v1", "type": "VIDEO"}, {"id": "../x", "type": "IMAGE"}]
                ).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            do_GET = do_POST = _reply

        cls.httpd = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_port}/"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def server(self, key="good", path=""):
        return core.Immich.from_config({"url": self.base + path, "api_key": key})

    def test_random_sends_key_and_filters_assets(self):
        assets = self.server().random(5)
        self.assertEqual([a["id"] for a in assets], ["a1"])  # video and unsafe id dropped
        self.assertEqual(self.seen[-1], ("/api/search/random", "good"))

    def test_bad_key(self):
        with self.assertRaisesRegex(core.ImmichError, "Invalid API key"):
            self.server(key="bad").random(1)

    def test_redirect_is_refused(self):
        with self.assertRaisesRegex(core.ImmichError, "redirected"):
            self.server(path="redirect/").version()

    def test_response_size_cap(self):
        old, core.MAX_BODY = core.MAX_BODY, 1024
        try:
            with self.assertRaisesRegex(core.ImmichError, "too large"):
                self.server(path="big/").random(1)
        finally:
            core.MAX_BODY = old

    def test_config_validation(self):
        with self.assertRaisesRegex(core.ImmichError, "plain HTTP"):
            core.Immich.from_config({"url": "http://photos.example.com", "api_key": "k"})
        core.Immich.from_config({"url": "http://photos.example.com", "api_key": "k", "allow_insecure_http": True})
        with self.assertRaisesRegex(core.ImmichError, "api_key"):
            core.Immich.from_config({"url": "https://h", "api_key": "a b"})
        with self.assertRaisesRegex(core.ImmichError, "Invalid id"):
            self.server().preview("../admin")


class ConfigTest(unittest.TestCase):
    def test_create_merge_and_bad_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "sub", "config.json")
            cfg, err = core.load_config(path)
            self.assertIsNone(err)
            self.assertTrue(os.path.exists(path))
            with open(path, "w") as f:
                json.dump({"interval_seconds": 1, "immich": {"url": "x"}}, f)
            cfg, err = core.load_config(path)
            self.assertEqual((cfg["interval_seconds"], cfg["immich"]["url"], cfg["immich"]["mode"]), (5, "x", "random"))
            with open(path, "w") as f:
                f.write("{broken")
            cfg, err = core.load_config(path)
            self.assertIn("Config error", err)
            self.assertEqual(cfg["layout"], "collages")
            with open(path) as f:
                self.assertEqual(f.read(), "{broken")  # never overwritten

    def test_wrong_types_fall_back_per_key(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "config.json")
            with open(path, "w") as f:
                json.dump({"folders": "D:/Photos", "night_mode": "sometimes", "weather": "Manila",
                           "interval_seconds": "fast", "layout": "single"}, f)
            cfg, err = core.load_config(path)
            self.assertEqual(cfg["folders"], core.DEFAULTS["folders"])  # a string would walk the drive root
            self.assertEqual((cfg["night_mode"], cfg["weather"], cfg["interval_seconds"]), ("auto", core.DEFAULTS["weather"], 15))
            self.assertEqual(cfg["layout"], "single")  # valid keys survive
            for k in ("folders", "night_mode", "weather", "interval_seconds"):
                self.assertIn(k, err)
            for bad in ({"layout": ["x"]}, {"photo_source": {}}, {"folders": 5}, {"folders": None}, {"interval_seconds": True}, {"interval_seconds": float("nan")}, {"interval_seconds": float("inf")}):
                with open(path, "w") as f:
                    json.dump(bad, f)
                cfg, err = core.load_config(path)  # must not raise
                self.assertIn("invalid", err, bad)
            with open(path, "w") as f:
                json.dump({"interval_seconds": 7.5, "display": 1}, f)
            cfg, err = core.load_config(path)
            self.assertIsNone(err)
            self.assertEqual((cfg["interval_seconds"], cfg["display"]), (8, 1))


if __name__ == "__main__":
    unittest.main()
