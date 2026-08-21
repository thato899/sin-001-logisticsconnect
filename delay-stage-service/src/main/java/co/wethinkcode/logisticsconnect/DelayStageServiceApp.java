package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DelayStageServiceApp {

    private static final int MIN_STAGE = 0;
    private static final int MAX_STAGE = 8;

    // hubId -> current delay stage. Absence means "never reported", not "stage 0" — GET below
    // defaults an unseen hub to 0 for callers, but the map itself only holds what's been set.
    private static final Map<String, Integer> STAGES = new ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // JMS resources for the package-status-topic producer. Null until connectMq() succeeds;
    // publishStageChange() retries lazily so the broker can come up after this service does.
    private static volatile Session mqSession;
    private static volatile MessageProducer mqProducer;

    public record DelayStage(String hubId, int stage) {
    }

    public record StageUpdateRequest(Integer stage) {
    }

    public record PackageStatusMessage(String hubId, int stage, String timestamp) {
    }

    public static void main(String[] args) {
        connectMq(); // best-effort; publishStageChange() retries if this fails

        Javalin app = Javalin.create().start(7052);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/delay-stage/{hubId}", ctx -> {
            String hubId = normalizeHubId(ctx.pathParam("hubId"));
            int stage = STAGES.getOrDefault(hubId, 0);
            ctx.json(new DelayStage(hubId, stage));
        });

        app.post("/delay-stage/{hubId}", ctx -> {
            String hubId = normalizeHubId(ctx.pathParam("hubId"));

            StageUpdateRequest body;
            try {
                body = ctx.bodyAsClass(StageUpdateRequest.class);
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST)
                        .json(Map.of("error", "malformed JSON body, expected { \"stage\": <0-8> }"));
                return;
            }

            if (body.stage() == null || body.stage() < MIN_STAGE || body.stage() > MAX_STAGE) {
                ctx.status(HttpStatus.BAD_REQUEST)
                        .json(Map.of("error", "\"stage\" must be an integer between " + MIN_STAGE + " and " + MAX_STAGE));
                return;
            }

            STAGES.put(hubId, body.stage());
            ctx.status(HttpStatus.OK).json(new DelayStage(hubId, body.stage()));

            publishStageChange(hubId, body.stage());
        });
    }

    private static String normalizeHubId(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static void connectMq() {
        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            Connection connection = factory.createConnection();
            connection.start();
            mqSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = mqSession.createTopic(MqConfig.TOPIC);
            mqProducer = mqSession.createProducer(topic);
            System.out.println("Connected to ActiveMQ broker at " + MqConfig.BROKER_URL
                    + ", publishing to " + MqConfig.TOPIC);
        } catch (JMSException e) {
            System.err.println("Could not connect to ActiveMQ broker ("
                    + "stage changes won't be published until it's reachable): " + e.getMessage());
        }
    }

    /** Publishes a stage change to package-status-topic. Best-effort: a publish failure never fails the REST call. */
    private static void publishStageChange(String hubId, int stage) {
        if (mqProducer == null) {
            connectMq(); // lazy retry — broker may have come up after this service started
        }
        if (mqProducer == null) {
            return; // still unreachable; already logged in connectMq()
        }
        try {
            String json = MAPPER.writeValueAsString(new PackageStatusMessage(hubId, stage, Instant.now().toString()));
            mqProducer.send(mqSession.createTextMessage(json));
        } catch (Exception e) {
            System.err.println("Failed to publish stage change to MQ: " + e.getMessage());
        }
    }
}
