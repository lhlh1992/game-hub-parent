package com.gamehub.systemservice.controller.friend;

import com.gamehub.systemservice.dto.request.ApplyFriendRequest;
import com.gamehub.systemservice.exception.BusinessException;
import com.gamehub.systemservice.service.friend.FriendService;
import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 好友控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    /**
     * 申请加好友
     * 
     * @param request 申请请求
     * @param jwt JWT Token
     * @return 响应结果
     */
    @PostMapping("/apply")
    public ResponseEntity<ApiResponse<Void>> applyFriend(
            @Valid @RequestBody ApplyFriendRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        // 获取当前用户Keycloak用户ID（String格式）
        String requesterKeycloakUserId = CurrentUserHelper.getUserId(jwt);
        String targetKeycloakUserId = request.getTargetUserId();
        String requestMessage = request.getRequestMessage();
        
        log.debug("收到好友申请请求: requesterId={}, targetId={}, message={}", 
                requesterKeycloakUserId, targetKeycloakUserId, requestMessage);
        
        try {
            // 调用服务层处理申请
            boolean autoAccepted = friendService.applyFriend(
                    requesterKeycloakUserId, 
                    targetKeycloakUserId, 
                    requestMessage
            );
            
            if (autoAccepted) {
                // 双向申请自动通过
                return ResponseEntity.ok(ApiResponse.success("已自动成为好友", null));
            } else {
                // 正常申请
                return ResponseEntity.ok(ApiResponse.success("申请已发送，等待对方处理", null));
            }
        } catch (BusinessException e) {
            // 业务异常，根据错误码返回对应的HTTP状态码
            int httpStatus = switch (e.getCode()) {
                case 400 -> 400; // Bad Request
                case 404 -> 404; // Not Found
                case 409 -> 409; // Conflict
                default -> 500;  // Internal Server Error
            };
            
            return ResponseEntity.status(httpStatus)
                    .body(ApiResponse.error(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("申请加好友失败: requesterKeycloakUserId={}, targetKeycloakUserId={}", 
                    requesterKeycloakUserId, targetKeycloakUserId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.serverError("申请加好友失败: " + e.getMessage()));
        }
    }

    /**
     * 同意好友申请（接收方操作）
     */
    @PostMapping("/requests/{id}/accept")
    public ResponseEntity<ApiResponse<Void>> accept(
            @PathVariable("id") UUID requestId,
            @AuthenticationPrincipal Jwt jwt) {
        String receiverKeycloakUserId = CurrentUserHelper.getUserId(jwt);
        try {
            friendService.acceptFriendRequest(receiverKeycloakUserId, requestId);
            return ResponseEntity.ok(ApiResponse.success("已同意好友申请", null));
        } catch (BusinessException e) {
            int httpStatus = switch (e.getCode()) {
                case 400 -> 400;
                case 403 -> 403;
                case 404 -> 404;
                case 409 -> 409;
                default -> 500;
            };
            return ResponseEntity.status(httpStatus)
                    .body(ApiResponse.error(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("同意好友申请失败: requestId={}", requestId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.serverError("同意好友申请失败: " + e.getMessage()));
        }
    }

    /**
     * 拒绝好友申请（接收方操作）
     */
    @PostMapping("/requests/{id}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable("id") UUID requestId,
            @AuthenticationPrincipal Jwt jwt) {
        String receiverKeycloakUserId = CurrentUserHelper.getUserId(jwt);
        try {
            friendService.rejectFriendRequest(receiverKeycloakUserId, requestId);
            return ResponseEntity.ok(ApiResponse.success("已拒绝好友申请", null));
        } catch (BusinessException e) {
            int httpStatus = switch (e.getCode()) {
                case 400 -> 400;
                case 403 -> 403;
                case 404 -> 404;
                case 409 -> 409;
                default -> 500;
            };
            return ResponseEntity.status(httpStatus)
                    .body(ApiResponse.error(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("拒绝好友申请失败: requestId={}", requestId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.serverError("拒绝好友申请失败: " + e.getMessage()));
        }
    }
}



