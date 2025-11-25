package com.gamehub.gameservice.platform.transport;

import com.gamehub.web.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/me")
public class MeController {

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal Jwt jwt) {
        String sub = jwt.getSubject();
        String username = Optional.ofNullable(jwt.getClaimAsString("preferred_username"))
                .orElse(sub);
        String email = jwt.getClaimAsString("email");

        // realm 角色
        Object realmAccess = jwt.getClaim("realm_access");
        Collection<?> realmRoles = null;
        if (realmAccess instanceof Map<?, ?> realm) {
            Object roles = realm.get("roles");
            if (roles instanceof Collection<?> r) {
                realmRoles = r;
            }
        }

        // client 角色（按需取具体 client-id）
        Object resourceAccess = jwt.getClaim("resource_access");
        Map<?, ?> clientRoles = null;
        if (resourceAccess instanceof Map<?, ?> res) {
            clientRoles = res;
        }

        Map<String, Object> body = Map.of(
                "sub", sub,
                "username", username,
                "email", Objects.toString(email, null),
                "realm_roles", Objects.requireNonNullElse(realmRoles, java.util.List.of()),
                "resource_access", Objects.requireNonNullElse(clientRoles, Map.of())
        );

        return ResponseEntity.ok(ApiResponse.success(body));
    }
}

