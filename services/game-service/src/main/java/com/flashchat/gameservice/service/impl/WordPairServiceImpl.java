package com.flashchat.gameservice.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.gameservice.config.GameConfig;
import com.flashchat.gameservice.dao.entity.GameRoomDO;
import com.flashchat.gameservice.dao.entity.WordPairDO;
import com.flashchat.gameservice.dao.mapper.WordPairMapper;
import com.flashchat.gameservice.service.GameRoomService;
import com.flashchat.gameservice.service.WordPairService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 词语对服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordPairServiceImpl extends ServiceImpl<WordPairMapper, WordPairDO> implements WordPairService {

    /**
     * 同一房间最近排除的历史局数
     */
    private static final int RECENT_ROOM_HISTORY_LIMIT = 5;

    /**
     * 游戏房间服务
     */
    private final GameRoomService gameRoomService;

    @Override
    public WordPairDO pickRandomWordPair(String roomId, GameConfig config) {
        // 1. 查该房间最近 5 局已使用的词对 ID
        List<Long> recentUsedWordPairIds = gameRoomService.lambdaQuery()
                .eq(GameRoomDO::getRoomId, roomId)
                .isNotNull(GameRoomDO::getWordPairId)
                .orderByDesc(GameRoomDO::getCreateTime)
                .last("LIMIT " + RECENT_ROOM_HISTORY_LIMIT)
                .list()
                .stream()
                .map(GameRoomDO::getWordPairId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());

        // 2. 先按“排除最近已使用词对”的条件查候选集
        List<WordPairDO> candidates = this.list(buildCandidateQuery(config, recentUsedWordPairIds));

        // 3. 如果过滤后没有可选项，则回退一次：忽略历史排除，只保留分类/难度过滤
        if (candidates.isEmpty() && !recentUsedWordPairIds.isEmpty()) {
            log.warn("[词库抽取-候选为空，忽略房间历史去重重试] roomId={}, excludedIds={}",
                    roomId, recentUsedWordPairIds);
            candidates = this.list(buildCandidateQuery(config, null));
        }
        if (candidates.isEmpty()) {
            throw new ServiceException("当前条件下没有可用词对");
        }
        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        WordPairDO selected = candidates.get(index);

        log.info("[词库抽取成功] roomId={}, wordPairId={}, category={}, difficulty={}",
                roomId, selected.getId(), selected.getCategory(), selected.getDifficulty());

        return selected;
    }

    /**
     * 构建候选词对查询条件
     */
    private LambdaQueryWrapper<WordPairDO> buildCandidateQuery(GameConfig config, List<Long> excludedIds) {
        LambdaQueryWrapper<WordPairDO> wrapper = new LambdaQueryWrapper<>();

        if (config != null) {
            if (StringUtils.hasText(config.getWordCategory())) {
                wrapper.eq(WordPairDO::getCategory, config.getWordCategory().trim());
            }
            if (config.getDifficultyOrDefault() > 0) {
                wrapper.eq(WordPairDO::getDifficulty, config.getDifficultyOrDefault());
            }
        }

        if (excludedIds != null && !excludedIds.isEmpty()) {
            wrapper.notIn(WordPairDO::getId, excludedIds);
        }

        return wrapper;
    }
}