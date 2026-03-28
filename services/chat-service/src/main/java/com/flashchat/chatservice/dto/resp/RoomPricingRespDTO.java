package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 房间时长定价响应
 * <p>
 * 前端创建房间时展示各档位的时长和积分消耗。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomPricingRespDTO {

    /** 档位名称 */
    private String name;

    /** 时长 */
    private Integer minutes;

    /** 展示文案 */
    private String desc;

    /** 所需积分 */
    private Integer cost;
}