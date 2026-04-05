package com.flashchat.gameservice.controller;

import com.flashchat.gameservice.dto.req.AddAiPlayerReqDTO;
import com.flashchat.gameservice.dto.req.CancelGameReqDTO;
import com.flashchat.gameservice.dto.req.CreateGameReqDTO;
import com.flashchat.gameservice.dto.req.JoinGameReqDTO;
import com.flashchat.gameservice.dto.req.LeaveGameReqDTO;
import com.flashchat.gameservice.dto.req.StartGameReqDTO;
import com.flashchat.gameservice.dto.req.SubmitDescriptionReqDTO;
import com.flashchat.gameservice.dto.req.SubmitVoteReqDTO;
import com.flashchat.gameservice.dto.resp.GameInfoRespDTO;
import com.flashchat.gameservice.dto.resp.GameResultRespDTO;
import com.flashchat.gameservice.dto.resp.GameStateRespDTO;
import com.flashchat.gameservice.service.GamePlayService;
import com.flashchat.gameservice.service.GameRoomService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 游戏模块接口控制器。
 * <p>
 * 这一层只做三件事：
 * 1. 接收 HTTP 请求并做参数校验
 * 2. 调用房间服务 / 玩法服务
 * 3. 统一包装 Result 返回体
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@AllArgsConstructor
@RequestMapping("/api/FlashChat/v1/game")
public class GameController {

    private final GameRoomService gameRoomService;
    private final GamePlayService gamePlayService;

    /**
     * 创建游戏
     */
    @PostMapping("/create")
    public com.flashchat.convention.result.Result<GameInfoRespDTO> createGame(@Valid @RequestBody CreateGameReqDTO request) {
        log.info("[创建游戏] roomId={}", request.getRoomId());
        return com.flashchat.convention.result.Results.success(gameRoomService.createGame(request));
    }

    /**
     * 加入游戏
     */
    @PostMapping("/join")
    public com.flashchat.convention.result.Result<GameInfoRespDTO> joinGame(@Valid @RequestBody JoinGameReqDTO request) {
        log.info("[加入游戏] gameId={}", request.getGameId());
        return com.flashchat.convention.result.Results.success(gameRoomService.joinGame(request));
    }

    /**
     * 退出游戏
     */
    @PostMapping("/leave")
    public com.flashchat.convention.result.Result<Void> leaveGame(@Valid @RequestBody LeaveGameReqDTO request) {
        log.info("[退出游戏] gameId={}", request.getGameId());
        gameRoomService.leaveGame(request);
        return com.flashchat.convention.result.Results.success();
    }

    /**
     * 取消游戏
     */
    @PostMapping("/cancel")
    public com.flashchat.convention.result.Result<Void> cancelGame(@Valid @RequestBody CancelGameReqDTO request) {
        log.info("[取消游戏] gameId={}", request.getGameId());
        gameRoomService.cancelGame(request);
        return com.flashchat.convention.result.Results.success();
    }

    /**
     * 添加 AI 玩家
     */
    @PostMapping("/ai/add")
    public com.flashchat.convention.result.Result<GameInfoRespDTO> addAiPlayer(@Valid @RequestBody AddAiPlayerReqDTO request) {
        log.info("[添加AI] gameId={}, provider={}, persona={}",
                request.getGameId(), request.getProvider(), request.getPersona());
        return com.flashchat.convention.result.Results.success(gameRoomService.addAiPlayer(request));
    }

    /**
     * 开始游戏
     */
    @PostMapping("/start")
    public com.flashchat.convention.result.Result<Void> startGame(@Valid @RequestBody StartGameReqDTO request) {
        log.info("[开始游戏] gameId={}", request.getGameId());
        gamePlayService.startGame(request);
        return com.flashchat.convention.result.Results.success();
    }

    /**
     * 提交发言
     */
    @PostMapping("/describe")
    public com.flashchat.convention.result.Result<Void> submitDescription(@Valid @RequestBody SubmitDescriptionReqDTO request) {
        log.info("[提交发言] gameId={}", request.getGameId());
        gamePlayService.submitDescription(request);
        return com.flashchat.convention.result.Results.success();
    }

    /**
     * 提交投票
     */
    @PostMapping("/vote")
    public com.flashchat.convention.result.Result<Void> submitVote(@Valid @RequestBody SubmitVoteReqDTO request) {
        log.info("[提交投票] gameId={}, targetAccountId={}",
                request.getGameId(), request.getTargetAccountId());
        gamePlayService.submitVote(request);
        return com.flashchat.convention.result.Results.success();
    }

    /**
     * 查询游戏实时状态
     */
    @GetMapping("/state/{gameId}")
    public com.flashchat.convention.result.Result<GameStateRespDTO> getGameState(@PathVariable("gameId") String gameId) {
        log.info("[查询状态] gameId={}", gameId);
        return com.flashchat.convention.result.Results.success(gamePlayService.getGameState(gameId));
    }

    /**
     * 查询房间当前活跃游戏
     */
    @GetMapping("/active/{roomId}")
    public com.flashchat.convention.result.Result<GameInfoRespDTO> getActiveGame(@PathVariable("roomId") String roomId) {
        log.info("[查询活跃游戏] roomId={}", roomId);
        return com.flashchat.convention.result.Results.success(gameRoomService.getActiveGame(roomId));
    }

    /**
     * 查询游戏历史记录
     */
    @GetMapping("/history/{gameId}")
    public com.flashchat.convention.result.Result<GameResultRespDTO> getGameHistory(@PathVariable("gameId") String gameId) {
        log.info("[查询历史] gameId={}", gameId);
        return com.flashchat.convention.result.Results.success(gamePlayService.getGameHistory(gameId));
    }
}
