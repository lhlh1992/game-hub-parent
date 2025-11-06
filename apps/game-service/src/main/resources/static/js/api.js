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

    // 2) 第一次尝试
    let res = await doFetch(freshToken);

    // 3) 如果 401，说明 token 失效，立即再向网关取一次并重试一次
    if (res.status === 401) {
        const retryToken = await getTokenFromGateway();
        if (retryToken) {
            res = await doFetch(retryToken);
        }
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
    const res = await fetch('/game-service/me', {
        headers: {
            'Authorization': 'Bearer ' + token
        }
    });
    
    if (!res.ok) {
        throw new Error(`获取用户信息失败 (HTTP ${res.status})`);
    }
    
    return await res.json();
}

