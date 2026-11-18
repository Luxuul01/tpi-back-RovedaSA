package com.rovedasa.contratos.service;

import com.rovedasa.contratos.client.bcra.BcraEstadisticasClient;
import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.config.ClockConfig;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.dto.ValorIndiceDTO;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.IndiceCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comportamiento transaccional del caché con la base real (H2 en modo PostgreSQL) y las transacciones
 * de Spring, que es lo que un test con mocks no puede mostrar:
 *  - lo cacheado se guarda aunque la transacción del ajuste que lo pidió se revierta (RN-12);
 *  - si guardar falla, la transacción del ajuste no queda marcada rollback-only;
 *  - varios ajustes que piden la misma fecha a la vez no chocan por la clave única.
 *
 * Los tests no corren dentro de la transacción de @DataJpaTest (NOT_SUPPORTED): cada uno arma la suya
 * con TransactionTemplate, como lo haría ContratoService.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:indices_cache_tx;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IndiceCacheService.class, ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IndiceCacheServiceTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 10, 1);

    @Autowired
    private IndiceCacheService cacheService;
    @Autowired
    private IndiceCacheRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;

    private TransactionTemplate transaccionDelAjuste;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        transaccionDelAjuste = new TransactionTemplate(transactionManager);
    }

    private static BcraValorDTO v(LocalDate fecha, String valor) {
        return new BcraValorDTO(fecha, new BigDecimal(valor));
    }

    @Test
    @DisplayName("guardar inserta y, si ya existía, actualiza el valor")
    void guardaYActualiza() {
        cacheService.guardar(TipoIndiceEnum.ICL, List.of(v(FECHA, "12.50")));
        cacheService.guardar(TipoIndiceEnum.ICL, List.of(v(FECHA, "12.60"), v(FECHA.plusDays(1), "12.70")));

        assertAll(
                () -> assertEquals(2, repository.count()),
                () -> assertEquals(0, new BigDecimal("12.60").compareTo(
                        repository.findByTipoIndiceAndFecha(TipoIndiceEnum.ICL, FECHA).orElseThrow().getValor())));
    }

    @Test
    @DisplayName("RN-12: lo cacheado queda guardado aunque la transacción del ajuste se revierta")
    void sobreviveAlRollbackDelAjuste() {
        transaccionDelAjuste.executeWithoutResult(estado -> {
            cacheService.guardar(TipoIndiceEnum.ICL, List.of(v(FECHA, "12.50")));
            estado.setRollbackOnly(); // p. ej. el ajuste se rechazó después de obtener el índice
        });

        assertTrue(repository.findByTipoIndiceAndFecha(TipoIndiceEnum.ICL, FECHA).isPresent());
    }

    @Test
    @DisplayName("Si guardar falla, el ajuste igual se responde con el dato del BCRA y su transacción sigue sana")
    void fallaAlGuardarNoArruinaElAjuste() {
        BcraEstadisticasClient bcra = mock(BcraEstadisticasClient.class);
        // Un valor que no entra en la columna (precision 19, scale 8): el INSERT falla en la base
        when(bcra.obtenerUltimoValor(eq(40), any())).thenReturn(List.of(v(FECHA, "123456789012345.5")));
        IndiceService indiceService = new IndiceService(bcra, cacheService, new CalculadoraAjuste(), clock, 7, 2);

        List<Boolean> rollbackOnly = new ArrayList<>();
        ValorIndiceDTO valor = assertDoesNotThrow(() -> transaccionDelAjuste.execute(estado -> {
            ValorIndiceDTO resultado = indiceService.obtenerValor(TipoIndiceEnum.ICL, FECHA);
            rollbackOnly.add(estado.isRollbackOnly());
            return resultado;
        }));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.BCRA, valor.getOrigen()),
                () -> assertFalse(rollbackOnly.get(0), "la transacción del ajuste no debe quedar rollback-only"),
                () -> assertEquals(0, repository.count()));
    }

    @Test
    @DisplayName("Tres ajustes que piden la misma fecha a la vez: ninguno falla y queda una sola fila")
    void guardadoConcurrenteDeLaMismaFecha() throws Exception {
        int hilos = 3;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        try {
            List<Future<?>> resultados = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                resultados.add(ejecutor.submit(() -> {
                    largada.await();
                    // Cada hilo simula un ajuste: su propia transacción de negocio, que pide el índice
                    transaccionDelAjuste.executeWithoutResult(estado ->
                            cacheService.guardar(TipoIndiceEnum.ICL, List.of(v(FECHA, "12.50"))));
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> resultado : resultados) {
                assertDoesNotThrow(() -> resultado.get(30, TimeUnit.SECONDS));
            }
        } finally {
            ejecutor.shutdownNow();
        }

        assertEquals(1, repository.count());
    }
}
