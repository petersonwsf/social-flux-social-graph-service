package com.socialflux.graph.events;

import com.socialflux.events.EventEnvelope;
import com.socialflux.events.Inbox;
import com.socialflux.graph.service.ProfileSyncService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reage aos eventos {@code profile.*} do profile_service. Idempotente (inbox por id de mensagem) e tolerante a tipos
 * desconhecidos (evolução de contrato); a lógica de negócio está em {@link ProfileSyncService}.
 */
@Component
public class ProfileEventHandler {

    private final ProfileSyncService sync;
    private final Inbox inbox;
    private final JsonMapper mapper;

    public ProfileEventHandler(ProfileSyncService sync, Inbox inbox, JsonMapper mapper) {
        this.sync = sync;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    public void handle(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        inbox.once(event.id(), () -> sync.apply(event));
    }
}
