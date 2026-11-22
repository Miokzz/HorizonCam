package com.miokzz.horizoncam

import com.miokzz.horizoncam.sensors.SensorCadence
import org.junit.Assert.*
import org.junit.Test

class SensorCadenceTest {
    @Test fun rateRequestRespectsHardwareLimitAndNeverRequestsAbove200Hz() {
        assertEquals(5_000, SensorCadence.requestedPeriodUs(0))
        assertEquals(5_000, SensorCadence.requestedPeriodUs(1_000))
        assertEquals(20_000, SensorCadence.requestedPeriodUs(20_000))
    }

    @Test fun actualRateUsesSensorTimestampsAndRejectsDuplicateOrReorderedSamples() {
        val cadence = SensorCadence()
        assertNull(cadence.measuredHz())
        cadence.append(1_000_000_000L)
        assertNull(cadence.measuredHz())
        cadence.append(1_005_000_000L)
        cadence.append(1_005_000_000L)
        cadence.append(999_000_000L)
        cadence.append(0L)
        cadence.append(1_010_000_000L)
        assertEquals(200.0, cadence.measuredHz()!!, 0.00001)
        assertEquals(5_000_000L, cadence.longestIntervalNs)
        cadence.append(1_030_000_000L)
        assertEquals(100.0, cadence.measuredHz()!!, 0.00001)
        assertEquals(20_000_000L, cadence.longestIntervalNs)
        cadence.reset()
        assertNull(cadence.measuredHz())
        assertEquals(0L, cadence.longestIntervalNs)
    }
}
