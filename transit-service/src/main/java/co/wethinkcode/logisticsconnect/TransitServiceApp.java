package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

public class TransitServiceApp {

    private static final String HUB_SERVICE_URL = "http://localhost:7051";
    private static final String DELAY_STAGE_SERVICE_URL = "http://localhost:7052";

    // ETA formula: a fixed baseline transit time, plus a widening window driven by delay stage.
    // Arbitrary but documented — the exact numbers aren't the point, the shape (stage worsens
    // both when it arrives and how uncertain that is) is.
    private static final long BASE_HOURS = 24;
    private static final long BASE_WINDOW_HOURS = 4;
    private static final long HOURS_PER_STAGE = 6;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record DelayStage(String hubId, int stage) {
    }

    public record EtaResponse(String hubId, String province, String sortingCenter, int delayStage,
                               String etaWindowStart, String etaWindowEnd) {
    }

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7053);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/eta/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").trim().toUpperCase(Locale.ROOT);

            Hub hub;
            try {
                hub = fetchHub(hubId);
            } catch (UpstreamNotFoundException e) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "no hub with id " + hubId));
                return;
            } catch (IOException | InterruptedException e) {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "could not reach hub-service: " + e.getMessage()));
                return;
            }

            // Stage 2: synchronous call to delay-stage-service. If it's unreachable we degrade
            // gracefully to stage 0 rather than failing the whole ETA — a missing delay signal
            // shouldn't block a response entirely. (This call is replaced by an MQ subscription
            // in Stage 3.)
            int delayStage = fetchDelayStage(hubId);

            Instant now = Instant.now();
            Instant windowStart = now.plus(BASE_HOURS, ChronoUnit.HOURS);
            Instant windowEnd = windowStart.plus(BASE_WINDOW_HOURS + delayStage * HOURS_PER_STAGE, ChronoUnit.HOURS);

            ctx.json(new EtaResponse(hub.hubId(), hub.province(), hub.sortingCenter(), delayStage,
                    windowStart.toString(), windowEnd.toString()));
        });
    }

    private static class UpstreamNotFoundException extends RuntimeException {
    }

    private static Hub fetchHub(String hubId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(HUB_SERVICE_URL + "/hubs/" + hubId)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new UpstreamNotFoundException();
        }
        if (response.statusCode() != 200) {
            throw new IOException("hub-service returned HTTP " + response.statusCode());
        }
        return MAPPER.readValue(response.body(), Hub.class);
    }

    /** Returns the current delay stage, or 0 if delay-stage-service can't be reached or the hub is unseen. */
    private static int fetchDelayStage(String hubId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(DELAY_STAGE_SERVICE_URL + "/delay-stage/" + hubId)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("delay-stage-service returned HTTP " + response.statusCode() + ", defaulting to stage 0");
                return 0;
            }
            return MAPPER.readValue(response.body(), DelayStage.class).stage();
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to reach delay-stage-service, defaulting to stage 0: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }
}

// MQ TODO: subscribes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL (see co.wethinkcode.logisticsconnect.mq.MqConfig)
