package app.baseline.ui.story

/** The editorial content behind every metric: why it matters, what to watch, how to move it. */
enum class Metric(val label: String) {
    Readiness("Readiness"), Hrv("HRV"), RestingHr("Resting heart rate"), Breathing("Breathing rate"),
    Sleep("Sleep"), Consistency("Sleep rhythm"),
    Steps("Steps"), Active("Active minutes"), Vo2("VO₂ max"), Load("Training load"),
    Stress("Stress"), Spo2("Night oxygen"), Weight("Weight"),
    // Phase 3
    Signals("Overnight signals"), HrvTrend("HRV trend"), HrDip("Overnight low"),
    SleepNeed("Sleep need"), SleepTiming("Sleep timing"), Zones("Time in zones"), Movement("Movement today"),
}

enum class Reliability(val label: String) {
    High("Measured well by your watch"),
    Medium("Good for trends, not absolute values"),
    Low("Rough estimate — look at patterns only"),
}

data class Story(
    val tagline: String,
    val why: String,
    val lookFor: List<String>,
    val improve: List<String>,
    val measured: String,
    val reliability: Reliability,
)

fun storyOf(m: Metric): Story = when (m) {
    Metric.Readiness -> Story(
        tagline = "How ready your body is to take on stress today.",
        why = "Your nervous system shows how well you've recovered. Baseline combines last night's HRV, your resting heart rate and how much you slept, each compared with your own 60-day normal. You can see exactly why the number is what it is.",
        lookFor = listOf(
            "The contributors matter more than the score. A low sleep line is a different story from a low HRV.",
            "One low day is noise. Two or three in a row, especially with resting HR up, is a signal.",
            "High readiness on a rest day is fine. It means the rest is working.",
        ),
        improve = listOf(
            "Protect sleep duration and a regular bedtime. Together they're the biggest lever.",
            "Alcohol within 3 hours of bed drops next-morning HRV for most people.",
            "Match training to the score: push on Push and Ready days, go easy on Easy and Recover days.",
            "Eat enough on hard training days. Under-fuelling shows up as a falling HRV.",
        ),
        measured = "50% HRV, 30% resting HR, 20% sleep, as z-scores against your last 60 days. Unlocks after 14 nights.",
        reliability = Reliability.Medium,
    )
    Metric.Hrv -> Story(
        tagline = "The variation between heartbeats while you sleep — your recovery barometer.",
        why = "A rested nervous system lets your heart rate flex beat to beat. HRV rises as you adapt to training and falls with stress, illness, alcohol, poor sleep or overreaching. It's very personal, so the only meaningful comparison is with your own normal range.",
        lookFor = listOf(
            "The 7-day line staying inside your shaded band means you're absorbing the load.",
            "Several days below the band calls for less intensity and more sleep.",
            "A steady rise over months usually means your fitness is improving.",
        ),
        improve = listOf(
            "Sleep 7–9 hours at consistent times.",
            "Build training gradually, and keep easy days easy.",
            "Limit alcohol, especially late in the evening.",
            "Slow breathing (about 6 breaths a minute for 5–10 minutes) gives a short-term boost and builds the habit of downshifting.",
        ),
        measured = "Your watch's overnight HRV (rMSSD-type, in ms). Baselines are computed on the log scale, the standard method in sport science.",
        reliability = Reliability.Medium,
    )
    Metric.RestingHr -> Story(
        tagline = "Your lowest steady heart rate — fitness over months, strain over days.",
        why = "Over months, a lower resting heart rate means a stronger, more efficient heart, and it's linked to lower mortality. Over days, a jump of a few beats above your normal is one of the earliest signs of illness, poor sleep, heat, alcohol or accumulated fatigue.",
        lookFor = listOf(
            "3 or more bpm above your normal for a day or two means ease off and sleep more.",
            "Up together with a higher breathing rate, it often means you're coming down with something.",
            "A slow downward drift over months means your aerobic fitness is improving.",
        ),
        improve = listOf(
            "Regular aerobic training, mostly at an easy, conversational pace.",
            "Consistent sleep, and hydration on hot days.",
            "Less alcohol and late heavy meals. Both raise overnight heart rate.",
        ),
        measured = "Zepp's daily resting heart rate (lowest stable value, mostly from sleep). Flagged at +3 bpm, and as needing rest at +5 bpm, over your 60-day mean.",
        reliability = Reliability.High,
    )
    Metric.Breathing -> Story(
        tagline = "Breaths per minute while you sleep.",
        why = "Your sleeping breathing rate is remarkably stable from night to night. That makes it a sensitive early-warning signal: it often rises a day before you feel ill, and when you've had alcohol or slept at altitude.",
        lookFor = listOf(
            "A rise of 1 or more breaths per minute above your usual, especially with resting HR up.",
            "Small night-to-night wobbles are normal. Ignore them.",
        ),
        improve = listOf(
            "There's nothing to train here. Treat a rise as a cue to rest, hydrate and sleep early.",
        ),
        measured = "Derived by the watch from heart-rate and motion signals during sleep.",
        reliability = Reliability.Medium,
    )
    Metric.Sleep -> Story(
        tagline = "Time actually asleep — the foundation everything else sits on.",
        why = "Adults need 7–9 hours. Regularly sleeping under 7 is linked to higher risk of heart disease, diabetes and depression, and to worse mood, focus and training. Short sleep also shows up in your HRV and resting HR the next morning.",
        lookFor = listOf(
            "A 7-night average at or near your need.",
            "Sleep debt above about 3 hours a week. That's when you start to feel it.",
            "Duration matters more than stages. Wrist trackers estimate stages poorly, so don't chase deep-sleep percentages.",
        ),
        improve = listOf(
            "Fix your wake-up time first, every day including weekends. Bedtime follows.",
            "Count back from your wake time: your need plus about 15 minutes to fall asleep.",
            "Get bright daylight within an hour of waking. Keep the evening dim, cool and screen-light.",
            "No caffeine after early afternoon, and finish alcohol at least 3 hours before bed.",
        ),
        measured = "Total time in light, deep and REM sleep from your watch's main sleep each night. Naps are not counted.",
        reliability = Reliability.High,
    )
    Metric.Consistency -> Story(
        tagline = "How alike your days are: asleep and awake at the same times, day after day.",
        why = "Large studies (UK Biobank, over 60,000 people) found sleep regularity predicts mortality and heart disease at least as strongly as duration. An irregular schedule keeps your body clock in a constant mild jet lag.",
        lookFor = listOf(
            "83 or more is regular. 72–83 is fair. Under 72 means your days look quite different from each other.",
            "Weekend drift: late Friday and Saturday nights are the most common culprit.",
            "Bedtime and wake-time spread (± minutes) explains what's moving the score.",
        ),
        improve = listOf(
            "Pick a wake time you can keep 7 days a week, within 30 minutes.",
            "Start a wind-down alarm about 45 minutes before your target bedtime.",
            "If you're short on sleep, go to bed earlier rather than sleeping in.",
        ),
        measured = "Sleep Regularity Index over the last 14 days: the chance you're in the same state (asleep or awake) at any minute and the same minute 24 hours later, scaled so 100 is perfectly regular and 0 is random. Built minute by minute from your sleep-stage timelines, naps included; needs at least 7 recorded days. Bedtime spread is the circular standard deviation of bedtimes.",
        reliability = Reliability.High,
    )
    Metric.Signals -> Story(
        tagline = "Four overnight signals that tend to shift together when your body is under strain.",
        why = "When you're coming down with something, several things change at once overnight: resting heart rate rises, breathing quickens, HRV drops and skin temperature drifts. Any one of them moves for ordinary reasons. Two or more out of their usual range for two nights in a row is a pattern worth listening to. Studies of wearables during COVID-19 and flu found this kind of shift often shows up a day or two before symptoms.",
        lookFor = listOf(
            "Two or more signals off for two nights running. That's when Baseline speaks up.",
            "A single night is usually noise: a late meal, a drink, a hot room.",
            "A hard workout the day before can tip the same signals. Baseline mentions it when that's likely.",
        ),
        improve = listOf(
            "When signals are off, rest comes first: skip intensity, hydrate, and get to bed early.",
            "Give it a day or two. If you feel unwell or it keeps going, talk to a doctor.",
            "Wear your watch every night so your usual range stays accurate.",
        ),
        measured = "Each night is compared with your previous 28 nights (leaving out nights that were already off): resting HR at +1.5 SD or more, breathing at +1 breath/min or more, HRV (log scale) at −1 SD or less, and Zepp's skin-temperature score in your lowest 10%. Needs 14 nights of history. Not a diagnosis.",
        reliability = Reliability.Medium,
    )
    Metric.HrvTrend -> Story(
        tagline = "Your HRV smoothed over a week, plus how much it swings day to day.",
        why = "A single night's HRV is noisy. The weekly average is a steadier read of how you're coping with training and life, and how much it bounces around adds a second clue. Sport scientists found that when the swings grow while the weekly average falls, fatigue is often building before you feel it.",
        lookFor = listOf(
            "The 7-day line inside your normal range: you're absorbing the load.",
            "Swings (CV) rising while the line drifts down: time for a few easier days.",
            "A line rising above your range after a rest week is a good sign.",
        ),
        improve = listOf(
            "Wear the watch at least 3 nights a week; fewer than that and the weekly average isn't reliable.",
            "Keep easy days truly easy, and bedtimes regular.",
            "Back off for a few days when the fatigue note appears, then rebuild.",
        ),
        measured = "Mean of the last 7 nights' ln(HRV) (at least 3 nights), shown back in ms, against a band of ±0.5 SD from the 60 days before. CV = SD ÷ mean × 100 of the same 7 values, compared with its median over the previous 4 weeks.",
        reliability = Reliability.Medium,
    )
    Metric.HrDip -> Story(
        tagline = "The lowest your heart rate went last night, and when.",
        why = "During good sleep your heart rate settles to its lowest point, usually in the first half of the night. Alcohol, a late heavy meal, late exercise or illness keep it higher for longer, so the low comes later or doesn't go as deep.",
        lookFor = listOf(
            "Early dips usually mean better recovery.",
            "Late dips often follow alcohol or a late meal. That's a rule of thumb, not a certainty.",
            "Your own pattern over weeks matters more than any single night.",
        ),
        improve = listOf(
            "Finish eating about 3 hours before bed.",
            "Keep alcohol early in the evening, or skip it on nights you want to recover.",
            "Finish hard workouts a few hours before bedtime when you can.",
        ),
        measured = "The minimum of a 5-minute rolling average of your per-minute heart rate during the main sleep, sent by the bridge for recent nights; older nights use hourly minimums, timed to the nearest hour. The early/late reading is a heuristic.",
        reliability = Reliability.Medium,
    )
    Metric.SleepNeed -> Story(
        tagline = "How much sleep your body seems to want, and whether you're keeping up.",
        why = "Most adults need 7–9 hours, but your own need sits somewhere specific in that range. On free days, without an alarm, you tend to sleep closer to what you need, so those nights are the best clue. Running short for several nights adds up, and it shows in mood, focus and recovery.",
        lookFor = listOf(
            "The shortfall line easing over two weeks means you're catching up.",
            "A shortfall that keeps building is the cue for a couple of earlier nights.",
            "Free days much longer than work days hint that weekday nights are too short.",
        ),
        improve = listOf(
            "Go to bed 20–30 minutes earlier on work nights rather than sleeping in.",
            "Short naps (under 30 minutes, before mid-afternoon) can help on short nights.",
            "Keep your wake time steady; catch-up sleep works best as earlier bedtimes.",
        ),
        measured = "Need = median total sleep (main sleep plus naps) on free days, the nights ending Saturday or Sunday, over the last 14 nights; needs 3 such nights, otherwise your setting is used. Kept within 6h 30m–9h 30m. The chart is the shortfall against that need, summed over the previous 7 nights with data.",
        reliability = Reliability.Medium,
    )
    Metric.SleepTiming -> Story(
        tagline = "The middle of your night, and how far it moves on weekends.",
        why = "The midpoint of your sleep is a simple marker of your body clock. When weekends run much later than weekdays, your body lives in two time zones. Researchers call this social jet lag, and it's linked to poorer mood, metabolic health and sleepiness on Mondays.",
        lookFor = listOf(
            "A weekend shift under an hour is easy on your body clock.",
            "Over an hour, Monday mornings tend to feel like jet lag.",
        ),
        improve = listOf(
            "Keep weekend wake-up within about an hour of your weekday time.",
            "Get outdoor light soon after waking, especially on weekends.",
            "If you're sleeping in to catch up, try an earlier bedtime on Thursday and Friday instead.",
        ),
        measured = "Midpoint = halfway between falling asleep and waking, for your main sleep. Social jet lag = average midpoint on free days (nights ending Saturday or Sunday) minus work days (Monday to Friday) over 28 nights, with at least 4 free and 8 work nights. Chronotype follows the MCTQ correction for catch-up sleep, adapted to watch data.",
        reliability = Reliability.High,
    )
    Metric.Zones -> Story(
        tagline = "How your training time splits between easy, steady-hard and hard.",
        why = "Endurance fitness is built mostly on easy time, with a smaller amount of hard work on top. Seeing the split helps you notice weeks that drift into \"everything medium\", which tires you out without adding much fitness.",
        lookFor = listOf(
            "Plenty of easy time most weeks.",
            "Some hard minutes if you're training for fitness. A little goes a long way.",
            "There's no magic ratio for everyday exercisers. Elite 80/20 splits don't need copying.",
        ),
        improve = listOf(
            "Slow your easy sessions down until talking in full sentences is comfortable.",
            "Keep hard sessions short and clearly hard, then recover.",
        ),
        measured = "Heart-rate zone time from your workouts over the last 7 days: zones 1–2 = low, zone 3 = threshold, zones 4–5 = high. Wrist heart rate lags behind short intervals and can read low at high effort.",
        reliability = Reliability.Medium,
    )
    Metric.Movement -> Story(
        tagline = "A plain suggestion for today: how many minutes, and how hard.",
        why = "Scores and strain targets are hard to act on. Baseline turns your recovery, your recent training and your weekly target into a simple range of minutes, so you can fit movement into the day without guessing.",
        lookFor = listOf(
            "Gentle and easy days are part of the plan, not a step back.",
            "On well-recovered days with time to spare, the upper end is fair game.",
            "Your own feeling counts too. If the suggestion doesn't match how you feel, go with your body.",
        ),
        improve = listOf(
            "Walking counts. Brisk walks fill most easy days.",
            "Spread the week's minutes over 3 or more days.",
        ),
        measured = "Readiness sets the base range (recover 10–20, easier 20–40, normal 30–60, primed 45–75 minutes). It drops a notch when this week's load is more than 1.3× your usual, and becomes \"up to 20 minutes gentle\" when your overnight signals are off. A rule of thumb, not a prescription.",
        reliability = Reliability.Low,
    )
    Metric.Steps -> Story(
        tagline = "The simplest measure of how much you move.",
        why = "Each extra 1,000 daily steps lowers mortality risk. The benefit levels off around 8–10k a day for adults under 60 and around 6–8k for older adults. Pace helps too, but total volume is what matters most.",
        lookFor = listOf(
            "Your 7-day average, not single days.",
            "Long sitting stretches. Even 2–3 minutes of walking every hour helps blood sugar and the back.",
        ),
        improve = listOf(
            "Attach walks to routines: calls, after meals (10 minutes after dinner works well), the commute.",
            "Take stairs, park far, get off one stop early. The small extras add up.",
            "Raise your goal by about 1,000 once you hit it most days.",
        ),
        measured = "Counted by the watch's accelerometer, fused across devices by Zepp. With your birth year in Settings, Baseline shows the age-based range where benefits level off (Paluch 2022): 8–10k under 60, 6–8k from 60.",
        reliability = Reliability.High,
    )
    Metric.Active -> Story(
        tagline = "Minutes your heart was working — the WHO way.",
        why = "The WHO recommends 150–300 minutes of moderate activity a week, or half that if vigorous, with vigorous minutes counting double. Meeting it is linked to about 30% lower all-cause mortality. Above 300 adds a little more benefit.",
        lookFor = listOf(
            "The rolling 7-day total against your weekly target.",
            "Spread across 3 or more days beats one long weekend session.",
        ),
        improve = listOf(
            "Brisk walking counts. Aim for a pace where talking takes effort.",
            "Add two 20–30 minute sessions of something that raises your breathing.",
            "Do two strength sessions a week as well. They're a separate, equally important recommendation.",
        ),
        measured = "Moderate-zone minutes plus 2 × vigorous-zone minutes from your watch's heart-rate zones.",
        reliability = Reliability.Medium,
    )
    Metric.Vo2 -> Story(
        tagline = "Your cardiorespiratory fitness — the strongest single predictor of longevity.",
        why = "Higher fitness is linked to dramatically lower mortality: each 1-MET gain cuts risk by about 13%, and the least-fit fifth of people carry several times the risk of the fittest. It responds to training at any age.",
        lookFor = listOf(
            "The trend over 2–3 months. Watch estimates can be about 10% off in absolute terms.",
            "Estimates need outdoor runs or walks with GPS and heart rate. Treadmill-only weeks may not update it.",
        ),
        improve = listOf(
            "Most of the gain comes from consistent easy aerobic work, 3–4 hours a week.",
            "Add one session of intervals, for example 4 × 4 minutes hard with 3 minutes easy between, once a week.",
            "Losing excess weight raises VO₂max per kg directly.",
        ),
        measured = "Firstbeat-style estimate from heart-rate response to pace during outdoor activities. With your birth year and sex in Settings, Baseline compares your 90-day median with FRIEND registry treadmill tests for your age group, as a percentile band covering ±3.5 mL/kg/min of watch error.",
        reliability = Reliability.Medium,
    )
    Metric.Load -> Story(
        tagline = "How much training stress you've been absorbing.",
        why = "Fitness builds when training load rises gradually and you recover from it. The two lines compare this week (7-day average) with your usual (42-day average). That's useful for pacing increases, though research doesn't support it as a precise injury predictor.",
        lookFor = listOf(
            "This week far above your usual for more than a week, especially with HRV falling.",
            "Long stretches well below usual mean you're detraining.",
        ),
        improve = listOf(
            "Increase weekly volume by about 10% or less, and plan an easier week every 3–4 weeks.",
            "Keep about 80% of sessions easy and about 20% hard.",
        ),
        measured = "Your watch's per-workout training load, summed per day. 7- and 42-day exponential averages.",
        reliability = Reliability.Medium,
    )
    Metric.Stress -> Story(
        tagline = "Zepp's daytime stress score from your heart rhythm.",
        why = "It's a rough proxy for nervous-system load during the day. The useful part is the pattern: which days and hours run hot, and whether stress follows you into the evening.",
        lookFor = listOf(
            "Evenings that stay high. They usually mean poorer sleep that night.",
            "Weeks trending up alongside a falling HRV.",
        ),
        improve = listOf(
            "Short walks outside, and 2–5 minutes of slow breathing between demanding blocks.",
            "A firm finish to the workday, and less caffeine after midday.",
        ),
        measured = "Zepp's proprietary 0–100 score derived from HRV during the day.",
        reliability = Reliability.Low,
    )
    Metric.Spo2 -> Story(
        tagline = "How well your blood stays oxygenated overnight.",
        why = "Repeated dips in oxygen during sleep can point to disordered breathing such as sleep apnoea, which is common and treatable. A watch can't diagnose it, but a consistent pattern is worth raising with a doctor.",
        lookFor = listOf(
            "Repeated low scores, or an oxygen-desaturation index (ODI) of 5 or more events an hour, over many nights.",
            "Ignore single bad nights. A loose strap causes false dips.",
        ),
        improve = listOf(
            "Wear the watch snugly above the wrist bone at night.",
            "If you snore loudly, wake unrefreshed or the pattern repeats, talk to a doctor about a sleep study.",
        ),
        measured = "Optical wrist SpO₂ during sleep. Noisy, so read the pattern rather than single values.",
        reliability = Reliability.Low,
    )
    Metric.Weight -> Story(
        tagline = "Body weight — read the trend, not the daily number.",
        why = "Daily readings swing 1–2 kg with water, salt and food. A smoothed trend line shows what's actually happening, so you can react to real change rather than noise.",
        lookFor = listOf(
            "The trend line's direction over 2–4 weeks.",
            "Sustainable change is about 0.25–0.75 kg a week.",
        ),
        improve = listOf(
            "Weigh at the same time, after waking and before eating.",
            "Pair any weight goal with protein and strength training to keep muscle.",
        ),
        measured = "Readings from a Zepp-connected scale. The trend is an exponential moving average (the Hacker's Diet method).",
        reliability = Reliability.High,
    )
}
