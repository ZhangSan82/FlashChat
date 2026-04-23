package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MsgHandlerFactoryTest {

    @Test
    void getHandlerShouldRejectUnsupportedAudioFiles() {
        FileDTO audio = FileDTO.builder()
                .name("voice.mp3")
                .type("audio/mpeg")
                .url("https://example.com/voice.mp3")
                .size(1024L)
                .build();

        assertThatThrownBy(() -> MsgHandlerFactory.getHandler(List.of(audio)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("仅支持图片、视频和压缩包");
    }

    @Test
    void getHandlerShouldRouteVideoFilesToVideoHandler() {
        VideoMsgHandler videoHandler = new VideoMsgHandler();
        MsgHandlerFactory.register(MessageTypeEnum.VIDEO.getType(), videoHandler);

        FileDTO video = FileDTO.builder()
                .name("clip.mp4")
                .type("video/mp4")
                .url("https://example.com/clip.mp4")
                .size(2048L)
                .build();

        AbstractMsgHandler handler = MsgHandlerFactory.getHandler(List.of(video));

        assertThat(handler).isSameAs(videoHandler);
    }
}
