package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertBotAppTest {
    @Test
    void alertsOnlyWhenCrossingUpIntoSevereStages() {
        assertTrue(AlertBotApp.crossedThreshold(null, 6));
        assertTrue(AlertBotApp.crossedThreshold(5, 8));
        assertFalse(AlertBotApp.crossedThreshold(6, 8));
        assertFalse(AlertBotApp.crossedThreshold(5, 4));
    }
}
