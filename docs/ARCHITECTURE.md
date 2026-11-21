# Arquitetura — social_graph_service

Serviço do grafo social (seguir, seguidores, solicitações de contas privadas). Spring Boot 4 · Java 21 · PostgreSQL (banco
próprio `social_graph`) · Flyway · RabbitMQ · resource server JWT RS256 · porta **8083**. Rotas, regras, eventos e
configuração: [`../README.md`](../README.md). Plano e decisões: [`../../docs/plano-dominio-relacoes-grafo-social.md`](../../docs/plano-dominio-relacoes-grafo-social.md).

## Camadas

```
web (GraphController, Dtos, ApiError/ApiException, GlobalExceptionHandler)
  └─► service (GraphService = regras · FollowWriter = transições + contadores + eventos · ProfileSyncService = cópia local)
        ├─► domain (SnapshotRepository, FollowRepository — JdbcClient) ─► PostgreSQL
        └─► Outbox ─► OutboxRelay ─► RabbitMQ ─┬─► post_service (cópia das arestas: feed e privacidade)   (libs/socialflux-events)
                                               └─► outros consumidores (futuro: notificações)
events/ProfileEventsListener ◄─ RabbitMQ (profile.#) ─► ProfileEventHandler (inbox) ─► ProfileSyncService
security (JwtDecoder via JWKS, JwtAuthorities, OriginCheckFilter, SecurityConfig) envolve tudo.
```

## Árvore de diretórios

```
social_graph_service/
├── Dockerfile                    build Maven multi-stage (contexto = raiz do repo, para incluir libs/socialflux-events); runtime Temurin 21 JRE, usuário sem root
├── .gitignore                    exclui target/, data/ e arquivos de IDE
├── pom.xml                       dependências (Spring Boot 4.1, Security/Resource Server, JDBC, Flyway, AMQP, socialflux-events; testes: embedded-postgres)
├── README.md                     rotas, regras, eventos, configuração
├── docs/ARCHITECTURE.md          este arquivo
└── src/
    ├── main/java/com/socialflux/graph/
    │   ├── config/ domain/ service/ events/ security/ web/
    │   └── GraphServiceApplication.java
    ├── main/resources/           application.yml e db/migration/ (V1 esquema; V2 remove `legacy_id`)
    └── test/java/com/socialflux/graph/   testes de integração (+ support/)
```

## Arquivos (`com.socialflux.graph`)

| Arquivo | O que representa |
| --- | --- |
| `GraphServiceApplication.java` | `main`; liga `@ConfigurationPropertiesScan` e `@EnableScheduling` (relay do outbox, limpeza do rate limit) |

### `config/`

| Arquivo | O que representa |
| --- | --- |
| `GraphProperties.java` | Configuração tipada e validada (`graph.*`): segurança/JWKS, origens permitidas, limites antiabuso, reconciliador |
| `ClockConfig.java` | Bean `Clock` UTC (injetado em vez de `Instant.now()`) |

### `domain/`

| Arquivo | O que representa |
| --- | --- |
| `UserSnapshot.java` | Cópia local do perfil público (id, username, nome, avatar, privado, status `ACTIVE`/`DELETED`, versão do perfil) |
| `UserRef.java` | Como o cliente cita um usuário na URL: o UUID da conta |
| `Edge.java` | Item de lista paginada: o usuário do outro lado e quando a relação/solicitação foi criada |
| `Cursor.java` | Cursor opaco de paginação por chave (`created_at`, id), sem `OFFSET` |
| `SnapshotRepository.java` | `user_snapshot`: leituras (com `FOR SHARE`/`FOR UPDATE` para fechar corridas) e escrita |
| `FollowRepository.java` | `follow`, `follow_request` e `follow_stats`: trava do par, estado, escrita, contadores (e a verificação/correção usada pelo reconciliador), listas paginadas, em comum, exclusão em lote (`purge`) |

### `service/`

| Arquivo | O que representa |
| --- | --- |
| `GraphService.java` | Regras: seguir/solicitar, deixar de seguir/cancelar, remover seguidor, aceitar/recusar, estado, contagens, listas (privacidade), em comum, limites |
| `FollowWriter.java` | Transições de estado: muda as tabelas, ajusta os contadores e grava o evento no outbox, na mesma transação |
| `ProfileSyncService.java` | Aplica `profile.*` na cópia local; aceita pendentes quando a conta vira pública; apaga o grafo de contas excluídas |
| `RateLimiter.java` | Limite de ações por usuário em janela deslizante, em memória |
| `CounterReconciler.java` | Rotina agendada (e chamável à mão) que recalcula por `count(*)` os contadores de `follow_stats` e corrige os que divergem, uma transação curta por usuário com a linha travada |

### `events/`

| Arquivo | O que representa |
| --- | --- |
| `Amqp.java` | Fila `social-graph.profile-events` (`profile.#`) e a sua ligação; o exchange e a DLX/DLQ vêm da biblioteca |
| `ProfileEventsListener.java` | `@RabbitListener` da fila de eventos do perfil; repassa ao handler |
| `ProfileEventHandler.java` | Lê o envelope e aplica uma vez por id de mensagem (inbox); tolera tipos desconhecidos |

### `security/`

| Arquivo | O que representa |
| --- | --- |
| `SecurityConfig.java` | Resource server: nega tudo por padrão; `/api/**` exige `ROLE_USER`; `/actuator/health` público; erros no formato padrão |
| `JwtDecoderConfig.java` | Valida o JWT RS256 pelo JWKS do auth (só chave pública): assinatura, expiração, issuer e audiência de usuário |
| `JwtAuthorities.java` | Só token de usuário (`sub` UUID) ganha papéis; qualquer outro fica sem autoridades |
| `OriginCheckFilter.java` | Defesa contra CSRF em métodos que alteram estado (`Sec-Fetch-Site`, `Origin`) |

### `web/`

| Arquivo | O que representa |
| --- | --- |
| `GraphController.java` | Rotas `/api/relacoes/*`; o ator vem do `sub` do token; nenhuma rota aceita corpo |
| `Dtos.java` | Corpos das respostas (usuário público, estado, contagens, página, em comum) |
| `ApiError.java` / `ApiException.java` | Corpo de erro `{mensagem, sucesso, codigo}` e exceção de negócio com status e código estável |
| `GlobalExceptionHandler.java` | Converte exceções no corpo padrão; 429 com `Retry-After` |

### `resources/`

| Arquivo | O que representa |
| --- | --- |
| `application.yml` | Propriedades ligadas às variáveis de ambiente |
| `db/migration/V1__social_graph.sql` | `user_snapshot`, `follow`, `follow_request`, `follow_stats`, `outbox_event`, `processed_message` e os índices das listas por cursor |
| `db/migration/V2__remove_legacy_id.sql` | Remove `user_snapshot.legacy_id`: as rotas aceitam só UUID |

## Testes (`src/test`)

| Arquivo | O que cobre |
| --- | --- |
| `FollowFlowTest` | Seguir/solicitar/aceitar/recusar/cancelar/remover, idempotência, contadores, validações, só UUID na URL, ator sem perfil, eventos no outbox sem dado pessoal |
| `SecurityTest` | Sem token/audiência errada/expirado/serviço, IDOR em cada rota, ator só do token, `privado` do banco, listas de conta privada, CSRF, usuário inexistente |
| `ListsTest` | Paginação por cursor (ordem, sem repetição, estável com inserção), solicitações por direção, em comum |
| `ProfileSyncTest` | Cópia local por eventos (duplicado, antigo, desconhecido), privado→público aceita pendentes, exclusão de conta, túmulo |
| `ConcurrencyTest` | Seguidas paralelas do mesmo par, seguir × deixar de seguir, pedidos × virar pública, seguir × exclusão |
| `LimitsTest` / `RateLimitTest` | Limites de seguidos/pendentes e de ações por hora (429) |
| `CounterReconcilerTest` / `CounterReconcilerDisabledTest` | Contadores maiores e menores que a realidade são corrigidos; lote limitado e conclusão nas rodadas seguintes; relatório do que estava gravado e do real; nenhuma divergência nova sob corrida com seguir/deixar de seguir; rotina agendada desligada |
| `support/` | `AbstractGraphTest` (Postgres embutido, JWKS local, usuários por evento), `Http`, `TestSupportConfig` (publicador falso) |

## `.github/workflows/`

| Arquivo | O que faz |
| --- | --- |
| `ci.yml` | CI em cada pull request: lint e, só se passar, testes |
| `cd.yml` | CD após o merge na `main`: repete os testes, publica a imagem no GHCR e atualiza o container na VPS por SSH (secrets listados no `docs/ARCHITECTURE.md` da raiz) |
