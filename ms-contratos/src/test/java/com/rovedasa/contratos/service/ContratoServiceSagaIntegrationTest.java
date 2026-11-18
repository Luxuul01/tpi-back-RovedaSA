package com.rovedasa.contratos.service;

import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.contratos.client.CatalogoClient;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.dto.RescindirContratoRequest;
import com.rovedasa.contratos.dto.SeniarContratoRequest;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.ContratoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La Saga de ContratoService con JPA real (H2) y el catálogo mockeado, sin @Transactional en el test: verifica que
 * ninguna llamada al catálogo se hace con una transacción abierta (ni la del servicio ni una de Spring), que la
 * escritura corta guarda lo que preparó el State en la lectura, que el @Version del contrato rechaza la escritura
 * si otro proceso lo cambió mientras se esperaba al catálogo, y que ante un timeout del PATCH se compensa y el
 * contrato se puede reintentar. El reloj se fija con app.reloj.fecha-fija.
 */
@SpringBootTest(properties = "app.reloj.fecha-fija=2026-11-17")
@ActiveProfiles("test")
class ContratoServiceSagaIntegrationTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 17);
    private static final Long PROPIEDAD = 100L;
    private static final Long INQUILINO = 300L;
    private static final Long GARANTE = 400L;

    @Autowired
    private ContratoService contratoService;

    @Autowired
    private ContratoRepository contratoRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private CatalogoClient catalogoClient;

    private final List<String> llamadasConTransaccion = new ArrayList<>();

    @BeforeEach
    void catalogoQueRegistraSiHayTransaccion() {
        when(catalogoClient.evaluarGarante(GARANTE)).thenAnswer(inv -> registrar("evaluarGarante",
                ValidacionGaranteDTO.builder().esSolvente(true).build()));
        when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> registrar("actualizarEstadoPropiedad", null));
        when(catalogoClient.compensarEstadoPropiedad(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> registrar("compensarEstadoPropiedad", null));
    }

    @AfterEach
    void limpiar() {
        contratoRepository.deleteAll();
    }

    @Test
    @DisplayName("Señar, activar y rescindir: el catálogo se llama sin transacción y la escritura corta guarda todo")
    void cicloCompletoSinTransaccionDuranteLasLlamadas() {
        Long id = contratoRepository.save(contrato(EstadoContratoEnum.CREADO)).getId();

        propiedadEn(EstadoPropiedadEnum.DISPONIBLE);
        contratoService.seniarContrato(id, SeniarContratoRequest.builder().montoSenia(new BigDecimal("100000"))
                .fechaLimiteSenia(HOY.plusDays(10)).build(), "sub-admin");
        assertEquals(EstadoContratoEnum.SENIADO, leer(id, Contrato::getEstado));

        var activo = contratoService.activarContrato(id, "sub-admin");
        assertEquals(EstadoContratoEnum.ACTIVO, activo.getEstado());
        assertEquals(EstadoContratoEnum.ACTIVO, leer(id, Contrato::getEstado));
        // Tramo 0 (inicio 01/07, frecuencia 3): 3 cuotas emitidas hoy, aunque vencieron en julio, agosto y septiembre
        assertEquals(List.of(HOY, HOY, HOY), leer(id, c -> c.getCuotas().stream().map(q -> q.getFechaEmision()).toList()));
        assertTrue(activo.getCuotas().stream().allMatch(q -> q.getMontoMora().signum() == 0),
                "las cuotas emitidas hoy no tienen mora retroactiva");

        propiedadEn(EstadoPropiedadEnum.ALQUILADA);
        var rescision = contratoService.rescindirContrato(id,
                RescindirContratoRequest.builder().fechaRescision(LocalDate.of(2026, 8, 5)).build(), "sub-admin");
        assertEquals(2, rescision.getCuotasAnuladas());
        assertEquals(EstadoContratoEnum.RESCINDIDO, leer(id, Contrato::getEstado));
        assertEquals(Long.valueOf(2), leer(id, c -> c.getCuotas().stream().filter(q -> q.getEstado() == EstadoCuotaEnum.ANULADA).count()));

        assertTrue(llamadasConTransaccion.isEmpty(), "llamadas al catálogo con transacción abierta: " + llamadasConTransaccion);
    }

    @Test
    @DisplayName("Timeout del PATCH al activar: se compensa, el contrato sigue SEÑADO sin cuotas y el reintento funciona")
    void timeoutDelPatchCompensaYSePuedeReintentar() {
        Long id = contratoRepository.save(contrato(EstadoContratoEnum.SENIADO)).getId();
        CatalogoNoDisponibleException timeout = new CatalogoNoDisponibleException("Read timed out");
        when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any()))
                .thenThrow(timeout)
                .thenAnswer(inv -> registrar("actualizarEstadoPropiedad", null));

        assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class,
                () -> contratoService.activarContrato(id, "sub-admin")));
        verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                EstadoPropiedadEnum.RESERVADA, null, null);
        assertEquals(EstadoContratoEnum.SENIADO, leer(id, Contrato::getEstado));
        assertEquals(Integer.valueOf(0), leer(id, c -> c.getCuotas().size()));

        // El catálogo volvió: el reintento activa el contrato
        assertEquals(EstadoContratoEnum.ACTIVO, contratoService.activarContrato(id, "sub-admin").getEstado());
        assertEquals(Integer.valueOf(3), leer(id, c -> c.getCuotas().size()));
        assertTrue(llamadasConTransaccion.isEmpty(), "llamadas al catálogo con transacción abierta: " + llamadasConTransaccion);
    }

    @Test
    @DisplayName("Si otro proceso cambia el contrato mientras se espera al catálogo, el @Version rechaza la escritura y se compensa")
    void cambioConcurrenteDuranteElPatchFallaPorVersionYCompensa() {
        Long id = contratoRepository.save(contrato(EstadoContratoEnum.SENIADO)).getId();
        // Mientras ms-contratos espera el PATCH, otra operación cancela la seña del mismo contrato
        when(catalogoClient.actualizarEstadoPropiedad(eq(PROPIEDAD), eq(EstadoPropiedadEnum.ALQUILADA), any(), any(), any()))
                .thenAnswer(inv -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(estado -> {
                        Contrato otro = contratoRepository.findById(id).orElseThrow();
                        otro.setEstado(EstadoContratoEnum.CANCELADO);
                        otro.setMotivoCancelacion("Cancelado en paralelo");
                        contratoRepository.saveAndFlush(otro);
                    });
                    return null;
                });

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> contratoService.activarContrato(id, "sub-admin"));

        verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                EstadoPropiedadEnum.RESERVADA, null, null);
        assertEquals(EstadoContratoEnum.CANCELADO, leer(id, Contrato::getEstado));
        assertEquals(Integer.valueOf(0), leer(id, c -> c.getCuotas().size()));
    }

    @Test
    @DisplayName("Un 409 claro del PATCH (la seña se canceló en el catálogo) no se compensa ni se guarda")
    void conflictoClaroNoCompensa() {
        Long id = contratoRepository.save(contrato(EstadoContratoEnum.SENIADO)).getId();
        when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any()))
                .thenThrow(new CatalogoConflictoException("La propiedad está en estado DISPONIBLE y se esperaba RESERVADA"));

        assertThrows(CatalogoConflictoException.class, () -> contratoService.activarContrato(id, "sub-admin"));

        verify(catalogoClient, never()).compensarEstadoPropiedad(any(), any(), any(), any(), any());
        assertEquals(EstadoContratoEnum.SENIADO, leer(id, Contrato::getEstado));
    }

    private <T> T registrar(String llamada, T resultado) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            llamadasConTransaccion.add(llamada);
        }
        return resultado;
    }

    private void propiedadEn(EstadoPropiedadEnum estado) {
        when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenAnswer(inv -> registrar("obtenerPropiedad",
                PropiedadDTO.builder().id(PROPIEDAD).estado(estado).build()));
    }

    private <T> T leer(Long id, Function<Contrato, T> consulta) {
        TransactionTemplate lectura = new TransactionTemplate(transactionManager);
        lectura.setReadOnly(true);
        return lectura.execute(estado -> consulta.apply(contratoRepository.findById(id).orElseThrow()));
    }

    private static Contrato contrato(EstadoContratoEnum estado) {
        return Contrato.builder()
                .propiedadId(PROPIEDAD).propietarioId(200L).inquilinoId(INQUILINO).garanteId(GARANTE)
                .propietarioKeycloakId("sub-prop-200").inquilinoKeycloakId("sub-inq-300")
                .fechaInicio(LocalDate.of(2026, 7, 1)).fechaFin(LocalDate.of(2028, 7, 1))
                .frecuenciaAjusteMeses(3).tipoIndice(TipoIndiceEnum.ICL)
                .montoInicial(new BigDecimal("500000.00")).montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000")).diaVencimientoMes(10)
                .montoSenia(estado == EstadoContratoEnum.SENIADO ? new BigDecimal("100000.00") : null)
                .fechaLimiteSenia(estado == EstadoContratoEnum.SENIADO ? HOY.plusDays(10) : null)
                .estado(estado)
                .build();
    }
}
