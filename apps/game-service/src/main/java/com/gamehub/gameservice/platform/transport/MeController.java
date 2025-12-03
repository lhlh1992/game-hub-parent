package com.gamehub.gameservice.platform.transport;

import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import com.gamehub.web.common.CurrentUserInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/me")
public class MeController {

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUserInfo user = CurrentUserHelper.from(jwt);
        
        Map<String, Object> body = Map.of(
                "sub", user.userId(),
                "username", user.username(),
                "nickname", user.nickname() != null ? user.nickname() : "",
                "displayName", user.getDisplayName(),
                "email", user.email() != null ? user.email() : "",
                "realm_roles", user.realmRoles(),
                "resource_access", user.clientRoles()
        );

        return ResponseEntity.ok(ApiResponse.success(body));
    }
}

