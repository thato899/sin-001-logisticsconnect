package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class IngestionServiceApp {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "7050"));

    public static void main(String[] args) {
        List<Hub> hubs = load();

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/hubs", ctx -> {
            if (hubs == null) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("error", "hubs-global.csv failed to load — see server logs"));
                return;
            }
            ctx.json(hubs);
        });

        app.get("/hubs/{hubId}", ctx -> {
            if (hubs == null) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("error", "hubs-global.csv failed to load — see server logs"));
                return;
            }
            String requestedId = ctx.pathParam("hubId").trim().toUpperCase(Locale.ROOT);
            hubs.stream()
                    .filter(h -> h.hubId().equals(requestedId))
                    .findFirst()
                    .ifPresentOrElse(
                            ctx::json,
                            () -> ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "no hub with id " + requestedId))
                    );
        });
    }

    /**
     * Loads and cleans hubs-global.csv from the classpath. Returns null (rather than throwing) on
     * failure so the service still boots and can report the problem via /hubs instead of crashing
     * before Javalin even starts.
     */
    private static List<Hub> load() {
        try (InputStream csv = IngestionServiceApp.class.getResourceAsStream("/hubs-global.csv")) {
            if (csv == null) {
                throw new IOException("hubs-global.csv not found on classpath");
            }
            List<Hub> hubs = HubCsvCleaner.loadAndClean(csv);
            System.out.printf("Loaded and cleaned %d hub records from hubs-global.csv%n", hubs.size());
            return hubs;
        } catch (IOException e) {
            System.err.println("Failed to load hubs-global.csv: " + e.getMessage());
            return null;
        }
    }
}
