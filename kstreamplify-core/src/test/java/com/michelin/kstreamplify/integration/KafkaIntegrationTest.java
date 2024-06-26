package com.michelin.kstreamplify.integration;

import static org.apache.kafka.streams.StreamsConfig.BOOTSTRAP_SERVERS_CONFIG;

import com.michelin.kstreamplify.context.KafkaStreamsExecutionContext;
import com.michelin.kstreamplify.initializer.KafkaStreamsInitializer;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.KafkaStreams;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

@Slf4j
abstract class KafkaIntegrationTest {
    protected static final String CONFLUENT_PLATFORM_VERSION = "7.6.1";
    protected final HttpClient httpClient = HttpClient.newBuilder().build();
    protected KafkaStreamsInitializer initializer;

    protected static void createTopics(String bootstrapServers, String... topics) {
        var newTopics = Arrays.stream(topics)
            .map(topic -> new NewTopic(topic, 1, (short) 1))
            .toList();
        try (var admin = AdminClient.create(Map.of(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(newTopics);
        }
    }

    protected void waitingForKafkaStreamsToRun() throws InterruptedException {
        while (!initializer.getKafkaStreams().state().equals(KafkaStreams.State.RUNNING)) {
            log.info("Waiting for Kafka Streams to start...");
            Thread.sleep(2000);
        }
    }

    @AllArgsConstructor
    static class KafkaStreamInitializerStub extends KafkaStreamsInitializer {
        private String bootstrapServers;

        @Override
        protected void initProperties() {
            super.initProperties();
            KafkaStreamsExecutionContext.getProperties()
                .setProperty(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        }
    }
}
