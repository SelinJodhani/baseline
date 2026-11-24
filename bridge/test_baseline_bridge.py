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


def _minutes(start_iso, n, value_at):
    """n per-minute heart_rate rows from start_iso (UTC); value_at(i) -> bpm."""
    import datetime as dt
    t0 = dt.datetime.fromisoformat(start_iso)
    return [{"metric": "heart_rate", "timestamp": (t0 + dt.timedelta(minutes=i)).isoformat(),
             "value": float(value_at(i)), "unit": "bpm", "source_scope": "device"} for i in range(n)]


class NadirTest(unittest.TestCase):
    # Night 2026-01-01 23:00 → 2026-01-02 07:00 UTC (LONG fixture). Low of 48 bpm for 10 min from 02:00 (minute 180).
    NIGHT = _minutes("2026-01-01T23:00:00+00:00", 480,
                     lambda i: 48 if 180 <= i < 190 else (30 if i == 60 else 60))

    def test_hr_readings_dedupes_and_filters(self):
        rows = [
            {"metric": "heart_rate", "timestamp": "2026-01-02T01:00:00+00:00", "value": 70.0, "source_scope": "user_fused"},
            {"metric": "heart_rate", "timestamp": "2026-01-02T01:00:00+00:00", "value": 55.0, "source_scope": "device"},
            {"metric": "heart_rate", "timestamp": "2026-01-02T01:01:00+00:00", "value": 0.0, "source_scope": "device"},
            {"metric": "resting_hr", "timestamp": "2026-01-02T01:02:00+00:00", "value": 50.0, "source_scope": "device"},
            {"metric": "heart_rate", "timestamp": "2026-01-02T01:03:00+00:00", "value": None},
        ]
        self.assertEqual(b.hr_readings(rows), [(1767315600, 55.0)])

    def test_nadir_is_smoothed_low_and_its_time(self):
        hr = b.hr_readings(self.NIGHT)
        start, end = 1767308400, 1767337200  # 23:00 → 07:00 UTC
        n = b.sleep_nadir(hr, [t for t, _ in hr], start, end)
        self.assertEqual(n["bpm"], 48)              # the single 30-bpm glitch averages out
        self.assertEqual((n["at"] - start) // 60, 182)  # middle of the first all-48 window
        self.assertIsNone(b.sleep_nadir(hr[:20], [t for t, _ in hr[:20]], start, end))  # too few readings

    def test_summary_attaches_hr_nadir_to_main_sleeps_only(self):
        nap = {"start_time": "2026-01-02T13:00:00+00:00", "end_time": "2026-01-02T13:40:00+00:00", "duration_minutes": 35}
        long_doc = {"data": dict(LONG["data"], sleep_sessions=LONG["data"]["sleep_sessions"] + [nap])}
        hr_doc = {"data": {"metric_samples": self.NIGHT + _minutes("2026-01-02T13:00:00+00:00", 40, lambda i: 50)}}
        s = b.build_summary(long_doc, SHORT, start="2026-01-01", end="2026-01-02", data_modified=None, hr_doc=hr_doc)
        night, nap_out = s["sleeps"]
        self.assertEqual(night["hrNadir"]["bpm"], 48)
        self.assertNotIn("hrNadir", nap_out)
        self.assertEqual(s["bridgeVersion"], b.VERSION)
        # Nights that start before the per-minute export window get nothing (a partial night would mislead).
        s2 = b.build_summary(long_doc, SHORT, start="2026-01-01", end="2026-01-02", data_modified=None,
                             hr_doc=hr_doc, hr_from="2026-01-03")
        self.assertNotIn("hrNadir", s2["sleeps"][0])
        # No per-minute export at all: unchanged output.
        self.assertNotIn("hrNadir", b.build_summary(LONG, SHORT, start="2026-01-01", end="2026-01-02", data_modified=None)["sleeps"][0])

    def test_cache_survives_failed_hr_export(self):
        def exporter(start, end, types):
            return LONG if "sleep" in types else SHORT

        def broken(start, end, types):
            raise RuntimeError("boom")

        calls = []

        def hr(start, end, types):
            calls.append((start, end, types))
            return {"data": {"metric_samples": []}}

        with tempfile.TemporaryDirectory() as d:
            doc = json.loads(b.SummaryCache(exporter, d, hr_exporter=broken).get(30)[1])
            self.assertEqual(doc["sleeps"][0]["durationMin"], 450)
            b.SummaryCache(exporter, d, hr_exporter=hr).get(30)
        self.assertEqual(calls[0][2], "heart_rate")
        # ~90 days are exported in ≤31-day chunks that cover the window without gaps.
        self.assertGreaterEqual(len(calls), 3)
        for (_, e1, _), (s2, _, _) in zip(calls, calls[1:]):
            import datetime as _dt
            self.assertEqual(_dt.date.fromisoformat(s2) - _dt.date.fromisoformat(e1), _dt.timedelta(days=1))

    def test_day_summaries_min_hr_and_azm(self):
        import datetime as _dt
        utc = _dt.timezone.utc
        # A full day at 60 bpm with a 30-minute run at 150 (cardio) and 30 minutes at 120 (fat burn).
        def hr_at(i):
            return 150.0 if 600 <= i < 630 else 120.0 if 630 <= i < 660 else 60.0
        rows = _minutes("2026-01-05T00:00:00+00:00", 1440, hr_at)
        readings = b.hr_readings(rows)
        out = b.day_hr_summaries(readings, max_hr=190.0, tz=utc)
        day = out["2026-01-05"]
        self.assertEqual(day["minHr"], 60)
        # reserve 130: fat burn from 112, cardio from 138 → 30 min × 2 + 30 min × 1
        self.assertEqual(day["azm"], 90)
        # No max HR: min HR only. Too few readings: nothing.
        self.assertNotIn("azm", b.day_hr_summaries(readings, max_hr=None, tz=utc)["2026-01-05"])
        self.assertEqual(b.day_hr_summaries(readings[:600], max_hr=190.0, tz=utc), {})


class MergeTest(unittest.TestCase):
    """Two accounts' libraries: the current one (with an imported CSV of the old account's basics) and the old
    account itself (with HRV and sleep stages)."""
    CURRENT = {"data": {
        "daily_metrics": [
            {"date": "2026-01-02", "metric": "steps", "value": 9000.0, "source_scope": "user_fused"},
            {"date": "2026-11-20", "metric": "steps", "value": 5000.0, "source_scope": "user_fused"},
            {"date": "2026-11-20", "metric": "sleep_hrv", "value": 66.0, "source_scope": "device"},
        ],
        "sleep_sessions": [{  # imported from CSV: no stages
            "start_time": "2026-01-01T23:00:00+00:00", "end_time": "2026-01-02T07:00:00+00:00", "duration_minutes": 450,
        }],
        "workouts": [{"workout_id": 1, "workout_type": "run", "start_time": "2026-01-02T10:00:00+00:00", "end_time": "2026-01-02T10:30:00+00:00"}],
        "metric_samples": [{"metric": "weight", "timestamp": "2026-01-02T07:00:00+00:00", "value": 61.0}],
    }}
    OLD = {"data": {
        "daily_metrics": [
            {"date": "2026-01-02", "metric": "steps", "value": 8800.0, "source_scope": "user_fused"},
            {"date": "2026-01-02", "metric": "sleep_hrv", "value": 58.0, "source_scope": "device"},
            {"date": "2025-06-01", "metric": "steps", "value": 7000.0, "source_scope": "user_fused"},
        ],
        "sleep_sessions": [
            {"start_time": "2026-01-01T23:05:00+00:00", "end_time": "2026-01-02T06:55:00+00:00", "duration_minutes": 440,
             "stages": [{"start_time": "2026-01-01T23:05:00+00:00", "end_time": "2026-01-02T00:00:00+00:00", "stage": "light"}]},
            {"start_time": "2025-05-31T23:00:00+00:00", "end_time": "2025-06-01T06:00:00+00:00", "duration_minutes": 400},
        ],
        "workouts": [
            {"workout_id": 9, "workout_type": "run", "start_time": "2026-01-02T10:01:00+00:00", "end_time": "2026-01-02T10:31:00+00:00"},
            {"workout_id": 8, "workout_type": "walk", "start_time": "2025-06-01T09:00:00+00:00", "end_time": "2025-06-01T10:00:00+00:00"},
        ],
        "metric_samples": [{"metric": "weight", "timestamp": "2026-01-02T07:00:30+00:00", "value": 61.0}],
    }}

    def test_merge(self):
        s = b.build_summary(self.CURRENT, SHORT, start="2025-01-01", end="2026-11-22", data_modified=None, extra_docs=[self.OLD])
        days = {d["date"]: d for d in s["days"]}
        self.assertEqual(days["2026-01-02"]["steps"], 9000)          # current account wins
        self.assertEqual(days["2026-01-02"]["sleepHrv"], 58.0)        # old account fills HRV
        self.assertEqual(days["2025-06-01"]["steps"], 7000)          # days only the old account has
        self.assertEqual(days["2026-11-20"]["sleepHrv"], 66.0)
        self.assertEqual(len(s["sleeps"]), 2)                        # the shared night once...
        night = [x for x in s["sleeps"] if x["durationMin"] in (440, 450)][0]
        self.assertEqual(night["durationMin"], 440)                  # ...the staged one wins over the CSV import
        self.assertEqual(len(s["workouts"]), 2)                      # the run once, plus the old walk
        self.assertEqual(len(s["weights"]), 1)                       # same weigh-in once

    def test_no_extras_is_unchanged(self):
        a = b.build_summary(self.CURRENT, SHORT, start="2025-01-01", end="2026-11-22", data_modified=None)
        c = b.build_summary(self.CURRENT, SHORT, start="2025-01-01", end="2026-11-22", data_modified=None, extra_docs=[])
        a.pop("generatedAt"); c.pop("generatedAt")
        self.assertEqual(a, c)

    def test_cache_merges_extra_library(self):
        def current(start, end, types):
            return self.CURRENT if "sleep" in types else SHORT
        def old(start, end, types):
            return self.OLD
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as d2:
            doc = json.loads(b.SummaryCache(current, d, extras=[(d2, old, None)]).get(30)[1])
            self.assertTrue(any(x.get("sleepHrv") == 58.0 for x in doc["days"]))
            # A broken extra library never takes the main one down.
            def broken(start, end, types):
                raise RuntimeError("boom")
            doc2 = json.loads(b.SummaryCache(current, d, extras=[(d2, broken, None)]).get(30)[1])
            self.assertTrue(any(x.get("steps") == 9000 for x in doc2["days"]))


class SyncTest(unittest.TestCase):
    def _job(self, exit_code, cooldown=0):
        d = tempfile.mkdtemp()
        script = os.path.join(d, "fake-cli")
        with open(script, "w") as f:
            f.write(f"#!/bin/sh\necho '{{}}'\nexit {exit_code}\n")
        os.chmod(script, 0o755)
        os.environ["ZEPPBRIDGE_CLI"] = script
        return b.SyncJob(True, cooldown)

    def _wait(self, job):
        import time
        for _ in range(100):
            if job.status()["state"] != "running":
                return job.status()
            time.sleep(0.02)
        self.fail("sync never finished")

    def tearDown(self):
        os.environ.pop("ZEPPBRIDGE_CLI", None)

    def test_success_busy_failure(self):
        for code, state in ((0, "done"), (4, "busy"), (1, "failed")):
            job = self._job(code)
            self.assertEqual(job.start()[0], 202)
            self.assertEqual(self._wait(job)["state"], state)

    def test_cooldown_and_disabled(self):
        job = self._job(0, cooldown=600)
        job.start(); self._wait(job)
        self.assertEqual(job.start()[0], 200)          # within cooldown: just status
        self.assertEqual(b.SyncJob(False, 0).start()[0], 403)


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


FULL = {"data": {"metric_samples": [
    {"metric": "heart_rate", "timestamp": "2026-01-02T00:00:00+00:00", "value": 61.0, "source_scope": "user_fused"},
    {"metric": "heart_rate", "timestamp": "2026-01-02T00:00:00+00:00", "value": 58.0, "source_scope": "device"},
    {"metric": "heart_rate", "timestamp": "2026-01-02T00:01:00+00:00", "value": 400.0, "source_scope": "device"},
    {"metric": "hrv_rmssd", "timestamp": "2026-01-02T00:02:00+00:00", "value": 71.4, "source_scope": "device"},
    {"metric": "hrv", "timestamp": "2026-01-02T00:02:00+00:00", "value": 99.0, "source_scope": "device"},
    {"metric": "spo2", "timestamp": "2026-01-02T00:03:30+00:00", "value": 97.0, "source_scope": "device"},
    {"metric": "spo2", "timestamp": "2026-01-03T00:00:00+00:00", "value": 96.0, "source_scope": "device"},
    {"metric": "stress", "timestamp": "2026-01-02T00:04:00+00:00", "value": 30.0, "source_scope": "device"},
]}}
T0 = 1767312000  # 2026-01-02T00:00:00Z


class SamplesTest(unittest.TestCase):
    def test_build_samples(self):
        out = b.build_samples(FULL, T0, T0 + 86400)
        self.assertEqual(out["heartRate"], [[T0, 58]])          # device wins; 400 bpm dropped
        self.assertEqual(out["hrv"], [[T0 + 120, 71.4]])        # RMSSD only, not the spot "hrv" reading
        self.assertEqual(out["spo2"], [[T0 + 210, 97]])         # the next day's reading is outside the window
        self.assertNotIn("stress", out)

    def test_extra_library_fills_gaps(self):
        older = {"data": {"metric_samples": [
            {"metric": "heart_rate", "timestamp": "2026-01-02T00:00:00+00:00", "value": 70.0, "source_scope": "device"},
            {"metric": "heart_rate", "timestamp": "2026-01-02T05:00:00+00:00", "value": 66.0, "source_scope": "device"},
        ]}}
        src = b.SampleSource(lambda s, e, t: FULL, "/nonexistent", extras=[("old", lambda s, e, t: older)])
        doc = src.get(T0, T0 + 86400)
        self.assertEqual(doc["heartRate"], [[T0, 58], [T0 + 18000, 66]])
        self.assertEqual(doc["lastReadingAt"], T0 + 18000)
        self.assertEqual(doc["schema"], "baseline.samples.v1")
        self.assertIsNone(doc["device"])

    def test_device_name(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "devices.json"), "w") as f:
                json.dump({"profiles": [{"name": "157", "canonical_name": "Amazfit Helio Strap"}]}, f)
            self.assertEqual(b.device_name(d), "Amazfit Helio Strap")

    def test_window(self):
        self.assertEqual(b.parse_window("", 1000000), (1000000 - 86400, 1000000))
        self.assertEqual(b.parse_window("from=10&to=20", 1000000), (10, 20))
        for q in ("from=20&to=10", "from=0&to=9999999", "from=x"):
            with self.assertRaises(ValueError):
                b.parse_window(q, 10000000)


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.calls = 0

        def exporter(start, end, types):
            cls.calls += 1
            return LONG if "sleep" in types else SHORT

        cache = b.SummaryCache(exporter, cls.tmp.name)
        samples = b.SampleSource(lambda s, e, t: FULL, cls.tmp.name)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), b.make_handler(cache, TOKEN, 400, samples=samples))
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

    def test_samples(self):
        r = self.req(f"/api/v1/samples?from={T0}&to={T0 + 86400}", headers={"Accept-Encoding": "gzip"})
        doc = json.loads(gzip.decompress(r.read()))
        self.assertEqual(doc["hrv"], [[T0 + 120, 71.4]])
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req(f"/api/v1/samples?from={T0}&to={T0 - 5}")
        self.assertEqual(cm.exception.code, 400)
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req("/api/v1/samples", token="wrong")
        self.assertEqual(cm.exception.code, 401)

    def test_bad_days(self):
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req("/api/v1/summary?days=abc")
        self.assertEqual(cm.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
