package com.rovedasa.catalogo.model;

import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "personas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Persona {

    // Escala de la Central de Deudores: 1 normal ... 5 irrecuperable, 6 irrecuperable por disposición técnica
    private static final int SITUACION_BCRA_MINIMA = 1;
    private static final int SITUACION_BCRA_MAXIMA = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nulo para los garantes, que no tienen usuario en Keycloak
    @Setter
    @Column(unique = true)
    private String keycloakUserId;

    // Solo dígitos, sin guiones
    @Column(nullable = false, unique = true, length = 11)
    private String cuitCuil;

    @Setter
    @Column(nullable = false)
    private String nombre;

    @Setter
    @Column(nullable = false)
    private String apellido;

    @Setter
    @Column(unique = true)
    private String email;

    @Setter
    private String telefono;

    // LAZY + BatchSize: al recorrer varias personas, los roles se cargan en lotes de 50 y no de a una
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @CollectionTable(name = "persona_roles", joinColumns = @JoinColumn(name = "persona_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 20)
    private Set<TipoPersonaEnum> roles = new HashSet<>();

    @Setter
    @Column(precision = 15, scale = 2)
    private BigDecimal ingresosMensualesDeclarados;

    // Última evaluación en la Central de Deudores del BCRA (F-08). Solo se escribe con registrarEvaluacionBcra()
    private Integer situacionCrediticiaBcra;

    private Integer chequesRechazados;

    private LocalDateTime fechaConsultaBcra;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime fechaAlta;

    @UpdateTimestamp
    private LocalDateTime fechaModificacion;

    @Builder
    private Persona(String keycloakUserId, String cuitCuil, String nombre, String apellido, String email,
            String telefono, Set<TipoPersonaEnum> roles, BigDecimal ingresosMensualesDeclarados) {
        this.keycloakUserId = keycloakUserId;
        this.cuitCuil = cuitCuil;
        this.nombre = nombre;
        this.apellido = apellido;
        this.email = email;
        this.telefono = telefono;
        this.ingresosMensualesDeclarados = ingresosMensualesDeclarados;
        if (roles != null) {
            this.roles.addAll(roles);
        }
    }

    public void agregarRol(TipoPersonaEnum rol) {
        roles.add(rol);
    }

    public boolean tieneRol(TipoPersonaEnum rol) {
        return roles.contains(rol);
    }

    /**
     * Registra el resultado de una consulta a la Central de Deudores del BCRA (F-08, RN-07).
     * Reemplaza la evaluación anterior: se conserva solo la más reciente.
     * Los datos inválidos son un error de programación (quien llama los obtuvo del BCRA), no de negocio.
     *
     * @param situacionCrediticia peor situación informada por el BCRA, de 1 (normal) a 6
     * @param chequesRechazados   cantidad de cheques rechazados informados (0 si no tiene)
     * @param fechaConsulta       momento de la consulta, tomado del Clock de la aplicación
     */
    public void registrarEvaluacionBcra(int situacionCrediticia, int chequesRechazados,
            LocalDateTime fechaConsulta) {
        // Todas las validaciones antes de asignar: un rechazo no deja la evaluación a medias
        Objects.requireNonNull(fechaConsulta, "La fecha de consulta es obligatoria");
        if (situacionCrediticia < SITUACION_BCRA_MINIMA || situacionCrediticia > SITUACION_BCRA_MAXIMA) {
            throw new IllegalArgumentException("La situación crediticia del BCRA va de " + SITUACION_BCRA_MINIMA
                    + " a " + SITUACION_BCRA_MAXIMA + ": " + situacionCrediticia);
        }
        if (chequesRechazados < 0) {
            throw new IllegalArgumentException("La cantidad de cheques rechazados no puede ser negativa: "
                    + chequesRechazados);
        }
        this.situacionCrediticiaBcra = situacionCrediticia;
        this.chequesRechazados = chequesRechazados;
        this.fechaConsultaBcra = fechaConsulta;
    }

    public String getNombreCompleto() {
        return nombre + " " + apellido;
    }

    // Los roles solo se modifican mediante agregarRol()
    public Set<TipoPersonaEnum> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
}
