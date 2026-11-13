package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Evaluación de solvencia de un garante registrado (F-08): consulta la Central de Deudores del BCRA
 * con SolvenciaGaranteService y guarda el resultado en la persona.
 *
 * Es un servicio aparte de PersonaService porque no es un ABM transaccional: igual que el alta de
 * propiedades con GeoRef, la llamada HTTP al BCRA (dos consultas, cada una con timeout) va fuera de
 * toda transacción, así no se retiene una conexión de la base mientras tanto. Se usan dos
 * transacciones cortas con TransactionTemplate:
 *   1. Lectura: buscar la persona y validar que tenga el rol GARANTE (los roles son LAZY).
 *   2. Escritura: registrar la evaluación. Se vuelve a buscar la persona para modificar una entidad
 *      administrada (la persona no tiene @Version: un merge de la copia leída en el paso 1 pisaría
 *      cambios hechos mientras tanto en otros campos).
 * Si el BCRA no responde, BcraNoDisponibleException (503) corta antes del paso 2 y no se guarda nada.
 */
@Slf4j
@Service
public class EvaluacionGaranteService {

    private final PersonaRepository personaRepository;
    private final SolvenciaGaranteService solvenciaGaranteService;
    private final Clock reloj;
    private final TransactionTemplate transaccionLectura;
    private final TransactionTemplate transaccionEscritura;

    public EvaluacionGaranteService(PersonaRepository personaRepository,
            SolvenciaGaranteService solvenciaGaranteService, Clock reloj,
            PlatformTransactionManager transactionManager) {
        this.personaRepository = personaRepository;
        this.solvenciaGaranteService = solvenciaGaranteService;
        this.reloj = reloj;
        this.transaccionLectura = new TransactionTemplate(transactionManager);
        this.transaccionLectura.setReadOnly(true);
        this.transaccionEscritura = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws RecursoNoEncontradoException si la persona no existe (404)
     * @throws OperacionInvalidaException si la persona no tiene el rol GARANTE (400, RN-02)
     * @throws com.rovedasa.catalogo.exception.BcraNoDisponibleException si el BCRA no responde (503, RN-07)
     */
    public ValidacionGaranteDTO evaluar(Long personaId) {
        // 1. Transacción corta de lectura
        DatosGarante garante = transaccionLectura.execute(estado -> buscarGarante(personaId));

        // 2. BCRA, fuera de toda transacción. La evaluación es la de RN-07 (situación 1 o 2 y 0 cheques rechazados)
        ValidacionGaranteDTO resultado = solvenciaGaranteService.evaluar(garante.cuitCuil(), garante.ingresos());

        // 3. Transacción corta de escritura
        LocalDateTime fechaConsulta = LocalDateTime.now(reloj);
        transaccionEscritura.executeWithoutResult(estado -> buscar(personaId).registrarEvaluacionBcra(
                resultado.getSituacionCrediticiaBcra(), resultado.getChequesRechazados(), fechaConsulta));

        resultado.setPersonaId(personaId);
        // El BCRA no informa denominación si la persona no registra deudas ni cheques: se usa la del catálogo
        if (resultado.getNombreCompleto() == null) {
            resultado.setNombreCompleto(garante.nombreCompleto());
        }
        // Sin CUIT en el log: es un dato personal
        log.info("Evaluación BCRA registrada para la persona ID {}: solvente={} (situación {}, cheques rechazados {})",
                personaId, resultado.getEsSolvente(), resultado.getSituacionCrediticiaBcra(),
                resultado.getChequesRechazados());
        return resultado;
    }

    private DatosGarante buscarGarante(Long personaId) {
        Persona persona = buscar(personaId);
        if (!persona.tieneRol(TipoPersonaEnum.GARANTE)) {
            throw new OperacionInvalidaException(String.format(
                    "La persona %s (ID %d) no tiene el rol GARANTE; agregáselo antes de evaluar su solvencia",
                    persona.getNombreCompleto(), personaId));
        }
        return new DatosGarante(persona.getCuitCuil(), persona.getIngresosMensualesDeclarados(),
                persona.getNombreCompleto());
    }

    private Persona buscar(Long id) {
        return personaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la persona con ID " + id));
    }

    // Lo que hace falta de la persona fuera de la transacción de lectura (los datos ya copiados, sin entidad LAZY)
    private record DatosGarante(String cuitCuil, BigDecimal ingresos, String nombreCompleto) {
    }
}
