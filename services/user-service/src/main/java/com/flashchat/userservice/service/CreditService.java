package com.flashchat.userservice.service;

import com.flashchat.userservice.dao.enums.CreditTypeEnum;

/**
 * 积分服务
 * <p>
 * 核心设计：
 * <ol>
 *   <li>每次积分变动先插流水（幂等键唯一索引兜底），再 CAS 更新余额</li>
 *   <li>grantCredits 和 deductCredits 的 amount 参数都传正数，内部处理正负号</li>
 *   <li>幂等键由内部组装：{type.name()}:{bizId}，调用方不需要关心格式</li>
 * </ol>
 */
public interface CreditService {

    /**
     * 增加积分（注册奖励、邀请奖励等）
     * <p>
     * 幂等行为：相同 type + bizId 的第二次调用静默返回 false（不抛异常、不重复增加）。
     * 调用方可通过返回值区分"首次执行"和"幂等跳过"，用于日志区分。
     */
    boolean grantCredits(Long accountId, int amount, CreditTypeEnum type,
                         String bizId, String remark);

    /**
     * 扣减积分（创建房间等）
     * 幂等行为：相同 type + bizId 的第二次调用静默返回（不抛异常、不重复扣减）。
     */
    void deductCredits(Long accountId, int amount, CreditTypeEnum type,
                       String bizId, String remark);

    /**
     * 查询积分余额
     */
    int getBalance(Long accountId);
}