# Evidence base

Research notes for a minimal, action-oriented dashboard that sits on top of Amazfit/Zepp data (via ZeppBridge). Every claim has a citation. Thresholds marked **heuristic** have no validated cut-point behind them. They are design choices and should be labelled that way in the app.

---

## 1. Which metrics matter most

### Long-term health and longevity, ranked by strength of evidence

| Rank | Metric | Key evidence |
|---|---|---|
| 1 | **Cardiorespiratory fitness (VO2max)** | Each extra 1 MET of fitness goes with 13% lower all-cause mortality (RR 0.87) ([Kodama 2009, JAMA](https://pmc.ncbi.nlm.nih.gov/articles/PMC2951585)). Low vs elite fitness: adjusted HR 5.04, with no upper limit to the benefit ([Mandsager 2018, JAMA Netw Open](https://pmc.ncbi.nlm.nih.gov/articles/PMC6324439)). In about 750k veterans, the least fit had 4.09x the risk of the extremely fit ([Kokkinos 2022, JACC](https://www.tctmd.com/news/cardiorespiratory-fitness-key-longevity-regardless-age-sex-and-race)). |
| 2 | **Activity volume: steps and MVPA minutes** | The step benefit levels off around 6–8k/day for people 60 and over and 8–10k/day for people under 60 ([Paluch 2022, Lancet Public Health](https://pubmed.ncbi.nlm.nih.gov/35247352/)). 7k vs 2k steps/day: all-cause mortality HR 0.53, with modest gains above 7k ([Ding 2025, Lancet Public Health](https://pubmed.ncbi.nlm.nih.gov/40713949/)). WHO: 150–300 min/week moderate, or 75–150 min vigorous ([Bull 2020, BJSM](https://bjsm.bmj.com/content/54/24/1451)). Mortality benefit plateaus at 3–5x the minimum, with no harm at 10x ([Arem 2015, JAMA IM](https://pmc.ncbi.nlm.nih.gov/articles/PMC4451435)). A weekly PAI of 100 or more goes with 17–23% lower CVD mortality ([Nes 2017, Am J Med](https://www.ntnu.edu/cerg/pai)). |
| 3 | **Sleep regularity**, then duration | Regularity predicts mortality more strongly than duration. Top vs bottom quintile of the Sleep Regularity Index (SRI): fully adjusted HR 0.70 ([Windred 2024, Sleep](https://pubmed.ncbi.nlm.nih.gov/37995126/)). Adults should sleep 7 h or more ([Watson 2015, AASM/SRS](https://pubmed.ncbi.nlm.nih.gov/25979105/)). |
| 4 | **Resting HR** | RR 1.09 per +10 bpm. RR 1.45 for >80 bpm vs the lowest category ([Zhang 2016, CMAJ](https://www.cmaj.ca/content/188/3/E53)). |
| 5 | **HRV** | Lowest vs highest SDNN: RR 1.35 for a first CVD event ([Hillebrand 2013, Europace](https://scholarlypublications.universiteitleiden.nl/handle/1887/102345)). These are 24-h or clinical recordings, not nightly wrist rMSSD, so HRV is a weak longevity marker as a wearable reads it. |

**Zone 2** is not uniquely optimal. The evidence for higher intensities improving mitochondrial and cardiometabolic outcomes is larger ([Storoschuk 2025, Sports Med](https://pubmed.ncbi.nlm.nih.gov/40560504/)). Count intensity minutes rather than "zone 2 time".

### Day-to-day recovery decisions, ranked

1. **Nightly ln rMSSD vs your personal normal.** HRV-guided training matched or beat fixed plans in three trials ([Kiviniemi 2007](https://pubmed.ncbi.nlm.nih.gov/17849143/); [Vesterinen 2016](https://pubmed.ncbi.nlm.nih.gov/26909534/); [Javaloyes 2019](https://pubmed.ncbi.nlm.nih.gov/29809080/)).
2. **Resting HR vs your personal normal.** Read it together with HRV, using Buchheit's 2x2 table ([Buchheit 2014](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full)).
3. **Sleep duration over the last 1–3 nights.** Garmin uses last night's sleep plus a 3-night history ([Garmin manual](https://www8.garmin.com/manuals/webhelp/GUID-C144B465-A0C8-4FE9-AFE6-41A3FE3F1D9A/EN-US/GUID-C21BE0C8-A08E-4DA1-B6C6-2E0E2DDDB372.html)).
4. **Recent training load.** Use it as context, not as a predictor (see section 4).

> **Implement as**
> - **Home screen, "Today":** readiness (HRV, RHR, sleep), plus one action.
> - **Home screen, "This week":** intensity minutes vs 150, steps 7-day mean vs 7k, SRI.
> - **Monthly "Long game" card:** VO2max trend (90-day), RHR trend (90-day), steps and MVPA trend.
> - **Hidden by default:** SpO2, stress score, stage breakdown, calories.

---

## 2. Interpreting HRV and resting HR

- **Log-transform rMSSD.** Use ln rMSSD; its 7-day rolling average is the standard monitoring unit ([Plews 2012, EJAP](https://pubmed.ncbi.nlm.nih.gov/22367011/); [AIS HRV guidance](https://www.ais.gov.au/__data/assets/pdf_file/0013/1130260/HRV-BPG_FINAL.pdf)).
- **Average across days.** Use at least 3–4 days of data, never a single day ([Buchheit 2014](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full)).
- **Noise levels.** Day-to-day CV for ln rMSSD is 10–20%. Typical error is about 12% for ln rMSSD and about 10% for resting HR. The smallest worthwhile change (SWC) for positive adaptation is about +3% for ln rMSSD and about −2% for resting HR. SWC is defined as 0.5–1 × the individual CV ([Buchheit 2014](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full)).
- **SWC band in practice.** The common implementation is the baseline mean ± 0.5 SD of ln rMSSD ([Javaloyes in Rouleur](https://www.rouleur.cc/blogs/rouleur-performance/heart-rate-variability-all-you-need-to-know); [TrainingPeaks](https://www.trainingpeaks.com/blog/new-study-widens-hrv-evidence-for-more-athletes/)). Kiviniemi reduced intensity when HRV fell below the 10-day mean − 1 SD, or kept falling for 2 days ([Kiviniemi 2007](https://pubmed.ncbi.nlm.nih.gov/17849143/)).
- **Normal range.** HRV4Training uses a 60-day normal range around a 7-day baseline. Compare against that range, never against yesterday ([Altini](https://marcoaltini.substack.com/p/whats-your-normal-range-for-heart); [Altini, trends](https://medium.com/@altini_marco/heart-rate-variability-hrv-trends-going-beyond-daily-scores-d32609c1eddd)).
- **CV of HRV.** In an athlete heading into non-functional overreaching, both the 7-day ln rMSSD and its CV fell ([Plews 2012](https://pubmed.ncbi.nlm.nih.gov/22367011/)). Buchheit calls CV-based interpretation promising but speculative ([Buchheit 2014](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full)).
- **Reading HRV and RHR together:**
  - HRV up, RHR down: coping well.
  - HRV down, RHR up: sympathetic stress. That means accumulated fatigue, unless the person is tapering.
  - HRV down, RHR down: parasympathetic saturation. Fine in trained people unless it persists ([Buchheit 2014](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full)).
- **Oura's RHR thresholds.** Oura penalises a resting HR 3–5 bpm above the long-term average ([Oura](https://support.ouraring.com/hc/en-us/articles/360057791533)).

> **Implement as**
> - `x_t = ln(nightly rMSSD)`. Skip nights without a value; never impute.
> - `base7_t = mean(x over t−6..t)`. Valid only with 4 or more of 7 nights present.
> - `mu60, sd60` = mean and SD of daily `x` over t−60..t−1. Show the band only once 14 or more nights exist. Mark it "provisional" below 30 nights.
> - **HRV status, using `z7 = (base7 − mu60)/sd60`:**
>   - Within normal: `|z7| ≤ 0.5`.
>   - Below normal (amber): `z7 < −0.5`.
>   - Well below (red): `z7 < −1.0`, **or** 2 consecutive daily `x < mu60 − 1·sd60`.
>   - Above normal: `z7 > +0.5`. Show it in neutral blue, not as "better".
> - **RHR:** `base7` and `mu60 ± sd60` computed the same way, in raw bpm.
>   - Amber: today ≥ `mu60 + 3`.
>   - Red: today ≥ `mu60 + 5`, or `base7 ≥ mu60 + 3`.
> - **Combined flag:** "Strain signal" when HRV is amber or red **and** RHR is amber or red on the same day.
> - **Advanced card only:** `CV7 = sd(x, 7d)/mean(x, 7d)×100`. Show it, but don't drive actions from it.
> - **Units:** show HRV in ms, with the band underneath. Never compare it with population norms.

---

## 3. Sleep

- **Duration.** 7 h or more ([Watson 2015](https://pubmed.ncbi.nlm.nih.gov/25979105/)). For adults 26–64, 7–9 h is recommended and under 6 h is not ([Hirshkowitz 2015, NSF](https://pubmed.ncbi.nlm.nih.gov/29073398/)).
- **Regularity: what the SRI measures.** The SRI is the probability of being in the same sleep/wake state at minutes 24 h apart, scaled so that 100 is perfectly regular and 0 is random ([Phillips 2017, Sci Rep](https://pmc.ncbi.nlm.nih.gov/articles/PMC5468315)).
- **Regularity: reference values.** In UK Biobank the median SRI was 81. The quintile cut points were 71.7, 78.6, 83.2 and 87.3, computed from 1 week of data ([Windred 2024](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC10782501/fullTextXML)).
- **Efficiency and latency.** The NSF quality indicators are efficiency of 85% or more and sleep latency of 30 min or less. There was no consensus on sleep architecture as a marker of quality ([Ohayon 2017, Sleep Health](https://pubmed.ncbi.nlm.nih.gov/28346153/)).
- **Sleep stages from the wrist.** Agreement with PSG on stages is weak:
  - Epoch-by-epoch agreement is 50–65%, κ 0.20–0.52. Sleep/wake agreement is 86–89% ([Miller 2022, Sensors](https://pubmed.ncbi.nlm.nih.gov/36016077/)).
  - Consumer devices are best used for sleep/wake, not stages ([Chinoy 2022](https://www.doi.org/10.2147/nss.s348795); [Chinoy 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8120339)).
- **Wake detection, and why efficiency is inflated.** Wearables detect wake poorly, with specificity of about 0.41–0.82 ([de Zambotti review](https://pmc.ncbi.nlm.nih.gov/articles/PMC6579636)). Missed wake time gets counted as sleep, so efficiency comes out too high.

> **Implement as**
> - **Total sleep time (TST)** = sum of non-awake stage minutes per main sleep session, plus naps of 20 min or more. Show last night and the 7-day mean.
>   - Green: 7–9 h.
>   - Amber: 6–7 h, or more than 9 h on 3 or more of the last 7 nights.
>   - Red: under 6 h.
> - **SRI:**
>   - Rasterise each day into 1440 minute-epochs (`s = 1` asleep, `0` awake).
>   - `SRI = −100 + 200/(M·(N−1)) · Σ_{j=1}^{M(N−1)} δ(s_j, s_{j+M})`, with `M = 1440`, `N` = days, and `δ = 1` when the two states match ([Phillips 2017](https://pmc.ncbi.nlm.nih.gov/articles/PMC5468315)).
>   - Use a rolling 7-day window. Require 5 or more nights with no sleep-data gap longer than 4 h.
>   - Green: ≥ 83 (above the median of the 3rd quintile).
>   - Amber: 72–83.
>   - Red: < 72 (bottom quintile) (Windred 2024).
> - **Explainer for SRI:** the 7-day SD of sleep midpoint and of wake time, shown as "your wake time varied by ±X min". Bands, **heuristic**: green ≤ 30 min, amber 30–60, red > 60.
> - **Efficiency** = TST / (session end − start). Show it only in the detail view, with "≥85%" as a reference line and a note that the device overestimates it.
> - **Stages:** detail view only. No targets, no colours, no score. Add a caption: "stage estimates from the wrist are approximate".

---

## 4. Training load

- **Banister / TrainingPeaks model.**
  - CTL is a 42-day exponentially weighted average of daily load; ATL is the 7-day equivalent.
  - TSB = yesterday's CTL − yesterday's ATL ([TrainingPeaks TSB](https://help.trainingpeaks.com/hc/en-us/articles/204071764-Form-TSB); [glossary](https://www.trainingpeaks.com/learn/articles/glossary-of-trainingpeaks-metrics/)).
- **Zepp's own load.** Zepp computes a per-workout load from EPOC, and Training Load is the 7-day sum ([Amazfit](https://us.amazfit.com/pages/amazfit-technology-page-running-technology)).
- **Edwards TRIMP.** Minutes in each of 5 %HRmax zones, multiplied by linear weights. The weights are arbitrary ([review](https://arxiv.org/pdf/2503.14841)).
- **ACWR is contested.** The acute:chronic workload ratio was popularised as an injury predictor ([Gabbett 2016, BJSM](https://bjsm.bmj.com/content/50/5/273.abstract)). Critics point to:
  - Mathematical coupling: the acute week sits inside the chronic window, inflating the correlation to r ≈ 0.5 ([Lolli 2019, BJSM](https://bjsm.bmj.com/content/53/15/921)).
  - Ratio artefacts, and no causal basis for manipulating the ratio to prevent injury ([Impellizzeri 2020, IJSPP](https://doi.org/10.1123/ijspp.2019-0864); [Impellizzeri 2020, J Athl Train](https://pmc.ncbi.nlm.nih.gov/articles/PMC7534938/)).

  Defensible use: a descriptive "this week vs your usual". It is not an injury-risk score, and "sweet-spot" bands should not be shown as validated.
- **Health-relevant load.** Weekly intensity minutes measured against WHO's 150–300 ([Bull 2020](https://bjsm.bmj.com/content/54/24/1451)). ACSM defines moderate as 64–76% HRmax and vigorous as 77–95% ([Garber 2011, MSSE](https://pubmed.ncbi.nlm.nih.gov/21694556/)).

> **Implement as**
> - **Headline:** weekly intensity minutes = `moderate_min + 2 × vigorous_min`, rolling 7 days, from per-minute HR. Moderate is 64–76% HRmax; vigorous is ≥ 77%. Use the user's observed HRmax, or 208 − 0.7·age when it is unknown. Count only bouts of 2 min or more (**heuristic**, to suppress HR noise).
>   - Grey: < 75.
>   - Amber: 75–149.
>   - Green: 150–300.
>   - Blue "bonus": > 300. Benefit continues to about 3–5x the minimum (Arem 2015).
> - **Show PAI** if available, with a target of ≥ 100 per 7 days (Nes 2017).
> - **Load trend (secondary):**
>   - `daily_load` = Zepp workout load, or Edwards TRIMP = Σ min_in_zone_i × i, with zones 50–60/60–70/70–80/80–90/90–100% HRmax.
>   - `CTL_t = CTL_{t−1} + (L_t − CTL_{t−1})/42` and `ATL_t = ATL_{t−1} + (L_t − ATL_{t−1})/7`.
>   - Show both as lines. Label TSB as "freshness".
> - **Uncoupled weekly change** = `sum(L, last 7d) / mean(weekly sum over the prior 3 weeks)`. Show it as text, e.g. "40% more than your usual". Flag ≥ 1.5 in amber as **heuristic**, never as "injury risk".
> - **Don't show:** an ACWR "sweet spot" gauge, or zone-2 targets.

---

## 5. Composite readiness

- **Oura.** Up to 9 contributors, including RHR vs long-term average (penalised at 3–5 bpm above), HRV balance (14 days vs 3 months), temperature deviation, sleep, sleep balance, regularity and activity balance. It takes up to 2 weeks to learn baselines. Bands: 85+, 70–84, 60–69, and < 60 ([Oura support](https://support.ouraring.com/hc/en-us/articles/360057791533)).
- **Whoop.** HRV, RHR, respiratory rate, SpO2, skin temperature and sleep performance. HRV carries most of the weight ([Whoop](https://whoop.com/thelocker/how-does-whoop-recovery-work-101)).
- **Garmin Training Readiness.** Sleep score, recovery time, HRV status, acute load, 3-night sleep history and 3-day stress history, with 5 bands ([Garmin](https://www8.garmin.com/manuals/webhelp/GUID-C144B465-A0C8-4FE9-AFE6-41A3FE3F1D9A/EN-US/GUID-C21BE0C8-A08E-4DA1-B6C6-2E0E2DDDB372.html)).
- **What the vendors don't publish.** None of them publish weights, and none has peer-reviewed outcome validation of the composite. The interventions that do have trial evidence used transparent HRV-vs-baseline rules (Kiviniemi 2007; Vesterinen 2016; Javaloyes 2019).
- **The case for transparency.** A transparent composite lets each contributor explain the result. That is needed for the "why" and the "what to do", and it avoids an unexplained number driving anxiety (section 6).

> **Implement as** (weights are **heuristic**: HRV-dominant, following Whoop)
> - **Components**, each clipped to [−3, 3]:
>   - `zH = (x_today − mu60)/sd60` (ln rMSSD).
>   - `zR = −(RHR_today − mu60_RHR)/sd60_RHR`.
>   - `zS = (min(TST_last, 9h) − mu60_TST)/sd60_TST − 0.5·max(0, (7h − TST_3nightmean)/1h)`. The second term is a sleep-debt penalty.
> - **Combined:** `R = 0.5·zH + 0.3·zR + 0.2·zS`.
> - **States:**
>   - **Ready (green):** `R ≥ −0.5` and no component < −1.5.
>   - **Take it easier (amber):** `−1.5 ≤ R < −0.5`, or any single component < −1.5.
>   - **Recover (red):** `R < −1.5`, or the HRV-down plus RHR-up strain signal on 2 consecutive days (Kiviniemi 2-day rule; Buchheit table).
>   - **Insufficient data (grey):** fewer than 14 nights of baseline, or tonight's HRV/RHR missing.
> - **Optional 0–100 display:** `score = clamp(70 + 15·R, 0, 100)`. Always show the three contributor arrows beside it.
> - **Exclusions:** leave Zepp's own readiness score out of the composite. Show it for comparison only.

---

## 6. Dashboard and behaviour-change UX

- **Pair self-monitoring with other techniques.** Self-monitoring combined with at least one other self-regulation technique (goal setting, feedback, action planning) roughly doubled effect sizes: 0.42 vs 0.26 ([Michie 2009, Health Psychol](https://pubmed.ncbi.nlm.nih.gov/19916637/)). Showing data alone is not enough; pair each metric with a goal and an action.
- **Trackers work for activity.** Activity trackers increase activity by roughly 1,800 steps/day (SMD 0.3–0.6) ([Ferguson 2022, Lancet Digit Health](https://pubmed.ncbi.nlm.nih.gov/35868813/)).
- **Design for the reflection-to-action step.** Personal informatics breaks down at reflection→action ([Li 2010](https://courses.cs.washington.edu/courses/cse440/15au/readings/PersonalInformatics-Li2010.pdf)).
- **Expect lapses.** People lapse and resume tracking ([Epstein 2015](https://my.eng.utah.edu/~cs5540/au16/readings/PersonalInformatics-Epstein2015.pdf)), so missing days must degrade gracefully and never shame.
- **Compare against the personal range.** Compare with personal normal ranges, not yesterday or population norms ([Altini](https://marcoaltini.substack.com/p/whats-your-normal-range-for-heart); [Oura](https://support.ouraring.com/hc/en-us/articles/360057791533)).
- **Smooth noisy series.** Use an exponentially smoothed trend: `T_n = T_{n−1} + 0.1·(W_n − T_{n−1})` ([Walker, Hacker's Diet](https://www.fourmilab.ch/hackdiet/www/subsection1_4_1_0_1.html)).
- **Watch for anxiety and over-checking.** Patients chasing tracker numbers spent more time in bed ("orthosomnia") ([Baron 2017, JCSM](https://pubmed.ncbi.nlm.nih.gov/27855740/)). Wearable users with AF showed more symptom preoccupation, and about 1 in 5 reported intense fear after alerts ([Rosman 2024, JAHA](https://pubmed.ncbi.nlm.nih.gov/39011944/)).
- **Accessibility.** Don't convey meaning by colour alone (WCAG 1.4.1). Text contrast must be ≥ 4.5:1 (WCAG 1.4.3) ([W3C WCAG 2.1](https://www.w3.org/TR/WCAG21/)).

> **Implement as**
> - **Home screen:** at most 3 cards (Today readiness, This week, Long game), plus **one action sentence**.
> - **Action rules:**
>   - Red: "Easy day: walk or mobility only, aim for bed by `usual_bedtime − 30 min`."
>   - Amber: "Keep it moderate, skip intervals."
>   - Green and intensity minutes < 150: "Good day for a harder session (~X min to hit 150)."
>   - Green and goal met: "Maintain; protect your wake time."
>   - Low SRI overrides the others on rest days: "Get up within 30 min of `median_wake` tomorrow."
> - **Every metric:** a value, its band vs your normal, a 30-day sparkline (7-day mean or 0.1-EWMA), and one sentence on "what this means".
> - **Colour semantics:**
>   - Green = within or above goal.
>   - Amber = sustained deviation.
>   - Red = multi-signal or 2-day deviation only.
>   - Neutral blue = above normal.
>   - Grey = insufficient or unreliable data.
>   - Pair every colour with an icon/arrow and a word.
>   - Use a colour-blind-safe palette; avoid relying on red vs green alone.
> - **Notifications:** none for a single bad night or a single metric. Notify only on a 2-day strain signal or a weekly summary.
> - **Weight:** if present, show only the 0.1-EWMA trend and the weekly change in trend; hide raw daily values.
> - **Missing data:** grey "no data" state, no zeros, no streak-breaking guilt.

---

## 7. Accuracy caveats (what to de-emphasise)

| Metric | Evidence | App treatment |
|---|---|---|
| HR at rest and in steady activity | 6 of 7 wrist devices had < 5% error ([Shcherbina 2017](https://med.stanford.edu/news/all-news/2017/05/fitness-trackers-accurately-measure-heart-rate-but-not-calories-burned.html)). Mi Band (a Huami-made sibling of Amazfit) underestimated HR and gave occasional extreme errors ([JMIR mHealth 2020](https://mhealth.jmir.org/2020/4/e14707)). Accuracy drops at high intensity ([Mi Band studies](https://pubmed.ncbi.nlm.nih.gov/32343255/)). | Trust it for RHR and intensity minutes. Filter spikes. |
| HRV (nightly) | Device bias vs ECG of −4 to −22 ms; ICC 0.24–0.99 ([Miller 2022](https://pubmed.ncbi.nlm.nih.gov/36016077/)). rMSSD error varies widely between devices ([Stone 2021](https://www.frontiersin.org/articles/10.3389/fspor.2021.585870/full)). | Within-person trends only, never population norms. |
| Sleep duration and timing | Sleep/wake agreement 86–89%; TST bias −12 to +44 min ([Miller 2022](https://pubmed.ncbi.nlm.nih.gov/36016077/)). | Primary sleep metrics. |
| Sleep stages | κ 0.20–0.52 ([Miller 2022](https://pubmed.ncbi.nlm.nih.gov/36016077/)). Use for sleep/wake, not stages ([Chinoy 2022](https://www.doi.org/10.2147/nss.s348795)). | Detail view only, no targets. |
| SpO2 | Accuracy and missing data vary a lot by device ([2023 smartwatch SpO2 study](https://pmc.ncbi.nlm.nih.gov/articles/PMC10337940)). The wrist has lower perfusion and worse signal-to-noise ([Frontiers Digit Health 2023](https://www.frontiersin.org/articles/10.3389/fdgth.2023.1258915)). | Hidden. Show only nights with a mean < 90% (**heuristic**), as "consider a medical check", never as a diagnosis. |
| VO2max estimate | Fine at group level, but individual error is large. Resting-based estimates have limits of agreement of about −13 to +17 ml/kg/min ([Molina-Garcia 2022, Sports Med](https://link.springer.com/10.1007/s40279-021-01639-y)). Amazfit estimates only from flat, low-altitude outdoor runs with HR ([Amazfit](https://us.amazfit.com/pages/amazfit-technology-page-running-technology)). | Show a 90-day trend; don't react to single values. Mark it stale if there has been no qualifying run in the last 30 days. |
| Calories | Energy expenditure is poor on every device tested ([Shcherbina 2017](https://pubmed.ncbi.nlm.nih.gov/28538708/)). An Amazfit band had 26.4% error vs a metabolic chamber ([JMIR mHealth 2021](https://mhealth.jmir.org/2021/1/e18320)). | Don't show on the home screen. |
| Zepp stress score, Zepp readiness | No independent validation found. | Hidden, or comparison-only. |
| Amazfit specifically | No independent PSG- or ECG-validated study of current Amazfit models was found. Zepp cites its own sponsored AF study ([Zepp](https://ir.zepp.com/technology)). | Treat all Amazfit-derived values as within-person trend signals. |

> **Implement as**
> - **Reliability tier per metric:**
>   - A: HR, RHR, steps, TST, sleep timing.
>   - B: HRV, intensity minutes, workout load, VO2max trend.
>   - C: stages, SpO2, stress, calories, readiness.
> - **Home screen:** tier A/B only. Tier C goes in the detail view, behind a "less reliable" label.
> - **Cleaning:**
>   - Reject per-minute HR outside 30–220 bpm.
>   - Reject nightly HRV values outside `mu60 ± 4·sd60` as artefacts (**heuristic**).
>   - Treat a night as missing if the sleep session is < 3 h with no HRV.
