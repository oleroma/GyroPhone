package com.oleroma.gyrophone.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PollPacerTest {
    private val us = 1_000L
    private val interval = 250 * us

    /**
     * Runs the poll loop against a sensor writing every [period] (wandering by [jitter]), each sleep [slack] longer
     * than asked, and returns the average wait of a sample for its check and the checks per sample.
     */
    private fun run(pacer: PollPacer?, period: Long = 1056 * us, jitter: Long = 30 * us, slack: Long = 50 * us): Pair<Double, Double> {
        val rnd = Random(1)
        var now = 0L
        var nextWrite = period
        var nextNominal = period
        var waited = 0L
        var samples = 0
        var checks = 0
        while (samples < 5000) {
            checks++
            if (now >= nextWrite) {
                waited += now - nextWrite
                samples++
                pacer?.onSample(now, nextWrite)
                nextNominal += period
                nextWrite = nextNominal + (rnd.nextGaussian() * jitter).toLong()
            }
            now += (pacer?.parkNs(now, interval) ?: interval) + slack
        }
        return waited.toDouble() / samples to checks.toDouble() / samples
    }

    @Test fun plainIntervalUntilThePeriodIsKnown() {
        val p = PollPacer()
        assertEquals(interval, p.parkNs(0, interval))
        p.onSample(1000 * us, 900 * us)
        assertEquals(interval, p.parkNs(1000 * us, interval))
        p.onSample(2056 * us, 1956 * us)
        assertEquals(1056 * us, p.periodNs)
    }

    @Test fun sleepsThroughTheQuietStretchThenChecksOften() {
        val p = PollPacer()
        p.onSample(1000 * us, 900 * us)
        p.onSample(2056 * us, 1956 * us)
        // Right after a sample: the plain interval, never a longer sleep.
        assertEquals(interval, p.parkNs(2056 * us, interval))
        // Close to the window: only up to its start.
        assertEquals(106 * us, p.parkNs((2056 + 800) * us, interval))
        // In the window: short checks.
        assertEquals(interval / 4, p.parkNs((2056 + 950) * us, interval))
    }

    @Test fun backToPlainPollingWhenTheSensorGoesQuiet() {
        val p = PollPacer()
        p.onSample(1000 * us, 900 * us)
        p.onSample(2056 * us, 1956 * us)
        assertEquals(interval, p.parkNs(2056 * us + 4 * 1056 * us, interval))
        // A gap in the samples forgets the period until two in a row give it again.
        p.onSample(100_000 * us, 99_900 * us)
        assertEquals(0L, p.periodNs)
    }

    @Test fun notPacedWhenThePollIsNoFasterThanTheSensor() {
        val p = PollPacer()
        p.onSample(1000 * us, 900 * us)
        p.onSample(2056 * us, 1956 * us)
        assertEquals(1000 * us, p.parkNs(2056 * us, 1000 * us))
    }

    @Test fun aSampleWaitsLessThanWithFixedPollingForFewMoreChecks() {
        val (fixedWait, fixedChecks) = run(null)
        val (pacedWait, pacedChecks) = run(PollPacer())
        println("fixed: wait ${fixedWait / 1000} us, $fixedChecks checks/sample; paced: ${pacedWait / 1000} us, $pacedChecks checks/sample")
        assertTrue("paced $pacedWait vs fixed $fixedWait", pacedWait < fixedWait * 0.5)
        assertTrue("checks $pacedChecks vs $fixedChecks", pacedChecks <= fixedChecks * 1.4)
    }

    @Test fun followsASensorThatDriftsOrStepsItsRate() {
        val (wait, _) = run(PollPacer(), period = 2400 * us, jitter = 80 * us)
        assertTrue("wait $wait", wait < 120 * us)
    }
}
