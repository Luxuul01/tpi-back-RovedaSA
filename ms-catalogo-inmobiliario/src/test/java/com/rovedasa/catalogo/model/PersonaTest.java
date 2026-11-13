package com.rovedasa.catalogo.model;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonaTest {

    private static final LocalDateTime FECHA_CONSULTA = LocalDateTime.of(2026, 11, 12, 10, 30);

    private Persona garante;

    @BeforeEach
    void prepararGarante() {
        garante = Persona.builder()
                .cuitCuil("20123456786")
                .nombre("Marcos")
                .apellido("Díaz")
                .roles(Set.of(TipoPersonaEnum.GARANTE))
                .build();
    }

    // --- registrarEvaluacionBcra (F-08) ---

    @Test
    void nuncaEvaluadoNoTieneDatosDelBcra() {
        assertNull(garante.getSituacionCrediticiaBcra());
        assertNull(garante.getChequesRechazados());
        assertNull(garante.getFechaConsultaBcra());
    }

    @Test
    void registrarEvaluacionBcraGuardaSituacionChequesYFecha() {
        garante.registrarEvaluacionBcra(1, 0, FECHA_CONSULTA);

        assertEquals(1, garante.getSituacionCrediticiaBcra());
        assertEquals(0, garante.getChequesRechazados());
        assertEquals(FECHA_CONSULTA, garante.getFechaConsultaBcra());
    }

    @Test
    void unaEvaluacionNuevaReemplazaALaAnterior() {
        garante.registrarEvaluacionBcra(1, 0, FECHA_CONSULTA);
        LocalDateTime mesSiguiente = FECHA_CONSULTA.plusMonths(1);

        garante.registrarEvaluacionBcra(3, 2, mesSiguiente);

        assertEquals(3, garante.getSituacionCrediticiaBcra());
        assertEquals(2, garante.getChequesRechazados());
        assertEquals(mesSiguiente, garante.getFechaConsultaBcra());
    }

    @Test
    void aceptaLosExtremosDeLaEscalaDelBcra() {
        garante.registrarEvaluacionBcra(6, 0, FECHA_CONSULTA);

        assertEquals(6, garante.getSituacionCrediticiaBcra());
    }

    @Test
    void rechazaSituacionesFueraDeLaEscalaSinModificarNada() {
        assertThrows(IllegalArgumentException.class, () -> garante.registrarEvaluacionBcra(0, 0, FECHA_CONSULTA));
        assertThrows(IllegalArgumentException.class, () -> garante.registrarEvaluacionBcra(7, 0, FECHA_CONSULTA));

        assertNull(garante.getSituacionCrediticiaBcra());
        assertNull(garante.getFechaConsultaBcra());
    }

    @Test
    void rechazaChequesNegativosYFechaNula() {
        assertThrows(IllegalArgumentException.class, () -> garante.registrarEvaluacionBcra(1, -1, FECHA_CONSULTA));
        assertThrows(NullPointerException.class, () -> garante.registrarEvaluacionBcra(1, 0, null));

        assertNull(garante.getSituacionCrediticiaBcra());
        assertNull(garante.getChequesRechazados());
    }
}
