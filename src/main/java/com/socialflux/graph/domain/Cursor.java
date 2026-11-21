package com.socialflux.graph.domain;

import com.socialflux.graph.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Cursor opaco de paginação por chave ({@code created_at}, id), sem OFFSET: a página seguinte começa logo depois do
 * último item entregue, mesmo que entrem ou saiam itens no meio.
 */
public record Cursor(OffsetDateTime at, UUID id) {

    public String encode() {
        String raw = at.toInstant() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code null} ou vazio = primeira página. Cursor adulterado = 400. */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new Cursor(
                    OffsetDateTime.ofInstant(java.time.Instant.parse(raw.substring(0, separator)), java.time.ZoneOffset.UTC),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("parametro_invalido", "Cursor inválido");
        }
    }
}
