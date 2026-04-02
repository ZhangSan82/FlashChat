package com.flashchat.gameservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flashchat.gameservice.dao.entity.GameDescriptionDO;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.entity.GameRoomDO;
import com.flashchat.gameservice.dao.entity.GameRoundDO;
import com.flashchat.gameservice.dao.entity.GameVoteDO;
import com.flashchat.gameservice.dao.entity.WordPairDO;
import com.flashchat.gameservice.dao.enums.EndReasonEnum;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.dao.enums.WinnerSideEnum;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GamePlayerInfo;
import com.flashchat.gameservice.service.GameDescriptionService;
import com.flashchat.gameservice.service.GamePlayerService;
import com.flashchat.gameservice.service.GamePersistService;
import com.flashchat.gameservice.service.GameRoomService;
import com.flashchat.gameservice.service.GameRoundService;
import com.flashchat.gameservice.service.GameVoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏数据持久化服务实现。
 * <p>
 * 这里只负责把引擎中的内存快照同步到数据库，
 * 不参与状态机推进，避免玩法逻辑和落库逻辑耦合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamePersistServiceImpl implements GamePersistService {

    private final GameRoomService gameRoomService;
    private final GamePlayerService gamePlayerService;
    private final GameRoundService gameRoundService;
    private final GameDescriptionService gameDescriptionService;
    private final GameVoteService gameVoteService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistGameStart(GameContext ctx, List<GamePlayerInfo> players, WordPairDO wordPair) {
        // 回写房间主表：游戏状态、词对、当前轮次等开局信息
        gameRoomService.update(new LambdaUpdateWrapper<GameRoomDO>()
                .eq(GameRoomDO::getGameId, ctx.getGameId())
                .set(GameRoomDO::getGameStatus, GameStatusEnum.PLAYING.getCode())
                .set(GameRoomDO::getCurrentRound, ctx.getCurrentRound().get())
                .set(GameRoomDO::getWordPairId, wordPair.getId())
                .set(GameRoomDO::getCivilianWord, wordPair.getWordA())
                .set(GameRoomDO::getSpyWord, wordPair.getWordB())
                .set(GameRoomDO::getWinnerSide, null)
                .set(GameRoomDO::getEndReason, null)
                .set(GameRoomDO::getEndTime, null));

        // 批量回写玩家开局后的角色、词语、顺序和状态
        List<GamePlayerDO> playerUpdates = players.stream()
                .map(player -> {
                    GamePlayerDO update = new GamePlayerDO();
                    update.setId(player.getPlayerId());
                    update.setRole(player.getRole() != null ? player.getRole().getCode() : null);
                    update.setWord(player.getWord());
                    update.setStatus(player.getStatus() != null ? player.getStatus().getCode() : null);
                    update.setPlayerOrder(player.getPlayerOrder());
                    return update;
                })
                .toList();
        gamePlayerService.updateBatchById(playerUpdates);

        log.info("[GamePersist] persisted game start, gameId={}, playerCount={}",
                ctx.getGameId(), players.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistRoundResult(GameContext ctx, Long eliminatedPlayerId, boolean isTie) {
        int roundNumber = ctx.getCurrentRound().get();

        // 先落轮次主记录，作为这一轮判定结束的锚点数据
        gameRoundService.save(GameRoundDO.builder()
                .gameId(ctx.getGameId())
                .roundNumber(roundNumber)
                .eliminatedPlayerId(eliminatedPlayerId)
                .isTie(isTie ? 1 : 0)
                .createTime(LocalDateTime.now())
                .build());

        // synchronizedList 在遍历时仍需手动同步，这里先做快照再批量落库
        List<GameContext.DescriptionRecord> descriptionSnapshot;
        synchronized (ctx.getCurrentRoundDescriptions()) {
            descriptionSnapshot = new ArrayList<>(ctx.getCurrentRoundDescriptions());
        }
        if (!descriptionSnapshot.isEmpty()) {
            List<GameDescriptionDO> descriptions = descriptionSnapshot.stream()
                    .map(record -> GameDescriptionDO.builder()
                            .gameId(ctx.getGameId())
                            .roundNumber(roundNumber)
                            .playerId(record.getPlayerId())
                            .content(record.getContent())
                            .isSkipped(record.isSkipped() ? 1 : 0)
                            .submitTime(toLocalDateTime(record.getTimestamp()))
                            .build())
                    .toList();
            gameDescriptionService.saveBatch(descriptions);
        }

        if (!ctx.getVotes().isEmpty()) {
            // MVP 阶段先统一使用落库时间，后续若做精确回放再补真实投票时间戳
            LocalDateTime voteTime = LocalDateTime.now();
            List<GameVoteDO> votes = ctx.getVotes().entrySet().stream()
                    .map(entry -> GameVoteDO.builder()
                            .gameId(ctx.getGameId())
                            .roundNumber(roundNumber)
                            .voterPlayerId(entry.getKey())
                            .targetPlayerId(entry.getValue())
                            .isAuto(ctx.isAutoVoter(entry.getKey()) ? 1 : 0)
                            .voteTime(voteTime)
                            .build())
                    .toList();
            gameVoteService.saveBatch(votes);
        }

        // 同步房间当前轮次，便于查询状态和故障恢复
        gameRoomService.update(new LambdaUpdateWrapper<GameRoomDO>()
                .eq(GameRoomDO::getGameId, ctx.getGameId())
                .set(GameRoomDO::getCurrentRound, roundNumber));

        // 每轮只有被淘汰玩家的状态发生变化，避免无意义的全量更新
        if (!isTie && eliminatedPlayerId != null) {
            GamePlayerInfo eliminated = ctx.getPlayerByPlayerId(eliminatedPlayerId);
            if (eliminated != null && eliminated.getStatus() != null) {
                GamePlayerDO update = new GamePlayerDO();
                update.setId(eliminatedPlayerId);
                update.setStatus(eliminated.getStatus().getCode());
                gamePlayerService.updateById(update);
            }
        }

        log.info("[GamePersist] persisted round result, gameId={}, round={}, descriptions={}, votes={}",
                ctx.getGameId(), roundNumber, descriptionSnapshot.size(), ctx.getVotes().size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistGameEnd(GameContext ctx, WinnerSideEnum winner, EndReasonEnum reason) {
        // 结束时统一回写最终胜负、结束原因和结束时间
        gameRoomService.update(new LambdaUpdateWrapper<GameRoomDO>()
                .eq(GameRoomDO::getGameId, ctx.getGameId())
                .set(GameRoomDO::getGameStatus, GameStatusEnum.ENDED.getCode())
                .set(GameRoomDO::getCurrentRound, ctx.getCurrentRound().get())
                .set(GameRoomDO::getWinnerSide, winner != null ? winner.getCode() : null)
                .set(GameRoomDO::getEndReason, reason != null ? reason.getCode() : null)
                .set(GameRoomDO::getEndTime, LocalDateTime.now()));

        // 游戏结束时全量同步玩家最终状态，保证 DB 能反映最终结果
        List<GamePlayerDO> statusUpdates = ctx.getAllPlayers().stream()
                .map(player -> {
                    GamePlayerDO update = new GamePlayerDO();
                    update.setId(player.getPlayerId());
                    update.setStatus(player.getStatus() != null ? player.getStatus().getCode() : null);
                    return update;
                })
                .toList();
        gamePlayerService.updateBatchById(statusUpdates);
        log.info("[GamePersist] persisted game end, gameId={}, winner={}, reason={}",
                ctx.getGameId(), winner, reason);
    }

    private LocalDateTime toLocalDateTime(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
    }
}
