package com.rovedasa.contratos.client.bcra;

import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.demo.SimuladorBcraEstadisticas;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * API Estadísticas del BCRA simulada para el perfil "demo" (Alcance, sección 12), con valores fijos
 * para que las comprobaciones den siempre lo mismo:
 *
 *   ICL (variable 40): 10,00 antes del 01/08/2026 y 12,50 desde esa fecha.
 *        Un ajuste del 01/07/2026 al 01/10/2026 da +25 % (C-05: 500.000 -> 625.000).
 *   IPC (variable 27): 2,0 % todos los meses, publicado con un mes de atraso.
 *
 * Igual que la API real, la serie empieza el 01/07/2020 y viene de la fecha más nueva a la más vieja.
 * Con la caída simulada prendida lanza ResourceAccessException, igual que un timeout real,
 * y el servicio usa el caché de contingencia o responde 503 (RN-12).
 */
@Slf4j
@Component
@Primary
@Profile("demo")
public class BcraEstadisticasClientSimulado extends BcraEstadisticasClient {

    static final LocalDate INICIO_SERIE = LocalDate.of(2020, 7, 1);
    static final LocalDate CAMBIO_ICL = LocalDate.of(2026, 8, 1);
    static final BigDecimal ICL_ANTES = new BigDecimal("10.00");
    static final BigDecimal ICL_DESPUES = new BigDecimal("12.50");
    static final BigDecimal IPC_MENSUAL = new BigDecimal("2.0");

    private final SimuladorBcraEstadisticas simulador;

    public BcraEstadisticasClientSimulado(SimuladorBcraEstadisticas simulador) {
        super(RestClient.create());
        this.simulador = simulador;
    }

    @PostConstruct
    void avisar() {
        log.warn("PERFIL DEMO: la API Estadísticas del BCRA está SIMULADA (ICL 10,00 -> 12,50 el 01/08/2026, IPC 2 % mensual)");
    }

    @Override
    public List<BcraValorDTO> obtenerSerie(int idVariable, LocalDate desde, LocalDate hasta) {
        verificarCaida();
        List<BcraValorDTO> valores = new ArrayList<>();
        if (idVariable == TipoIndiceEnum.IPC.getIdVariableBcra()) {
            LocalDate mes = ultimoMesIpcPublicado(hasta);
            while (!mes.isBefore(desde) && !mes.isBefore(INICIO_SERIE)) {
                valores.add(new BcraValorDTO(mes, IPC_MENSUAL));
                mes = mes.minusMonths(1);
            }
        } else {
            for (LocalDate dia = hasta; !dia.isBefore(desde) && !dia.isBefore(INICIO_SERIE); dia = dia.minusDays(1)) {
                valores.add(new BcraValorDTO(dia, icl(dia)));
            }
        }
        return valores;
    }

    @Override
    public List<BcraValorDTO> obtenerUltimoValor(int idVariable, LocalDate hasta) {
        verificarCaida();
        if (idVariable == TipoIndiceEnum.IPC.getIdVariableBcra()) {
            LocalDate mes = ultimoMesIpcPublicado(hasta);
            return mes.isBefore(INICIO_SERIE) ? List.of() : List.of(new BcraValorDTO(mes, IPC_MENSUAL));
        }
        return hasta.isBefore(INICIO_SERIE) ? List.of() : List.of(new BcraValorDTO(hasta, icl(hasta)));
    }

    private static BigDecimal icl(LocalDate fecha) {
        return fecha.isBefore(CAMBIO_ICL) ? ICL_ANTES : ICL_DESPUES;
    }

    /** El IPC de un mes se publica el mes siguiente: el último disponible es el del mes anterior. */
    private static LocalDate ultimoMesIpcPublicado(LocalDate hasta) {
        return hasta.minusMonths(1).withDayOfMonth(1);
    }

    private void verificarCaida() {
        if (simulador.isCaido()) {
            throw new ResourceAccessException("API Estadísticas del BCRA simulada como caída (perfil demo)");
        }
    }
}
