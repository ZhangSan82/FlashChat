package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dto.req.AdminAccountQueryReqDTO;
import com.flashchat.userservice.dto.req.AdminCreditAdjustReqDTO;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminAccountRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;

/**
 * 管理员账号管理服务。
 */
public interface AdminAccountService {

    AdminPageRespDTO<AdminAccountRespDTO> searchAccounts(Long operatorId, AdminAccountQueryReqDTO request);

    AdminAccountRespDTO getAccountDetail(Long operatorId, String accountId);

    void banAccount(Long operatorId, String accountId, AdminOperationReasonReqDTO request);

    void unbanAccount(Long operatorId, String accountId, AdminOperationReasonReqDTO request);

    void kickoutAccount(Long operatorId, String accountId, AdminOperationReasonReqDTO request);

    void adjustCredits(Long operatorId, String accountId, AdminCreditAdjustReqDTO request);

    void grantAdmin(Long operatorId, String accountId, AdminOperationReasonReqDTO request);

    void revokeAdmin(Long operatorId, String accountId, AdminOperationReasonReqDTO request);
}
