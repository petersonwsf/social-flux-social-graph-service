package com.socialflux.graph;

import com.socialflux.graph.service.CounterReconciler;
import com.socialflux.graph.support.AbstractGraphTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "graph.reconciler.enabled=false")
class CounterReconcilerDisabledTest extends AbstractGraphTest {

    @Autowired
    CounterReconciler reconciler;

    @Test
    void theScheduledRunDoesNothingWhenDisabledButManualRoundsStillWork() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        follow(ana, bia);
        jdbc.sql("update follow_stats set followers_count = 77 where user_id = :id").param("id", bia.id).update();

        reconciler.runScheduled();

        assertThat(jdbc.sql("select followers_count from follow_stats where user_id = :id").param("id", bia.id)
                .query(Long.class).single()).isEqualTo(77L);   // continua errado: a rotina agendada está desligada

        assertThat(reconciler.reconcile().fixedCount()).isEqualTo(1);   // a execução manual corrige (e limpa o banco para os outros testes)
        assertConsistent(ana, bia);
    }
}
