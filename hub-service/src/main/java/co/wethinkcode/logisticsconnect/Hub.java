package co.wethinkcode.logisticsconnect;

import java.util.List;

/**
 * Mirrors the shape ingestion-service serves from GET /hubs. Duplicated here rather than shared,
 * since each service in this repo is an independent Maven project with no parent pom (same
 * pattern as MqConfig in the MQ-aware services).
 *
 * @param hubId         canonical hub ID, e.g. "H-501"
 * @param province      canonical province name, or null if unknown
 * @param sortingCenter canonical sorting-center name
 * @param active        true/false if known, null if genuinely ambiguous in the source data
 * @param mergedFrom    raw hub IDs folded into this record as duplicates (informational only here)
 */
public record Hub(String hubId, String province, String sortingCenter, Boolean active, List<String> mergedFrom) {
}
