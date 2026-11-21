package com.socialflux.graph.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Converte o JWT em autoridades. Só um token de USUÁRIO (o {@code sub} é um UUID) ganha {@code ROLE_*} a partir de
 * {@code roles}; qualquer outro (por exemplo, de serviço, com {@code sub = svc:...}) fica sem autoridades e é negado.
 */
@Component
public class JwtAuthorities implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        String subject = jwt.getSubject() == null ? "" : jwt.getSubject();
        if (isUuid(subject)) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, subject);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
