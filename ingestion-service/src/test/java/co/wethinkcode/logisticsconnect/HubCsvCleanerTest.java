package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises HubCsvCleaner's normalization and dedup rules directly through its public API
 * (loadAndClean), using small hand-built CSVs so each rule is isolated from the real
 * hubs-global.csv — that file is covered as a whole by the "realDataset" tests at the bottom.
 */
class HubCsvCleanerTest {

    private static final String HEADER = "hub_id, Province ,sorting_center,active";

    private static List<Hub> clean(String... rows) throws IOException {
        String csv = HEADER + "\n" + String.join("\n", rows) + "\n";
        InputStream stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        return HubCsvCleaner.loadAndClean(stream);
    }

    private static Hub find(List<Hub> hubs, String hubId) {
        return hubs.stream().filter(h -> h.hubId().equals(hubId)).findFirst()
                .orElseThrow(() -> new AssertionError("no hub with id " + hubId + " in " + hubs));
    }

    // --- per-field normalization --------------------------------------------------------

    @Test
    void normalizesCasingOfHubIdAndProvince() throws IOException {
        List<Hub> hubs = clean("h-501,GAUTENG,pretoria north,YES");
        Hub hub = find(hubs, "H-501");
        assertEquals("Gauteng", hub.province());
        assertEquals("Pretoria North", hub.sortingCenter());
    }

    @Test
    void trimsPaddingAndCollapsesDoubleSpaces() throws IOException {
        List<Hub> hubs = clean(" H-502 , Western Cape ,Cape Town  Port ,0");
        Hub hub = find(hubs, "H-502");
        assertEquals("Western Cape", hub.province());
        assertEquals("Cape Town Port", hub.sortingCenter());
        assertEquals(Boolean.FALSE, hub.active());
    }

    @Test
    void canonicalizesProvinceSpellingVariantsToTheSameForm() throws IOException {
        // Same rule the real data exercises for KwaZulu-Natal, isolated to a single row here.
        List<Hub> hubs = clean("H-600,Kwa-Zulu Natal,Test Hub,Y");
        assertEquals("KwaZulu-Natal", find(hubs, "H-600").province());
    }

    @Test
    void normalizesTruthyAndFalsyBooleanRepresentations() throws IOException {
        List<Hub> hubs = clean(
                "H-601,Gauteng,Truthy Y Hub,Y",
                "H-602,Gauteng,Truthy 1 Hub,1",
                "H-603,Gauteng,Truthy True Hub,TRUE",
                "H-604,Gauteng,Falsy N Hub,N",
                "H-605,Gauteng,Falsy 0 Hub,0",
                "H-606,Gauteng,Falsy False Hub,FALSE"
        );
        assertEquals(Boolean.TRUE, find(hubs, "H-601").active());
        assertEquals(Boolean.TRUE, find(hubs, "H-602").active());
        assertEquals(Boolean.TRUE, find(hubs, "H-603").active());
        assertEquals(Boolean.FALSE, find(hubs, "H-604").active());
        assertEquals(Boolean.FALSE, find(hubs, "H-605").active());
        assertEquals(Boolean.FALSE, find(hubs, "H-606").active());
    }

    @Test
    void treatsUnknownAndPlaceholderActiveValuesAsGenuinelyAmbiguous() throws IOException {
        List<Hub> hubs = clean(
                "H-607,Gauteng,Unknown Hub,unknown",
                "H-608,Gauteng,NA Hub,N/A",
                "H-609,Gauteng,Blank Hub,"
        );
        assertNull(find(hubs, "H-607").active());
        assertNull(find(hubs, "H-608").active());
        assertNull(find(hubs, "H-609").active());
    }

    // --- dedup ------------------------------------------------------------------------------

    @Test
    void mergesDuplicateRowsForTheSameSortingCenterIntoOneRecord() throws IOException {
        List<Hub> hubs = clean(
                "H-510,Gauteng,johannesburg central,FALSE",
                "H-500,Gauteng,Johannesburg Central,Y",
                "H-515,Gauteng,Johannesburg Central,YES"
        );
        long matching = hubs.stream().filter(h -> "Johannesburg Central".equals(h.sortingCenter())).count();
        assertEquals(1, matching, "duplicate rows for the same sorting center should collapse to one record");
    }

    @Test
    void canonicalHubIdIsTheLowestNumericSuffixInTheGroup() throws IOException {
        List<Hub> hubs = clean(
                "H-510,Gauteng,Johannesburg Central,FALSE",
                "H-500,Gauteng,Johannesburg Central,Y",
                "H-515,Gauteng,Johannesburg Central,YES"
        );
        Hub merged = find(hubs, "H-500");
        assertEquals(List.of("H-510", "H-515"), merged.mergedFrom());
    }

    @Test
    void activeResolvesToTrueIfAnyDuplicateRowSaysTrue() throws IOException {
        List<Hub> hubs = clean(
                "H-500,Gauteng,Johannesburg Central,FALSE",
                "H-504,Gauteng,Johannesburg Central,true"
        );
        assertEquals(Boolean.TRUE, find(hubs, "H-500").active());
    }

    @Test
    void activeResolvesToFalseIfEveryDuplicateRowAgreesItsFalse() throws IOException {
        List<Hub> hubs = clean(
                "H-700,Gauteng,All False Hub,N",
                "H-701,Gauteng,All False Hub,0"
        );
        assertEquals(Boolean.FALSE, find(hubs, "H-700").active());
    }

    @Test
    void activeResolvesToNullIfEveryDuplicateRowIsAmbiguous() throws IOException {
        List<Hub> hubs = clean(
                "H-702,Gauteng,All Ambiguous Hub,unknown",
                "H-703,Gauteng,All Ambiguous Hub,N/A"
        );
        assertNull(find(hubs, "H-702").active());
    }

    @Test
    void missingProvinceIsBackfilledFromADuplicateSiblingRow() throws IOException {
        List<Hub> hubs = clean(
                "H-502,gauteng,Pretoria North,0",
                "H-508,,Pretoria North,yes"
        );
        assertEquals("Gauteng", find(hubs, "H-502").province());
    }

    @Test
    void nonDuplicateRecordsHaveAnEmptyMergedFromList() throws IOException {
        List<Hub> hubs = clean("H-999,Gauteng,Only Hub,Y");
        assertTrue(find(hubs, "H-999").mergedFrom().isEmpty());
    }

    // --- the real dataset --------------------------------------------------------------------

    @Test
    void realDatasetCollapsesEighteenRowsToTenRecords() throws IOException {
        InputStream csv = HubCsvCleanerTest.class.getResourceAsStream("/hubs-global.csv");
        List<Hub> hubs = HubCsvCleaner.loadAndClean(csv);
        assertEquals(10, hubs.size());
    }

    @Test
    void realDatasetHasNoDuplicateSortingCentersAfterCleaning() throws IOException {
        InputStream csv = HubCsvCleanerTest.class.getResourceAsStream("/hubs-global.csv");
        List<Hub> hubs = HubCsvCleaner.loadAndClean(csv);
        long distinctSortingCenters = hubs.stream().map(Hub::sortingCenter).distinct().count();
        assertEquals(hubs.size(), distinctSortingCenters);
    }

    @Test
    void realDatasetJohannesburgCentralClusterMergesAllFourDuplicates() throws IOException {
        InputStream csv = HubCsvCleanerTest.class.getResourceAsStream("/hubs-global.csv");
        List<Hub> hubs = HubCsvCleaner.loadAndClean(csv);
        Optional<Hub> jhb = hubs.stream().filter(h -> "Johannesburg Central".equals(h.sortingCenter())).findFirst();
        assertTrue(jhb.isPresent());
        assertEquals("H-500", jhb.get().hubId());
        assertEquals(List.of("H-504", "H-510", "H-515"), jhb.get().mergedFrom());
        assertFalse(jhb.get().active() == null, "3 of 4 duplicates say active — should resolve, not stay ambiguous");
    }
}
