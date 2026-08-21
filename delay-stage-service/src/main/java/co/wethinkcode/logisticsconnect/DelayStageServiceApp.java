package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DelayStageServiceApp {

    private static final int MIN_STAGE = 0;
    private static final int MAX_STAGE = 8;

    // hubId -> current delay stage. Absence means "never reported", not "stage 0" — GET below
    // defaults an unseen hub to 0 for callers, but the map itself only holds what's been set.
    private static final Map<String, Integer> STAGES = new ConcurrentHashMap<>();

    public record DelayStage(String hubId, int stage) {
    }

    public record StageUpdateRequest(Integer stage) {
    }

    public static void main(String[] args) {
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

            // MQ TODO (Stage 3): publish { hubId, stage, timestamp } to MqConfig.TOPIC here.
        });
    }

    private static String normalizeHubId(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}

// MQ TODO: publishes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL (see co.wethinkcode.logisticsconnect.mq.MqConfig)
