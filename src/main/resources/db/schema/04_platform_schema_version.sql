-- * 这是 schema 版本表的初始化脚本 (Spec §5): 为未来 schema 迁移打地基, 本轮只建表 + 置初始版本行, 不含迁移逻辑.
-- * 幂等: 建表 IF NOT EXISTS, 版本行 ON CONFLICT DO NOTHING, 可重复执行.
CREATE TABLE IF NOT EXISTS platform_schema_version
(
    version    INT PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_schema_version (version) VALUES (1) ON CONFLICT (version) DO NOTHING;
