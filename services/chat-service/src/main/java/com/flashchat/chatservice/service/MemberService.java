package com.flashchat.chatservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dto.resp.MemberInfoRespDTO;

public interface MemberService extends IService<MemberDO> {

    /**
     *用户自动注册
     */
    MemberInfoRespDTO autoRegister();


    /**
     * 通过账号ID查询用户信息
     */
    MemberInfoRespDTO getMemberByAccountId(String accountId);

    MemberDO getByAccountId(String accountId);

    /**
     * 通过数据库主键 ID 查询（带缓存）
     */
    MemberDO getByMemberId(Long memberId);
}
