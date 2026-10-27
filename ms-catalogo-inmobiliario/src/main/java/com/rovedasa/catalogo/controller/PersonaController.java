package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.dto.AgregarRolRequest;
import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.service.PersonaService;
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

@Tag(name = "Personas", description = "Propietarios, inquilinos y garantes")
@RestController
@RequestMapping("/api/personas")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;

    @Operation(summary = "Registrar una persona con sus roles de negocio")
    @PostMapping
    public ResponseEntity<PersonaResponse> registrar(@Valid @RequestBody CrearPersonaRequest solicitud) {
        PersonaResponse creada = personaService.registrar(solicitud);
        URI ubicacion = UriComponentsBuilder.fromPath("/api/personas/{id}").buildAndExpand(creada.id()).toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    @Operation(summary = "Obtener una persona por id")
    @GetMapping("/{id}")
    public PersonaResponse obtener(@PathVariable("id") Long id) {
        return personaService.obtener(id);
    }

    // PagedModel: JSON estable {content, page}; serializar Page (PageImpl) directo no está soportado por Spring Data
    @Operation(summary = "Listar personas paginadas, opcionalmente filtradas por rol")
    @GetMapping
    public PagedModel<PersonaResponse> listar(
            @RequestParam(name = "rol", required = false) TipoPersonaEnum rol,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable paginacion) {
        return new PagedModel<>(personaService.listar(rol, paginacion));
    }

    @Operation(summary = "Agregar un rol de negocio a una persona (idempotente)")
    @PatchMapping("/{id}/roles")
    public PersonaResponse agregarRol(@PathVariable("id") Long id, @Valid @RequestBody AgregarRolRequest solicitud) {
        return personaService.agregarRol(id, solicitud.rol());
    }
}
