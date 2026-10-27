package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.dto.CrearPropiedadRequest;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.exception.AutenticacionRequeridaException;
import com.rovedasa.catalogo.service.PropiedadService;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Tag(name = "Propiedades", description = "Alta, consulta y cambios de estado de inmuebles")
@RestController
@RequestMapping("/api/propiedades")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class PropiedadController {

    private final PropiedadService propiedadService;

    @Operation(summary = "Registrar una propiedad (normaliza la dirección con GeoRef y calcula el precio sugerido)")
    @PostMapping
    public ResponseEntity<PropiedadResponse> registrar(@Valid @RequestBody CrearPropiedadRequest solicitud) {
        PropiedadResponse creada = propiedadService.registrar(solicitud);
        // Location relativa: detrás del Gateway el host interno no le sirve al cliente
        URI ubicacion = UriComponentsBuilder.fromPath("/api/propiedades/{id}").buildAndExpand(creada.id()).toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    // PagedModel: JSON estable {content, page}; serializar Page (PageImpl) directo no está soportado por Spring Data
    @Operation(summary = "Listar propiedades paginadas, opcionalmente filtradas por estado")
    @GetMapping
    public PagedModel<PropiedadResponse> listar(
            @RequestParam(name = "estado", required = false) EstadoPropiedadEnum estado,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable paginacion) {
        return new PagedModel<>(propiedadService.listar(estado, paginacion));
    }

    @Operation(summary = "Obtener una propiedad por id")
    @GetMapping("/{id}")
    public PropiedadResponse obtener(@PathVariable("id") Long id) {
        return propiedadService.obtener(id);
    }

    // CU-08: la identidad sale del claim sub del JWT validado; nunca se acepta el id del propietario por parámetro
    @Operation(summary = "Listar las propiedades del propietario autenticado")
    @GetMapping("/propias")
    @PreAuthorize("hasRole('PROPIETARIO')")
    public List<PropiedadResponse> listarPropias(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new AutenticacionRequeridaException(
                    "Este endpoint requiere un token JWT; no está disponible en el perfil local");
        }
        return propiedadService.listarPropias(jwt.getSubject());
    }

    @Operation(summary = "Cambiar el estado de una propiedad (reserva, alquiler, liberación, baja o reactivación)")
    @PatchMapping("/{id}/estado")
    public PropiedadDTO actualizarEstado(@PathVariable("id") Long id,
            @Valid @RequestBody ActualizarEstadoPropiedadDTO solicitud) {
        return propiedadService.actualizarEstado(id, solicitud);
    }
}
