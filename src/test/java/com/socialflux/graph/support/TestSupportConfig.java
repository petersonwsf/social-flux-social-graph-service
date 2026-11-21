package com.socialflux.graph.support;

import com.socialflux.events.EventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@TestConfiguration
public class TestSupportConfig {

    @Bean
    @Primary
    RecordingPublisher recordingPublisher() {
        return new RecordingPublisher();
    }

    /** Substitui o RabbitMQ: guarda o que o relay publicou e permite simular o broker fora do ar. */
    public static class RecordingPublisher implements EventPublisher {

        public record Sent(UUID id, String routingKey, String json) {
        }

        public final List<Sent> sent = new CopyOnWriteArrayList<>();
        public volatile boolean down;

        @Override
        public void publish(UUID messageId, String routingKey, String json) {
            if (down) {
                throw new IllegalStateException("broker fora do ar (simulado)");
            }
            sent.add(new Sent(messageId, routingKey, json));
        }
    }
}
