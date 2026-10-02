-- * 这是创建表 "ai_chat_sessions" 的初始化脚本.
-- * 请在数据库开启且没有该表的时候使用.
-- * 如果你的数据库是空白状态, 请去 [[init_schema.sql]] 初始化.
CREATE TABLE IF NOT EXISTS ai_chat_sessions
(
    id                UUID      PRIMARY KEY,
    user_id           UUID      NOT NULL REFERENCES users(id),
    messages          JSONB,
    title             VARCHAR(64) NULL,
    warning_triggered BOOLEAN   NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_id ON ai_chat_sessions(user_id);
