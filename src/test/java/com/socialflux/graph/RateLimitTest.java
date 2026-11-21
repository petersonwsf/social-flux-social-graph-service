package com.socialflux.graph;

import com.socialflux.graph.support.AbstractGraphTest;
import com.socialflux.graph.support.Http.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "graph.limits.follow-actions-per-hour=3")
class RateLimitTest extends AbstractGraphTest {

    @Test
    void tooManyFollowActionsInAnHourReturn429WithRetryAfter() {
        TestUser ana = newUser();
        TestUser a = newUser();
        TestUser b = newUser();

        assertThat(follow(ana, a).status()).isEqualTo(201);
        assertThat(follow(ana, b).status()).isEqualTo(201);
        assertThat(unfollow(ana, a).status()).isEqualTo(204);   // deixar de seguir também conta

        Response limited = follow(ana, a);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json()).containsEntry("codigo", "muitas_requisicoes");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isPositive();
        assertThat(state(ana, a)).isEqualTo("NENHUM");   // a ação recusada não teve efeito
        // O limite é por usuário: outro usuário não é afetado
        assertThat(follow(a, b).status()).isEqualTo(201);
        // Leituras não contam
        for (int i = 0; i < 10; i++) {
            assertThat(get(ana, "/api/relacoes/" + a.path() + "/contagens").status()).isEqualTo(200);
        }
    }
}
