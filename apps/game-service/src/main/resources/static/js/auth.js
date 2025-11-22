/**
 * 认证模块 - 通过 Gateway OAuth2 登录和 Token 管理
 *
 * 注意：Keycloak 客户端类型为 confidential，前端不能直接连接 Keycloak。
 * 需要通过 Gateway 的 OAuth2 登录流程，然后从 Gateway 获取 token。
 */

const TOKEN_STORAGE_KEY = 'access_token';
const GATEWAY_LOGIN_URL = '/oauth2/authorization/keycloak';
const GATEWAY_TOKEN_URL = '/token';
const AUTH_REDIRECT_HEADER = 'X-Auth-Redirect-To';
const AUTH_MODAL_ID = 'auth-expired-modal';

let sessionLoggingOut = false;

function ensureAuthModalMounted() {
    if (document.getElementById(AUTH_MODAL_ID)) {
        return;
    }
    const modal = document.createElement('div');
    modal.id = AUTH_MODAL_ID;
    modal.innerHTML = `
        <style>
            .auth-modal-backdrop {
                position: fixed;
                inset: 0;
                background: rgba(255, 245, 243, 0.85);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 9999;
                visibility: hidden;
                opacity: 0;
                transition: opacity .25s ease;
                backdrop-filter: blur(6px);
            }
            .auth-modal-card {
                width: 380px;
                border-radius: 20px;
                padding: 28px 32px;
                background: linear-gradient(135deg, #fff8f6 0%, #ffe3de 100%);
                box-shadow: 0 20px 45px rgba(255, 132, 132, 0.25);
                text-align: center;
                font-family: "Helvetica Neue", "PingFang SC", sans-serif;
                color: #682d2d;
            }
            .auth-modal-card h3 {
                margin: 0 0 10px;
                font-size: 20px;
                color: #ff6a94;
            }
            .auth-modal-card p {
                margin: 0 0 22px;
                line-height: 1.5;
                color: #9b6c6c;
            }
            .auth-modal-actions {
                display: flex;
                gap: 14px;
                justify-content: center;
            }
            .auth-modal-btn {
                flex: 1;
                padding: 12px 0;
                border-radius: 999px;
                border: none;
                font-size: 15px;
                cursor: pointer;
                transition: transform .15s, box-shadow .15s;
            }
            .auth-modal-btn.primary {
                background: linear-gradient(120deg, #ff8db2, #ff6b74);
                color: #fff;
                box-shadow: 0 10px 20px rgba(255, 118, 148, 0.35);
            }
            .auth-modal-btn.secondary {
                background: #fff;
                color: #ff8db2;
                border: 2px solid rgba(255, 141, 178, 0.3);
            }
            .auth-modal-btn:hover {
                transform: translateY(-1px);
            }
        </style>
        <div class="auth-modal-backdrop">
            <div class="auth-modal-card">
                <h3>登录状态失效</h3>
                <p>很抱歉，您的登录已过期或被其他设备挤下线，请重新登录后继续。</p>
                <div class="auth-modal-actions">
                    <button id="auth-modal-retry" class="auth-modal-btn primary">重新登录</button>
                    <button id="auth-modal-cancel" class="auth-modal-btn secondary">稍后</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(modal);

    const backdrop = modal.querySelector('.auth-modal-backdrop');
    const retryBtn = modal.querySelector('#auth-modal-retry');
    const cancelBtn = modal.querySelector('#auth-modal-cancel');

    retryBtn.addEventListener('click', () => {
        hideAuthModal();
        window.location.href = GATEWAY_LOGIN_URL + '?redirect_uri=' + encodeURIComponent(window.location.href);
    });

    cancelBtn.addEventListener('click', () => {
        hideAuthModal();
    });

    modal.showAuthModal = () => {
        sessionLoggingOut = true;
        backdrop.style.visibility = 'visible';
        backdrop.style.opacity = '1';
    };

    modal.hideAuthModal = () => {
        backdrop.style.opacity = '0';
        backdrop.style.visibility = 'hidden';
    };
}

function showAuthModal(message) {
    ensureAuthModalMounted();
    const modal = document.getElementById(AUTH_MODAL_ID);
    const text = modal.querySelector('p');
    text.textContent = message || '会话已失效，请重新登录。';
    modal.showAuthModal();
}

function hideAuthModal() {
    const modal = document.getElementById(AUTH_MODAL_ID);
    if (modal && typeof modal.hideAuthModal === 'function') {
        modal.hideAuthModal();
    }
    sessionLoggingOut = false;
}

/**
 * 会话失效时，执行与手动“退出”按钮相同的操作：
 * 1) 清除本地 token
 * 2) 提交表单 POST /logout，让后端 / Keycloak 完整登出
 */
function performSessionLogout(reason = '') {
    if (sessionLoggingOut) {
        return;
    }
    sessionLoggingOut = true;

    try {
        clearToken();
    } catch (e) {
        // ignore
    }

    showAuthModal('会话已失效，请点击“重新登录”再次进入游戏。');
}

function handleAuthExpiredResponse(res, context) {
    if (res && typeof res.headers?.get === 'function') {
        const redirect = res.headers.get(AUTH_REDIRECT_HEADER);
        if (redirect) {
            showAuthModal('登录状态已过期，请重新登录。');
            return;
        }
    }
    performSessionLogout(context);
}

function handleFetchFailure(error, context) {
    performSessionLogout(context || 'fetch error');
}

/**
 * 从 Gateway 获取当前登录用户的 token
 * @returns {Promise<string|null>} 返回 access_token，401 时返回 null（已触发登出）
 */
async function getTokenFromGateway() {
    try {
        const res = await fetch(GATEWAY_TOKEN_URL, {
            credentials: 'include' // 包含 cookies，Gateway 使用 session 存储认证信息
        });

        if (!res.ok) {
            if (res.status === 401) {
                handleAuthExpiredResponse(res, 'GET /token 返回 401');
                return null;
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
        handleFetchFailure(error, '从 Gateway 获取 token 失败');
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
        sessionLoggingOut = false;
        return token;
    }

    // 如果没有 token，跳转到 Gateway 的 OAuth2 登录
    const currentUrl = window.location.href;
    window.location.href =
        GATEWAY_LOGIN_URL + '?redirect_uri=' + encodeURIComponent(currentUrl);

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
    sessionLoggingOut = false;
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

        if (res.status === 401) {
            handleAuthExpiredResponse(res, '/game-service/me 返回 401 (validateToken)');
            return false;
        }

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
 * @returns {Promise<Object|null>} 用户信息对象
 */
async function getUserInfo() {
    const token = getToken();
    if (!token) return null;

    // 优先从 system-service 获取完整资料（含昵称/头像等）
    const profile = await fetchSystemUserProfile(token);
    if (profile) {
        return profile;
    }

    // 兜底：从 game-service 的 /me 接口读取 JWT 基础信息
    return await fetchGatewayUserProfile(token);
}

async function fetchSystemUserProfile(token) {
    try {
        const res = await fetch('/system-service/api/users/me', {
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });

        if (res.status === 401) {
            handleAuthExpiredResponse(res, '/system-service/api/users/me 返回 401');
            return null;
        }

        if (!res.ok) {
            return null;
        }

        const body = await res.json();
        const data = body?.data || body;
        if (data) {
            if (!data.nickname && data.username) {
                data.nickname = data.username;
            }
            return data;
        }
    } catch (error) {
        // 获取系统用户信息异常
    }
    return null;
}

async function fetchGatewayUserProfile(token) {
    try {
        const res = await fetch('/game-service/me', {
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });

        if (res.status === 401) {
            handleAuthExpiredResponse(res, '/game-service/me 返回 401 (fallback)');
            return null;
        }

        if (!res.ok) {
            return null;
        }

        const profile = await res.json();
        if (profile && !profile.nickname && profile.username) {
            profile.nickname = profile.username;
        }
        return profile;
    } catch (error) {
        return null;
    }
}



