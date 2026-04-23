package com.flashchat.aggregation.controller;

import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregation fallback for game routes.
 * <p>
 * Some local run modes may fail to discover the controller from game-service
 * dependency. This fallback keeps the same API contract and delegates to
 * game services directly when that happens.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/FlashChat/v1/game")
@ConditionalOnMissingBean(name = "gameController")
public class GameControllerFallback {

    private static final Logger log = LoggerFactory.getLogger(GameControllerFallback.class);

    private final GameRoomService gameRoomService;
    private final GamePlayService gamePlayService;

    public GameControllerFallback(GameRoomService gameRoomService, GamePlayService gamePlayService) {
        this.gameRoomService = gameRoomService;
        this.gamePlayService = gamePlayService;
    }

    @PostMapping("/create")
    public Result<GameInfoRespDTO> createGame(@Valid @RequestBody CreateGameReqDTO request) {
        log.info("[GameFallback] create game, roomId={}", request.getRoomId());
        return Results.success(gameRoomService.createGame(request));
    }

    @PostMapping("/join")
    public Result<GameInfoRespDTO> joinGame(@Valid @RequestBody JoinGameReqDTO request) {
        log.info("[GameFallback] join game, gameId={}", request.getGameId());
        return Results.success(gameRoomService.joinGame(request));
    }

    @PostMapping("/leave")
    public Result<Void> leaveGame(@Valid @RequestBody LeaveGameReqDTO request) {
        log.info("[GameFallback] leave game, gameId={}", request.getGameId());
        gameRoomService.leaveGame(request);
        return Results.success();
    }

    @PostMapping("/cancel")
    public Result<Void> cancelGame(@Valid @RequestBody CancelGameReqDTO request) {
        log.info("[GameFallback] cancel game, gameId={}", request.getGameId());
        gameRoomService.cancelGame(request);
        return Results.success();
    }

    @PostMapping("/ai/add")
    public Result<GameInfoRespDTO> addAiPlayer(@Valid @RequestBody AddAiPlayerReqDTO request) {
        log.info("[GameFallback] add ai, gameId={}, provider={}, persona={}",
                request.getGameId(), request.getProvider(), request.getPersona());
        return Results.success(gameRoomService.addAiPlayer(request));
    }

    @PostMapping("/start")
    public Result<Void> startGame(@Valid @RequestBody StartGameReqDTO request) {
        log.info("[GameFallback] start game, gameId={}", request.getGameId());
        gamePlayService.startGame(request);
        return Results.success();
    }

    @PostMapping("/describe")
    public Result<Void> submitDescription(@Valid @RequestBody SubmitDescriptionReqDTO request) {
        log.info("[GameFallback] submit description, gameId={}", request.getGameId());
        gamePlayService.submitDescription(request);
        return Results.success();
    }

    @PostMapping("/vote")
    public Result<Void> submitVote(@Valid @RequestBody SubmitVoteReqDTO request) {
        log.info("[GameFallback] submit vote, gameId={}, targetAccountId={}",
                request.getGameId(), request.getTargetAccountId());
        gamePlayService.submitVote(request);
        return Results.success();
    }

    @GetMapping("/state/{gameId}")
    public Result<GameStateRespDTO> getGameState(@PathVariable("gameId") String gameId) {
        log.info("[GameFallback] get game state, gameId={}", gameId);
        return Results.success(gamePlayService.getGameState(gameId));
    }

    @GetMapping("/active/{roomId}")
    public Result<GameInfoRespDTO> getActiveGame(@PathVariable("roomId") String roomId) {
        log.info("[GameFallback] get active game, roomId={}", roomId);
        return Results.success(gameRoomService.getActiveGame(roomId));
    }

    @GetMapping("/history/{gameId}")
    public Result<GameResultRespDTO> getGameHistory(@PathVariable("gameId") String gameId) {
        log.info("[GameFallback] get game history, gameId={}", gameId);
        return Results.success(gamePlayService.getGameHistory(gameId));
    }
}
