package com.rovedasa.catalogo.repository;

import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repositorios del catálogo contra H2 en memoria (@DataJpaTest reemplaza el datasource de PostgreSQL y
 * genera el esquema con create-drop). Solo se cargan las entidades y los repositorios: el seed de zonas
 * (un @Component CommandLineRunner) no corre, así que cada test arma sus propios datos.
 */
@DataJpaTest
class CatalogoRepositoriesTest {

    private static final String KC_PROPIETARIO = "00000000-0000-0000-0000-000000000002";
    private static final String KC_OTRO_PROPIETARIO = "00000000-0000-0000-0000-000000000009";

    @Autowired
    private TestEntityManager testEntityManager;
    @Autowired
    private PersonaRepository personaRepository;
    @Autowired
    private PropiedadRepository propiedadRepository;
    @Autowired
    private PrecioReferenciaZonaRepository precioReferenciaZonaRepository;

    private Persona propietario;
    private Persona otroPropietario;
    private Persona garante;

    @BeforeEach
    void cargarPersonas() {
        propietario = testEntityManager.persist(persona("20123456786", "propietario@mail.com", KC_PROPIETARIO,
                TipoPersonaEnum.PROPIETARIO));
        otroPropietario = testEntityManager.persist(persona("27123456780", "otro@mail.com", KC_OTRO_PROPIETARIO,
                TipoPersonaEnum.PROPIETARIO));
        garante = testEntityManager.persist(persona("30123456781", null, null, TipoPersonaEnum.GARANTE));
        testEntityManager.flush();
    }

    // --- PersonaRepository ---

    @Test
    void existsByDetectaCuitEmailYKeycloakUserIdExistentes() {
        assertTrue(personaRepository.existsByCuitCuil("20123456786"));
        assertFalse(personaRepository.existsByCuitCuil("20123456700"));
        assertTrue(personaRepository.existsByEmail("propietario@mail.com"));
        assertFalse(personaRepository.existsByEmail("nadie@mail.com"));
        assertTrue(personaRepository.existsByKeycloakUserId(KC_PROPIETARIO));
        assertFalse(personaRepository.existsByKeycloakUserId("00000000-0000-0000-0000-000000000099"));
    }

    @Test
    void findByRolesContainingFiltraPorRolYPagina() {
        Persona ambos = persona("20123456700", "ambos@mail.com", "00000000-0000-0000-0000-000000000005",
                TipoPersonaEnum.INQUILINO);
        ambos.agregarRol(TipoPersonaEnum.GARANTE);
        testEntityManager.persistAndFlush(ambos);
        testEntityManager.clear();

        Page<Persona> garantes = personaRepository.findByRolesContaining(TipoPersonaEnum.GARANTE,
                PageRequest.of(0, 20, Sort.by("id")));
        Page<Persona> propietarios = personaRepository.findByRolesContaining(TipoPersonaEnum.PROPIETARIO,
                PageRequest.of(0, 1, Sort.by("id")));

        assertEquals(List.of(garante.getId(), ambos.getId()), garantes.map(Persona::getId).getContent());
        // Dos propietarios en páginas de a uno
        assertEquals(2, propietarios.getTotalElements());
        assertEquals(List.of(propietario.getId()), propietarios.map(Persona::getId).getContent());
    }

    // La tabla persona_roles tiene PK compuesta (persona_id, rol): el mismo rol no se puede repetir
    @Test
    void personaRolesTienePkCompuestaQueRechazaRolRepetido() {
        EntityManager em = testEntityManager.getEntityManager();

        // Otro rol para la misma persona entra sin problema
        em.createNativeQuery("INSERT INTO persona_roles (persona_id, rol) VALUES (?1, 'INQUILINO')")
                .setParameter(1, propietario.getId())
                .executeUpdate();

        assertThrows(PersistenceException.class, () ->
                em.createNativeQuery("INSERT INTO persona_roles (persona_id, rol) VALUES (?1, 'PROPIETARIO')")
                        .setParameter(1, propietario.getId())
                        .executeUpdate());
    }

    @Test
    void personaRolesSeLeenComoUnConjunto() {
        propietario.agregarRol(TipoPersonaEnum.GARANTE);
        // Agregar un rol que ya tiene no duplica nada
        propietario.agregarRol(TipoPersonaEnum.PROPIETARIO);
        testEntityManager.flush();
        testEntityManager.clear();

        Persona leida = personaRepository.findById(propietario.getId()).orElseThrow();
        assertEquals(Set.of(TipoPersonaEnum.PROPIETARIO, TipoPersonaEnum.GARANTE), leida.getRoles());
    }

    // --- PropiedadRepository ---

    @Test
    void findByPropietarioKeycloakUserIdDevuelveSoloLasDelPropietario() {
        Propiedad propia1 = testEntityManager.persist(propiedad(propietario, "Independencia", "900"));
        Propiedad propia2 = testEntityManager.persist(propiedad(propietario, "Obispo Trejo", "1200"));
        testEntityManager.persist(propiedad(otroPropietario, "Chacabuco", "500"));
        testEntityManager.flush();
        testEntityManager.clear();

        List<Propiedad> propias = propiedadRepository.findByPropietario_KeycloakUserId(KC_PROPIETARIO);

        assertEquals(Set.of(propia1.getId(), propia2.getId()),
                Set.copyOf(propias.stream().map(Propiedad::getId).toList()));
        // El graph trae el propietario inicializado (open-in-view está deshabilitado)
        assertEquals("Juan Pérez", propias.get(0).getPropietario().getNombreCompleto());
        assertTrue(propiedadRepository.findByPropietario_KeycloakUserId("00000000-0000-0000-0000-000000000099")
                .isEmpty());
    }

    @Test
    void findByEstadoFiltraYPagina() {
        Propiedad disponible = testEntityManager.persist(propiedad(propietario, "Independencia", "900"));
        Propiedad inactiva = propiedad(propietario, "Obispo Trejo", "1200");
        inactiva.inactivar();
        testEntityManager.persist(inactiva);
        testEntityManager.flush();
        testEntityManager.clear();

        Page<Propiedad> disponibles = propiedadRepository.findByEstado(EstadoPropiedadEnum.DISPONIBLE,
                PageRequest.of(0, 20));
        Page<Propiedad> inactivas = propiedadRepository.findByEstado(EstadoPropiedadEnum.INACTIVA,
                PageRequest.of(0, 20));

        assertEquals(List.of(disponible.getId()), disponibles.map(Propiedad::getId).getContent());
        assertEquals(List.of(inactiva.getId()), inactivas.map(Propiedad::getId).getContent());
    }

    @Test
    void alPersistirSeGeneraElCodigoYLaVersionArrancaEnCero() {
        Propiedad guardada = propiedadRepository.saveAndFlush(propiedad(propietario, "Independencia", "900"));

        assertTrue(guardada.getCodigo().matches("PROP-[0-9A-F]{8}"), guardada.getCodigo());
        assertEquals(0L, guardada.getVersion());
    }

    /*
     * RN-05: dos actualizaciones sobre la misma versión. La primera incrementa la versión; la segunda llega con
     * la versión vieja (como una entidad leída antes del cambio) y el merge la rechaza. La excepción es la que
     * el handler traduce a 409.
     */
    @Test
    void dosActualizacionesSobreLaMismaVersionLanzanObjectOptimisticLockingFailure() {
        Long id = propiedadRepository.saveAndFlush(propiedad(propietario, "Independencia", "900")).getId();
        testEntityManager.clear();

        // Lector 1: se lleva la propiedad en versión 0 y queda "detached" (como en PropiedadService.modificar)
        Propiedad copiaVieja = propiedadRepository.findById(id).orElseThrow();
        testEntityManager.detach(copiaVieja);

        // Lector 2: la reserva y confirma primero -> versión 1
        Propiedad copiaNueva = propiedadRepository.findById(id).orElseThrow();
        copiaNueva.reservar(null, null);
        propiedadRepository.saveAndFlush(copiaNueva);
        assertEquals(1L, copiaNueva.getVersion());
        assertNotEquals(copiaNueva.getVersion(), copiaVieja.getVersion());

        // El lector 1 intenta guardar su cambio sobre la versión 0
        copiaVieja.inactivar();
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> propiedadRepository.saveAndFlush(copiaVieja));
    }

    // --- PrecioReferenciaZonaRepository ---

    @Test
    void findByProvinciaYCiudadNormalizadasTraeLasZonasDeLaCiudadConElDefault() {
        testEntityManager.persist(zona("Nueva Córdoba", "Córdoba", "Córdoba", -31.4245, -64.1870));
        testEntityManager.persist(zona("Córdoba (default)", "Córdoba", "Córdoba", null, null));
        testEntityManager.persist(zona("Centro", "Córdoba", "Río Cuarto", -33.1232, -64.3493));
        testEntityManager.flush();

        List<PrecioReferenciaZona> zonas =
                precioReferenciaZonaRepository.findByProvinciaNormalizadaAndCiudadNormalizada("cordoba", "cordoba");

        assertEquals(Set.of("Nueva Córdoba", "Córdoba (default)"),
                Set.copyOf(zonas.stream().map(PrecioReferenciaZona::getNombreZona).toList()));
        assertEquals(1, precioReferenciaZonaRepository
                .findByProvinciaNormalizadaAndCiudadNormalizada("cordoba", "rio cuarto").size());
    }

    // --- Datos de prueba ---

    private static Persona persona(String cuit, String email, String keycloakUserId, TipoPersonaEnum rol) {
        return Persona.builder()
                .cuitCuil(cuit)
                .nombre("Juan")
                .apellido("Pérez")
                .email(email)
                .keycloakUserId(keycloakUserId)
                .roles(Set.of(rol))
                .build();
    }

    private static Propiedad propiedad(Persona propietario, String calle, String numero) {
        return Propiedad.builder()
                .tipologia(TipologiaEnum.DEPARTAMENTO)
                .calle(calle)
                .numero(numero)
                .ciudad("Córdoba")
                .provincia("Córdoba")
                .superficieTotalM2(new BigDecimal("55.00"))
                .propietario(propietario)
                .build();
    }

    private static PrecioReferenciaZona zona(String nombre, String provincia, String ciudad, Double latitud,
            Double longitud) {
        return PrecioReferenciaZona.builder()
                .nombreZona(nombre)
                .provincia(provincia)
                .ciudad(ciudad)
                .latitudCentro(latitud)
                .longitudCentro(longitud)
                .radioMetros(latitud != null ? 1200 : null)
                .valorM2(new BigDecimal("12000"))
                .puntajeZona(8)
                .fechaActualizacion(LocalDate.of(2026, 11, 1))
                .build();
    }
}
