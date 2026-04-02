package com.flashchat.gameservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flashchat.gameservice.dao.entity.*;
import com.flashchat.gameservice.dao.enums.EndReasonEnum;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.dao.enums.PlayerStatusEnum;
import com.flashchat.gameservice.dao.enums.WinnerSideEnum;
import com.flashchat.gameservice.dao.mapper.*;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GamePlayerInfo;
import com.flashchat.gameservice.service.GamePersistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
/**
 * 游戏数据持久化服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamePersistServiceImpl implements GamePersistService {


}
