package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class HubServiceApp {

    private static final String INGESTION_URL = System.getenv().getOrDefault("INGESTION_URL", "http://localhost:7050/hubs");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "7051"));
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicReference<List<Hub>> CACHE = new AtomicReference<>();

    public static void main(String[] args) {
        // Best-effort warm-up at boot; if ingestion-service isn't up yet this is a no-op and
        // each request retries the fetch until one succeeds (see hubs() below) — no restart needed.
        refreshCache();

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
            });
            config.plugins.enableCors(cors -> cors.add(corsConfig -> corsConfig.anyHost()));
        }).start(PORT);

        app.get("/", ctx -> ctx.redirect("/index.html"));

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/hubs", ctx -> {
            List<Hub> hubs = hubs();
            if (hubs == null) {
                ctx.status(HttpStatus.BAD_GATEWAY)
                        .json(Map.of("error", "could not reach ingestion-service at " + INGESTION_URL));
                return;
            }
            ctx.json(hubs);
        });

        app.get("/hubs/{hubId}", ctx -> {
            List<Hub> hubs = hubs();
            if (hubs == null) {
                ctx.status(HttpStatus.BAD_GATEWAY)
                        .json(Map.of("error", "could not reach ingestion-service at " + INGESTION_URL));
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

    /** Returns the cached hub list, attempting a fresh fetch from ingestion-service if the cache is empty. */
    private static List<Hub> hubs() {
        List<Hub> cached = CACHE.get();
        return cached != null ? cached : refreshCache();
    }

    private static List<Hub> refreshCache() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(INGESTION_URL)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("ingestion-service returned HTTP " + response.statusCode());
                return null;
            }
            List<Hub> hubs = MAPPER.readValue(response.body(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Hub.class));
            CACHE.set(hubs);
            System.out.printf("Cached %d hubs from ingestion-service%n", hubs.size());
            return hubs;
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to reach ingestion-service: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    static boolean matchesHubId(Hub hub, String requestedId) {
        return hub.hubId().equals(requestedId.trim().toUpperCase(Locale.ROOT));
    }
}
