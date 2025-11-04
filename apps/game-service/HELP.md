com.gamehub.gameservice
├─ common                // 全局通用（异常、工具类）
│  └─ WebExceptionAdvice
│
├─ engine                // 对局引擎（核心框架）
│  ├─ core               // 状态机 / 命令 / AI建议器 / 引擎模式
│  │  ├─ AiAdvisor       //AI 建议器抽象：给定状态，返回一条建议的 Command（例如：五子棋的“建议落在(7,8)”）。
│  │  ├─ Command         //统一的“玩家/AI 输入指令”抽象。回合制/实时制都用这个表示一条输入。
│  │  ├─ EngineMode      //引擎运行模式
│  │  └─ GameState       //游戏状态快照接口
│  └─ transport          // 消息传输（封装、后续接WebSocket）
│     └─ MessageEnvelope
│
├─ games                 // 各游戏模块
│  ├─ chineseChess       // 象棋模块（预留）
│  └─ gomoku             // 五子棋模块（已实现）
│     ├─ ai              // AI算法层
│     │  ├─ Evaluator    // 棋面评估函数
│     │  └─ GomokuAI     // 威胁优先 + αβ 搜索 + 禁手过滤
│     ├─ model           // 数据结构
│     │  ├─ Board
│     │  ├─ GomokuState
│     │  └─ Move
│     ├─ rule            // 规则判定
│     │  ├─ GomokuJudge        // 普通规则
│     │  ├─ GomokuJudgeRenju  // 禁手规则
│     │  └─ Outcome
│     └─ service
│        ├─ GomokuService      // 接口定义
│        └─ impl
│           └─ GomokuServiceImpl // 实现类：管理房间+AI调用
│
└─ web                   // 控制器层
└─ GomokuController   // （待加：REST+WebSocket接口）