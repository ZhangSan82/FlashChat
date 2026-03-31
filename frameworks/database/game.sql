
-- 1. 词语对库
CREATE TABLE IF NOT EXISTS t_word_pair (
                                           id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           word_a      VARCHAR(50)  NOT NULL COMMENT '平民词',
    word_b      VARCHAR(50)  NOT NULL COMMENT '卧底词',
    category    VARCHAR(30)  NOT NULL COMMENT '分类',
    difficulty  TINYINT      DEFAULT 1 COMMENT '难度 1-3',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='词语对库';

-- 2. 游戏会话
CREATE TABLE IF NOT EXISTS t_game_room (
                                           id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           game_id         VARCHAR(32)  NOT NULL COMMENT '游戏业务ID',
    room_id         VARCHAR(32)  NOT NULL COMMENT '所属聊天房间',
    game_type       VARCHAR(20)  NOT NULL DEFAULT 'WHO_IS_SPY' COMMENT '游戏类型',
    game_status     VARCHAR(20)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/PLAYING/ENDED',
    current_round   INT          DEFAULT 0 COMMENT '当前轮次',
    word_pair_id    BIGINT       NULL COMMENT '本局词对ID',
    civilian_word   VARCHAR(50)  NULL COMMENT '平民词',
    spy_word        VARCHAR(50)  NULL COMMENT '卧底词',
    config          JSON         NULL COMMENT '游戏配置JSON',
    winner_side     VARCHAR(20)  NULL COMMENT '胜利方 CIVILIAN/SPY',
    end_reason      VARCHAR(30)  NULL COMMENT '结束原因 NORMAL/ROOM_EXPIRED/ALL_DISCONNECTED/CANCELLED',
    creator_id      BIGINT       NOT NULL COMMENT '创建者',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    end_time        DATETIME     NULL,
    UNIQUE KEY uk_game_id (game_id),
    INDEX idx_room_id (room_id),
    INDEX idx_status (game_status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏会话';

-- 3. 游戏玩家
CREATE TABLE IF NOT EXISTS t_game_player (
                                             id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             game_id         VARCHAR(32)  NOT NULL COMMENT '游戏ID',
    account_id      BIGINT       NOT NULL COMMENT '真人=正数 AI=负数',
    player_type     VARCHAR(10)  NOT NULL DEFAULT 'HUMAN' COMMENT 'HUMAN/AI',
    ai_provider     VARCHAR(20)  NULL COMMENT 'AI厂商',
    ai_persona      VARCHAR(30)  NULL COMMENT 'AI性格',
    nickname        VARCHAR(50)  NOT NULL COMMENT '显示名',
    avatar          VARCHAR(200) NULL COMMENT '头像',
    role            VARCHAR(15)  NULL COMMENT 'CIVILIAN/SPY/BLANK',
    word            VARCHAR(50)  NULL COMMENT '分配词语',
    status          VARCHAR(15)  NOT NULL DEFAULT 'ALIVE' COMMENT 'ALIVE/ELIMINATED/DISCONNECTED',
    player_order    INT          NULL COMMENT '发言顺序',
    join_time       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_game_id (game_id),
    UNIQUE KEY uk_game_account (game_id, account_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏玩家';

-- 4. 轮次记录
CREATE TABLE IF NOT EXISTS t_game_round (
                                            id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            game_id              VARCHAR(32) NOT NULL,
    round_number         INT         NOT NULL,
    eliminated_player_id BIGINT      NULL COMMENT '被淘汰者',
    is_tie               TINYINT     DEFAULT 0 COMMENT '是否平票',
    create_time          DATETIME    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_game_round (game_id, round_number)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轮次记录';

-- 5. 发言记录
CREATE TABLE IF NOT EXISTS t_game_description (
                                                  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                  game_id         VARCHAR(32) NOT NULL,
    round_number    INT         NOT NULL,
    player_id       BIGINT      NOT NULL COMMENT 't_game_player.id',
    content         TEXT        NULL COMMENT '发言内容',
    is_skipped      TINYINT     DEFAULT 0 COMMENT '是否跳过',
    submit_time     DATETIME    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_game_desc (game_id, round_number)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发言记录';

-- 6. 投票记录
CREATE TABLE IF NOT EXISTS t_game_vote (
                                           id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           game_id          VARCHAR(32) NOT NULL,
    round_number     INT         NOT NULL,
    voter_player_id  BIGINT      NOT NULL COMMENT '投票者',
    target_player_id BIGINT      NOT NULL COMMENT '被投目标',
    is_auto          TINYINT     DEFAULT 0 COMMENT '是否自动投票',
    vote_time        DATETIME    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_game_vote (game_id, round_number),
    UNIQUE KEY uk_game_round_voter (game_id, round_number, voter_player_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投票记录';

-- ============================================
-- 词库初始数据（80对，8个分类各10对）
-- ============================================

INSERT INTO t_word_pair (word_a, word_b, category, difficulty) VALUES
-- 水果
('苹果', '梨', '水果', 1),
('西瓜', '哈密瓜', '水果', 1),
('草莓', '蓝莓', '水果', 2),
('香蕉', '芒果', '水果', 1),
('橘子', '柚子', '水果', 2),
('葡萄', '提子', '水果', 3),
('桃子', '杏子', '水果', 2),
('樱桃', '车厘子', '水果', 3),
('菠萝', '凤梨', '水果', 3),
('柠檬', '青柠', '水果', 2),

-- 动物
('猫', '狗', '动物', 1),
('老虎', '狮子', '动物', 1),
('兔子', '松鼠', '动物', 2),
('鹦鹉', '八哥', '动物', 2),
('熊猫', '棕熊', '动物', 2),
('海豚', '鲸鱼', '动物', 1),
('蜜蜂', '黄蜂', '动物', 3),
('乌龟', '甲鱼', '动物', 2),
('公鸡', '野鸡', '动物', 2),
('蝴蝶', '飞蛾', '动物', 3),

-- 职业
('医生', '护士', '职业', 1),
('厨师', '糕点师', '职业', 2),
('老师', '教授', '职业', 2),
('警察', '保安', '职业', 1),
('律师', '法官', '职业', 2),
('画家', '雕塑家', '职业', 3),
('记者', '编辑', '职业', 2),
('飞行员', '空姐', '职业', 1),
('歌手', '演员', '职业', 1),
('程序员', '测试员', '职业', 2),

-- 食物
('米饭', '面条', '食物', 1),
('包子', '饺子', '食物', 1),
('火锅', '麻辣烫', '食物', 2),
('蛋糕', '面包', '食物', 1),
('薯条', '薯片', '食物', 2),
('豆腐', '豆皮', '食物', 3),
('牛排', '猪排', '食物', 2),
('寿司', '刺身', '食物', 2),
('馄饨', '汤圆', '食物', 2),
('煎饼', '烙饼', '食物', 3),

-- 日用品
('牙刷', '牙膏', '日用品', 1),
('毛巾', '浴巾', '日用品', 2),
('枕头', '靠垫', '日用品', 2),
('雨伞', '遮阳伞', '日用品', 3),
('钥匙', '锁', '日用品', 1),
('杯子', '碗', '日用品', 1),
('剪刀', '指甲刀', '日用品', 2),
('台灯', '手电筒', '日用品', 2),
('镜子', '放大镜', '日用品', 2),
('扫帚', '拖把', '日用品', 1),

-- 运动
('篮球', '排球', '运动', 1),
('游泳', '跳水', '运动', 2),
('跑步', '竞走', '运动', 2),
('足球', '橄榄球', '运动', 2),
('乒乓球', '羽毛球', '运动', 1),
('滑雪', '滑冰', '运动', 2),
('拳击', '摔跤', '运动', 2),
('瑜伽', '太极', '运动', 3),
('高尔夫', '棒球', '运动', 2),
('网球', '壁球', '运动', 3),

-- 地点
('医院', '诊所', '地点', 2),
('超市', '便利店', '地点', 1),
('学校', '培训班', '地点', 2),
('公园', '游乐园', '地点', 1),
('图书馆', '书店', '地点', 2),
('电影院', '剧场', '地点', 2),
('机场', '火车站', '地点', 1),
('银行', '邮局', '地点', 2),
('酒店', '旅馆', '地点', 2),
('餐厅', '食堂', '地点', 1),

-- 抽象概念
('开心', '兴奋', '抽象概念', 2),
('勇敢', '鲁莽', '抽象概念', 3),
('聪明', '狡猾', '抽象概念', 3),
('浪漫', '肉麻', '抽象概念', 3),
('孤独', '寂寞', '抽象概念', 2),
('自信', '骄傲', '抽象概念', 2),
('温柔', '软弱', '抽象概念', 3),
('节约', '吝啬', '抽象概念', 3),
('梦想', '幻想', '抽象概念', 2),
('自由', '放纵', '抽象概念', 3);
