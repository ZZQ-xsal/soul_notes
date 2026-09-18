-- * 这是创建表 "mood_diaries" 的初始化脚本 (程序化权威源, Spec §5: 由 sql_scripts/mood_diaries_init.sql 迁移并加序号前缀保证执行次序).
-- * 幂等: CREATE TABLE IF NOT EXISTS, 可重复执行. 依赖 01_users.sql 先行创建 users 表.
CREATE TABLE IF NOT EXISTS mood_diaries
(
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users(id),
    content         TEXT,
    audio_url       VARCHAR(512),
    analysis_result JSONB,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mood_diaries_user_id ON mood_diaries(user_id);
