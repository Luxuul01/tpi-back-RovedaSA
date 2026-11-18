package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.IndiceCache;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repositorio del caché de índices contra H2 en modo PostgreSQL (Apunte 07: @DataJpaTest), para que
 * el INSERT ... ON CONFLICT DO NOTHING nativo se ejecute de verdad.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:indices_cache_repo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IndiceCacheRepositoryTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Autowired
    private IndiceCacheRepository repository;

    private void guardar(String fecha, String valor) {
        repository.save(IndiceCache.builder().tipoIndice(TipoIndiceEnum.ICL).fecha(LocalDate.parse(fecha))
                .valor(new BigDecimal(valor)).fechaConsulta(AHORA).build());
    }

    @Test
    @DisplayName("insertarSiNoExiste: la segunda vez no inserta ni falla por la clave única (tipo_indice, fecha)")
    void insertarEsIdempotente() {
        LocalDate fecha = LocalDate.of(2026, 10, 1);

        int primera = repository.insertarSiNoExiste("ICL", fecha, new BigDecimal("12.50"), AHORA);
        int segunda = repository.insertarSiNoExiste("ICL", fecha, new BigDecimal("99.00"), AHORA);

        assertAll(
                () -> assertEquals(1, primera),
                () -> assertEquals(0, segunda),
                () -> assertEquals(1, repository.count()),
                () -> assertEquals(0, new BigDecimal("12.50").compareTo(
                        repository.findByTipoIndiceAndFecha(TipoIndiceEnum.ICL, fecha).orElseThrow().getValor())));
    }

    @Test
    @DisplayName("La misma fecha con otro índice es otra fila")
    void mismaFechaOtroIndice() {
        LocalDate fecha = LocalDate.of(2026, 10, 1);
        repository.insertarSiNoExiste("ICL", fecha, new BigDecimal("12.50"), AHORA);
        assertEquals(1, repository.insertarSiNoExiste("IPC", fecha, new BigDecimal("2.0"), AHORA));
    }

    @Test
    @DisplayName("actualizarValor cambia el valor existente y devuelve 0 si no existe")
    void actualizarValor() {
        guardar("2026-10-01", "12.50");
        LocalDate fecha = LocalDate.of(2026, 10, 1);

        int existente = repository.actualizarValor(TipoIndiceEnum.ICL, fecha, new BigDecimal("12.60"), AHORA);
        int inexistente = repository.actualizarValor(TipoIndiceEnum.ICL, fecha.plusDays(1), BigDecimal.ONE, AHORA);

        assertAll(
                () -> assertEquals(1, existente),
                () -> assertEquals(0, inexistente),
                () -> assertEquals(0, new BigDecimal("12.60").compareTo(
                        repository.findByTipoIndiceAndFecha(TipoIndiceEnum.ICL, fecha).orElseThrow().getValor())));
    }

    @Test
    @DisplayName("buscarUltimoEntre: el último hasta la fecha, pero nunca uno anterior al mínimo")
    void buscarUltimoEntreRespetaElMinimo() {
        guardar("2026-09-16", "36.27");
        guardar("2026-10-24", "37.05");
        guardar("2026-10-27", "37.11");

        List<IndiceCache> dentro = repository.buscarUltimoEntre(TipoIndiceEnum.ICL,
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), PageRequest.of(0, 1));
        List<IndiceCache> fuera = repository.buscarUltimoEntre(TipoIndiceEnum.ICL,
                LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 16), PageRequest.of(0, 1));

        assertAll(
                () -> assertEquals(1, dentro.size()),
                () -> assertEquals(LocalDate.of(2026, 10, 24), dentro.get(0).getFecha()),
                () -> assertTrue(fuera.isEmpty(), "el valor del 16/09 tiene más de 7 días: no sirve"));
    }
}
