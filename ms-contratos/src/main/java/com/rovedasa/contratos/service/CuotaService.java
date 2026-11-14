package com.rovedasa.contratos.service;

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
 * Servicio de cuotas: cálculo dinámico de mora, registro de pago y anulación.
 * La mora no se persiste; se calcula en cada consulta sobre la fecha de referencia.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CuotaService {

    private final CuotaRepository cuotaRepository;
    private final Clock clock;

    /**
     * Guarda una lista de cuotas (usado tras activar o aplicar ajuste).
     */
    @Transactional
    public void guardarCuotas(List<Cuota> cuotas) {
        if (cuotas == null || cuotas.isEmpty()) {
            return;
        }
        cuotaRepository.saveAllAndFlush(cuotas);
        log.debug("Guardadas {} cuotas", cuotas.size());
    }

    /**
     * Registra el pago de una cuota pendiente: PENDIENTE -> PAGADA con fecha_pago.
     */
    @Transactional
    public CuotaResumenDTO pagarCuota(Long cuotaId, LocalDate fechaPago) {
        log.info("Registrando pago de cuota {} en fecha {}", cuotaId, fechaPago);

        Cuota cuota = cuotaRepository.findById(cuotaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Cuota con ID " + cuotaId + " no fue encontrado"));

        if (cuota.getEstado() == EstadoCuotaEnum.PAGADA) {
            throw new OperacionInvalidaException("La cuota " + cuotaId + " ya fue pagada");
        }
        if (cuota.getEstado() == EstadoCuotaEnum.ANULADA) {
            throw new OperacionInvalidaException("La cuota " + cuotaId + " está anulada, no se puede pagar");
        }

        LocalDate hoy = LocalDate.now(clock);
        if (fechaPago != null && fechaPago.isAfter(hoy)) {
            throw new OperacionInvalidaException("La fecha de pago no puede ser futura: " + fechaPago);
        }

        LocalDate fechaEfectiva = fechaPago != null ? fechaPago : hoy;
        cuota.setEstado(EstadoCuotaEnum.PAGADA);
        cuota.setFechaPago(fechaEfectiva);
        cuota = cuotaRepository.save(cuota);

        BigDecimal porcentajeMora = cuota.getContrato() != null
                ? cuota.getContrato().getPorcentajeMoraDiaria()
                : BigDecimal.ZERO;

        log.info("Cuota {} marcada como PAGADA en fecha {}", cuotaId, cuota.getFechaPago());
        return toResumen(cuota, porcentajeMora, fechaEfectiva);
    }

    /**
     * Anula cuotas futuras de un contrato (usado al rescindir).
     */
    @Transactional
    public void anularCuotasFuturas(Long contratoId, LocalDate fechaCorte) {
        List<Cuota> futuras = cuotaRepository.findByContratoIdAndEstadoAndFechaVencimientoAfter(
                contratoId, EstadoCuotaEnum.PENDIENTE, fechaCorte);

        for (Cuota cuota : futuras) {
            cuota.anular();
        }
        cuotaRepository.saveAll(futuras);
        log.info("Anuladas {} cuotas futuras del contrato {} a partir de {}", futuras.size(), contratoId, fechaCorte);
    }

    /**
     * Obtiene las cuotas de un contrato con mora calculada dinámicamente a la fecha de referencia.
     */
    @Transactional(readOnly = true)
    public List<CuotaResumenDTO> listarCuotasConMora(Long contratoId, BigDecimal porcentajeMoraDiaria, LocalDate fechaReferencia) {
        List<Cuota> cuotas = cuotaRepository.findByContratoIdOrderByNroCuotaAsc(contratoId);
        final LocalDate fechaRef = fechaReferencia != null ? fechaReferencia : LocalDate.now(clock);
        return cuotas.stream()
                .map(c -> toResumen(c, porcentajeMoraDiaria, fechaRef))
                .collect(Collectors.toList());
    }

    /**
     * Obtiene una cuota individual con mora calculada.
     */
    @Transactional(readOnly = true)
    public CuotaResumenDTO obtenerCuotaConMora(Long cuotaId, BigDecimal porcentajeMoraDiaria, LocalDate fechaReferencia) {
        Cuota cuota = cuotaRepository.findById(cuotaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Cuota con ID " + cuotaId + " no fue encontrado"));
        if (fechaReferencia == null) {
            fechaReferencia = LocalDate.now(clock);
        }
        return toResumen(cuota, porcentajeMoraDiaria, fechaReferencia);
    }

    /**
     * Devuelve el ID del contrato al que pertenece la cuota. Se usa para validar
     * la pertenencia del recurso antes de exponerlo a un usuario no administrador.
     */
    @Transactional(readOnly = true)
    public Long obtenerContratoIdDeCuota(Long cuotaId) {
        return cuotaRepository.findContratoIdByCuotaId(cuotaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Cuota con ID " + cuotaId + " no fue encontrado"));
    }

    /**
     * Convierte una entidad Cuota a CuotaResumenDTO calculando la mora dinámica.
     * La mora no se guarda; solo se calcula en el momento de la consulta.
     * Las cuotas PAGADAS o ANULADAS no generan mora (RN-13 y C-06).
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
                .montoBase(cuota.getMontoBase())
                .montoMora(montoMora)
                .montoTotal(montoTotal)
                .estado(cuota.getEstado())
                .fechaPago(cuota.getFechaPago())
                .build();
    }

    /**
     * Convierte una lista de entidades Cuota a DTOs con mora calculada.
     */
    public List<CuotaResumenDTO> toResumenList(List<Cuota> cuotas, BigDecimal porcentajeMoraDiaria, LocalDate fechaReferencia) {
        final LocalDate fechaRef = fechaReferencia != null ? fechaReferencia : LocalDate.now(clock);
        return cuotas.stream()
                .map(c -> toResumen(c, porcentajeMoraDiaria, fechaRef))
                .collect(Collectors.toList());
    }
}
