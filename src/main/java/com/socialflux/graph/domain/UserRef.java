package com.socialflux.graph.domain;

import com.socialflux.graph.web.ApiException;

import java.util.UUID;

/** Como o cliente se refere a um usuário na URL: o UUID da conta. Qualquer outro formato é 400. */
public record UserRef(UUID uuid) {

    public static UserRef parse(String raw) {
        try {
            return new UserRef(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("parametro_invalido", "Identificador de usuário inválido");
        }
    }
}
