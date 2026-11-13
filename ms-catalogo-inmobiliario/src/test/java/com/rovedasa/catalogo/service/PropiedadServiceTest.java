package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.client.georef.DireccionNormalizada;
import com.rovedasa.catalogo.client.georef.GeoRefClient;
import com.rovedasa.catalogo.dto.CrearPropiedadRequest;
import com.rovedasa.catalogo.dto.ModificarPropiedadRequest;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.exception.ServicioExternoNoDisponibleException;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.catalogo.repository.PropiedadRepository;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropiedadServiceTest {

    private static final double LATITUD = -31.4266835418118;
    private static final double LONGITUD = -64.1885105207543;
    private static final double LATITUD_1200 = -31.4297781602675;
    private static final double LONGITUD_1200 = -64.1898168807412;

    @Mock
    private PropiedadRepository propiedadRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private GeoRefClient geoRefClient;
    @Mock
    private TasacionService tasacionService;
    // Mock: TransactionTemplate abre y confirma "transacciones" sobre él, y permite contarlas
    @Mock
    private PlatformTransactionManager transactionManager;

    private PropiedadService propiedadService;

    private Persona propietario;
    private Persona inquilino;
    private Persona garante;

    @BeforeEach
    void prepararServicio() {
        propiedadService = new PropiedadService(propiedadRepository, personaRepository, geoRefClient,
                tasacionService, transactionManager);
        propietario = persona(1L, "Juan", TipoPersonaEnum.PROPIETARIO);
        inquilino = persona(2L, "Lucía", TipoPersonaEnum.INQUILINO);
        garante = persona(3L, "Marcos", TipoPersonaEnum.GARANTE);
    }

    // --- registrar ---

    @Test
    void registrarAplicaNormalizacionYTasacionYGuarda() {
        PrecioReferenciaZona nuevaCordoba = zona();
        when(personaRepository.findById(1L)).thenReturn(Optional.of(propietario));
        when(geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba"))
                .thenReturn(Optional.of(direccionIndependencia900()));
        // 55 m² totales y 50 cubiertos -> 50 + 5 × 0,5 = 52,50 m² computables
        when(tasacionService.tasar(LATITUD, LONGITUD, "Córdoba", "Córdoba", TipologiaEnum.DEPARTAMENTO,
                new BigDecimal("52.50")))
                .thenReturn(new Tasacion(nuevaCordoba, new BigDecimal("14000.00"), new BigDecimal("735000.00")));
        when(propiedadRepository.save(any(Propiedad.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        PropiedadResponse respuesta = propiedadService.registrar(solicitud(" Independencia ", "Córdoba", "50"));

        ArgumentCaptor<Propiedad> guardada = ArgumentCaptor.forClass(Propiedad.class);
        verify(propiedadRepository).save(guardada.capture());
        Propiedad propiedad = guardada.getValue();
        assertEquals("INDEPENDENCIA 900, Capital, Córdoba", propiedad.getDireccionNormalizada());
        assertEquals(LATITUD, propiedad.getLatitud());
        assertEquals(LONGITUD, propiedad.getLongitud());
        assertSame(nuevaCordoba, propiedad.getZonaReferencia());
        assertEquals(new BigDecimal("735000.00"), propiedad.getCanonBaseSugerido());
        assertSame(propietario, propiedad.getPropietario());
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());

        assertEquals("Nueva Córdoba", respuesta.zonaNombre());
        assertEquals(new BigDecimal("14000.00"), respuesta.precioSugeridoM2());
        assertEquals("Juan Pérez", respuesta.propietarioNombreCompleto());
        // Dos transacciones cortas (lectura del propietario y escritura); GeoRef queda afuera de ambas
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    void registrarConPropietarioSinRolLanzaOperacionInvalida() {
        when(personaRepository.findById(2L)).thenReturn(Optional.of(inquilino));
        CrearPropiedadRequest solicitud = new CrearPropiedadRequest(TipologiaEnum.CASA, "Independencia", "900",
                null, null, null, null, "Córdoba", "Córdoba", new BigDecimal("55"), null, 2L);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.registrar(solicitud));

        assertTrue(ex.getMessage().contains("no tiene el rol PROPIETARIO"));
        verifyNoInteractions(geoRefClient);
        verify(propiedadRepository, never()).save(any());
    }

    @Test
    void registrarConPropietarioInexistenteLanzaRecursoNoEncontrado() {
        when(personaRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> propiedadService.registrar(solicitud("Independencia", "Córdoba", null)));
        verifyNoInteractions(geoRefClient);
    }

    @Test
    void registrarConCubiertaMayorQueTotalNoLlamaAGeoRef() {
        when(personaRepository.findById(1L)).thenReturn(Optional.of(propietario));

        assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.registrar(solicitud("Independencia", "Córdoba", "60")));
        verifyNoInteractions(geoRefClient);
    }

    @Test
    void registrarConDireccionNoNormalizableLanzaOperacionInvalidaConMensajeOrientativo() {
        when(personaRepository.findById(1L)).thenReturn(Optional.of(propietario));
        when(geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba Capital"))
                .thenReturn(Optional.empty());

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.registrar(solicitud("Independencia", "Córdoba Capital", null)));

        assertTrue(ex.getMessage().contains("'Independencia 900' en Córdoba Capital, Córdoba"));
        assertTrue(ex.getMessage().contains("nombre oficial de la localidad"));
        verify(propiedadRepository, never()).save(any());
        verifyNoInteractions(tasacionService);
    }

    @Test
    void registrarConGeoRefCaidoPropagaLaExcepcionYNoGuardaNada() {
        when(personaRepository.findById(1L)).thenReturn(Optional.of(propietario));
        when(geoRefClient.normalizar("Independencia", "900", "Córdoba", "Córdoba"))
                .thenThrow(new ServicioExternoNoDisponibleException("GeoRef no disponible", null));

        assertThrows(ServicioExternoNoDisponibleException.class,
                () -> propiedadService.registrar(solicitud("Independencia", "Córdoba", null)));

        verify(propiedadRepository, never()).save(any());
        verify(tasacionService, never()).tasar(anyDouble(), anyDouble(), any(), any(), any(), any());
        // Solo se abrió la transacción de lectura: la de escritura nunca empezó
        verify(transactionManager, times(1)).getTransaction(any());
    }

    // --- actualizarEstado ---

    @Test
    void actualizarAReservadaAsignaInquilinoYGarante() {
        Propiedad propiedad = propiedadDisponible();
        when(personaRepository.findById(2L)).thenReturn(Optional.of(inquilino));
        when(personaRepository.findById(3L)).thenReturn(Optional.of(garante));

        PropiedadDTO dto = propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.RESERVADA, 2L, 3L));

        assertEquals(EstadoPropiedadEnum.RESERVADA, dto.getEstado());
        assertEquals(2L, dto.getInquilinoId());
        assertSame(garante, propiedad.getGarante());
    }

    @Test
    void actualizarAAlquiladaDesdeReservada() {
        Propiedad propiedad = propiedadDisponible();
        propiedad.reservar(inquilino, garante);

        PropiedadDTO dto = propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.ALQUILADA, null, null));

        assertEquals(EstadoPropiedadEnum.ALQUILADA, dto.getEstado());
    }

    @Test
    void actualizarADisponibleDesdeAlquiladaLibera() {
        Propiedad propiedad = propiedadDisponible();
        propiedad.reservar(inquilino, garante);
        propiedad.alquilar();

        PropiedadDTO dto = propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.DISPONIBLE, null, null));

        assertEquals(EstadoPropiedadEnum.DISPONIBLE, dto.getEstado());
        assertNull(dto.getInquilinoId());
        assertNull(propiedad.getGarante());
    }

    @Test
    void actualizarADisponibleDesdeReservadaCaeLaReserva() {
        Propiedad propiedad = propiedadDisponible();
        propiedad.reservar(inquilino, garante);

        PropiedadDTO dto = propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.DISPONIBLE, null, null));

        assertEquals(EstadoPropiedadEnum.DISPONIBLE, dto.getEstado());
        assertNull(dto.getInquilinoId());
    }

    @Test
    void transicionInvalidaDisponibleAAlquiladaLanzaOperacionInvalidaConMensajeClaro() {
        Propiedad propiedad = propiedadDisponible();

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.ALQUILADA, null, null)));

        assertEquals("No se puede pasar de DISPONIBLE a ALQUILADA", ex.getMessage());
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
    }

    @Test
    void transicionAlMismoEstadoLanzaOperacionInvalida() {
        propiedadDisponible();

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.DISPONIBLE, null, null)));

        assertEquals("No se puede pasar de DISPONIBLE a DISPONIBLE", ex.getMessage());
    }

    @Test
    void inactivarPorPatchNoEstaPermitido() {
        propiedadDisponible();

        assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.INACTIVA, null, null)));
    }

    @Test
    void transicionInvalidaSeRechazaAntesDeBuscarPersonas() {
        Propiedad propiedad = propiedadDisponible();
        propiedad.reservar(inquilino, garante);
        propiedad.alquilar();

        assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.RESERVADA, 2L, 3L)));

        verifyNoInteractions(personaRepository);
    }

    @Test
    void reservarConInquilinoSinRolInquilinoLanzaOperacionInvalida() {
        Propiedad propiedad = propiedadDisponible();
        when(personaRepository.findById(3L)).thenReturn(Optional.of(garante));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.RESERVADA, 3L, null)));

        assertTrue(ex.getMessage().contains("no tiene el rol INQUILINO"));
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
    }

    @Test
    void reservarConGaranteIgualAlInquilinoLanzaOperacionInvalida() {
        Propiedad propiedad = propiedadDisponible();
        Persona inquilinoYGarante = persona(4L, "Sofía", TipoPersonaEnum.INQUILINO);
        inquilinoYGarante.agregarRol(TipoPersonaEnum.GARANTE);
        when(personaRepository.findById(4L)).thenReturn(Optional.of(inquilinoYGarante));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.RESERVADA, 4L, 4L)));

        assertTrue(ex.getMessage().contains("misma persona que el inquilino"));
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
    }

    @Test
    void reservarConGaranteIgualAlPropietarioLanzaOperacionInvalida() {
        propiedadDisponible();
        propietario.agregarRol(TipoPersonaEnum.GARANTE);
        when(personaRepository.findById(2L)).thenReturn(Optional.of(inquilino));
        when(personaRepository.findById(1L)).thenReturn(Optional.of(propietario));

        assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.RESERVADA, 2L, 1L)));
    }

    @Test
    void reservarConInquilinoInexistenteLanzaRecursoNoEncontrado() {
        propiedadDisponible();
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.RESERVADA, 99L, null)));
    }

    @Test
    void actualizarPropiedadInexistenteLanzaRecursoNoEncontrado() {
        when(propiedadRepository.findById(10L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> propiedadService.actualizarEstado(10L, cambio(EstadoPropiedadEnum.INACTIVA, null, null)));
    }

    // --- modificar (F-06) ---

    @Test
    void modificarSinCambioDeDireccionRetasaConLasCoordenadasActualesSinLlamarAGeoRef() {
        propiedadNormalizada();
        // CASA, 80 m² totales y 60 cubiertos -> 60 + 20 × 0,5 = 70,00 m² computables
        when(tasacionService.tasar(LATITUD, LONGITUD, "Córdoba", "Córdoba", TipologiaEnum.CASA,
                new BigDecimal("70.00")))
                .thenReturn(new Tasacion(zona(), new BigDecimal("15400.00"), new BigDecimal("1078000.00")));
        when(propiedadRepository.save(any(Propiedad.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        PropiedadResponse respuesta = propiedadService.modificar(10L,
                modificacion("Independencia", "900", TipologiaEnum.CASA, "80", "60"));

        verifyNoInteractions(geoRefClient);
        assertEquals(TipologiaEnum.CASA, respuesta.tipologia());
        assertEquals(new BigDecimal("70.00"), respuesta.superficieComputableM2());
        assertEquals(new BigDecimal("15400.00"), respuesta.precioSugeridoM2());
        assertEquals(new BigDecimal("1078000.00"), respuesta.canonBaseSugerido());
        assertEquals("Nueva Córdoba", respuesta.zonaNombre());
        // Conserva la normalización y el propietario
        assertEquals("INDEPENDENCIA 900, Capital, Córdoba", respuesta.direccionNormalizada());
        assertEquals(LATITUD, respuesta.latitud());
        assertEquals(1L, respuesta.propietarioId());
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, respuesta.estado());
        // Dos transacciones cortas (lectura y escritura), igual que el alta
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    void modificarConCambioDeDireccionNormalizaConGeoRefYRetasaConLasCoordenadasNuevas() {
        propiedadNormalizada();
        when(geoRefClient.normalizar("Independencia", "1200", "Córdoba", "cordoba"))
                .thenReturn(Optional.of(direccionIndependencia1200()));
        when(tasacionService.tasar(LATITUD_1200, LONGITUD_1200, "Córdoba", "Córdoba", TipologiaEnum.DEPARTAMENTO,
                new BigDecimal("55.00")))
                .thenReturn(new Tasacion(zona(), new BigDecimal("14000.00"), new BigDecimal("770000.00")));
        when(propiedadRepository.save(any(Propiedad.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // Ciudad en minúsculas: se guarda el nombre oficial que devuelve GeoRef
        PropiedadResponse respuesta = propiedadService.modificar(10L,
                new ModificarPropiedadRequest(TipologiaEnum.DEPARTAMENTO, " Independencia ", "1200", " ", null,
                        null, null, "cordoba", "Córdoba", new BigDecimal("55"), null));

        ArgumentCaptor<Propiedad> guardada = ArgumentCaptor.forClass(Propiedad.class);
        verify(propiedadRepository).save(guardada.capture());
        Propiedad propiedad = guardada.getValue();
        assertEquals("Independencia", propiedad.getCalle());
        assertEquals("1200", propiedad.getNumero());
        assertNull(propiedad.getPiso());
        assertEquals("Córdoba", propiedad.getCiudad());
        assertEquals("INDEPENDENCIA 1200, Capital, Córdoba", propiedad.getDireccionNormalizada());
        assertEquals(LATITUD_1200, propiedad.getLatitud());
        assertEquals(LONGITUD_1200, propiedad.getLongitud());
        assertEquals(new BigDecimal("770000.00"), respuesta.canonBaseSugerido());
    }

    @Test
    void modificarPropiedadInactivaEstaPermitido() {
        propiedadEn(EstadoPropiedadEnum.INACTIVA);
        when(tasacionService.tasar(anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(Tasacion.sinCobertura());
        when(propiedadRepository.save(any(Propiedad.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        PropiedadResponse respuesta = propiedadService.modificar(10L,
                modificacion("Independencia", "900", TipologiaEnum.PH, "55", null));

        assertEquals(TipologiaEnum.PH, respuesta.tipologia());
        assertEquals(EstadoPropiedadEnum.INACTIVA, respuesta.estado());
        assertNull(respuesta.canonBaseSugerido());
    }

    @Test
    void modificarPropiedadReservadaLanzaOperacionInvalidaSinLlamarAGeoRefNiGuardar() {
        Propiedad propiedad = propiedadEn(EstadoPropiedadEnum.RESERVADA);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.modificar(10L,
                        modificacion("Independencia", "1200", TipologiaEnum.CASA, "55", null)));

        assertTrue(ex.getMessage().contains("está RESERVADA y hay un contrato que la referencia"));
        assertEquals(TipologiaEnum.DEPARTAMENTO, propiedad.getTipologia());
        verifyNoInteractions(geoRefClient, tasacionService);
        verify(propiedadRepository, never()).save(any());
    }

    @Test
    void modificarPropiedadAlquiladaLanzaOperacionInvalidaSinLlamarAGeoRefNiGuardar() {
        propiedadEn(EstadoPropiedadEnum.ALQUILADA);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.modificar(10L,
                        modificacion("Independencia", "900", TipologiaEnum.CASA, "55", null)));

        assertTrue(ex.getMessage().contains("está ALQUILADA y hay un contrato que la referencia"));
        verifyNoInteractions(geoRefClient, tasacionService);
        verify(propiedadRepository, never()).save(any());
    }

    @Test
    void modificarConDireccionQueGeoRefNoEncuentraLanzaOperacionInvalidaYNoGuarda() {
        propiedadNormalizada();
        when(geoRefClient.normalizar("Obispo Trejo", "1200", "Córdoba", "Córdoba")).thenReturn(Optional.empty());

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.modificar(10L,
                        modificacion("Obispo Trejo", "1200", TipologiaEnum.DEPARTAMENTO, "55", null)));

        assertTrue(ex.getMessage().contains("No se pudo normalizar la dirección 'Obispo Trejo 1200'"));
        verifyNoInteractions(tasacionService);
        verify(propiedadRepository, never()).save(any());
    }

    @Test
    void modificarConGeoRefCaidoPropagaLaExcepcionYNoGuardaNada() {
        propiedadNormalizada();
        when(geoRefClient.normalizar("Independencia", "1200", "Córdoba", "Córdoba"))
                .thenThrow(new ServicioExternoNoDisponibleException("GeoRef no disponible", null));

        assertThrows(ServicioExternoNoDisponibleException.class,
                () -> propiedadService.modificar(10L,
                        modificacion("Independencia", "1200", TipologiaEnum.DEPARTAMENTO, "55", null)));

        verifyNoInteractions(tasacionService);
        verify(propiedadRepository, never()).save(any());
        // Solo se abrió la transacción de lectura: la de escritura nunca empezó
        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    void modificarPropiedadInexistenteLanzaRecursoNoEncontrado() {
        when(propiedadRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> propiedadService.modificar(99L,
                        modificacion("Independencia", "900", TipologiaEnum.CASA, "55", null)));
        verifyNoInteractions(geoRefClient, tasacionService);
    }

    // --- darDeBaja (F-06) ---

    @Test
    void darDeBajaDesdeDisponibleLaDejaInactiva() {
        Propiedad propiedad = propiedadNormalizada();

        PropiedadResponse respuesta = propiedadService.darDeBaja(10L);

        assertEquals(EstadoPropiedadEnum.INACTIVA, respuesta.estado());
        assertEquals(EstadoPropiedadEnum.INACTIVA, propiedad.getEstado());
        assertEquals(10L, respuesta.id());
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPropiedadEnum.class, names = {"RESERVADA", "ALQUILADA", "INACTIVA"})
    void darDeBajaFueraDeDisponibleLanzaOperacionInvalidaYNoCambiaElEstado(EstadoPropiedadEnum estado) {
        Propiedad propiedad = propiedadEn(estado);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadService.darDeBaja(10L));

        assertTrue(ex.getMessage().contains("su estado actual es " + estado));
        assertEquals(estado, propiedad.getEstado());
    }

    @Test
    void darDeBajaPropiedadInexistenteLanzaRecursoNoEncontrado() {
        when(propiedadRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> propiedadService.darDeBaja(99L));
    }

    // --- listarPropias ---

    @Test
    void listarPropiasFiltraPorElKeycloakUserIdDelToken() {
        Propiedad propiedad = Propiedad.builder()
                .codigo("PROP-TEST0001")
                .tipologia(TipologiaEnum.DEPARTAMENTO)
                .calle("Independencia")
                .numero("900")
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal("55"))
                .propietario(propietario)
                .build();
        when(propiedadRepository.findByPropietario_KeycloakUserId("kc-propietario-1"))
                .thenReturn(List.of(propiedad));

        List<PropiedadResponse> propias = propiedadService.listarPropias("kc-propietario-1");

        assertEquals(1, propias.size());
        assertEquals("PROP-TEST0001", propias.get(0).codigo());
        assertEquals(1L, propias.get(0).propietarioId());
    }

    @Test
    void listarPropiasSinPropiedadesDevuelveListaVacia() {
        when(propiedadRepository.findByPropietario_KeycloakUserId("kc-sin-propiedades")).thenReturn(List.of());

        assertTrue(propiedadService.listarPropias("kc-sin-propiedades").isEmpty());
    }

    // --- Datos de prueba ---

    private Propiedad propiedadDisponible() {
        Propiedad propiedad = Propiedad.builder()
                .codigo("PROP-TEST0001")
                .tipologia(TipologiaEnum.DEPARTAMENTO)
                .calle("Independencia")
                .numero("900")
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal("55"))
                .propietario(propietario)
                .build();
        ReflectionTestUtils.setField(propiedad, "id", 10L);
        when(propiedadRepository.findById(10L)).thenReturn(Optional.of(propiedad));
        return propiedad;
    }

    // Disponible y ya normalizada en Independencia 900, como queda después del alta
    private Propiedad propiedadNormalizada() {
        Propiedad propiedad = propiedadDisponible();
        propiedad.aplicarNormalizacion("INDEPENDENCIA 900, Capital, Córdoba", "Córdoba", "Córdoba", LATITUD, LONGITUD);
        return propiedad;
    }

    private Propiedad propiedadEn(EstadoPropiedadEnum estado) {
        Propiedad propiedad = propiedadNormalizada();
        switch (estado) {
            case RESERVADA -> propiedad.reservar(inquilino, garante);
            case ALQUILADA -> {
                propiedad.reservar(inquilino, garante);
                propiedad.alquilar();
            }
            case INACTIVA -> propiedad.inactivar();
            case DISPONIBLE -> {
            }
        }
        return propiedad;
    }

    private static ModificarPropiedadRequest modificacion(String calle, String numero, TipologiaEnum tipologia,
            String superficieTotal, String superficieCubierta) {
        return new ModificarPropiedadRequest(tipologia, calle, numero, null, null, null, null, "Córdoba", "Córdoba",
                new BigDecimal(superficieTotal),
                superficieCubierta != null ? new BigDecimal(superficieCubierta) : null);
    }

    private static DireccionNormalizada direccionIndependencia1200() {
        return new DireccionNormalizada("INDEPENDENCIA 1200, Capital, Córdoba", "Córdoba", "Córdoba", "Capital",
                LATITUD_1200, LONGITUD_1200);
    }

    private static CrearPropiedadRequest solicitud(String calle, String ciudad, String superficieCubierta) {
        return new CrearPropiedadRequest(TipologiaEnum.DEPARTAMENTO, calle, "900", "3", "B", null, null,
                ciudad, "Córdoba", new BigDecimal("55"),
                superficieCubierta != null ? new BigDecimal(superficieCubierta) : null, 1L);
    }

    private static DireccionNormalizada direccionIndependencia900() {
        return new DireccionNormalizada("INDEPENDENCIA 900, Capital, Córdoba", "Córdoba", "Córdoba", "Capital",
                LATITUD, LONGITUD);
    }

    private static ActualizarEstadoPropiedadDTO cambio(EstadoPropiedadEnum estado, Long inquilinoId, Long garanteId) {
        return ActualizarEstadoPropiedadDTO.builder()
                .nuevoEstado(estado)
                .inquilinoId(inquilinoId)
                .garanteId(garanteId)
                .build();
    }

    private static PrecioReferenciaZona zona() {
        return PrecioReferenciaZona.builder()
                .nombreZona("Nueva Córdoba")
                .provincia("Córdoba")
                .ciudad("Córdoba")
                .latitudCentro(-31.4245)
                .longitudCentro(-64.1870)
                .radioMetros(1200)
                .valorM2(new BigDecimal("14000"))
                .puntajeZona(9)
                .fechaActualizacion(LocalDate.now())
                .build();
    }

    private static Persona persona(Long id, String nombre, TipoPersonaEnum rol) {
        Persona persona = Persona.builder()
                .cuitCuil("2000000000" + id)
                .nombre(nombre)
                .apellido("Pérez")
                .roles(Set.of(rol))
                .build();
        ReflectionTestUtils.setField(persona, "id", id);
        return persona;
    }
}
