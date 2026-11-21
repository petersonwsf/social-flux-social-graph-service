package com.socialflux.graph;

import com.socialflux.graph.service.CounterReconciler;
import com.socialflux.graph.support.AbstractGraphTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** O reconciliador recalcula por {@code count(*)} os contadores que divergem das linhas e não atrapalha as escritas. */
@TestPropertySource(properties = "graph.reconciler.batch-size=2")
class CounterReconcilerTest extends AbstractGraphTest {

    @Autowired
    CounterReconciler reconciler;

    private void corrupt(TestUser user, long followers, long following, long pending) {
        jdbc.sql("update follow_stats set followers_count = :followers, following_count = :following, "
                        + "pending_count = :pending where user_id = :id")
                .param("followers", followers).param("following", following).param("pending", pending)
                .param("id", user.id).update();
    }

    /** Roda o reconciliador até não sobrar divergência (o lote é pequeno de propósito). */
    private int reconcileUntilClean() {
        int total = 0;
        for (int i = 0; i < 20; i++) {
            CounterReconciler.Result result = reconciler.reconcile();
            total += result.fixedCount();
            if (result.checked() == 0) {
                return total;
            }
        }
        throw new AssertionError("o reconciliador não convergiu em 20 rodadas");
    }

    @Test
    void consistentCountersAreLeftAlone() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);
        follow(ana, bia);
        follow(ana, priv);   // pendente

        CounterReconciler.Result result = reconciler.reconcile();

        assertThat(result.fixedCount()).isZero();
        assertThat(result.checked()).isZero();
        assertConsistent(ana, bia, priv);
    }

    @Test
    void countersLargerAndSmallerThanTheRealRowsAreCorrected() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser priv = newUser(true);
        follow(ana, bia);       // bia: 1 seguidor; ana: 1 seguido
        follow(ana, priv);      // priv: 1 pendente

        corrupt(bia, 99, 7, 3);     // maiores que a realidade
        corrupt(ana, 0, 0, 0);      // menores que a realidade (ana segue 1)
        corrupt(priv, 5, 5, 0);     // pendente zerado, mas há 1

        int fixed = reconcileUntilClean();

        assertThat(fixed).isEqualTo(3);
        assertConsistent(ana, bia, priv);
        assertThat(counts(bia)).containsEntry("seguidores", 1).containsEntry("seguidos", 0);
        assertThat(counts(ana)).containsEntry("seguidos", 1);
    }

    @Test
    void theResultReportsWhatWasStoredAndWhatWasReal() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        follow(ana, bia);
        corrupt(bia, 42, 0, 0);

        CounterReconciler.Result result = reconciler.reconcile();

        assertThat(result.fixed()).hasSize(1);
        assertThat(result.fixed().get(0).storedFollowers()).isEqualTo(42);
        assertThat(result.fixed().get(0).realFollowers()).isEqualTo(1);
        assertConsistent(bia);
    }

    @Test
    void eachRoundIsLimitedToTheBatchSizeAndTheRestIsDoneNext() {
        List<TestUser> users = new ArrayList<>();
        TestUser target = newUser();
        for (int i = 0; i < 5; i++) {
            TestUser user = newUser();
            follow(user, target);
            users.add(user);
            corrupt(user, 50, 50, 50);
        }

        CounterReconciler.Result first = reconciler.reconcile();

        assertThat(first.checked()).isEqualTo(2);        // batch-size = 2
        assertThat(first.fixedCount()).isEqualTo(2);
        assertThat(first.moreToDo()).isTrue();
        assertThat(reconcileUntilClean()).isEqualTo(3);  // os 3 restantes
        assertConsistent(users.toArray(new TestUser[0]));
        assertConsistent(target);
    }

    @Test
    void runningItAgainAfterTheFixFindsNothing() {
        TestUser ana = newUser();
        corrupt(ana, 9, 9, 9);

        assertThat(reconciler.reconcile().fixedCount()).isEqualTo(1);
        assertThat(reconciler.reconcile().fixedCount()).isZero();
    }

    @Test
    void reconcilingWhileUsersFollowAndUnfollowNeverIntroducesDrift() throws Exception {
        TestUser star = newUser();
        List<TestUser> people = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            people.add(newUser());
        }
        ExecutorService pool = Executors.newFixedThreadPool(people.size() + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (TestUser person : people) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int round = 0; round < 6; round++) {
                    follow(person, star);
                    unfollow(person, star);
                    follow(person, star);
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            start.await();
            for (int round = 0; round < 25; round++) {
                reconciler.reconcile();   // concorre com as escritas
            }
            return null;
        }));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Se o reconciliador tivesse gravado um valor velho no meio de uma escrita, sobraria divergência aqui.
        assertConsistent(star);
        assertConsistent(people.toArray(new TestUser[0]));
        assertThat(reconciler.reconcile().fixedCount()).isZero();
        assertThat(counts(star)).containsEntry("seguidores", people.size());
    }
}
