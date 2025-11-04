/**
 * WebSocket 连接管理模块
 */

let stomp = null;
let socket = null;
let subscriptions = new Map();

/**
 * 连接 WebSocket
 * @param {string} token - 认证 token
 * @param {Object} callbacks - 回调函数对象
 * @param {Function} callbacks.onConnect - 连接成功回调
 * @param {Function} callbacks.onError - 连接失败回调
 * @param {Function} callbacks.onEvent - 收到房间事件回调
 * @param {Function} callbacks.onSeatKey - 收到 seatKey 回调
 * @param {Function} callbacks.onFullSync - 收到完整同步回调
 */
function connectWebSocket(token, callbacks = {}) {
    if (stomp && stomp.connected) {
        console.warn('WebSocket 已连接');
        return;
    }
    
    console.log('创建 SockJS 连接: /game-service/ws');
    socket = new SockJS('/game-service/ws');
    stomp = Stomp.over(socket);
    
    // 设置调试模式（可选，生产环境可关闭）
    stomp.debug = function(str) {
        console.log('STOMP:', str);
    };
    
    const headers = token ? { Authorization: 'Bearer ' + token } : {};
    console.log('WebSocket 连接 headers:', headers);
    
    try {
        stomp.connect(headers, (frame) => {
            console.log('WebSocket 连接成功，frame:', frame);
            
            if (callbacks.onConnect) {
                callbacks.onConnect();
            }
        }, (error) => {
            console.error('WebSocket 连接失败:', error);
            console.error('错误详情:', error.headers, error.body);
            if (callbacks.onError) {
                callbacks.onError(error);
            }
        });
    } catch (error) {
        console.error('WebSocket 连接异常:', error);
        if (callbacks.onError) {
            callbacks.onError(error);
        }
    }
}

/**
 * 订阅房间事件
 * @param {string} roomId - 房间ID
 * @param {Function} onEvent - 事件处理函数
 */
function subscribeRoom(roomId, onEvent) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    const topic = `/topic/room.${roomId}`;
    if (subscriptions.has(topic)) {
        subscriptions.get(topic).unsubscribe();
    }
    
    const sub = stomp.subscribe(topic, (frame) => {
        try {
            const evt = JSON.parse(frame.body);
            onEvent(evt);
        } catch (e) {
            console.error('解析事件失败:', e);
        }
    });
    
    subscriptions.set(topic, sub);
}

/**
 * 订阅 seatKey 推送
 * @param {Function} onSeatKey - seatKey 处理函数
 */
function subscribeSeatKey(onSeatKey) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    const topic = '/user/queue/gomoku.seat';
    if (subscriptions.has(topic)) {
        subscriptions.get(topic).unsubscribe();
    }
    
    const sub = stomp.subscribe(topic, (frame) => {
        try {
            const payload = JSON.parse(frame.body);
            const seatKey = typeof payload === 'string' ? payload : payload.seatKey;
            const side = payload.side || 'X';
            onSeatKey(seatKey, side);
        } catch (e) {
            console.error('解析 seatKey 失败:', e);
        }
    });
    
    subscriptions.set(topic, sub);
}

/**
 * 订阅完整同步推送
 * @param {Function} onFullSync - 完整同步处理函数
 */
function subscribeFullSync(onFullSync) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    const topic = '/user/queue/gomoku.full';
    if (subscriptions.has(topic)) {
        subscriptions.get(topic).unsubscribe();
    }
    
    const sub = stomp.subscribe(topic, (frame) => {
        try {
            const snap = JSON.parse(frame.body);
            onFullSync(snap);
        } catch (e) {
            console.error('解析完整同步失败:', e);
        }
    });
    
    subscriptions.set(topic, sub);
}

/**
 * 发送恢复请求
 * @param {string} roomId - 房间ID
 * @param {string} seatKey - 座位令牌（可选）
 */
function sendResume(roomId, seatKey = null) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    stomp.send('/app/gomoku.resume', {}, JSON.stringify({ roomId, seatKey }));
}

/**
 * 发送落子指令
 * @param {string} roomId - 房间ID
 * @param {number} x - X 坐标
 * @param {number} y - Y 坐标
 * @param {string} side - 执子方（X 或 O）
 * @param {string} seatKey - 座位令牌（可选）
 */
function sendPlace(roomId, x, y, side, seatKey = null) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    const cmd = { roomId, x, y, side, seatKey };
    stomp.send('/app/gomoku.place', {}, JSON.stringify(cmd));
}

/**
 * 发送认输指令
 * @param {string} roomId - 房间ID
 * @param {string} seatKey - 座位令牌（可选）
 */
function sendResign(roomId, seatKey = null) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    stomp.send('/app/gomoku.resign', {}, JSON.stringify({ roomId, seatKey }));
}

/**
 * 发送重开指令
 * @param {string} roomId - 房间ID
 * @param {string} seatKey - 座位令牌（可选）
 */
function sendRestart(roomId, seatKey = null) {
    if (!stomp || !stomp.connected) {
        console.warn('WebSocket 未连接');
        return;
    }
    
    stomp.send('/app/gomoku.restart', {}, JSON.stringify({ roomId, seatKey }));
}

/**
 * 断开 WebSocket 连接
 */
function disconnectWebSocket() {
    subscriptions.forEach(sub => sub.unsubscribe());
    subscriptions.clear();
    
    if (stomp && stomp.connected) {
        stomp.disconnect();
    }
    
    if (socket) {
        socket.close();
    }
    
    stomp = null;
    socket = null;
}

/**
 * 检查 WebSocket 是否已连接
 * @returns {boolean}
 */
function isConnected() {
    return stomp !== null && stomp.connected;
}

