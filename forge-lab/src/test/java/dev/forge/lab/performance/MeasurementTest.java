package dev.forge.lab.performance;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MeasurementTest {

    @Test
    void scheduleDoesNotDriftForNonIntegerIntervals() {
        ArrivalSchedule s = new ArrivalSchedule(1_000, 3);   // 333 333 333.3 ns apart
        assertThat(s.offsetNanos(1)).isEqualTo(333_333_333L);
        assertThat(s.offsetNanos(3)).isEqualTo(1_000_000_000L);
        assertThat(s.intendedNanos(3)).isEqualTo(1_000_001_000L);
        // one hour at 300 req/s lands exactly on the hour
        assertThat(new ArrivalSchedule(0, 300).offsetNanos(300L * 3_600)).isEqualTo(3_600_000_000_000L);
    }

    @Test
    void countWithinIncludesStartAndExcludesEnd() {
        assertThat(new ArrivalSchedule(0, 100).countWithin(1_000_000_000L)).isEqualTo(100);
        assertThat(new ArrivalSchedule(0, 3).countWithin(1_000_000_000L)).isEqualTo(3);
        assertThat(new ArrivalSchedule(0, 3).countWithin(1_000_000_001L)).isEqualTo(4);
        assertThat(new ArrivalSchedule(0, 7).countWithin(500_000_000L)).isEqualTo(4);   // 0, 142.9, 285.7, 428.6 ms
        assertThat(new ArrivalSchedule(0, 100).countWithin(0)).isZero();
        assertThatThrownBy(() -> new ArrivalSchedule(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryReportsPercentilesInMillisecondsWithinHistogramPrecision() {
        Histogram h = Histograms.newMicros();
        for (int ms = 1; ms <= 1_000; ms++) {
            h.recordValue(ms * 1_000L);
        }
        Map<String, Object> s = Histograms.summary(h);
        assertThat(s.get("count")).isEqualTo(1_000L);
        assertThat((Double) s.get("minMs")).isEqualTo(1.0);
        assertThat((Double) s.get("p50Ms")).isCloseTo(500.0, within(0.5));
        assertThat((Double) s.get("p90Ms")).isCloseTo(900.0, within(0.9));
        assertThat((Double) s.get("p99Ms")).isCloseTo(990.0, within(1.0));
        assertThat((Double) s.get("p999Ms")).isCloseTo(999.0, within(1.0));
        assertThat((Double) s.get("maxMs")).isCloseTo(1_000.0, within(1.0));
        assertThat((Double) s.get("meanMs")).isCloseTo(500.5, within(0.5));
    }

    @Test
    void emptyHistogramHasOnlyACount() {
        assertThat(Histograms.summary(Histograms.newMicros())).containsOnlyKeys("count");
        assertThat(Histograms.spectrum(Histograms.newMicros())).isEmpty();
    }

    @Test
    void spectrumMarksPercentilesTheSampleCannotResolve() {
        Histogram h = Histograms.newMicros();
        for (int i = 1; i <= 1_000; i++) {
            h.recordValue(i);
        }
        List<Map<String, Object>> spectrum = Histograms.spectrum(h);
        Map<Double, Boolean> resolvable = new java.util.LinkedHashMap<>();
        spectrum.forEach(p -> resolvable.put((Double) p.get("percentile"), (Boolean) p.get("resolvable")));
        assertThat(resolvable.get(99.0)).isTrue();
        assertThat(resolvable.get(99.9)).isTrue();     // 1000 samples: exactly one above p99.9
        assertThat(resolvable.get(99.95)).isFalse();
        assertThat(resolvable.get(99.99)).isFalse();
    }

    @Test
    void valuesAreClampedIntoTheTrackableRange() {
        assertThat(Histograms.toMicros(0)).isEqualTo(1);
        assertThat(Histograms.toMicros(1_499)).isEqualTo(1);
        assertThat(Histograms.toMicros(2_500_000)).isEqualTo(2_500);
        assertThat(Histograms.toMicros(Long.MAX_VALUE / 2)).isEqualTo(Histograms.HIGHEST_MICROS);
    }
}
