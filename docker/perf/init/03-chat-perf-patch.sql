USE flashchat;

ALTER TABLE t_room
    ADD COLUMN current_members INT NOT NULL DEFAULT 0 COMMENT '当前成员数' AFTER max_members;

ALTER TABLE t_message
    ADD COLUMN reactions TEXT NULL COMMENT 'emoji reactions JSON' AFTER is_host;
