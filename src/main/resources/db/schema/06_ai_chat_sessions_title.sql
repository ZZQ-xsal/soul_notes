-- * 这是 "ai_chat_sessions" 表增加会话标题列的升级脚本 (历史会话列表展示 AI 概括标题).
-- * 新库由 03_ai_chat_sessions.sql 直接建出该列, 本脚本对存量库补列, 两者叠加幂等.
-- * 幂等: ADD COLUMN IF NOT EXISTS, 可重复执行.
-- * 存量会话的标题由 ChatService 在拉取会话列表时按需补生成, 不回填数据.
ALTER TABLE ai_chat_sessions ADD COLUMN IF NOT EXISTS title VARCHAR(64);
