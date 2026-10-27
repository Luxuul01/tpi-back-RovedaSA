package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.exception.RecursoDuplicadoException;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonaServiceTest {

    @Mock
    private PersonaRepository personaRepository;

    @InjectMocks
    private PersonaService personaService;

    @Test
    void registrarGuardaElCuitNormalizadoYElEmailEnMinuscula() {
        when(personaRepository.existsByCuitCuil("20123456786")).thenReturn(false);
        when(personaRepository.existsByKeycloakUserId("kc-propietario-1")).thenReturn(false);
        when(personaRepository.existsByEmail("juan.perez@mail.com")).thenReturn(false);
        when(personaRepository.save(any(Persona.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        PersonaResponse respuesta = personaService.registrar(solicitud("20-12345678-6", " kc-propietario-1 ",
                " Juan.Perez@Mail.com "));

        ArgumentCaptor<Persona> guardada = ArgumentCaptor.forClass(Persona.class);
        verify(personaRepository).save(guardada.capture());
        assertEquals("20123456786", guardada.getValue().getCuitCuil());
        assertEquals("juan.perez@mail.com", guardada.getValue().getEmail());
        assertEquals("kc-propietario-1", guardada.getValue().getKeycloakUserId());
        assertEquals("20123456786", respuesta.cuitCuil());
        assertEquals("Juan Pérez", respuesta.nombreCompleto());
        assertEquals(Set.of(TipoPersonaEnum.PROPIETARIO), respuesta.roles());
    }

    @Test
    void registrarConCuitDuplicadoLanzaRecursoDuplicado() {
        when(personaRepository.existsByCuitCuil("20123456786")).thenReturn(true);

        RecursoDuplicadoException ex = assertThrows(RecursoDuplicadoException.class,
                () -> personaService.registrar(solicitud("20-12345678-6", null, null)));

        assertTrue(ex.getMessage().contains("20123456786"));
        verify(personaRepository, never()).save(any());
    }

    @Test
    void registrarConKeycloakUserIdDuplicadoLanzaRecursoDuplicado() {
        when(personaRepository.existsByCuitCuil("20123456786")).thenReturn(false);
        when(personaRepository.existsByKeycloakUserId("kc-repetido")).thenReturn(true);

        assertThrows(RecursoDuplicadoException.class,
                () -> personaService.registrar(solicitud("20123456786", "kc-repetido", null)));
        verify(personaRepository, never()).save(any());
    }

    @Test
    void registrarConEmailDuplicadoLanzaRecursoDuplicado() {
        when(personaRepository.existsByCuitCuil("20123456786")).thenReturn(false);
        when(personaRepository.existsByEmail("repetido@mail.com")).thenReturn(true);

        assertThrows(RecursoDuplicadoException.class,
                () -> personaService.registrar(solicitud("20123456786", null, "Repetido@mail.com")));
        verify(personaRepository, never()).save(any());
    }

    @Test
    void registrarSinKeycloakNiEmailNoLosConsultaYGuardaNull() {
        when(personaRepository.existsByCuitCuil("20123456786")).thenReturn(false);
        when(personaRepository.save(any(Persona.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        PersonaResponse respuesta = personaService.registrar(solicitud("20123456786", "  ", ""));

        assertEquals(null, respuesta.keycloakUserId());
        assertEquals(null, respuesta.email());
        verify(personaRepository, never()).existsByKeycloakUserId(any());
        verify(personaRepository, never()).existsByEmail(any());
    }

    @Test
    void agregarRolEsIdempotente() {
        Persona persona = persona(1L, TipoPersonaEnum.PROPIETARIO);
        when(personaRepository.findById(1L)).thenReturn(Optional.of(persona));

        PersonaResponse mismoRol = personaService.agregarRol(1L, TipoPersonaEnum.PROPIETARIO);
        assertEquals(Set.of(TipoPersonaEnum.PROPIETARIO), mismoRol.roles());

        PersonaResponse conGarante = personaService.agregarRol(1L, TipoPersonaEnum.GARANTE);
        assertEquals(Set.of(TipoPersonaEnum.PROPIETARIO, TipoPersonaEnum.GARANTE), conGarante.roles());
    }

    @Test
    void obtenerInexistenteLanzaRecursoNoEncontrado() {
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> personaService.obtener(99L));
    }

    @Test
    void listarSinRolTraeTodasYConRolFiltra() {
        Pageable paginacion = PageRequest.of(0, 20);
        Page<Persona> pagina = new PageImpl<>(List.of(persona(1L, TipoPersonaEnum.GARANTE)), paginacion, 1);
        when(personaRepository.findAll(paginacion)).thenReturn(pagina);
        when(personaRepository.findByRolesContaining(TipoPersonaEnum.GARANTE, paginacion)).thenReturn(pagina);

        assertEquals(1, personaService.listar(null, paginacion).getTotalElements());
        assertEquals(1, personaService.listar(TipoPersonaEnum.GARANTE, paginacion).getTotalElements());
    }

    private static CrearPersonaRequest solicitud(String cuit, String keycloakUserId, String email) {
        return new CrearPersonaRequest(cuit, "Juan", "Pérez", email, "3511234567", keycloakUserId,
                Set.of(TipoPersonaEnum.PROPIETARIO), new BigDecimal("1500000"));
    }

    private static Persona persona(Long id, TipoPersonaEnum rol) {
        Persona persona = Persona.builder()
                .cuitCuil("20123456786")
                .nombre("Ana")
                .apellido("Gómez")
                .roles(Set.of(rol))
                .build();
        ReflectionTestUtils.setField(persona, "id", id);
        return persona;
    }
}
