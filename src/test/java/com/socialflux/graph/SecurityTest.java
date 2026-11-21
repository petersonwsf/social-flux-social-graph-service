package com.socialflux.graph;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socialflux.graph.support.AbstractGraphTest;
import com.socialflux.graph.support.Http.Response;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Autenticação, autorização (IDOR), privacidade de contas privadas e defesa contra CSRF. */
class SecurityTest extends AbstractGraphTest {

    // ------------------------------------------------------------------ autenticação

    @Test
    void routesRequireAUserToken() {
        TestUser ana = newUser();
        String path = "/api/relacoes/" + ana.path() + "/contagens";

        assertThat(http().get(path, null).status()).isEqualTo(401);
        assertThat(http().get(path, "lixo").status()).isEqualTo(401);
        assertThat(http().get(path, serviceToken()).status()).isEqualTo(401);   // audiência de serviço não é aceita aqui
        assertThat(http().send("PUT", "/api/relacoes/seguindo/" + ana.path(), null, null).status()).isEqualTo(401);
        assertThat(http().get("/actuator/health", null).status()).isEqualTo(200);
        assertThat(http().get("/qualquer-outra-rota", ana.token).status()).isEqualTo(403);   // negar tudo por padrão
    }

    @Test
    void tokensWithAWrongAudienceOrWithoutTheUserRoleAreRefused() {
        TestUser ana = newUser();
        String path = "/api/relacoes/" + ana.path() + "/contagens";
        String otherAudience = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience("outro-sistema")
                .subject(ana.id.toString()).expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", List.of("USER")).build());
        String noRole = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience(USER_AUDIENCE)
                .subject(ana.id.toString()).expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
        String notAUuid = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience(USER_AUDIENCE).subject("svc:x")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000)).claim("roles", List.of("USER")).build());
        String expired = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience(USER_AUDIENCE)
                .subject(ana.id.toString()).expirationTime(new Date(System.currentTimeMillis() - 3_600_000))
                .claim("roles", List.of("USER")).build());

        assertThat(http().get(path, otherAudience).status()).isEqualTo(401);
        assertThat(http().get(path, expired).status()).isEqualTo(401);
        assertThat(http().get(path, noRole).status()).isEqualTo(403);
        assertThat(http().get(path, notAUuid).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ IDOR

    @Test
    void nobodyCanActOnRelationsThatAreNotTheirOwn() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);
        follow(ana, priv);   // pendente: ana -> priv

        // Bia não é a dona da conta privada: não consegue aceitar nem recusar a solicitação de Ana
        assertThat(accept(bia, ana).status()).isEqualTo(404);
        assertThat(reject(bia, ana).status()).isEqualTo(404);
        assertThat(state(ana, priv)).isEqualTo("SOLICITADO");

        // O dono aceita; depois Bia tenta "remover seguidor" Ana: só mexe na lista da própria Bia
        accept(priv, ana);
        assertThat(http().send("DELETE", "/api/relacoes/seguidores/" + ana.path(), null, bia.token).status())
                .isEqualTo(204);
        assertThat(state(ana, priv)).isEqualTo("SEGUINDO");

        // Bia não consegue desfazer o vínculo de Ana: "deixar de seguir" só age sobre o vínculo de quem chama
        assertThat(unfollow(bia, priv).status()).isEqualTo(204);
        assertThat(state(ana, priv)).isEqualTo("SEGUINDO");
        assertConsistent(ana, bia, priv);
    }

    @Test
    void theActorAlwaysComesFromTheTokenNeverFromTheBody() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser carla = newUser();

        // Corpo tentando fazer "carla" seguir "bia" em nome dela, e forjar "privado": tudo é ignorado
        String forged = "{\"seguidor\":\"" + carla.id + "\",\"user\":{\"id\":\"" + carla.id + "\"},"
                + "\"profileUser\":{\"id\":\"" + bia.id + "\",\"privado\":false},\"aceito\":true}";
        Response response = http().send("PUT", "/api/relacoes/seguindo/" + bia.path(), forged, ana.token);

        assertThat(response.status()).isEqualTo(201);
        assertThat(state(ana, bia)).isEqualTo("SEGUINDO");
        assertThat(state(carla, bia)).isEqualTo("NENHUM");
    }

    @Test
    void privacyComesFromTheStoredProfileNotFromTheClient() {
        TestUser ana = newUser();
        TestUser priv = newUser(true);

        // Mesmo mandando "privado:false" no corpo, a conta privada exige solicitação
        Response response = http().send("PUT", "/api/relacoes/seguindo/" + priv.path(),
                "{\"profileUser\":{\"privado\":false},\"aceito\":true}", ana.token);

        assertThat(response.json()).containsEntry("estado", "SOLICITADO");
        assertThat(state(ana, priv)).isEqualTo("SOLICITADO");
    }

    // ------------------------------------------------------------------ conta privada

    @Test
    void connectionListsOfAPrivateAccountAreOnlyForTheOwnerAndFollowers() {
        TestUser priv = newUser(true);
        TestUser follower = newUser();
        TestUser requester = newUser();
        TestUser stranger = newUser();
        follow(follower, priv);
        accept(priv, follower);
        follow(requester, priv);

        for (String list : List.of("seguidores", "seguidos")) {
            String path = "/api/relacoes/" + priv.path() + "/" + list;
            Response denied = get(stranger, path);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.json()).containsEntry("codigo", "conta_privada");
            assertThat(get(requester, path).status()).isEqualTo(403);   // pedir não dá acesso
            assertThat(get(follower, path).status()).isEqualTo(200);
            assertThat(get(priv, path).status()).isEqualTo(200);
        }

        // As contagens e o estado da relação continuam visíveis (decisão: só as listas são protegidas)
        assertThat(get(stranger, "/api/relacoes/" + priv.path() + "/contagens").json())
                .containsEntry("seguidores", 1);
        assertThat(get(stranger, "/api/relacoes/" + priv.path() + "/estado").status()).isEqualTo(200);

        // Quem deixa de seguir perde o acesso
        unfollow(follower, priv);
        assertThat(get(follower, "/api/relacoes/" + priv.path() + "/seguidores").status()).isEqualTo(403);
    }

    @Test
    void connectionListsOfAPublicAccountAreVisibleToAnyone() {
        TestUser pub = newUser();
        TestUser ana = newUser();
        TestUser stranger = newUser();
        follow(ana, pub);

        Response response = get(stranger, "/api/relacoes/" + pub.path() + "/seguidores");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("itens")).asList().hasSize(1);
    }

    @Test
    void usersNeverExposePersonalDataInTheLists() {
        TestUser pub = newUser();
        TestUser ana = newUser();
        follow(ana, pub);

        String body = get(pub, "/api/relacoes/" + pub.path() + "/seguidores").body();

        assertThat(body).contains(ana.username).contains("\"id\"").contains("foto_perfil");
        assertThat(body).doesNotContain("telefone").doesNotContain("email").doesNotContain("data_nascimento")
                .doesNotContain("sexo").doesNotContain("senha");
    }

    // ------------------------------------------------------------------ CSRF

    @Test
    void stateChangingRequestsFromAnotherSiteAreRefused() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        String path = "/api/relacoes/seguindo/" + bia.path();

        Response crossSite = http().send("PUT", path, null, ana.token, Map.of("Sec-Fetch-Site", "cross-site"));
        Response foreignOrigin = http().send("PUT", path, null, ana.token, Map.of("Origin", "https://evil.example"));
        Response nullOrigin = http().send("PUT", path, null, ana.token, Map.of("Origin", "null"));

        for (Response refused : List.of(crossSite, foreignOrigin, nullOrigin)) {
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.json()).containsEntry("codigo", "origem_invalida");
        }
        assertThat(state(ana, bia)).isEqualTo("NENHUM");

        Response sameOrigin = http().send("PUT", path, null, ana.token, Map.of("Origin", http().baseUrl()));
        assertThat(sameOrigin.status()).isEqualTo(201);
        // Leituras de outro site não alteram nada: não passam pelo filtro
        assertThat(http().send("GET", "/api/relacoes/" + bia.path() + "/contagens", null, ana.token,
                Map.of("Sec-Fetch-Site", "cross-site")).status()).isEqualTo(200);
    }

    @Test
    void unknownUsersAreNotFoundInEveryRoute() {
        TestUser ana = newUser();
        UUID missing = UUID.randomUUID();

        for (String suffix : List.of("estado", "contagens", "seguidores", "seguidos", "em-comum")) {
            assertThat(get(ana, "/api/relacoes/" + missing + "/" + suffix).status())
                    .as(suffix).isEqualTo(404);
        }
        assertThat(http().send("DELETE", "/api/relacoes/seguindo/" + missing, null, ana.token).status()).isEqualTo(404);
        assertThat(http().send("POST", "/api/relacoes/solicitacoes/" + missing + "/aceitar", null, ana.token).status())
                .isEqualTo(404);
    }
}
