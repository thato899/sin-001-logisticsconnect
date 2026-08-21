package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class AlertBotApp {

    // Chosen threshold: stage 6+ (of 0-8) is "this is now bad enough to tell the public about" —
    // e.g. severe weather shutdowns rather than routine sorting delays.
    private static final int ALERT_THRESHOLD = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // hubId -> last stage seen, so we alert on crossing the threshold, not on every message
    // that happens to still be above it.
    private static final Map<String, Integer> LAST_STAGE = new ConcurrentHashMap<>();

    private static final List<Alert> ALERTS = new CopyOnWriteArrayList<>();

    public record PackageStatusMessage(String hubId, int stage, String timestamp) {
    }

    public record Alert(String hubId, int stage, String postedAt, String message) {
    }

    public static void main(String[] args) {
        subscribeMq();

        Javalin app = Javalin.create().start(7054);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alerts", ctx -> ctx.json(ALERTS));
    }

    private static void subscribeMq() {
        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            Connection connection = factory.createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            session.createConsumer(topic).setMessageListener(message -> {
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
        boolean crossedUpward = status.stage() >= ALERT_THRESHOLD && (previous == null || previous < ALERT_THRESHOLD);
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
        System.out.println("[SIMULATED SOCIAL POST] " + text);
    }
}
