ALTER TABLE t_message
    MODIFY COLUMN content TEXT NULL COMMENT '消息内容（兼容旧数据/未加密数据）',
    ADD COLUMN content_cipher TEXT NULL COMMENT '加密后的消息正文(Base64)' AFTER content,
    ADD COLUMN content_iv VARCHAR(64) NULL COMMENT '消息正文随机IV(Base64)' AFTER content_cipher,
    ADD COLUMN key_version INT NULL COMMENT '消息正文加密密钥版本' AFTER content_iv;
