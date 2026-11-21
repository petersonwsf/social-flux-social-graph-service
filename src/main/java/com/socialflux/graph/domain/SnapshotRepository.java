package com.socialflux.graph.domain;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Acesso à cópia local dos perfis ({@code user_snapshot}). */
@Repository
public class SnapshotRepository {

    static final String COLUMNS = "user_id, username, display_name, avatar_key, is_private, status, profile_version";
    static final RowMapper<UserSnapshot> MAPPER = (rs, n) -> new UserSnapshot(
            rs.getObject("user_id", UUID.class),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("avatar_key"),
            rs.getBoolean("is_private"),
            rs.getString("status"),
            rs.getLong("profile_version"));

    private final JdbcClient jdbc;

    public SnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserSnapshot> find(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from user_snapshot where user_id = :id")
                .param("id", userId).query(MAPPER).optional();
    }

    /**
     * Lê travando a linha em modo COMPARTILHADO. Usado pelas operações que gravam arestas: bloqueia enquanto o
     * processamento de {@code profile.deleted} / mudança de privacidade (que trava a mesma linha para ESCREVER) não
     * termina, e vice-versa — fecha a corrida "seguir enquanto a conta é excluída/vira pública".
     */
    public Optional<UserSnapshot> findForShare(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from user_snapshot where user_id = :id for share")
                .param("id", userId).query(MAPPER).optional();
    }

    /** Lê travando a linha para ESCRITA (aplicação de eventos de perfil). */
    public Optional<UserSnapshot> findForUpdate(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from user_snapshot where user_id = :id for update")
                .param("id", userId).query(MAPPER).optional();
    }

    public void insert(UserSnapshot s, OffsetDateTime now) {
        jdbc.sql("insert into user_snapshot (user_id, username, display_name, avatar_key, is_private, status, "
                        + "profile_version, updated_at) values (:id, :username, :display, :avatar, :private, "
                        + ":status, :version, :now)")
                .param("id", s.userId()).param("username", s.username())
                .param("display", s.displayName()).param("avatar", s.avatarKey()).param("private", s.isPrivate())
                .param("status", s.status()).param("version", s.profileVersion()).param("now", now)
                .update();
        jdbc.sql("insert into follow_stats (user_id) values (:id) on conflict (user_id) do nothing")
                .param("id", s.userId()).update();
    }

    public void update(UserSnapshot s, OffsetDateTime now) {
        jdbc.sql("update user_snapshot set username = :username, display_name = :display, "
                        + "avatar_key = :avatar, is_private = :private, status = :status, profile_version = :version, "
                        + "updated_at = :now where user_id = :id")
                .param("id", s.userId()).param("username", s.username())
                .param("display", s.displayName()).param("avatar", s.avatarKey()).param("private", s.isPrivate())
                .param("status", s.status()).param("version", s.profileVersion()).param("now", now)
                .update();
    }
}
