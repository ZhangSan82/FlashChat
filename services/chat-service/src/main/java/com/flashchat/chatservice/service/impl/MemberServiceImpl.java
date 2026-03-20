package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.DistributedCache;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dao.mapper.MemberMapper;
import com.flashchat.chatservice.dto.resp.MemberInfoRespDTO;
import com.flashchat.chatservice.service.MemberService;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;


@Service
@Slf4j
@AllArgsConstructor
public class MemberServiceImpl extends ServiceImpl<MemberMapper, MemberDO> implements MemberService {

    @Qualifier("flashChatAccountRegisterCachePenetrationBloomFilter")
    private  final RBloomFilter<String> flashChatAccountRegisterCachePenetrationBloomFilter;

    private final DistributedCache distributedCache;
    private static final long CACHE_TIMEOUT = 60000L;


    private static final String[] ADJ = {
            "神秘的", "可爱的", "暴躁的", "温柔的", "沉默的",
            "快乐的", "忧伤的", "勇敢的", "害羞的", "优雅的",
            "调皮的", "冷静的", "热情的", "迷糊的", "机智的"
    };
    private static final String[] NOUN = {
            "猫咪", "兔子", "企鹅", "熊猫", "水母",
            "狐狸", "松鼠", "鹦鹉", "猫头鹰", "海豚",
            "柴犬", "考拉", "树懒", "浣熊", "刺猬"
    };



    @Transactional
    @Override
    public MemberInfoRespDTO autoRegister() {
        // 1. 生成 accountId，确保唯一
        String accountId = generateUniqueAccountId();

        // 2. 生成随机昵称
        String nickname = generateNickname();

        // 3. 生成随机头像色
        String avatarColor = generateAvatarColor();

        // 4. 创建记录
        MemberDO member = MemberDO.builder()
                .accountId(accountId)
                .password("")       // 匿名用户暂不设密码
                .nickname(nickname)
                .avatarColor(avatarColor)
                .status(1)          // 正常状态
                .build();

        try {
            this.save(member);
            flashChatAccountRegisterCachePenetrationBloomFilter.add(accountId);
            flashChatAccountRegisterCachePenetrationBloomFilter.add(String.valueOf(member.getId()));
            distributedCache.put(
                    CacheUtil.buildKey("flashchat","member",accountId),
                    member,
                    CACHE_TIMEOUT
            );

            distributedCache.put(
                    CacheUtil.buildKey("flashchat", "member", "id", String.valueOf(member.getId())),
                    member,
                    CACHE_TIMEOUT
            );

        } catch (Exception e) {
            log.error("注册失败:" + e.getMessage());
            throw new ServiceException(e.getMessage());
        }


        log.info("[匿名注册] id={}, accountId={}, nickname={}",
                member.getId(), accountId, nickname);

        return MemberInfoRespDTO.builder()
                .accountId(accountId)
                .nickname(nickname)
                .avatarColor(avatarColor)
                .build();
    }

    @Override
    public MemberInfoRespDTO getMemberByAccountId(String accountId) {
        MemberDO member = getByAccountId(accountId);


        return MemberInfoRespDTO.builder()
                .accountId(member.getAccountId())
                .nickname(member.getNickname())
                .avatarColor(member.getAvatarColor())
                .build();
    }

    @Override
    public MemberDO getByAccountId(String accountId) {

        MemberDO member = distributedCache.safeGet(
                CacheUtil.buildKey("flashchat","member",accountId),
                MemberDO.class,
                ()->this.lambdaQuery().eq(MemberDO::getAccountId,accountId).one(),
                CACHE_TIMEOUT,
                flashChatAccountRegisterCachePenetrationBloomFilter
        );

        if (member == null) {
            throw new ClientException("账号不存在");
        }
        if (member.getStatus() != null && member.getStatus() == 0) {
            throw new ClientException("账号已被封禁");
        }
        return  member;
    }

    @Override
    public MemberDO getByMemberId(Long memberId) {
        if (memberId == null) {
            return null;
        }
        return distributedCache.safeGet(
                CacheUtil.buildKey("flashchat", "member", "id", String.valueOf(memberId)),
                MemberDO.class,
                () -> this.getById(memberId),
                CACHE_TIMEOUT,
                flashChatAccountRegisterCachePenetrationBloomFilter
        );
    }


    /**
     *生成唯一账号ID
     */
    private String  generateUniqueAccountId(){
        int customGenerateCount = 0;
        String accountId;
        String SEED_PREFIX = "flashchat:AccountId:";
        while (true) {
            if (customGenerateCount > 10)
            {
                throw new ServiceException("房间ID频繁生成,请稍后再试");
            }
            accountId = HashUtil.hashToBase62(SEED_PREFIX + UUID.randomUUID().toString());
            if (!flashChatAccountRegisterCachePenetrationBloomFilter.contains(accountId))
            {
                break;
            }
            customGenerateCount++;
        }
        return "FC-" + accountId ;
    }


    /**
     * 生成随机昵称
     * 格式：形容词 + 动物名
     */
    private String generateNickname() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String adj = ADJ[random.nextInt(ADJ.length)];
        String noun = NOUN[random.nextInt(NOUN.length)];
        return adj + noun;
    }

    /**
     * 生成随机头像背景色
     * 排除太浅的颜色（确保白色文字可读）
     */
    private String generateAvatarColor() {
        String[] colors = {
                "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4",
                "#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F",
                "#BB8FCE", "#85C1E9", "#F0B27A", "#82E0AA",
                "#D35400", "#8E44AD", "#2980B9", "#27AE60"
        };
        return colors[ThreadLocalRandom.current().nextInt(colors.length)];
    }
}
