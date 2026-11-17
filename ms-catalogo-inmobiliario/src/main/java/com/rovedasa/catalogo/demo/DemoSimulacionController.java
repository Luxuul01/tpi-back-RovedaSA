package com.rovedasa.catalogo.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Solo existe con el perfil "demo". Prende y apaga la caída simulada de los servicios externos
 * del catálogo para la colección Postman (carpeta 06 Integraciones y contingencia).
 */
@Slf4j
@Tag(name = "Demo", description = "Simulación de servicios externos (solo perfil demo)")
@RestController
@Profile("demo")
@RequestMapping("/api/demo/catalogo/simulacion")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class DemoSimulacionController {

    private final SimuladorServiciosExternos simulador;

    @Operation(summary = "Estado de la simulación (BCRA Central de Deudores y GeoRef)")
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> estado() {
        return ResponseEntity.ok(estadoActual());
    }

    @Operation(summary = "Simular la caída (true) o la recuperación (false) de BCRA y GeoRef")
    @PutMapping
    public ResponseEntity<Map<String, Boolean>> cambiar(
            @RequestParam(name = "bcraCaido", required = false) Boolean bcraCaido,
            @RequestParam(name = "georefCaido", required = false) Boolean georefCaido) {
        if (bcraCaido != null) {
            simulador.setBcraCaido(bcraCaido);
        }
        if (georefCaido != null) {
            simulador.setGeorefCaido(georefCaido);
        }
        log.warn("Simulación de servicios externos del catálogo: {}", estadoActual());
        return ResponseEntity.ok(estadoActual());
    }

    private Map<String, Boolean> estadoActual() {
        return Map.of("bcraCaido", simulador.isBcraCaido(), "georefCaido", simulador.isGeorefCaido());
    }
}
