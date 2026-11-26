-- Auditoria central (libs/socialflux-audit): cada evento é gravado aqui, na transação da ação, e um relay o envia em lote ao
-- audit-service.
create table audit_outbox (
    id         uuid primary key,                       -- eventId (chave de idempotência no audit-service)
    payload    text not null,                          -- JSON do evento
    created_at timestamp with time zone not null,
    sent_at    timestamp with time zone,
    attempts   integer not null default 0,
    last_error varchar(300),
    failed_at  timestamp with time zone                -- recusado ou esgotou as tentativas: não é mais reenviado
);
create index idx_audit_outbox_pending on audit_outbox (created_at, id) where sent_at is null and failed_at is null;
create index idx_audit_outbox_sent on audit_outbox (sent_at) where sent_at is not null;
