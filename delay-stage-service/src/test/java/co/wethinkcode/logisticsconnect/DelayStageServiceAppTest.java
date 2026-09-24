package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayStageServiceAppTest {
    @Test
    void acceptsOnlyStagesZeroThroughEight() {
        assertTrue(DelayStageServiceApp.isValidStage(0));
        assertTrue(DelayStageServiceApp.isValidStage(8));
        assertFalse(DelayStageServiceApp.isValidStage(null));
        assertFalse(DelayStageServiceApp.isValidStage(-1));
        assertFalse(DelayStageServiceApp.isValidStage(9));
    }
}
