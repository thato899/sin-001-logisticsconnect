package co.wethinkcode.logisticsconnect;

import java.util.List;

/**
 * A cleaned hub / sorting-center record, ready for other services to consume.
 *
 * @param hubId         canonical hub ID, e.g. "H-501"
 * @param province      canonical province name, or null if it couldn't be determined even after
 *                       backfilling from a duplicate row
 * @param sortingCenter canonical sorting-center name
 * @param active        true/false if known, null if every source row for this hub was genuinely
 *                       ambiguous (e.g. "unknown", "N/A")
 * @param mergedFrom     raw hub IDs from hubs-global.csv that were folded into this record as
 *                       duplicates of the same real-world hub (empty if none were)
 */
public record Hub(String hubId, String province, String sortingCenter, Boolean active, List<String> mergedFrom) {
}
