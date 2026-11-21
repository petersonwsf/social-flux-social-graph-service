package com.socialflux.graph.service;

import com.socialflux.graph.web.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Limitador de janela deslizante em memória (1 instância; trocar por Redis ao escalar). */
@Component
public class RateLimiter {

    private static final Duration RETENTION = Duration.ofHours(2);

    private final ConcurrentMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Registra uma ocorrência e lança 429 se a chave já tiver {@code max} dentro da janela. */
    public void hit(String key, int max, Duration window) {
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (deque) {
            Instant limit = now.minus(window);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(limit)) {
                deque.removeFirst();
            }
            if (deque.size() >= max) {
                long retry = Math.max(1, Duration.between(now, deque.peekFirst().plus(window)).toSeconds());
                throw new ApiException.TooManyRequests(retry);
            }
            deque.addLast(now);
        }
    }

    @Scheduled(fixedDelayString = "PT10M")
    void cleanup() {
        Instant limit = clock.instant().minus(RETENTION);
        hits.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || entry.getValue().peekLast().isBefore(limit);
            }
        });
    }
}
