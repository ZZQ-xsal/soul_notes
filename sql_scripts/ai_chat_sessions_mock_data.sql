-- * 这是表 "ai_chat_sessions" 的测试数据加载脚本.
-- * 请在数据库开启, 且需要简单测试时使用.
INSERT INTO ai_chat_sessions (id, user_id, messages, warning_triggered, updated_at) VALUES
    ('f6a7b8c9-d0e1-2345-fabc-456789012345', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
     '[{"role": "user", "content": "最近好累"}, {"role": "assistant", "content": "听起来你最近压力很大, 愿意多说说吗?"}]',
     FALSE, CURRENT_TIMESTAMP),
    ('a7b8c9d0-e1f2-3456-abcd-567890123456', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
     '[{"role": "user", "content": "我感觉很焦虑"}, {"role": "assistant", "content": "我感受到你此刻的焦虑, 这种紧绷着的感觉确实很难受。愿意和我说说, 是什么事让你这么不安吗?"}]',
     FALSE, CURRENT_TIMESTAMP),
    ('b8c9d0e1-f2a3-4567-bcde-678901234567', 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
     '[{"role": "user", "content": "今天和朋友玩了, 很开心"}, {"role": "assistant", "content": "听起来你今天过得很开心, 和朋友在一起的时光总是让人放松呢。愿意多讲讲你们玩了什么吗?"}]',
     FALSE, CURRENT_TIMESTAMP),
    ('c9d0e1f2-a3b4-5678-cdef-789012345678', 'e5f6a7b8-c9d0-1234-efab-345678901234',
     '[{"role": "user", "content": "不想活了"}, {"role": "assistant", "content": "我听到你正在经历非常困难的时刻, 你的感受很重要。建议你拨打心理援助热线: 400-161-9995。"}]',
     TRUE, CURRENT_TIMESTAMP),
    ('d0e1f2a3-b4c5-6789-defa-890123456789', 'e5f6a7b8-c9d0-1234-efab-345678901234',
     '[{"role": "user", "content": "今天天气不错"}, {"role": "assistant", "content": "天气好的时候, 心情好像也会跟着明朗起来。这样的好天气, 你今天有出门走走吗?"}]',
     FALSE, CURRENT_TIMESTAMP);
