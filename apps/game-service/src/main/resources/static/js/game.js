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
    // 关键修复：如果是二维数组，创建深拷贝，避免引用问题
    if (Array.isArray(raw) && Array.isArray(raw[0])) {
        return raw.map(row => [...row]); // 创建新数组，避免引用问题
    }
    return raw;
}

/**
 * 检测棋盘上的五连（仅在游戏结束时调用）
 * @param {Array} grid - 棋盘数据
 * @param {string} winnerPiece - 获胜方的棋子类型 'X' 或 'O'
 * @returns {Set} 五连坐标的 Set，格式为 "x,y"
 */
function detectWinLines(grid, winnerPiece) {
    if (!grid || !winnerPiece) return new Set();
    
    const n = grid.length;
    const winPieces = new Set();
    
    // 4个方向：横、竖、主对角、反对角
    const dirs = [
        [1, 0],  // →
        [0, 1],  // ↓
        [1, 1],  // ↘
        [1, -1]  // ↗
    ];
    
    // 修复：标准化 winnerPiece：处理各种格式
    // 后端约定：BLACK='X', WHITE='O'
    // 但传输时可能变成：数字 0='X'（黑棋），数字 1='O'（白棋）
    let normalizedWinner = winnerPiece;
    if (winnerPiece === 0 || winnerPiece === '0') {
        normalizedWinner = 'X'; // 0 = 黑棋
    } else if (winnerPiece === 1 || winnerPiece === '1') {
        normalizedWinner = 'O'; // 1 = 白棋
    } else if (winnerPiece === 'O' || winnerPiece === 'o') {
        normalizedWinner = 'O';
    } else if (winnerPiece === 'X' || winnerPiece === 'x') {
        normalizedWinner = 'X';
    }
    
    // 遍历棋盘上的每个棋子
    for (let x = 0; x < n; x++) {
        for (let y = 0; y < n; y++) {
            const piece = grid[x][y];
            // 修复：匹配各种格式：'X'/'O', 0/1, '0'/'1'
            let pieceMatches = false;
            if (normalizedWinner === 'X') {
                pieceMatches = (piece === 'X' || piece === 'x' || piece === 0 || piece === '0');
            } else if (normalizedWinner === 'O') {
                pieceMatches = (piece === 'O' || piece === 'o' || piece === 1 || piece === '1');
            }
            if (!pieceMatches) continue;
            
            // 检查这个棋子在4个方向上是否形成五连
            for (let dirIdx = 0; dirIdx < dirs.length; dirIdx++) {
                const [dx, dy] = dirs[dirIdx];
                const line = [];
                
                // 正方向收集
                let cx = x + dx, cy = y + dy;
                while (cx >= 0 && cx < n && cy >= 0 && cy < n && grid[cx][cy] === winnerPiece) {
                    line.push([cx, cy]);
                    cx += dx;
                    cy += dy;
                }
                
                // 添加中心点
                line.push([x, y]);
                
                // 反方向收集
                cx = x - dx;
                cy = y - dy;
                while (cx >= 0 && cx < n && cy >= 0 && cy < n && grid[cx][cy] === winnerPiece) {
                    line.unshift([cx, cy]);
                    cx -= dx;
                    cy -= dy;
                }
                
                // 如果找到5个或更多
                if (line.length >= 5) {
                    // 只取前5个，添加到 Set 中
                    for (let i = 0; i < 5; i++) {
                        const [px, py] = line[i];
                        winPieces.add(px + ',' + py);
                    }
                    break; // 找到一个方向就够了，继续下一个棋子
                }
            }
        }
    }
    
    return winPieces;
}

/**
 * 渲染棋盘
 * @param {Array} grid - 棋盘数据
 * @param {Object} lastMove - 最后一步坐标 {x, y}
 * @param {Set} winPieces - 五连坐标的 Set（可选）
 */
function renderBoard(grid, lastMove, winPieces) {
    const boardEl = document.getElementById('board');
    if (!boardEl) return;
    
    // 获取board-container，坐标轴刻度将放在这里（避免被scale影响）
    const boardContainer = boardEl.parentElement;
    if (!boardContainer || !boardContainer.classList.contains('board-container')) return;
    
    // 清除旧的胜利连线
    boardEl.querySelectorAll('.win-line').forEach(el => el.remove());
    
    boardEl.innerHTML = '';
    boardEl.style.setProperty('--n', grid.length.toString());
    
    // 清除旧的坐标轴刻度
    boardContainer.querySelectorAll('.board-coord').forEach(el => el.remove());
    
    const n = grid.length;
    const letters = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O'];
    
    // 交叉点布局：动态获取网格线位置，不要写死
    const rootStyle = getComputedStyle(document.documentElement);
    const cellSizeStr = rootStyle.getPropertyValue('--cell').trim();
    const cellSize = parseFloat(cellSizeStr) || 32;
    
    // 动态获取#board的padding值（现在统一为40px，确保前后左右对称）
    const boardStyle = getComputedStyle(boardEl);
    const paddingLeft = parseFloat(boardStyle.paddingLeft) || 40;
    const paddingTop = parseFloat(boardStyle.paddingTop) || 40;
    const paddingRight = parseFloat(boardStyle.paddingRight) || 40;
    const paddingBottom = parseFloat(boardStyle.paddingBottom) || 40;
    
    // 关键修复：网格线CSS: 现在padding统一为40px，确保对称
    // - left: calc(40px - 0.75px) = 39.25px（网格线容器左边缘）
    // - top: calc(40px - 0.75px) = 39.25px（网格线容器上边缘）
    // repeating-linear-gradient从0开始，第一条线在0-1.5px，中心在0.75px
    // 所以：
    // - 第一条竖线中心 = 39.25 + 0.75 = 40px（相对于#board）
    // - 第一条横线中心 = 39.25 + 0.75 = 40px（相对于#board）
    const gridLineLeftEdge = paddingLeft - 0.75; // 网格线容器左边缘
    const gridLineTopEdge = paddingTop - 0.75;  // 网格线容器上边缘
    const gridLineCenterOffset = 0.75; // 网格线中心偏移（1.5px / 2）
    const gridLineCenterX = gridLineLeftEdge + gridLineCenterOffset; // 第一个网格线中心X = paddingLeft
    const gridLineCenterY = gridLineTopEdge + gridLineCenterOffset;  // 第一个网格线中心Y = paddingTop
    
    const cellWidth = cellSize * 0.8;
    const cellHeight = cellSize * 0.8;
    
    let cellCount = 0;
    
    // grid[x][y]中x是行，y是列
    for (let x = 0; x < n; x++) {
        for (let y = 0; y < n; y++) {
            if (x < 0 || x >= n || y < 0 || y >= n) {
                continue;
            }
            
            const v = grid[x][y];
            const isLast = lastMove && lastMove.x === x && lastMove.y === y;
            const isWinPiece = winPieces && winPieces.has(x + ',' + y);
            
            const cell = document.createElement('div');
            let className = 'cell';
            // 修复：处理各种棋子值格式：'X'/'O', '0'/'1', 数字 0/1
            // 标准化棋子值
            let pieceType = null;
            if (v === 'X' || v === 'x' || v === 0 || v === '0') {
                pieceType = 'X'; // 黑棋
            } else if (v === 'O' || v === 'o' || v === 1 || v === '1') {
                pieceType = 'O'; // 白棋
            }
            
            if (pieceType === 'X') className += ' X';
            else if (pieceType === 'O') className += ' O';
            if (isLast) className += ' last';
            // 不再添加 win-flash 类，改用红线连线
            cell.className = className;
            cell.textContent = (v === '.' || v === null || v === undefined) ? '' : String(v);
            cell.dataset.x = String(x);
            cell.dataset.y = String(y);
            cell.title = `(${letters[y]}${n - x})`;
            
            // 绝对定位：交叉点在网格线交点上
            const crossPointX = gridLineCenterX + y * cellSize;
            const crossPointY = gridLineCenterY + x * cellSize;
            cell.style.position = 'absolute';
            cell.style.left = `${crossPointX - cellWidth / 2}px`;
            cell.style.top = `${crossPointY - cellHeight / 2}px`;
            cell.style.margin = '0';
            cell.style.transform = 'none';
            cell.style.zIndex = '10';
            
            if (v === '.' && x >= 0 && x < n && y >= 0 && y < n) {
                cell.addEventListener('click', onCellClick);
            }
            
            boardEl.appendChild(cell);
            cellCount++;
        }
    }
    
    // 验证cell数量
    const createdCells = boardEl.querySelectorAll('.cell');
    
    // ====================================================================
    // 天元和星位标记：在棋盘交叉点上添加标记点
    // ====================================================================
    // 对于15x15棋盘：
    // - 天元：正中央 (7, 7)
    // - 四个角星：(3, 3), (3, 11), (11, 3), (11, 11)
    // 星位标记使用小圆点，天元稍大一些
    
    // 清除旧的星位标记
    boardEl.querySelectorAll('.star-point').forEach(el => el.remove());
    
    // 定义星位坐标（x, y, 是否是天元）
    const starPoints = [
        { x: 7, y: 7, isTengen: true },   // 天元（中心）
        { x: 3, y: 3, isTengen: false },  // 左上角星
        { x: 3, y: 11, isTengen: false }, // 右上角星
        { x: 11, y: 3, isTengen: false }, // 左下角星
        { x: 11, y: 11, isTengen: false } // 右下角星
    ];
    
    starPoints.forEach(star => {
        const crossPointX = gridLineCenterX + star.y * cellSize;
        const crossPointY = gridLineCenterY + star.x * cellSize;
        
        const starPoint = document.createElement('div');
        starPoint.className = 'star-point' + (star.isTengen ? ' tengen' : '');
        starPoint.style.position = 'absolute';
        starPoint.style.left = `${crossPointX}px`;
        starPoint.style.top = `${crossPointY}px`;
        starPoint.style.transform = 'translate(-50%, -50%)';
        starPoint.style.pointerEvents = 'none';
        starPoint.style.zIndex = '5'; // 在网格线上方，但在棋子下方
        
        boardEl.appendChild(starPoint);
    });
    
    // ====================================================================
    // 五连胜利连线：如果有五连，画一条红线连接五个棋子
    // ====================================================================
    if (winPieces && winPieces.size >= 5) {
        // 将 winPieces Set 转换为数组并排序，确保顺序正确
        const winCoords = Array.from(winPieces).map(coord => {
            const [x, y] = coord.split(',').map(Number);
            return { x, y };
        });
        
        // 按坐标排序，找到起点和终点（可能是横、竖、斜）
        // 简单排序：先按x排序，如果x相同则按y排序
        winCoords.sort((a, b) => {
            if (a.x !== b.x) return a.x - b.x;
            return a.y - b.y;
        });
        
        // 计算五个棋子的中心位置
        const positions = winCoords.map(coord => {
            const crossPointX = gridLineCenterX + coord.y * cellSize;
            const crossPointY = gridLineCenterY + coord.x * cellSize;
            return {
                x: crossPointX,
                y: crossPointY
            };
        });
        
        // 创建SVG线条
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.className = 'win-line';
        svg.style.position = 'absolute';
        svg.style.left = '0';
        svg.style.top = '0';
        svg.style.width = '100%';
        svg.style.height = '100%';
        svg.style.pointerEvents = 'none';
        svg.style.zIndex = '15'; // 在棋子上方
        
        const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
        line.setAttribute('x1', positions[0].x);
        line.setAttribute('y1', positions[0].y);
        line.setAttribute('x2', positions[positions.length - 1].x);
        line.setAttribute('y2', positions[positions.length - 1].y);
        line.setAttribute('stroke', '#FF0000');
        line.setAttribute('stroke-width', '4');
        line.setAttribute('stroke-linecap', 'round');
        line.setAttribute('opacity', '0.9');
        
        svg.appendChild(line);
        boardEl.appendChild(svg);
        
        // 胜利弹窗：在画红线的同时显示庆祝弹窗
        // 从全局 state 获取获胜方信息
        if (state && (state.outcome || state.winner)) {
            const winner = state.winner || (state.outcome === 'X_WIN' ? 'X' : state.outcome === 'O_WIN' ? 'O' : null);
            if (winner) {
                showVictoryModal(winner, null);
            }
        }
    }
    
    // ====================================================================
    // 坐标轴标签：稳定实现 - 自适应不同分辨率
    // ====================================================================
    // 关键点：
    // 1. 使用双重 requestAnimationFrame 确保布局完成
    // 2. 基于 #board 的实际位置计算，确保精确对齐
    // 3. 坐标轴放在 board-container 上，需要考虑 scale 影响
    // 4. 监听窗口 resize，自动重新计算位置
    // 5. 位置 = (#board相对位置 + 交叉点位置) / scale
    
    function updateCoordinates() {
        // 清除旧的坐标轴标签
        boardContainer.querySelectorAll('.board-coord').forEach(el => el.remove());
        
        // 获取 board-container 的 scale 值（从CSS中动态读取）
        const containerStyle = getComputedStyle(boardContainer);
        const transform = containerStyle.transform;
        let scale = 1.25; // 默认值
        if (transform && transform !== 'none') {
            const matrix = transform.match(/matrix\(([^)]+)\)/);
            if (matrix) {
                const values = matrix[1].split(',').map(v => parseFloat(v.trim()));
                if (values.length >= 1) {
                    scale = values[0];
                }
            }
        }
        
        // 获取 #board 相对于 board-container 的位置（基于实际渲染位置）
        const boardRect = boardEl.getBoundingClientRect();
        const containerRect = boardContainer.getBoundingClientRect();
        const boardOffsetX = (boardRect.left - containerRect.left) / scale;
        const boardOffsetY = (boardRect.top - containerRect.top) / scale;
        
        // 添加Y轴刻度（左侧）- 数字 1-15，1在最底下，15在最上面
        for (let x = 0; x < n; x++) {
            const crossPointY = gridLineCenterY + x * cellSize; // 交叉点Y坐标（相对于#board）
            const coordY = document.createElement('div');
            coordY.className = 'board-coord coord-y';
            coordY.textContent = String(n - x);
            coordY.style.position = 'absolute';
            coordY.style.left = `${boardOffsetX + paddingLeft - 20}px`; // 在棋盘左侧，距离左边缘20px
            coordY.style.top = `${boardOffsetY + crossPointY}px`;
            coordY.style.transform = 'translate(-50%, -50%)';
            coordY.style.textAlign = 'center';
            boardContainer.appendChild(coordY);
        }
        
        // 添加X轴刻度（下方）- 字母 A-O
        const lastRowIndex = n - 1;
        for (let y = 0; y < n; y++) {
            const crossPointX = gridLineCenterX + y * cellSize; // 交叉点X坐标（相对于#board）
            const crossPointY = gridLineCenterY + lastRowIndex * cellSize; // 最后一行交叉点Y
            const coordX = document.createElement('div');
            coordX.className = 'board-coord coord-x';
            coordX.textContent = letters[y];
            coordX.style.position = 'absolute';
            coordX.style.left = `${boardOffsetX + crossPointX}px`;
            coordX.style.top = `${boardOffsetY + crossPointY + 20}px`; // 在棋盘下方，距离下边缘20px
            coordX.style.transform = 'translate(-50%, -50%)';
            coordX.style.textAlign = 'center';
            boardContainer.appendChild(coordX);
        }
    }
    
    // 等待布局完成后更新坐标轴
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            updateCoordinates();
        });
    });
    
    // 关键：监听窗口 resize，确保分辨率变化时重新计算坐标轴位置
    // 使用防抖，避免频繁触发
    let resizeTimer = null;
    const handleResize = () => {
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => {
            requestAnimationFrame(() => {
                updateCoordinates();
            });
        }, 150); // 150ms 防抖
    };
    
    // 只在当前页面添加监听器，避免内存泄漏
    if (typeof window !== 'undefined') {
        window.addEventListener('resize', handleResize);
        // 存储清理函数，供页面卸载时调用
        if (!window._gameResizeHandlers) {
            window._gameResizeHandlers = [];
        }
        window._gameResizeHandlers.push(() => {
            window.removeEventListener('resize', handleResize);
        });
    }
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
        return;
    }
    
    const currentRoomId = typeof window !== 'undefined' ? window._currentRoomId : null;
    if (!currentRoomId) {
        return;
    }
    
    // 重要：确保点击的是真正的cell元素
    if (!e.currentTarget || !e.currentTarget.classList.contains('cell')) {
        e.preventDefault();
        e.stopPropagation();
        return;
    }
    
    const x = parseInt(e.currentTarget.dataset.x, 10);
    const y = parseInt(e.currentTarget.dataset.y, 10);
    
    // 验证坐标
    const n = grid ? grid.length : DEFAULT_N;
    if (isNaN(x) || isNaN(y) || x < 0 || x >= n || y < 0 || y >= n) {
        return;
    }
    
    if (!grid || !grid[x] || typeof grid[x][y] === 'undefined' || grid[x][y] !== '.') {
        return;
    }
    
    const side = state?.current || 'X';
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
        return;
    }
    
    if (evt.type === 'ERROR') {
        // 检测禁手错误
        const errorMsg = evt.payload?.message || evt.payload || '';
        if (errorMsg.includes('禁手') || errorMsg.includes('forbidden')) {
            showForbiddenTip();
        }
        
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
    let currentGrid = grid;
    if (g) {
        grid = g;
        currentGrid = g;
    }
    
    let lastClient = null;
    if (state?.lastMove && Number.isFinite(state.lastMove.x) && Number.isFinite(state.lastMove.y)) {
        lastClient = { x: state.lastMove.x, y: state.lastMove.y };
    }
    
    // 纯前端实现：游戏结束时，检测五连并闪烁
    let winPieces = null;
    if (state?.over || state?.outcome) {
        const outcome = state.outcome || (state.winner === 'X' ? 'X_WIN' : state.winner === 'O' || state.winner === '0' ? 'O_WIN' : null);
        if (outcome && (outcome === 'X_WIN' || outcome === 'O_WIN')) {
            // 修复：处理各种格式：0='X'（黑棋），1='O'（白棋）
            let winnerPiece = outcome === 'X_WIN' ? 'X' : 'O';
            // 如果后端返回的是数字，需要正确映射
            if (state.winner === 0 || state.winner === '0') {
                winnerPiece = 'X'; // 0 = 黑棋
            } else if (state.winner === 1 || state.winner === '1') {
                winnerPiece = 'O'; // 1 = 白棋
            }
            winPieces = detectWinLines(currentGrid, winnerPiece);
        }
    }
    
    renderBoard(currentGrid, lastClient, winPieces);
    
    // 更新游戏信息显示
    updateGameInfo(state, series);
    
    // 处理游戏结束
    if (state?.over) {
        stopCountdown();
        if (window.gameCallbacks && window.gameCallbacks.onGameOver) {
            window.gameCallbacks.onGameOver(state.winner);
        }
    }
    
    // 如果游戏结束但没有五连（超时、认输），也需要显示弹窗
    // 五连的情况会在 renderBoard 中画红线时一起触发
    if (state?.over && (!winPieces || winPieces.size < 5)) {
        const winner = state.winner || (state.outcome === 'X_WIN' ? 'X' : state.outcome === 'O_WIN' ? 'O' : null);
        if (winner) {
            showVictoryModal(winner, null);
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
    const currentGrid = grid;
    
    // 纯前端实现：游戏结束时，检测五连并画线
    let winPieces = null;
    if (snap.outcome && (snap.outcome === 'X_WIN' || snap.outcome === 'O_WIN')) {
        // 修复：处理各种格式：0='X'（黑棋），1='O'（白棋）
        let winnerPiece = snap.outcome === 'X_WIN' ? 'X' : 'O';
        // 如果后端返回的是数字，需要正确映射
        if (snap.winner === 0 || snap.winner === '0') {
            winnerPiece = 'X'; // 0 = 黑棋
        } else if (snap.winner === 1 || snap.winner === '1') {
            winnerPiece = 'O'; // 1 = 白棋
        }
        winPieces = detectWinLines(currentGrid, winnerPiece);
    }
    
    // 如果有游戏结束状态，即使没有五连（超时、认输），也显示弹窗
    // 但只在 renderBoard 中画红线时一起触发，避免重复
    renderBoard(currentGrid, snap.lastMove, winPieces);
    
    // 如果游戏结束但没有五连（超时、认输），也需要显示弹窗
    if (snap.outcome && (!winPieces || winPieces.size < 5)) {
        const winner = snap.outcome === 'X_WIN' ? 'X' : snap.outcome === 'O_WIN' ? 'O' : null;
        if (winner) {
            showVictoryModal(winner, snap);
        }
    }
    
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
    // 更新轮次信息
    const roundEl = document.getElementById('roundInfo');
    if (roundEl && series) {
        roundEl.textContent = String(series.index || 1);
    }
    
    // 更新当前执子方（保留原有功能）
    const currentPlayerEl = document.getElementById('currentPlayer');
    if (currentPlayerEl) {
        const current = currentSide || state?.current || '-';
        currentPlayerEl.textContent = current === 'X' ? 'Black' : current === 'O' ? 'White' : '-';
    }
    
    // 更新比分
    const scoreEl = document.getElementById('scoreInfo');
    if (scoreEl && series) {
        scoreEl.textContent = `${series.blackWins || 0}:${series.whiteWins || 0}`;
    }
    
    // 更新游戏状态（已结束/进行中）
    const gameStatusEl = document.getElementById('gameStatus');
    const gameStatusCapsule = document.getElementById('gameStatusCapsule');
    if (gameStatusEl) {
        if (state?.over) {
            gameStatusEl.textContent = 'Ended';
            if (gameStatusCapsule) {
                gameStatusCapsule.style.background = 'rgba(239, 68, 68, 0.1)';
                gameStatusCapsule.style.borderColor = 'rgba(239, 68, 68, 0.2)';
            }
        } else {
            gameStatusEl.textContent = 'Playing';
            if (gameStatusCapsule) {
                gameStatusCapsule.style.background = 'rgba(255, 255, 255, 0.75)';
                gameStatusCapsule.style.borderColor = 'rgba(255, 255, 255, 0.9)';
            }
        }
    }
    
    // 获取自己的执子方
    let mySide = typeof window !== 'undefined' ? (window.mySide || window._mySide) : null;
    if (!mySide) {
        // 如果不知道自己的执子方，尝试从DOM获取
        const selfSideText = document.getElementById('selfSideText');
        if (selfSideText) {
            const selfSide = selfSideText.textContent.trim().toLowerCase();
            if (selfSide === 'black') {
                mySide = 'X';
                if (typeof window !== 'undefined') window._mySide = 'X';
            } else if (selfSide === 'white') {
                mySide = 'O';
                if (typeof window !== 'undefined') window._mySide = 'O';
            }
        }
    }
    
    // 更新Winner标签和头像光环效果
    const selfWinnerEl = document.getElementById('selfWinner');
    const opponentWinnerEl = document.getElementById('opponentWinner');
    const selfAvatar = document.querySelector('.player-panel.player-left .player-avatar');
    const opponentAvatar = document.querySelector('.player-panel.player-right .player-avatar');
    const turnIndicator = document.getElementById('turnIndicator');
    
    // 先隐藏Winner标签
    if (selfWinnerEl) {
        selfWinnerEl.style.opacity = '0';
        selfWinnerEl.classList.remove('show');
    }
    if (opponentWinnerEl) {
        opponentWinnerEl.style.opacity = '0';
        opponentWinnerEl.classList.remove('show');
    }
    
    // 先移除所有头像光环
    if (selfAvatar) selfAvatar.classList.remove('active-turn');
    if (opponentAvatar) opponentAvatar.classList.remove('active-turn');
    
    if (state?.over && state?.winner) {
        // 游戏结束，显示Winner标签
        const isSelfWinner = mySide && ((state.winner === 'X' && mySide === 'X') || (state.winner === 'O' && mySide === 'O'));
        if (isSelfWinner) {
            if (selfWinnerEl) {
                selfWinnerEl.style.opacity = '1';
                selfWinnerEl.classList.add('show');
            }
        } else {
            if (opponentWinnerEl) {
                opponentWinnerEl.style.opacity = '1';
                opponentWinnerEl.classList.add('show');
            }
        }
        // 更新顶部提示
        if (turnIndicator) {
            turnIndicator.textContent = '-';
        }
    } else if (!state?.over && state?.current) {
        // 游戏进行中，更新头像光环和顶部提示
        const isMyTurn = mySide && ((state.current === 'X' && mySide === 'X') || (state.current === 'O' && mySide === 'O'));
        
        if (isMyTurn) {
            // 自己的回合：自己的头像显示光环
            if (selfAvatar) selfAvatar.classList.add('active-turn');
            if (opponentAvatar) opponentAvatar.classList.remove('active-turn');
        } else {
            // 对手的回合：对手的头像显示光环
            if (selfAvatar) selfAvatar.classList.remove('active-turn');
            if (opponentAvatar) opponentAvatar.classList.add('active-turn');
        }
        
        // 更新顶部当前执子方提示
        if (turnIndicator) {
            const currentSide = state.current === 'X' ? 'Black' : state.current === 'O' ? 'White' : '-';
            turnIndicator.textContent = `${currentSide} to play`;
        }
    } else {
        // 无当前执子方
        if (turnIndicator) {
            turnIndicator.textContent = '-';
        }
    }
    
    // 更新倒计时显示（根据当前执子方）
    updateTimerDisplay();
}

/**
 * 显示禁手提示
 */
function showForbiddenTip() {
    const tip = document.getElementById('forbiddenTip');
    if (!tip) return;
    
    // 如果提示框正在显示，不重复显示
    if (tip.classList.contains('show')) {
        return;
    }
    
    // 清除之前的定时器
    if (tip._hideTimer) {
        clearTimeout(tip._hideTimer);
        tip._hideTimer = null;
    }
    
    // 显示提示框
    tip.classList.add('show');
    
    // 2秒后自动消失
    tip._hideTimer = setTimeout(() => {
        tip.classList.remove('show');
        tip._hideTimer = null;
    }, 2000);
}

/**
 * 显示游戏结束庆祝弹窗
 * @param {string} winner - 获胜方 'X' 或 'O'
 * @param {Object} snap - 快照数据（可选）
 */
function showVictoryModal(winner, snap) {
    const modal = document.getElementById('victoryModal');
    const winnerNameEl = document.getElementById('victoryWinnerName');
    const sideBadgeEl = document.getElementById('victorySideBadge');
    const sideTextEl = document.getElementById('victorySideText');
    
    if (!modal || !winnerNameEl || !sideBadgeEl || !sideTextEl) return;
    
    // 获取获胜者名字
    const mySide = typeof window !== 'undefined' ? (window.mySide || window._mySide) : null;
    let winnerName = 'Unknown';
    
    if (winner === 'X' || winner === 'x' || winner === 0 || winner === '0') {
        // 黑棋获胜
        if (mySide === 'X') {
            // 自己获胜
            const selfNameEl = document.getElementById('selfName');
            winnerName = selfNameEl ? selfNameEl.textContent : 'You';
        } else {
            // 对手获胜
            const opponentNameEl = document.getElementById('opponentName');
            winnerName = opponentNameEl ? opponentNameEl.textContent : 'Opponent';
        }
        sideBadgeEl.className = 'victory-side-badge black';
        sideTextEl.textContent = 'Black';
    } else if (winner === 'O' || winner === 'o' || winner === 1 || winner === '1') {
        // 白棋获胜
        if (mySide === 'O') {
            // 自己获胜
            const selfNameEl = document.getElementById('selfName');
            winnerName = selfNameEl ? selfNameEl.textContent : 'You';
        } else {
            // 对手获胜
            const opponentNameEl = document.getElementById('opponentName');
            winnerName = opponentNameEl ? opponentNameEl.textContent : 'Opponent';
        }
        sideBadgeEl.className = 'victory-side-badge white';
        sideTextEl.textContent = 'White';
    }
    
    // 更新弹窗内容
    winnerNameEl.textContent = winnerName;
    
    // 清除之前的定时器和事件监听器（避免重复绑定）
    if (modal._autoHideTimer) {
        clearTimeout(modal._autoHideTimer);
        modal._autoHideTimer = null;
    }
    const existingHandler = modal._clickHandler;
    if (existingHandler) {
        modal.removeEventListener('click', existingHandler);
        modal._clickHandler = null;
    }
    
    // 关闭弹窗的函数
    const closeModal = () => {
        modal.classList.remove('show');
        if (modal._autoHideTimer) {
            clearTimeout(modal._autoHideTimer);
            modal._autoHideTimer = null;
        }
        if (modal._clickHandler) {
            modal.removeEventListener('click', modal._clickHandler);
            modal._clickHandler = null;
        }
    };
    
    // 添加点击关闭功能（点击弹窗任何地方都关闭）
    const clickHandler = (e) => {
        closeModal();
    };
    modal.addEventListener('click', clickHandler);
    modal._clickHandler = clickHandler;
    
    // 显示弹窗
    modal.classList.add('show');
    
    // 4秒后自动消失
    modal._autoHideTimer = setTimeout(() => {
        closeModal();
    }, 4000);
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
    // 更新顶部状态栏的倒计时（保留兼容性）
    const timerEl = document.getElementById('timer');
    if (timerEl) {
        timerEl.textContent = currentCountdown > 0 ? currentCountdown : '--';
    }
    
    // 更新玩家卡片中的倒计时
    // 获取当前执子方
    const currentSide = state?.current;
    if (!currentSide) {
        // 如果没有当前执子方，隐藏所有倒计时
        hideAllCountdowns();
        return;
    }
    
    // 获取自己的执子方
    const mySide = typeof window !== 'undefined' ? (window.mySide || window._mySide) : null;
    if (!mySide) {
        // 如果不知道自己的执子方，尝试从DOM获取
        const selfSideText = document.getElementById('selfSideText');
        if (selfSideText) {
            const selfSide = selfSideText.textContent.trim().toLowerCase();
            if (selfSide === 'black') {
                window._mySide = 'X';
            } else if (selfSide === 'white') {
                window._mySide = 'O';
            }
        }
    }
    
    const actualMySide = typeof window !== 'undefined' ? (window.mySide || window._mySide) : null;
    
    // 判断当前执子的是自己还是对手
    const isMyTurn = actualMySide && (currentSide === actualMySide);
    
    // 获取倒计时元素
    const selfCountdown = document.getElementById('selfCountdown');
    const opponentCountdown = document.getElementById('opponentCountdown');
    const selfCountdownText = document.getElementById('selfCountdownText');
    const opponentCountdownText = document.getElementById('opponentCountdownText');
    const selfCountdownProgress = document.getElementById('selfCountdownProgress');
    const opponentCountdownProgress = document.getElementById('opponentCountdownProgress');
    
    // 更新倒计时显示和样式
    if (isMyTurn) {
        // 当前是自己的回合，显示自己的倒计时
        updateCountdownDisplay(selfCountdown, selfCountdownText, selfCountdownProgress, currentCountdown);
        hideCountdown(opponentCountdown);
    } else {
        // 当前是对手的回合，显示对手的倒计时
        updateCountdownDisplay(opponentCountdown, opponentCountdownText, opponentCountdownProgress, currentCountdown);
        hideCountdown(selfCountdown);
    }
}

/**
 * 更新倒计时显示和样式
 */
function updateCountdownDisplay(countdownEl, textEl, progressEl, seconds) {
    if (!countdownEl || !textEl) return;
    
    // 显示倒计时
    countdownEl.style.opacity = '1';
    countdownEl.classList.add('show');
    
    // 更新文本（只显示数字，如 "30s"）
    textEl.textContent = seconds > 0 ? `${seconds}s` : '';
    
    // 根据剩余时间更新样式
    textEl.classList.remove('normal', 'warning', 'danger');
    if (progressEl) {
        progressEl.classList.remove('warning', 'danger');
    }
    
    if (seconds > 10) {
        // 正常状态：> 10秒
        textEl.classList.add('normal');
        if (progressEl) {
            progressEl.style.stroke = '#3B82F6';
        }
    } else if (seconds > 5) {
        // 警告状态：≤ 10秒
        textEl.classList.add('warning');
        if (progressEl) {
            progressEl.classList.add('warning');
            progressEl.style.stroke = '#F59E0B';
        }
    } else if (seconds > 0) {
        // 危险状态：≤ 5秒
        textEl.classList.add('danger');
        if (progressEl) {
            progressEl.classList.add('danger');
            progressEl.style.stroke = '#EF4444';
        }
    }
    
    // 更新进度条（假设总时间是30秒，可以根据实际情况调整）
    const totalTime = 30; // 总时间（秒）
    const progress = seconds > 0 ? (seconds / totalTime) : 0;
    const circumference = 2 * Math.PI * 45; // 半径45的圆周长
    const offset = circumference * (1 - progress);
    
    if (progressEl) {
        progressEl.style.strokeDashoffset = offset;
    }
}

/**
 * 隐藏倒计时
 */
function hideCountdown(countdownEl) {
    if (countdownEl) {
        countdownEl.style.opacity = '0';
        countdownEl.classList.remove('show');
    }
}

/**
 * 隐藏所有倒计时
 */
function hideAllCountdowns() {
    const selfCountdown = document.getElementById('selfCountdown');
    const opponentCountdown = document.getElementById('opponentCountdown');
    hideCountdown(selfCountdown);
    hideCountdown(opponentCountdown);
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
    hideAllCountdowns();
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

