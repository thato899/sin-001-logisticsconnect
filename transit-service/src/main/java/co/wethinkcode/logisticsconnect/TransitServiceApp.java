package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TransitServiceApp {

    private static final String HUB_SERVICE_URL = System.getenv().getOrDefault("HUB_SERVICE_URL", "http://localhost:7051");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "7053"));

    // ETA formula: a fixed baseline transit time, plus a widening window driven by delay stage.
    // Arbitrary but documented — the exact numbers aren't the point, the shape (stage worsens
    // both when it arrives and how uncertain that is) is.
    private static final long BASE_HOURS = 24;
    private static final long BASE_WINDOW_HOURS = 4;
    private static final long HOURS_PER_STAGE = 6;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // hubId -> last delay stage seen on package-status-topic. Stage 3: this replaces the
    // synchronous GET :7052/delay-stage/{hubId} call — a hub with no message yet defaults to 0,
    // same fallback behavior as the old HTTP call had when delay-stage-service was unreachable.
    private static final Map<String, Integer> DELAY_STAGE_CACHE = new ConcurrentHashMap<>();

    public record PackageStatusMessage(String hubId, int stage, String timestamp) {
    }

    public record EtaResponse(String hubId, String province, String sortingCenter, int delayStage,
                               String etaWindowStart, String etaWindowEnd) {
    }

    public static void main(String[] args) {
        subscribeMq();

        Javalin app = Javalin.create(config ->
                config.plugins.enableCors(cors -> cors.add(corsConfig -> corsConfig.anyHost()))).start(PORT);

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

            // Stage 3: read the last stage seen from package-status-topic instead of calling
            // delay-stage-service synchronously. Defaults to 0 if no message has arrived yet for
            // this hub — same graceful-degradation behavior the old HTTP call had.
            int delayStage = DELAY_STAGE_CACHE.getOrDefault(hubId, 0);

            Instant now = Instant.now();
            Instant windowStart = now.plus(BASE_HOURS, ChronoUnit.HOURS);
            Instant windowEnd = etaWindowEnd(windowStart, delayStage);

            ctx.json(new EtaResponse(hub.hubId(), hub.province(), hub.sortingCenter(), delayStage,
                    windowStart.toString(), windowEnd.toString()));
        });
    }

    private static class UpstreamNotFoundException extends RuntimeException {
    }

    static Instant etaWindowEnd(Instant windowStart, int delayStage) {
        if (delayStage < 0) {
            throw new IllegalArgumentException("delay stage cannot be negative");
        }
        return windowStart.plus(BASE_WINDOW_HOURS + delayStage * HOURS_PER_STAGE, ChronoUnit.HOURS);
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

    /**
     * Subscribes to package-status-topic and keeps DELAY_STAGE_CACHE current. Best-effort: if the
     * broker isn't reachable at startup, transit-service still boots — ETAs just default every
     * hub to stage 0 (identical fallback to the old delay-stage-service HTTP call) until a
     * connection is possible.
     */
    private static void subscribeMq() {
        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            Connection connection = factory.createConnection();
            connection.setClientID("logisticsconnect-transit-service");
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            session.createDurableSubscriber(topic, "transit-service").setMessageListener(message -> {
                try {
                    String json = ((TextMessage) message).getText();
                    PackageStatusMessage status = MAPPER.readValue(json, PackageStatusMessage.class);
                    DELAY_STAGE_CACHE.put(status.hubId(), status.stage());
                    System.out.printf("Received stage update: %s -> stage %d%n", status.hubId(), status.stage());
                } catch (Exception e) {
                    System.err.println("Failed to process package-status-topic message: " + e.getMessage());
                }
            });
            System.out.println("Subscribed to " + MqConfig.TOPIC + " at " + MqConfig.BROKER_URL);
        } catch (JMSException e) {
            System.err.println("Could not connect to ActiveMQ broker (delay stages will default to 0 until it's "
                    + "reachable and this service is restarted): " + e.getMessage());
        }
    }
}
