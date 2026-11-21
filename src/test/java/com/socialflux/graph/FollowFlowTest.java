package com.socialflux.graph;

import com.socialflux.graph.support.AbstractGraphTest;
import com.socialflux.graph.support.Http.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Seguir, solicitar, aceitar, recusar, cancelar, deixar de seguir e remover seguidor, com contadores e eventos. */
class FollowFlowTest extends AbstractGraphTest {

    @Test
    void followingAPublicAccountIsImmediateAndIdempotent() {
        TestUser ana = newUser();
        TestUser bia = newUser();

        Response first = follow(ana, bia);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.json()).containsEntry("estado", "SEGUINDO");

        Response again = follow(ana, bia);
        assertThat(again.status()).isEqualTo(200);   // D12: repetir devolve o estado atual
        assertThat(again.json()).containsEntry("estado", "SEGUINDO");

        assertThat(state(ana, bia)).isEqualTo("SEGUINDO");
        assertThat(counts(bia)).containsEntry("seguidores", 1).containsEntry("seguidos", 0);
        assertThat(counts(ana)).containsEntry("seguidores", 0).containsEntry("seguidos", 1);
        assertThat(countOf("follow", "follower_id", ana.id)).isEqualTo(1);

        Map<String, Object> view = get(bia, "/api/relacoes/" + ana.path() + "/estado").json();
        assertThat(view).containsEntry("estado", "NENHUM").containsEntry("segueVoce", true)
                .containsEntry("solicitouVoce", false);
    }

    @Test
    void followingAPrivateAccountCreatesARequestUntilTheOwnerAccepts() {
        TestUser ana = newUser();
        TestUser priv = newUser(true);

        Response requested = follow(ana, priv);
        assertThat(requested.status()).isEqualTo(201);
        assertThat(requested.json()).containsEntry("estado", "SOLICITADO");
        assertThat(follow(ana, priv).status()).isEqualTo(200);   // idempotente
        assertThat(state(ana, priv)).isEqualTo("SOLICITADO");
        assertThat(counts(priv)).containsEntry("seguidores", 0);
        assertThat(countOf("follow_request", "target_id", priv.id)).isEqualTo(1);

        Map<String, Object> received = get(priv, "/api/relacoes/solicitacoes").json();
        List<?> items = (List<?>) received.get("itens");
        assertThat(items).hasSize(1);
        assertThat(received).containsEntry("total", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) ((Map<String, Object>) items.get(0)).get("usuario");
        assertThat(user.get("id")).isEqualTo(ana.id.toString());
        assertThat(user).doesNotContainKey("accountId");
        assertThat(user.keySet()).doesNotContain("email", "telefone", "senha");

        assertThat(accept(priv, ana).status()).isEqualTo(204);
        assertThat(state(ana, priv)).isEqualTo("SEGUINDO");
        assertThat(counts(priv)).containsEntry("seguidores", 1);
        assertThat(countOf("follow_request", "target_id", priv.id)).isZero();
        assertThat(get(priv, "/api/relacoes/solicitacoes").json().get("itens")).isEqualTo(List.of());
        assertThat(get(priv, "/api/relacoes/solicitacoes").json()).containsEntry("total", 0);
    }

    @Test
    void theRequesterCanCancelAndTheOwnerCanReject() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);

        follow(ana, priv);
        assertThat(unfollow(ana, priv).status()).isEqualTo(204);   // cancelar
        assertThat(state(ana, priv)).isEqualTo("NENHUM");

        follow(bia, priv);
        assertThat(reject(priv, bia).status()).isEqualTo(204);
        assertThat(state(bia, priv)).isEqualTo("NENHUM");
        assertThat(reject(priv, bia).status()).isEqualTo(404);   // já não existe
        assertThat(get(priv, "/api/relacoes/solicitacoes").json()).containsEntry("total", 0);
        assertThat(counts(priv)).containsEntry("seguidores", 0);

        // recusar não deixa rastro: dá para pedir de novo
        assertThat(follow(bia, priv).status()).isEqualTo(201);
    }

    @Test
    void unfollowingAndRemovingAFollowerAreIdempotentAndAdjustCounters() {
        TestUser ana = newUser();
        TestUser bia = newUser();

        assertThat(unfollow(ana, bia).status()).isEqualTo(204);   // sem relação: não faz nada

        follow(ana, bia);
        assertThat(unfollow(ana, bia).status()).isEqualTo(204);
        assertThat(unfollow(ana, bia).status()).isEqualTo(204);
        assertThat(counts(bia)).containsEntry("seguidores", 0);
        assertThat(counts(ana)).containsEntry("seguidos", 0);

        follow(ana, bia);
        // Bia (a seguida) tira Ana da lista dela
        Response removed = http().send("DELETE", "/api/relacoes/seguidores/" + ana.path(), null, bia.token);
        assertThat(removed.status()).isEqualTo(204);
        assertThat(state(ana, bia)).isEqualTo("NENHUM");
        assertThat(counts(bia)).containsEntry("seguidores", 0);
        assertThat(counts(ana)).containsEntry("seguidos", 0);
    }

    @Test
    void validationErrors() {
        TestUser ana = newUser();

        Response self = follow(ana, ana);
        assertThat(self.status()).isEqualTo(422);
        assertThat(self.json()).containsEntry("codigo", "seguir_a_si_mesmo").containsEntry("sucesso", false);

        Response unknown = http().send("PUT", "/api/relacoes/seguindo/" + UUID.randomUUID(), null, ana.token);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.json()).containsEntry("codigo", "usuario_nao_encontrado");

        assertThat(http().send("PUT", "/api/relacoes/seguindo/nao-e-um-id", null, ana.token).status()).isEqualTo(400);
        assertThat(http().send("PUT", "/api/relacoes/seguindo/999999999", null, ana.token).status()).isEqualTo(400);   // id inteiro: só UUID
        assertThat(get(ana, "/api/relacoes/" + ana.path() + "/seguidores?limite=0").status()).isEqualTo(400);
        assertThat(get(ana, "/api/relacoes/" + ana.path() + "/seguidores?limite=101").status()).isEqualTo(400);
        assertThat(get(ana, "/api/relacoes/" + ana.path() + "/seguidores?cursor=lixo").status()).isEqualTo(400);
        assertThat(get(ana, "/api/relacoes/" + ana.path() + "/seguidores?limite=abc").status()).isEqualTo(400);
    }

    @Test
    void onlyTheAccountUuidIsAcceptedInTheUrl() {
        TestUser ana = newUser();

        assertThat(http().send("PUT", "/api/relacoes/seguindo/1001", null, ana.token).status()).isEqualTo(400);
        assertThat(get(ana, "/api/relacoes/1001/estado").status()).isEqualTo(400);
    }

    @Test
    void anActorWhoseProfileHasNotArrivedYetGetsAClearTemporaryError() {
        TestUser bia = newUser();
        TestUser ghost = unknownUser();   // o token é válido, mas o evento profile.created ainda não chegou

        Response response = follow(ghost, bia);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json()).containsEntry("codigo", "perfil_indisponivel");
    }

    @Test
    void everyChangeIsRecordedInTheOutboxWithoutPersonalData() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);

        follow(ana, bia);
        unfollow(ana, bia);
        follow(ana, priv);
        unfollow(ana, priv);
        follow(ana, priv);
        accept(priv, ana);
        follow(bia, priv);
        reject(priv, bia);
        unfollow(ana, priv);

        assertThat(publishedTypes(ana)).containsExactly(
                "follow.created.v1", "follow.removed.v1",
                "follow.requested.v1", "follow.request-cancelled.v1",
                "follow.requested.v1", "follow.created.v1", "follow.removed.v1");
        assertThat(publishedTypes(bia)).contains("follow.request-rejected.v1");

        String payloads = publisher.sent.stream().filter(s -> s.json().contains(ana.id.toString()))
                .map(s -> s.json()).reduce("", String::concat);
        assertThat(payloads).doesNotContain(ana.username).doesNotContain("email").doesNotContain("telefone");
        assertThat(payloads).contains("\"origin\":\"DIRECT\"").contains("\"origin\":\"REQUEST_ACCEPTED\"")
                .contains("\"reason\":\"UNFOLLOW\"");
    }
}
