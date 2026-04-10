package com.flashchat.chatservice.service.persist;

public record PersistResult(String msgId, Long msgSeqId, PersistMode acceptedBy) {

    public static PersistResult acceptedByStream(String msgId, Long msgSeqId) {
        return new PersistResult(msgId, msgSeqId, PersistMode.STREAM);
    }

    public static PersistResult acceptedByDb(String msgId, Long msgSeqId) {
        return new PersistResult(msgId, msgSeqId, PersistMode.DB);
    }

    public enum PersistMode {
        STREAM,
        DB
    }
}
