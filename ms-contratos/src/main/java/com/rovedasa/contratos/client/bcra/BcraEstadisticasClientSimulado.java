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
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * API Estadísticas del BCRA simulada para el perfil "demo" (Alcance, sección 12), con valores fijos
 * para que las comprobaciones den siempre lo mismo:
 *
 *   ICL (variable 40): 10,00 antes del 01/08/2026 y 12,50 desde esa fecha.
 *        Un ajuste del 01/07/2026 al 01/10/2026 da +25 % (C-05: 500.000 -> 625.000).
 *   IPC (variable 27): 2,0 % todos los meses. Igual que la API real, cada mes viene fechado el último
 *        día del mes y se publica a mediados del mes siguiente: el mes M aparece recién cuando el
 *        reloj de la aplicación (Clock) llega al día 15 de M+1. Por ejemplo, el IPC de agosto 2026
 *        (fecha 31/08/2026) está disponible desde el 15/09/2026.
 *
 * Igual que la API real, la serie empieza el 01/07/2020 y viene de la fecha más nueva a la más vieja.
 * El ICL se devuelve también para fechas futuras (el BCRA lo publica por adelantado).
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
    /** Día del mes siguiente en que se publica el IPC de un mes. */
    static final int DIA_PUBLICACION_IPC = 15;

    private final SimuladorBcraEstadisticas simulador;
    private final Clock clock;

    public BcraEstadisticasClientSimulado(SimuladorBcraEstadisticas simulador, Clock clock) {
        super(RestClient.create());
        this.simulador = simulador;
        this.clock = clock;
    }

    @PostConstruct
    void avisar() {
        log.warn("PERFIL DEMO: la API Estadísticas del BCRA está SIMULADA (ICL 10,00 -> 12,50 el 01/08/2026, IPC 2 % mensual publicado el día 15 del mes siguiente)");
    }

    @Override
    public List<BcraValorDTO> obtenerSerie(int idVariable, LocalDate desde, LocalDate hasta) {
        verificarCaida();
        List<BcraValorDTO> valores = new ArrayList<>();
        if (idVariable == TipoIndiceEnum.IPC.getIdVariableBcra()) {
            YearMonth mes = ultimoMesIpcPublicado(hasta);
            while (!mes.atEndOfMonth().isBefore(desde) && !mes.atEndOfMonth().isBefore(INICIO_SERIE)) {
                valores.add(new BcraValorDTO(mes.atEndOfMonth(), IPC_MENSUAL));
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
            LocalDate fecha = ultimoMesIpcPublicado(hasta).atEndOfMonth();
            return fecha.isBefore(INICIO_SERIE) ? List.of() : List.of(new BcraValorDTO(fecha, IPC_MENSUAL));
        }
        return hasta.isBefore(INICIO_SERIE) ? List.of() : List.of(new BcraValorDTO(hasta, icl(hasta)));
    }

    private static BigDecimal icl(LocalDate fecha) {
        return fecha.isBefore(CAMBIO_ICL) ? ICL_ANTES : ICL_DESPUES;
    }

    /**
     * Último mes de IPC que se devuelve para una consulta hasta 'hasta': su fecha (fin de mes) no puede
     * pasar de 'hasta' y tiene que estar publicado según el reloj (día 15 del mes siguiente).
     */
    private YearMonth ultimoMesIpcPublicado(LocalDate hasta) {
        YearMonth mes = YearMonth.from(hasta);
        if (mes.atEndOfMonth().isAfter(hasta)) {
            mes = mes.minusMonths(1);
        }
        LocalDate hoy = LocalDate.now(clock);
        while (hoy.isBefore(mes.plusMonths(1).atDay(DIA_PUBLICACION_IPC))) {
            mes = mes.minusMonths(1);
        }
        return mes;
    }

    private void verificarCaida() {
        if (simulador.isCaido()) {
            throw new ResourceAccessException("API Estadísticas del BCRA simulada como caída (perfil demo)");
        }
    }
}
