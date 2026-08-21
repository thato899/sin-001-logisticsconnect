package co.wethinkcode.logisticsconnect;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads hubs-global.csv and turns it into a clean, deduplicated list of {@link Hub} records.
 * See ingestion-service/README.md#known-data-issues for the full list of issues this handles.
 */
public final class HubCsvCleaner {

    // Known province spelling/casing variants in this dataset, keyed by a normalized form
    // (lowercase, letters/digits only) so "KwaZulu-Natal" / "Kwa-Zulu Natal" / "KwaZulu Natal"
    // all resolve to the same canonical string.
    private static final Map<String, String> PROVINCE_CANONICAL = Map.ofEntries(
            Map.entry("gauteng", "Gauteng"),
            Map.entry("westerncape", "Western Cape"),
            Map.entry("kwazulunatal", "KwaZulu-Natal"),
            Map.entry("freestate", "Free State"),
            Map.entry("easterncape", "Eastern Cape"),
            Map.entry("limpopo", "Limpopo"),
            Map.entry("northwest", "North West"),
            Map.entry("mpumalanga", "Mpumalanga"),
            Map.entry("northerncape", "Northern Cape")
    );

    private static final Set<String> TRUE_VALUES = Set.of("y", "yes", "1", "true");
    private static final Set<String> FALSE_VALUES = Set.of("n", "no", "0", "false");

    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("(\\d+)$");

    private HubCsvCleaner() {
    }

    public static List<Hub> loadAndClean(InputStream csvStream) throws IOException {
        List<RawRow> rawRows = readRows(csvStream);
        List<Candidate> cleaned = rawRows.stream().map(HubCsvCleaner::cleanRow).toList();
        return dedupe(cleaned);
    }

    // --- CSV reading ---------------------------------------------------------------------

    /** Raw, unmodified cell values straight off a CSV row. */
    private record RawRow(String hubId, String province, String sortingCenter, String active) {
    }

    private static List<RawRow> readRows(InputStream csvStream) throws IOException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {
            List<String[]> all = reader.readAll();
            if (all.isEmpty()) {
                return List.of();
            }

            String[] header = all.get(0);
            int hubIdCol = findColumn(header, "hub_id");
            int provinceCol = findColumn(header, "province");
            int sortingCenterCol = findColumn(header, "sorting_center");
            int activeCol = findColumn(header, "active");
            int maxCol = Math.max(Math.max(hubIdCol, provinceCol), Math.max(sortingCenterCol, activeCol));

            List<RawRow> rows = new ArrayList<>();
            for (int i = 1; i < all.size(); i++) {
                String[] cols = all.get(i);
                if (cols.length <= maxCol) {
                    continue; // malformed/short row — skip rather than let one bad row crash ingestion
                }
                rows.add(new RawRow(cols[hubIdCol], cols[provinceCol], cols[sortingCenterCol], cols[activeCol]));
            }
            return rows;
        } catch (CsvException e) {
            throw new IOException("Failed to parse hubs-global.csv", e);
        }
    }

    private static int findColumn(String[] header, String columnName) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new IllegalStateException("hubs-global.csv is missing expected column: " + columnName);
    }

    // --- Per-row cleaning ------------------------------------------------------------------

    /** A single cleaned row, before duplicates across rows have been merged. */
    private record Candidate(String hubId, String province, String sortingCenter, Boolean active) {
    }

    private static Candidate cleanRow(RawRow row) {
        String hubId = collapseWhitespace(row.hubId()).toUpperCase(Locale.ROOT);
        String province = canonicalizeProvince(row.province());
        String sortingCenter = titleCase(row.sortingCenter());
        Boolean active = normalizeActive(row.active());
        return new Candidate(hubId, province, sortingCenter, active);
    }

    private static String collapseWhitespace(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** lowercase, letters/digits only — used to match spelling/casing/punctuation variants of the same name. */
    private static String normalizeKey(String s) {
        return collapseWhitespace(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String titleCase(String s) {
        String collapsed = collapseWhitespace(s);
        if (collapsed.isEmpty()) {
            return collapsed;
        }
        StringBuilder result = new StringBuilder();
        for (String word : collapsed.split(" ")) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    private static String canonicalizeProvince(String raw) {
        String key = normalizeKey(raw);
        if (key.isEmpty()) {
            return null; // blank province — left for dedupe to backfill from a sibling row, if any
        }
        return PROVINCE_CANONICAL.getOrDefault(key, titleCase(raw));
    }

    private static Boolean normalizeActive(String raw) {
        String v = collapseWhitespace(raw).toLowerCase(Locale.ROOT);
        if (TRUE_VALUES.contains(v)) {
            return Boolean.TRUE;
        }
        if (FALSE_VALUES.contains(v)) {
            return Boolean.FALSE;
        }
        return null; // "unknown", "N/A", blank, etc. — genuinely ambiguous, not a guess either way
    }

    // --- Dedup -----------------------------------------------------------------------------

    private static List<Hub> dedupe(List<Candidate> candidates) {
        // Group by normalized sorting-center name. In this dataset the sorting-center name is the
        // reliable signal for "same real-world hub" — hub ID and province are exactly the fields
        // that vary (or go missing) across duplicate rows for the same physical place.
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            groups.computeIfAbsent(normalizeKey(c.sortingCenter()), k -> new ArrayList<>()).add(c);
        }

        List<Hub> result = new ArrayList<>();
        for (List<Candidate> group : groups.values()) {
            result.add(mergeGroup(group));
        }
        result.sort(Comparator.comparing(Hub::hubId));
        return result;
    }

    private static Hub mergeGroup(List<Candidate> group) {
        // Canonical ID: lowest numeric suffix wins, e.g. H-500 over H-504/H-510/H-515 — arbitrary
        // but stable and predictable, and keeps the "first-seen" record as the one callers see.
        Candidate canonical = group.stream()
                .min(Comparator.comparingInt(c -> extractNumericSuffix(c.hubId())))
                .orElseThrow();

        // Province: first non-null value in the group. This backfills rows with a blank province
        // (e.g. H-508) from a sibling row sharing the same sorting center (e.g. H-502).
        String province = group.stream()
                .map(Candidate::province)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        // Active: "any true wins". A duplicate row confirming a hub is active is a stronger signal
        // than a stale "inactive" snapshot elsewhere in the export — an inactive hub is unlikely to
        // have a duplicate row mistakenly asserting it's active. Falls back to false if every row
        // agrees it's inactive, or null if every row was itself ambiguous.
        Boolean active;
        if (group.stream().anyMatch(c -> Boolean.TRUE.equals(c.active()))) {
            active = Boolean.TRUE;
        } else if (group.stream().anyMatch(c -> Boolean.FALSE.equals(c.active()))) {
            active = Boolean.FALSE;
        } else {
            active = null;
        }

        List<String> mergedFrom = group.stream()
                .map(Candidate::hubId)
                .filter(id -> !id.equals(canonical.hubId()))
                .sorted()
                .toList();

        return new Hub(canonical.hubId(), province, canonical.sortingCenter(), active, mergedFrom);
    }

    private static int extractNumericSuffix(String hubId) {
        Matcher m = NUMERIC_SUFFIX.matcher(hubId);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
