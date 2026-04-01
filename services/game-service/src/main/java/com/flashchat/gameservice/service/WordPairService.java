package com.flashchat.gameservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.gameservice.config.GameConfig;
import com.flashchat.gameservice.dao.entity.WordPairDO;

public interface WordPairService  extends IService<WordPairDO> {

    /**
     * 随机抽取一对词语
     */
    WordPairDO pickRandomWordPair(String roomId, GameConfig config);
}