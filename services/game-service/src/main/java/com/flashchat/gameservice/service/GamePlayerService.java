package com.flashchat.gameservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;

/**
 * 游戏玩家服务。
 * <p>
 * 主要承载 t_game_player 的通用增删改查和批量更新能力，
 * 供房间服务、持久化服务、后续玩法服务统一复用。
 */
public interface GamePlayerService extends IService<GamePlayerDO> {
}
