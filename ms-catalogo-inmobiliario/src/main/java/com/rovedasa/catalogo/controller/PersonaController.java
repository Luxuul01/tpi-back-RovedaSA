package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.dto.AgregarRolRequest;
import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.service.EvaluacionGaranteService;
import com.rovedasa.catalogo.service.PersonaService;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

@Tag(name = "Personas", description = "Propietarios, inquilinos y garantes, y evaluación de solvencia en el BCRA")
@RestController
@RequestMapping("/api/personas")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;
    private final EvaluacionGaranteService evaluacionGaranteService;

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

    // F-08. Solo ADMINISTRADOR (regla por URL de POST y @PreAuthorize de la clase). Lo usa también
    // ms-contratos con su cuenta de servicio, que tiene el rol ADMINISTRADOR.
    @Operation(summary = "Evaluar la solvencia del garante en el BCRA y registrar el resultado",
            description = "Consulta la Central de Deudores del BCRA por el CUIT de la persona y guarda la situación "
                    + "crediticia, los cheques rechazados y la fecha de la consulta. Es solvente con situación 1 o 2 "
                    + "y 0 cheques rechazados (RN-07). La persona tiene que tener el rol GARANTE. Sin body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evaluación realizada y registrada (solvente o no)"),
            @ApiResponse(responseCode = "400", description = "La persona no tiene el rol GARANTE",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Sin token o con un token inválido",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "El rol no es ADMINISTRADOR",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No existe la persona",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "La Central de Deudores del BCRA no está disponible; "
                    + "no se guarda nada y el garante no se considera solvente",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/evaluacion-solvencia")
    public ValidacionGaranteDTO evaluarSolvencia(@PathVariable("id") Long id) {
        return evaluacionGaranteService.evaluar(id);
    }
}
