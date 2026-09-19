-- * 这是创建表 "users" 的初始化脚本 (程序化权威源, Spec §5: 由 sql_scripts/users_init.sql 迁移并加序号前缀保证执行次序).
-- * 幂等: CREATE TABLE IF NOT EXISTS, 可重复执行.
CREATE TABLE IF NOT EXISTS users
(
    id          UUID PRIMARY KEY,
    user_name   VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role        VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
