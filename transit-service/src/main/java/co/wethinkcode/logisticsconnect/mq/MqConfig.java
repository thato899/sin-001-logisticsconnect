package co.wethinkcode.logisticsconnect.mq;

/**
 * Shared by every producer/consumer service that talks to the "package-status-topic"
 * ActiveMQ topic. Duplicated into each participating service's own source tree,
 * since these are independent Maven projects with no shared parent pom.
 */
public final class MqConfig {

    public static final String BROKER_URL = System.getenv().getOrDefault("BROKER_URL",
            "failover:(tcp://localhost:61616)?maxReconnectAttempts=-1&initialReconnectDelay=1000");
    public static final String TOPIC = "package-status-topic";

    private MqConfig() {
    }
}
