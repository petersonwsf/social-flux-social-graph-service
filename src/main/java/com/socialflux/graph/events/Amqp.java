package com.socialflux.graph.events;

import com.socialflux.events.EventQueues;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filas de consumo deste serviço. O exchange {@code topic} compartilhado ({@code socialflux.events}) e a fila de
 * mensagens mortas são declarados pela biblioteca {@code socialflux-events}; aqui só as filas do grafo e as ligações.
 */
@Configuration
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class Amqp {

    public static final String PROFILE_QUEUE = "social-graph.profile-events";

    /** Eventos do perfil (criação, mudança de dados e de privacidade, exclusão) publicados pelo profile_service. */
    @Bean
    Queue profileQueue() {
        return EventQueues.durable(PROFILE_QUEUE);
    }

    @Bean
    Binding profileBinding(@Qualifier("profileQueue") Queue profileQueue,
                           @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(profileQueue, exchange, "profile.#");
    }

}
