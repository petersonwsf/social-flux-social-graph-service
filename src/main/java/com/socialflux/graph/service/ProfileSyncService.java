package com.socialflux.graph.service;

import com.socialflux.events.EventEnvelope;
import com.socialflux.graph.domain.FollowRepository;
import com.socialflux.graph.domain.SnapshotRepository;
import com.socialflux.graph.domain.UserSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Mantém a cópia local dos perfis ({@code user_snapshot}) a partir dos eventos {@code profile.*} e aplica o que cada
 * mudança implica para o grafo:
 * <ul>
 *   <li>conta que vira PÚBLICA: todas as solicitações pendentes são aceitas automaticamente (D5);</li>
 *   <li>conta EXCLUÍDA: apaga arestas e solicitações dos dois lados e ajusta os contadores de quem ficou.</li>
 * </ul>
 * Cada evento traz {@code aggregateVersion}: versões iguais ou mais antigas que a gravada são ignoradas (duplicado ou fora de
 * ordem). Tipos desconhecidos também. Quem chama já garante a idempotência por id de mensagem (inbox).
 */
@Service
public class ProfileSyncService {

    public static final String PROFILE_CREATED = "profile.created.v1";
    public static final String PROFILE_UPDATED = "profile.updated.v1";
    public static final String PROFILE_PRIVACY_CHANGED = "profile.privacy-changed.v1";
    public static final String PROFILE_DELETED = "profile.deleted.v1";

    private static final Logger log = LoggerFactory.getLogger(ProfileSyncService.class);

    private final SnapshotRepository snapshots;
    private final FollowRepository follows;
    private final FollowWriter writer;
    private final Clock clock;

    public ProfileSyncService(SnapshotRepository snapshots, FollowRepository follows, FollowWriter writer, Clock clock) {
        this.snapshots = snapshots;
        this.follows = follows;
        this.writer = writer;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case PROFILE_CREATED, PROFILE_UPDATED -> upsert(event);
            case PROFILE_PRIVACY_CHANGED -> changePrivacy(event);
            case PROFILE_DELETED -> delete(event);
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }

    private void upsert(EventEnvelope event) {
        UUID id = event.aggregateId();
        Optional<UserSnapshot> current = snapshots.findForUpdate(id);
        if (current.isPresent() && current.get().profileVersion() >= event.aggregateVersion()) {
            return;   // duplicado ou mais antigo que o já aplicado (inclui o "túmulo" de uma conta excluída)
        }
        JsonNode data = event.data();
        UserSnapshot next = new UserSnapshot(id, data.path("username").asString(),
                data.path("displayName").asString(), nullable(data.path("avatarKey")),
                data.path("isPrivate").asBoolean(), UserSnapshot.ACTIVE, event.aggregateVersion());
        if (current.isPresent()) {
            snapshots.update(next, now());
        } else {
            snapshots.insert(next, now());
        }
        if (current.isPresent() && current.get().isPrivate() && !next.isPrivate()) {
            acceptAllPending(id);
        }
    }

    private void changePrivacy(EventEnvelope event) {
        Optional<UserSnapshot> current = snapshots.findForUpdate(event.aggregateId());
        // Mesma versão do profile.updated que acompanha este evento: só ignora se for estritamente mais antigo.
        if (current.isEmpty() || !current.get().isActive() || current.get().profileVersion() > event.aggregateVersion()) {
            return;
        }
        boolean isPrivate = event.data().path("isPrivate").asBoolean();
        UserSnapshot before = current.get();
        if (before.isPrivate() == isPrivate) {
            return;
        }
        snapshots.update(new UserSnapshot(before.userId(), before.username(), before.displayName(),
                before.avatarKey(), isPrivate, before.status(), event.aggregateVersion()), now());
        if (before.isPrivate() && !isPrivate) {
            acceptAllPending(before.userId());
        }
    }

    private void delete(EventEnvelope event) {
        UUID id = event.aggregateId();
        Optional<UserSnapshot> current = snapshots.findForUpdate(id);
        if (current.isPresent() && !current.get().isActive()) {
            return;   // já excluído
        }
        long version = Math.max(event.aggregateVersion(), current.map(UserSnapshot::profileVersion).orElse(0L));
        if (current.isPresent()) {
            UserSnapshot s = current.get();
            snapshots.update(new UserSnapshot(id, s.username(), s.displayName(), null, s.isPrivate(),
                    UserSnapshot.DELETED, version), now());
        } else {
            // Chegou antes do profile.created: guarda um "túmulo" para que um created atrasado (versão menor) não reviva a conta.
            snapshots.insert(new UserSnapshot(id, "excluido-" + id, "", null, false, UserSnapshot.DELETED, version), now());
        }
        follows.purge(id);
    }

    private void acceptAllPending(UUID targetId) {
        for (UUID requester : follows.pendingRequesters(targetId)) {
            follows.lockPair(requester, targetId);
            writer.acceptRequest(requester, targetId, FollowWriter.ORIGIN_AUTO_ON_PUBLIC);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String nullable(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
