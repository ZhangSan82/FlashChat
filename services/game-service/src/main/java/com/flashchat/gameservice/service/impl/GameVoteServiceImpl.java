package com.flashchat.gameservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.gameservice.dao.entity.GameVoteDO;
import com.flashchat.gameservice.dao.mapper.GameVoteMapper;
import com.flashchat.gameservice.service.GameVoteService;
import org.springframework.stereotype.Service;

@Service
public class GameVoteServiceImpl extends ServiceImpl<GameVoteMapper, GameVoteDO>
        implements GameVoteService {
}