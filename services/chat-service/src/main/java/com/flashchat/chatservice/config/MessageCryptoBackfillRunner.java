package com.flashchat.chatservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后的可选回填入口。
 *
 * <p>如果部署时希望立刻开始迁移历史数据，而不是等第一次定时调度，就可以打开这条路径。</p>
 */
@Component
@RequiredArgsConstructor
public class MessageCryptoBackfillRunner implements ApplicationRunner {

    private final MessageCryptoProperties messageCryptoProperties;
    private final MessageCryptoBackfillJob messageCryptoBackfillJob;

    @Override
    public void run(ApplicationArguments args) {
        if (!messageCryptoProperties.getBackfill().isRunOnStartup()) {
            return;
        }
        messageCryptoBackfillJob.runOnce("startup");
    }
}
