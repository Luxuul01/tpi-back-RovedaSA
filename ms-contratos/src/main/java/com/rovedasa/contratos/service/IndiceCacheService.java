package com.rovedasa.contratos.service;

import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.model.IndiceCache;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.IndiceCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lectura y escritura de la tabla indices_cache (caché local de contingencia).
 * Está separado de IndiceService para que la transacción de base de datos no
 * quede abierta mientras se espera la respuesta HTTP del BCRA.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndiceCacheService {

    private final IndiceCacheRepository repository;

    /** Guarda (o actualiza si ya existía) cada valor recibido del BCRA. */
    @Transactional
    public void guardar(TipoIndiceEnum tipo, List<BcraValorDTO> valores) {
        LocalDateTime ahora = LocalDateTime.now();
        for (BcraValorDTO v : valores) {
            IndiceCache registro = repository.findByTipoIndiceAndFecha(tipo, v.getFecha())
                    .orElseGet(() -> IndiceCache.builder().tipoIndice(tipo).fecha(v.getFecha()).build());
            registro.setValor(v.getValor());
            registro.setFechaConsulta(ahora);
            repository.save(registro);
        }
        log.debug("Caché de índices actualizado: {} valores de {}", valores.size(), tipo);
    }

    /** Valores guardados entre dos fechas, del más reciente al más viejo. */
    @Transactional(readOnly = true)
    public List<BcraValorDTO> buscarEntreFechas(TipoIndiceEnum tipo, LocalDate desde, LocalDate hasta) {
        return repository.buscarEntreFechas(tipo, desde, hasta).stream()
                .map(i -> new BcraValorDTO(i.getFecha(), i.getValor()))
                .toList();
    }
}
