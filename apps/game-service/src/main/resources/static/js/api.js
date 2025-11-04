/**
 * REST API 调用封装
 */

/**
 * 创建房间
 * @param {string} token - 认证 token
 * @param {string} mode - 模式：PVE 或 PVP
 * @param {string} aiPiece - AI 执子：X 或 O
 * @param {string} rule - 规则：STANDARD 或 RENJU
 * @returns {Promise<string>} 房间ID
 */
async function createRoom(token, mode = 'PVE', aiPiece = 'O', rule = 'STANDARD') {
    const url = `/game-service/api/gomoku/new?mode=${mode}&aiPiece=${aiPiece}&rule=${rule}`;
    const res = await fetch(url, {
        method: 'POST',
        headers: {
            'Authorization': 'Bearer ' + token
        }
    });
    
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

