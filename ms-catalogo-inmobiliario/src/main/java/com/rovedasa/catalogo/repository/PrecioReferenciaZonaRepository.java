package com.rovedasa.catalogo.repository;

import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Las búsquedas son por provincia y ciudad NORMALIZADAS: quien llama tiene que pasar los valores
 * ya procesados con {@link com.rovedasa.catalogo.util.NormalizadorTexto#normalizar(String)}.
 */
public interface PrecioReferenciaZonaRepository extends JpaRepository<PrecioReferenciaZona, Long> {

    /**
     * Todas las zonas de la ciudad, incluida la fila por defecto.
     *
     * @param provinciaNormalizada provincia ya normalizada (ej. "cordoba")
     * @param ciudadNormalizada    ciudad ya normalizada (ej. "cordoba")
     */
    List<PrecioReferenciaZona> findByProvinciaNormalizadaAndCiudadNormalizada(
            String provinciaNormalizada, String ciudadNormalizada);

    /**
     * Valor por defecto de la ciudad: la fila sin centro geográfico.
     *
     * @param provinciaNormalizada provincia ya normalizada (ej. "cordoba")
     * @param ciudadNormalizada    ciudad ya normalizada (ej. "cordoba")
     */
    Optional<PrecioReferenciaZona> findFirstByProvinciaNormalizadaAndCiudadNormalizadaAndLatitudCentroIsNull(
            String provinciaNormalizada, String ciudadNormalizada);
}
