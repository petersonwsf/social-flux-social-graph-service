package com.socialflux.graph.web;

/** Corpo padrão de erro, igual ao dos demais serviços: {@code {mensagem, sucesso, codigo}}. */
public record ApiError(String mensagem, boolean sucesso, String codigo) {

    public static ApiError of(String codigo, String mensagem) {
        return new ApiError(mensagem, false, codigo);
    }

    /** JSON escrito à mão para uso em filtros/entry points (fora do MVC). */
    public String toJson() {
        return "{\"mensagem\":\"" + escape(mensagem) + "\",\"sucesso\":false,\"codigo\":\"" + escape(codigo) + "\"}";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
