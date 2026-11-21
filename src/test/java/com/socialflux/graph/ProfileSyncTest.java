package com.socialflux.graph;

import com.socialflux.graph.support.AbstractGraphTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A cópia local dos perfis e o que cada evento {@code profile.*} implica para o grafo. */
class ProfileSyncTest extends AbstractGraphTest {

    private long snapshotRows(UUID id) {
        return countOf("user_snapshot", "user_id", id);
    }

    @Test
    void aProfileCreatedEventMakesTheUserKnownWithZeroedCounters() {
        TestUser ana = newUser();

        assertThat(snapshotRows(ana.id)).isEqualTo(1);
        assertThat(counts(ana)).containsEntry("seguidores", 0).containsEntry("seguidos", 0);
        assertThat(get(newUser(), "/api/relacoes/" + ana.path() + "/estado").status()).isEqualTo(200);
    }

    @Test
    void duplicatedAndStaleEventsAreHarmless() {
        TestUser ana = newUser(false);
        String created = profileEventJson(UUID.randomUUID(), "profile.created.v1", ana);

        profileEvents.handle(created);
        profileEvents.handle(created);   // mesma mensagem entregue duas vezes (inbox)

        ana.version = 5;
        ana.isPrivate = true;
        deliver("profile.updated.v1", ana);   // versão nova: aplica
        ana.version = 3;
        ana.isPrivate = false;
        deliver("profile.updated.v1", ana);   // versão antiga: ignora

        assertThat(jdbc.sql("select is_private from user_snapshot where user_id = :id").param("id", ana.id)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select profile_version from user_snapshot where user_id = :id").param("id", ana.id)
                .query(Long.class).single()).isEqualTo(5L);
    }

    @Test
    void unknownEventTypesAndMalformedPayloadsAreIgnoredOrRejectedWithoutSideEffects() {
        TestUser ana = newUser();
        ana.version++;
        deliver("profile.algo-novo.v1", ana);   // tipo desconhecido: ignora

        assertThat(jdbc.sql("select profile_version from user_snapshot where user_id = :id").param("id", ana.id)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void whenAnAccountBecomesPublicAllPendingRequestsAreAcceptedAutomatically() {
        TestUser priv = newUser(true);
        TestUser a = newUser();
        TestUser b = newUser();
        TestUser c = newUser();
        follow(a, priv);
        follow(b, priv);
        follow(c, priv);
        assertThat(counts(priv)).containsEntry("seguidores", 0);

        setPrivate(priv, false);   // profile.updated + profile.privacy-changed com a mesma versão

        for (TestUser requester : List.of(a, b, c)) {
            assertThat(state(requester, priv)).isEqualTo("SEGUINDO");
            assertThat(counts(requester)).containsEntry("seguidos", 1);
        }
        assertThat(counts(priv)).containsEntry("seguidores", 3);
        assertThat(countOf("follow_request", "target_id", priv.id)).isZero();
        assertThat(get(priv, "/api/relacoes/solicitacoes").json()).containsEntry("total", 0);
        assertConsistent(priv, a, b, c);

        long auto = publishedTypes(priv).stream().filter("follow.created.v1"::equals).count();
        assertThat(auto).isEqualTo(3);
        assertThat(publisher.sent.stream().filter(s -> s.json().contains(priv.id.toString()))
                .filter(s -> s.json().contains("\"origin\":\"AUTO_ON_PUBLIC\"")).count()).isEqualTo(3);
    }

    @Test
    void theSamePrivacyChangeAppliedTwiceDoesNotDoubleCount() {
        TestUser priv = newUser(true);
        TestUser a = newUser();
        follow(a, priv);

        setPrivate(priv, false);
        deliver("profile.privacy-changed.v1", priv);   // reentrega do mesmo estado

        assertThat(counts(priv)).containsEntry("seguidores", 1);
        assertConsistent(priv, a);
    }

    @Test
    void whenAnAccountBecomesPrivateExistingFollowersStayButNewOnesNeedApproval() {
        TestUser pub = newUser(false);
        TestUser old = newUser();
        TestUser fresh = newUser();
        follow(old, pub);

        setPrivate(pub, true);

        assertThat(state(old, pub)).isEqualTo("SEGUINDO");
        assertThat(follow(fresh, pub).json()).containsEntry("estado", "SOLICITADO");
        assertConsistent(pub, old, fresh);
    }

    @Test
    void deletingAnAccountRemovesItsEdgesOnBothSidesAndFixesTheOthersCounters() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);
        TestUser carla = newUser();
        follow(ana, bia);       // ana segue bia
        follow(bia, ana);       // bia segue ana
        follow(carla, ana);     // carla segue ana
        follow(ana, priv);      // ana pediu para seguir priv
        assertThat(counts(ana)).containsEntry("seguidores", 2).containsEntry("seguidos", 1);

        deleteProfile(ana);

        assertThat(countOf("follow", "follower_id", ana.id) + countOf("follow", "followee_id", ana.id)).isZero();
        assertThat(countOf("follow_request", "requester_id", ana.id)).isZero();
        assertThat(counts(bia)).containsEntry("seguidores", 0).containsEntry("seguidos", 0);
        assertThat(counts(carla)).containsEntry("seguidos", 0);
        assertThat(counts(priv)).containsEntry("seguidores", 0);
        assertThat(get(priv, "/api/relacoes/solicitacoes").json()).containsEntry("total", 0);   // o pedido de ana sumiu
        assertConsistent(bia, carla, priv);

        // A conta excluída some: 404 para quem consulta e acesso negado para o token antigo dela
        assertThat(get(bia, "/api/relacoes/" + ana.path() + "/estado").status()).isEqualTo(404);
        assertThat(follow(ana, bia).status()).isEqualTo(403);
        assertThat(follow(bia, ana).status()).isEqualTo(404);
        // e desfazer vínculo com uma conta excluída não falha
        assertThat(unfollow(bia, ana).status()).isEqualTo(204);
        // reentrega do mesmo evento é inócua
        deliver("profile.deleted.v1", ana);
        assertConsistent(bia, carla, priv);
    }

    @Test
    void aDeleteThatArrivesBeforeTheCreateLeavesATombstoneSoALateCreateDoesNotReviveTheAccount() {
        TestUser ghost = unknownUser();
        ghost.version = 4;
        deliver("profile.deleted.v1", ghost);

        ghost.version = 1;   // o profile.created (mais antigo) chega atrasado
        deliver("profile.created.v1", ghost);

        assertThat(get(newUser(), "/api/relacoes/" + ghost.path() + "/estado").status()).isEqualTo(404);
        assertThat(jdbc.sql("select status from user_snapshot where user_id = :id").param("id", ghost.id)
                .query(String.class).single()).isEqualTo("DELETED");
    }

    @Test
    void deletedUsersDoNotAppearInTheListsOfOthers() {
        TestUser star = newUser();
        TestUser keep = newUser();
        TestUser gone = newUser();
        follow(keep, star);
        follow(gone, star);

        deleteProfile(gone);

        String body = get(star, "/api/relacoes/" + star.path() + "/seguidores").body();
        assertThat(body).contains(keep.username).doesNotContain(gone.username);
        assertThat(counts(star)).containsEntry("seguidores", 1);
    }
}
