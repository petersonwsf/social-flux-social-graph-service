-- O id inteiro temporário (ponte com o backend legado) não tem mais uso: o legado foi desligado e todos os serviços
-- identificam o usuário só pelo UUID da conta. As rotas {usuario} passam a aceitar só UUID.
alter table user_snapshot drop column legacy_id;
