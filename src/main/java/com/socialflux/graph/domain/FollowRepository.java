package com.socialflux.graph.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acesso às arestas aceitas ({@code follow}), às solicitações ({@code follow_request}) e aos contadores. */
@Repository
public class FollowRepository {

    /** Contadores de um usuário. */
    public record Counts(long followers, long following, long pending) {
    }

    private static final String USER_COLUMNS = "u.user_id, u.username, u.display_name, u.avatar_key, "
            + "u.is_private, u.status, u.profile_version";

    private final JdbcClient jdbc;

    public FollowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ concorrência

    /**
     * Trava (até o fim da transação) as operações sobre o par. Serializa cliques duplos e corridas como "seguir ×
     * aceitar" sem travar as linhas de usuários muito seguidos.
     */
    public void lockPair(UUID a, UUID b) {
        String key = a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().singleValue();
    }

    // ------------------------------------------------------------------ estado do par

    public boolean isFollowing(UUID follower, UUID followee) {
        return exists("follow", "follower_id", "followee_id", follower, followee);
    }

    public boolean hasRequest(UUID requester, UUID target) {
        return exists("follow_request", "requester_id", "target_id", requester, target);
    }

    private boolean exists(String table, String firstColumn, String secondColumn, UUID first, UUID second) {
        return jdbc.sql("select exists (select 1 from " + table + " where " + firstColumn + " = :a and "
                        + secondColumn + " = :b)")
                .param("a", first).param("b", second).query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ escrita

    public void insertFollow(UUID relationId, UUID follower, UUID followee, String origin, OffsetDateTime at) {
        jdbc.sql("insert into follow (follower_id, followee_id, relation_id, origin, created_at) "
                        + "values (:follower, :followee, :relation, :origin, :at)")
                .param("follower", follower).param("followee", followee).param("relation", relationId)
                .param("origin", origin).param("at", at).update();
    }

    /** Remove a aresta e devolve o id do vínculo removido (vazio se não existia). */
    public Optional<UUID> deleteFollow(UUID follower, UUID followee) {
        return jdbc.sql("delete from follow where follower_id = :follower and followee_id = :followee "
                        + "returning relation_id")
                .param("follower", follower).param("followee", followee).query(UUID.class).optional();
    }

    public void insertRequest(UUID requestId, UUID requester, UUID target, OffsetDateTime at) {
        jdbc.sql("insert into follow_request (requester_id, target_id, request_id, created_at) "
                        + "values (:requester, :target, :request, :at)")
                .param("requester", requester).param("target", target).param("request", requestId)
                .param("at", at).update();
    }

    /** Remove a solicitação e devolve o id dela (vazio se não existia). */
    public Optional<UUID> deleteRequest(UUID requester, UUID target) {
        return jdbc.sql("delete from follow_request where requester_id = :requester and target_id = :target "
                        + "returning request_id")
                .param("requester", requester).param("target", target).query(UUID.class).optional();
    }

    // ------------------------------------------------------------------ contadores

    public void addCounts(UUID userId, long followers, long following, long pending) {
        jdbc.sql("update follow_stats set followers_count = followers_count + :followers, "
                        + "following_count = following_count + :following, pending_count = pending_count + :pending "
                        + "where user_id = :id")
                .param("id", userId).param("followers", followers).param("following", following)
                .param("pending", pending).update();
    }

    public Counts counts(UUID userId) {
        return jdbc.sql("select followers_count, following_count, pending_count from follow_stats where user_id = :id")
                .param("id", userId)
                .query((rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .optional().orElse(new Counts(0, 0, 0));
    }

    public long countPendingSent(UUID requester) {
        return jdbc.sql("select count(*) from follow_request where requester_id = :id")
                .param("id", requester).query(Long.class).single();
    }

    // ------------------------------------------------------------------ listas (paginadas por cursor)

    /** Quem segue {@code userId} (mais recentes primeiro). */
    public List<Edge> followers(UUID userId, Cursor cursor, int limit) {
        return edges("follow", "followee_id", "follower_id", userId, cursor, limit);
    }

    /** Quem {@code userId} segue (mais recentes primeiro). */
    public List<Edge> following(UUID userId, Cursor cursor, int limit) {
        return edges("follow", "follower_id", "followee_id", userId, cursor, limit);
    }

    /** Solicitações RECEBIDAS por {@code userId}: o outro lado é quem pediu para seguir. */
    public List<Edge> receivedRequests(UUID userId, Cursor cursor, int limit) {
        return edges("follow_request", "target_id", "requester_id", userId, cursor, limit);
    }

    /** Solicitações ENVIADAS por {@code userId}: o outro lado é a conta privada que ele quer seguir. */
    public List<Edge> sentRequests(UUID userId, Cursor cursor, int limit) {
        return edges("follow_request", "requester_id", "target_id", userId, cursor, limit);
    }

    /** Os nomes de tabela/coluna são constantes internas (nunca vêm do cliente). */
    private List<Edge> edges(String table, String ownerColumn, String otherColumn, UUID owner, Cursor cursor, int limit) {
        String sql = "select " + USER_COLUMNS + ", t.created_at as at from " + table + " t "
                + "join user_snapshot u on u.user_id = t." + otherColumn + " "
                + "where t." + ownerColumn + " = :owner and u.status = 'ACTIVE' "
                + (cursor == null ? "" : "and (t.created_at, t." + otherColumn + ") < (:at, :id) ")
                + "order by t.created_at desc, t." + otherColumn + " desc limit :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("owner", owner).param("limit", limit);
        if (cursor != null) {
            spec = spec.param("at", cursor.at()).param("id", cursor.id());
        }
        return spec.query((rs, n) -> new Edge(SnapshotRepository.MAPPER.mapRow(rs, n),
                rs.getObject("at", OffsetDateTime.class))).list();
    }

    // ------------------------------------------------------------------ em comum

    /** Quem {@code viewer} segue e também segue {@code target}. */
    public List<Edge> mutual(UUID viewer, UUID target, int limit) {
        return jdbc.sql("select " + USER_COLUMNS + ", f1.created_at as at from follow f1 "
                        + "join follow f2 on f2.follower_id = f1.followee_id and f2.followee_id = :target "
                        + "join user_snapshot u on u.user_id = f1.followee_id "
                        + "where f1.follower_id = :viewer and u.status = 'ACTIVE' "
                        + "order by f1.created_at desc, f1.followee_id desc limit :limit")
                .param("viewer", viewer).param("target", target).param("limit", limit)
                .query((rs, n) -> new Edge(SnapshotRepository.MAPPER.mapRow(rs, n),
                        rs.getObject("at", OffsetDateTime.class)))
                .list();
    }

    public long mutualCount(UUID viewer, UUID target) {
        return jdbc.sql("select count(*) from follow f1 "
                        + "join follow f2 on f2.follower_id = f1.followee_id and f2.followee_id = :target "
                        + "where f1.follower_id = :viewer")
                .param("viewer", viewer).param("target", target).query(Long.class).single();
    }

    // ------------------------------------------------------------------ reconciliação dos contadores

    /** Contadores gravados e o que as linhas reais dizem, para um usuário. */
    public record StatsCheck(long storedFollowers, long storedFollowing, long storedPending,
                             long realFollowers, long realFollowing, long realPending) {

        public boolean diverges() {
            return storedFollowers != realFollowers || storedFollowing != realFollowing || storedPending != realPending;
        }
    }

    /**
     * Usuários cujos contadores NÃO batem com as linhas (consulta somente de leitura; um comando enxerga um estado consistente,
     * então não há falso positivo por transação em andamento). Limitada a {@code limit} por rodada.
     */
    public List<UUID> findStatsDivergences(int limit) {
        return jdbc.sql("select s.user_id from follow_stats s "
                        + "left join (select followee_id as uid, count(*) as n from follow group by followee_id) a on a.uid = s.user_id "
                        + "left join (select follower_id as uid, count(*) as n from follow group by follower_id) b on b.uid = s.user_id "
                        + "left join (select target_id as uid, count(*) as n from follow_request group by target_id) c on c.uid = s.user_id "
                        + "where s.followers_count <> coalesce(a.n, 0) or s.following_count <> coalesce(b.n, 0) "
                        + "or s.pending_count <> coalesce(c.n, 0) order by s.user_id limit :limit")
                .param("limit", limit).query(UUID.class).list();
    }

    /**
     * Trava a linha de contadores do usuário (as escritas do grafo precisam da mesma linha, então esperam ou já terminaram) e compara
     * o gravado com a contagem real. Se divergir, corrige. Devolve o que encontrou (vazio se o usuário não tem linha de contadores).
     * Chamar dentro de uma transação curta, uma por usuário.
     */
    public Optional<StatsCheck> reconcileStats(UUID userId) {
        Optional<long[]> stored = jdbc.sql("select followers_count, following_count, pending_count from follow_stats "
                        + "where user_id = :id for update")
                .param("id", userId).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}).optional();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        long followers = countWhere("follow", "followee_id", userId);
        long following = countWhere("follow", "follower_id", userId);
        long pending = countWhere("follow_request", "target_id", userId);
        StatsCheck check = new StatsCheck(stored.get()[0], stored.get()[1], stored.get()[2], followers, following, pending);
        if (check.diverges()) {
            jdbc.sql("update follow_stats set followers_count = :followers, following_count = :following, pending_count = :pending "
                            + "where user_id = :id")
                    .param("id", userId).param("followers", followers).param("following", following)
                    .param("pending", pending).update();
        }
        return Optional.of(check);
    }

    private long countWhere(String table, String column, UUID userId) {
        return jdbc.sql("select count(*) from " + table + " where " + column + " = :id")
                .param("id", userId).query(Long.class).single();
    }

    // ------------------------------------------------------------------ lote

    /** Quem tem solicitação pendente para {@code target}, da mais antiga para a mais nova. */
    public List<UUID> pendingRequesters(UUID target) {
        return jdbc.sql("select requester_id from follow_request where target_id = :target order by created_at, requester_id")
                .param("target", target).query(UUID.class).list();
    }

    /**
     * Apaga todas as arestas e solicitações do usuário (nos dois sentidos) e ajusta os contadores de quem ficou do
     * outro lado. Usado quando a conta é excluída ({@code profile.deleted.v1}); não gera eventos por aresta.
     */
    public void purge(UUID userId) {
        jdbc.sql("update follow_stats s set followers_count = s.followers_count - c.n from "
                        + "(select followee_id as uid, count(*) as n from follow where follower_id = :id group by followee_id) c "
                        + "where s.user_id = c.uid")
                .param("id", userId).update();
        jdbc.sql("update follow_stats s set following_count = s.following_count - c.n from "
                        + "(select follower_id as uid, count(*) as n from follow where followee_id = :id group by follower_id) c "
                        + "where s.user_id = c.uid")
                .param("id", userId).update();
        jdbc.sql("update follow_stats s set pending_count = s.pending_count - c.n from "
                        + "(select target_id as uid, count(*) as n from follow_request where requester_id = :id group by target_id) c "
                        + "where s.user_id = c.uid")
                .param("id", userId).update();
        jdbc.sql("delete from follow where follower_id = :id or followee_id = :id").param("id", userId).update();
        jdbc.sql("delete from follow_request where requester_id = :id or target_id = :id").param("id", userId).update();
        jdbc.sql("update follow_stats set followers_count = 0, following_count = 0, pending_count = 0 where user_id = :id")
                .param("id", userId).update();
    }
}
