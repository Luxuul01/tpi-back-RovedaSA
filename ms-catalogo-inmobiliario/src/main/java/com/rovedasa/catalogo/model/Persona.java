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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "personas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Persona {

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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "persona_roles", joinColumns = @JoinColumn(name = "persona_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 20)
    private Set<TipoPersonaEnum> roles = new HashSet<>();

    @Setter
    @Column(precision = 15, scale = 2)
    private BigDecimal ingresosMensualesDeclarados;

    // Caché de la última consulta a la Central de Deudores del BCRA (lógica de solvencia a cargo del Dev 4)
    @Setter
    private Integer situacionCrediticiaBcra;

    @Setter
    private Integer chequesRechazados;

    @Setter
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

    public String getNombreCompleto() {
        return nombre + " " + apellido;
    }

    // Los roles solo se modifican mediante agregarRol()
    public Set<TipoPersonaEnum> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
}
