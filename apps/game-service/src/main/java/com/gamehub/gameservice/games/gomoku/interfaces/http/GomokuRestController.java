package com.gamehub.gameservice.games.gomoku.interfaces.http;


import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuState;
import com.gamehub.gameservice.games.gomoku.domain.model.Move;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gomoku")
public class GomokuRestController {

    private final GomokuService svc;

    public GomokuRestController(GomokuService svc) { this.svc = svc; }

    /**
     * 新建房间：
     *  mode=PVP 或 PVE（默认 PVE）
     *  aiPiece=X 或 O（PVE时有效，默认 O=后手）
     *  rule 禁手规则
     *  需要认证用户，创建者作为房主
     */
    @PostMapping("/new")
    public ResponseEntity<String> newRoom(@RequestParam(name = "mode", defaultValue="PVE") String mode,
                                          @RequestParam(name = "aiPiece", required = false) Character aiPiece,
                                          @RequestParam(name = "rule", defaultValue="STANDARD") String rule,
                                          @AuthenticationPrincipal Jwt jwt) {
        String ownerUserId = jwt.getSubject(); // 从 JWT 获取房主用户ID
        var m = "PVP".equalsIgnoreCase(mode) ? Mode.PVP : Mode.PVE;
        var r = "RENJU".equalsIgnoreCase(rule) ? Rule.RENJU : Rule.STANDARD;
        return ResponseEntity.ok(svc.newRoom(m, aiPiece, r, ownerUserId));
    }

    /**
     * 玩家落子：x,y,piece=X/O；必要时会触发AI同步下子
     * 对外入参保持不变，但内部根据认证用户解析其实际执子，确保鉴权。
     */
    @PostMapping("/{roomId}/place")
    public ResponseEntity<GameStateDTO> place(@PathVariable String roomId,
                                              @RequestParam int x,
                                              @RequestParam int y,
                                              @RequestParam char piece,
                                              @AuthenticationPrincipal Jwt jwt) {
        // 兼容：仍接受 piece 作为“意向”，实际执子由服务基于 userId+房间分配校验
        String userId = jwt.getSubject();
        Character want = (piece == 'X' || piece == 'O') ? piece : null;
        char caller = svc.resolveAndBindSide(roomId, userId, want);
        GomokuState s = svc.place(roomId, x, y, caller);
        return ResponseEntity.ok(GameStateDTO.from(s));
    }

    /** AI 辅助建议：不自动下，只返回推荐点 */
    @GetMapping("/{roomId}/suggest")
    public ResponseEntity<MoveDTO> suggest(@PathVariable String roomId,
                                           @RequestParam(defaultValue="X") char side) {
        Move m = svc.suggest(roomId, side);
        return ResponseEntity.ok(new MoveDTO(m.x(), m.y(), m.piece()));
    }
    // -------- DTO --------
    public record MoveDTO(int x,int y,char piece){}
    public record GameStateDTO(char[][] board,char current,boolean over,Character winner){
        static GameStateDTO from(GomokuState s){ return new GameStateDTO(s.board().view(), s.current(), s.over(), s.winner()); }
    }
}
