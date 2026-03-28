package com.flashchat.userservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.userservice.dao.entity.InviteCodeDO;
import com.flashchat.userservice.dto.resp.InviteCodeRespDTO;


import java.util.List;

public interface InviteCodeService extends IService<InviteCodeDO> {

    /**
     * 为用户生成邀请码
     */
    void generateForUser(Long accountId, int count);

    /**
     * 使用邀请码（升级时调用）
     */
    Long useCode(String code, Long usedByAccountId);

    /**
     * 查询我的邀请码列表
     */
    List<InviteCodeRespDTO> listMyCodes(Long accountId);
}