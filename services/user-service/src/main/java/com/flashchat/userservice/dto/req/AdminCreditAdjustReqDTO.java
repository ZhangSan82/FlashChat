package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员手动调整积分请求。
 */
@Data
public class AdminCreditAdjustReqDTO {

    @NotNull(message = "调整数量不能为空")
    @Min(value = 1, message = "调整数量必须大于 0")
    private Integer amount;

    /**
     * 1=增加积分，-1=扣减积分。
     */
    @NotNull(message = "调整方向不能为空")
    private Integer direction;

    @NotBlank(message = "调整原因不能为空")
    @Size(max = 200, message = "调整原因不能超过 200 个字符")
    private String reason;
}
