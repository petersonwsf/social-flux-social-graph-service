package com.socialflux.graph.events;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class ProfileEventsListener {

    private final ProfileEventHandler handler;

    public ProfileEventsListener(ProfileEventHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = Amqp.PROFILE_QUEUE)
    void onMessage(Message message) {
        handler.handle(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
