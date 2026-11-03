package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.client.georef.DireccionNormalizada;
import com.rovedasa.catalogo.client.georef.GeoRefClient;
import com.rovedasa.catalogo.dto.CrearPropiedadRequest;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.mapper.PropiedadMapper;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.catalogo.repository.PropiedadRepository;
import com.rovedasa.catalogo.util.NormalizadorTexto;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Alta, consulta y cambios de estado de propiedades (CU-01 y CU-08).
 */
@Slf4j
@Service
public class PropiedadService {

    private final PropiedadRepository propiedadRepository;
    private final PersonaRepository personaRepository;
    private final GeoRefClient geoRefClient;
    private final TasacionService tasacionService;
    // registrar() maneja sus transacciones a mano para no tener una conexión tomada durante la llamada a GeoRef
    private final TransactionTemplate transaccionLectura;
    private final TransactionTemplate transaccionEscritura;

    public PropiedadService(PropiedadRepository propiedadRepository, PersonaRepository personaRepository,
            GeoRefClient geoRefClient, TasacionService tasacionService,
            PlatformTransactionManager transactionManager) {
        this.propiedadRepository = propiedadRepository;
        this.personaRepository = personaRepository;
        this.geoRefClient = geoRefClient;
        this.tasacionService = tasacionService;
        this.transaccionLectura = new TransactionTemplate(transactionManager);
        this.transaccionLectura.setReadOnly(true);
        this.transaccionEscritura = new TransactionTemplate(transactionManager);
    }

    /**
     * Alta de propiedad con normalización GeoRef y tasación (CU-01).
     *
     * Este método NO es @Transactional a propósito: la llamada HTTP a GeoRef puede tardar segundos
     * (timeout de lectura de 5 s) y no queremos una conexión de la base y una transacción abiertas
     * mientras tanto. Se usan dos transacciones cortas con TransactionTemplate (no hay
     * auto-invocación de @Transactional, que a través de "this" no pasaría por el proxy):
     *   1. Lectura: buscar el propietario y validar su rol (los roles son LAZY).
     *   2. Escritura: el INSERT final.
     * Entre las dos, GeoRef y la tasación (que abre su propia transacción de solo lectura).
     *
     * El propietario y la zona quedan "detached" al cerrarse sus transacciones. Es seguro: save() de una
     * entidad nueva hace persist, que no cascadea a esas relaciones; Hibernate solo lee su id para
     * escribir la FK y no intenta reasociarlas (ninguna se modificó).
     */
    public PropiedadResponse registrar(CrearPropiedadRequest solicitud) {
        // 1. Transacción corta de lectura
        Persona propietario = transaccionLectura.execute(
                estado -> buscarPropietarioValidado(solicitud.propietarioId()));

        // 2. Construcción (la entidad valida que la superficie cubierta no supere la total)
        Propiedad propiedad = Propiedad.builder()
                .tipologia(solicitud.tipologia())
                .calle(solicitud.calle().trim())
                .numero(solicitud.numero().trim())
                .piso(NormalizadorTexto.limpiarOpcional(solicitud.piso()))
                .departamento(NormalizadorTexto.limpiarOpcional(solicitud.departamento()))
                .barrio(NormalizadorTexto.limpiarOpcional(solicitud.barrio()))
                .codigoPostal(NormalizadorTexto.limpiarOpcional(solicitud.codigoPostal()))
                .ciudad(solicitud.ciudad().trim())
                .provincia(solicitud.provincia().trim())
                .superficieTotalM2(solicitud.superficieTotalM2())
                .superficieCubiertaM2(solicitud.superficieCubiertaM2())
                .propietario(propietario)
                .build();

        // 3. GeoRef, fuera de toda transacción
        DireccionNormalizada direccion = geoRefClient
                .normalizar(propiedad.getCalle(), propiedad.getNumero(), propiedad.getProvincia(), propiedad.getCiudad())
                .orElseThrow(() -> new OperacionInvalidaException(String.format(
                        "No se pudo normalizar la dirección '%s %s' en %s, %s. Verificá la calle, la altura y que "
                                + "la ciudad sea el nombre oficial de la localidad (por ejemplo 'Córdoba', no 'Córdoba Capital')",
                        propiedad.getCalle(), propiedad.getNumero(), propiedad.getCiudad(), propiedad.getProvincia())));

        // 4. Normalización y tasación
        propiedad.aplicarNormalizacion(direccion.nomenclatura(), direccion.provincia(), direccion.ciudad(),
                direccion.latitud(), direccion.longitud());
        Tasacion tasacion = tasacionService.tasar(direccion.latitud(), direccion.longitud(),
                direccion.provincia(), direccion.ciudad(), propiedad.getTipologia(), propiedad.superficieComputableM2());
        propiedad.aplicarTasacion(tasacion.zona(), tasacion.precioSugeridoM2(), tasacion.canonBaseSugerido());

        // 5. Transacción corta de escritura
        Propiedad guardada = transaccionEscritura.execute(estado -> propiedadRepository.save(propiedad));

        log.info("Propiedad {} registrada (ID: {}) en zona {} con canon sugerido {}", guardada.getCodigo(),
                guardada.getId(), tasacion.tieneCobertura() ? tasacion.zona().getNombreZona() : "sin cobertura",
                guardada.getCanonBaseSugerido());
        return PropiedadMapper.aResponse(guardada);
    }

    @Transactional(readOnly = true)
    public PropiedadResponse obtener(Long id) {
        return PropiedadMapper.aResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public Page<PropiedadResponse> listar(EstadoPropiedadEnum estado, Pageable paginacion) {
        Page<Propiedad> propiedades = estado == null
                ? propiedadRepository.findAll(paginacion)
                : propiedadRepository.findByEstado(estado, paginacion);
        return propiedades.map(PropiedadMapper::aResponse);
    }

    // CU-08: el keycloakUserId sale SIEMPRE del claim sub del JWT, nunca de un parámetro de la petición
    @Transactional(readOnly = true)
    public List<PropiedadResponse> listarPropias(String keycloakUserId) {
        return propiedadRepository.findByPropietario_KeycloakUserId(keycloakUserId).stream()
                .map(PropiedadMapper::aResponse)
                .toList();
    }

    /**
     * Cambio de estado pedido por ms-contratos (seña, activación, cancelación, rescisión) o por el administrador.
     * Las guardas de cada transición viven en la entidad; acá se resuelven las personas y sus roles.
     */
    @Transactional
    public PropiedadDTO actualizarEstado(Long id, ActualizarEstadoPropiedadDTO solicitud) {
        Propiedad propiedad = buscar(id);
        EstadoPropiedadEnum estadoAnterior = propiedad.getEstado();

        switch (solicitud.getNuevoEstado()) {
            case RESERVADA -> reservar(propiedad, solicitud.getInquilinoId(), solicitud.getGaranteId());
            case ALQUILADA -> propiedad.alquilar();
            case DISPONIBLE -> {
                // DISPONIBLE significa "reactivar" si estaba dada de baja, o "liberar" si estaba reservada/alquilada
                if (propiedad.getEstado() == EstadoPropiedadEnum.INACTIVA) {
                    propiedad.reactivar();
                } else {
                    propiedad.liberar();
                }
            }
            case INACTIVA -> propiedad.inactivar();
        }

        log.info("Propiedad {} pasó de {} a {}", propiedad.getCodigo(), estadoAnterior, propiedad.getEstado());
        return PropiedadMapper.aPropiedadDTO(propiedad);
    }

    private void reservar(Propiedad propiedad, Long inquilinoId, Long garanteId) {
        Persona inquilino = inquilinoId != null ? buscarPersonaConRol(inquilinoId, TipoPersonaEnum.INQUILINO) : null;
        Persona garante = garanteId != null ? buscarPersonaConRol(garanteId, TipoPersonaEnum.GARANTE) : null;

        if (garanteId != null && Objects.equals(garanteId, inquilinoId)) {
            throw new OperacionInvalidaException("El garante no puede ser la misma persona que el inquilino");
        }
        if (garanteId != null && Objects.equals(garanteId, propiedad.getPropietario().getId())) {
            throw new OperacionInvalidaException("El garante no puede ser el propietario de la propiedad");
        }
        propiedad.reservar(inquilino, garante);
    }

    private Persona buscarPropietarioValidado(Long propietarioId) {
        Persona propietario = buscarPersona(propietarioId);
        if (!propietario.tieneRol(TipoPersonaEnum.PROPIETARIO)) {
            throw new OperacionInvalidaException(String.format(
                    "La persona %s (ID %d) no tiene el rol PROPIETARIO; agregáselo antes de registrarle una propiedad",
                    propietario.getNombreCompleto(), propietarioId));
        }
        return propietario;
    }

    private Persona buscarPersonaConRol(Long id, TipoPersonaEnum rol) {
        Persona persona = buscarPersona(id);
        if (!persona.tieneRol(rol)) {
            throw new OperacionInvalidaException(String.format(
                    "La persona %s (ID %d) no tiene el rol %s", persona.getNombreCompleto(), id, rol));
        }
        return persona;
    }

    private Persona buscarPersona(Long id) {
        return personaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la persona con ID " + id));
    }

    private Propiedad buscar(Long id) {
        return propiedadRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la propiedad con ID " + id));
    }
}
