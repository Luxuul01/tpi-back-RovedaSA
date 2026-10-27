package com.rovedasa.catalogo.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PropiedadMapperTest {

    private Propiedad propiedad;

    @BeforeEach
    void prepararPropiedadReservada() {
        Persona propietario = persona(1L, "Juan", TipoPersonaEnum.PROPIETARIO);
        Persona inquilino = persona(2L, "Lucía", TipoPersonaEnum.INQUILINO);
        Persona garante = persona(3L, "Marcos", TipoPersonaEnum.GARANTE);
        PrecioReferenciaZona zona = PrecioReferenciaZona.builder()
                .nombreZona("Nueva Córdoba")
                .provincia("Córdoba")
                .ciudad("Córdoba")
                .valorM2(new BigDecimal("14000"))
                .puntajeZona(9)
                .fechaActualizacion(LocalDate.now())
                .build();

        propiedad = Propiedad.builder()
                .codigo("PROP-1A2B3C4D")
                .tipologia(TipologiaEnum.DEPARTAMENTO)
                .calle("Independencia")
                .numero("900")
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal("55.00"))
                .superficieCubiertaM2(new BigDecimal("50.00"))
                .propietario(propietario)
                .build();
        ReflectionTestUtils.setField(propiedad, "id", 10L);
        propiedad.aplicarNormalizacion("INDEPENDENCIA 900, Capital, Córdoba", "Córdoba", "Córdoba",
                -31.4266835418118, -64.1885105207543);
        propiedad.aplicarTasacion(zona, new BigDecimal("14000.00"), new BigDecimal("735000.00"));
        propiedad.reservar(inquilino, garante);
    }

    @Test
    void propiedadResponseTieneTodosLosCamposDePropiedadDTOConElMismoTipo() {
        Map<String, Class<?>> componentes = Arrays.stream(PropiedadResponse.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));

        for (Field campo : PropiedadDTO.class.getDeclaredFields()) {
            if (Modifier.isStatic(campo.getModifiers())) {
                continue;
            }
            assertEquals(campo.getType(), componentes.get(campo.getName()),
                    "PropiedadResponse debe tener el campo " + campo.getName() + " de tipo " + campo.getType());
        }
    }

    @Test
    void aPropiedadDTOCopiaLosValoresCorrectos() {
        PropiedadDTO dto = PropiedadMapper.aPropiedadDTO(propiedad);

        assertEquals(10L, dto.getId());
        assertEquals("PROP-1A2B3C4D", dto.getCodigo());
        assertEquals("INDEPENDENCIA 900, Capital, Córdoba", dto.getDireccionNormalizada());
        assertEquals(new BigDecimal("55.00"), dto.getSuperficieTotalM2());
        assertEquals(new BigDecimal("14000.00"), dto.getPrecioSugeridoM2());
        assertEquals(new BigDecimal("735000.00"), dto.getCanonBaseSugerido());
        assertEquals(1L, dto.getPropietarioId());
        assertEquals(2L, dto.getInquilinoId());
        assertEquals(EstadoPropiedadEnum.RESERVADA, dto.getEstado());
    }

    @Test
    void elJsonDePropiedadResponseSeDeserializaComoPropiedadDTO() throws Exception {
        // Simula a ms-contratos leyendo GET /api/propiedades/{id} con su PropiedadDTO
        ObjectMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        String json = mapper.writeValueAsString(PropiedadMapper.aResponse(propiedad));

        PropiedadDTO leido = mapper.readValue(json, PropiedadDTO.class);

        assertEquals(PropiedadMapper.aPropiedadDTO(propiedad), leido);
    }

    @Test
    void aResponseCompletaLosCamposPropiosDelCatalogo() {
        PropiedadResponse respuesta = PropiedadMapper.aResponse(propiedad);

        assertEquals(3L, respuesta.garanteId());
        assertEquals("Juan Pérez", respuesta.propietarioNombreCompleto());
        assertEquals("Nueva Córdoba", respuesta.zonaNombre());
        assertEquals(9, respuesta.zonaPuntaje());
        assertEquals(new BigDecimal("52.50"), respuesta.superficieComputableM2());
        assertEquals(TipologiaEnum.DEPARTAMENTO, respuesta.tipologia());
        assertNotNull(respuesta.latitud());
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
