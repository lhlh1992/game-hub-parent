/**
 * 游戏逻辑模块 - 五子棋棋盘渲染和游戏状态管理
 */

const DEFAULT_N = 15;
let grid = null;
let state = null;
// 注意：currentRoomId 在 game.html 中声明，这里不重复声明
let countdownTimer = null;
let currentCountdown = 0;
let countdownDeadline = 0;

/**
 * 初始化游戏
 * @param {string} roomId - 房间ID
 */
function initGame(roomId) {
    // 将 roomId 存储到 window 对象，供其他函数使用
    if (typeof window !== 'undefined') {
        window._currentRoomId = roomId;
    }
    grid = makeEmpty(DEFAULT_N);
    state = null;
    stopCountdown();
    renderBoard(grid, null);
}

/**
 * 创建空棋盘
 * @param {number} n - 棋盘大小
 * @returns {Array}
 */
function makeEmpty(n) {
    return Array.from({length: n}, _ => Array(n).fill('.'));
}

/**
 * 标准化棋盘数据
 * @param {*} raw - 原始数据
 * @returns {Array|null}
 */
function normalizeGrid(raw) {
    if (!raw) return null;
    if (Array.isArray(raw) && typeof raw[0] === 'string') {
        return raw.map(row => row.split(''));
    }
    return raw;
}

/**
 * 渲染棋盘
 * @param {Array} grid - 棋盘数据
 * @param {Object} lastMove - 最后一步坐标 {x, y}
 */
function renderBoard(grid, lastMove) {
    const boardEl = document.getElementById('board');
    if (!boardEl) return;
    
    boardEl.innerHTML = '';
    boardEl.style.setProperty('--n', grid.length.toString());
    
    const n = grid.length;
    const letters = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O'];
    
    // 渲染棋盘格子
    for (let y = 0; y < n; y++) {
        for (let x = 0; x < n; x++) {
            const v = grid[x][y];
            const isLast = lastMove && lastMove.x === x && lastMove.y === y;
            
            const cell = document.createElement('div');
            cell.className = 'cell' + 
                (v === 'X' ? ' X' : (v === 'O' ? ' O' : '')) + 
                (isLast ? ' last' : '');
            cell.textContent = v === '.' ? '' : v;
            cell.dataset.x = String(x);
            cell.dataset.y = String(y);
            cell.title = `(${letters[x]}${y + 1})`;
            
            // 只有空位时才添加点击事件（游戏结束检查在onCellClick中）
            if (v === '.') {
                cell.addEventListener('click', onCellClick);
            }
            
            boardEl.appendChild(cell);
        }
    }
    
    // 等待DOM完全渲染后，基于实际元素位置动态设置坐标标记
    // 使用双重 requestAnimationFrame 确保布局完成
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            const boardRect = boardEl.getBoundingClientRect();
            const cells = boardEl.querySelectorAll('.cell');
            
            if (cells.length !== n * n) return;
            
            // 添加Y轴坐标（左侧）- 数字 1-15
            // 1在最底下，15在最上面
            // Y轴要对齐到每一条横线（水平线），即每个cell的top边界
            for (let y = 0; y < n; y++) {
                // 获取第y行第0列的cell（每行第一个）
                const cellIndex = y * n;
                const cell = cells[cellIndex];
                if (!cell) continue;
                
                const cellRect = cell.getBoundingClientRect();
                // 网格横线在cell的top边界，不是中心！
                // 直接使用实际DOM元素的top边界位置
                const lineY = cellRect.top - boardRect.top;
                
                const coordY = document.createElement('div');
                coordY.className = 'board-coord coord-y';
                coordY.textContent = String(n - y);
                coordY.style.position = 'absolute';
                // 左侧坐标区域：获取第一个cell的left位置，减去一个cell宽度作为坐标区域
                const firstCellRect = cells[0].getBoundingClientRect();
                const coordXPos = firstCellRect.left - boardRect.left - (firstCellRect.width / 2);
                coordY.style.left = `${coordXPos}px`;
                coordY.style.top = `${lineY}px`; // 对齐到横线（cell的top边界）
                coordY.style.transform = 'translate(-50%, -50%)';
                coordY.style.textAlign = 'center';
                boardEl.appendChild(coordY);
            }
            
            // 添加X轴坐标（下方）- 字母 A-O
            // X轴要对齐到每一条竖线（垂直线），即每个cell的left边界
            const lastRowIndex = n - 1;
            for (let x = 0; x < n; x++) {
                // 获取最后一行第x列的cell
                const cellIndex = lastRowIndex * n + x;
                const cell = cells[cellIndex];
                if (!cell) continue;
                
                const cellRect = cell.getBoundingClientRect();
                // 网格竖线在cell的left边界，不是中心！
                // 直接使用实际DOM元素的left边界位置
                const lineX = cellRect.left - boardRect.left;
                // 最后一条横线的位置（最后一行的top边界），这就是底线
                // X轴坐标应该紧贴这条底线，稍微往下一点点
                const lineY = cellRect.top - boardRect.top;
                // 稍微往下一点点，让坐标标记正好在底线下方一点点（约半个字体大小）
                const offsetY = lineY + 8; // 往下8px，正好贴着底线
                
                const coordX = document.createElement('div');
                coordX.className = 'board-coord coord-x';
                coordX.textContent = letters[x];
                coordX.style.position = 'absolute';
                coordX.style.left = `${lineX}px`; // 对齐到竖线（cell的left边界）
                coordX.style.top = `${offsetY}px`; // 正好贴着底线，稍微往下一点点
                coordX.style.transform = 'translate(-50%, -50%)';
                coordX.style.textAlign = 'center';
                boardEl.appendChild(coordX);
            }
        });
    });
}

/**
 * 检查游戏是否已结束
 * @returns {boolean}
 */
function isGameOver() {
    // 检查 state.over 或 state.outcome
    if (state?.over) return true;
    if (state?.outcome) return true;
    return false;
}

/**
 * 处理棋盘点击事件
 * @param {Event} e - 点击事件
 */
function onCellClick(e) {
    // 如果游戏已结束，不允许下棋
    if (isGameOver()) {
        console.warn('游戏已结束，无法继续下棋');
        return;
    }
    
    const currentRoomId = typeof window !== 'undefined' ? window._currentRoomId : null;
    if (!currentRoomId) {
        console.warn('未选择房间');
        return;
    }
    
    const x = parseInt(e.currentTarget.dataset.x, 10);
    const y = parseInt(e.currentTarget.dataset.y, 10);
    
    // 获取当前执子方（从 state 或使用默认值）
    const side = state?.current || 'X';
    
    // 触发外部回调
    if (window.gameCallbacks && window.gameCallbacks.onPlace) {
        window.gameCallbacks.onPlace(x, y, side);
    }
}

/**
 * 处理游戏事件
 * @param {Object} evt - 事件对象
 */
function handleGameEvent(evt) {
    if (!evt) return;
    
    // 处理倒计时事件
    if (evt.type === 'TICK') {
        if (evt.payload) {
            if (typeof evt.payload.deadlineEpochMs === 'number' && evt.payload.deadlineEpochMs > 0) {
                startCountdownFromDeadline(evt.payload.deadlineEpochMs);
            } else if (typeof evt.payload.left === 'number') {
                startCountdown(evt.payload.left);
            }
        }
        return;
    }
    
    if (evt.type === 'TIMEOUT') {
        console.log(`超时：${evt.payload?.side ?? '-'}`);
        return;
    }
    
    if (evt.type === 'ERROR') {
        console.error('游戏错误:', evt.payload);
        if (window.gameCallbacks && window.gameCallbacks.onError) {
            window.gameCallbacks.onError(evt.payload);
        }
        return;
    }
    
    // 处理快照事件
    if (evt.type === 'SNAPSHOT') {
        renderFullSync(evt.payload);
        return;
    }
    
    // 处理状态更新
    const payload = evt.payload || {};
    state = payload.state || payload;
    const series = payload.series || null;
    
    const g = normalizeGrid(state?.board?.grid ?? state?.board ?? state?.grid);
    if (g) {
        grid = g;
    }
    
    // 渲染最后一步
    let lastClient = null;
    if (state?.lastMove && Number.isFinite(state.lastMove.x) && Number.isFinite(state.lastMove.y)) {
        lastClient = { x: state.lastMove.x, y: state.lastMove.y };
    }
    renderBoard(grid, lastClient);
    
    // 更新游戏信息显示
    updateGameInfo(state, series);
    
    // 处理游戏结束
    if (state?.over) {
        stopCountdown();
        if (window.gameCallbacks && window.gameCallbacks.onGameOver) {
            window.gameCallbacks.onGameOver(state.winner);
        }
    }
}

/**
 * 渲染完整同步数据
 * @param {Object} snap - 快照数据
 */
function renderFullSync(snap) {
    if (!snap || !snap.board) return;
    
    // 先更新全局state，确保能正确检测游戏结束状态
    if (!state) state = {};
    
    // 处理游戏结束状态 - 必须在renderBoard之前更新
    if (snap.outcome) {
        state.over = true;
        state.outcome = snap.outcome;
        state.winner = snap.outcome === 'X_WIN' ? 'X' : 
                      snap.outcome === 'O_WIN' ? 'O' : null;
        stopCountdown();
        if (window.gameCallbacks && window.gameCallbacks.onGameOver) {
            window.gameCallbacks.onGameOver(state.winner);
        }
    } else {
        // 如果没有outcome，游戏未结束
        state.over = false;
        state.outcome = null;
        state.winner = null;
    }
    
    // 更新其他状态信息
    state.current = snap.sideToMove || state.current;
    
    grid = snap.board.cells;
    renderBoard(grid, snap.lastMove);
    
    const seriesView = snap.seriesView || {};
    const round = snap.round || seriesView.round || 1;
    const blackWins = seriesView.scoreX || 0;
    const whiteWins = seriesView.scoreO || 0;
    const currentSide = snap.sideToMove || '-';
    
    // 更新游戏信息显示（传递正确的state对象）
    updateGameInfo(state, {
        index: round,
        blackWins: blackWins,
        whiteWins: whiteWins,
        draws: 0
    }, currentSide, snap.mode);
    
    // 更新玩家执子方（如果快照中包含）
    if (snap.mySide && typeof window.updatePlayerSide === 'function') {
        window.updatePlayerSide(snap.mySide, snap.mode);
    }
    
    // 同步倒计时
    if (snap.deadlineEpochMs && snap.deadlineEpochMs > 0) {
        startCountdownFromDeadline(snap.deadlineEpochMs);
    } else {
        stopCountdown();
    }
}

/**
 * 更新游戏信息显示
 * @param {Object} state - 游戏状态
 * @param {Object} series - 系列信息
 * @param {string} currentSide - 当前执子方
 * @param {string} mode - 游戏模式
 */
function updateGameInfo(state, series, currentSide = null, mode = null) {
    const metaEl = document.getElementById('meta');
    const winnerEl = document.getElementById('winner');
    const timerEl = document.getElementById('timer');
    
    if (metaEl) {
        const current = currentSide || state?.current || '-';
        const over = state?.over || false;
        
        if (series) {
            metaEl.textContent = 
                `第 ${series.index || 1} 盘 | 比分 黑:${series.blackWins || 0} 白:${series.whiteWins || 0}` +
                ` | 当前执子: ${current} | 已结束: ${over ? '是' : '否'}`;
        } else {
            metaEl.textContent = `当前执子: ${current} | 已结束: ${over ? '是' : '否'}`;
        }
    }
    
    if (winnerEl) {
        if (state?.over && state?.winner) {
            winnerEl.textContent = `🎉 Winner: ${state.winner}`;
            winnerEl.style.display = 'inline-block';
        } else {
            winnerEl.style.display = 'none';
        }
    }
}

/**
 * 开始倒计时
 * @param {number} seconds - 剩余秒数
 */
function startCountdown(seconds) {
    const newDeadline = Date.now() + seconds * 1000;
    
    if (Math.abs(newDeadline - countdownDeadline) < 2000) {
        return;
    }
    
    if (countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }
    
    countdownDeadline = newDeadline;
    updateCountdownFromDeadline();
    
    if (seconds > 0) {
        countdownTimer = setInterval(() => {
            updateCountdownFromDeadline();
            if (currentCountdown <= 0) {
                clearInterval(countdownTimer);
                countdownTimer = null;
            }
        }, 100);
    }
}

/**
 * 从截止时间开始倒计时
 * @param {number} deadlineEpochMs - 截止时间戳（毫秒）
 */
function startCountdownFromDeadline(deadlineEpochMs) {
    if (Math.abs(deadlineEpochMs - countdownDeadline) < 2000) {
        return;
    }
    
    if (countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }
    
    countdownDeadline = deadlineEpochMs;
    updateCountdownFromDeadline();
    
    if (countdownDeadline > Date.now()) {
        countdownTimer = setInterval(() => {
            updateCountdownFromDeadline();
            if (currentCountdown <= 0) {
                clearInterval(countdownTimer);
                countdownTimer = null;
            }
        }, 100);
    }
}

/**
 * 更新倒计时显示
 */
function updateCountdownFromDeadline() {
    const now = Date.now();
    const remainingMs = Math.max(0, countdownDeadline - now);
    const newCountdown = Math.ceil(remainingMs / 1000);
    
    if (newCountdown !== currentCountdown) {
        currentCountdown = newCountdown;
        updateTimerDisplay();
    }
    
    if (currentCountdown <= 0 && countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }
}

/**
 * 更新计时器显示
 */
function updateTimerDisplay() {
    const timerEl = document.getElementById('timer');
    if (timerEl) {
        timerEl.textContent = currentCountdown > 0 ? currentCountdown : '--';
    }
}

/**
 * 停止倒计时
 */
function stopCountdown() {
    if (countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }
    currentCountdown = 0;
    countdownDeadline = 0;
    updateTimerDisplay();
}

/**
 * 保存 seatKey
 * @param {string} roomId - 房间ID
 * @param {string} side - 座位方（X 或 O）
 * @param {string} key - seatKey
 */
function saveSeatKey(roomId, side, key) {
    localStorage.setItem(`room:${roomId}:seatKey:${side}`, key);
    sessionStorage.setItem(`room:${roomId}:currentSeatKey`, key);
}

/**
 * 获取 seatKey
 * @param {string} roomId - 房间ID
 * @returns {string|null}
 */
function getSeatKey(roomId) {
    const ss = sessionStorage.getItem(`room:${roomId}:currentSeatKey`);
    if (ss) return ss;
    
    const x = localStorage.getItem(`room:${roomId}:seatKey:X`);
    const o = localStorage.getItem(`room:${roomId}:seatKey:O`);
    
    if (x && !o) {
        sessionStorage.setItem(`room:${roomId}:currentSeatKey`, x);
        return x;
    }
    if (o && !x) {
        sessionStorage.setItem(`room:${roomId}:currentSeatKey`, o);
        return o;
    }
    
    return null;
}

/**
 * 获取当前房间ID
 * @returns {string|null}
 */
function getCurrentRoomId() {
    return typeof window !== 'undefined' ? window._currentRoomId : null;
}

