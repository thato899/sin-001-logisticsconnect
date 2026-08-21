package co.wethinkcode.logisticsconnect;

import java.util.List;

/**
 * Mirrors the shape hub-service serves from GET /hubs/{hubId}. Duplicated here rather than
 * shared, since each service in this repo is an independent Maven project with no parent pom.
 */
public record Hub(String hubId, String province, String sortingCenter, Boolean active, List<String> mergedFrom) {
}
