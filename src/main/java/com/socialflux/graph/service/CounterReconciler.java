package com.socialflux.graph.service;

import com.socialflux.graph.config.GraphProperties;
import com.socialflux.graph.domain.FollowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rede de segurança dos contadores ({@code follow_stats}). Eles são mantidos na mesma transação de cada mudança, então
 * normalmente nunca divergem das linhas; esta rotina cobre o que os testes não pegaram (um bug, um conserto manual no banco,
 * uma operação em lote fora do serviço). A verdade são as linhas de {@code follow} e {@code follow_request}: o contador é só
 * uma cópia para leitura rápida.
 * <p>Funcionamento:
 * <ol>
 *   <li>uma consulta de LEITURA acha os usuários cujos contadores não batem (no máximo {@code batch-size} por rodada);</li>
 *   <li>para cada um, uma transação CURTA trava a linha de contadores, recalcula por {@code count(*)} e corrige — como as
 *   escritas do grafo precisam da mesma linha, nunca há corrida entre a correção e uma curtida/seguida em andamento, e as linhas
 *   que já estão certas nem são tocadas;</li>
 *   <li>registra quantos usuários foram corrigidos: divergência frequente é sinal de bug a investigar, não algo para mascarar.</li>
 * </ol>
 */
@Component
public class CounterReconciler {

    private static final Logger log = LoggerFactory.getLogger(CounterReconciler.class);
    private static final int LOGGED_IDS = 10;

    /** Resultado de uma rodada: quantos usuários divergiam e quais foram corrigidos (a lista é a rodada inteira). */
    public record Result(int checked, List<FollowRepository.StatsCheck> fixed, boolean moreToDo) {

        public int fixedCount() {
            return fixed.size();
        }
    }

    private final FollowRepository follows;
    private final GraphProperties.Reconciler properties;
    private final TransactionTemplate tx;

    public CounterReconciler(FollowRepository follows, GraphProperties properties, PlatformTransactionManager txManager) {
        this.follows = follows;
        this.properties = properties.reconciler();
        this.tx = new TransactionTemplate(txManager);
    }

    /** Execução agendada (a cada {@code graph.reconciler.interval}; a primeira, 5 minutos depois de subir). */
    @Scheduled(fixedDelayString = "${graph.reconciler.interval:PT1H}", initialDelayString = "PT5M")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Reconciliador de contadores falhou (tentará de novo na próxima rodada): {}", e.toString());
        }
    }

    /** Uma rodada. Pode ser chamada direto (testes, manutenção). */
    public Result reconcile() {
        List<UUID> divergent = follows.findStatsDivergences(properties.batchSize());
        List<FollowRepository.StatsCheck> fixed = new ArrayList<>();
        List<UUID> fixedIds = new ArrayList<>();
        for (UUID userId : divergent) {
            Optional<FollowRepository.StatsCheck> check = tx.execute(status -> follows.reconcileStats(userId))
                    .filter(FollowRepository.StatsCheck::diverges);
            check.ifPresent(found -> {
                fixed.add(found);
                fixedIds.add(userId);
            });
        }
        boolean moreToDo = divergent.size() >= properties.batchSize();
        if (!fixed.isEmpty()) {
            log.warn("Reconciliador: {} usuário(s) com contadores divergentes foram corrigidos (primeiros: {}){}",
                    fixed.size(), fixedIds.stream().limit(LOGGED_IDS).toList(),
                    moreToDo ? "; há mais divergências para a próxima rodada" : "");
        }
        return new Result(divergent.size(), List.copyOf(fixed), moreToDo);
    }
}
