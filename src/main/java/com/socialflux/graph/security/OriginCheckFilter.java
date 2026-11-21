package com.socialflux.graph.security;

import com.socialflux.graph.config.GraphProperties;
import com.socialflux.graph.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Defesa contra CSRF para métodos que alteram estado. O gateway converte o cookie de sessão em {@code Authorization: Bearer},
 * então uma página de outro site poderia disparar um PUT/DELETE com o cookie da vítima (seguir alguém em nome dela); aqui
 * rejeitamos requisições cross-site ({@code Sec-Fetch-Site}) e {@code Origin} fora da lista (padrão: a própria origem).
 * Clientes sem cabeçalhos de navegador passam.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(),
            HttpMethod.OPTIONS.name(), HttpMethod.TRACE.name());

    private final Set<String> allowedOrigins;

    public OriginCheckFilter(GraphProperties properties) {
        this.allowedOrigins = Set.copyOf(properties.allowedOrigins().stream()
                .filter(origin -> !origin.isBlank())
                .map(origin -> origin.trim().replaceAll("/+$", ""))
                .toList());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE.contains(request.getMethod()) && !isSameOrigin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(ApiError.of("origem_invalida", "Origem não permitida").toJson());
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return origin == null || origin.isBlank();
        }
        String normalized = origin.replaceAll("/+$", "");
        if (!allowedOrigins.isEmpty()) {
            return allowedOrigins.contains(normalized);
        }
        return normalized.equalsIgnoreCase(requestOrigin(request));
    }

    private static String requestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port);
    }
}
