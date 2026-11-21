"""Run: python3 -m unittest -v bridge/test_baseline_bridge.py  (synthetic data only)."""
import gzip
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(__file__))
import baseline_bridge as b  # noqa: E402

TOKEN = "t" * 32

LONG = {"data": {
    "daily_metrics": [
        {"date": "2026-01-02", "metric": "steps", "value": 9000.0, "source_scope": "user_fused"},
        {"date": "2026-01-02", "metric": "steps", "value": 8100.0, "source_scope": "device"},
        {"date": "2026-01-02", "metric": "resting_hr", "value": 54.0, "source_scope": "device"},
        {"date": "2026-01-02", "metric": "resting_hr", "value": 70.0, "source_scope": "unknown"},
        {"date": "2026-01-02", "metric": "sleep_hrv", "value": 61.0, "source_scope": "device"},
        {"date": "2026-01-02", "metric": "device_max_hr", "value": 190.0, "source_scope": "device"},
        {"date": "2026-01-01", "metric": "stress", "value": 31.0, "source_scope": "device"},
        {"date": "2026-01-01", "metric": "not_a_metric", "value": 1.0, "source_scope": "device"},
    ],
    "sleep_sessions": [{
        "start_time": "2026-01-01T23:00:00+00:00", "end_time": "2026-01-02T07:00:00+00:00",
        "duration_minutes": 450, "deep_minutes": 80, "light_minutes": 250, "rem_minutes": 100,
        "awake_minutes": 30, "wake_count": 4, "score": 80,
        "stages": [{"start_time": "2026-01-01T23:00:00+00:00", "end_time": "2026-01-01T23:30:00+00:00", "stage": "light"}],
    }],
    "workouts": [{"workout_id": 7, "effective_type": "running", "start_time": "2026-01-02T08:00:00+00:00",
                  "end_time": "2026-01-02T08:40:00+00:00", "distance_meters": 6000.4, "avg_hr": 151.2,
                  "training_load": 88.0, "hr_zones": {"zones": [{"seconds": 60}, {"seconds": 900}]}}],
    "metric_samples": [{"metric": "weight", "timestamp": "2026-01-02T06:00:00+00:00", "value": 72.3456}],
}}
SHORT = {"data": {"metric_samples": [
    {"metric": "heart_rate", "hour": "2026-01-02T05:00:00+00:00", "min": 50.0, "avg": 58.4, "max": 71.0, "source_scope": "device"},
    {"metric": "heart_rate", "hour": "2026-01-02T05:00:00+00:00", "min": 1.0, "avg": 2.0, "max": 3.0, "source_scope": "user_fused"},
    {"metric": "stress", "hour": "2026-01-02T04:00:00+00:00", "min": 10.0, "avg": 20.0, "max": 30.0, "source_scope": "device"},
]}}


class TransformTest(unittest.TestCase):
    def setUp(self):
        self.s = b.build_summary(LONG, SHORT, start="2026-01-01", end="2026-01-02", data_modified=None)

    def test_days(self):
        days = {d["date"]: d for d in self.s["days"]}
        self.assertEqual(days["2026-01-02"]["steps"], 9000)      # fused wins for activity totals
        self.assertEqual(days["2026-01-02"]["restingHr"], 54)    # device wins otherwise
        self.assertEqual(days["2026-01-01"], {"date": "2026-01-01", "stress": 31})
        self.assertEqual(self.s["profile"]["maxHr"], 190)

    def test_sleep_workout_weight_hourly(self):
        sl = self.s["sleeps"][0]
        self.assertEqual(sl["durationMin"], 450)
        self.assertEqual(sl["stages"][0][2], "light")
        w = self.s["workouts"][0]
        self.assertEqual((w["id"], w["distanceM"], w["avgHr"], w["zoneSeconds"]), ("7", 6000, 151, [60, 900]))
        self.assertEqual(self.s["weights"][0]["kg"], 72.35)
        self.assertEqual(self.s["hourly"]["heartRate"], [[1767330000, 50, 58.4, 71]])
        self.assertEqual(self.s["freshness"]["lastReadingAt"], sl["end"])


class ChunkTest(unittest.TestCase):
    def test_long_ranges_are_chunked(self):
        import datetime as dt
        calls = []
        def exporter(start, end, types):
            calls.append((start, end))
            d0, d1 = dt.date.fromisoformat(start), dt.date.fromisoformat(end)
            self.assertLessEqual((d1 - d0).days + 1, 366)
            return {"data": {"daily_metrics": [{"date": start}]}}
        doc = b.export_range(exporter, dt.date(2025, 1, 1), dt.date(2026, 6, 30), "x")
        self.assertEqual(len(calls), 2)
        self.assertEqual(len(doc["data"]["daily_metrics"]), 2)


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.calls = 0

        def exporter(start, end, types):
            cls.calls += 1
            return LONG if "sleep" in types else SHORT

        cache = b.SummaryCache(exporter, cls.tmp.name)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), b.make_handler(cache, TOKEN, 400))
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.tmp.cleanup()

    def req(self, path, token=TOKEN, headers=None):
        h = dict(headers or {})
        if token:
            h["Authorization"] = f"Bearer {token}"
        return urllib.request.urlopen(urllib.request.Request(self.base + path, headers=h), timeout=5)

    def test_healthz_open(self):
        self.assertEqual(json.load(self.req("/healthz", token=None))["ok"], True)

    def test_auth_required(self):
        for tok in (None, "wrong"):
            with self.assertRaises(urllib.error.HTTPError) as cm:
                self.req("/api/v1/summary", token=tok)
            self.assertEqual(cm.exception.code, 401)

    def test_summary_gzip_and_etag(self):
        r = self.req("/api/v1/summary?days=30", headers={"Accept-Encoding": "gzip"})
        self.assertEqual(r.headers["Content-Encoding"], "gzip")
        doc = json.loads(gzip.decompress(r.read()))
        self.assertEqual(doc["schema"], "baseline.v1")
        calls = self.calls
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req("/api/v1/summary?days=30", headers={"If-None-Match": r.headers["ETag"]})
        self.assertEqual(cm.exception.code, 304)
        self.assertEqual(self.calls, calls)  # served from cache

    def test_bad_days(self):
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req("/api/v1/summary?days=abc")
        self.assertEqual(cm.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
