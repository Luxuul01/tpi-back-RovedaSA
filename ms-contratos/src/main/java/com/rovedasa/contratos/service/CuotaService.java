package com.rovedasa.contratos.service;

import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.repository.CuotaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de cuotas: consulta con mora calculada al día y registro de pago. La anulación de cuotas la hace
 * el State del contrato (EstadoActivo) al rescindir o finalizar.
 * La mora no se persiste; se calcula en cada consulta sobre la fecha de referencia
 * (para una cuota PAGADA, como mucho hasta su fecha de pago).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CuotaService {

    private final CuotaRepository cuotaRepository;
    private final Clock clock;

    /**
     * Registra el pago de una cuota pendiente (RN-18): PENDIENTE -> PAGADA con fecha_pago.
     * La fecha de pago no puede ser futura ni anterior al inicio del contrato; si no se
     * informa, se usa la fecha de hoy. La mora queda fija hasta la fecha de pago.
     * Se usa saveAndFlush para que un pago concurrente de la misma cuota (@Version)
     * falle dentro de esta operación con ObjectOptimisticLockingFailureException (409).
     */
    @Transactional
    public CuotaResumenDTO pagarCuota(Long cuotaId, LocalDate fechaPago) {
        log.info("Registrando pago de cuota {} en fecha {}", cuotaId, fechaPago);

        Cuota cuota = buscarCuota(cuotaId);

        if (cuota.getEstado() == EstadoCuotaEnum.PAGADA) {
            throw new OperacionInvalidaException(
                    "La cuota " + cuotaId + " ya fue pagada el " + cuota.getFechaPago());
        }
        if (cuota.getEstado() == EstadoCuotaEnum.ANULADA) {
            throw new OperacionInvalidaException("La cuota " + cuotaId + " está anulada, no se puede pagar");
        }

        LocalDate hoy = LocalDate.now(clock);
        LocalDate fechaEfectiva = fechaPago != null ? fechaPago : hoy;
        validarFechaPago(cuota, fechaEfectiva, hoy);

        cuota.registrarPago(fechaEfectiva);
        cuota = cuotaRepository.saveAndFlush(cuota);

        log.info("Cuota {} marcada como PAGADA en fecha {}", cuotaId, cuota.getFechaPago());
        return toResumen(cuota, cuota.getContrato().getPorcentajeMoraDiaria(), hoy);
    }

    /**
     * Obtiene las cuotas de un contrato con mora calculada dinámicamente a la fecha de referencia.
     * La pertenencia del contrato la valida quien llama (GET /api/contratos/{id}/cuotas).
     */
    @Transactional(readOnly = true)
    public List<CuotaResumenDTO> listarCuotasConMora(Long contratoId, BigDecimal porcentajeMoraDiaria, LocalDate fechaReferencia) {
        List<Cuota> cuotas = cuotaRepository.findByContratoIdOrderByNroCuotaAsc(contratoId);
        return toResumenList(cuotas, porcentajeMoraDiaria, fechaReferencia);
    }

    /**
     * Obtiene una cuota con mora validando que pertenezca al usuario (RN-17, anti-IDOR):
     * el ADMINISTRADOR ve todas; PROPIETARIO e INQUILINO solo las de sus contratos.
     * Una cuota ajena responde igual que una inexistente (404), sin revelar su contrato.
     */
    @Transactional(readOnly = true)
    public CuotaResumenDTO obtenerCuotaConMora(Long cuotaId, LocalDate fechaReferencia, String rol, String usuarioKeycloakId) {
        Cuota cuota = buscarCuota(cuotaId);
        Contrato contrato = cuota.getContrato();
        if (!pertenece(contrato, rol, usuarioKeycloakId)) {
            log.warn("Acceso a cuota {} denegado para rol={}, sub={} (recurso ajeno)", cuotaId, rol, usuarioKeycloakId);
            throw cuotaNoEncontrada(cuotaId);
        }
        return toResumen(cuota, contrato.getPorcentajeMoraDiaria(), fechaReferenciaODefault(fechaReferencia));
    }

    /**
     * Convierte una lista de entidades Cuota a DTOs con mora calculada.
     */
    public List<CuotaResumenDTO> toResumenList(List<Cuota> cuotas, BigDecimal porcentajeMoraDiaria, LocalDate fechaReferencia) {
        final LocalDate fechaRef = fechaReferenciaODefault(fechaReferencia);
        return cuotas.stream()
                .map(c -> toResumen(c, porcentajeMoraDiaria, fechaRef))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Fecha de pago: no futura y no anterior a la fecha de inicio del contrato.
     */
    private void validarFechaPago(Cuota cuota, LocalDate fechaPago, LocalDate hoy) {
        if (fechaPago.isAfter(hoy)) {
            throw new OperacionInvalidaException(
                    "La fecha de pago (" + fechaPago + ") no puede ser futura: hoy es " + hoy);
        }
        LocalDate fechaInicio = cuota.getContrato().getFechaInicio();
        if (fechaInicio != null && fechaPago.isBefore(fechaInicio)) {
            throw new OperacionInvalidaException(
                    "La fecha de pago (" + fechaPago + ") no puede ser anterior al inicio del contrato ("
                            + fechaInicio + ")");
        }
    }

    private boolean pertenece(Contrato contrato, String rol, String usuarioKeycloakId) {
        if (rol == null || contrato == null) {
            return false;
        }
        return switch (rol) {
            case "ADMINISTRADOR" -> true;
            case "PROPIETARIO" -> usuarioKeycloakId != null
                    && usuarioKeycloakId.equals(contrato.getPropietarioKeycloakId());
            case "INQUILINO" -> usuarioKeycloakId != null
                    && usuarioKeycloakId.equals(contrato.getInquilinoKeycloakId());
            default -> false;
        };
    }

    private Cuota buscarCuota(Long cuotaId) {
        return cuotaRepository.findById(cuotaId)
                .orElseThrow(() -> cuotaNoEncontrada(cuotaId));
    }

    private RecursoNoEncontradoException cuotaNoEncontrada(Long cuotaId) {
        return new RecursoNoEncontradoException("Cuota con ID " + cuotaId + " no fue encontrada");
    }

    private LocalDate fechaReferenciaODefault(LocalDate fechaReferencia) {
        return fechaReferencia != null ? fechaReferencia : LocalDate.now(clock);
    }

    /**
     * Convierte una entidad Cuota a CuotaResumenDTO calculando la mora dinámica.
     * PENDIENTE: mora hasta la fecha de referencia. PAGADA: mora hasta la fecha de pago
     * (fija; 0 si se pagó en término). ANULADA: sin mora (RN-13, RN-18 y C-06).
     */
    private CuotaResumenDTO toResumen(Cuota cuota, BigDecimal porcentajeMoraDiaria, LocalDate fechaReferencia) {
        BigDecimal montoMora = cuota.calcularMora(fechaReferencia, porcentajeMoraDiaria);
        BigDecimal montoTotal = cuota.calcularTotalConMora(fechaReferencia, porcentajeMoraDiaria);

        return CuotaResumenDTO.builder()
                .id(cuota.getId())
                .nroCuota(cuota.getNroCuota())
                .periodoMes(cuota.getPeriodoMes())
                .periodoAnio(cuota.getPeriodoAnio())
                .fechaVencimiento(cuota.getFechaVencimiento())
                .fechaEmision(cuota.getFechaEmision())
                .montoBase(cuota.getMontoBase())
                .montoMora(montoMora)
                .montoTotal(montoTotal)
                .estado(cuota.getEstado())
                .fechaPago(cuota.getFechaPago())
                .build();
    }
}
