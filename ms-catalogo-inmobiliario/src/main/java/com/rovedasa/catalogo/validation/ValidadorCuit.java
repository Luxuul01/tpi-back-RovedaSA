package com.rovedasa.catalogo.validation;

import java.util.Set;

/**
 * Normalización y validación de CUIT/CUIL según el algoritmo de AFIP (módulo 11).
 */
public final class ValidadorCuit {

    private static final int LARGO_CUIT = 11;
    private static final Set<String> PREFIJOS_VALIDOS = Set.of("20", "23", "24", "27", "30", "33", "34");
    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private ValidadorCuit() {
    }

    // Deja solo los dígitos: "20-12345678-6" -> "20123456786"
    public static String normalizar(String cuit) {
        return cuit == null ? null : cuit.replaceAll("\\D", "");
    }

    public static boolean esValido(String cuit) {
        String digitos = normalizar(cuit);
        if (digitos == null || digitos.length() != LARGO_CUIT) {
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
}
