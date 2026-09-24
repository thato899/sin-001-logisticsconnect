package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransitServiceAppTest {
    @Test
    void delayStageWidensEtaWindowBySixHoursPerStage() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(Instant.parse("2026-01-01T04:00:00Z"), TransitServiceApp.etaWindowEnd(start, 0));
        assertEquals(Instant.parse("2026-01-03T04:00:00Z"), TransitServiceApp.etaWindowEnd(start, 8));
    }

    @Test
    void negativeDelayStagesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TransitServiceApp.etaWindowEnd(Instant.now(), -1));
    }
}
