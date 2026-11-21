package app.baseline.ui.story

/** The editorial content behind every metric: why it matters, what to watch, how to move it. */
enum class Metric(val label: String) {
    Readiness("Readiness"), Hrv("HRV"), RestingHr("Resting heart rate"), Breathing("Breathing rate"),
    Sleep("Sleep"), Consistency("Sleep rhythm"),
    Steps("Steps"), Active("Active minutes"), Vo2("VO₂ max"), Load("Training load"),
    Stress("Stress"), Spo2("Night oxygen"), Weight("Weight"),
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
            "Match training to the score: push on green days, go easy on amber ones.",
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
        measured = "Zepp's daily resting heart rate (lowest stable value, mostly from sleep). Amber at +3 bpm, red at +5 bpm over your 60-day mean.",
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
        tagline = "How much your bedtime moves from night to night.",
        why = "Large studies (UK Biobank, over 60,000 people) found sleep regularity predicts mortality and heart disease at least as strongly as duration. An irregular schedule keeps your body clock in a constant mild jet lag.",
        lookFor = listOf(
            "Under ±30 minutes is excellent. Over ±60 minutes is worth working on.",
            "Weekend drift: late Friday and Saturday nights are the most common culprit.",
        ),
        improve = listOf(
            "Pick a wake time you can keep 7 days a week, within 30 minutes.",
            "Start a wind-down alarm about 45 minutes before your target bedtime.",
            "If you're short on sleep, go to bed earlier rather than sleeping in.",
        ),
        measured = "Circular standard deviation of bedtimes over the last 7–14 nights. This approximates the Sleep Regularity Index, which needs minute-level data.",
        reliability = Reliability.High,
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
        measured = "Counted by the watch's accelerometer, fused across devices by Zepp.",
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
        measured = "Firstbeat-style estimate from heart-rate response to pace during outdoor activities.",
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
