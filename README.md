# social_graph_service

Serviço do **grafo social** do Social Flux: quem segue quem, solicitações para contas privadas, seguidores/seguidos
paginados, contagens e seguidores em comum.
Spring Boot 4 · Java 21 · PostgreSQL (banco próprio) · Flyway · RabbitMQ (outbox/inbox) · resource server JWT RS256.

Plano, análise SQL × NoSQL e decisões D1–D14: [`../docs/plano-dominio-relacoes-grafo-social.md`](../docs/plano-dominio-relacoes-grafo-social.md).

> **Estado:** serviço implementado e testado (Fases 1–4 do plano), já presente no `docker-compose.yml` (perfil `apps`, banco
> criado por `graph_db_init`) e roteado no `Caddyfile` (`/api/relacoes*`). O **frontend já o usa** (`services/relacaoService.ts`);
> as rotas `/api/relacao` do legado e a projeção `relacao` no MySQL foram removidas (o serviço não depende mais do MySQL).

## Como se relaciona com os outros serviços

```
Navegador ─► Caddy ──► /api/relacoes/*  ─► social_graph_service        (cookie -> Bearer; forward_auth confere a revogação)
profile_service ──profile.created|updated|privacy-changed|deleted.v1 (RabbitMQ)──► social_graph_service   (cópia local dos perfis)
social_graph_service ──follow.created|removed|requested|request-cancelled|request-rejected.v1──► RabbitMQ ──► post_service (cópia das arestas aceitas) e futuros serviços
```

- **Identidade:** quem age vem SEMPRE do `sub` do token. O usuário da URL é só o **alvo**; nenhuma rota aceita corpo, então não
  há como informar seguidor, "privado" ou id de relação.
- **Quem é privado:** vem da **cópia local** (`user_snapshot`), alimentada pelos eventos do profile — nunca do cliente.
- **Cópia local:** o serviço nunca consulta o `profile_service`. Um usuário "existe" aqui quando o `profile.created.v1`
  dele foi processado; antes disso as rotas respondem 404 (alvo) ou 503 `perfil_indisponivel` (quem age).
- **`{usuario}` nas rotas:** o UUID da conta (outro formato: 400 `parametro_invalido`).

## API pública (`/api/relacoes`)

Todas exigem token de usuário. Nenhuma tem corpo. Erros: `{ "mensagem", "sucesso": false, "codigo" }`.

| Rota | Descrição |
| --- | --- |
| `PUT /seguindo/{usuario}` | Seguir. Conta pública: segue na hora (`201 {estado:"SEGUINDO"}`). Privada: cria a solicitação (`201 {estado:"SOLICITADO"}`). Repetir devolve `200` com o estado atual (idempotente) |
| `DELETE /seguindo/{usuario}` | Deixar de seguir **ou** cancelar a solicitação pendente. `204`, também quando não havia relação |
| `DELETE /seguidores/{usuario}` | Tirar um seguidor da **minha** lista. `204` |
| `GET /{usuario}/estado` | `{ estado: "NENHUM"\|"SOLICITADO"\|"SEGUINDO", segueVoce, solicitouVoce }` |
| `GET /{usuario}/contagens` | `{ seguidores, seguidos }` (visível a qualquer logado) |
| `GET /{usuario}/seguidores?cursor=&limite=` · `GET /{usuario}/seguidos?...` | Lista paginada (mais recentes primeiro). **Conta privada: só o dono e quem a segue**; os demais recebem `403 conta_privada` |
| `GET /{usuario}/em-comum?limite=` | Quem eu sigo e também segue `{usuario}`: `{ itens, total }` (D6: qualquer logado) |
| `GET /solicitacoes?cursor=&limite=` | Solicitações **recebidas** por mim, com o `total` de pendentes (o número real, não o tamanho da página): `{ itens, proximoCursor, total }` |
| `GET /solicitacoes/enviadas?cursor=&limite=` | Solicitações que **eu enviei** |
| `POST /solicitacoes/{usuario}/aceitar` | Aceitar a solicitação de `{usuario}` (só existe solicitação dirigida a mim). `204` |
| `DELETE /solicitacoes/{usuario}` | Recusar. `204` (não deixa rastro: é possível pedir de novo) |

Listas: `{ itens: [{ usuario: { id (UUID), username, nome, foto_perfil, foto_url, privado }, desde }], proximoCursor }`.
`limite` padrão 20, máximo 100; o cursor é opaco (paginação por chave, sem `OFFSET`: estável mesmo que entrem/saiam itens).
O item de usuário **nunca** tem dado pessoal.

Códigos de erro: `usuario_nao_encontrado` 404 · `seguir_a_si_mesmo` 422 · `conta_privada` 403 · `solicitacao_nao_encontrada` 404 ·
`limite_seguidos` 422 · `limite_solicitacoes` 422 · `muitas_requisicoes` 429 (com `Retry-After`) · `parametro_invalido` 400 ·
`origem_invalida` 403 (CSRF) · `perfil_indisponivel` 503 · `unauthorized` 401 · `forbidden` 403.

## Regras

| Regra | Detalhe |
| --- | --- |
| Público × privado | Alvo público: segue direto. Alvo privado: solicitação pendente até o dono aceitar |
| Conta vira **pública** | Todas as solicitações pendentes são aceitas automaticamente, com `follow.created.v1` e `origin = AUTO_ON_PUBLIC` (D5) |
| Conta vira **privada** | Quem já segue continua seguindo; novos seguidores precisam de solicitação |
| Conta **excluída** (`profile.deleted.v1`) | Apaga arestas e solicitações dos dois lados e corrige os contadores de quem ficou. Não gera eventos por aresta |
| Contadores | `seguidores`, `seguidos` e `pendentes` ficam em `follow_stats`, mudam na mesma transação de cada escrita e são **conferidos por uma rotina agendada** (abaixo) |
| Limites (D8) | `GRAPH_FOLLOW_RATE_PER_HOUR` (60 ações de seguir/deixar de seguir por hora), `GRAPH_MAX_FOLLOWING` (7.500), `GRAPH_MAX_PENDING_SENT` (1.000) |
| Concorrência | Toda escrita trava o par (advisory lock) e a linha do usuário em modo compartilhado; contadores mudam na mesma transação |

## Reconciliador de contadores

Rede de segurança: os contadores de `follow_stats` são uma **cópia** das linhas de `follow` e `follow_request` (a verdade). Normalmente nunca divergem, mas um
bug, um conserto manual no banco ou uma operação em lote fora do serviço podem desalinhá-los, e nada mais os recalcularia. A cada `GRAPH_RECONCILER_INTERVAL`
(padrão 1 hora; a primeira rodada 5 minutos depois de subir) o `CounterReconciler`:

1. acha, por uma consulta somente de leitura, os usuários cujos contadores não batem com `count(*)` (no máximo `GRAPH_RECONCILER_BATCH` por rodada, padrão 500);
2. para cada um, em uma transação curta, **trava a linha de contadores**, recalcula e corrige: as escritas do grafo precisam da mesma linha, então não há corrida
   com um seguir/aceitar em andamento, e as linhas que já estão certas nem são tocadas;
3. registra um `WARN` com quantos usuários foram corrigidos (e os primeiros ids). **Divergência recorrente é sinal de bug a investigar**, não algo para mascarar.

Pode ser desligado com `GRAPH_RECONCILER_ENABLED=false` (a execução manual, `CounterReconciler.reconcile()`, continua disponível para testes e manutenção).

## Eventos (RabbitMQ, exchange `socialflux.events`, routing key = tipo)

Publica (via **outbox transacional**, biblioteca [`libs/socialflux-events`](../libs/socialflux-events/README.md)). O agregado é o
vínculo (`relationId`) ou a solicitação (`requestId`); cada vínculo novo tem id novo, então `aggregateVersion` é sempre 1.
Os payloads só têm ids e datas — **nunca** dados pessoais.

| Evento | Quando | `data` |
| --- | --- | --- |
| `follow.created.v1` | passou a seguir | `relationId, followerId, followeeId, origin (DIRECT\|REQUEST_ACCEPTED\|AUTO_ON_PUBLIC), createdAt` |
| `follow.removed.v1` | deixou de seguir / foi removido | `relationId, followerId, followeeId, reason (UNFOLLOW\|REMOVED_BY_FOLLOWEE)` |
| `follow.requested.v1` | solicitação criada | `requestId, requesterId, targetId, requestedAt` |
| `follow.request-cancelled.v1` | o solicitante desistiu | `requestId, requesterId, targetId` |
| `follow.request-rejected.v1` | o alvo recusou | `requestId, requesterId, targetId` |

Consome (fila `social-graph.profile-events`, ligação `profile.#`, idempotente por inbox e por `aggregateVersion`):
`profile.created.v1` e `profile.updated.v1` (upsert da cópia local), `profile.privacy-changed.v1` e `profile.deleted.v1`.
Tipos desconhecidos são ignorados. Um `profile.deleted.v1` que chega antes do `created` deixa um "túmulo" para que um
`created` atrasado não reviva a conta.

## Rodando

Tudo em containers: `docker compose --profile apps up -d --build` (o serviço `graph_db_init` cria o banco `social_graph`).

Com as aplicações no host: suba a infra (`docker compose --profile host up -d`, que também cria o banco; o gateway aponta
`/api/relacoes*` para `localhost:8083`) e, na raiz do repositório (usa o `.env`, o mesmo usuário/senha do auth, com o auth e o
profile no ar):

```bash
powershell -ExecutionPolicy Bypass -File scripts/dev/run-graph.ps1    # 8083   (ou ./scripts/dev/run-graph.sh)
```

Testes (PostgreSQL real embutido, JWKS local e JWT RS256 de verdade; sem Docker):

```bash
mvn -pl social_graph_service -am test      # na raiz; compila a biblioteca de eventos junto
```

## Configuração (variáveis de ambiente)

| Variável | Descrição |
| --- | --- |
| `GRAPH_DB_URL` / `GRAPH_DB_NAME` | Banco do serviço (padrão `jdbc:postgresql://localhost:5432/social_graph`) |
| `GRAPH_DB_USER` / `GRAPH_DB_PASSWORD` | Se ausentes, **reutilizam** `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` do `.env` |
| `AUTH_JWKS_URI` | JWKS do authentication_service (só a chave **pública**) |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Validação do token de usuário |
| `RABBITMQ_HOST/PORT/USER/PASSWORD`, `EVENTS_EXCHANGE`, `GRAPH_EVENTS_ENABLED`, `GRAPH_OUTBOX_INTERVAL` | Broker e eventos |
| `GRAPH_FOLLOW_RATE_PER_HOUR`, `GRAPH_MAX_FOLLOWING`, `GRAPH_MAX_PENDING_SENT` | Limites antiabuso (D8) |
| `GRAPH_RECONCILER_ENABLED`, `GRAPH_RECONCILER_INTERVAL`, `GRAPH_RECONCILER_BATCH` | Reconciliador de contadores: liga/desliga (padrão ligado), intervalo ISO-8601 (`PT1H`) e usuários por rodada (500) |
| `GRAPH_ALLOWED_ORIGINS` | Origens aceitas em métodos não seguros (vazio = a própria origem) |
| `SERVER_PORT` | Porta (padrão 8083) |

## Limites conhecidos

- A **revogação imediata** (logout geral, troca de senha) é aplicada pelo gateway (`forward_auth`); este serviço só valida
  assinatura/validade do JWT (até 2 h). Não o exponha sem o gateway.
- Rate limit em memória (1 instância).
- Os eventos de perfil precisam ter sido processados: usuários que existiam antes deste serviço não têm cópia local
  (sem bootstrap — decisão D9). Em desenvolvimento, recrie os volumes ou republique os eventos.
- Os contadores são mantidos na transação e conferidos pelo reconciliador; entre uma divergência e a próxima rodada (até 1 h) o número exibido pode estar errado.
- O limite de seguidos (`GRAPH_MAX_FOLLOWING`) não é aplicado quando uma solicitação é aceita (manual ou automática).
- A consumidora RabbitMQ e a declaração das filas só foram exercitadas nos testes com um publicador/handler direto, não
  contra um RabbitMQ real.
