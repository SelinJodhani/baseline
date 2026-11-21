# How Baseline calculates things

Every number in Baseline is computed on your phone from your own history. There are no black boxes; the code is [`insight/Insights.kt`](../android/app/src/main/java/app/baseline/insight/Insights.kt). The research behind each rule, with citations, is in [RESEARCH.md](RESEARCH.md).

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

## Illness / strain flag

The flag appears only when **two independent signals agree**: resting HR at least 1.5 SD above normal **and** breathing rate at least 1 brpm above normal. A single bad night or a single metric never raises an alarm. That's deliberate, to avoid the "orthosomnia" effect where tracking itself becomes a source of anxiety.

## Sleep

- **Main sleep** = the longest session that ended on a given day. Naps are ignored.
- **Debt** = the sum over the last 7 nights of `max(0, need − asleep)`.
- **Rhythm** = the circular SD of bedtimes and of wake times over 14 nights. The worse of the two is shown. Under ±30 min is very regular; over ±75 min is very irregular. This approximates the Sleep Regularity Index, which needs minute-level data.
- **Suggested bedtime** = your typical wake time (circular mean) − sleep need − 15 min to fall asleep.
- **Stages and efficiency** are shown, but nothing is judged on them. Wrist devices classify stages only moderately well and overestimate efficiency.

## Activity

- **Active minutes** = moderate minutes + 2 × vigorous minutes over 7 days, the WHO equivalence. The target is 150, with 300 for extra benefit.
- **Steps:** judged on the 7-day average against your goal. Evidence shows benefits plateauing around 8–10k a day under 60 and 6–8k over 60.
- **PAI:** Zepp's own score. Keeping it at 100 or more over 7 days was linked to lower cardiovascular mortality in the HUNT study.
- **Training load:** a 7-day ("fatigue") and 42-day ("fitness") exponentially weighted average of daily workout load. The ratio is described as "in your usual range" or "above your usual". The app deliberately makes no injury-risk claims, because the ACWR literature doesn't support them.

## Today's focus

One sentence, picked by the first rule that matches:

1. Illness flag → rest.
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
