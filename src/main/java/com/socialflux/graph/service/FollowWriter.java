package com.socialflux.graph.service;

import com.socialflux.events.EventIds;
import com.socialflux.events.Outbox;
import com.socialflux.graph.domain.FollowRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * As transições de estado do grafo. Cada método faz, NA MESMA TRANSAÇÃO (que o chamador precisa ter aberto), a mudança
 * nas tabelas, o ajuste dos contadores e a gravação do evento no outbox. As REGRAS (quem pode, limites, privacidade) ficam
 * no {@link GraphService}; aqui só há o mecanismo. O chamador deve ter travado o par ({@code FollowRepository#lockPair}).
 * <p>Eventos: o agregado é o vínculo ({@code relationId}) ou a solicitação ({@code requestId}); como cada vínculo novo tem
 * id novo, {@code aggregateVersion} é sempre 1. Os payloads só têm ids e datas, nunca dados pessoais.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class FollowWriter {

    public static final String ORIGIN_DIRECT = "DIRECT";
    public static final String ORIGIN_REQUEST_ACCEPTED = "REQUEST_ACCEPTED";
    public static final String ORIGIN_AUTO_ON_PUBLIC = "AUTO_ON_PUBLIC";

    public static final String REASON_UNFOLLOW = "UNFOLLOW";
    public static final String REASON_REMOVED_BY_FOLLOWEE = "REMOVED_BY_FOLLOWEE";

    public static final String FOLLOW_CREATED = "follow.created.v1";
    public static final String FOLLOW_REMOVED = "follow.removed.v1";
    public static final String FOLLOW_REQUESTED = "follow.requested.v1";
    public static final String FOLLOW_REQUEST_CANCELLED = "follow.request-cancelled.v1";
    public static final String FOLLOW_REQUEST_REJECTED = "follow.request-rejected.v1";

    private final FollowRepository follows;
    private final Outbox outbox;
    private final Clock clock;

    public FollowWriter(FollowRepository follows, Outbox outbox, Clock clock) {
        this.follows = follows;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Cria a aresta {@code follower -> followee}. */
    public void addFollow(UUID follower, UUID followee, String origin) {
        UUID relationId = EventIds.v7(clock);
        OffsetDateTime at = now();
        follows.insertFollow(relationId, follower, followee, origin, at);
        follows.addCounts(followee, 1, 0, 0);
        follows.addCounts(follower, 0, 1, 0);
        outbox.add(FOLLOW_CREATED, relationId, 1, edgeData(relationId, follower, followee, "origin", origin, at));
    }

    /** Cria a solicitação {@code requester -> target} (conta privada). */
    public void addRequest(UUID requester, UUID target) {
        UUID requestId = EventIds.v7(clock);
        OffsetDateTime at = now();
        follows.insertRequest(requestId, requester, target, at);
        follows.addCounts(target, 0, 0, 1);
        Map<String, Object> data = requestData(requestId, requester, target);
        data.put("requestedAt", at.toInstant().toString());
        outbox.add(FOLLOW_REQUESTED, requestId, 1, data);
    }

    /** Remove a aresta. Devolve {@code false} se ela não existia. */
    public boolean removeFollow(UUID follower, UUID followee, String reason) {
        Optional<UUID> removed = follows.deleteFollow(follower, followee);
        if (removed.isEmpty()) {
            return false;
        }
        follows.addCounts(followee, -1, 0, 0);
        follows.addCounts(follower, 0, -1, 0);
        outbox.add(FOLLOW_REMOVED, removed.get(), 1,
                edgeData(removed.get(), follower, followee, "reason", reason, null));
        return true;
    }

    /** O solicitante desistiu. Devolve {@code false} se não havia solicitação. */
    public boolean cancelRequest(UUID requester, UUID target) {
        return dropRequest(requester, target, FOLLOW_REQUEST_CANCELLED);
    }

    /** O alvo recusou. Devolve {@code false} se não havia solicitação. */
    public boolean rejectRequest(UUID requester, UUID target) {
        return dropRequest(requester, target, FOLLOW_REQUEST_REJECTED);
    }

    /** Transforma a solicitação em aresta. Devolve {@code false} se não havia solicitação. */
    public boolean acceptRequest(UUID requester, UUID target, String origin) {
        Optional<UUID> request = follows.deleteRequest(requester, target);
        if (request.isEmpty()) {
            return false;
        }
        follows.addCounts(target, 0, 0, -1);
        addFollow(requester, target, origin);
        return true;
    }

    private boolean dropRequest(UUID requester, UUID target, String eventType) {
        Optional<UUID> request = follows.deleteRequest(requester, target);
        if (request.isEmpty()) {
            return false;
        }
        follows.addCounts(target, 0, 0, -1);
        outbox.add(eventType, request.get(), 1, requestData(request.get(), requester, target));
        return true;
    }

    private OffsetDateTime now() {
        // O PostgreSQL guarda microssegundos: truncar aqui mantém o cursor de paginação idêntico ao valor gravado.
        Instant instant = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Map<String, Object> edgeData(UUID relationId, UUID follower, UUID followee, String extraKey,
                                                String extraValue, OffsetDateTime createdAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relationId", relationId.toString());
        data.put("followerId", follower.toString());
        data.put("followeeId", followee.toString());
        data.put(extraKey, extraValue);
        if (createdAt != null) {
            data.put("createdAt", createdAt.toInstant().toString());
        }
        return data;
    }

    private static Map<String, Object> requestData(UUID requestId, UUID requester, UUID target) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId.toString());
        data.put("requesterId", requester.toString());
        data.put("targetId", target.toString());
        return data;
    }
}
