-- * 这是表 "mood_diaries" 的测试数据加载脚本.
-- * 请在数据库开启, 且需要简单测试时使用.
-- * analysis_result 结构对齐 EmotionAnalysisService#mergeResults 的输出字段;
-- * created_at 错开近 3 天, 便于"情绪天气预报"看板展示多日聚合效果.
INSERT INTO mood_diaries (user_id, content, analysis_result, created_at) VALUES
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', '今天天气真好, 心情很愉快!',
     '{"positive": 0.85, "negative": 0.05, "anxiety": 0.10, "weather": "sunny", "summary": "看到你今天这么开心, 真为你高兴! 记得多留住一些这样的好心情。", "warningLevel": "NONE", "warningReason": "", "suggestedAction": ""}',
     CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', '考试没考好, 有点沮丧。',
     '{"positive": 0.15, "negative": 0.70, "anxiety": 0.60, "weather": "rainy", "summary": "一次考试说明不了什么, 难过就允许自己歇一歇, 明天出门散散步, 慢慢会好起来的。", "warningLevel": "NONE", "warningReason": "", "suggestedAction": ""}',
     CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('b2c3d4e5-f6a7-8901-bcde-f12345678901', '和朋友一起出去了, 开心!',
     '{"positive": 0.90, "negative": 0.02, "anxiety": 0.05, "weather": "sunny", "summary": "和朋友在一起的时光真美好, 为你的这份快乐喝彩!", "warningLevel": "NONE", "warningReason": "", "suggestedAction": ""}',
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('b2c3d4e5-f6a7-8901-bcde-f12345678901', '最近压力好大, 睡不着。',
     '{"positive": 0.10, "negative": 0.75, "anxiety": 0.90, "weather": "thunderstorm", "summary": "压力大的时候, 试试睡前的深呼吸, 或白天出去走走; 也别一个人扛着, 找人聊聊会轻松一些。", "warningLevel": "YELLOW", "warningReason": "持续高焦虑", "suggestedAction": "建议倾诉或联系心理中心"}',
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('e5f6a7b8-c9d0-1234-efab-345678901234', '平淡的一天, 没有什么特别的。',
     '{"positive": 0.40, "negative": 0.20, "anxiety": 0.25, "weather": "cloudy", "summary": "平平淡淡也是生活的一部分, 谢谢你愿意把今天记录下来。", "warningLevel": "NONE", "warningReason": "", "suggestedAction": ""}',
     CURRENT_TIMESTAMP);
