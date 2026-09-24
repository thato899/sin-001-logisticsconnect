package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubServiceAppTest {
    @Test
    void hubIdsAreMatchedCaseInsensitivelyAndWithPadding() {
        Hub hub = new Hub("H-500", "Gauteng", "Johannesburg Central", true, List.of());
        assertTrue(HubServiceApp.matchesHubId(hub, " h-500 "));
        assertFalse(HubServiceApp.matchesHubId(hub, "H-501"));
    }
}
