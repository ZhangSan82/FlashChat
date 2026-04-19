package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 管理员账号查询参数。
 */
@Data
public class AdminAccountQueryReqDTO {

    /**
     * 支持按 accountId、昵称、邮箱模糊搜索。
     */
    private String keyword;

    /**
     * 账号状态筛选：0=封禁，1=正常。
     */
    private Integer status;

    /**
     * 系统角色筛选：0=普通用户，1=系统管理员。
     */
    private Integer systemRole;

    @Min(1)
    private Long page = 1L;

    @Min(1)
    @Max(100)
    private Long size = 20L;
}
