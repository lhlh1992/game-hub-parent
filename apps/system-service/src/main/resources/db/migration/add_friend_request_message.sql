-- 确保 friend_request 表有 request_message 字段
-- 如果字段已存在，此脚本不会报错（使用 IF NOT EXISTS）

-- PostgreSQL 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，需要先检查
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'friend_request' 
        AND column_name = 'request_message'
    ) THEN
        ALTER TABLE friend_request 
        ADD COLUMN request_message VARCHAR(200);
        
        COMMENT ON COLUMN friend_request.request_message IS '申请留言';
    END IF;
END $$;


