package com.socialflux.graph;

import com.socialflux.graph.support.AbstractGraphTest;
import com.socialflux.graph.support.Http.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Limites antiabuso (D8). Valores baixos só neste teste; os padrões reais são 60/h, 7.500 e 1.000. */
@TestPropertySource(properties = {
        "graph.limits.follow-actions-per-hour=1000",
        "graph.limits.max-following=2",
        "graph.limits.max-pending-sent=1"
})
class LimitsTest extends AbstractGraphTest {

    @Test
    void followingBeyondTheLimitIsRefusedUntilSomeoneIsUnfollowed() {
        TestUser ana = newUser();
        TestUser a = newUser();
        TestUser b = newUser();
        TestUser c = newUser();
        follow(ana, a);
        follow(ana, b);

        Response refused = follow(ana, c);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json()).containsEntry("codigo", "limite_seguidos");
        assertThat(state(ana, c)).isEqualTo("NENHUM");
        assertThat(follow(ana, a).status()).isEqualTo(200);   // repetir quem já segue não conta nem falha

        unfollow(ana, a);
        assertThat(follow(ana, c).status()).isEqualTo(201);
        assertConsistent(ana, a, b, c);
    }

    @Test
    void pendingRequestsBeyondTheLimitAreRefusedUntilOneIsCancelled() {
        TestUser ana = newUser();
        TestUser p1 = newUser(true);
        TestUser p2 = newUser(true);
        follow(ana, p1);

        Response refused = follow(ana, p2);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json()).containsEntry("codigo", "limite_solicitacoes");
        unfollow(ana, p1);   // cancelar libera espaço
        assertThat(follow(ana, p2).status()).isEqualTo(201);
    }
}
