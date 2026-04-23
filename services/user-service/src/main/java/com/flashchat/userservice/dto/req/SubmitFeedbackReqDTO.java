package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户提交反馈请求。
 */
@Data
public class SubmitFeedbackReqDTO {

    @NotBlank(message = "反馈类型不能为空")
    private String feedbackType;

    @NotBlank(message = "反馈内容不能为空")
    @Size(min = 5, max = 1000, message = "反馈内容长度需在 5-1000 个字符之间")
    private String content;

    @Size(max = 100, message = "联系方式不能超过 100 个字符")
    private String contact;

    @Size(max = 255, message = "截图地址不能超过 255 个字符")
    private String screenshotUrl;

    private Boolean willingContact;

    @Size(max = 64, message = "来源页面不能超过 64 个字符")
    private String sourcePage;

    @Size(max = 64, message = "来源场景不能超过 64 个字符")
    private String sourceScene;
}
