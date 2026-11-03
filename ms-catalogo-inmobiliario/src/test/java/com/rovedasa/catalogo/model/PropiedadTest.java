package com.rovedasa.catalogo.model;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropiedadTest {

    private Persona inquilino;
    private Persona garante;
    private Propiedad propiedad;

    @BeforeEach
    void prepararPropiedadDisponible() {
        Persona propietario = persona("20111111112", TipoPersonaEnum.PROPIETARIO);
        inquilino = persona("20222222223", TipoPersonaEnum.INQUILINO);
        garante = persona("20333333334", TipoPersonaEnum.GARANTE);

        propiedad = Propiedad.builder()
                .codigo("PROP-TEST0001")
                .tipologia(TipologiaEnum.DEPARTAMENTO)
                .calle("Obispo Trejo")
                .numero("1200")
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal("55.00"))
                .propietario(propietario)
                .build();
    }

    // --- Transiciones válidas ---

    @Test
    void debeNacerDisponible() {
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
        assertTrue(propiedad.estaDisponible());
    }

    @Test
    void debeReservarDesdeDisponibleAsignandoInquilinoYGarante() {
        propiedad.reservar(inquilino, garante);

        assertEquals(EstadoPropiedadEnum.RESERVADA, propiedad.getEstado());
        assertFalse(propiedad.estaDisponible());
        assertSame(inquilino, propiedad.getInquilino());
        assertSame(garante, propiedad.getGarante());
    }

    @Test
    void debeAlquilarDesdeReservadaConInquilino() {
        propiedad.reservar(inquilino, garante);

        propiedad.alquilar();

        assertEquals(EstadoPropiedadEnum.ALQUILADA, propiedad.getEstado());
    }

    @Test
    void debeLiberarDesdeReservadaLimpiandoInquilinoYGarante() {
        propiedad.reservar(inquilino, garante);

        propiedad.liberar();

        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
        assertNull(propiedad.getInquilino());
        assertNull(propiedad.getGarante());
    }

    @Test
    void debeLiberarDesdeAlquiladaLimpiandoInquilinoYGarante() {
        propiedad.reservar(inquilino, garante);
        propiedad.alquilar();

        propiedad.liberar();

        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
        assertNull(propiedad.getInquilino());
        assertNull(propiedad.getGarante());
    }

    @Test
    void debeInactivarYReactivar() {
        propiedad.inactivar();
        assertEquals(EstadoPropiedadEnum.INACTIVA, propiedad.getEstado());

        propiedad.reactivar();
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
    }

    // --- Transiciones inválidas ---

    @Test
    void noDebeAlquilarDesdeDisponibleSalteandoLaReserva() {
        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class, propiedad::alquilar);

        assertTrue(ex.getMessage().contains("PROP-TEST0001"));
        assertTrue(ex.getMessage().contains("DISPONIBLE"));
        assertEquals(EstadoPropiedadEnum.DISPONIBLE, propiedad.getEstado());
    }

    @Test
    void noDebeAlquilarReservaSinInquilino() {
        propiedad.reservar(null, null);

        assertThrows(OperacionInvalidaException.class, propiedad::alquilar);
        assertEquals(EstadoPropiedadEnum.RESERVADA, propiedad.getEstado());
    }

    @Test
    void noDebeReservarUnaPropiedadYaReservada() {
        propiedad.reservar(inquilino, garante);

        assertThrows(OperacionInvalidaException.class, () -> propiedad.reservar(inquilino, garante));
    }

    @Test
    void noDebeReservarUnaPropiedadAlquilada() {
        propiedad.reservar(inquilino, garante);
        propiedad.alquilar();

        assertThrows(OperacionInvalidaException.class, () -> propiedad.reservar(inquilino, garante));
        assertEquals(EstadoPropiedadEnum.ALQUILADA, propiedad.getEstado());
    }

    @Test
    void noDebeLiberarUnaPropiedadDisponible() {
        assertThrows(OperacionInvalidaException.class, propiedad::liberar);
    }

    @Test
    void noDebeOperarSobreUnaPropiedadInactiva() {
        propiedad.inactivar();

        assertThrows(OperacionInvalidaException.class, () -> propiedad.reservar(inquilino, garante));
        assertThrows(OperacionInvalidaException.class, propiedad::alquilar);
        assertThrows(OperacionInvalidaException.class, propiedad::liberar);
        assertThrows(OperacionInvalidaException.class, propiedad::inactivar);
        assertEquals(EstadoPropiedadEnum.INACTIVA, propiedad.getEstado());
    }

    @Test
    void noDebeInactivarUnaPropiedadReservada() {
        propiedad.reservar(inquilino, garante);

        assertThrows(OperacionInvalidaException.class, propiedad::inactivar);
    }

    @Test
    void noDebeReactivarUnaPropiedadDisponible() {
        assertThrows(OperacionInvalidaException.class, propiedad::reactivar);
    }

    // --- Código autogenerado ---

    @Test
    void debeGenerarCodigoAlPersistirSiNoVieneInformado() {
        Propiedad sinCodigo = Propiedad.builder()
                .tipologia(TipologiaEnum.CASA)
                .calle("Av. Colón")
                .numero("500")
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal("120.00"))
                .build();

        sinCodigo.generarCodigoSiFalta();

        assertTrue(sinCodigo.getCodigo().matches("PROP-[0-9A-F]{8}"), sinCodigo.getCodigo());
    }

    @Test
    void noDebePisarCodigoInformado() {
        propiedad.generarCodigoSiFalta();

        assertEquals("PROP-TEST0001", propiedad.getCodigo());
    }

    // --- Superficie computable ---

    @Test
    void superficieComputableSinCubiertaEsLaTotal() {
        assertEquals(new BigDecimal("55.00"), propiedad.superficieComputableM2());
    }

    @Test
    void superficieComputablePonderaLoDescubiertoAl50PorCiento() {
        // 80 cubiertos + (100 - 80) × 0,5 = 90,00
        Propiedad conPatio = propiedadConSuperficies("100", "80");

        assertEquals(new BigDecimal("90.00"), conPatio.superficieComputableM2());
    }

    @Test
    void superficieComputableRedondeaHalfUpAEscala2() {
        // 50,01 + (75,50 - 50,01) × 0,5 = 50,01 + 12,745 = 62,755 -> 62,76
        Propiedad conBalcon = propiedadConSuperficies("75.50", "50.01");

        assertEquals(new BigDecimal("62.76"), conBalcon.superficieComputableM2());
    }

    @Test
    void noDebeCrearseConCubiertaMayorQueLaTotal() {
        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> propiedadConSuperficies("50", "60"));

        assertTrue(ex.getMessage().contains("superficie cubierta"));
    }

    // --- Normalización y tasación ---

    @Test
    void aplicarNormalizacionReemplazaProvinciaYCiudadPorLosNombresOficiales() {
        Propiedad cruda = Propiedad.builder()
                .tipologia(TipologiaEnum.DEPARTAMENTO)
                .calle("independencia")
                .numero("900")
                .ciudad("cordoba")
                .provincia("cba")
                .superficieTotalM2(new BigDecimal("40"))
                .build();

        cruda.aplicarNormalizacion("INDEPENDENCIA 900, Capital, Córdoba", "Córdoba", "Córdoba",
                -31.4266835418118, -64.1885105207543);

        assertEquals("INDEPENDENCIA 900, Capital, Córdoba", cruda.getDireccionNormalizada());
        assertEquals("Córdoba", cruda.getProvincia());
        assertEquals("Córdoba", cruda.getCiudad());
        assertEquals(-31.4266835418118, cruda.getLatitud());
        assertEquals(-64.1885105207543, cruda.getLongitud());
    }

    @Test
    void aplicarNormalizacionExigeLosDatosOficiales() {
        assertThrows(IllegalArgumentException.class,
                () -> propiedad.aplicarNormalizacion(" ", "Córdoba", "Córdoba", -31.4, -64.1));
        assertThrows(IllegalArgumentException.class,
                () -> propiedad.aplicarNormalizacion("CALLE 1", null, "Córdoba", -31.4, -64.1));
        assertThrows(IllegalArgumentException.class,
                () -> propiedad.aplicarNormalizacion("CALLE 1", "Córdoba", "", -31.4, -64.1));
    }

    @Test
    void aplicarTasacionGuardaZonaYPrecios() {
        PrecioReferenciaZona zona = PrecioReferenciaZona.builder()
                .nombreZona("Nueva Córdoba")
                .provincia("Córdoba")
                .ciudad("Córdoba")
                .valorM2(new BigDecimal("14000"))
                .puntajeZona(9)
                .fechaActualizacion(LocalDate.now())
                .build();

        propiedad.aplicarTasacion(zona, new BigDecimal("14000.00"), new BigDecimal("770000.00"));

        assertSame(zona, propiedad.getZonaReferencia());
        assertEquals(new BigDecimal("14000.00"), propiedad.getPrecioSugeridoM2());
        assertEquals(new BigDecimal("770000.00"), propiedad.getCanonBaseSugerido());
    }

    @Test
    void aplicarTasacionAceptaSinCobertura() {
        propiedad.aplicarTasacion(null, null, null);

        assertNull(propiedad.getZonaReferencia());
        assertNull(propiedad.getPrecioSugeridoM2());
        assertNull(propiedad.getCanonBaseSugerido());
    }

    private Propiedad propiedadConSuperficies(String total, String cubierta) {
        return Propiedad.builder()
                .tipologia(TipologiaEnum.CASA)
                .calle("Av. Colón")
                .numero("500")
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal(total))
                .superficieCubiertaM2(new BigDecimal(cubierta))
                .build();
    }

    private Persona persona(String cuit, TipoPersonaEnum rol) {
        return Persona.builder()
                .cuitCuil(cuit)
                .nombre("Nombre " + cuit)
                .apellido("Apellido")
                .roles(Set.of(rol))
                .build();
    }
}
