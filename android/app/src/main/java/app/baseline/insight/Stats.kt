package app.baseline.insight

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Small, dependency-free statistics used by the insight engine. */
object Stats {
    fun mean(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sum() / xs.size

    fun sd(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Exponentially weighted moving average; [alpha] = 1 - exp(-1/timeConstant) for a time constant in samples. */
    fun ewma(xs: List<Double?>, alpha: Double, seed: Double? = null): List<Double?> {
        var s: Double? = seed
        return xs.map { x ->
            if (x != null) s = s?.let { it + alpha * (x - it) } ?: x
            s
        }
    }

    /**
     * Time-aware EWMA over a daily series (index = day): each reading pulls the trend by 1 − e^(−gap/τ), so
     * sparse readings after a long gap aren't drowned by stale history. Days before the first reading stay null.
     */
    fun timeEwma(daily: List<Double?>, tauDays: Double): List<Double?> {
        var s: Double? = null
        var lastIdx = -1
        return daily.mapIndexed { i, x ->
            if (x != null) {
                s = s?.let { prev -> prev + (1 - exp(-(i - lastIdx) / tauDays)) * (x - prev) } ?: x
                lastIdx = i
            }
            s
        }
    }

    /** Same as [timeEwma] for irregular timestamps (epoch seconds). */
    fun timeEwmaAt(points: List<Pair<Long, Double>>, tauDays: Double): List<Pair<Long, Double>> {
        var s: Double? = null
        var lastT = 0L
        return points.sortedBy { it.first }.map { (t, x) ->
            val gap = if (s == null) 0.0 else (t - lastT) / 86400.0
            s = s?.let { prev -> prev + (1 - exp(-gap.coerceAtLeast(0.0) / tauDays)) * (x - prev) } ?: x
            lastT = t
            t to s!!
        }
    }

    fun alphaForTimeConstant(days: Double) = 1 - exp(-1 / days)

    fun lnOrNull(x: Double?): Double? = x?.takeIf { it > 0 }?.let { ln(it) }

    /** Circular standard deviation for clock times (minutes since midnight), in minutes. */
    fun clockSdMinutes(minutes: List<Double>): Double? {
        if (minutes.size < 3) return null
        val angles = minutes.map { it / 1440.0 * 2 * Math.PI }
        val c = angles.sumOf { kotlin.math.cos(it) } / angles.size
        val s = angles.sumOf { kotlin.math.sin(it) } / angles.size
        val r = sqrt(c * c + s * s).coerceIn(1e-9, 1.0)
        return sqrt(-2 * ln(r)) / (2 * Math.PI) * 1440.0
    }

    /** Circular mean for clock times (minutes since midnight). */
    fun clockMeanMinutes(minutes: List<Double>): Double? {
        if (minutes.isEmpty()) return null
        val angles = minutes.map { it / 1440.0 * 2 * Math.PI }
        val c = angles.sumOf { kotlin.math.cos(it) }
        val s = angles.sumOf { kotlin.math.sin(it) }
        val a = kotlin.math.atan2(s, c)
        return ((a / (2 * Math.PI) * 1440.0) + 1440.0) % 1440.0
    }

    /** Least-squares slope per sample index. */
    fun slope(ys: List<Double>): Double? {
        if (ys.size < 3) return null
        val n = ys.size
        val xm = (n - 1) / 2.0
        val ym = ys.average()
        var num = 0.0; var den = 0.0
        ys.forEachIndexed { i, y -> num += (i - xm) * (y - ym); den += (i - xm) * (i - xm) }
        return if (den == 0.0) null else num / den
    }
}
