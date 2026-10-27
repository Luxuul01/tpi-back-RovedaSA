package com.rovedasa.catalogo.repository;

import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrecioReferenciaZonaRepository extends JpaRepository<PrecioReferenciaZona, Long> {

    // Todas las zonas de la ciudad, incluida la fila por defecto
    List<PrecioReferenciaZona> findByProvinciaIgnoreCaseAndCiudadIgnoreCase(String provincia, String ciudad);

    // Valor por defecto de la ciudad: la fila sin centro geográfico
    Optional<PrecioReferenciaZona> findFirstByProvinciaIgnoreCaseAndCiudadIgnoreCaseAndLatitudCentroIsNull(
            String provincia, String ciudad);
}
