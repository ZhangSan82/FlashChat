package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.mapper.RoomMemberMapper;
import com.flashchat.chatservice.service.RoomMemberService;
import org.springframework.stereotype.Service;

@Service
public class RoomMemberServiceImpl extends ServiceImpl< RoomMemberMapper,RoomMemberDO> implements RoomMemberService {
}
