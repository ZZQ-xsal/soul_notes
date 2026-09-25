-- * 这是创建表 "clinical_assessments" 的初始化脚本 (咨询员工作台: 副医生结构化评估存储).
-- * 幂等: CREATE TABLE IF NOT EXISTS, 可重复执行.
-- * 与程序化权威源 src/main/resources/db/schema/05_clinical_assessments.sql 保持一致.
-- * 列型 TIMESTAMP 沿用 01-04 号既有约定 (实体写路径恒显式赋值 created_at, 无漂移).
-- * 无外键为有意设计: 心理评估记录独立于用户账号生命周期留存至保留期.
CREATE TABLE IF NOT EXISTS clinical_assessments
(
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL,
    session_id  UUID         NOT NULL,
    risk_level  VARCHAR(16)  NOT NULL,
    tags        JSONB        NOT NULL,
    summary     TEXT         NOT NULL DEFAULT '',
    schema_hash VARCHAR(64)  NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_assess_level_created ON clinical_assessments (risk_level, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_assess_user_created  ON clinical_assessments (user_id, created_at DESC);
