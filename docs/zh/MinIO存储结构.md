# MinIO 存储结构说明

## Bucket 列表

| Bucket 名称 | 用途 | 访问策略 |
|------------|------|---------|
| `avatars` | 用户头像 | Private |
| `game-replays` | 游戏回放/棋谱 | Private |
| `materials` | 活动素材、公告图片 | Private |
| `temp` | 临时文件 | Private |

## 文件路径层级

### 1. avatars/（用户头像）

```
avatars/
└── {uuid}.jpg
```

**说明：**
- 使用 UUID v4 作为文件名，避免冲突
- 扁平化结构，不按用户ID分目录
- 示例：`550e8400-e29b-41d4-a716-446655440000.jpg`

**访问URL：**
```
http://files.localhost/files/avatars/{uuid}.jpg
```

---

### 2. game-replays/（游戏回放）

```
game-replays/
└── {gameType}/
    └── {roomId}/
        └── replay.json
```

**说明：**
- 按游戏类型分目录（如：`gomoku`、`chess`）
- 按房间ID分目录
- 回放文件统一命名为 `replay.json`

**示例：**
```
game-replays/
└── gomoku/
    └── room-12345/
        └── replay.json
```

**访问URL：**
```
http://files.localhost/files/game-replays/{gameType}/{roomId}/replay.json
```

---

### 3. materials/（活动素材）

```
materials/
└── {type}/
    └── {id}/
        └── {filename}.{ext}
```

**说明：**
- 按素材类型分目录（如：`banners`、`notices`、`games`）
- 按素材ID分目录
- 保留原始文件名和扩展名

**示例：**
```
materials/
├── banners/
│   └── campaign-001/
│       └── banner.jpg
├── notices/
│   └── notice-20250108/
│       └── image.png
└── games/
    └── gomoku/
        └── cover.png
```

**访问URL：**
```
http://files.localhost/files/materials/{type}/{id}/{filename}.{ext}
```

---

### 4. temp/（临时文件）

```
temp/
├── uploads/
│   └── {sessionId}/
│       └── {uuid}.tmp
└── processing/
    └── {taskId}/
        └── {filename}
```

**说明：**
- `uploads/`：上传中的临时文件
- `processing/`：处理中的文件（压缩、裁剪等）
- 使用 sessionId 或 taskId 作为目录名，便于清理

**清理策略：**
- 上传完成后立即移动到目标 bucket
- 定期清理超过 24 小时的临时文件

---

## 命名规范

### 文件名
- 使用 UUID v4：`550e8400-e29b-41d4-a716-446655440000.jpg`
- 避免中文、特殊字符
- 保留原始扩展名（.jpg、.png、.json 等）

### 目录名
- 小写字母 + 连字符：`game-replays`、`user-avatars`
- 避免下划线

---

## 数据库关联

### sys_user 表
```sql
avatar_url VARCHAR(500)
-- 存储完整URL: http://files.localhost/files/avatars/{uuid}.jpg
```

### 游戏回放（如有回放表）
```sql
replay_url VARCHAR(500)
-- 存储完整URL: http://files.localhost/files/game-replays/{gameType}/{roomId}/replay.json
```

