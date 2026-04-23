ALTER TABLE t_account
    ADD COLUMN system_role TINYINT NOT NULL DEFAULT 0 COMMENT '系统角色：0=普通用户 1=系统管理员' AFTER status;

CREATE TABLE t_admin_operation_log (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    operator_id        BIGINT       NOT NULL                COMMENT '操作人账号主键 t_account.id',
    operator_account_id VARCHAR(16) NOT NULL                COMMENT '操作人业务账号ID快照',
    operation_type     VARCHAR(64)  NOT NULL                COMMENT '操作类型枚举名',
    target_type        VARCHAR(32)  NOT NULL                COMMENT '操作对象类型枚举名',
    target_id          VARCHAR(64)  NOT NULL                COMMENT '操作对象标识',
    target_display     VARCHAR(128) NULL                    COMMENT '操作对象展示快照',
    reason             VARCHAR(255) NOT NULL                COMMENT '操作原因',
    detail_json        TEXT         NULL                    COMMENT '补充详情JSON',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag           TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_operator_id (operator_id),
    KEY idx_target_type_target_id (target_type, target_id),
    KEY idx_operation_type (operation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员操作日志表';
