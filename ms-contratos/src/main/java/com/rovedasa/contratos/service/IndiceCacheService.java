package com.rovedasa.contratos.service;

import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.IndiceCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Lectura y escritura de la tabla indices_cache (caché local de contingencia, RN-12).
 *
 * guardar() corre en una transacción propia (REQUIRES_NEW): si a IndiceService lo llaman desde
 * la transacción de un ajuste, lo cacheado queda guardado aunque el ajuste después se rechace, y
 * una falla al guardar no marca la transacción del ajuste como rollback-only. El guardado es
 * idempotente (UPDATE y, si no existía, INSERT ... ON CONFLICT DO NOTHING): dos ajustes que piden la
 * misma fecha al mismo tiempo no chocan por la clave única (tipo_indice, fecha).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndiceCacheService {

    private final IndiceCacheRepository repository;
    private final Clock clock;

    /** Guarda (o actualiza si ya existía) cada valor recibido del BCRA. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void guardar(TipoIndiceEnum tipo, List<BcraValorDTO> valores) {
        LocalDateTime ahora = LocalDateTime.now(clock);
        // Siempre en el mismo orden, para que dos transacciones concurrentes no se bloqueen en cruz
        List<BcraValorDTO> ordenados = valores.stream()
                .sorted(Comparator.comparing(BcraValorDTO::getFecha))
                .toList();
        for (BcraValorDTO v : ordenados) {
            int actualizados = repository.actualizarValor(tipo, v.getFecha(), v.getValor(), ahora);
            if (actualizados == 0) {
                repository.insertarSiNoExiste(tipo.name(), v.getFecha(), v.getValor(), ahora);
            }
        }
        log.debug("Caché de índices actualizado: {} valores de {}", valores.size(), tipo);
    }

    /**
     * Último valor guardado entre 'minimo' y 'hasta' (0 o 1 elemento). 'minimo' es el límite de
     * antigüedad: un valor anterior no sirve para la contingencia.
     */
    @Transactional(readOnly = true)
    public List<BcraValorDTO> buscarUltimoEntre(TipoIndiceEnum tipo, LocalDate minimo, LocalDate hasta) {
        return repository.buscarUltimoEntre(tipo, minimo, hasta, PageRequest.of(0, 1)).stream()
                .map(i -> new BcraValorDTO(i.getFecha(), i.getValor()))
                .toList();
    }

    /** Valores guardados entre dos fechas, del más reciente al más viejo. */
    @Transactional(readOnly = true)
    public List<BcraValorDTO> buscarEntreFechas(TipoIndiceEnum tipo, LocalDate desde, LocalDate hasta) {
        return repository.buscarEntreFechas(tipo, desde, hasta).stream()
                .map(i -> new BcraValorDTO(i.getFecha(), i.getValor()))
                .toList();
    }
}
