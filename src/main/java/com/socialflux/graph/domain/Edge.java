package com.socialflux.graph.domain;

import java.time.OffsetDateTime;

/** Um item de lista paginada: o usuário do outro lado da relação e quando a relação/solicitação foi criada. */
public record Edge(UserSnapshot user, OffsetDateTime at) {
}
