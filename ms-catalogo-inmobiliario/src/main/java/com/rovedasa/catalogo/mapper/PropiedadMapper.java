package com.rovedasa.catalogo.mapper;

import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.commons.dto.PropiedadDTO;

/**
 * Conversión manual de Propiedad a DTOs. Métodos estáticos: es una función pura, sin dependencias.
 * Usa el propietario y la zona (LAZY): hay que llamarlo dentro de una transacción o con esas
 * relaciones ya cargadas.
 */
public final class PropiedadMapper {

    private PropiedadMapper() {
    }

    public static PropiedadResponse aResponse(Propiedad propiedad) {
        Persona propietario = propiedad.getPropietario();
        PrecioReferenciaZona zona = propiedad.getZonaReferencia();
        return new PropiedadResponse(
                propiedad.getId(),
                propiedad.getCodigo(),
                propiedad.getDireccionNormalizada(),
                propiedad.getSuperficieTotalM2(),
                propiedad.getPrecioSugeridoM2(),
                propiedad.getCanonBaseSugerido(),
                idDe(propietario),
                idDe(propiedad.getInquilino()),
                propiedad.getEstado(),
                propiedad.getTipologia(),
                propiedad.getCalle(),
                propiedad.getNumero(),
                propiedad.getPiso(),
                propiedad.getDepartamento(),
                propiedad.getBarrio(),
                propiedad.getCiudad(),
                propiedad.getProvincia(),
                propiedad.getCodigoPostal(),
                propiedad.getLatitud(),
                propiedad.getLongitud(),
                propiedad.getSuperficieCubiertaM2(),
                propiedad.superficieComputableM2(),
                idDe(propiedad.getGarante()),
                propietario != null ? propietario.getNombreCompleto() : null,
                zona != null ? zona.getNombreZona() : null,
                zona != null ? zona.getPuntajeZona() : null,
                propiedad.getFechaAlta());
    }

    // DTO compartido con ms-contratos (respuesta de PATCH /{id}/estado)
    public static PropiedadDTO aPropiedadDTO(Propiedad propiedad) {
        return PropiedadDTO.builder()
                .id(propiedad.getId())
                .codigo(propiedad.getCodigo())
                .direccionNormalizada(propiedad.getDireccionNormalizada())
                .superficieTotalM2(propiedad.getSuperficieTotalM2())
                .precioSugeridoM2(propiedad.getPrecioSugeridoM2())
                .canonBaseSugerido(propiedad.getCanonBaseSugerido())
                .propietarioId(idDe(propiedad.getPropietario()))
                .inquilinoId(idDe(propiedad.getInquilino()))
                .estado(propiedad.getEstado())
                .build();
    }

    // getId() sobre un proxy LAZY de Hibernate no dispara la carga de la persona
    private static Long idDe(Persona persona) {
        return persona != null ? persona.getId() : null;
    }
}
