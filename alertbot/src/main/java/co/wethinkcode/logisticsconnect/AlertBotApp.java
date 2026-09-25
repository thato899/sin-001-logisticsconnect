package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class AlertBotApp {

    // Chosen threshold: stage 6+ (of 0-8) is "this is now bad enough to tell the public about" —
    // e.g. severe weather shutdowns rather than routine sorting delays.
    private static final int ALERT_THRESHOLD = 6;
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "7054"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // hubId -> last stage seen, so we alert on crossing the threshold, not on every message
    // that happens to still be above it.
    private static final Map<String, Integer> LAST_STAGE = new ConcurrentHashMap<>();

    private static final List<Alert> ALERTS = new CopyOnWriteArrayList<>();
    private static final Path STATE_FILE = Path.of(System.getenv().getOrDefault("STATE_FILE", "data/alerts.json"));
    private static final Path STAGE_FILE = STATE_FILE.resolveSibling("last-stages.json");

    public record PackageStatusMessage(String hubId, int stage, String timestamp) {
    }

    public record Alert(String hubId, int stage, String postedAt, String message) {
    }

    public static void main(String[] args) {
        loadAlerts();
        loadStages();
        subscribeMq();

        Javalin app = Javalin.create(config ->
                config.plugins.enableCors(cors -> cors.add(corsConfig -> corsConfig.anyHost()))).start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alerts", ctx -> ctx.json(ALERTS));
    }

    private static void subscribeMq() {
        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            Connection connection = factory.createConnection();
            connection.setClientID("logisticsconnect-alertbot");
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            session.createDurableSubscriber(topic, "alertbot").setMessageListener(message -> {
                try {
                    String json = ((TextMessage) message).getText();
                    PackageStatusMessage status = MAPPER.readValue(json, PackageStatusMessage.class);
                    handleStageUpdate(status);
                } catch (Exception e) {
                    System.err.println("Failed to process package-status-topic message: " + e.getMessage());
                }
            });
            System.out.println("Subscribed to " + MqConfig.TOPIC + " at " + MqConfig.BROKER_URL
                    + " (alert threshold: stage >= " + ALERT_THRESHOLD + ")");
        } catch (JMSException e) {
            System.err.println("Could not connect to ActiveMQ broker (no alerts will fire until it's "
                    + "reachable and this service is restarted): " + e.getMessage());
        }
    }

    private static void handleStageUpdate(PackageStatusMessage status) {
        Integer previous = LAST_STAGE.put(status.hubId(), status.stage());
        saveStages();
        boolean crossedUpward = crossedThreshold(previous, status.stage());
        if (crossedUpward) {
            postSimulatedAlert(status);
        }
    }

    /** "Posts" a simulated public alert — just a distinct log line + an in-memory record, per the brief. */
    private static void postSimulatedAlert(PackageStatusMessage status) {
        String text = String.format(
                "Delays reported at hub %s (stage %d/8) — expect longer transit times until this clears.",
                status.hubId(), status.stage());
        Alert alert = new Alert(status.hubId(), status.stage(), Instant.now().toString(), text);
        ALERTS.add(alert);
        saveAlerts();
        System.out.println("[SIMULATED SOCIAL POST] " + text);
    }

    static boolean crossedThreshold(Integer previous, int current) {
        return current >= ALERT_THRESHOLD && (previous == null || previous < ALERT_THRESHOLD);
    }

    private static void loadAlerts() {
        if (!Files.exists(STATE_FILE)) {
            return;
        }
        try {
            List<Alert> saved = MAPPER.readValue(Files.readString(STATE_FILE),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Alert.class));
            ALERTS.addAll(saved);
        } catch (IOException e) {
            System.err.println("Could not load alert history; starting empty: " + e.getMessage());
        }
    }

    private static synchronized void saveAlerts() {
        try {
            Files.createDirectories(STATE_FILE.toAbsolutePath().getParent());
            Path temporary = STATE_FILE.resolveSibling(STATE_FILE.getFileName() + ".tmp");
            Files.writeString(temporary, MAPPER.writeValueAsString(ALERTS));
            try {
                Files.move(temporary, STATE_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, STATE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Could not persist alert history: " + e.getMessage());
        }
    }

    private static void loadStages() {
        if (!Files.exists(STAGE_FILE)) {
            return;
        }
        try {
            Map<String, Integer> saved = MAPPER.readValue(Files.readString(STAGE_FILE),
                    MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Integer.class));
            LAST_STAGE.putAll(saved);
        } catch (IOException e) {
            System.err.println("Could not load alert stage history; starting empty: " + e.getMessage());
        }
    }

    private static synchronized void saveStages() {
        try {
            Files.createDirectories(STAGE_FILE.toAbsolutePath().getParent());
            Path temporary = STAGE_FILE.resolveSibling(STAGE_FILE.getFileName() + ".tmp");
            Files.writeString(temporary, MAPPER.writeValueAsString(LAST_STAGE));
            try {
                Files.move(temporary, STAGE_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, STAGE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Could not persist alert stage history: " + e.getMessage());
        }
    }
}
