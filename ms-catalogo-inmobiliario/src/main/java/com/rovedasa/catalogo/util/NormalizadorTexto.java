package com.rovedasa.catalogo.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normaliza textos para compararlos sin importar acentos, mayúsculas ni espacios en los extremos.
 * Ejemplo: "  Córdoba " -> "cordoba". La ñ también pierde su tilde: "Ñuñoa" -> "nunoa".
 */
public final class NormalizadorTexto {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");

    private NormalizadorTexto() {
    }

    public static String normalizar(String texto) {
        if (texto == null) {
            return null;
        }
        // NFD separa cada letra de su acento ("ó" -> "o" + marca combinada), y después se borran las marcas
        String descompuesto = Normalizer.normalize(texto.trim(), Normalizer.Form.NFD);
        return MARCAS_DIACRITICAS.matcher(descompuesto).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * Para campos opcionales: recorta espacios y convierte el texto vacío en null.
     * Evita guardar "" en columnas únicas (dos "" chocarían; dos null no).
     */
    public static String limpiarOpcional(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.trim();
    }
}
