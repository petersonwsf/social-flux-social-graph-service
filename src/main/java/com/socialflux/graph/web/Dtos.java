package com.socialflux.graph.web;

import com.socialflux.graph.domain.Edge;
import com.socialflux.graph.domain.UserSnapshot;
import com.socialflux.graph.service.GraphService;

import java.util.List;

/** Corpos das respostas. Os nomes dos campos seguem o contrato que o frontend já usa (português, snake_case). */
public final class Dtos {

    private Dtos() {
    }

    /** Visão pública de um usuário nas listas: mesmos campos do perfil público, sem nenhum dado pessoal. */
    public record User(
            String id,
            String username,
            String nome,
            String foto_perfil,
            String foto_url,
            Boolean privado) {

        public static User of(UserSnapshot s) {
            return new User(s.userId().toString(), s.username(), s.displayName(), s.avatarKey(),
                    s.avatarKey() == null ? null : "/media/avatars/" + s.avatarKey(), s.isPrivate());
        }
    }

    public record RelationshipView(String estado, boolean segueVoce, boolean solicitouVoce) {

        public static RelationshipView of(GraphService.Relationship r) {
            return new RelationshipView(r.state().name(), r.followsYou(), r.requestedYou());
        }
    }

    public record StateView(String estado) {
    }

    public record CountsView(long seguidores, long seguidos) {
    }

    /** Item de lista de usuários paginada. {@code desde} é quando a relação ou a solicitação foi criada. */
    public record Item(User usuario, String desde) {

        public static Item of(Edge e) {
            return new Item(User.of(e.user()), e.at().toInstant().toString());
        }
    }

    public record PageView(List<Item> itens, String proximoCursor) {

        public static PageView of(GraphService.Page page) {
            return new PageView(page.items().stream().map(Item::of).toList(), page.nextCursor());
        }
    }

    /** Solicitações recebidas: a página e o total de pendentes (para o contador da interface). */
    public record RequestsView(List<Item> itens, String proximoCursor, long total) {

        public static RequestsView of(GraphService.Requests requests) {
            PageView page = PageView.of(requests.page());
            return new RequestsView(page.itens(), page.proximoCursor(), requests.total());
        }
    }

    public record MutualView(List<User> itens, long total) {

        public static MutualView of(GraphService.Mutual mutual) {
            return new MutualView(mutual.items().stream().map(e -> User.of(e.user())).toList(), mutual.total());
        }
    }
}
