package com.socialflux.graph;

import com.socialflux.graph.support.AbstractGraphTest;
import com.socialflux.graph.support.Http.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Listas paginadas por cursor, solicitações e seguidores em comum. */
class ListsTest extends AbstractGraphTest {

    @SuppressWarnings("unchecked")
    private static List<String> usernames(Response response, String field) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.json().get("itens");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> user = field == null ? item : (Map<String, Object>) item.get(field);
            names.add((String) user.get("username"));
        }
        return names;
    }

    @Test
    void followersArePagedNewestFirstWithoutRepetitionOrGaps() throws InterruptedException {
        TestUser star = newUser();
        List<TestUser> followers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TestUser f = newUser();
            follow(f, star);
            followers.add(f);
            Thread.sleep(5);   // created_at distintos (a ordem é o desempate, mas o teste quer ver "mais novos primeiro")
        }
        List<String> expected = new ArrayList<>(followers.stream().map(f -> f.username).toList());
        java.util.Collections.reverse(expected);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = get(star, "/api/relacoes/" + star.path() + "/seguidores?limite=2"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            seen.addAll(usernames(page, "usuario"));
            cursor = (String) page.json().get("proximoCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);        // 2 + 2 + 1
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(new LinkedHashSet<>(seen)).hasSize(5);
    }

    @Test
    void thePageIsStableWhenSomeoneFollowsInTheMiddleOfTheWalk() throws InterruptedException {
        TestUser star = newUser();
        List<TestUser> first = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestUser f = newUser();
            follow(f, star);
            first.add(f);
            Thread.sleep(5);
        }
        Response page1 = get(star, "/api/relacoes/" + star.path() + "/seguidores?limite=2");
        String cursor = (String) page1.json().get("proximoCursor");

        follow(newUser(), star);   // novo seguidor depois de a primeira página ter sido entregue

        Response page2 = get(star, "/api/relacoes/" + star.path() + "/seguidores?limite=2&cursor=" + cursor);
        assertThat(usernames(page2, "usuario")).containsExactly(first.get(1).username, first.get(0).username);
        assertThat(page2.json().get("proximoCursor")).isNull();
    }

    @Test
    void followingIsPagedToo() throws InterruptedException {
        TestUser ana = newUser();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestUser other = newUser();
            follow(ana, other);
            expected.add(0, other.username);
            Thread.sleep(5);
        }

        Response page1 = get(ana, "/api/relacoes/" + ana.path() + "/seguidos?limite=2");
        Response page2 = get(ana, "/api/relacoes/" + ana.path() + "/seguidos?limite=2&cursor="
                + page1.json().get("proximoCursor"));

        List<String> all = new ArrayList<>(usernames(page1, "usuario"));
        all.addAll(usernames(page2, "usuario"));
        assertThat(all).containsExactlyElementsOf(expected);
        assertThat(page2.json().get("proximoCursor")).isNull();
    }

    @Test
    void requestListsAreSeparatedByDirectionAndPrivateToTheirOwner() {
        TestUser priv = newUser(true);
        TestUser priv2 = newUser(true);
        TestUser ana = newUser();
        TestUser bia = newUser();
        follow(ana, priv);
        follow(bia, priv);
        follow(ana, priv2);

        assertThat(usernames(get(priv, "/api/relacoes/solicitacoes"), "usuario"))
                .containsExactlyInAnyOrder(ana.username, bia.username);
        assertThat(get(priv, "/api/relacoes/solicitacoes").json()).containsEntry("total", 2);
        assertThat(get(ana, "/api/relacoes/solicitacoes").json()).containsEntry("total", 0);
        assertThat(usernames(get(ana, "/api/relacoes/solicitacoes/enviadas"), "usuario"))
                .containsExactlyInAnyOrder(priv.username, priv2.username);
        assertThat(usernames(get(ana, "/api/relacoes/solicitacoes"), "usuario")).isEmpty();   // ninguém pediu a Ana
        assertThat(usernames(get(bia, "/api/relacoes/solicitacoes/enviadas"), "usuario")).containsExactly(priv.username);
        assertConsistent(priv, priv2, ana, bia);
    }

    @Test
    void theReceivedRequestsTotalCountsAllPendingNotJustThePage() {
        TestUser priv = newUser(true);
        for (int i = 0; i < 3; i++) {
            follow(newUser(), priv);
        }

        Response page = get(priv, "/api/relacoes/solicitacoes?limite=2");

        assertThat(usernames(page, "usuario")).hasSize(2);
        assertThat(page.json().get("proximoCursor")).isNotNull();
        assertThat(page.json()).containsEntry("total", 3);   // o total não depende do tamanho da página
        assertThat(get(priv, "/api/relacoes/solicitacoes?limite=2&cursor=" + page.json().get("proximoCursor")).json())
                .containsEntry("total", 3);
    }

    @Test
    void mutualFollowersAreWhoIFollowAndAlsoFollowTheProfile() {
        TestUser me = newUser();
        TestUser target = newUser();
        TestUser a = newUser();
        TestUser b = newUser();
        TestUser c = newUser();
        TestUser notFollowedByMe = newUser();
        follow(me, a);
        follow(me, b);
        follow(me, c);
        follow(a, target);
        follow(c, target);
        follow(notFollowedByMe, target);

        Response response = get(me, "/api/relacoes/" + target.path() + "/em-comum");

        assertThat(usernames(response, null)).containsExactlyInAnyOrder(a.username, c.username);
        assertThat(response.json()).containsEntry("total", 2);
        assertThat(get(me, "/api/relacoes/" + me.path() + "/em-comum").json()).containsEntry("total", 0);
    }

    @Test
    void mutualFollowersOfAPrivateAccountAreVisibleToAnyLoggedUser() {
        TestUser me = newUser();
        TestUser priv = newUser(true);
        TestUser a = newUser();
        follow(me, a);
        follow(a, priv);
        accept(priv, a);

        Response response = get(me, "/api/relacoes/" + priv.path() + "/em-comum");   // D6

        assertThat(response.status()).isEqualTo(200);
        assertThat(usernames(response, null)).containsExactly(a.username);
    }
}
