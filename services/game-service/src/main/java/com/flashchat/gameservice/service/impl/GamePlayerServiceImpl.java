package com.flashchat.gameservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.mapper.GamePlayerMapper;
import com.flashchat.gameservice.service.GamePlayerService;
import org.springframework.stereotype.Service;

/**
 * 游戏玩家服务默认实现。
 * <p>
 * 当前先复用 MyBatis-Plus 的通用 Service 能力，
 * 后续如果有玩家状态批量变更、按 accountId 查询等专属逻辑，
 * 可以继续在这里沉淀。
 */
@Service
public class GamePlayerServiceImpl extends ServiceImpl<GamePlayerMapper, GamePlayerDO>
        implements GamePlayerService {
}
