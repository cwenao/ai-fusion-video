ALTER TABLE afv_agent_run
    ADD COLUMN generation_review_json JSON DEFAULT NULL COMMENT '用户明确启用的项目生成审查范围';

ALTER TABLE afv_generation_submission
    ADD COLUMN content_review_json JSON DEFAULT NULL COMMENT '提交时固定的审查模型、提示词与业务对象';

CREATE TABLE afv_generation_content_review (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '审查记录标识',
    submission_id CHAR(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属生成受理记录',
    item_id BIGINT NOT NULL COMMENT '对应的生成结果条目',
    media_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审查时使用的原始媒体资源',
    model_id BIGINT NOT NULL COMMENT '审查模型标识',
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '审查状态',
    deadline_at DATETIME(6) DEFAULT NULL COMMENT '本次领取的执行截止时间',
    result_json JSON DEFAULT NULL COMMENT '审查结论、依据、建议与限制',
    error_message VARCHAR(255) DEFAULT NULL COMMENT '可向用户展示的失败原因',
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_review_item (submission_id, item_id),
    UNIQUE KEY uk_content_review_media (media_id),
    KEY idx_content_review_queue (state, id),
    KEY idx_content_review_model (state, model_id, deadline_at),
    CONSTRAINT fk_content_review_submission FOREIGN KEY (submission_id) REFERENCES afv_generation_submission (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户主动启用的逐产物生成内容审查';
