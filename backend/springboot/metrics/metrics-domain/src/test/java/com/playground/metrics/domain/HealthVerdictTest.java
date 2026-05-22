package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthVerdictTest {

    @Test
    void scrapeMissBothIsDown() {
        assertThat(HealthVerdict.from(0, true, true)).isEqualTo(HealthVerdict.Status.DOWN);
        assertThat(HealthVerdict.from(0, false, false)).isEqualTo(HealthVerdict.Status.DOWN);
    }

    @Test
    void oneScrapeMissWithActuatorUpIsDegraded() {
        assertThat(HealthVerdict.from(1, true, true)).isEqualTo(HealthVerdict.Status.DEGRADED);
    }

    @Test
    void oneScrapeMissWithActuatorDownIsDown() {
        assertThat(HealthVerdict.from(1, true, false)).isEqualTo(HealthVerdict.Status.DOWN);
        assertThat(HealthVerdict.from(1, false, false)).isEqualTo(HealthVerdict.Status.DOWN);
    }

    @Test
    void cleanScrapeWithActuatorUpIsUp() {
        assertThat(HealthVerdict.from(2, true, true)).isEqualTo(HealthVerdict.Status.UP);
    }

    @Test
    void cleanScrapeWithActuatorDownIsDegraded() {
        assertThat(HealthVerdict.from(2, true, false)).isEqualTo(HealthVerdict.Status.DEGRADED);
    }

    @Test
    void cleanScrapeWithActuatorUnreachableIsDegraded() {
        assertThat(HealthVerdict.from(2, false, false)).isEqualTo(HealthVerdict.Status.DEGRADED);
    }

    @Test
    void sparkProbeReachableAndOkIsUp() {
        assertThat(HealthVerdict.fromSparkProbe(true, true)).isEqualTo(HealthVerdict.Status.UP);
    }

    @Test
    void sparkProbeReachableButNotOkIsDegraded() {
        assertThat(HealthVerdict.fromSparkProbe(true, false)).isEqualTo(HealthVerdict.Status.DEGRADED);
    }

    @Test
    void sparkProbeUnreachableIsDown() {
        assertThat(HealthVerdict.fromSparkProbe(false, false)).isEqualTo(HealthVerdict.Status.DOWN);
        assertThat(HealthVerdict.fromSparkProbe(false, true)).isEqualTo(HealthVerdict.Status.DOWN);
    }
}
