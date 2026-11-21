-- Esquema do social_graph_service (PostgreSQL). Banco próprio do serviço; nada aqui referencia outro banco.

-- Cópia local, somente leitura, do perfil PÚBLICO (alimentada pelos eventos profile.*). Nenhum dado pessoal.
-- A ausência de uma linha significa "este serviço ainda não conhece o usuário" (404 nas rotas).
create table user_snapshot (
    user_id          uuid         primary key,              -- = id do perfil = `sub` do JWT
    legacy_id        integer      unique,                   -- TEMPORÁRIO: id inteiro dos domínios legados (rotas e projeção MySQL)
    username         varchar(60)  not null,
    display_name     varchar(120) not null,
    avatar_key       varchar(80),
    is_private       boolean      not null default false,
    status           varchar(10)  not null check (status in ('ACTIVE', 'DELETED')),
    profile_version  bigint       not null,                 -- aggregateVersion aplicado (ignora eventos antigos/duplicados)
    updated_at       timestamp with time zone not null
);

-- Arestas ACEITAS: follower SEGUE followee. É o grafo.
create table follow (
    follower_id  uuid not null references user_snapshot (user_id),
    followee_id  uuid not null references user_snapshot (user_id),
    relation_id  uuid not null unique,                      -- UUIDv7; novo a cada vínculo (agregado dos eventos)
    origin       varchar(20) not null check (origin in ('DIRECT', 'REQUEST_ACCEPTED', 'AUTO_ON_PUBLIC')),
    created_at   timestamp with time zone not null,         -- quando passou a seguir
    primary key (follower_id, followee_id),                 -- "A segue B?" e unicidade do par
    constraint follow_not_self check (follower_id <> followee_id)
);
-- Listas paginadas por cursor (created_at desc, id desc), sem OFFSET.
create index idx_follow_followers on follow (followee_id, created_at desc, follower_id desc);
create index idx_follow_following on follow (follower_id, created_at desc, followee_id desc);

-- Solicitações PENDENTES para contas privadas. Recusar/cancelar apaga a linha (não deixa rastro).
create table follow_request (
    requester_id  uuid not null references user_snapshot (user_id),
    target_id     uuid not null references user_snapshot (user_id),
    request_id    uuid not null unique,
    created_at    timestamp with time zone not null,
    primary key (requester_id, target_id),
    constraint follow_request_not_self check (requester_id <> target_id)
);
create index idx_follow_request_received on follow_request (target_id, created_at desc, requester_id desc);
create index idx_follow_request_sent on follow_request (requester_id, created_at desc, target_id desc);

-- Contadores desnormalizados, atualizados na MESMA transação da mudança.
create table follow_stats (
    user_id          uuid   primary key references user_snapshot (user_id),
    followers_count  bigint not null default 0 check (followers_count >= 0),
    following_count  bigint not null default 0 check (following_count >= 0),
    pending_count    bigint not null default 0 check (pending_count >= 0)
);

-- Transactional outbox: o evento é gravado na mesma transação da mudança e publicado depois no RabbitMQ.
create table outbox_event (
    seq                bigint generated always as identity primary key,   -- ordem de publicação (não depende de relógio)
    id                 uuid        not null unique,
    type               varchar(80) not null,
    aggregate_id       uuid        not null,
    aggregate_version  bigint      not null,
    payload            text        not null,
    created_at         timestamp with time zone not null,
    published_at       timestamp with time zone
);
create index idx_outbox_unpublished on outbox_event (seq) where published_at is null;

-- Inbox: garante processamento idempotente das mensagens recebidas (entrega é "pelo menos uma vez").
create table processed_message (
    id            uuid primary key,
    processed_at  timestamp with time zone not null
);
