package com.socialflux.graph;

import com.socialflux.graph.support.AbstractGraphTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Corridas: o par é serializado e os contadores nunca se afastam das linhas reais. */
class ConcurrencyTest extends AbstractGraphTest {

    private static final int THREADS = 12;

    private void runInParallel(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    @Test
    void manyParallelFollowsOfTheSamePairCreateOneEdgeAndCountOnce() throws Exception {
        TestUser ana = newUser();
        TestUser bia = newUser();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> assertThat(follow(ana, bia).status()).isIn(200, 201));
        }

        runInParallel(tasks);

        assertThat(countOf("follow", "follower_id", ana.id)).isEqualTo(1);
        assertThat(counts(bia)).containsEntry("seguidores", 1);
        assertConsistent(ana, bia);
        assertThat(publishedTypes(ana).stream().filter("follow.created.v1"::equals).count()).isEqualTo(1);
    }

    @Test
    void followAndUnfollowInterleavedKeepTheCountersConsistent() throws Exception {
        TestUser ana = newUser();
        TestUser bia = newUser();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean doFollow = i % 2 == 0;
            tasks.add(() -> {
                for (int round = 0; round < 5; round++) {
                    int status = doFollow ? follow(ana, bia).status() : unfollow(ana, bia).status();
                    assertThat(status).isIn(200, 201, 204);
                }
            });
        }

        runInParallel(tasks);

        assertConsistent(ana, bia);
        assertThat(countOf("follow", "follower_id", ana.id)).isBetween(0L, 1L);
    }

    @Test
    void requestsRacingWithTheAccountBecomingPublicAreNeverLeftPending() throws Exception {
        TestUser priv = newUser(true);
        List<TestUser> requesters = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            TestUser requester = newUser();
            requesters.add(requester);
            tasks.add(() -> assertThat(follow(requester, priv).status()).isIn(200, 201));
        }
        tasks.add(() -> setPrivate(priv, false));   // vira pública no meio das solicitações

        runInParallel(tasks);

        // Quem pediu antes foi aceito no lote; quem chegou depois seguiu direto: ninguém fica pendente
        assertThat(countOf("follow_request", "target_id", priv.id)).isZero();
        assertThat(countOf("follow", "followee_id", priv.id)).isEqualTo(THREADS);
        assertConsistent(priv);
        assertConsistent(requesters.toArray(new TestUser[0]));
    }

    @Test
    void followsRacingWithTheAccountBeingDeletedLeaveNoDanglingEdges() throws Exception {
        TestUser victim = newUser();
        List<TestUser> followers = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            TestUser follower = newUser();
            followers.add(follower);
            tasks.add(() -> assertThat(follow(follower, victim).status()).isIn(200, 201, 404));
        }
        tasks.add(() -> deleteProfile(victim));

        runInParallel(tasks);

        assertThat(countOf("follow", "followee_id", victim.id)).isZero();
        assertConsistent(followers.toArray(new TestUser[0]));
    }
}
