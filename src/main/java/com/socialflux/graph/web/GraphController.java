package com.socialflux.graph.web;

import com.socialflux.graph.domain.FollowRepository;
import com.socialflux.graph.domain.UserRef;
import com.socialflux.graph.service.GraphService;
import com.socialflux.graph.web.Dtos.CountsView;
import com.socialflux.graph.web.Dtos.MutualView;
import com.socialflux.graph.web.Dtos.PageView;
import com.socialflux.graph.web.Dtos.RequestsView;
import com.socialflux.graph.web.Dtos.RelationshipView;
import com.socialflux.graph.web.Dtos.StateView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API pública das relações. Regras de segurança desta classe:
 * <ul>
 *   <li>quem está agindo vem SEMPRE do {@code sub} do token — o usuário da URL é só o ALVO da ação;</li>
 *   <li>nenhuma rota aceita corpo: não há como o cliente informar seguidor, "privado" ou ids de relação;</li>
 *   <li>conta privada: lista de seguidores/seguidos só para o dono e para quem a segue (403 {@code conta_privada}).</li>
 * </ul>
 * {@code {usuario}} é o UUID da conta ou o id inteiro legado.
 */
@RestController
@RequestMapping("/api/relacoes")
public class GraphController {

    private final GraphService graph;

    public GraphController(GraphService graph) {
        this.graph = graph;
    }

    // ------------------------------------------------------------------ seguir / deixar de seguir

    /** Seguir (conta pública) ou solicitar (conta privada). 201 se criou; 200 se já era assim (idempotente). */
    @PutMapping("/seguindo/{usuario}")
    ResponseEntity<StateView> follow(@PathVariable String usuario, @AuthenticationPrincipal Jwt jwt) {
        GraphService.FollowResult result = graph.follow(actor(jwt), UserRef.parse(usuario));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new StateView(result.state().name()));
    }

    /** Deixar de seguir ou cancelar a solicitação pendente. */
    @DeleteMapping("/seguindo/{usuario}")
    ResponseEntity<Void> unfollow(@PathVariable String usuario, @AuthenticationPrincipal Jwt jwt) {
        graph.unfollow(actor(jwt), UserRef.parse(usuario));
        return ResponseEntity.noContent().build();
    }

    /** Tirar um seguidor da minha lista. */
    @DeleteMapping("/seguidores/{usuario}")
    ResponseEntity<Void> removeFollower(@PathVariable String usuario, @AuthenticationPrincipal Jwt jwt) {
        graph.removeFollower(actor(jwt), UserRef.parse(usuario));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ solicitações (contas privadas)

    @GetMapping("/solicitacoes")
    RequestsView receivedRequests(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limite, @AuthenticationPrincipal Jwt jwt) {
        return RequestsView.of(graph.receivedRequests(actor(jwt), cursor, limite));
    }

    @GetMapping("/solicitacoes/enviadas")
    PageView sentRequests(@RequestParam(required = false) String cursor,
                          @RequestParam(required = false) Integer limite, @AuthenticationPrincipal Jwt jwt) {
        return PageView.of(graph.sentRequests(actor(jwt), cursor, limite));
    }

    @PostMapping("/solicitacoes/{usuario}/aceitar")
    ResponseEntity<Void> accept(@PathVariable String usuario, @AuthenticationPrincipal Jwt jwt) {
        graph.acceptRequest(actor(jwt), UserRef.parse(usuario));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/solicitacoes/{usuario}")
    ResponseEntity<Void> reject(@PathVariable String usuario, @AuthenticationPrincipal Jwt jwt) {
        graph.rejectRequest(actor(jwt), UserRef.parse(usuario));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ consultas

    @GetMapping("/{usuario}/estado")
    RelationshipView relationship(@PathVariable String usuario, @AuthenticationPrincipal Jwt jwt) {
        return RelationshipView.of(graph.relationship(actor(jwt), UserRef.parse(usuario)));
    }

    @GetMapping("/{usuario}/contagens")
    CountsView counts(@PathVariable String usuario) {
        FollowRepository.Counts counts = graph.counts(UserRef.parse(usuario));
        return new CountsView(counts.followers(), counts.following());
    }

    @GetMapping("/{usuario}/seguidores")
    PageView followers(@PathVariable String usuario, @RequestParam(required = false) String cursor,
                       @RequestParam(required = false) Integer limite, @AuthenticationPrincipal Jwt jwt) {
        return PageView.of(graph.followers(actor(jwt), UserRef.parse(usuario), cursor, limite));
    }

    @GetMapping("/{usuario}/seguidos")
    PageView following(@PathVariable String usuario, @RequestParam(required = false) String cursor,
                       @RequestParam(required = false) Integer limite, @AuthenticationPrincipal Jwt jwt) {
        return PageView.of(graph.following(actor(jwt), UserRef.parse(usuario), cursor, limite));
    }

    @GetMapping("/{usuario}/em-comum")
    MutualView mutual(@PathVariable String usuario, @RequestParam(required = false) Integer limite,
                      @AuthenticationPrincipal Jwt jwt) {
        return MutualView.of(graph.mutual(actor(jwt), UserRef.parse(usuario), limite));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
