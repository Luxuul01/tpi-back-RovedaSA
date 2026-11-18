package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Control optimista de la cuota (@Version) contra una base H2 real:
 * de dos pagos que partieron de la misma versión, solo el primero se confirma.
 */
@DataJpaTest
class CuotaVersionadoJpaTest {

    @Autowired
    private CuotaRepository cuotaRepository;

    @Autowired
    private ContratoRepository contratoRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("La cuota nace con versión 0 y la versión sube al registrar el pago")
    void debeIncrementarLaVersionAlPagar() {
        Long cuotaId = persistirCuotaPendiente();

        Cuota cuota = cuotaRepository.findById(cuotaId).orElseThrow();
        assertEquals(0L, cuota.getVersion());

        cuota.registrarPago(LocalDate.of(2026, 7, 10));
        cuotaRepository.saveAndFlush(cuota);

        assertEquals(1L, cuota.getVersion());
    }

    @Test
    @DisplayName("Un segundo pago sobre una versión vieja de la cuota falla con conflicto optimista (409)")
    void debeRechazarElPagoSobreUnaVersionVieja() {
        Long cuotaId = persistirCuotaPendiente();

        // Dos "peticiones" leen la cuota PENDIENTE con la misma versión
        Cuota lecturaA = cuotaRepository.findById(cuotaId).orElseThrow();
        entityManager.detach(lecturaA);
        Cuota lecturaB = cuotaRepository.findById(cuotaId).orElseThrow();

        // La B paga primero
        lecturaB.registrarPago(LocalDate.of(2026, 7, 10));
        cuotaRepository.saveAndFlush(lecturaB);

        // La A intenta pagar con la versión que había leído
        lecturaA.registrarPago(LocalDate.of(2026, 7, 11));
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> cuotaRepository.saveAndFlush(lecturaA));

        entityManager.clear();
        Cuota enBase = cuotaRepository.findById(cuotaId).orElseThrow();
        assertEquals(EstadoCuotaEnum.PAGADA, enBase.getEstado());
        assertEquals(LocalDate.of(2026, 7, 10), enBase.getFechaPago());
        assertEquals(1L, enBase.getVersion());
    }

    private Long persistirCuotaPendiente() {
        Contrato contrato = Contrato.builder()
                .propiedadId(100L)
                .propietarioId(200L)
                .inquilinoId(300L)
                .garanteId(400L)
                .propietarioKeycloakId("sub-prop-200")
                .inquilinoKeycloakId("sub-inq-300")
                .fechaInicio(LocalDate.of(2026, 7, 1))
                .fechaFin(LocalDate.of(2028, 7, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10)
                .build();
        contrato.agregarCuota(Cuota.builder()
                .nroCuota(1)
                .periodoMes(7)
                .periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("500000.00"))
                .build());
        contrato = contratoRepository.saveAndFlush(contrato);
        Long cuotaId = contrato.getCuotas().get(0).getId();
        entityManager.clear();
        return cuotaId;
    }
}
