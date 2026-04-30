package com.flashchat.userservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dto.req.ChangePasswordReqDTO;
import com.flashchat.userservice.dto.req.SetPasswordReqDTO;
import com.flashchat.userservice.dto.req.UpdateProfileReqDTO;
import com.flashchat.userservice.dto.req.UpgradeAccountReqDTO;
import com.flashchat.userservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.userservice.dto.resp.AuthRespDTO;
import com.flashchat.userservice.dto.resp.MyAccountRespDTO;


public interface AccountService extends IService<AccountDO> {


    /**
     * 匿名注册 + 自动登录（一站式）
     * <p>
     * 内部编排：创建账号（事务）→ 写缓存 → SaToken 登录
     * Controller 只需调这一个方法。
     */
    AuthRespDTO autoRegister();

    /**
     * 执行 SaToken 登录
     * @param account  账号实体
     * @param userType 用户类型，取值见 UserTypeConstant
     * @return 带 token 的认证响应
     */
    AuthRespDTO doLogin(AccountDO account, int userType);

    /**
     * 登出当前用户
     */
    void doLogout();

    /**
     * 通过业务账号 ID（FC-XXXXXX）查询信息
     */
    AccountInfoRespDTO getAccountInfoByAccountId(String accountId);

    /**
     * 通过业务账号 ID（FC-XXXXXX）查询完整实体（带缓存）
     */
    AccountDO getByAccountId(String accountId);

    /**
     * 通过邮箱查询账号完整实体
     * <p>
     * 用于邮箱登录场景。未命中抛 ClientException，封禁账号直接拒绝。
     */
    AccountDO getByEmail(String email);

    /**
     * 通过数据库主键 ID 查询（带缓存）
     */
    AccountDO getAccountByDbId(Long id);

    /**
     * 修改个人资料
     */
    void updateProfile(UpdateProfileReqDTO request);

    /**
     * 获取当前登录用户的完整信息
     */
    MyAccountRespDTO getMyAccount();

    /**
     * 首次设置密码（hasPassword=false 的用户）
     */
    void setPassword(SetPasswordReqDTO request);

    /**
     * 修改密码（hasPassword=true 的用户）
     */
    void changePassword(ChangePasswordReqDTO request);

    /**
     * 匿名用户升级为注册用户
     * 升级后 SaToken loginId 从 member_{id} 变为 user_{id}，返回新 token。
     */
    AuthRespDTO upgradeAccount(UpgradeAccountReqDTO request);

    /**
     * 注销账号
     */
    void deleteAccount();

    /**
     * 失效账号缓存（供 CreditService 等跨 Service 调用）
     */
    void evictCacheByDbId(Long dbId);

    /**
     * 每日签到
     */
    boolean dailyCheckIn();

}
