package com.flashchat.gameservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.gameservice.dao.entity.GameDescriptionDO;
import com.flashchat.gameservice.dao.mapper.GameDescriptionMapper;
import com.flashchat.gameservice.service.GameDescriptionService;
import org.springframework.stereotype.Service;

@Service
public class GameDescriptionServiceImpl extends ServiceImpl<GameDescriptionMapper, GameDescriptionDO>
        implements GameDescriptionService {
}
