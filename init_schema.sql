-- * 这是数据库初始化脚本入口.
-- * 请在空白数据库上按顺序执行.
-- * 该文件引用 sql_scripts/ 目录下的具体表脚本.
-- * 添加新的表初始化脚本时, 请在此文件中按顺序引用.
-- * 注意 (Spec §5): 程序化权威源已迁移至 src/main/resources/db/schema/ (向导 applySchema 按文件名序执行,
-- * 含 platform_schema_version 版本表); 本文件与 sql_scripts/ 保留为 psql 手动执行镜像, 内容与其保持一致.

-- 用户表
\i sql_scripts/users_init.sql

-- 情绪日记表
\i sql_scripts/mood_diaries_init.sql

-- AI 对话会话表
\i sql_scripts/ai_chat_sessions_init.sql

-- Schema 版本表 (为未来迁移打地基)
\i sql_scripts/platform_schema_version_init.sql

-- 临床评估表 (咨询员工作台)
\i sql_scripts/clinical_assessments_init.sql
