-- ============================================================
-- FlashChat 数据库 Schema V4（统一身份体系）
--
-- 核心变更：
--   t_member + t_user → t_account（统一身份表）
--   t_room_member / t_message 去掉互斥双外键，统一用 account_id
--
-- 设计原则：
--   1. 所有用户从出生到死亡只有一个身份，一个 account_id，一张表
--   2. "注册"不是创建新身份，而是给现有身份绑定邮箱和密码（UPDATE，非 INSERT）
--   3. 匿名升级为注册用户时，房间、消息、成员关系零迁移
--
-- 表清单（4 张）：
--   t_account      统一账号表
--   t_room         房间表
--   t_room_member  房间成员关系表
--   t_message      聊天消息表
--
-- 辅助表（后续按需创建）：
--   t_invite_code  邀请码表（每个注册用户可拥有多个邀请码）
-- ============================================================


-- ============================================================
-- 1. 统一账号表 (t_account)
--
-- 替代原 t_member + t_user，所有用户共用一张表
--
-- 生命周期：
--   匿名阶段：扫码进入 → 自动 INSERT（account_id + 随机昵称 + 随机头像色）
--             email/invite_code/invited_by 全部为 NULL，is_registered=0
--             可选：设置密码（password 字段从空串变为 BCrypt 值）
--
--   注册升级：拿到邀请码 → UPDATE 同一条记录
--             填入 email、password、invite_code，is_registered 改为 1
--             account_id 和 id 不变，所有关联数据零迁移
--
-- 登录方式：
--   匿名用户：account_id（+ 可选密码）
--   注册用户：email + password
--
-- 显示名称：
--   nickname 是唯一的显示名，所有人都有，不要求唯一
--   匿名用户首次随机生成（如"神秘的猫咪"），可随时修改
--   注册后 nickname 不变（用户愿意改可以改）
-- ============================================================
CREATE TABLE t_account (
                           id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                           account_id        VARCHAR(16)  NOT NULL                COMMENT '全局唯一账号ID（FC-XXXXXX格式）',
                           nickname          VARCHAR(32)  NOT NULL                COMMENT '显示名称（首次=随机生成，可修改，不要求唯一）',
                           avatar_color      VARCHAR(16)  NOT NULL DEFAULT ''     COMMENT '头像背景色（如 #FF6B6B，纯色背景+昵称首字）',
                           avatar_url        VARCHAR(256) NOT NULL DEFAULT ''     COMMENT '头像URL（注册后可上传自定义头像）',
                           password          VARCHAR(128) NOT NULL DEFAULT ''     COMMENT '密码（BCrypt加密），空字符串=未设置密码',

    -- 注册信息（注册后才有值，匿名用户全部为 NULL）
                           email             VARCHAR(128)     NULL DEFAULT NULL   COMMENT '邮箱（注册时绑定，用于登录）',
                           invite_code       VARCHAR(16)      NULL DEFAULT NULL   COMMENT '自己的邀请码（注册时系统生成）',
                           invited_by        BIGINT           NULL DEFAULT NULL   COMMENT '邀请人账号ID（t_account.id，关系链追踪）',
                           credits           INT          NOT NULL DEFAULT 0      COMMENT '积分余额（邀请奖励等）',

    -- 状态
                           is_registered     TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否已注册：0=匿名成员 1=已注册用户',
                           status            TINYINT      NOT NULL DEFAULT 1      COMMENT '账号状态：0=封禁 1=正常',
                           last_active_time  DATETIME         NULL                COMMENT '最近活跃时间',
                           create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           del_flag          TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',

                           PRIMARY KEY (id),
                           UNIQUE KEY uk_account_id  (account_id),
                           UNIQUE KEY uk_email       (email),
                           UNIQUE KEY uk_invite_code (invite_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号表（统一身份）';


-- ============================================================
-- 2. 房间表 (t_room)
--
-- 与 V3 相比唯一变化：creator_id 语义从 t_user.id 变为 t_account.id
--
-- 房间生命周期：等待中(0) → 活跃(1) → 即将到期(2) → 宽限期(3) → 已关闭(4)
-- 公开房间展示在房间列表，私密房间只能扫码/链接进入
-- 只有已注册用户（is_registered=1）能创建房间
-- expire_time=NULL 表示永不过期（广场房间）
-- ============================================================
CREATE TABLE t_room (
                        id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                        room_id         VARCHAR(32)  NOT NULL                COMMENT '房间业务ID（URL/QR码中使用）',
                        creator_id      BIGINT       NOT NULL                COMMENT '创建者（t_account.id）',
                        title           VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '房间标题',
                        max_members     INT          NOT NULL DEFAULT 50     COMMENT '最大人数',
                        is_public       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=私密（仅扫码/链接） 1=公开（展示在房间列表）',
                        status          TINYINT      NOT NULL DEFAULT 0      COMMENT '0=等待中 1=活跃 2=即将到期 3=宽限期 4=已关闭',
                        qr_url          VARCHAR(256) NOT NULL DEFAULT ''     COMMENT '二维码URL',
                        expire_time     DATETIME         NULL                COMMENT '预计到期时间（NULL=永不过期）',
                        expire_version  INT          NOT NULL DEFAULT 1      COMMENT '到期版本号（每次延期+1，防止延时队列重复消费）',
                        grace_end_time  DATETIME         NULL                COMMENT '宽限期结束时间',
                        closed_time     DATETIME         NULL                COMMENT '实际关闭时间',
                        create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        del_flag        TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',

                        PRIMARY KEY (id),
                        UNIQUE KEY uk_room_id      (room_id),
                        KEY idx_creator_id         (creator_id),
                        KEY idx_expire_time        (expire_time),
                        KEY idx_status_expire      (status, expire_time),
                        KEY idx_status_public      (status, is_public)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房间表';


-- ============================================================
-- 3. 房间成员关系表 (t_room_member)
--
-- 与 V3 相比核心变化：
--   user_id + member_id（互斥双外键）→ account_id（统一外键）
--   所有 if-else 分支消失，一个 account_id 关联所有人
--
-- last_ack_msg_id：该成员在该房间最后确认的消息自增ID
--   离线消息计算：SELECT * FROM t_message WHERE room_id=? AND id > last_ack_msg_id
--   每次用户读到新消息时更新此值
--
-- status 控制访问权限：
--   1=正常：可收发消息
--   2=主动离开：不再接收消息，可重新加入
--   3=被踢出：不再接收消息
-- ============================================================
CREATE TABLE t_room_member (
                               id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
                               room_id         VARCHAR(32) NOT NULL                COMMENT '房间业务ID（t_room.room_id）',
                               account_id      BIGINT      NOT NULL                COMMENT '账号ID（t_account.id）',
                               role            TINYINT     NOT NULL DEFAULT 0      COMMENT '角色：0=普通成员 1=房主',
                               is_muted        TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '是否被禁言（每个房间独立）',
                               status          TINYINT     NOT NULL DEFAULT 1      COMMENT '状态：1=正常 2=主动离开 3=被踢出',
                               last_ack_msg_id BIGINT      NOT NULL DEFAULT 0      COMMENT '最后确认的消息ID（离线消息偏移量）',
                               join_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
                               leave_time      DATETIME        NULL                COMMENT '离开时间（status≠1时有值）',

                               PRIMARY KEY (id),
                               UNIQUE KEY uk_room_account (room_id, account_id),
                               KEY idx_account_id         (account_id),
                               KEY idx_room_status        (room_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房间成员关系表';


-- ============================================================
-- 4. 聊天消息表 (t_message)
--
-- 与 V3 相比核心变化：
--   sender_user_id + sender_member_id（互斥双外键）→ sender_id（统一外键）
--
-- 自增主键 id 同时作为离线消息的偏移量（比时间戳更精确）
-- 房间存活期间消息一直保留，房间关闭后由定时任务清理
--
-- 冗余 nickname / avatar_color：
--   写入时记录当时的值（历史快照）
--   前端不直接使用这些字段展示，而是用成员列表的最新值覆盖
--   改昵称时不需要批量更新消息表
-- ============================================================
CREATE TABLE t_message (
                           id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键（自增，兼做离线消息偏移量）',
                           msg_id          VARCHAR(32)   NOT NULL                COMMENT '消息业务ID（UUID去横线）',
                           room_id         VARCHAR(32)   NOT NULL                COMMENT '房间业务ID（t_room.room_id）',
                           sender_id       BIGINT        NOT NULL                COMMENT '发送者（t_account.id）',
                           nickname        VARCHAR(32)   NOT NULL                COMMENT '发送时昵称（冗余快照，前端用最新值覆盖）',
                           avatar_color    VARCHAR(16)   NOT NULL DEFAULT ''     COMMENT '发送时头像色（冗余快照）',
                           content         VARCHAR(1024) NOT NULL                COMMENT '消息内容',
                           body            TEXT              NULL                COMMENT '消息体JSON（files数组格式，文本消息为NULL）',
                           reply_msg_id    BIGINT            NULL                COMMENT '引用回复的消息ID（NULL=非回复消息）',
                           msg_type        TINYINT       NOT NULL DEFAULT 1      COMMENT '消息类型：1=文本 2=系统消息 3=游戏消息',
                           status          TINYINT       NOT NULL DEFAULT 0      COMMENT '消息状态：0=正常 1=已撤回 2=AI审核拦截',
                           is_host         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '发送者是否为房主（冗余，用于前端展示房主标识）',
                           create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',

                           PRIMARY KEY (id),
                           UNIQUE KEY uk_msg_id   (msg_id),
                           KEY idx_room_id        (room_id, id),
                           KEY idx_sender_id      (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';


-- ============================================================
-- 5. 邀请码表 (t_invite_code)（后续 Phase 7 创建）
--
-- 每个注册用户可拥有多个邀请码（默认 3 个）
-- 系统初始化时预置 5 个种子邀请码（无 owner）
--
CREATE TABLE t_invite_code (
                               id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                               code        VARCHAR(16) NOT NULL COMMENT '邀请码',
                               owner_id    BIGINT          NULL COMMENT '持有者（t_account.id，种子码为 NULL）',
                               used_by     BIGINT          NULL COMMENT '使用者（t_account.id）',
                               status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=未使用 1=已使用',
                               create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               UNIQUE KEY uk_code (code),
                               KEY idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请码表';
-- ============================================================