package com.rovedasa.contratos.service;

import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.client.CatalogoClient;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.client.CatalogoIntegracionException;
import com.rovedasa.contratos.dto.AplicarAjusteRequest;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.CrearContratoRequest;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.dto.PersonaResumenDTO;
import com.rovedasa.contratos.dto.RescindirContratoRequest;
import com.rovedasa.contratos.dto.RescisionResponse;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.SeniarContratoRequest;
import com.rovedasa.contratos.exception.CatalogoNoDisponibleException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.ContratoRepository;
import com.rovedasa.contratos.state.EstadoContratoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de servicio de ContratoService con el catálogo y el índice mockeados, el State real y un reloj fijo
 * en el 17/11/2026.
 */
@ExtendWith(OutputCaptureExtension.class)
class ContratoServiceTest {

    static final LocalDate HOY = LocalDate.of(2026, 11, 17);
    static final Long PROPIEDAD = 100L;
    static final Long INQUILINO = 300L;
    static final Long GARANTE = 400L;

    private ContratoRepository contratoRepository;
    private CuotaService cuotaService;
    private CatalogoClient catalogoClient;
    private IndiceService indiceService;
    private ContratoService contratoService;

    @BeforeEach
    void setUp() {
        contratoRepository = mock(ContratoRepository.class);
        cuotaService = mock(CuotaService.class);
        catalogoClient = mock(CatalogoClient.class);
        indiceService = mock(IndiceService.class);
        contratoService = crearServicio(contratoRepository, cuotaService, catalogoClient, indiceService, HOY);
        when(cuotaService.toResumenList(anyList(), any(), any())).thenReturn(List.of());
        when(contratoRepository.saveAndFlush(any(Contrato.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    static ContratoService crearServicio(ContratoRepository repo, CuotaService cuotas, CatalogoClient catalogo,
                                         IndiceService indices, LocalDate hoy) {
        Clock reloj = Clock.fixed(Instant.parse(hoy + "T12:00:00Z"), ZoneOffset.UTC);
        return new ContratoService(repo, cuotas, catalogo, new EstadoContratoFactory(), indices, reloj,
                mock(PlatformTransactionManager.class));
    }

    // ------------------------------------------------------------------
    // Consultas y alta (comportamiento previo)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADMINISTRADOR ve todos los contratos")
    void administradorVeTodosLosContratos() {
        when(contratoRepository.findAll()).thenReturn(List.of(
                crearContrato(1L, "sub-prop-1", "sub-inq-1"),
                crearContrato(2L, "sub-prop-2", "sub-inq-2")));

        List<ContratoResponse> resultado = contratoService.listarContratos("ADMINISTRADOR", "sub-admin");

        assertEquals(2, resultado.size());
        verify(contratoRepository).findAll();
        verify(contratoRepository, never()).findByPropietarioKeycloakId(anyString());
        verify(contratoRepository, never()).findByInquilinoKeycloakId(anyString());
    }

    @Test
    @DisplayName("PROPIETARIO solo ve los contratos de su usuario de Keycloak")
    void propietarioSoloVeSusContratos() {
        when(contratoRepository.findByPropietarioKeycloakId("sub-prop-1"))
                .thenReturn(List.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        List<ContratoResponse> resultado = contratoService.listarContratos("PROPIETARIO", "sub-prop-1");

        assertEquals(1, resultado.size());
        assertEquals("sub-prop-1", resultado.get(0).getPropietarioKeycloakId());
        verify(contratoRepository, never()).findAll();
    }

    @Test
    @DisplayName("INQUILINO solo ve los contratos de su usuario de Keycloak")
    void inquilinoSoloVeSusContratos() {
        when(contratoRepository.findByInquilinoKeycloakId("sub-inq-1"))
                .thenReturn(List.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        List<ContratoResponse> resultado = contratoService.listarContratos("INQUILINO", "sub-inq-1");

        assertEquals(1, resultado.size());
        assertEquals("sub-inq-1", resultado.get(0).getInquilinoKeycloakId());
        verify(contratoRepository, never()).findAll();
    }

    @Test
    @DisplayName("Un usuario no puede ver el contrato de otro (anti-IDOR)")
    void noPermiteVerContratoDeOtroUsuario() {
        when(contratoRepository.findById(1L))
                .thenReturn(Optional.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        assertThrows(RecursoNoEncontradoException.class,
                () -> contratoService.obtenerContrato(1L, "INQUILINO", "sub-ajeno"));
    }

    @Test
    @DisplayName("El dueño puede ver su propio contrato")
    void permiteVerContratoPropio() {
        when(contratoRepository.findById(1L))
                .thenReturn(Optional.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        ContratoResponse response = contratoService.obtenerContrato(1L, "PROPIETARIO", "sub-prop-1");

        assertEquals(1L, response.getId());
    }

    @Test
    @DisplayName("Un rol desconocido no puede listar contratos")
    void rolDesconocidoLanzaOperacionInvalida() {
        assertThrows(OperacionInvalidaException.class,
                () -> contratoService.listarContratos("GARANTE", "sub-garante"));
    }

    @Test
    @DisplayName("No permite crear contrato si el garante coincide con el inquilino o el propietario (RN-02)")
    void crearContratoRechazaGaranteRepetidoConPartes() {
        CrearContratoRequest req = requestAlta().garanteId(20L).build();

        assertThrows(OperacionInvalidaException.class, () -> contratoService.crearContrato(req));
        verify(catalogoClient, never()).obtenerPropiedad(any());
    }

    @Test
    @DisplayName("No permite crear contrato si alguna persona no posee el rol correspondiente (RN-02)")
    void crearContratoRechazaPersonaSinRolRequerido() {
        mockearPartesAlta();
        when(catalogoClient.obtenerPersona(30L)).thenReturn(PersonaResumenDTO.builder()
                .id(30L).keycloakUserId("sub-otro").roles(Set.of("INQUILINO")).build());

        assertThrows(OperacionInvalidaException.class, () -> contratoService.crearContrato(requestAlta().build()));
        verify(contratoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Crear contrato: sin índice queda ICL (alcance comprometido) y con IPC queda IPC")
    void crearContratoGuardaElIndicePactado() {
        mockearPartesAlta();
        when(contratoRepository.save(any(Contrato.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(TipoIndiceEnum.ICL, contratoService.crearContrato(requestAlta().build()).getTipoIndice());
        assertEquals(TipoIndiceEnum.IPC,
                contratoService.crearContrato(requestAlta().tipoIndice(TipoIndiceEnum.IPC).build()).getTipoIndice());
    }

    @Test
    @DisplayName("Crear contrato: rechaza una frecuencia de ajuste mayor que la duración en meses (400)")
    void crearContratoRechazaFrecuenciaMayorQueLaDuracion() {
        CrearContratoRequest req = requestAlta()
                .fechaInicio(LocalDate.of(2026, 12, 1)).fechaFin(LocalDate.of(2026, 12, 20))
                .frecuenciaAjusteMeses(3).build();

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> contratoService.crearContrato(req));
        assertTrue(ex.getMessage().contains("1 meses"), ex.getMessage());
        verify(catalogoClient, never()).obtenerPropiedad(any());
    }

    // ------------------------------------------------------------------
    // Ajustes: hitos, ventana y validación antes del índice
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Aplicar ajuste")
    class AplicarAjuste {

        @Test
        @DisplayName("C-05: inicio 01/07 frecuencia 3, ventana 01/07 -> 01/10, ICL 10 -> 12,50: canon 625.000 y 3 cuotas nuevas")
        void c05AjusteDelPrimerPeriodo() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            mockearIcl(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1), "500000.00", "625000.00",
                    LocalDate.of(2026, 9, 30));

            ContratoResponse resp = contratoService.aplicarAjuste(1L,
                    pedido(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)), "sub-admin");

            assertEquals(new BigDecimal("625000.00"), resp.getMontoActual());
            assertEquals(1, resp.getAjustes().size());
            assertEquals(LocalDate.of(2026, 7, 1), resp.getAjustes().get(0).getFechaIndiceDesde());
            assertEquals(LocalDate.of(2026, 10, 1), resp.getAjustes().get(0).getFechaIndiceHasta());
            assertEquals(LocalDate.of(2026, 9, 30), resp.getAjustes().get(0).getFechaValorIndiceHasta());
            assertEquals(HOY, resp.getAjustes().get(0).getFechaAplicacion());
            assertEquals(new BigDecimal("1.25000000"), resp.getAjustes().get(0).getCoeficiente());
            List<Cuota> nuevas = contrato.getCuotas().stream()
                    .filter(c -> c.getMontoBase().compareTo(new BigDecimal("625000")) == 0).toList();
            assertEquals(List.of(4, 5, 6), nuevas.stream().map(Cuota::getNroCuota).toList());
            verify(contratoRepository).saveAndFlush(contrato);

            // La repetición se rechaza y no vuelve a consultar el índice
            assertThrows(OperacionInvalidaException.class, () -> contratoService.aplicarAjuste(1L,
                    pedido(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)), "sub-admin"));
            verify(indiceService, times(1)).calcularAjuste(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Sin fechas en el pedido se usa la ventana del período (H0 -> H1)")
        void sinFechasUsaLaVentana() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            mockearIcl(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1), "500000.00", "625000.00", null);

            contratoService.aplicarAjuste(1L, new AplicarAjusteRequest(), "sub-admin");

            verify(indiceService).calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("500000.00"),
                    LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1));
        }

        @Test
        @DisplayName("Fechas que no coinciden: 400 con las fechas que corresponden y sin consultar el índice")
        void fechasQueNoCoincidenNoConsultanElIndice() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));

            OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                    () -> contratoService.aplicarAjuste(1L,
                            pedido(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 11, 16)), "sub-admin"));

            assertTrue(ex.getMessage().contains("desde 2026-07-01 hasta 2026-10-01"), ex.getMessage());
            verify(indiceService, never()).calcularAjuste(any(), any(), any(), any());
            verify(contratoRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Ajuste prematuro (todavía no llegó el hito): 400 sin consultar el índice")
        void prematuroNoConsultaElIndice() {
            Contrato contrato = contratoActivo(1L, HOY, HOY.plusDays(730), 3);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));

            OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                    () -> contratoService.aplicarAjuste(1L, pedido(HOY, HOY), "sub-admin"));

            assertTrue(ex.getMessage().contains("2027-02-17"), ex.getMessage());
            verify(indiceService, never()).calcularAjuste(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Contrato que no está ACTIVO: 400 del State sin consultar el índice")
        void noActivoNoConsultaElIndice() {
            Contrato contrato = crearContrato(1L, "p", "i");
            contrato.setEstado(EstadoContratoEnum.SENIADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));

            assertThrows(OperacionInvalidaException.class,
                    () -> contratoService.aplicarAjuste(1L, new AplicarAjusteRequest(), "sub-admin"));
            verify(indiceService, never()).calcularAjuste(any(), any(), any(), any());
        }

        @Test
        @DisplayName("IPC: un contrato pactado con IPC se ajusta por IPC y nunca por ICL")
        void usaElIndiceDelContrato() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
            contrato.setTipoIndice(TipoIndiceEnum.IPC);
            LocalDate desde = LocalDate.of(2026, 7, 1);
            LocalDate hasta = LocalDate.of(2026, 10, 1);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(indiceService.calcularAjuste(eq(TipoIndiceEnum.IPC), any(), eq(desde), eq(hasta))).thenReturn(
                    ResultadoAjusteDTO.builder().tipoIndice(TipoIndiceEnum.IPC)
                            .fechaIndiceDesde(LocalDate.of(2026, 6, 1)).fechaIndiceHasta(LocalDate.of(2026, 8, 1))
                            .valorIndiceDesde(BigDecimal.ONE).valorIndiceHasta(new BigDecimal("1.061208"))
                            .coeficiente(new BigDecimal("1.061208")).montoAnterior(new BigDecimal("500000.00"))
                            .montoNuevo(new BigDecimal("530604.00")).porcentajeVariacion(new BigDecimal("6.12"))
                            .origen(OrigenDatoEnum.BCRA).build());

            ContratoResponse resp = contratoService.aplicarAjuste(1L, pedido(desde, hasta), "sub-admin");

            verify(indiceService, never()).calcularAjuste(eq(TipoIndiceEnum.ICL), any(), any(), any());
            assertEquals(TipoIndiceEnum.IPC, resp.getTipoIndice());
            assertEquals(new BigDecimal("530604.00"), resp.getMontoActual());
            assertEquals(desde, resp.getAjustes().get(0).getFechaIndiceDesde());
            assertEquals(LocalDate.of(2026, 6, 1), resp.getAjustes().get(0).getFechaValorIndiceDesde());
        }

        @Test
        @DisplayName("Un canon ajustado que no entra en NUMERIC(15,2) se rechaza con 400 y no se guarda")
        void canonQueNoEntraEnLaColumnaSeRechaza() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            mockearIcl(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1), "500000.00", "10000000000000.00", null);

            assertThrows(OperacionInvalidaException.class,
                    () -> contratoService.aplicarAjuste(1L, new AplicarAjusteRequest(), "sub-admin"));
            verify(contratoRepository, never()).saveAndFlush(any());
        }
    }

    // ------------------------------------------------------------------
    // Saga: estado esperado y compensaciones
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Saga con el catálogo")
    class Saga {

        @Test
        @DisplayName("Señar: PATCH con estadoEsperado DISPONIBLE; si falla el guardado compensa RESERVADA -> DISPONIBLE y sale el conflicto")
        void seniarCompensaYRelanzaElConflicto() {
            Contrato contrato = crearContrato(1L, "p", "i");
            contrato.setEstado(EstadoContratoEnum.CREADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.DISPONIBLE));
            ObjectOptimisticLockingFailureException conflicto = new ObjectOptimisticLockingFailureException(Contrato.class, 1L);
            when(contratoRepository.saveAndFlush(contrato)).thenThrow(conflicto);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> contratoService.seniarContrato(1L,
                    SeniarContratoRequest.builder().montoSenia(new BigDecimal("100000"))
                            .fechaLimiteSenia(HOY.plusDays(10)).build(), "sub-admin"));

            assertSame(conflicto, ex);
            var orden = inOrder(catalogoClient);
            orden.verify(catalogoClient).actualizarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.RESERVADA,
                    EstadoPropiedadEnum.DISPONIBLE, INQUILINO, GARANTE);
            orden.verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.RESERVADA,
                    EstadoPropiedadEnum.DISPONIBLE, null, null);
        }

        @Test
        @DisplayName("Señar con la propiedad ya RESERVADA (C-02): 400 y no se toca el catálogo")
        void seniarConPropiedadReservadaSeRechaza() {
            Contrato contrato = crearContrato(1L, "p", "i");
            contrato.setEstado(EstadoContratoEnum.CREADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.RESERVADA));

            assertThrows(OperacionInvalidaException.class, () -> contratoService.seniarContrato(1L,
                    SeniarContratoRequest.builder().montoSenia(new BigDecimal("100000"))
                            .fechaLimiteSenia(HOY.plusDays(10)).build(), "sub-admin"));
            verify(catalogoClient, never()).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Activar: PATCH ALQUILADA con estadoEsperado RESERVADA; si falla el guardado compensa ALQUILADA -> RESERVADA")
        void activarCompensaAlquiladaAReservada() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(contratoRepository.findByPropiedadIdAndEstado(PROPIEDAD, EstadoContratoEnum.CREADO)).thenReturn(List.of());
            ObjectOptimisticLockingFailureException conflicto = new ObjectOptimisticLockingFailureException(Contrato.class, 1L);
            when(contratoRepository.saveAndFlush(contrato)).thenThrow(conflicto);

            assertSame(conflicto, assertThrows(RuntimeException.class,
                    () -> contratoService.activarContrato(1L, "sub-admin")));

            verify(catalogoClient).actualizarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                    EstadoPropiedadEnum.RESERVADA, INQUILINO, GARANTE);
            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                    EstadoPropiedadEnum.RESERVADA, null, null);
        }

        @Test
        @DisplayName("Activar: si el catálogo responde 409 (la seña se canceló en paralelo) no se guarda ni se compensa")
        void activarConConflictoEnElCatalogo() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any()))
                    .thenThrow(new CatalogoConflictoException("La propiedad está en estado DISPONIBLE y se esperaba RESERVADA"));

            assertThrows(CatalogoConflictoException.class, () -> contratoService.activarContrato(1L, "sub-admin"));
            verify(contratoRepository, never()).saveAndFlush(any());
            verify(catalogoClient, never()).compensarEstadoPropiedad(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Activar: cancela por el State los borradores competidores (motivo y seña no retenida)")
        void activarCancelaCompetidoresPorElState() {
            Contrato contrato = contratoSeniado(1L);
            Contrato competidor = crearContrato(2L, "p", "i2");
            competidor.setEstado(EstadoContratoEnum.CREADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(contratoRepository.findByPropiedadIdAndEstado(PROPIEDAD, EstadoContratoEnum.CREADO))
                    .thenReturn(List.of(contrato, competidor));

            ContratoResponse resp = contratoService.activarContrato(1L, "sub-admin");

            assertEquals(EstadoContratoEnum.ACTIVO, resp.getEstado());
            assertEquals(EstadoContratoEnum.CANCELADO, competidor.getEstado());
            assertEquals("Otro contrato de la propiedad se activó", competidor.getMotivoCancelacion());
            assertFalse(competidor.getSeniaRetenida());
            verify(contratoRepository).save(competidor);
            // Inicio 17/11 y día 10: la primera cuota vence el 10/12 (no nace vencida)
            assertEquals(LocalDate.of(2026, 12, 10), contrato.getCuotas().get(0).getFechaVencimiento());
        }

        @Test
        @DisplayName("Activar con garante no solvente: 400 sin tocar el estado de la propiedad")
        void activarConGaranteNoSolvente() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(
                    ValidacionGaranteDTO.builder().esSolvente(false).motivoEvaluacion("Situación 3").build());

            assertThrows(OperacionInvalidaException.class, () -> contratoService.activarContrato(1L, "sub-admin"));
            verify(catalogoClient, never()).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Cancelar una seña: libera con esperado RESERVADA; si falla el guardado compensa DISPONIBLE -> RESERVADA con las partes")
        void cancelarCompensaDisponibleAReservada() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.RESERVADA));
            when(contratoRepository.saveAndFlush(contrato)).thenThrow(new ObjectOptimisticLockingFailureException(Contrato.class, 1L));

            assertThrows(ObjectOptimisticLockingFailureException.class,
                    () -> contratoService.cancelarContrato(1L, "Desiste", true, "sub-admin"));

            verify(catalogoClient).actualizarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.RESERVADA, null, null);
            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.RESERVADA, INQUILINO, GARANTE);
        }

        @Test
        @DisplayName("Cancelar una seña muestra el motivo y si la seña se retuvo (RN-09)")
        void cancelarMuestraMotivoYSenia() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.RESERVADA));

            ContratoResponse resp = contratoService.cancelarContrato(1L, "Garante no solvente", true, "sub-admin");

            assertEquals(EstadoContratoEnum.CANCELADO, resp.getEstado());
            assertEquals("Garante no solvente", resp.getMotivoCancelacion());
            assertTrue(resp.getSeniaRetenida());
            verify(catalogoClient, never()).compensarEstadoPropiedad(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Cancelar un borrador CREADO no toca el catálogo")
        void cancelarBorradorNoTocaElCatalogo() {
            Contrato contrato = crearContrato(1L, "p", "i");
            contrato.setEstado(EstadoContratoEnum.CREADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));

            ContratoResponse resp = contratoService.cancelarContrato(1L, "Descarte", false, "sub-admin");

            assertFalse(resp.getSeniaRetenida());
            verify(catalogoClient, never()).obtenerPropiedad(any());
            verify(catalogoClient, never()).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Rescindir: libera con esperado ALQUILADA; si falla el guardado compensa DISPONIBLE -> ALQUILADA con inquilino y garante")
        void rescindirCompensaDisponibleAAlquilada() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 17), LocalDate.of(2028, 7, 17), 6);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));
            when(contratoRepository.saveAndFlush(contrato)).thenThrow(new ObjectOptimisticLockingFailureException(Contrato.class, 1L));

            assertThrows(ObjectOptimisticLockingFailureException.class, () -> contratoService.rescindirContrato(1L,
                    RescindirContratoRequest.builder().fechaRescision(HOY).build(), "sub-admin"));

            verify(catalogoClient).actualizarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.ALQUILADA, null, null);
            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.ALQUILADA, INQUILINO, GARANTE);
        }

        @Test
        @DisplayName("Finalizar: si falla el guardado compensa DISPONIBLE -> ALQUILADA")
        void finalizarCompensaDisponibleAAlquilada() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2025, 11, 17), HOY, 12);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));
            when(contratoRepository.saveAndFlush(contrato)).thenThrow(new ObjectOptimisticLockingFailureException(Contrato.class, 1L));

            assertThrows(ObjectOptimisticLockingFailureException.class,
                    () -> contratoService.finalizarContrato(1L, "sub-admin"));

            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.ALQUILADA, INQUILINO, GARANTE);
        }

        @Test
        @DisplayName("Si la compensación también falla, se relanza el error original (no se lo traga ni lo reemplaza)")
        void compensacionFallidaNoOcultaElErrorOriginal() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(contratoRepository.findByPropiedadIdAndEstado(PROPIEDAD, EstadoContratoEnum.CREADO)).thenReturn(List.of());
            IllegalStateException errorBase = new IllegalStateException("base caída");
            when(contratoRepository.saveAndFlush(contrato)).thenThrow(errorBase);
            when(catalogoClient.compensarEstadoPropiedad(any(), any(), any(), any(), any()))
                    .thenThrow(new CatalogoNoDisponibleException("Catálogo no disponible"));

            assertSame(errorBase, assertThrows(RuntimeException.class,
                    () -> contratoService.activarContrato(1L, "sub-admin")));
            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                    EstadoPropiedadEnum.RESERVADA, null, null);
        }
    }

    // ------------------------------------------------------------------
    // Saga: falla ambigua del PATCH de ida (timeout o 5xx): se compensa por las dudas
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Saga: PATCH de ida sin respuesta clara")
    class FallaAmbiguaDelPatch {

        private final CatalogoNoDisponibleException timeout =
                new CatalogoNoDisponibleException("Catálogo no disponible al cambiar estado de propiedad: Read timed out");

        @Test
        @DisplayName("Señar: timeout del PATCH -> compensa RESERVADA -> DISPONIBLE, no guarda y relanza el 503")
        void seniar() {
            Contrato contrato = crearContrato(1L, "p", "i");
            contrato.setEstado(EstadoContratoEnum.CREADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.DISPONIBLE));
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(timeout);

            assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class,
                    () -> contratoService.seniarContrato(1L, SeniarContratoRequest.builder()
                            .montoSenia(new BigDecimal("100000")).fechaLimiteSenia(HOY.plusDays(10)).build(), "sub-admin")));

            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.RESERVADA,
                    EstadoPropiedadEnum.DISPONIBLE, null, null);
            verify(contratoRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Activar: timeout del PATCH -> compensa ALQUILADA -> RESERVADA, no guarda y relanza el 503")
        void activar() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(timeout);

            assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class,
                    () -> contratoService.activarContrato(1L, "sub-admin")));

            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                    EstadoPropiedadEnum.RESERVADA, null, null);
            verify(contratoRepository, never()).saveAndFlush(any());
            verify(contratoRepository, never()).findByPropiedadIdAndEstado(any(), any());
        }

        @Test
        @DisplayName("Cancelar una seña: 5xx del PATCH -> compensa DISPONIBLE -> RESERVADA con inquilino y garante")
        void cancelar() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.RESERVADA));
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(timeout);

            assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class,
                    () -> contratoService.cancelarContrato(1L, "Desiste", false, "sub-admin")));

            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.RESERVADA, INQUILINO, GARANTE);
            verify(contratoRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Rescindir: timeout del PATCH -> compensa DISPONIBLE -> ALQUILADA con inquilino y garante")
        void rescindir() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 17), LocalDate.of(2028, 7, 17), 6);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(timeout);

            assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class, () -> contratoService.rescindirContrato(
                    1L, RescindirContratoRequest.builder().fechaRescision(HOY).build(), "sub-admin")));

            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.ALQUILADA, INQUILINO, GARANTE);
            verify(contratoRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Finalizar: timeout del PATCH -> compensa DISPONIBLE -> ALQUILADA con inquilino y garante")
        void finalizar() {
            Contrato contrato = contratoActivo(1L, LocalDate.of(2025, 11, 17), HOY, 12);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(timeout);

            assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class,
                    () -> contratoService.finalizarContrato(1L, "sub-admin")));

            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                    EstadoPropiedadEnum.ALQUILADA, INQUILINO, GARANTE);
            verify(contratoRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Si el PATCH no se había aplicado la compensación da 409: se registra en INFO y sale el error original")
        void compensacionConConflictoNoHaciaFalta(CapturedOutput salida) {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(timeout);
            when(catalogoClient.compensarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(
                    new CatalogoConflictoException("La propiedad está en estado RESERVADA y se esperaba ALQUILADA"));

            assertSame(timeout, assertThrows(CatalogoNoDisponibleException.class,
                    () -> contratoService.activarContrato(1L, "sub-admin")));

            assertTrue(salida.getAll().contains("Compensación no necesaria"), salida.getAll());
            assertFalse(salida.getAll().contains("COMPENSACIÓN FALLIDA"));
        }

        @Test
        @DisplayName("Un error inesperado del PATCH (no es un 4xx claro) también se compensa")
        void errorInesperadoTambienCompensa() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            IllegalStateException inesperado = new IllegalStateException("respuesta ilegible");
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any())).thenThrow(inesperado);

            assertSame(inesperado, assertThrows(IllegalStateException.class,
                    () -> contratoService.activarContrato(1L, "sub-admin")));
            verify(catalogoClient).compensarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.ALQUILADA,
                    EstadoPropiedadEnum.RESERVADA, null, null);
        }

        @Test
        @DisplayName("Un 4xx claro del PATCH (400, 404, 502 por credencial) no cambió nada: no se compensa")
        void rechazoClaroNoCompensa() {
            // Un contrato recién leído en cada intento, como en la base (el intento anterior no guardó nada)
            when(contratoRepository.findById(1L)).thenAnswer(inv -> Optional.of(contratoSeniado(1L)));
            when(catalogoClient.evaluarGarante(GARANTE)).thenReturn(ValidacionGaranteDTO.builder().esSolvente(true).build());
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any()))
                    .thenThrow(new OperacionInvalidaException("El catálogo rechazó la operación"))
                    .thenThrow(new RecursoNoEncontradoException("Propiedad con ID 100 no fue encontrado en el catálogo"))
                    .thenThrow(new CatalogoIntegracionException("El catálogo rechazó la credencial del servicio (HTTP 401)"));

            assertThrows(OperacionInvalidaException.class, () -> contratoService.activarContrato(1L, "sub-admin"));
            assertThrows(RecursoNoEncontradoException.class, () -> contratoService.activarContrato(1L, "sub-admin"));
            assertThrows(CatalogoIntegracionException.class, () -> contratoService.activarContrato(1L, "sub-admin"));
            verify(catalogoClient, never()).compensarEstadoPropiedad(any(), any(), any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // Transacciones cortas: ninguna llamada HTTP con una transacción abierta
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Llamadas al catálogo y al índice fuera de toda transacción")
    class SinTransaccionDuranteHttp {

        private final List<String> llamadasConTransaccion = new ArrayList<>();
        private final List<String> guardadosSinTransaccion = new ArrayList<>();
        private ContratoService servicio;

        @BeforeEach
        void setUp() {
            Clock reloj = Clock.fixed(Instant.parse(HOY + "T12:00:00Z"), ZoneOffset.UTC);
            servicio = new ContratoService(contratoRepository, cuotaService, catalogoClient, new EstadoContratoFactory(),
                    indiceService, reloj, new TransaccionesDePrueba());
            when(contratoRepository.saveAndFlush(any(Contrato.class))).thenAnswer(inv -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    guardadosSinTransaccion.add("saveAndFlush");
                }
                return inv.getArgument(0);
            });
            when(catalogoClient.evaluarGarante(GARANTE)).thenAnswer(inv -> registrar("evaluarGarante",
                    ValidacionGaranteDTO.builder().esSolvente(true).build()));
            when(catalogoClient.actualizarEstadoPropiedad(any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> registrar("actualizarEstadoPropiedad", null));
            when(catalogoClient.compensarEstadoPropiedad(any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> registrar("compensarEstadoPropiedad", null));
        }

        private <T> T registrar(String llamada, T resultado) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                llamadasConTransaccion.add(llamada);
            }
            return resultado;
        }

        private void propiedadEn(EstadoPropiedadEnum estado) {
            when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenAnswer(inv -> registrar("obtenerPropiedad", propiedad(estado)));
        }

        private void verificar() {
            assertTrue(llamadasConTransaccion.isEmpty(), "llamadas HTTP con transacción abierta: " + llamadasConTransaccion);
            assertTrue(guardadosSinTransaccion.isEmpty(), "el guardado tiene que ir en la escritura corta");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }

        @Test
        @DisplayName("Señar")
        void seniar() {
            Contrato contrato = crearContrato(1L, "p", "i");
            contrato.setEstado(EstadoContratoEnum.CREADO);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            propiedadEn(EstadoPropiedadEnum.DISPONIBLE);

            servicio.seniarContrato(1L, SeniarContratoRequest.builder().montoSenia(new BigDecimal("100000"))
                    .fechaLimiteSenia(HOY.plusDays(10)).build(), "sub-admin");

            verify(catalogoClient).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
            verificar();
        }

        @Test
        @DisplayName("Activar (la evaluación del garante pasa por el BCRA, hasta 12 s)")
        void activar() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(contratoRepository.findByPropiedadIdAndEstado(PROPIEDAD, EstadoContratoEnum.CREADO)).thenReturn(List.of());

            ContratoResponse resp = servicio.activarContrato(1L, "sub-admin");

            assertEquals(EstadoContratoEnum.ACTIVO, resp.getEstado());
            var orden = inOrder(contratoRepository, catalogoClient);
            orden.verify(contratoRepository).findById(1L);
            orden.verify(catalogoClient).evaluarGarante(GARANTE);
            orden.verify(catalogoClient).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
            orden.verify(contratoRepository).saveAndFlush(contrato);
            verificar();
        }

        @Test
        @DisplayName("Cancelar una seña")
        void cancelar() {
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contratoSeniado(1L)));
            propiedadEn(EstadoPropiedadEnum.RESERVADA);

            servicio.cancelarContrato(1L, "Desiste", false, "sub-admin");

            verify(catalogoClient).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
            verificar();
        }

        @Test
        @DisplayName("Rescindir")
        void rescindir() {
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(
                    contratoActivo(1L, LocalDate.of(2026, 7, 17), LocalDate.of(2028, 7, 17), 6)));
            propiedadEn(EstadoPropiedadEnum.ALQUILADA);

            servicio.rescindirContrato(1L, RescindirContratoRequest.builder().fechaRescision(HOY).build(), "sub-admin");

            verify(catalogoClient).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
            verificar();
        }

        @Test
        @DisplayName("Finalizar")
        void finalizar() {
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contratoActivo(1L, LocalDate.of(2025, 11, 17), HOY, 12)));
            propiedadEn(EstadoPropiedadEnum.ALQUILADA);

            servicio.finalizarContrato(1L, "sub-admin");

            verify(catalogoClient).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
            verificar();
        }

        @Test
        @DisplayName("Compensación después de una escritura fallida: también fuera de la transacción")
        void compensacion() {
            Contrato contrato = contratoSeniado(1L);
            when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
            when(contratoRepository.findByPropiedadIdAndEstado(PROPIEDAD, EstadoContratoEnum.CREADO)).thenReturn(List.of());
            doThrow(new ObjectOptimisticLockingFailureException(Contrato.class, 1L)).when(contratoRepository).saveAndFlush(contrato);

            assertThrows(ObjectOptimisticLockingFailureException.class, () -> servicio.activarContrato(1L, "sub-admin"));

            verify(catalogoClient).compensarEstadoPropiedad(any(), any(), any(), any(), any());
            verificar();
        }

        @Test
        @DisplayName("Crear el borrador")
        void crear() {
            when(catalogoClient.obtenerPropiedad(1L)).thenAnswer(inv -> registrar("obtenerPropiedad", PropiedadDTO.builder()
                    .id(1L).propietarioId(10L).estado(EstadoPropiedadEnum.DISPONIBLE).build()));
            when(catalogoClient.obtenerPersona(any())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                String rol = id == 10L ? "PROPIETARIO" : id == 20L ? "INQUILINO" : "GARANTE";
                return registrar("obtenerPersona", PersonaResumenDTO.builder().id(id).keycloakUserId("sub-" + id)
                        .roles(Set.of(rol)).build());
            });
            when(contratoRepository.save(any(Contrato.class))).thenAnswer(inv -> inv.getArgument(0));

            servicio.crearContrato(requestAlta().build());

            verify(catalogoClient, times(3)).obtenerPersona(any());
            verificar();
        }
    }

    /**
     * Administrador de transacciones mínimo: abre y cierra transacciones "de verdad" para
     * TransactionSynchronizationManager, sin base de datos.
     */
    static class TransaccionesDePrueba extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaccion, TransactionDefinition definicion) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus estado) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus estado) {
        }
    }

    // ------------------------------------------------------------------
    // Rescisión y finalización
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C-07: 4 meses de antigüedad y frecuencia 6: penalización 750.000 y cuotas futuras anuladas; la segunda se rechaza")
    void c07Rescision() {
        Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 17), LocalDate.of(2028, 7, 16), 6);
        when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
        when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));
        RescindirContratoRequest req = RescindirContratoRequest.builder().fechaRescision(HOY).build();

        RescisionResponse resp = contratoService.rescindirContrato(1L, req, "sub-admin");

        assertEquals(new BigDecimal("750000.00"), resp.getPenalizacion());
        // Vencen 10/12/2026 y 10/01/2027, después del 17/11
        assertEquals(2, resp.getCuotasAnuladas());
        assertEquals(EstadoContratoEnum.RESCINDIDO, contrato.getEstado());
        assertThrows(OperacionInvalidaException.class, () -> contratoService.rescindirContrato(1L, req, "sub-admin"));
    }

    @Test
    @DisplayName("Rescisión fuera de rango (futura, 2030 o anterior al inicio): 400 sin tocar el catálogo")
    void rescisionFueraDeRango() {
        Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 17), LocalDate.of(2028, 7, 16), 6);
        when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));

        assertThrows(OperacionInvalidaException.class, () -> contratoService.rescindirContrato(1L,
                RescindirContratoRequest.builder().fechaRescision(LocalDate.of(2030, 1, 1)).build(), "sub-admin"));
        OperacionInvalidaException futura = assertThrows(OperacionInvalidaException.class,
                () -> contratoService.rescindirContrato(1L,
                        RescindirContratoRequest.builder().fechaRescision(HOY.plusDays(1)).build(), "sub-admin"));
        assertTrue(futura.getMessage().contains("no puede ser futura"), futura.getMessage());
        assertThrows(OperacionInvalidaException.class, () -> contratoService.rescindirContrato(1L,
                RescindirContratoRequest.builder().fechaRescision(LocalDate.of(2026, 7, 16)).build(), "sub-admin"));
        verify(catalogoClient, never()).obtenerPropiedad(any());
        assertEquals(EstadoContratoEnum.ACTIVO, contrato.getEstado());
    }

    @Test
    @DisplayName("Rescisión con una fecha pasada (ayer): válida, la propiedad se libera hoy")
    void rescisionConFechaPasada() {
        Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 17), LocalDate.of(2028, 7, 16), 6);
        when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
        when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));

        RescisionResponse resp = contratoService.rescindirContrato(1L,
                RescindirContratoRequest.builder().fechaRescision(HOY.minusDays(1)).build(), "sub-admin");

        assertEquals(HOY.minusDays(1), resp.getFechaRescision());
        assertEquals(EstadoContratoEnum.RESCINDIDO, contrato.getEstado());
        verify(catalogoClient).actualizarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                EstadoPropiedadEnum.ALQUILADA, null, null);
    }

    @Test
    @DisplayName("Finalizar: no anula la cuota cuyo período empezó antes del fin aunque venza después, y libera la propiedad")
    void finalizarConservaLasCuotasDevengadas() {
        // 16/11/2025 -> 17/11/2026: 13 meses; la cuota 13 (período 16/11/2026) vence el 10/12/2026 y se cobra
        Contrato contrato = contratoActivo(1L, LocalDate.of(2025, 11, 16), HOY, 13);
        when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));
        when(catalogoClient.obtenerPropiedad(PROPIEDAD)).thenReturn(propiedad(EstadoPropiedadEnum.ALQUILADA));

        ContratoResponse resp = contratoService.finalizarContrato(1L, "sub-admin");

        assertEquals(EstadoContratoEnum.FINALIZADO, resp.getEstado());
        assertEquals(EstadoCuotaEnum.PENDIENTE, contrato.getCuotas().get(12).getEstado());
        assertEquals(EstadoCuotaEnum.PENDIENTE, contrato.getCuotas().get(11).getEstado());
        verify(catalogoClient).actualizarEstadoPropiedad(PROPIEDAD, EstadoPropiedadEnum.DISPONIBLE,
                EstadoPropiedadEnum.ALQUILADA, null, null);
    }

    @Test
    @DisplayName("Finalizar antes de la fecha de fin: 400 del State")
    void finalizarAntesDelFin() {
        Contrato contrato = contratoActivo(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
        when(contratoRepository.findById(1L)).thenReturn(Optional.of(contrato));

        assertThrows(OperacionInvalidaException.class, () -> contratoService.finalizarContrato(1L, "sub-admin"));
        verify(catalogoClient, never()).actualizarEstadoPropiedad(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void mockearIcl(LocalDate desde, LocalDate hasta, String anterior, String nuevo, LocalDate fechaValorHasta) {
        when(indiceService.calcularAjuste(eq(TipoIndiceEnum.ICL), any(), eq(desde), eq(hasta))).thenReturn(
                ResultadoAjusteDTO.builder()
                        .tipoIndice(TipoIndiceEnum.ICL)
                        .fechaIndiceDesde(desde)
                        .fechaIndiceHasta(fechaValorHasta != null ? fechaValorHasta : hasta)
                        .valorIndiceDesde(new BigDecimal("10.00"))
                        .valorIndiceHasta(new BigDecimal("12.50"))
                        .coeficiente(new BigDecimal("1.25000000"))
                        .montoAnterior(new BigDecimal(anterior))
                        .montoNuevo(new BigDecimal(nuevo))
                        .porcentajeVariacion(new BigDecimal("25.00"))
                        .origen(OrigenDatoEnum.BCRA)
                        .build());
    }

    private void mockearPartesAlta() {
        when(catalogoClient.obtenerPropiedad(1L)).thenReturn(PropiedadDTO.builder()
                .id(1L).propietarioId(10L).estado(EstadoPropiedadEnum.DISPONIBLE).build());
        when(catalogoClient.obtenerPersona(10L)).thenReturn(PersonaResumenDTO.builder()
                .id(10L).keycloakUserId("sub-prop").roles(Set.of("PROPIETARIO")).build());
        when(catalogoClient.obtenerPersona(20L)).thenReturn(PersonaResumenDTO.builder()
                .id(20L).keycloakUserId("sub-inq").roles(Set.of("INQUILINO")).build());
        when(catalogoClient.obtenerPersona(30L)).thenReturn(PersonaResumenDTO.builder()
                .id(30L).keycloakUserId(null).roles(Set.of("GARANTE")).build());
    }

    private static CrearContratoRequest.CrearContratoRequestBuilder requestAlta() {
        return CrearContratoRequest.builder()
                .propiedadId(1L).propietarioId(10L).inquilinoId(20L).garanteId(30L)
                .fechaInicio(LocalDate.of(2026, 8, 1)).fechaFin(LocalDate.of(2028, 8, 1))
                .frecuenciaAjusteMeses(3).montoInicial(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.10")).diaVencimientoMes(10);
    }

    private static AplicarAjusteRequest pedido(LocalDate desde, LocalDate hasta) {
        return AplicarAjusteRequest.builder().fechaIndiceDesde(desde).fechaIndiceHasta(hasta).build();
    }

    private static PropiedadDTO propiedad(EstadoPropiedadEnum estado) {
        return PropiedadDTO.builder().id(PROPIEDAD).estado(estado).build();
    }

    /** Contrato SEÑADO con inicio hoy (17/11/2026), seña vigente. */
    static Contrato contratoSeniado(Long id) {
        Contrato contrato = Contrato.builder()
                .id(id).nroContrato("CONT-TEST" + id)
                .propiedadId(PROPIEDAD).propietarioId(200L).inquilinoId(INQUILINO).garanteId(GARANTE)
                .propietarioKeycloakId("p").inquilinoKeycloakId("i")
                .fechaInicio(HOY).fechaFin(HOY.plusYears(2)).frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00")).montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000")).diaVencimientoMes(10)
                .montoSenia(new BigDecimal("100000.00")).fechaLimiteSenia(HOY.plusDays(10))
                .estado(EstadoContratoEnum.SENIADO)
                .build();
        return contrato;
    }

    /** Contrato ACTIVO con las cuotas del tramo 0 generadas por el State al activarlo en su fecha de inicio. */
    static Contrato contratoActivo(Long id, LocalDate inicio, LocalDate fin, int frecuencia) {
        Contrato contrato = Contrato.builder()
                .id(id).nroContrato("CONT-TEST" + id)
                .propiedadId(PROPIEDAD).propietarioId(200L).inquilinoId(INQUILINO).garanteId(GARANTE)
                .propietarioKeycloakId("p").inquilinoKeycloakId("i")
                .fechaInicio(inicio).fechaFin(fin).frecuenciaAjusteMeses(frecuencia)
                .tipoIndice(TipoIndiceEnum.ICL)
                .montoInicial(new BigDecimal("500000.00")).montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000")).diaVencimientoMes(10)
                .estado(EstadoContratoEnum.SENIADO)
                .build();
        new EstadoContratoFactory().obtenerEstado(contrato).activar(contrato, inicio);
        return contrato;
    }

    /** Ajuste ya aplicado (como lo dejaría ContratoService), para armar contratos con historia. */
    static Ajuste ajusteAplicado(LocalDate fechaAplicacion, String montoNuevo) {
        return Ajuste.builder()
                .tipoIndice(TipoIndiceEnum.ICL).fechaAplicacion(fechaAplicacion)
                .valorIndiceDesde(new BigDecimal("10")).valorIndiceHasta(new BigDecimal("11"))
                .coeficiente(new BigDecimal("1.1")).montoAnterior(new BigDecimal("500000.00"))
                .montoNuevo(new BigDecimal(montoNuevo)).porcentajeVariacion(new BigDecimal("10"))
                .origen(OrigenDatoEnum.BCRA).build();
    }

    private Contrato crearContrato(Long id, String propietarioKeycloakId, String inquilinoKeycloakId) {
        return Contrato.builder()
                .id(id)
                .nroContrato("CONT-TEST" + id)
                .propiedadId(PROPIEDAD)
                .propietarioId(10L + id)
                .inquilinoId(INQUILINO)
                .garanteId(GARANTE)
                .propietarioKeycloakId(propietarioKeycloakId)
                .inquilinoKeycloakId(inquilinoKeycloakId)
                .fechaInicio(LocalDate.of(2026, 1, 1))
                .fechaFin(LocalDate.of(2027, 1, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10)
                .estado(EstadoContratoEnum.ACTIVO)
                .build();
    }
}
