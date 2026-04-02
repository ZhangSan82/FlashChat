package com.flashchat.gameservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.gameservice.dao.entity.GameRoundDO;
import com.flashchat.gameservice.dao.mapper.GameRoundMapper;
import com.flashchat.gameservice.service.GameRoundService;
import org.springframework.stereotype.Service;

@Service
public class GameRoundServiceImpl extends ServiceImpl<GameRoundMapper, GameRoundDO>
        implements GameRoundService {
}