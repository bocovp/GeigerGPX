package com.github.bocovp.geigergpx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class KernelDensityEstimatorTest {
    private data class Sample(val start: Double, val duration: Double, val counts: Int) {
        val end: Double get() = start + duration
        val center: Double get() = start + duration / 2.0
    }

    private fun estimator(samples: List<Sample>, sensitivity: Double): KernelDensityEstimator =
        KernelDensityEstimator(sensitivity).apply {
            for (sample in samples) {
                if (sample.duration == 0.0) addPoint(sample.start, sample.counts)
                else addSampleInterval(sample.start, sample.duration, sample.counts)
            }
        }

    private fun kernel(offset: Double, scale: Double): Double {
        val u = offset / scale
        return if (abs(u) >= 1.0) 0.0 else 0.75 / scale * (1.0 - u * u)
    }

    private fun mass(lo: Double, hi: Double): Double {
        fun primitive(x: Double): Double {
            val u = x.coerceIn(-1.0, 1.0)
            return 0.75 * (u - u * u * u / 3.0)
        }
        return (primitive(hi) - primitive(lo)).coerceAtLeast(0.0)
    }

    private fun squaredMass(lo: Double, hi: Double): Double {
        fun primitive(x: Double): Double {
            val u = x.coerceIn(-1.0, 1.0)
            val u2 = u * u
            return 0.5625 * u * (1.0 - 2.0 * u2 / 3.0 + u2 * u2 / 5.0)
        }
        return (primitive(hi) - primitive(lo)).coerceAtLeast(0.0)
    }

    private fun scales(
        samples: List<Sample>, sensitivity: Double, scale: Double, endOverride: Double? = null
    ): List<Double> {
        val sortedCenters = samples.map { it.center }.sorted().toDoubleArray()
        val pilot = estimator(samples, sensitivity).estimateDoseRate(sortedCenters, scale, endOverride)
        val byCenter = sortedCenters.indices.associate { sortedCenters[it] to pilot[it] }
        return samples.map { (scale * 0.1 / byCenter.getValue(it.center)).coerceIn(scale / 10.0, scale) }
    }

    /** Direct O(NQ) reference, independent of the production support sweep. */
    private fun reference(
        samples: List<Sample>, sensitivity: Double, queries: DoubleArray,
        scale: Double, endOverride: Double? = null
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val widths = scales(samples, sensitivity, scale, endOverride)
        val first = samples.minOf { it.start }
        val last = max(samples.maxOf { it.end }, endOverride ?: Double.NEGATIVE_INFINITY)
        val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)
        val means = DoubleArray(queries.size)
        val lows = DoubleArray(queries.size)
        val highs = DoubleArray(queries.size)
        for ((index, t) in queries.withIndex()) {
            val boundaryT = t.coerceIn(first, last)
            var rate = 0.0
            var variance = 0.0
            for ((sampleIndex, sample) in samples.withIndex()) {
                val h = widths[sampleIndex]
                val boundaryMass = mass((boundaryT - last) / h, (boundaryT - first) / h)
                if (boundaryMass <= 1e-9) continue
                if (sample.duration == 0.0) {
                    val k = kernel(t - sample.start, h) / boundaryMass
                    rate += sample.counts * k
                    variance += sample.counts * k * k
                } else {
                    val lo = (t - sample.end) / h
                    val hi = (t - sample.start) / h
                    val density = sample.counts / sample.duration
                    rate += density * mass(lo, hi) / boundaryMass
                    variance += density * squaredMass(lo, hi) / h / (boundaryMass * boundaryMass)
                }
            }
            val backgroundLo = (boundaryT - last) / scale
            val backgroundHi = (boundaryT - first) / scale
            val backgroundMass = mass(backgroundLo, backgroundHi)
            val backgroundSquaredMass = squaredMass(backgroundLo, backgroundHi)
            val backgroundExposure = if (backgroundSquaredMass > 1e-12) {
                scale * backgroundMass * backgroundMass / backgroundSquaredMass
            } else 0.0
            val exposure = if (variance > 0.0 && rate > 0.0) {
                min(rate / variance, backgroundExposure)
            } else backgroundExposure
            val effectiveCount = rate * exposure
            val lowerCount = floor(effectiveCount).toInt()
            val fraction = effectiveCount - lowerCount
            val lowerChi = ci.chi2L(lowerCount) + fraction * (ci.chi2L(lowerCount + 1) - ci.chi2L(lowerCount))
            val upperChi = ci.chi2R(lowerCount + 1) + fraction * (ci.chi2R(lowerCount + 2) - ci.chi2R(lowerCount + 1))
            means[index] = rate / sensitivity
            if (exposure > 0.0) {
                lows[index] = lowerChi * 0.5 / exposure / sensitivity
                highs[index] = upperChi * 0.5 / exposure / sensitivity
            }
        }
        return Triple(means, lows, highs)
    }

    private fun assertCurvesEqual(
        expected: Triple<DoubleArray, DoubleArray, DoubleArray>,
        actual: Triple<DoubleArray, DoubleArray, DoubleArray>, tolerance: Double = 1e-8
    ) {
        assertArrayEquals("means", expected.first, actual.first, tolerance)
        assertArrayEquals("lower bounds", expected.second, actual.second, tolerance)
        assertArrayEquals("upper bounds", expected.third, actual.third, tolerance)
        for (i in actual.first.indices) {
            assertTrue(actual.first[i].isFinite())
            assertTrue(actual.second[i] >= 0.0)
            assertTrue(actual.second[i] <= actual.first[i])
            assertTrue(actual.third[i] >= actual.first[i])
        }
    }

    @Test
    fun backgroundDoseKeepsMaximumScaleAndFixedIntervalResults() {
        val samples = listOf(Sample(0.0, 100.0, 10))
        val kde = estimator(samples, 2.0)
        val queries = DoubleArray(101) { it.toDouble() }
        assertEquals(listOf(10.0), scales(samples, 2.0, 10.0))
        assertCurvesEqual(kde.getConfidenceIntervals(queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun highDoseClampsAtOneTenthOfSliderScale() {
        val samples = listOf(Sample(0.0, 100.0, 10000))
        val kde = estimator(samples, 1.0)
        val queries = DoubleArray(201) { it * 0.5 }
        assertEquals(listOf(1.0), scales(samples, 1.0, 10.0))
        assertCurvesEqual(kde.getConfidenceIntervals(queries, 1.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-6)
    }

    @Test
    fun intermediateDoseUsesInverseProportionalScale() {
        val samples = listOf(Sample(0.0, 100.0, 40))
        val kde = estimator(samples, 1.0)
        val queries = DoubleArray(221) { it * 0.5 - 5.0 }
        assertEquals(2.5, scales(samples, 1.0, 10.0).single(), 1e-10)
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun pointSupportsCanBeginAndEndInDifferentOrders() {
        val samples = listOf(
            Sample(-1.0, 0.0, 1), Sample(0.0, 0.0, 1), Sample(8.0, 0.0, 1), Sample(9.0, 0.0, 300),
            Sample(10.0, 0.0, 1), Sample(17.0, 0.0, 1), Sample(19.0, 0.0, 1), Sample(40.0, 0.0, 1)
        )
        val widths = scales(samples, 30.0, 10.0)
        val starts = samples.indices.map { samples[it].start - widths[it] }
        val ends = samples.indices.map { samples[it].end + widths[it] }
        assertTrue("fixture must reorder support starts", starts.zipWithNext().any { it.first > it.second })
        assertTrue("fixture must reorder support ends", ends.zipWithNext().any { it.first > it.second })
        val queries = DoubleArray(241) { it * 0.25 - 10.0 }
        val kde = estimator(samples.reversed(), 30.0)
        assertCurvesEqual(reference(samples, 30.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertArrayEquals(kde.getAdaptiveConfidenceIntervals(queries, 10.0).first, kde.estimateAdaptiveDoseRate(queries, 10.0), 1e-12)
    }

    @Test
    fun sparseQueriesSkipExpiredSupportsWithoutAddingTheirTails() {
        val samples = listOf(Sample(0.0, 0.0, 10), Sample(1.0, 0.0, 1), Sample(100.0, 0.0, 20), Sample(101.0, 0.0, 1))
        val queries = doubleArrayOf(-20.0, 0.0, 50.0, 100.0, 150.0)
        assertCurvesEqual(reference(samples, 5.0, queries, 10.0), estimator(samples, 5.0).getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun intervalsUseTheirMidpointsAndRetainSharedEndpointsAndOverlaps() {
        val samples = listOf(
            Sample(0.0, 10.0, 5), Sample(10.0, 10.0, 5), Sample(15.0, 18.0, 40),
            Sample(28.0, 2.0, 300), Sample(31.0, 0.0, 4), Sample(40.0, 30.0, 12)
        )
        val queries = DoubleArray(361) { it * 0.25 - 10.0 }
        assertCurvesEqual(reference(samples, 5.0, queries, 10.0), estimator(samples, 5.0).getAdaptiveConfidenceIntervals(queries, 10.0), 1e-7)
    }

    @Test
    fun intervalVarianceIntegratesSquaredKernelInsteadOfSquaringItsMean() {
        val samples = listOf(Sample(0.0, 100.0, 40))
        val queries = doubleArrayOf(50.0)
        val actual = estimator(samples, 1.0).getAdaptiveConfidenceIntervals(queries, 10.0)
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), actual)
        // Only the local kernel exposure contributes to precision, not all 100 seconds.
        val fullInterval = ConfidenceInterval(0.0, 100.0, 40, false)
        assertTrue(actual.third[0] - actual.second[0] > fullInterval.highBound - fullInterval.lowBound)
    }

    @Test
    fun endOverrideChangesPilotAndBoundaryCorrectionAndKeepsZeroCountUpperLimit() {
        val samples = listOf(Sample(0.0, 4.0, 10), Sample(8.0, 0.0, 5))
        val queries = doubleArrayOf(0.0, 4.0, 8.0, 15.0, 25.0, 35.0)
        val actual = estimator(samples, 2.0).getAdaptiveConfidenceIntervals(queries, 10.0, 30.0)
        assertCurvesEqual(reference(samples, 2.0, queries, 10.0, 30.0), actual)
        assertEquals(0.0, actual.first[4], 0.0)
        assertEquals(0.0, actual.second[4], 0.0)
        assertTrue(actual.third[4] > 0.0)
    }

    @Test
    fun tinyIntervalTailKeepsSquaredKernelVarianceAccurate() {
        val kde = estimator(listOf(Sample(0.0, 100.0, 10000)), 1.0)
        val t = 100.999999
        val delta = 1.0 - (t - 100.0)
        // h = 1, boundary mass = 1/2. Integrate from u = 1 - delta to 1
        // in powers of delta to avoid subtracting nearly equal primitives.
        val mass = 0.75 * delta * delta * (1.0 - delta / 3.0)
        val squareMass = 0.5625 * delta * delta * delta *
            (4.0 / 3.0 - delta + delta * delta / 5.0)
        val rate = 100.0 * mass / 0.5
        val variance = 100.0 * squareMass / 0.25
        val exposure = min(rate / variance, 5.0 * 10.0 / 6.0)
        val effectiveCount = rate * exposure
        val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)
        val expectedHigh = (ci.chi2R(1) + effectiveCount * (ci.chi2R(2) - ci.chi2R(1))) * 0.5 / exposure
        val actual = kde.getAdaptiveConfidenceIntervals(doubleArrayOf(t), 10.0)
        assertEquals(rate, actual.first.single(), rate * 1e-6)
        assertEquals(expectedHigh, actual.third.single(), expectedHigh * 1e-6)
    }

    @Test
    fun lastSupportEdgeHasContinuousPositiveUpperBound() {
        val kde = estimator(listOf(Sample(0.0, 100.0, 10000)), 1.0)
        val queries = doubleArrayOf(100.999999, 101.0, 101.000001)
        val actual = kde.getAdaptiveConfidenceIntervals(queries, 10.0)
        assertTrue(actual.first[0] > 0.0)
        assertEquals(0.0, actual.first[1], 0.0)
        assertEquals(0.0, actual.first[2], 0.0)
        assertTrue(actual.third[1] > 0.0)
        assertEquals(actual.third[1], actual.third[0], 1e-8)
        assertEquals(actual.third[1], actual.third[2], 1e-12)
    }

    @Test
    fun squaredKernelIntegralRemainsAccurateNearSupportEdge() {
        // The confidence exposure cap can hide cancellation in a vanishing tail;
        // check this numerical primitive directly against its endpoint expansion.
        val kde = KernelDensityEstimator(1.0)
        val integral = KernelDensityEstimator::class.java.getDeclaredMethod(
            "adaptiveSquaredKernelIntegral", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType
        ).apply { isAccessible = true }
        for (width in doubleArrayOf(1e-3, 1e-6, 1e-9)) {
            val lo = 1.0 - width
            val delta = 1.0 - lo
            val expected = 0.5625 * delta * delta * delta *
                (4.0 / 3.0 - delta + delta * delta / 5.0)
            val actual = integral.invoke(kde, lo, 1.0) as Double
            assertEquals(expected, actual, expected * 1e-6)
        }
    }

    @Test
    fun epochTimestampsPreserveAdaptiveEstimate() {
        val samples = listOf(Sample(0.0, 4.0, 8), Sample(6.0, 0.0, 50), Sample(8.0, 5.0, 3), Sample(30.0, 8.0, 4))
        val queries = DoubleArray(101) { it * 0.5 - 5.0 }
        val offset = 1_800_000_000.0
        val shifted = samples.map { it.copy(start = it.start + offset) }
        assertCurvesEqual(
            estimator(samples, 3.0).getAdaptiveConfidenceIntervals(queries, 10.0),
            estimator(shifted, 3.0).getAdaptiveConfidenceIntervals(queries.map { it + offset }.toDoubleArray(), 10.0),
            1e-7
        )
    }

    @Test
    fun copyIsIndependentAndClearDiscardsAdaptiveIntervalData() {
        val samples = listOf(Sample(0.0, 10.0, 8), Sample(10.0, 10.0, 8), Sample(30.0, 0.0, 12))
        val original = estimator(samples, 3.0)
        val copy = original.copy()
        val queries = DoubleArray(81) { it * 0.5 }
        val expected = reference(samples, 3.0, queries, 10.0)
        original.addSampleInterval(40.0, 20.0, 100)
        original.clear()
        assertCurvesEqual(expected, copy.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertArrayEquals(DoubleArray(queries.size), original.estimateAdaptiveDoseRate(queries, 10.0), 0.0)
        original.addSampleInterval(0.0, 10.0, 3)
        assertCurvesEqual(
            estimator(listOf(Sample(0.0, 10.0, 3)), 3.0).getAdaptiveConfidenceIntervals(queries, 10.0),
            original.getAdaptiveConfidenceIntervals(queries, 10.0)
        )
    }

    @Test
    fun timeoutDropsCompleteIntervalsAndPointsFromBothEstimators() {
        val kde = KernelDensityEstimator(3.0).apply { timeout = 15.0 }
        kde.addSampleInterval(0.0, 4.0, 20)
        kde.addPoint(5.0, 10)
        kde.addSampleInterval(30.0, 5.0, 8)
        kde.addPoint(40.0, 2)
        val remaining = listOf(Sample(30.0, 5.0, 8), Sample(40.0, 0.0, 2))
        val queries = DoubleArray(81) { it * 0.5 + 20.0 }
        assertEquals(30.0 to 40.0, kde.timestampBounds())
        assertCurvesEqual(reference(remaining, 3.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertCurvesEqual(kde.getAdaptiveConfidenceIntervals(queries, 10.0), kde.copy().getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun emptyInputsAndZeroExtentReturnFiniteZeroArrays() {
        val empty = KernelDensityEstimator(2.0)
        val queries = doubleArrayOf(0.0, 1.0)
        val zeros = Triple(DoubleArray(2), DoubleArray(2), DoubleArray(2))
        assertCurvesEqual(zeros, empty.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertEquals(0, empty.estimateAdaptiveDoseRate(doubleArrayOf(), 10.0).size)
        empty.addSampleInterval(1.0, 0.0, 2)
        assertCurvesEqual(zeros, empty.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun sensitivityUpdateAffectsPilotAndDoseConversion() {
        val samples = listOf(Sample(0.0, 20.0, 40), Sample(20.0, 20.0, 2))
        val kde = estimator(samples, 1.0)
        kde.updateSensitivity(20.0)
        val queries = DoubleArray(81) { it * 0.5 }
        assertCurvesEqual(reference(samples, 20.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidScaleIsRejected() {
        KernelDensityEstimator(1.0).estimateAdaptiveDoseRate(doubleArrayOf(0.0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsortedQueriesAreRejected() {
        estimator(listOf(Sample(0.0, 10.0, 3)), 1.0).estimateAdaptiveDoseRate(doubleArrayOf(2.0, 1.0), 10.0)
    }
}
