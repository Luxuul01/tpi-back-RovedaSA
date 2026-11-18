package com.rovedasa.catalogo.validation;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalización y validación de CUIT/CUIL según el algoritmo de AFIP (módulo 11).
 * Formatos aceptados: 11 dígitos seguidos ("20123456786") o con guiones ("20-12345678-6"),
 * con espacios opcionales solo al principio y al final. Cualquier otro carácter lo vuelve inválido.
 */
public final class ValidadorCuit {

    private static final int LARGO_CUIT = 11;
    private static final Pattern SOLO_DIGITOS = Pattern.compile("\\d{11}");
    private static final Pattern CON_GUIONES = Pattern.compile("\\d{2}-\\d{8}-\\d");
    private static final Set<String> PREFIJOS_VALIDOS = Set.of("20", "23", "24", "27", "30", "33", "34");
    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private ValidadorCuit() {
    }

    /**
     * Pasa un CUIT/CUIL en un formato aceptado a solo dígitos: "20-12345678-6" -> "20123456786".
     * Si el valor no respeta ninguno de los dos formatos se devuelve recortado y sin cambios, así
     * {@link #esValido(String)} lo rechaza (nunca se "rescatan" dígitos mezclados con otros caracteres).
     */
    public static String normalizar(String cuit) {
        if (cuit == null) {
            return null;
        }
        String recortado = cuit.trim();
        if (CON_GUIONES.matcher(recortado).matches()) {
            return recortado.replace("-", "");
        }
        return recortado;
    }

    public static boolean esValido(String cuit) {
        String digitos = normalizar(cuit);
        if (digitos == null || !SOLO_DIGITOS.matcher(digitos).matches()) {
            return false;
        }
        if (!PREFIJOS_VALIDOS.contains(digitos.substring(0, 2))) {
            return false;
        }

        // Dígito verificador: suma ponderada de los 10 primeros dígitos, módulo 11
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += Character.getNumericValue(digitos.charAt(i)) * PESOS[i];
        }
        int digitoVerificador = 11 - (suma % 11);
        if (digitoVerificador == 11) {
            digitoVerificador = 0;
        }
        if (digitoVerificador == 10) {
            // AFIP no emite estos números: en ese caso cambia el prefijo a 23 o 33
            return false;
        }
        return digitoVerificador == Character.getNumericValue(digitos.charAt(LARGO_CUIT - 1));
    }

    /**
     * Versión para logs (dato personal): deja el prefijo, los 4 últimos dígitos del documento y el
     * verificador. "20123456786" -> "20-****5678-6". Si no tiene un formato aceptado, no muestra nada.
     */
    public static String enmascarar(String cuit) {
        String digitos = normalizar(cuit);
        if (digitos == null || !SOLO_DIGITOS.matcher(digitos).matches()) {
            return "***";
        }
        return digitos.substring(0, 2) + "-****" + digitos.substring(6, 10) + "-" + digitos.charAt(LARGO_CUIT - 1);
    }
}
