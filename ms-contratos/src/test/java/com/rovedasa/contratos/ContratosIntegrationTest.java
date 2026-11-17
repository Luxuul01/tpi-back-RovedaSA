package com.rovedasa.contratos;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.AjusteRepository;
import com.rovedasa.contratos.repository.ContratoRepository;
import com.rovedasa.contratos.repository.CuotaRepository;
import com.rovedasa.contratos.state.EstadoContratoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles({"local", "test"})
@Transactional
class ContratosIntegrationTest {

    @Autowired
    private EstadoContratoFactory estadoContratoFactory;

    @Autowired
    private ContratoRepository contratoRepository;

    @Autowired
    private AjusteRepository ajusteRepository;

    @Autowired
    private CuotaRepository cuotaRepository;

    @Autowired
    @Qualifier("catalogoRestClient")
    private RestClient catalogoRestClient;

    @Test
    @DisplayName("Contexto Spring + JPA: levanta beans, persiste ciclo State completo y ejecuta queries de repositorios")
    void debeLevantarContextoYPersistirCicloDeVidaCompleto() {
        assertNotNull(estadoContratoFactory);
        assertNotNull(catalogoRestClient);

        for (EstadoContratoEnum tipo : EstadoContratoEnum.values()) {
            assertEquals(tipo, estadoContratoFactory.obtenerEstado(tipo).getTipoEstado());
        }

        Contrato contrato = construirContrato("CONT-INT-0001");
        contrato = contratoRepository.saveAndFlush(contrato);
        assertNotNull(contrato.getId());
        assertNotNull(contrato.getVersion());
        assertNotNull(contrato.getFechaAlta());
        assertNotNull(contrato.getFechaModificacion());

        estadoContratoFactory.obtenerEstado(contrato)
                .seniar(contrato, new BigDecimal("150000.00"), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 20));
        estadoContratoFactory.obtenerEstado(contrato)
                .activar(contrato, LocalDate.of(2026, 6, 24));

        Ajuste ajuste = Ajuste.builder()
                .tipoIndice(TipoIndiceEnum.ICL)
                .fechaAplicacion(LocalDate.of(2026, 10, 1))
                .fechaIndiceDesde(LocalDate.of(2026, 7, 1))
                .fechaIndiceHasta(LocalDate.of(2026, 10, 1))
                .valorIndiceDesde(new BigDecimal("10.000000"))
                .valorIndiceHasta(new BigDecimal("12.500000"))
                .coeficiente(new BigDecimal("1.250000"))
                .montoAnterior(new BigDecimal("500000.00"))
                .montoNuevo(new BigDecimal("625000.00"))
                .porcentajeVariacion(new BigDecimal("25.0000"))
                .origen(OrigenDatoEnum.BCRA)
                .build();
        estadoContratoFactory.obtenerEstado(contrato).aplicarAjuste(contrato, ajuste);

        contratoRepository.saveAndFlush(contrato);

        assertTrue(contratoRepository.findByNroContrato("CONT-INT-0001").isPresent());
        assertEquals(1, contratoRepository.findByPropiedadIdAndEstado(100L, EstadoContratoEnum.ACTIVO).size());
        assertTrue(contratoRepository.existsByPropiedadIdAndEstadoIn(100L, List.of(EstadoContratoEnum.ACTIVO)));
        assertEquals(1, contratoRepository.findByInquilinoId(300L).size());
        assertEquals(1, contratoRepository.findByPropietarioId(200L).size());
        assertEquals(1, contratoRepository.findByInquilinoKeycloakId("sub-inq-300").size());
        assertEquals(1, contratoRepository.findByPropietarioKeycloakId("sub-prop-200").size());

        assertTrue(ajusteRepository.existsByContratoIdAndFechaIndiceHasta(
                contrato.getId(), LocalDate.of(2026, 10, 1)));
        assertTrue(ajusteRepository.findFirstByContratoIdOrderByFechaIndiceHastaDesc(contrato.getId()).isPresent());

        List<Cuota> cuotas = cuotaRepository.findByContratoIdOrderByNroCuotaAsc(contrato.getId());
        assertEquals(6, cuotas.size());
        assertTrue(cuotaRepository.findByContratoIdAndNroCuota(contrato.getId(), 4).isPresent());
        assertEquals(5, cuotaRepository.findByContratoIdAndEstadoAndFechaVencimientoAfter(
                contrato.getId(), EstadoCuotaEnum.PENDIENTE, LocalDate.of(2026, 7, 15)).size());
    }

    @Test
    @DisplayName("Restricción única BD: rechaza dos cuotas con el mismo nro_cuota para el mismo contrato")
    void debeRespetarUniqueConstraintDeCuotaPorContrato() {
        Contrato contrato = contratoRepository.saveAndFlush(construirContrato("CONT-INT-0002"));

        Cuota cuota1 = Cuota.builder()
                .nroCuota(1)
                .periodoAnio(2026)
                .periodoMes(7)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("500000.00"))
                .estado(EstadoCuotaEnum.PENDIENTE)
                .build();
        Cuota cuotaDuplicada = Cuota.builder()
                .nroCuota(1)
                .periodoAnio(2026)
                .periodoMes(8)
                .fechaVencimiento(LocalDate.of(2026, 8, 10))
                .montoBase(new BigDecimal("500000.00"))
                .estado(EstadoCuotaEnum.PENDIENTE)
                .build();

        contrato.agregarCuota(cuota1);
        contrato.agregarCuota(cuotaDuplicada);

        assertThrows(DataIntegrityViolationException.class, () -> contratoRepository.saveAndFlush(contrato));
    }

    private Contrato construirContrato(String nroContrato) {
        return Contrato.builder()
                .nroContrato(nroContrato)
                .propiedadId(100L)
                .propietarioId(200L)
                .inquilinoId(300L)
                .garanteId(400L)
                .propietarioKeycloakId("sub-prop-200")
                .inquilinoKeycloakId("sub-inq-300")
                .fechaInicio(LocalDate.of(2026, 7, 1))
                .fechaFin(LocalDate.of(2028, 7, 1))
                .frecuenciaAjusteMeses(3)
                .tipoIndice(TipoIndiceEnum.ICL)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10)
                .estado(EstadoContratoEnum.CREADO)
                .build();
    }
}
