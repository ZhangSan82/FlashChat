
-- ============================================================
-- 1. 注册用户表 (t_user)
--
-- 通过邀请码注册的主持人，上限100人（可配置）
-- 可创建房间、管理房间成员、加入他人房间
-- 房间内显示 username
-- ============================================================
CREATE TABLE t_user (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    username        VARCHAR(32)     NOT NULL                 COMMENT '用户名（房间内显示名）',
    password        VARCHAR(128)    NOT NULL                 COMMENT '密码（BCrypt加密）',
    nickname        VARCHAR(32)     NOT NULL DEFAULT ''      COMMENT '昵称（预留，暂与username一致）',
    avatar_url      VARCHAR(256)    NOT NULL DEFAULT ''      COMMENT '头像URL',
    invite_code     VARCHAR(16)     NOT NULL                 COMMENT '自己的邀请码（注册时系统生成）',
    invited_by      BIGINT          NULL                     COMMENT '邀请人用户ID（关系链追踪）',
    credits         INT             NOT NULL DEFAULT 0       COMMENT '积分余额',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '0-禁用 1-正常',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag        TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_invite_code (invite_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注册用户表（主持人）';


-- ============================================================
-- 3. 匿名成员表 (t_member)
--
-- 匿名用户的持久身份，不是一次性会话
-- 首次使用：系统生成 account_id（FC-8A3D7K格式）+ 随机昵称
-- 密码可选：未设密码时 password 为空字符串，跨设备只输account_id登录
-- 支持多设备同时在线（同一member_id多个WebSocket连接）
-- 所有房间共用同一昵称，改名只更新此表
-- account_id只有用户自己可见
-- ============================================================
create table t_member
(
    id               bigint auto_increment comment '主键'
        primary key,
    account_id       varchar(16)                            not null comment '全局唯一账号ID（如FC-8A3D7K，展示给用户本人）',
    password         varchar(128) default ''                not null comment '密码（BCrypt），空字符串=未设置',
    nickname         varchar(32)                            not null comment '昵称（首次=随机生成，全局统一，可修改）',
    avatar_color     varchar(16)  default ''                not null comment '头像背景色（如 #FF6B6B）',
    last_active_time datetime                               null comment '最近活跃时间',
    status           tinyint      default 1                 not null comment '0-封禁 1-正常',
    create_time      datetime     default CURRENT_TIMESTAMP not null,
    update_time      datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint uk_account_id
        unique (account_id)
)
    comment '匿名成员表（持久身份）' charset = utf8mb4;



-- ============================================================
-- 4. 房间表 (t_room)
--
-- 房间生命周期：等待中(0) → 活跃(1) → 即将到期(2) → 宽限期(3) → 已关闭(4)
-- 公开房间展示在房间列表，私密房间只能扫码/链接进入
-- 只有注册用户能创建房间
-- ============================================================
create table t_room
(
    id             bigint auto_increment comment '主键'
        primary key,
    room_id        varchar(32)                            not null comment '房间业务ID（URL/QR码中使用）',
    creator_id     bigint                                 not null comment '创建者用户ID（t_user.id）',
    title          varchar(64)  default ''                not null comment '房间标题',
    max_members    int          default 50                not null comment '最大人数',
    is_public      tinyint(1)   default 0                 not null comment '0-私密（仅扫码/链接） 1-公开（展示在房间列表）',
    status         tinyint      default 0                 not null comment '0-等待中 1-活跃 2-即将到期 3-宽限期 4-已关闭',
    qr_url         varchar(256) default ''                not null comment '二维码URL',
    expire_time    datetime                               null comment '预计到期时间',
    expire_version int          default 1                 not null comment '到期版本号，每次延期+1',
    grace_end_time datetime                               null comment '宽限期结束时间',
    closed_time    datetime                               null comment '实际关闭时间',
    create_time    datetime     default CURRENT_TIMESTAMP not null,
    update_time    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    del_flag       tinyint(1)   default 0                 not null comment '逻辑删除',
    constraint uk_room_id
        unique (room_id)
)
    comment '房间表' charset = utf8mb4;

create index idx_creator_id
    on t_room (creator_id);

create index idx_expire_time
    on t_room (expire_time);

create index idx_status_expire
    on t_room (status, expire_time);

create index idx_status_public
    on t_room (status, is_public);


-- ============================================================
-- 5. 房间成员关系表 (t_room_member)
--
-- 核心多对多关系表：谁在哪个房间
-- user_id 和 member_id 二选一有值：
--   注册用户进房间 → user_id 有值，member_id 为 NULL
--   匿名成员进房间 → member_id 有值，user_id 为 NULL
--
-- last_ack_msg_id：该成员在该房间最后确认的消息自增ID
--   用于离线消息计算：SELECT * FROM t_message WHERE room_id=? AND id > last_ack_msg_id
--   每次用户读到新消息时更新此值
--
-- status 控制访问权限：
--   1-正常：可收发消息、可拉取离线消息
--   2-主动离开：不再接收消息
--   3-被踢出：不再接收消息
-- ============================================================
CREATE TABLE t_room_member (
                               id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
                               room_id         VARCHAR(32)     NOT NULL                 COMMENT '房间业务ID',
                               user_id         BIGINT          NULL                     COMMENT '注册用户ID（与member_id互斥）',
                               member_id       BIGINT          NULL                     COMMENT '匿名成员ID（与user_id互斥）',
                               role            TINYINT         NOT NULL DEFAULT 0       COMMENT '0-普通成员 1-房主',
                               is_muted        TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '是否被禁言',
                               status          TINYINT         NOT NULL DEFAULT 1       COMMENT '1-正常 2-主动离开 3-被踢出',
                               last_ack_msg_id BIGINT          NOT NULL DEFAULT 0       COMMENT '最后确认的消息ID（离线消息偏移量）',
                               join_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
                               leave_time      DATETIME        NULL                     COMMENT '离开时间',
                               PRIMARY KEY (id),
                               UNIQUE KEY uk_room_user (room_id, user_id),
                               UNIQUE KEY uk_room_member (room_id, member_id),
                               KEY idx_user_id (user_id),
                               KEY idx_member_id (member_id),
                               KEY idx_room_status (room_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房间成员关系表';


-- ============================================================
-- 6. 聊天消息表 (t_message)
--
-- 自增主键 id 同时作为离线消息的偏移量（比时间戳更精确）
-- 房间存活期间消息一直保留，房间关闭后由定时任务清理
--
-- 冗余 nickname/avatar_color：
--   写入时记录当时的值（历史快照）
--   前端不直接使用这些字段展示，而是用成员列表的最新值覆盖
--   改昵称时不需要批量更新消息表
--
-- sender_user_id/sender_member_id 二选一：
--   注册用户发的消息 → sender_user_id 有值
--   匿名成员发的消息 → sender_member_id 有值
-- ============================================================
create table t_message
(
    id               bigint auto_increment comment '主键（自增，兼做离线消息偏移量）'
        primary key,
    msg_id           varchar(32)                           not null comment '消息业务ID（UUID去横线）',
    room_id          varchar(32)                           not null comment '房间业务ID',
    sender_user_id   bigint                                null comment '发送者注册用户ID（与sender_member_id互斥）',
    sender_member_id bigint                                null comment '发送者匿名成员ID（与sender_user_id互斥）',
    nickname         varchar(32)                           not null comment '发送时昵称（冗余快照，前端用最新值覆盖）',
    avatar_color     varchar(16) default ''                not null comment '发送时头像色（冗余快照）',
    content          varchar(1024)                         not null comment '消息内容',
    body             text                                  null comment '消息体JSON（files数组格式，文本消息为NULL）',
    reply_msg_id     bigint                                null comment '引用回复的消息ID（NULL=非回复消息）',
    msg_type         tinyint     default 1                 not null comment '1-文本 2-系统消息 3-游戏消息',
    status           tinyint     default 0                 not null comment '0-正常 1-已撤回 2-AI审核拦截',
    is_host          tinyint(1)  default 0                 not null comment '发送者是否为房主',
    create_time      datetime    default CURRENT_TIMESTAMP not null comment '发送时间',
    constraint uk_msg_id
        unique (msg_id)
)
    comment '聊天消息表' charset = utf8mb4;

create index idx_room_id
    on t_message (room_id, id);
