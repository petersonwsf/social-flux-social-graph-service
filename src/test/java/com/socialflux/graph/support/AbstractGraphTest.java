package com.socialflux.graph.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialflux.events.OutboxRelay;
import com.socialflux.graph.events.ProfileEventHandler;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base dos testes de integração: sobe o serviço completo em porta aleatória contra um PostgreSQL REAL embutido (sem Docker)
 * e um servidor JWKS local com uma chave RSA de teste — o serviço valida os tokens exatamente como em produção
 * (assinatura RS256 via JWKS, issuer, audiência, expiração). Os usuários nascem como no sistema real: por eventos
 * {@code profile.*} entregues ao mesmo tratador que o RabbitMQ usa.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "socialflux.events.enabled=false",
                "graph.security.issuer=http://auth.test",
                "spring.flyway.enabled=true"
        })
@Import(TestSupportConfig.class)
public abstract class AbstractGraphTest {

    protected static final String ISSUER = "http://auth.test";
    protected static final String USER_AUDIENCE = "socialflux-api";

    protected static final EmbeddedPostgres POSTGRES;
    protected static final RSAKey KEY;
    private static final HttpServer JWKS;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar a infraestrutura de teste", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("graph.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ProfileEventHandler profileEvents;

    @Autowired
    protected OutboxRelay relay;

    @Autowired
    protected TestSupportConfig.RecordingPublisher publisher;

    private Http http;

    protected Http http() {
        if (http == null) {
            http = new Http(port);
        }
        return http;
    }

    // ------------------------------------------------------------------ tokens

    protected static String userToken(UUID accountId) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(USER_AUDIENCE).subject(accountId.toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("roles", List.of("USER")).claim("ver", 1).build());
    }

    protected static String serviceToken() {
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience("profile-service").subject("svc:authentication-service")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("scope", "profiles:read").build());
    }

    protected static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ usuários (criados por eventos de perfil)

    /** Um usuário de teste. {@code version} é o {@code aggregateVersion} do último evento de perfil enviado. */
    protected static final class TestUser {
        public final UUID id = UUID.randomUUID();
        public final String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        public final String token = userToken(id);
        public long version = 0;
        public boolean isPrivate;

        public String path() {
            return id.toString();
        }
    }

    /** Usuário com token válido cujo perfil o serviço ainda não conhece (nenhum evento entregue). */
    protected TestUser unknownUser() {
        return new TestUser();
    }

    protected TestUser newUser() {
        return newUser(false);
    }

    protected TestUser newUser(boolean isPrivate) {
        TestUser user = new TestUser();
        user.isPrivate = isPrivate;
        user.version = 1;
        deliver("profile.created.v1", user);
        return user;
    }

    /** Muda a privacidade como o profile_service faz: um profile.updated e um profile.privacy-changed com a MESMA versão. */
    protected void setPrivate(TestUser user, boolean isPrivate) {
        user.isPrivate = isPrivate;
        user.version++;
        deliver("profile.updated.v1", user);
        deliver("profile.privacy-changed.v1", user);
    }

    protected void deleteProfile(TestUser user) {
        user.version++;
        deliver("profile.deleted.v1", user);
    }

    protected void deliver(String type, TestUser user) {
        profileEvents.handle(profileEventJson(UUID.randomUUID(), type, user));
    }

    protected static String profileEventJson(UUID messageId, String type, TestUser user) {
        return """
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":%d,
                 "data":{"accountId":"%s","username":"%s","displayName":"Pessoa %s","bio":null,
                         "avatarKey":null,"isPrivate":%s,"createdAt":"2026-01-01T00:00:00Z"}}
                """.formatted(messageId, type, user.id, user.version, user.id, user.username,
                user.username, user.isPrivate);
    }

    // ------------------------------------------------------------------ atalhos de chamadas

    protected Http.Response follow(TestUser actor, TestUser target) {
        return http().send("PUT", "/api/relacoes/seguindo/" + target.path(), null, actor.token);
    }

    protected Http.Response unfollow(TestUser actor, TestUser target) {
        return http().send("DELETE", "/api/relacoes/seguindo/" + target.path(), null, actor.token);
    }

    protected Http.Response accept(TestUser owner, TestUser requester) {
        return http().send("POST", "/api/relacoes/solicitacoes/" + requester.path() + "/aceitar", null, owner.token);
    }

    protected Http.Response reject(TestUser owner, TestUser requester) {
        return http().send("DELETE", "/api/relacoes/solicitacoes/" + requester.path(), null, owner.token);
    }

    protected Http.Response get(TestUser viewer, String path) {
        return http().get(path, viewer.token);
    }

    protected String state(TestUser actor, TestUser target) {
        return (String) get(actor, "/api/relacoes/" + target.path() + "/estado").json().get("estado");
    }

    protected Map<String, Object> counts(TestUser user) {
        return get(user, "/api/relacoes/" + user.path() + "/contagens").json();
    }

    // ------------------------------------------------------------------ eventos / banco

    /** Publica TODOS os eventos pendentes (o relay envia em lotes). */
    protected void drainOutbox() {
        for (int i = 0; i < 100 && relay.publishPending() > 0; i++) {
            // continua até esvaziar
        }
    }

    /** Tipos de TODOS os eventos gravados no outbox cujo payload cita o usuário (ordem de gravação). */
    protected List<String> eventTypesMentioning(UUID userId) {
        return jdbc.sql("select type from outbox_event where payload like :like order by seq")
                .param("like", "%" + userId + "%").query(String.class).list();
    }

    /** Publica o outbox e devolve os tipos de evento (routing keys) já entregues ao broker que citam o usuário. */
    protected List<String> publishedTypes(TestUser user) {
        drainOutbox();
        return publisher.sent.stream().filter(s -> s.json().contains(user.id.toString()))
                .map(TestSupportConfig.RecordingPublisher.Sent::routingKey).toList();
    }

    /** Os contadores desnormalizados batem com as linhas reais (invariante que toda operação deve manter). */
    protected void assertConsistent(TestUser... users) {
        for (TestUser user : users) {
            Map<String, Object> stats = jdbc.sql("select followers_count, following_count, pending_count "
                            + "from follow_stats where user_id = :id").param("id", user.id).query().singleRow();
            assertThat(((Number) stats.get("followers_count")).longValue())
                    .as("seguidores de %s", user.username).isEqualTo(countOf("follow", "followee_id", user.id));
            assertThat(((Number) stats.get("following_count")).longValue())
                    .as("seguidos de %s", user.username).isEqualTo(countOf("follow", "follower_id", user.id));
            assertThat(((Number) stats.get("pending_count")).longValue())
                    .as("pendentes de %s", user.username).isEqualTo(countOf("follow_request", "target_id", user.id));
        }
    }

    protected long countOf(String table, String column, UUID id) {
        return jdbc.sql("select count(*) from " + table + " where " + column + " = :id").param("id", id)
                .query(Long.class).single();
    }
}
