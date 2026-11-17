package com.rovedasa.contratos.demo;

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
 * Solo existe con el perfil "demo". Prende y apaga la caída simulada de la API Estadísticas
 * del BCRA para la colección Postman (carpeta 06 Integraciones y contingencia).
 */
@Slf4j
@Tag(name = "Demo", description = "Simulación del BCRA (solo perfil demo)")
@RestController
@Profile("demo")
@RequestMapping("/api/demo/contratos/simulacion")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class DemoSimulacionController {

    private final SimuladorBcraEstadisticas simulador;

    @Operation(summary = "Estado de la simulación de la API Estadísticas del BCRA")
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> estado() {
        return ResponseEntity.ok(Map.of("bcraCaido", simulador.isCaido()));
    }

    @Operation(summary = "Simular la caída (true) o la recuperación (false) de la API Estadísticas del BCRA")
    @PutMapping
    public ResponseEntity<Map<String, Boolean>> cambiar(@RequestParam(name = "bcraCaido") boolean bcraCaido) {
        simulador.setCaido(bcraCaido);
        log.warn("Simulación BCRA Estadísticas: caído={}", bcraCaido);
        return ResponseEntity.ok(Map.of("bcraCaido", bcraCaido));
    }
}
