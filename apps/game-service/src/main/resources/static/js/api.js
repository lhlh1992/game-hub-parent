/**
 * REST API 调用封装
 */

/**
 * 创建房间
 * @param {string} token - 兼容旧调用，实际会从网关获取最新 token
 * @param {string} mode - 模式：PVE 或 PVP
 * @param {string} aiPiece - AI 执子：X 或 O
 * @param {string} rule - 规则：STANDARD 或 RENJU
 * @returns {Promise<string>} 房间ID
 */
async function createRoom(token, mode = 'PVE', aiPiece = 'O', rule = 'STANDARD') {
    const url = `/game-service/api/gomoku/new?mode=${mode}&aiPiece=${aiPiece}&rule=${rule}`;

    // 1) 每次请求前，先从网关获取“可用的最新 token”（内部会自动刷新）
    let freshToken = await getTokenFromGateway();
    if (!freshToken) freshToken = token; // 兜底：若异常则退回入参

    // 内部方法：携带指定 token 发起一次请求
    const doFetch = async (tok) => {
        return fetch(url, {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + tok
            }
        });
    };

    // 2) 发起请求
    let res;
    try {
        res = await doFetch(freshToken);
    } catch (error) {
        handleFetchFailure(error, '创建房间请求异常');
        throw error;
    }

    // 401：会话失效 → 自动登出
    if (res.status === 401) {
        handleAuthExpiredResponse(res, '创建房间接口返回 401');
        throw new Error('创建房间失败：会话已失效');
    }

    if (!res.ok) {
        const text = await res.text();
        throw new Error(`创建房间失败 (HTTP ${res.status}): ${text}`);
    }

    return await res.text(); // 返回房间ID
}

/**
 * 获取用户信息
 * @param {string} token - 认证 token
 * @returns {Promise<Object>} 用户信息
 */
async function getMe(token) {
    let res;
    try {
        res = await fetch('/game-service/me', {
        headers: {
            'Authorization': 'Bearer ' + token
        }
    });
    } catch (error) {
        handleFetchFailure(error, 'GET /game-service/me 请求异常 (getMe)');
        throw error;
    }

    if (res.status === 401) {
        handleAuthExpiredResponse(res, 'GET /game-service/me 返回 401 (getMe)');
        throw new Error('获取用户信息失败：会话已失效');
    }

    if (!res.ok) {
        throw new Error(`获取用户信息失败 (HTTP ${res.status})`);
    }

    return await res.json();
}



