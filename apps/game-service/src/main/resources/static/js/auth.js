/**
 * 认证模块 - 通过 Gateway OAuth2 登录和 Token 管理
 * 
 * 注意：Keycloak 客户端类型为 confidential，前端不能直接连接 Keycloak。
 * 需要通过 Gateway 的 OAuth2 登录流程，然后从 Gateway 获取 token。
 */

const TOKEN_STORAGE_KEY = 'access_token';
const GATEWAY_LOGIN_URL = '/oauth2/authorization/keycloak';
const GATEWAY_TOKEN_URL = '/token';

/**
 * 从 Gateway 获取当前登录用户的 token
 * @returns {Promise<string>} 返回 access_token
 */
async function getTokenFromGateway() {
    try {
        const res = await fetch(GATEWAY_TOKEN_URL, {
            credentials: 'include' // 包含 cookies，Gateway 使用 session 存储认证信息
        });
        
        if (!res.ok) {
            if (res.status === 401) {
                return null; // 未登录
            }
            throw new Error(`获取 token 失败 (HTTP ${res.status})`);
        }
        
        const data = await res.json();
        const token = data.access_token;
        
        if (!token) {
            throw new Error('Gateway 返回的 token 为空');
        }
        
        // 保存 token 到 localStorage
        localStorage.setItem(TOKEN_STORAGE_KEY, token);
        
        return token;
    } catch (error) {
        console.error('从 Gateway 获取 token 失败:', error);
        return null;
    }
}

/**
 * 通过 Gateway OAuth2 登录
 * 如果未登录，跳转到 Gateway 的 OAuth2 登录端点
 * @returns {Promise<string>} 返回 access_token
 */
async function initAndLogin() {
    // 先尝试从 Gateway 获取 token
    let token = await getTokenFromGateway();
    
    if (token) {
        console.log('从 Gateway 获取 token 成功');
        return token;
    }
    
    // 如果没有 token，跳转到 Gateway 的 OAuth2 登录
    console.log('未登录，跳转到 Gateway OAuth2 登录...');
    const currentUrl = window.location.href;
    // Gateway 登录成功后会重定向回原页面，或者我们需要手动处理重定向
    window.location.href = GATEWAY_LOGIN_URL + '?redirect_uri=' + encodeURIComponent(currentUrl);
    
    // 不会执行到这里，因为页面会跳转
    throw new Error('正在跳转到登录页面...');
}

/**
 * 从 localStorage 获取 token
 * @returns {string|null}
 */
function getToken() {
    return localStorage.getItem(TOKEN_STORAGE_KEY);
}

/**
 * 保存 token 到 localStorage
 * @param {string} token
 */
function saveToken(token) {
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
}

/**
 * 清除 token
 */
function clearToken() {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    // 通过 Gateway 登出（跳转到 Gateway 的登出端点）
    // 注意：Gateway 可能需要配置登出端点
}

/**
 * 验证 token 是否有效（通过调用 /me 接口）
 * @param {string} token
 * @returns {Promise<boolean>}
 */
async function validateToken(token) {
    if (!token) return false;
    
    try {
        const res = await fetch('/game-service/me', {
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });
        return res.ok;
    } catch (e) {
        return false;
    }
}

/**
 * 确保用户已登录，如果未登录则自动触发登录
 * @returns {Promise<string>} 返回有效的 access_token
 */
async function ensureAuthenticated() {
    // 先尝试从 Gateway 获取最新的 token（可能已过期）
    let token = await getTokenFromGateway();
    
    // 如果 Gateway 有 token，验证是否有效
    if (token) {
        const isValid = await validateToken(token);
        if (isValid) {
            return token;
        } else {
            // Token 无效，清除
            clearToken();
        }
    }
    
    // 检查 localStorage 中是否有缓存的 token
    token = getToken();
    if (token) {
        const isValid = await validateToken(token);
        if (isValid) {
            return token;
        } else {
            // Token 无效，清除
            clearToken();
        }
    }
    
    // 没有 token 或 token 无效，触发登录
    token = await initAndLogin();
    return token;
}

/**
 * 获取当前用户信息
 * @returns {Promise<Object>} 用户信息对象
 */
async function getUserInfo() {
    const token = getToken();
    if (!token) return null;
    
    try {
        const res = await fetch('/game-service/me', {
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });
        if (res.ok) {
            return await res.json();
        }
    } catch (e) {
        console.error('获取用户信息失败:', e);
    }
    return null;
}

