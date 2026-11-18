package com.rovedasa.catalogo.validation;

import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class KeycloakUserIdSegunRolesValidator
        implements ConstraintValidator<KeycloakUserIdSegunRoles, CrearPersonaRequest> {

    private static final String CAMPO = "keycloakUserId";
    // Keycloak identifica a los usuarios con un UUID (el claim sub del token)
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public boolean isValid(CrearPersonaRequest solicitud, ConstraintValidatorContext contexto) {
        if (solicitud == null) {
            return true;
        }
        String keycloakUserId = solicitud.keycloakUserId();
        boolean informado = keycloakUserId != null && !keycloakUserId.isBlank();

        if (informado && !esUuid(keycloakUserId.trim())) {
            return rechazar(contexto, "El keycloakUserId tiene que ser el UUID del usuario en Keycloak "
                    + "(formato xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)");
        }
        // Roles nulos o vacíos los rechaza @NotEmpty; un rol nulo, @NotNull
        if (!informado && solicitud.roles() != null && solicitud.roles().stream()
                .anyMatch(rol -> rol != null && rol.requiereUsuarioKeycloak())) {
            return rechazar(contexto, "El keycloakUserId es obligatorio para los roles PROPIETARIO e INQUILINO: "
                    + "sin él la persona no puede ver sus propiedades ni sus contratos. Solo es opcional para "
                    + TipoPersonaEnum.GARANTE);
        }
        return true;
    }

    private static boolean esUuid(String valor) {
        return valor != null && UUID.matcher(valor).matches();
    }

    private static boolean rechazar(ConstraintValidatorContext contexto, String mensaje) {
        contexto.disableDefaultConstraintViolation();
        contexto.buildConstraintViolationWithTemplate(mensaje)
                .addPropertyNode(CAMPO)
                .addConstraintViolation();
        return false;
    }
}
