# How Baseline calculates things

Every number in Baseline is computed on your phone from your own history. There are no black boxes; the code is [`insight/Insights.kt`](../android/app/src/main/java/app/baseline/insight/Insights.kt) and, for the newer metrics, [`insight/Advanced.kt`](../android/app/src/main/java/app/baseline/insight/Advanced.kt). **[H]** marks a heuristic: a sensible rule of thumb rather than a validated cut-off. The research behind each rule, with citations, is in [RESEARCH.md](RESEARCH.md).

The core idea is to **compare you with you**. Population norms for HRV and resting heart rate vary so much that they mean little to an individual. Your own 60-day normal does.

## Personal normal ranges

For HRV, resting heart rate and breathing rate:

- **Baseline:** the mean and SD of the **previous 60 days**, ending yesterday, so a bad week can't hide itself. It needs **14 or more nights** before it's used.
- **HRV is handled on the log scale**, `ln(ms)`, the standard practice in sport science (Plews, Buchheit, HRV4Training). The band is converted back to ms for display.
- **Normal band** = mean ± 0.5 SD, which is the "smallest worthwhile change". Resting HR uses at least ±2 bpm and breathing at least ±0.5 brpm, so a very stable history doesn't flag noise.
- **Today's z-score** = (today − mean) / SD, with the sign flipped where lower is better.

| Signal | Above normal | Normal | Watch | Low |
|---|---|---|---|---|
| HRV | z ≥ +0.5 | ±0.5 | −0.5 to −1 | < −1 |
| Resting HR | ≤ −2 bpm | within ±2 | +3 bpm | +5 bpm (Oura-style absolute cut-offs) |
| Breathing | — | within band | above band | — |

## Readiness (0–100)

```
z     = 0.5 · z_HRV + 0.3 · z_RHR + 0.2 · z_sleep
score = 70 + 15 · z      (clamped 1–100)

z_HRV, z_RHR clipped to [−3, +2]
z_sleep = (minutes asleep − sleep need) / 60, clipped to [−3, +1]
```

- **Ready / Primed:** z ≥ −0.5 (Primed when z ≥ +0.5).
- **Take it easier:** z < −0.5, **or** any single input below −1.5.
- **Recover:** z < −1.5.

These weights are a transparent heuristic, weighted towards HRV like commercial scores; vendors don't publish their own. The Today card always shows which input drove the score. Until 14 nights exist, Baseline shows Zepp's own readiness, labelled "Zepp".

## Signals off (the illness / strain flag)

Each night, four overnight signals get a vote when they leave their usual range:

| Signal | Off when |
|---|---|
| Resting HR | z ≥ +1.5 (SD floor 1 bpm) |
| Breathing rate | ≥ +1 breath/min above the mean |
| HRV, `ln(ms)` | z ≤ −1 (SD floor 0.03) |
| Zepp skin-temperature readiness score | in your bottom 10% (nearest-rank P10) **and** below your median **[H]** |

- **Baseline:** the previous **28 nights**, leaving out nights that themselves had ≥2 votes, so an illness can't hide inside its own baseline. A signal votes only when it has **≥14 nights** in that window.
- **Signals off** = **≥2 votes on each of 2 consecutive nights** (Mishra 2020; Quer 2021; Natarajan 2020; Mason 2022 for skin temperature).
- **Held back:** when the day before the latest night had a training load above its 90th percentile of the previous 90 days (≥10 workout days needed), the card still explains what's off, but the illness flag and the "rest" focus are held back and the copy mentions the hard session.
- **Wording** is never diagnostic: "Several of your overnight signals are off — your body may be fighting something. Rest if you can."
- One night with two signals off, or one signal off for many nights, never raises the flag. That's deliberate, to avoid the "orthosomnia" effect where tracking itself becomes a source of anxiety.
- `Insights.illnessFlag` = signals off and not held back; `Insights.signalsOff` carries which signals and for how many nights.

## Sleep

- **Main sleep** = the longest session that ended on a given day. Naps count only where noted below.
- **Debt** (Today, readiness) = the sum over the last 7 nights of `max(0, need − asleep)`, using your sleep-need setting.
- **Sleep Regularity Index (SRI)** (Phillips 2017; Windred 2024, UK Biobank). Each "sleep day" runs noon to noon and is split into 1,440 minutes; a minute is asleep when it falls inside a deep, light or REM stage span of any session, naps included (sessions without a stage timeline count their whole start–end window). A day is valid only with ≥3 h of recorded sleep, so a night without the watch is a gap rather than a "wake" day.

  ```
  SRI = −100 + 200 / (1440 · pairs) · Σ 1[state(day i, minute m) = state(day i+1, minute m)]
  ```

  summed over consecutive *valid* day pairs in the last **14 days**; needs **≥7 valid days and ≥6 pairs**. Bands: **≥83 regular, 72–83 fairly regular, <72 irregular** (amber, never red). The bedtime/wake-time circular SD (worse of the two, 14 nights) stays as the explanation underneath.
- **Sleep need (estimated)** **[H]** = the median *total* sleep (main + naps) on free days, the nights ending Saturday or Sunday, over the last 14 nights. Needs ≥3 such nights, otherwise your setting is used. Rounded to 5 min and kept within 6h 30m–9h 30m.
- **Shortfall trend** = `Σ max(0, need − total sleep)` over the last 7 nights with data (≥4 nights), compared with the 7 before: ≥60 min less is *easing*, ≥60 min more is *building*, otherwise *steady*. It's shown as a gentle trend, never as hours owed.
- **Sleep midpoint** = onset + (wake − onset)/2 of the main sleep; the headline is the circular mean of the last 14 nights.
- **Social jet lag** = circular mean midpoint on free days − work days (Mon–Fri wake days), over 28 nights with ≥4 free and ≥8 work nights (Wittmann 2006; Roenneberg 2012). **Chronotype** (MCTQ MSFsc, adapted to watch data **[H]**) = `MS_free − 0.5 · (SD_free − SD_week)` when free-day sleep is longer, with `SD_week = (5 · SD_work + 2 · SD_free) / 7`.
- **Suggested bedtime** = your typical wake time (circular mean) − sleep need − 15 min to fall asleep.
- **Stages and efficiency** are shown, but nothing is judged on them. Wrist devices classify stages only moderately well and overestimate efficiency.

## HRV trend

- `lnRMSSD₇` = mean of the last 7 nightly `ln(HRV)` values, needing **≥3 nights** (Plews 2014). Shown back in ms against a band of mean ± 0.5 SD of `ln(HRV)` over the 60 days before that week (≥14 nights).
- `CV₇` = SD ÷ mean × 100 of the same 7 `ln` values. "Usual" CV = the median of the four previous non-overlapping weekly CVs (≥2 needed).
- **Fatigue note** **[H]**: CV₇ ≥ 1.25 × usual **and** `lnRMSSD₇` at least 0.5 baseline SD below its value a week earlier.

## Overnight heart-rate low

- **Bridge 0.3.0+** sends `hrNadir {bpm, at}` for each sleep of ≥3 h in the last 30 nights: the minimum of a 5-reading rolling mean of per-minute heart rate (the 5 readings within 10 min; ≥30 readings in the sleep window; readings outside 25–230 bpm dropped). Older nights fall back to the lowest *hourly* minimum inside the sleep, timed to the middle of that hour (±1 h).
- **Timing** = (time of low − sleep onset) ÷ (wake − onset): **<0.5 early, 0.5–0.75 mid-night, ≥0.75 late** **[H]**.
- **Wording:** "Early dips usually mean better recovery; late dips often follow alcohol or a late meal." That reading is a consumer-wearable heuristic (alcohol: Pietilä 2018; meal timing: a 2026 trial reported by Northwestern) and is phrased as "often", never as a cause.

## Activity

- **Active minutes** = moderate minutes + 2 × vigorous minutes over 7 days, the WHO equivalence. The target is 150, with 300 for extra benefit.
- **Steps:** judged on the 7-day average against your goal. Evidence shows benefits plateauing around 8–10k a day under 60 and 6–8k over 60 (Paluch 2022). With your birth year set (optional, Settings → About you), the Steps story names the range for your age.
- **Time in zones:** per-workout HR-zone seconds over the last 7 days, bucketed **low = Zepp zones 1–2, threshold = zone 3, high = zones 4–5** (with other zone counts: the top two are high, the one below is threshold, the rest low). The split is shown without promoting an "80/20" target, which is unproven for recreational exercisers (Stöggl & Sperlich 2014).
- **Today's movement range** **[H]**: readiness picks a tier: Recover 10–20 min gentle, Take it easier 20–40 min easy, calibrating 30–45 min easy, Ready 30–60 min moderate, Primed 45–75 min harder. It drops one tier when the 7-day/42-day load ratio is above 1.3, and becomes "up to 20 min gentle" when signals are off. The text notes whether the weekly active-minute target is already met. Exposed as `Insights.movementRange`.
- **VO₂max band:** the 90-day median of your watch's VO₂max, placed on the FRIEND registry's treadmill percentiles for your age decade and sex (needs birth year and sex, both optional and stored only on the phone). The band is the percentiles of `VO₂max ± 3.5 mL/kg/min`, widened to multiples of 5, because wearable estimates carry several points of error (Molina-García 2022). The table currently holds the FRIEND 2015 values (Kaminsky 2015); the 2022 update (Kaminsky 2022) reports treadmill values 1.5–4.6 mL/kg/min lower, so today's band errs on the modest side. It's a single table constant (`Friend` in Advanced.kt) to swap.
- **PAI:** Zepp's own score. Keeping it at 100 or more over 7 days was linked to lower cardiovascular mortality in the HUNT study.
- **Training load:** a 7-day ("fatigue") and 42-day ("fitness") exponentially weighted average of daily workout load. The ratio is described as "in your usual range" or "above your usual". The app deliberately makes no injury-risk claims, because the ACWR literature doesn't support them.

## Today's focus

One sentence, picked by the first rule that matches:

1. Signals off (illness flag) → rest.
2. Calibrating → explain what's missing.
3. Readiness in **Recover** → recovery day.
4. Sleep debt ≥ 3 h → bedtime suggestion.
5. Primed and weekly active minutes below target → a good day to push.
6. Readiness in **Take it easier** → keep it easy.
7. Afternoon, and steps below 60% of goal → how many minutes of walking closes the gap.
8. Otherwise → steady, train as planned.

## Data rules

- A missing reading is a gap, never zero and never interpolated. This follows ZeppBridge's own export contract.
- Activity totals use Zepp's cross-device fused values. Everything else uses the watch's own values.
