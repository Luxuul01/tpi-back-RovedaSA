package com.rovedasa.contratos.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validación (Bean Validation) de los DTOs de alta y seña de contrato, sin levantar contexto.
 * Los límites siguen las columnas reales: montos NUMERIC(15,2) y % de mora diaria NUMERIC(7,4).
 */
class ValidacionRequestsContratoTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void iniciar() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void cerrar() {
        factory.close();
    }

    // ------------------------------------------------------------------
    // CrearContratoRequest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un alta con valores dentro de las columnas es válida (incluye los máximos)")
    void altaValidaNoTieneErrores() {
        assertTrue(validator.validate(altaValida().build()).isEmpty());
        assertTrue(validator.validate(altaValida()
                .montoInicial(new BigDecimal("9999999999999.99"))
                .porcentajeMoraDiaria(new BigDecimal("100.0000"))
                .build()).isEmpty());
        assertTrue(validator.validate(altaValida()
                .porcentajeMoraDiaria(new BigDecimal("0.0001"))
                .build()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e15", "10000000000000", "10000000000000.00", "500000.001"})
    @DisplayName("montoInicial fuera de NUMERIC(15,2) da error de validación (antes: 500 numeric field overflow)")
    void montoInicialFueraDeRangoEsInvalido(String monto) {
        CrearContratoRequest request = altaValida().montoInicial(new BigDecimal(monto)).build();

        assertEquals(Set.of("El monto inicial admite como máximo 13 dígitos enteros y 2 decimales"),
                mensajesDe(validator.validate(request), "montoInicial"));
    }

    @Test
    @DisplayName("porcentajeMoraDiaria mayor a 100 da error de validación (antes: 500 numeric field overflow)")
    void porcentajeMoraMayorACienEsInvalido() {
        assertTrue(mensajesDe(validator.validate(altaValida().porcentajeMoraDiaria(new BigDecimal("1000")).build()),
                "porcentajeMoraDiaria")
                .contains("El porcentaje de mora diaria no puede superar el 100 %"));
        assertEquals(Set.of("El porcentaje de mora diaria no puede superar el 100 %"),
                mensajesDe(validator.validate(altaValida().porcentajeMoraDiaria(new BigDecimal("100.0001")).build()),
                        "porcentajeMoraDiaria"));
    }

    @Test
    @DisplayName("porcentajeMoraDiaria con más de 4 decimales da error de validación")
    void porcentajeMoraConMasDeCuatroDecimalesEsInvalido() {
        assertEquals(Set.of("El porcentaje de mora diaria admite como máximo 3 dígitos enteros y 4 decimales"),
                mensajesDe(validator.validate(altaValida().porcentajeMoraDiaria(new BigDecimal("0.12345")).build()),
                        "porcentajeMoraDiaria"));
    }

    @Test
    @DisplayName("Las validaciones previas (obligatorio, positivo, no negativo) se mantienen")
    void validacionesPreviasSeMantienen() {
        assertEquals(Set.of("El monto inicial debe ser mayor a cero"),
                mensajesDe(validator.validate(altaValida().montoInicial(BigDecimal.ZERO).build()), "montoInicial"));
        assertEquals(Set.of("El porcentaje de mora diaria no puede ser negativo"),
                mensajesDe(validator.validate(altaValida().porcentajeMoraDiaria(new BigDecimal("-0.1")).build()),
                        "porcentajeMoraDiaria"));
        assertEquals(Set.of("El monto inicial es obligatorio"),
                mensajesDe(validator.validate(altaValida().montoInicial(null).build()), "montoInicial"));
    }

    // ------------------------------------------------------------------
    // SeniarContratoRequest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Una seña con monto dentro de NUMERIC(15,2) es válida")
    void seniaValidaNoTieneErrores() {
        assertTrue(validator.validate(senia("150000.00")).isEmpty());
        assertTrue(validator.validate(senia("9999999999999.99")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e14", "10000000000000", "150000.005"})
    @DisplayName("montoSenia fuera de NUMERIC(15,2) da error de validación (antes: 400 con mensaje equivocado)")
    void montoSeniaFueraDeRangoEsInvalido(String monto) {
        assertEquals(Set.of("El monto de la seña admite como máximo 13 dígitos enteros y 2 decimales"),
                mensajesDe(validator.validate(senia(monto)), "montoSenia"));
    }

    // ------------------------------------------------------------------
    // AplicarAjusteRequest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Las fechas del ajuste no se validan por campo: un hito futuro lo rechaza el State con su mensaje")
    void ajusteConFechasFuturasNoTieneErroresDeCampo() {
        AplicarAjusteRequest pedido = AplicarAjusteRequest.builder()
                .fechaIndiceDesde(LocalDate.now())
                .fechaIndiceHasta(LocalDate.now().plusMonths(3))
                .build();
        assertTrue(validator.validate(pedido).isEmpty());
        assertTrue(validator.validate(new AplicarAjusteRequest()).isEmpty());
    }

    private static CrearContratoRequest.CrearContratoRequestBuilder altaValida() {
        return CrearContratoRequest.builder()
                .propiedadId(1L)
                .propietarioId(2L)
                .inquilinoId(3L)
                .garanteId(4L)
                .fechaInicio(LocalDate.now())
                .fechaFin(LocalDate.now().plusYears(2))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10);
    }

    private static SeniarContratoRequest senia(String monto) {
        return SeniarContratoRequest.builder()
                .montoSenia(new BigDecimal(monto))
                .fechaLimiteSenia(LocalDate.now().plusDays(5))
                .build();
    }

    private static <T> Set<String> mensajesDe(Set<ConstraintViolation<T>> violaciones, String campo) {
        Set<String> campos = violaciones.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of(campo), campos, "Solo debería fallar el campo " + campo);
        return violaciones.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
