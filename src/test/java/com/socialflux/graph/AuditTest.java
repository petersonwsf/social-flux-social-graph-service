package com.socialflux.graph;

import com.socialflux.audit.AuditMode;
import com.socialflux.audit.InMemoryAuditRecorder;
import com.socialflux.graph.support.AbstractGraphTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Auditoria central do grafo: aprovar/recusar solicitação e remover seguidor. Seguir e deixar de seguir não entram. */
class AuditTest extends AbstractGraphTest {

    @Autowired
    InMemoryAuditRecorder audit;

    @BeforeEach
    void clear() {
        audit.clear();
    }

    @Test
    void approvingAndRejectingRequestsAreAudited() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);

        follow(ana, priv);
        follow(bia, priv);
        assertThat(audit.recorded()).as("seguir/solicitar não são auditados").isEmpty();

        assertThat(accept(priv, ana).status()).isEqualTo(204);
        assertThat(audit.recorded("FOLLOW_REQUEST_APPROVED")).singleElement().satisfies(item -> {
            assertThat(item.mode()).isEqualTo(AuditMode.IN_TRANSACTION);
            assertThat(item.payload()).containsEntry("actorId", priv.id.toString())
                    .containsEntry("targetType", "USER").containsEntry("targetId", ana.id.toString())
                    .containsEntry("service", "graph");
        });

        assertThat(reject(priv, bia).status()).isEqualTo(204);
        assertThat(audit.recorded("FOLLOW_REQUEST_REJECTED")).singleElement()
                .satisfies(item -> assertThat(item.payload()).containsEntry("targetId", bia.id.toString()));

        // Sem solicitação a operação dá 404 e nada é auditado.
        audit.clear();
        assertThat(reject(priv, bia).status()).isEqualTo(404);
        assertThat(audit.recorded()).isEmpty();
    }

    @Test
    void removingAFollowerIsAuditedOnlyWhenTheEdgeExisted() {
        TestUser ana = newUser();
        TestUser bia = newUser();

        follow(ana, bia);
        assertThat(http().send("DELETE", "/api/relacoes/seguidores/" + ana.path(), null, bia.token).status())
                .isEqualTo(204);
        assertThat(audit.recorded("FOLLOWER_REMOVED")).singleElement().satisfies(item ->
                assertThat(item.payload()).containsEntry("actorId", bia.id.toString())
                        .containsEntry("targetId", ana.id.toString()));

        audit.clear();
        assertThat(http().send("DELETE", "/api/relacoes/seguidores/" + ana.path(), null, bia.token).status())
                .isEqualTo(204);   // idempotente: sem aresta, nada a registrar
        assertThat(audit.recorded()).isEmpty();

        unfollow(ana, bia);
        assertThat(audit.recorded()).as("deixar de seguir não é auditado").isEmpty();
    }
}
