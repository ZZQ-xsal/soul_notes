-- * 这是创建表 "ai_chat_sessions" 的初始化脚本 (程序化权威源, Spec §5: 由 sql_scripts/ai_chat_sessions_init.sql 迁移并加序号前缀保证执行次序).
-- * 幂等: CREATE TABLE IF NOT EXISTS, 可重复执行. 依赖 01_users.sql 先行创建 users 表.
CREATE TABLE IF NOT EXISTS ai_chat_sessions
(
    id                UUID      PRIMARY KEY,
    user_id           UUID      NOT NULL REFERENCES users(id),
    messages          JSONB,
    warning_triggered BOOLEAN   NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_id ON ai_chat_sessions(user_id);
