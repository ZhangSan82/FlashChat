package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.service.RoomService;
import org.springframework.stereotype.Service;

@Service
public class RoomServiceImpl extends ServiceImpl<RoomMapper, RoomDO> implements RoomService {
}
