package com.flashchat.gameservice.engine;

import com.flashchat.channel.ChannelPushService;
import com.flashchat.gameservice.ai.AiContextAssembler;
import com.flashchat.gameservice.ai.AiPlayerService;
import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;
import com.flashchat.gameservice.config.GameConfig;
import com.flashchat.gameservice.dao.enums.*;
import com.flashchat.gameservice.service.GamePersistService;
import com.flashchat.gameservice.timer.GameTurnTimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RunnableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WhoIsSpyEngine} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class WhoIsSpyEngineTest {

    @Mock
    private ChannelPushService channelPushService;

    @Mock
    private GameContextManager gameContextManager;

    @Mock
    private GameTurnTimer gameTurnTimer;

    @Mock
    private GamePersistService gamePersistService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AiContextAssembler aiContextAssembler;

    @Mock
    private AiPlayerService aiPlayerService;

    @Mock
    private ExecutorService aiPlayerExecutor;

    private WhoIsSpyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WhoIsSpyEngine(
                channelPushService,
                gameContextManager,
                gameTurnTimer,
                gamePersistService,
                eventPublisher,
                aiContextAssembler,
                aiPlayerService,
                aiPlayerExecutor
        );
    }

    @Test
    void advanceToNextSpeaker_shouldRecordAiDescriptionAndMoveToHuman() {
        GameContext ctx = newContext("game-ai-describe");
        GamePlayerInfo aiPlayer = buildAiPlayer(101L, -1L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        GamePlayerInfo humanPlayer = buildHumanPlayer(102L, 2L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, aiPlayer, humanPlayer);
        prepareDescribeRound(ctx, 0);

        AiDescribeInput input = AiDescribeInput.builder()
                .gameId(ctx.getGameId())
                .aiPlayerId(aiPlayer.getPlayerId())
                .provider(AiProviderEnum.KIMI)
                .persona(AiPersonaEnum.CAUTIOUS)
                .role(aiPlayer.getRole())
                .word(aiPlayer.getWord())
                .roundNumber(ctx.getCurrentRound().get())
                .currentRoundDescriptions(List.of())
                .historyDescriptions(List.of())
                .build();
        when(aiContextAssembler.buildDescribeInput(ctx, aiPlayer)).thenReturn(input);
        when(aiPlayerService.generateDescription(input)).thenReturn("这个东西挺常见");
        runSubmittedTasksImmediately();

        engine.advanceToNextSpeaker(ctx, 0);

        assertThat(ctx.getCurrentSpeakerOrder().get()).isEqualTo(2);
        assertThat(ctx.getCurrentRoundDescriptions()).hasSize(1);
        GameContext.DescriptionRecord record = ctx.getCurrentRoundDescriptions().get(0);
        assertThat(record.getPlayerId()).isEqualTo(101L);
        assertThat(record.getContent()).isEqualTo("这个东西挺常见");
        assertThat(record.isSkipped()).isFalse();
        verify(aiPlayerService).generateDescription(input);
    }

    @Test
    void advanceToNextSpeaker_shouldSkipOnlyOnce_whenSafetyTimeoutWins() {
        GameContext ctx = newContext("game-ai-timeout");
        GamePlayerInfo aiPlayer = buildAiPlayer(201L, -1L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        GamePlayerInfo humanPlayer = buildHumanPlayer(202L, 2L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, aiPlayer, humanPlayer);
        prepareDescribeRound(ctx, 0);

        AiDescribeInput input = AiDescribeInput.builder()
                .gameId(ctx.getGameId())
                .aiPlayerId(aiPlayer.getPlayerId())
                .provider(AiProviderEnum.KIMI)
                .persona(AiPersonaEnum.CAUTIOUS)
                .role(aiPlayer.getRole())
                .word(aiPlayer.getWord())
                .roundNumber(ctx.getCurrentRound().get())
                .currentRoundDescriptions(List.of())
                .historyDescriptions(List.of())
                .build();
        when(aiContextAssembler.buildDescribeInput(ctx, aiPlayer)).thenReturn(input);
        when(aiPlayerService.generateDescription(input)).thenReturn("晚到的发言");

        List<Runnable> submittedTasks = new ArrayList<>();
        doAnswer(invocation -> {
            submittedTasks.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(aiPlayerExecutor).submit(any(Runnable.class));

        List<Runnable> describeTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
            describeTimeouts.add(invocation.getArgument(2));
            return null;
        }).when(gameTurnTimer).scheduleDescribeTimeout(any(GameContext.class), anyInt(), any(Runnable.class));

        engine.advanceToNextSpeaker(ctx, 0);

        assertThat(submittedTasks).hasSize(1);
        assertThat(describeTimeouts).isNotEmpty();

        describeTimeouts.get(0).run();

        assertThat(ctx.getCurrentSpeakerOrder().get()).isEqualTo(2);
        assertThat(ctx.getCurrentRoundDescriptions()).hasSize(1);
        assertThat(ctx.getCurrentRoundDescriptions().get(0).isSkipped()).isTrue();

        submittedTasks.get(0).run();

        assertThat(ctx.getCurrentRoundDescriptions()).hasSize(1);
        assertThat(ctx.getCurrentRoundDescriptions().get(0).isSkipped()).isTrue();
    }

    @Test
    void advanceToNextSpeaker_shouldEnterVoting_whenEveryoneAlreadyDescribed() {
        GameContext ctx = newContext("game-finish-describe-round");
        GamePlayerInfo firstHuman = buildHumanPlayer(211L, 11L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        GamePlayerInfo secondHuman = buildHumanPlayer(212L, 12L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        GamePlayerInfo thirdHuman = buildHumanPlayer(213L, 13L, 3, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, firstHuman, secondHuman, thirdHuman);
        prepareDescribeRound(ctx, 3);

        ctx.addDescriptionIfAbsent(new GameContext.DescriptionRecord(
                firstHuman.getPlayerId(), firstHuman.getAccountId(), firstHuman.getNickname(), "第一位已发言", false));
        ctx.addDescriptionIfAbsent(new GameContext.DescriptionRecord(
                secondHuman.getPlayerId(), secondHuman.getAccountId(), secondHuman.getNickname(), "第二位已发言", false));
        ctx.addDescriptionIfAbsent(new GameContext.DescriptionRecord(
                thirdHuman.getPlayerId(), thirdHuman.getAccountId(), thirdHuman.getNickname(), "第三位已发言", false));

        engine.advanceToNextSpeaker(ctx, 3);

        assertThat(ctx.getCurrentPhase().get()).isEqualTo(RoundPhaseEnum.VOTING);
        assertThat(ctx.getCurrentSpeakerOrder().get()).isEqualTo(4);
        verify(gameTurnTimer).scheduleVoteTimeout(any(GameContext.class), anyInt(), any(Runnable.class));
    }

    @Test
    void enterVotingPhase_shouldJudgeAndEndGame_whenAiCompletesLastVote() {
        GameContext ctx = newContext("game-ai-vote");
        GamePlayerInfo aiPlayer = buildAiPlayer(301L, -1L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        GamePlayerInfo spyPlayer = buildHumanPlayer(302L, 2L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        GamePlayerInfo civilianPlayer = buildHumanPlayer(303L, 3L, 3, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, aiPlayer, spyPlayer, civilianPlayer);
        ctx.setCivilianWord("苹果");
        ctx.setSpyWord("梨");
        ctx.startNewRound();
        ctx.getCurrentPhase().set(RoundPhaseEnum.DESCRIBING);
        ctx.castVote(302L, 303L);
        ctx.castVote(303L, 302L);

        AiVoteInput input = AiVoteInput.builder()
                .gameId(ctx.getGameId())
                .aiPlayerId(aiPlayer.getPlayerId())
                .provider(AiProviderEnum.KIMI)
                .persona(AiPersonaEnum.MASTER)
                .role(aiPlayer.getRole())
                .word(aiPlayer.getWord())
                .roundNumber(ctx.getCurrentRound().get())
                .candidates(List.of(
                        AiVoteInput.VoteCandidate.builder().index(1).playerId(302L).nickname("人类二").descriptionsByRound(List.of()).build(),
                        AiVoteInput.VoteCandidate.builder().index(2).playerId(303L).nickname("人类三").descriptionsByRound(List.of()).build()
                ))
                .build();
        when(aiContextAssembler.buildVoteInput(ctx, aiPlayer)).thenReturn(input);
        when(aiPlayerService.generateVoteTarget(input)).thenReturn(302L);
        runSubmittedTasksImmediately();
        runPostVoteDelayImmediately();

        engine.enterVotingPhase(ctx);

        assertThat(ctx.getGameStatus().get()).isEqualTo(GameStatusEnum.ENDED);
        assertThat(ctx.getVotes()).hasSize(3);
        assertThat(ctx.getVotes().get(301L)).isEqualTo(302L);
        assertThat(ctx.isAutoVoter(301L)).isTrue();
        assertThat(spyPlayer.getStatus()).isEqualTo(PlayerStatusEnum.ELIMINATED);
        verify(gamePersistService).persistRoundResult(ctx, 302L, false);
        verify(gamePersistService).persistGameEnd(ctx, WinnerSideEnum.CIVILIAN, EndReasonEnum.NORMAL);
        verify(gameContextManager).remove(ctx.getGameId());
    }

    @Test
    void enterVotingPhase_shouldFallbackToRandomVote_whenAiVoteTaskStalls() {
        GameContext ctx = newContext("game-ai-vote-timeout");
        GamePlayerInfo aiPlayer = buildAiPlayer(311L, -1L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.ALIVE);
        GamePlayerInfo spyPlayer = buildHumanPlayer(312L, 2L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, aiPlayer, spyPlayer);
        ctx.setCivilianWord("苹果");
        ctx.setSpyWord("梨");
        ctx.startNewRound();
        ctx.getCurrentPhase().set(RoundPhaseEnum.DESCRIBING);
        ctx.castVote(312L, 311L);

        AiVoteInput input = AiVoteInput.builder()
                .gameId(ctx.getGameId())
                .aiPlayerId(aiPlayer.getPlayerId())
                .provider(AiProviderEnum.KIMI)
                .persona(AiPersonaEnum.MASTER)
                .role(aiPlayer.getRole())
                .word(aiPlayer.getWord())
                .roundNumber(ctx.getCurrentRound().get())
                .candidates(List.of(
                        AiVoteInput.VoteCandidate.builder().index(1).playerId(312L).nickname("人类二").descriptionsByRound(List.of()).build()
                ))
                .build();
        when(aiContextAssembler.buildVoteInput(ctx, aiPlayer)).thenReturn(input);

        List<Runnable> submittedTasks = new ArrayList<>();
        doAnswer(invocation -> {
            submittedTasks.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(aiPlayerExecutor).submit(any(Runnable.class));

        List<Runnable> detachedTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
            detachedTimeouts.add(invocation.getArgument(1));
            return null;
        }).when(gameTurnTimer).scheduleDetached(anyInt(), any(Runnable.class));

        runPostVoteDelayImmediately();

        engine.enterVotingPhase(ctx);

        assertThat(submittedTasks).hasSize(1);
        assertThat(detachedTimeouts).hasSize(1);

        detachedTimeouts.get(0).run();

        assertThat(ctx.getGameStatus().get()).isEqualTo(GameStatusEnum.ENDED);
        assertThat(ctx.getVotes()).hasSize(2);
        assertThat(ctx.getVotes()).containsKey(311L);
        assertThat(ctx.isAutoVoter(311L)).isTrue();
        verify(gamePersistService).persistRoundResult(ctx, null, true);
        verify(gamePersistService).persistGameEnd(ctx, WinnerSideEnum.SPY, EndReasonEnum.NORMAL);
    }

    @Test
    void handlePlayerDisconnected_shouldAutoVoteAndEndGame_whenVotingVoteIsMissing() {
        GameContext ctx = newContext("game-disconnect-vote");
        GamePlayerInfo disconnectedCivilian = buildHumanPlayer(401L, 1L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.DISCONNECTED);
        GamePlayerInfo spyPlayer = buildHumanPlayer(402L, 2L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, disconnectedCivilian, spyPlayer);
        ctx.setCivilianWord("苹果");
        ctx.setSpyWord("梨");
        ctx.startNewRound();
        ctx.getCurrentPhase().set(RoundPhaseEnum.VOTING);
        ctx.castVote(402L, 401L);
        runPostVoteDelayImmediately();

        engine.handlePlayerDisconnected(ctx, disconnectedCivilian);

        assertThat(ctx.getVotes()).hasSize(2);
        assertThat(ctx.getVotes().get(401L)).isEqualTo(402L);
        assertThat(ctx.isAutoVoter(401L)).isTrue();
        assertThat(ctx.getGameStatus().get()).isEqualTo(GameStatusEnum.ENDED);
        verify(gamePersistService).persistRoundResult(ctx, null, true);
        verify(gamePersistService).persistGameEnd(ctx, WinnerSideEnum.SPY, EndReasonEnum.NORMAL);
    }

    @Test
    void handlePlayerDisconnected_shouldSkipCurrentSpeakerAndAdvance_whenDescribing() {
        GameContext ctx = newContext("game-disconnect-describe");
        GamePlayerInfo disconnectedHuman = buildHumanPlayer(501L, 1L, 1, GameRoleEnum.CIVILIAN, PlayerStatusEnum.DISCONNECTED);
        GamePlayerInfo nextHuman = buildHumanPlayer(502L, 2L, 2, GameRoleEnum.SPY, PlayerStatusEnum.ALIVE);
        preparePlayers(ctx, disconnectedHuman, nextHuman);
        ctx.startNewRound();
        ctx.getCurrentPhase().set(RoundPhaseEnum.DESCRIBING);
        ctx.getCurrentSpeakerOrder().set(1);

        engine.handlePlayerDisconnected(ctx, disconnectedHuman);

        assertThat(ctx.getCurrentSpeakerOrder().get()).isEqualTo(2);
        assertThat(ctx.getCurrentRoundDescriptions()).hasSize(1);
        GameContext.DescriptionRecord record = ctx.getCurrentRoundDescriptions().get(0);
        assertThat(record.getPlayerId()).isEqualTo(501L);
        assertThat(record.isSkipped()).isTrue();
    }

    private void runSubmittedTasksImmediately() {
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return CompletableFuture.completedFuture(null);
        }).when(aiPlayerExecutor).submit(any(Runnable.class));
    }

    private void runPostVoteDelayImmediately() {
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return null;
        }).when(gameTurnTimer).schedulePostVoteDelay(any(GameContext.class), anyInt(), any(Runnable.class));
    }

    private GameContext newContext(String gameId) {
        return new GameContext(gameId, "room-" + gameId, 1L,
                GameConfig.builder()
                        .describeTimeout(30)
                        .voteTimeout(20)
                        .build());
    }

    private void preparePlayers(GameContext ctx, GamePlayerInfo... players) {
        for (GamePlayerInfo player : players) {
            ctx.addPlayer(player.getAccountId());
        }
        ctx.initPlayers(List.of(players));
        ctx.casGameStatus(GameStatusEnum.WAITING, GameStatusEnum.PLAYING);
    }

    private void prepareDescribeRound(GameContext ctx, int currentSpeakerOrder) {
        ctx.startNewRound();
        ctx.getCurrentPhase().set(RoundPhaseEnum.DESCRIBING);
        ctx.setRoundStartOrder(1);
        ctx.getCurrentSpeakerOrder().set(currentSpeakerOrder);
    }

    private GamePlayerInfo buildAiPlayer(Long playerId,
                                         Long accountId,
                                         int order,
                                         GameRoleEnum role,
                                         PlayerStatusEnum status) {
        return GamePlayerInfo.builder()
                .playerId(playerId)
                .accountId(accountId)
                .playerType(PlayerTypeEnum.AI)
                .nickname("AI-" + order)
                .avatar("ai.png")
                .role(role)
                .word(role == GameRoleEnum.SPY ? "梨" : "苹果")
                .playerOrder(order)
                .status(status)
                .aiProvider(AiProviderEnum.KIMI)
                .aiPersona(AiPersonaEnum.CAUTIOUS)
                .build();
    }

    private GamePlayerInfo buildHumanPlayer(Long playerId,
                                            Long accountId,
                                            int order,
                                            GameRoleEnum role,
                                            PlayerStatusEnum status) {
        return GamePlayerInfo.builder()
                .playerId(playerId)
                .accountId(accountId)
                .playerType(PlayerTypeEnum.HUMAN)
                .nickname("人类-" + order)
                .avatar("human.png")
                .role(role)
                .word(role == GameRoleEnum.SPY ? "梨" : "苹果")
                .playerOrder(order)
                .status(status)
                .build();
    }
}
