package com.socialflux.graph.domain;

import java.util.UUID;

/** Cópia local do perfil PÚBLICO de um usuário (alimentada por {@code profile.*}). Nunca tem dados pessoais. */
public record UserSnapshot(
        UUID userId,
        String username,
        String displayName,
        String avatarKey,
        boolean isPrivate,
        String status,
        long profileVersion) {

    public static final String ACTIVE = "ACTIVE";
    public static final String DELETED = "DELETED";

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
