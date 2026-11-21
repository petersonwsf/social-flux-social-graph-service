package com.socialflux.graph.service;

import com.socialflux.graph.config.GraphProperties;
import com.socialflux.graph.domain.Cursor;
import com.socialflux.graph.domain.Edge;
import com.socialflux.graph.domain.FollowRepository;
import com.socialflux.graph.domain.SnapshotRepository;
import com.socialflux.graph.domain.UserRef;
import com.socialflux.graph.domain.UserSnapshot;
import com.socialflux.graph.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Regras do grafo social. Princípios (CLAUDE.md): quem está agindo é SEMPRE o {@code actorId} (o {@code sub} do token);
 * o usuário da URL é só o ALVO e nunca uma autoridade; "conta privada" vem da cópia local (nunca do cliente); e toda
 * operação que grava trava o par e é idempotente.
 */
@Service
public class GraphService {

    public static final int DEFAULT_PAGE = 20;
    public static final int MAX_PAGE = 100;

    /** Estado da relação do usuário logado com outro usuário. */
    public enum RelationState { NENHUM, SOLICITADO, SEGUINDO }

    public record FollowResult(RelationState state, boolean created) {
    }

    public record Relationship(RelationState state, boolean followsYou, boolean requestedYou) {
    }

    public record Page(List<Edge> items, String nextCursor) {
    }

    public record Mutual(List<Edge> items, long total) {
    }

    /** Página de solicitações recebidas e o total de pendentes (não só os itens desta página). */
    public record Requests(Page page, long total) {
    }

    private final SnapshotRepository snapshots;
    private final FollowRepository follows;
    private final FollowWriter writer;
    private final RateLimiter rateLimiter;
    private final GraphProperties.Limits limits;

    public GraphService(SnapshotRepository snapshots, FollowRepository follows, FollowWriter writer,
                        RateLimiter rateLimiter, GraphProperties properties) {
        this.snapshots = snapshots;
        this.follows = follows;
        this.writer = writer;
        this.rateLimiter = rateLimiter;
        this.limits = properties.limits();
    }

    // ------------------------------------------------------------------ escrita

    /** Seguir. Alvo público: segue na hora. Alvo privado: cria a solicitação. Repetir não muda nada (idempotente). */
    @Transactional
    public FollowResult follow(UUID actorId, UserRef ref) {
        UserSnapshot actor = requireActor(actorId);
        UserSnapshot target = resolveActive(ref, true);
        if (actor.userId().equals(target.userId())) {
            throw ApiException.unprocessable("seguir_a_si_mesmo", "Você não pode seguir a si mesmo");
        }
        rateLimit(actorId);
        follows.lockPair(actorId, target.userId());

        if (follows.isFollowing(actorId, target.userId())) {
            return new FollowResult(RelationState.SEGUINDO, false);
        }
        if (follows.hasRequest(actorId, target.userId())) {
            return new FollowResult(RelationState.SOLICITADO, false);
        }
        if (target.isPrivate()) {
            if (follows.countPendingSent(actorId) >= limits.maxPendingSent()) {
                throw ApiException.unprocessable("limite_solicitacoes",
                        "Você atingiu o limite de solicitações pendentes. Cancele alguma para enviar outra.");
            }
            writer.addRequest(actorId, target.userId());
            return new FollowResult(RelationState.SOLICITADO, true);
        }
        if (follows.counts(actorId).following() >= limits.maxFollowing()) {
            throw ApiException.unprocessable("limite_seguidos", "Você atingiu o limite de contas seguidas");
        }
        writer.addFollow(actorId, target.userId(), FollowWriter.ORIGIN_DIRECT);
        return new FollowResult(RelationState.SEGUINDO, true);
    }

    /** Deixar de seguir OU cancelar a própria solicitação pendente. Sem relação, não faz nada. */
    @Transactional
    public void unfollow(UUID actorId, UserRef ref) {
        UserSnapshot target = resolveAny(ref, ApiException.notFound("usuario_nao_encontrado", "Usuário não encontrado"));
        rateLimit(actorId);
        follows.lockPair(actorId, target.userId());
        if (!writer.removeFollow(actorId, target.userId(), FollowWriter.REASON_UNFOLLOW)) {
            writer.cancelRequest(actorId, target.userId());
        }
    }

    /** O usuário logado tira um seguidor da própria lista. Sem relação, não faz nada. */
    @Transactional
    public void removeFollower(UUID actorId, UserRef ref) {
        UserSnapshot follower = resolveAny(ref, ApiException.notFound("usuario_nao_encontrado", "Usuário não encontrado"));
        follows.lockPair(follower.userId(), actorId);
        writer.removeFollow(follower.userId(), actorId, FollowWriter.REASON_REMOVED_BY_FOLLOWEE);
    }

    /** O usuário logado (alvo) aceita a solicitação de {@code ref}. Só existe solicitação DIRIGIDA a ele. */
    @Transactional
    public void acceptRequest(UUID actorId, UserRef ref) {
        UserSnapshot requester = resolveAny(ref, requestNotFound());
        follows.lockPair(requester.userId(), actorId);
        if (!writer.acceptRequest(requester.userId(), actorId, FollowWriter.ORIGIN_REQUEST_ACCEPTED)) {
            throw requestNotFound();
        }
    }

    /** O usuário logado (alvo) recusa a solicitação de {@code ref}. */
    @Transactional
    public void rejectRequest(UUID actorId, UserRef ref) {
        UserSnapshot requester = resolveAny(ref, requestNotFound());
        follows.lockPair(requester.userId(), actorId);
        if (!writer.rejectRequest(requester.userId(), actorId)) {
            throw requestNotFound();
        }
    }

    // ------------------------------------------------------------------ leitura

    @Transactional(readOnly = true)
    public Relationship relationship(UUID actorId, UserRef ref) {
        UserSnapshot target = resolveActive(ref, false);
        UUID other = target.userId();
        if (other.equals(actorId)) {
            return new Relationship(RelationState.NENHUM, false, false);
        }
        RelationState state = follows.isFollowing(actorId, other) ? RelationState.SEGUINDO
                : follows.hasRequest(actorId, other) ? RelationState.SOLICITADO : RelationState.NENHUM;
        return new Relationship(state, follows.isFollowing(other, actorId), follows.hasRequest(other, actorId));
    }

    @Transactional(readOnly = true)
    public FollowRepository.Counts counts(UserRef ref) {
        return follows.counts(resolveActive(ref, false).userId());
    }

    /** Seguidores de {@code ref}. Conta privada: só o dono e quem a segue. */
    @Transactional(readOnly = true)
    public Page followers(UUID viewerId, UserRef ref, String cursor, Integer limit) {
        UserSnapshot target = resolveListable(viewerId, ref);
        return page(follows.followers(target.userId(), Cursor.decode(cursor), pageSize(limit) + 1), pageSize(limit));
    }

    /** Quem {@code ref} segue. Mesma regra de privacidade dos seguidores. */
    @Transactional(readOnly = true)
    public Page following(UUID viewerId, UserRef ref, String cursor, Integer limit) {
        UserSnapshot target = resolveListable(viewerId, ref);
        return page(follows.following(target.userId(), Cursor.decode(cursor), pageSize(limit) + 1), pageSize(limit));
    }

    /** Quem o usuário logado segue e também segue {@code ref} (D6: qualquer usuário logado pode ver). */
    @Transactional(readOnly = true)
    public Mutual mutual(UUID viewerId, UserRef ref, Integer limit) {
        UserSnapshot target = resolveActive(ref, false);
        if (target.userId().equals(viewerId)) {
            return new Mutual(List.of(), 0);
        }
        return new Mutual(follows.mutual(viewerId, target.userId(), pageSize(limit)),
                follows.mutualCount(viewerId, target.userId()));
    }

    /** Solicitações recebidas pelo usuário logado, com o total de pendentes (lido na mesma transação da página). */
    @Transactional(readOnly = true)
    public Requests receivedRequests(UUID actorId, String cursor, Integer limit) {
        Page page = page(follows.receivedRequests(actorId, Cursor.decode(cursor), pageSize(limit) + 1), pageSize(limit));
        return new Requests(page, follows.counts(actorId).pending());
    }

    /** Solicitações enviadas pelo usuário logado. */
    @Transactional(readOnly = true)
    public Page sentRequests(UUID actorId, String cursor, Integer limit) {
        return page(follows.sentRequests(actorId, Cursor.decode(cursor), pageSize(limit) + 1), pageSize(limit));
    }

    // ------------------------------------------------------------------ apoio

    private UserSnapshot resolveListable(UUID viewerId, UserRef ref) {
        UserSnapshot target = resolveActive(ref, false);
        boolean allowed = target.userId().equals(viewerId) || !target.isPrivate()
                || follows.isFollowing(viewerId, target.userId());
        if (!allowed) {
            throw ApiException.forbidden("conta_privada", "Esta conta é privada. Siga para ver as conexões.");
        }
        return target;
    }

    /** Usuário que existe e está ativo; senão 404 uniforme (não revela se a conta foi excluída). */
    private UserSnapshot resolveActive(UserRef ref, boolean lock) {
        return find(ref, lock).filter(UserSnapshot::isActive)
                .orElseThrow(() -> ApiException.notFound("usuario_nao_encontrado", "Usuário não encontrado"));
    }

    /** Usuário em qualquer estado (para desfazer vínculos de contas já excluídas). */
    private UserSnapshot resolveAny(UserRef ref, ApiException notFound) {
        return find(ref, true).orElseThrow(() -> notFound);
    }

    private Optional<UserSnapshot> find(UserRef ref, boolean lock) {
        Optional<UserSnapshot> found = snapshots.find(ref.uuid());
        if (lock && found.isPresent()) {
            return snapshots.findForShare(found.get().userId());
        }
        return found;
    }

    /** Quem age precisa existir e estar ativo. Ausente = o evento do perfil ainda não chegou (breve). */
    private UserSnapshot requireActor(UUID actorId) {
        UserSnapshot actor = snapshots.findForShare(actorId).orElseThrow(() -> new ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "perfil_indisponivel",
                "Seu perfil ainda está sendo preparado. Tente novamente em instantes."));
        if (!actor.isActive()) {
            throw ApiException.forbidden("forbidden", "Acesso negado");
        }
        return actor;
    }

    private void rateLimit(UUID actorId) {
        rateLimiter.hit("follow:" + actorId, limits.followActionsPerHour(), Duration.ofHours(1));
    }

    private static ApiException requestNotFound() {
        return ApiException.notFound("solicitacao_nao_encontrada", "Solicitação não encontrada");
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE;
        }
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.badRequest("parametro_invalido", "O limite deve estar entre 1 e " + MAX_PAGE);
        }
        return limit;
    }

    /** Recebe {@code size + 1} itens: o excedente indica que há próxima página e define o cursor. */
    private static Page page(List<Edge> fetched, int size) {
        if (fetched.size() <= size) {
            return new Page(fetched, null);
        }
        List<Edge> items = fetched.subList(0, size);
        Edge last = items.get(size - 1);
        return new Page(items, new Cursor(last.at(), last.user().userId()).encode());
    }
}
