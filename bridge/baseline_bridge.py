#!/usr/bin/env python3
"""Baseline Bridge — a tiny, read-only HTTP API over a ZeppBridge library.

It shells out to `zeppbridge-cli export --format json` (the stable, documented
`zeppbridge.ai.v2` contract) and reshapes the result into one compact JSON
document the Baseline Android app understands (`baseline.v1`). It also reads per-minute heart
rate (`--detail full`, heart_rate only) for the last ~9 months and sends only small summaries of it:
each night's lowest heart rate and its time (`hrNadir`, last 30 nights), and per day the lowest
heart rate (`minHr`) and Fitbit-style Active Zone Minutes (`azm`). `/api/v1/samples` hands out the raw
per-minute heart rate, HRV (RMSSD) and SpO2 readings for a time window, so the app can copy Zepp's data into
Health Connect as recorded. It never writes to the
ZeppBridge database, never talks to the Zepp cloud, and has no dependencies
beyond the Python standard library.

Environment:
  BASELINE_TOKEN          required. Bearer token the app must send (>= 24 chars).
  BASELINE_PORT           default 8787
  BASELINE_BIND           default 0.0.0.0
  BASELINE_MAX_DAYS       default 730 (hard cap for ?days=)
  BASELINE_ALLOW_SYNC     default 1. Lets the app ask for an on-demand Zepp cloud sync
                          (the same `zeppbridge-cli sync --mode incremental` your timer runs).
  BASELINE_SYNC_COOLDOWN  default 120 seconds between on-demand syncs
  ZEPPBRIDGE_CLI      default zeppbridge-cli
  ZEPPBRIDGE_DATA_DIR default /data (the account the watch syncs to now)
  BASELINE_EXTRA_LIBRARIES  optional, comma-separated: more ZeppBridge data folders (one account each, e.g. an
                          older account). They are merged in day by day; the main library wins where both have
                          a value, and a night with sleep stages beats one without.
  TZ                  set it! ZeppBridge files readings under local days.
"""

from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import hmac
import json
import logging
import os
import subprocess
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

VERSION = "0.6.0"
SCHEMA = "baseline.v1"

LONG_TYPES = "daily_activity,recovery,pai,sleep,workouts,vo2max,weight,training_load"
SHORT_TYPES = "heart_rate,stress,spo2"
INTRADAY_DAYS = 8
SLEEP_STAGE_NIGHTS = 45

# Overnight heart-rate low ("hrNadir" on each sleep). Computed here from the per-minute readings of a
# `--detail full` heart_rate export over the last HR_NADIR_DAYS only, so the app gets one tiny object per night
# instead of ~1,400 readings.
HR_NADIR_DAYS = 30
NADIR_MIN_SLEEP_MIN = 180   # main sleeps only; naps are skipped
NADIR_SMOOTH = 5            # rolling mean over 5 consecutive readings (~5 min) so one glitch can't be the low
NADIR_MAX_SPAN_S = 600      # ...and those 5 readings must fall within 10 minutes
NADIR_MIN_READINGS = 30     # a night needs this many readings inside the sleep window
HR_PLAUSIBLE = (25.0, 230.0)

# Per-day summaries of minute heart rate over the last HR_DETAIL_DAYS (for the Coachable Health Age):
#   minHr — the day's lowest 5-reading rolling mean (the paper's "daily minimum heart rate" stand-in for RHR);
#   azm   — Active Zone Minutes as Fitbit defines them: heart-rate-reserve zones from that day's resting rate and
#           the profile max HR; 1 point a minute in the fat-burn zone (40–59 % HRR), 2 in cardio/peak (≥ 60 %).
# Exported in chunks so the bridge never holds a 90-day JSON document at once.
HR_DETAIL_DAYS = 273     # 91-day health-age window + 26 weekly points of history
HR_CHUNK_DAYS = 31
DAY_MIN_READINGS = 720      # a day needs ≥ 12 h of readings for minHr/azm (gaps are never imputed)
AZM_FATBURN, AZM_CARDIO = 0.40, 0.60

# ZeppBridge daily metric name -> Baseline day field.
DAILY_FIELDS = {
    "steps": "steps",
    "step_goal": "stepGoal",
    "distance": "distanceM",
    "calories": "calories",
    "active_calories": "activeCalories",
    "calorie_goal": "calorieGoal",
    "active_minutes": "activeMinutes",
    "active_minutes_goal": "activeMinutesGoal",
    "resting_hr": "restingHr",
    "device_resting_hr": "deviceRestingHr",
    "device_max_hr": "maxHr",
    "sleep_hrv": "sleepHrv",
    "sleep_rhr": "sleepRhr",
    "respiratory_rate": "respRate",
    "spo2_night_score": "spo2NightScore",
    "spo2_odi": "spo2Odi",
    "spo2_measured_minutes": "spo2Minutes",
    "stress": "stress",
    "stress_min": "stressMin",
    "stress_max": "stressMax",
    "stress_relaxed_pct": "stressRelaxedPct",
    "stress_normal_pct": "stressNormalPct",
    "stress_medium_pct": "stressMediumPct",
    "stress_high_pct": "stressHighPct",
    "pai_daily": "pai",
    "pai_total": "paiWeek",
    "pai_low_zone_minutes": "lowZoneMin",
    "pai_medium_zone_minutes": "moderateZoneMin",
    "pai_high_zone_minutes": "vigorousZoneMin",
    "readiness": "zeppReadiness",
    "hrv_readiness": "zeppHrvScore",
    "rhr_readiness": "zeppRhrScore",
    "skin_temp_readiness": "zeppSkinTempScore",
    "physical_charge": "physicalCharge",
    "mental_charge": "mentalCharge",
    "hybrid_charge": "charge",
    "training_load": "trainingLoad",
    "vo2max": "vo2max",
}
# Activity totals: the cloud's cross-device fusion is the better number.
PREFER_FUSED = {"steps", "distance", "calories", "active_calories", "active_minutes"}
SCOPE_RANK = {"device": 0, "user_fused": 1, "unknown": 2}

log = logging.getLogger("baseline-bridge")


# --------------------------------------------------------------------- export

Exporter = Callable[[str, str, str], dict]


def cli_exporter(start: str, end: str, types: str, detail: str = "summary", data_dir: str | None = None) -> dict:
    """Run zeppbridge-cli export (against [data_dir] when given) and return the parsed JSON document."""
    cmd = [
        os.environ.get("ZEPPBRIDGE_CLI", "zeppbridge-cli"),
        "export", "--format", "json", "--detail", detail,
        "--from", start, "--to", end, "--types", types,
    ]
    env = dict(os.environ, ZEPPBRIDGE_DATA_DIR=data_dir) if data_dir else None
    proc = subprocess.run(cmd, capture_output=True, timeout=120, check=False, env=env)
    if proc.returncode != 0:
        msg = proc.stderr.decode("utf-8", "replace").strip()[-400:]
        raise RuntimeError(f"zeppbridge-cli export exited {proc.returncode}: {msg}")
    return json.loads(proc.stdout)


MAX_EXPORT_DAYS = 366  # zeppbridge-cli refuses longer single exports


def export_range(exporter: Exporter, start: dt.date, end: dt.date, types: str) -> dict:
    """Export [start, end] in <= 366-day chunks and merge the data lists."""
    merged: dict[str, list] = {}
    cur = start
    while cur <= end:
        stop = min(end, cur + dt.timedelta(days=MAX_EXPORT_DAYS - 1))
        doc = exporter(cur.isoformat(), stop.isoformat(), types)
        for key, rows in (doc.get("data") or {}).items():
            if isinstance(rows, list):
                merged.setdefault(key, []).extend(rows)
        cur = stop + dt.timedelta(days=1)
    return {"data": merged}


# ------------------------------------------------------------------ transform

def _round(v: Any, nd: int = 1) -> Any:
    if isinstance(v, float):
        r = round(v, nd)
        return int(r) if nd == 0 or r.is_integer() else r
    return v


def _local_tz() -> dt.tzinfo:
    return dt.datetime.now().astimezone().tzinfo or dt.timezone.utc


def _parse_ts(s: str | None) -> dt.datetime | None:
    if not s:
        return None
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def _epoch(s: str | None) -> int | None:
    t = _parse_ts(s)
    return int(t.timestamp()) if t else None


def build_days(daily_metrics: list[dict]) -> list[dict]:
    chosen: dict[tuple[str, str], tuple[int, Any]] = {}
    for r in daily_metrics:
        name, date, value = r.get("metric"), r.get("date"), r.get("value")
        if name not in DAILY_FIELDS or date is None or value is None:
            continue
        rank = SCOPE_RANK.get(r.get("source_scope") or "unknown", 2)
        if name in PREFER_FUSED and r.get("source_scope") == "user_fused":
            rank = -1
        key = (date, name)
        if key not in chosen or rank < chosen[key][0]:
            chosen[key] = (rank, value)
    days: dict[str, dict] = {}
    for (date, name), (_, value) in chosen.items():
        nd = 0 if name in {"steps", "distance", "calories", "active_calories", "step_goal"} else 1
        days.setdefault(date, {"date": date})[DAILY_FIELDS[name]] = _round(float(value), nd)
    return [days[d] for d in sorted(days)]


def build_weights(samples: list[dict]) -> list[dict]:
    out = []
    for r in samples:
        if r.get("metric") == "weight" and r.get("value") is not None:
            t = r.get("timestamp") or r.get("hour")
            out.append({"t": _epoch(t), "kg": _round(float(r["value"]), 2)})
    return sorted((w for w in out if w["t"]), key=lambda w: w["t"])


def hr_readings(samples: list[dict]) -> list[tuple[int, float]]:
    """Per-minute heart rate from a --detail full export: sorted (epoch, bpm), one per timestamp (device wins)."""
    best: dict[int, tuple[int, float]] = {}
    for r in samples:
        if r.get("metric") != "heart_rate" or r.get("value") is None:
            continue
        t = _epoch(r.get("timestamp"))
        try:
            v = float(r["value"])
        except (TypeError, ValueError):
            continue
        if t is None or not HR_PLAUSIBLE[0] <= v <= HR_PLAUSIBLE[1]:
            continue
        rank = SCOPE_RANK.get(r.get("source_scope") or "unknown", 2)
        if t not in best or rank < best[t][0]:
            best[t] = (rank, v)
    return sorted((t, v) for t, (_, v) in best.items())


def sleep_nadir(readings: list[tuple[int, float]], times: list[int], start: int, end: int) -> dict | None:
    """Lowest 5-reading rolling mean inside [start, end] and the time of its middle reading, or None."""
    import bisect
    window = readings[bisect.bisect_left(times, start):bisect.bisect_right(times, end)]
    if len(window) < NADIR_MIN_READINGS:
        return None
    best: tuple[float, int] | None = None
    for i in range(len(window) - NADIR_SMOOTH + 1):
        chunk = window[i:i + NADIR_SMOOTH]
        if chunk[-1][0] - chunk[0][0] > NADIR_MAX_SPAN_S:
            continue
        avg = sum(v for _, v in chunk) / NADIR_SMOOTH
        if best is None or avg < best[0]:
            best = (avg, chunk[NADIR_SMOOTH // 2][0])
    if best is None:
        return None
    return {"bpm": _round(best[0], 0), "at": best[1]}


def day_hr_summaries(readings: list[tuple[int, float]], max_hr: float | None, tz: dt.tzinfo | None = None) -> dict[str, dict]:
    """{local date: {"minHr": bpm, "azm": points}} for days with enough readings. `readings` sorted (epoch, bpm)."""
    tz = tz or _local_tz()
    by_day: dict[str, list[tuple[int, float]]] = {}
    for t, v in readings:
        by_day.setdefault(dt.datetime.fromtimestamp(t, tz).date().isoformat(), []).append((t, v))
    out: dict[str, dict] = {}
    for day, rs in by_day.items():
        if len(rs) < DAY_MIN_READINGS:
            continue
        low = sleep_nadir(rs, [t for t, _ in rs], rs[0][0], rs[-1][0])
        if low is None:
            continue
        item: dict[str, Any] = {"minHr": low["bpm"]}
        if max_hr and max_hr > low["bpm"] + 40:
            reserve = max_hr - low["bpm"]
            fat, cardio = low["bpm"] + AZM_FATBURN * reserve, low["bpm"] + AZM_CARDIO * reserve
            item["azm"] = sum(2 if v >= cardio else 1 for _, v in rs if v >= fat)
        out[day] = item
    return out


def build_sleeps(sessions: list[dict], hr: list[tuple[int, float]] | None = None,
                 hr_since: int | None = None) -> list[dict]:
    """Sleep sessions; stage timelines for the last SLEEP_STAGE_NIGHTS, and an hrNadir for main sleeps that start
    on or after `hr_since` when per-minute heart rate (`hr`) is available."""
    out = []
    ordered = sorted(sessions, key=lambda s: s.get("start_time") or "")
    stage_cutoff = len(ordered) - SLEEP_STAGE_NIGHTS
    hr = hr or []
    hr_times = [t for t, _ in hr]
    for i, s in enumerate(ordered):
        start, end = _epoch(s.get("start_time")), _epoch(s.get("end_time"))
        if start is None or end is None or end <= start:
            continue
        item = {
            "start": start,
            "end": end,
            "durationMin": s.get("duration_minutes"),
            "deepMin": s.get("deep_minutes"),
            "lightMin": s.get("light_minutes"),
            "remMin": s.get("rem_minutes"),
            "awakeMin": s.get("awake_minutes"),
            "wakeCount": s.get("wake_count"),
            "score": s.get("score"),
        }
        if i >= stage_cutoff:
            stages = []
            for st in s.get("stages") or []:
                a, b = _epoch(st.get("start_time")), _epoch(st.get("end_time"))
                if a is not None and b is not None and b > a and st.get("stage"):
                    stages.append([a, b, st["stage"]])
            item["stages"] = stages
        if hr and end - start >= NADIR_MIN_SLEEP_MIN * 60 and (hr_since is None or start >= hr_since):
            item["hrNadir"] = sleep_nadir(hr, hr_times, start, end)
        out.append({k: v for k, v in item.items() if v is not None})
    return out


def _dedupe_weights(ws: list[dict]) -> list[dict]:
    """Same weigh-in seen by two accounts (within a minute): keep one."""
    out: list[dict] = []
    for w in ws:
        if out and abs(w["t"] - out[-1]["t"]) <= 60:
            continue
        out.append(w)
    return out


def build_hourly(samples: list[dict]) -> dict:
    series: dict[str, list] = {"heartRate": [], "stress": [], "spo2": []}
    key = {"heart_rate": "heartRate", "stress": "stress", "spo2": "spo2"}
    best: dict[tuple[str, int], tuple[int, list]] = {}
    for r in samples:
        k = key.get(r.get("metric"))
        t = _epoch(r.get("hour"))
        if not k or t is None or r.get("avg") is None:
            continue
        rank = SCOPE_RANK.get(r.get("source_scope") or "unknown", 2)
        row = [t, _round(r.get("min")), _round(r.get("avg")), _round(r.get("max"))]
        if (k, t) not in best or rank < best[(k, t)][0]:
            best[(k, t)] = (rank, row)
    for (k, _), (_, row) in sorted(best.items(), key=lambda kv: kv[0][1]):
        series[k].append(row)
    return series


def build_workouts(workouts: list[dict]) -> list[dict]:
    out = []
    for w in workouts:
        start, end = _epoch(w.get("start_time")), _epoch(w.get("end_time"))
        if start is None:
            continue
        zones = None
        hz = w.get("hr_zones")
        if isinstance(hz, dict) and isinstance(hz.get("zones"), list):
            zones = [int(z.get("seconds") or 0) for z in hz["zones"]]
        elif isinstance(hz, list):
            zones = [int((z or {}).get("seconds") or 0) for z in hz if isinstance(z, dict)]
        item = {
            "id": str(w.get("workout_id")),
            "type": w.get("effective_type") or w.get("workout_type") or "workout",
            "start": start,
            "end": end,
            "distanceM": _round(w.get("distance_meters"), 0),
            "calories": _round(w.get("calories"), 0),
            "avgHr": _round(w.get("avg_hr"), 0),
            "maxHr": _round(w.get("max_hr"), 0),
            "trainingLoad": _round(w.get("training_load")),
            "aerobicTe": _round(w.get("training_effect")),
            "anaerobicTe": _round(w.get("anaerobic_training_effect")),
            "vo2max": _round(w.get("vo2max")),
            "steps": w.get("total_steps"),
            "elevationGainM": _round(w.get("elevation_gain_m"), 0),
            "zoneSeconds": zones,
        }
        out.append({k: v for k, v in item.items() if v is not None})
    return sorted(out, key=lambda w: w["start"])


# ---------------------------------------------------------------- merging libraries

def _overlap(a: tuple[int, int], b: tuple[int, int]) -> float:
    """Overlap as a share of the shorter span."""
    inter = min(a[1], b[1]) - max(a[0], b[0])
    shorter = min(a[1] - a[0], b[1] - b[0])
    return inter / shorter if shorter > 0 and inter > 0 else 0.0


def merge_sessions(primary: list[dict], extra: list[dict]) -> list[dict]:
    """Sleep sessions from two accounts: one per night. Where two overlap by more than half, the one with sleep
    stages wins (an imported CSV night has none); on a tie the primary account wins."""
    def span(x: dict) -> tuple[int, int] | None:
        a, b = _epoch(x.get("start_time")), _epoch(x.get("end_time"))
        return (a, b) if a is not None and b is not None and b > a else None
    out = [x for x in primary if span(x)]
    for e in extra:
        se = span(e)
        if se is None:
            continue
        clash = next((i for i, p in enumerate(out) if _overlap(span(p), se) > 0.5), None)
        if clash is None:
            out.append(e)
        elif not out[clash].get("stages") and e.get("stages"):
            out[clash] = e
    return out


def merge_workouts(primary: list[dict], extra: list[dict]) -> list[dict]:
    """Workouts from two accounts; the same session (starts within 2 minutes) is kept once, primary first."""
    starts = [_epoch(w.get("start_time")) for w in primary]
    out = list(primary)
    for w in extra:
        t = _epoch(w.get("start_time"))
        if t is None or any(s is not None and abs(s - t) <= 120 for s in starts):
            continue
        out.append(w); starts.append(t)
    return out


def merge_days(primary: list[dict], extra: list[dict]) -> list[dict]:
    """Day by day, field by field: the primary account's values win, the other fills what it doesn't have."""
    by_date = {d["date"]: dict(d) for d in extra}
    for d in primary:
        by_date.setdefault(d["date"], {}).update(d)
    return [by_date[k] for k in sorted(by_date)]


def merge_readings(primary: list[tuple[int, float]], extra: list[tuple[int, float]]) -> list[tuple[int, float]]:
    out = dict(extra)
    out.update(dict(primary))
    return sorted(out.items())


def build_summary(long_doc: dict, short_doc: dict, *, start: str, end: str,
                  data_modified: float | None, hr_doc: dict | None = None, hr_from: str | None = None,
                  extra_docs: list[dict] | None = None) -> dict:
    """`hr_doc` is an optional --detail full heart_rate export starting on local date `hr_from`. `extra_docs` are
    long exports from other accounts' libraries, merged in under the primary one."""
    ld, sd = long_doc.get("data", {}), short_doc.get("data", {})
    days = build_days(ld.get("daily_metrics", []))
    sessions = ld.get("sleep_sessions", [])
    workouts_raw = ld.get("workouts", [])
    samples = list(ld.get("metric_samples", []))
    for doc in extra_docs or []:
        xd = doc.get("data", {})
        days = merge_days(days, build_days(xd.get("daily_metrics", [])))
        sessions = merge_sessions(sessions, xd.get("sleep_sessions", []))
        workouts_raw = merge_workouts(workouts_raw, xd.get("workouts", []))
        samples += xd.get("metric_samples", [])
    hourly = build_hourly(sd.get("metric_samples", []))
    if isinstance(hr_doc, list):  # pre-parsed readings (chunked export)
        hr = hr_doc or None
    else:
        hr = hr_readings((hr_doc or {}).get("data", {}).get("metric_samples", [])) if hr_doc else None
    hr_since = None
    if hr_from:
        hr_since = int(dt.datetime.combine(dt.date.fromisoformat(hr_from), dt.time(), _local_tz()).timestamp())
    sleeps = build_sleeps(sessions, hr, hr_since)
    if hr:
        profile_max = next((d["maxHr"] for d in reversed(days) if "maxHr" in d), None)
        by_date = {d["date"]: d for d in days}
        for date, extra in day_hr_summaries(hr, profile_max).items():
            if date not in by_date:
                by_date[date] = {"date": date}
                days.append(by_date[date])
            by_date[date].update(extra)
        days.sort(key=lambda d: d["date"])
    last_points = [r[0] for s in hourly.values() for r in s]
    last_points += [s["end"] for s in sleeps]
    max_hr = next((d["maxHr"] for d in reversed(days) if "maxHr" in d), None)
    tz = _local_tz()
    return {
        "schema": SCHEMA,
        "bridgeVersion": VERSION,
        "generatedAt": int(time.time()),
        "timezone": os.environ.get("TZ") or str(tz),
        "utcOffsetMinutes": int((dt.datetime.now(tz).utcoffset() or dt.timedelta()).total_seconds() // 60),
        "range": {"from": start, "to": end},
        "freshness": {
            "lastReadingAt": max(last_points) if last_points else None,
            "libraryModifiedAt": int(data_modified) if data_modified else None,
        },
        "profile": {"maxHr": max_hr},
        "days": days,
        "sleeps": sleeps,
        "hourly": hourly,
        "workouts": build_workouts(workouts_raw),
        "weights": _dedupe_weights(build_weights(samples)),
    }


# ---------------------------------------------------------------------- cache

class SummaryCache:
    """Re-exports only when the ZeppBridge library changed on disk."""

    def __init__(self, exporter: Exporter, data_dir: str, hr_exporter: Exporter | None = None,
                 extras: list[tuple[str, Exporter, Exporter | None]] | None = None):
        """`hr_exporter` (optional) returns a --detail full heart_rate export. `extras` are other accounts'
        libraries as (data_dir, exporter, hr_exporter), merged in under this one."""
        self.exporter, self.data_dir, self.hr_exporter = exporter, data_dir, hr_exporter
        self.extras = extras or []
        self.sync_enabled = False
        self.lock = threading.Lock()
        self.entries: dict[int, tuple[str, bytes, bytes]] = {}

    def minute_hr(self, today: dt.date, hr_exporter: Exporter | None = None) -> list[tuple[int, float]]:
        """Per-minute heart rate for the last HR_DETAIL_DAYS, exported in HR_CHUNK_DAYS pieces."""
        hr_exporter = hr_exporter or self.hr_exporter
        readings: dict[int, float] = {}
        start = today - dt.timedelta(days=HR_DETAIL_DAYS)
        while start <= today:
            stop = min(today, start + dt.timedelta(days=HR_CHUNK_DAYS - 1))
            doc = hr_exporter(start.isoformat(), stop.isoformat(), "heart_rate")
            readings.update(hr_readings(doc.get("data", {}).get("metric_samples", [])))
            del doc
            start = stop + dt.timedelta(days=1)
        return sorted(readings.items())

    def library_mtime(self) -> float | None:
        times = []
        for folder in [self.data_dir] + [d for d, _, _ in self.extras]:
            for name in ("zepp.db", "zepp.db-wal"):
                try:
                    times.append(os.stat(os.path.join(folder, name)).st_mtime)
                except OSError:
                    pass
        return max(times) if times else None

    def get(self, days: int) -> tuple[str, bytes, bytes]:
        mtime = self.library_mtime()
        today = dt.date.today()
        version = f"{mtime}:{today}:{days}:{VERSION}"
        etag = '"' + hashlib.sha256(version.encode()).hexdigest()[:20] + '"'
        with self.lock:
            hit = self.entries.get(days)
            if hit and hit[0] == etag:
                return hit
            start = (today - dt.timedelta(days=days - 1)).isoformat()
            short_start = (today - dt.timedelta(days=INTRADAY_DAYS - 1)).isoformat()
            end = today.isoformat()
            t0 = time.monotonic()
            long_doc = export_range(self.exporter, today - dt.timedelta(days=days - 1), today, LONG_TYPES)
            short_doc = self.exporter(short_start, end, SHORT_TYPES)
            hr_doc: list | None = None
            hr_from = (today - dt.timedelta(days=HR_NADIR_DAYS)).isoformat()
            if self.hr_exporter:
                try:
                    hr_doc = self.minute_hr(today)
                except Exception as exc:  # optional extra: the summary still ships without these summaries
                    log.warning("per-minute heart-rate export failed; skipping hrNadir/minHr/azm: %s", exc)
            extra_docs = []
            for folder, ex, hx in self.extras:
                try:
                    extra_docs.append(export_range(ex, today - dt.timedelta(days=days - 1), today, LONG_TYPES))
                    if hx and hr_doc is not None:
                        hr_doc = merge_readings(hr_doc, self.minute_hr(today, hx))
                except Exception as exc:  # an extra library never blocks the main one
                    log.warning("extra library %s skipped: %s", folder, exc)
            summary = build_summary(long_doc, short_doc, start=start, end=end, data_modified=mtime,
                                    hr_doc=hr_doc, hr_from=hr_from, extra_docs=extra_docs)
            summary["capabilities"] = {"sync": self.sync_enabled, "samples": True}
            body = json.dumps(summary, separators=(",", ":"), ensure_ascii=False).encode()
            entry = (etag, body, gzip.compress(body, 6))
            self.entries[days] = entry
            log.info("built summary days=%d bytes=%d gz=%d in %.0fms",
                     days, len(body), len(entry[2]), (time.monotonic() - t0) * 1000)
            return entry


# -------------------------------------------------------------------- samples

# `GET /api/v1/samples?from=<epoch>&to=<epoch>`: the raw readings behind the summary, for the app's Health Connect
# writer. Zepp is the source of truth, so the app copies these as recorded instead of re-deriving them.
SAMPLES_SCHEMA = "baseline.samples.v1"
SAMPLE_TYPES = "heart_rate,hrv_rmssd,spo2"
SAMPLES_MAX_DAYS = 32
# ZeppBridge metric -> (response key, plausible range). Out-of-range readings are dropped, never clamped.
SAMPLE_METRICS = {
    "heart_rate": ("heartRate", HR_PLAUSIBLE),
    "hrv_rmssd": ("hrv", (1.0, 300.0)),
    "spo2": ("spo2", (50.0, 100.0)),
}


def build_samples(doc: dict, start: int, end: int) -> dict[str, list]:
    """Per-reading series in [start, end): {"heartRate", "hrv", "spo2"} as sorted [epoch, value] pairs, one reading
    per metric and timestamp (a device reading beats a fused one)."""
    best: dict[tuple[str, int], tuple[int, float]] = {}
    for r in (doc.get("data") or {}).get("metric_samples", []):
        spec = SAMPLE_METRICS.get(r.get("metric"))
        if spec is None or r.get("value") is None:
            continue
        t = _epoch(r.get("timestamp"))
        try:
            v = float(r["value"])
        except (TypeError, ValueError):
            continue
        if t is None or not start <= t < end or not spec[1][0] <= v <= spec[1][1]:
            continue
        rank = SCOPE_RANK.get(r.get("source_scope") or "unknown", 2)
        key = (spec[0], t)
        if key not in best or rank < best[key][0]:
            best[key] = (rank, v)
    out: dict[str, list] = {name: [] for name, _ in SAMPLE_METRICS.values()}
    for (name, t), (_, v) in sorted(best.items(), key=lambda kv: kv[0][1]):
        out[name].append([t, _round(v, 1)])
    return out


def device_name(data_dir: str) -> str | None:
    """The watch/strap model ZeppBridge knows about (e.g. "Amazfit Helio Strap"), from its devices.json."""
    try:
        with open(os.path.join(data_dir, "devices.json"), encoding="utf-8") as f:
            doc = json.load(f)
        for p in doc.get("profiles") or []:
            name = p.get("canonical_name") or p.get("display_name")
            if isinstance(name, str) and name.strip():
                return name.strip()
    except (OSError, ValueError, AttributeError):
        pass
    return None


class SampleSource:
    """Exports raw readings for a time window on request (no cache: windows are small and always end 'now')."""

    def __init__(self, exporter: Exporter, data_dir: str, extras: list[tuple[str, Exporter]] | None = None):
        """`exporter` must return a --detail full export. `extras` are (data_dir, full exporter) of other accounts,
        merged in under this one."""
        self.exporter, self.data_dir, self.extras = exporter, data_dir, extras or []
        self.lock = threading.Lock()  # one heavy export at a time

    def get(self, start: int, end: int) -> dict:
        tz = _local_tz()
        first = dt.datetime.fromtimestamp(start, tz).date().isoformat()
        last = dt.datetime.fromtimestamp(end - 1, tz).date().isoformat()
        with self.lock:
            series = build_samples(self.exporter(first, last, SAMPLE_TYPES), start, end)
            for folder, ex in self.extras:
                try:
                    extra = build_samples(ex(first, last, SAMPLE_TYPES), start, end)
                except Exception as exc:  # an extra library never blocks the main one
                    log.warning("extra library %s skipped for samples: %s", folder, exc)
                    continue
                for key, rows in extra.items():
                    merged = merge_readings([(t, v) for t, v in series[key]], [(t, v) for t, v in rows])
                    series[key] = [[t, v] for t, v in merged]
        last_at = max((rows[-1][0] for rows in series.values() if rows), default=None)
        return {
            "schema": SAMPLES_SCHEMA,
            "bridgeVersion": VERSION,
            "generatedAt": int(time.time()),
            "from": start,
            "to": end,
            "device": device_name(self.data_dir),
            "lastReadingAt": last_at,
            **series,
        }


def parse_window(query: str, now: int) -> tuple[int, int]:
    """`from`/`to` epoch seconds from a query string; `to` defaults to now, `from` to a day before `to`."""
    q = parse_qs(query)
    end = int(q.get("to", [str(now)])[0])
    start = int(q.get("from", [str(end - 86400)])[0])
    end = min(end, now + 60)
    if start >= end:
        raise ValueError("from must be before to")
    if end - start > SAMPLES_MAX_DAYS * 86400:
        raise ValueError(f"ask for at most {SAMPLES_MAX_DAYS} days at a time")
    return start, end


# ----------------------------------------------------------------------- sync

class SyncJob:
    """One on-demand `zeppbridge-cli sync` at a time, with a cooldown so the app can't hammer Zepp."""

    def __init__(self, enabled: bool, cooldown: int):
        self.enabled, self.cooldown = enabled, cooldown
        self.lock = threading.Lock()
        self.state = "idle"  # idle | running | done | busy | failed
        self.started_at: int | None = None
        self.finished_at: int | None = None
        self.message: str | None = None

    def status(self) -> dict:
        with self.lock:
            return {"enabled": self.enabled, "state": self.state, "startedAt": self.started_at,
                    "finishedAt": self.finished_at, "message": self.message, "cooldownSeconds": self.cooldown}

    def start(self) -> tuple[int, dict | None]:
        if not self.enabled:
            return 403, {"error": "on-demand sync is disabled on this bridge (BASELINE_ALLOW_SYNC=0)"}
        with self.lock:
            now = int(time.time())
            if self.state == "running":
                return 202, None
            if self.finished_at and now - self.finished_at < self.cooldown:
                return 200, None
            self.state, self.started_at, self.message = "running", now, None
        threading.Thread(target=self._run, daemon=True).start()
        return 202, None

    def _run(self) -> None:
        cmd = [os.environ.get("ZEPPBRIDGE_CLI", "zeppbridge-cli"), "sync", "--mode", "incremental", "--json"]
        state, message = "failed", None
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=900, check=False)
            if proc.returncode == 0:
                state = "done"
            elif proc.returncode == 4:  # another writer (the scheduled timer) holds the lock
                state, message = "busy", "A scheduled sync is already running"
            else:
                message = f"zeppbridge-cli sync exited {proc.returncode}"
                log.error("sync failed (%d): %s", proc.returncode, proc.stderr.decode("utf-8", "replace")[-300:])
        except subprocess.TimeoutExpired:
            message = "Sync took longer than 15 minutes"
        except OSError as exc:
            message = f"Couldn't run zeppbridge-cli: {exc}"
        with self.lock:
            self.state, self.message, self.finished_at = state, message, int(time.time())
        log.info("on-demand sync finished: %s %s", state, message or "")


# ----------------------------------------------------------------------- http

def make_handler(cache: SummaryCache, token: str, max_days: int, sync: "SyncJob | None" = None,
                 samples: "SampleSource | None" = None):
    sync = sync or SyncJob(False, 120)
    token_b = token.encode()

    class Handler(BaseHTTPRequestHandler):
        server_version = f"BaselineBridge/{VERSION}"
        sys_version = ""

        def log_message(self, fmt: str, *args: Any) -> None:  # no query strings, no tokens
            log.info("%s %s %s", self.address_string(), self.command, fmt % args)

        def _send(self, status: int, body: bytes = b"", ctype: str = "application/json",
                  headers: dict | None = None) -> None:
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _error(self, status: int, message: str) -> None:
            self._send(status, json.dumps({"error": message}).encode())

        def _authorized(self) -> bool:
            header = self.headers.get("Authorization", "")
            if not header.startswith("Bearer "):
                return False
            return hmac.compare_digest(header[7:].strip().encode(), token_b)

        def do_GET(self) -> None:  # noqa: N802
            url = urlparse(self.path)
            if url.path == "/healthz":
                return self._send(200, json.dumps({"ok": True, "version": VERSION}).encode())
            if not url.path.startswith("/api/"):
                return self._error(404, "not found")
            if not self._authorized():
                return self._send(401, b'{"error":"unauthorized"}',
                                  headers={"WWW-Authenticate": 'Bearer realm="baseline"'})
            if url.path == "/api/v1/ping":
                return self._send(200, json.dumps({"ok": True, "version": VERSION, "schema": SCHEMA, "sync": sync.enabled}).encode())
            if url.path == "/api/v1/sync":
                return self._send(200, json.dumps(sync.status()).encode())
            if url.path == "/api/v1/samples":
                return self._samples(url.query)
            if url.path != "/api/v1/summary":
                return self._error(404, "not found")
            try:
                days = int(parse_qs(url.query).get("days", ["400"])[0])
            except ValueError:
                return self._error(400, "days must be an integer")
            days = max(14, min(days, max_days))
            try:
                etag, body, gz = cache.get(days)
            except Exception as exc:  # export failed: tell the app, keep running
                log.error("summary failed: %s", exc)
                return self._error(503, "ZeppBridge export failed — check the bridge logs")
            if self.headers.get("If-None-Match") == etag:
                return self._send(304, headers={"ETag": etag})
            headers = {"ETag": etag, "Vary": "Accept-Encoding"}
            if "gzip" in (self.headers.get("Accept-Encoding") or ""):
                headers["Content-Encoding"] = "gzip"
                return self._send(200, gz, headers=headers)
            return self._send(200, body, headers=headers)

        do_HEAD = do_GET

        def _samples(self, query: str) -> None:
            if samples is None:
                return self._error(404, "not found")
            try:
                start, end = parse_window(query, int(time.time()))
            except ValueError as exc:
                return self._error(400, str(exc))
            try:
                body = json.dumps(samples.get(start, end), separators=(",", ":")).encode()
            except Exception as exc:  # export failed: tell the app, keep running
                log.error("samples failed: %s", exc)
                return self._error(503, "ZeppBridge export failed — check the bridge logs")
            if "gzip" in (self.headers.get("Accept-Encoding") or ""):
                return self._send(200, gzip.compress(body, 6), headers={"Content-Encoding": "gzip", "Vary": "Accept-Encoding"})
            return self._send(200, body)

        def do_POST(self) -> None:  # noqa: N802
            url = urlparse(self.path)
            if not self._authorized():
                return self._send(401, b'{"error":"unauthorized"}', headers={"WWW-Authenticate": 'Bearer realm="baseline"'})
            if url.path != "/api/v1/sync":
                return self._error(404, "not found")
            code, err = sync.start()
            if err:
                return self._send(code, json.dumps(err).encode())
            return self._send(code, json.dumps(sync.status()).encode())

    return Handler


def pair(argv: list[str]) -> int:
    """Print a baseline://connect link (and nothing else) for the app. Run it yourself; it is never logged."""
    from urllib.parse import quote
    if len(argv) != 1 or not argv[0].startswith(("https://", "http://")):
        print("usage: baseline_bridge.py pair https://your-bridge-address", file=sys.stderr)
        return 2
    token = os.environ.get("BASELINE_TOKEN", "")
    if len(token) < 24:
        print("BASELINE_TOKEN is not set in this environment", file=sys.stderr)
        return 2
    print(f"baseline://connect?url={quote(argv[0].rstrip('/'), safe='')}&token={quote(token, safe='')}")
    return 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "pair":
        return pair(sys.argv[2:])
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    token = os.environ.get("BASELINE_TOKEN", "")
    if len(token) < 24:
        log.error("BASELINE_TOKEN must be set to a random string of at least 24 characters "
                  "(e.g. `openssl rand -base64 32`).")
        return 2
    port = int(os.environ.get("BASELINE_PORT", "8787"))
    bind = os.environ.get("BASELINE_BIND", "0.0.0.0")
    max_days = int(os.environ.get("BASELINE_MAX_DAYS", "730"))
    def lib(folder: str) -> tuple[str, Exporter, Exporter]:
        return (folder, lambda s, e, t: cli_exporter(s, e, t, "summary", folder),
                lambda s, e, t: cli_exporter(s, e, t, "full", folder))
    extras = [lib(f.strip()) for f in os.environ.get("BASELINE_EXTRA_LIBRARIES", "").split(",") if f.strip()]
    cache = SummaryCache(cli_exporter, os.environ.get("ZEPPBRIDGE_DATA_DIR", "/data"),
                         hr_exporter=lambda s, e, t: cli_exporter(s, e, t, "full"), extras=extras)
    sync = SyncJob(os.environ.get("BASELINE_ALLOW_SYNC", "1") not in ("0", "false", "no"),
                   int(os.environ.get("BASELINE_SYNC_COOLDOWN", "120")))
    cache.sync_enabled = sync.enabled
    samples = SampleSource(lambda s, e, t: cli_exporter(s, e, t, "full"), os.environ.get("ZEPPBRIDGE_DATA_DIR", "/data"),
                           extras=[(folder, full) for folder, _, full in extras])
    server = ThreadingHTTPServer((bind, port), make_handler(cache, token, max_days, sync, samples))
    log.info("Baseline Bridge %s listening on %s:%d (TZ=%s, on-demand sync %s, %d extra libraries)", VERSION, bind, port,
             os.environ.get("TZ", "unset"), "on" if sync.enabled else "off", len(extras))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
