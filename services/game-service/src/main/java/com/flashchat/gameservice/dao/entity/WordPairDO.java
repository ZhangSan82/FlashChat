package com.flashchat.gameservice.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 词语对
 * <p>
 * 对应表：t_word_pair
 * <p>
 * 每对词语包含一个平民词和一个卧底词，两者含义相近但有差异。
 * 游戏开始时随机抽取一对，平民拿 word_a，卧底拿 word_b。
 * <p>
 * 按 category 和 difficulty 分类，支持创建游戏时指定分类和难度。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_word_pair")
public class WordPairDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平民词 */
    private String wordA;

    /** 卧底词 */
    private String wordB;

    /** 分类 */
    private String category;

    /** 难度 1-3（1=简单 2=中等 3=困难） */
    private Integer difficulty;

    /** 创建时间 */
    private LocalDateTime createTime;
}
