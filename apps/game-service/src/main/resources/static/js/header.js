(() => {
    const DEFAULT_AVATAR = '/game-service/images/avatar-default.png';
    const BELL_ICON = '/game-service/images/bell.svg';
    const TEMPLATE = `
        <header class="gh-header">
            <div class="gh-header-inner">
                <div class="logo">
                    <div class="logo-square"></div>
                    <span>GameHub</span>
                </div>
                <nav class="gh-nav">
                    <a href="/game-service/index.html" data-nav="home">大厅</a>
                    <a href="/game-service/lobby.html" data-nav="lobby">游戏</a>
                    <a href="/game-service/game.html" data-nav="profile">我的</a>
                </nav>
                <div class="header-tools">
                    <button class="bell" aria-label="通知">
                        <img src="${BELL_ICON}" alt="通知">
                    </button>
                    <div class="avatar-pill">
                        <div class="avatar" data-role="avatar"></div>
                        <span data-role="username">玩家</span>
                        <button class="link-btn" data-role="logout">退出</button>
                    </div>
                </div>
            </div>
        </header>
    `;

    window.addEventListener('DOMContentLoaded', () => {
        const containers = document.querySelectorAll('[data-component="global-header"]');
        if (!containers.length) return;

        containers.forEach(async (container) => {
            container.innerHTML = TEMPLATE;
            activateNav(container);
            await hydrateUserInfo(container);
            bindLogout(container);
        });
    });

    function activateNav(root) {
        const active = root.dataset.active || (root.parentElement?.dataset?.active) || '';
        root.querySelectorAll('[data-nav]').forEach(link => {
            if (link.dataset.nav === active) {
                link.classList.add('active');
            }
        });
    }

    async function hydrateUserInfo(root) {
        if (typeof ensureAuthenticated !== 'function') {
            return;
        }
        try {
            await ensureAuthenticated();
            const profile = await getUserInfo();
            const display = profile?.nickname?.trim() || profile?.username || '玩家';
            const avatarEl = root.querySelector('[data-role="avatar"]');
            const nameEl = root.querySelector('[data-role="username"]');
            if (nameEl) nameEl.textContent = display;
            const photo = profile?.avatarUrl?.trim();
            if (avatarEl) {
                avatarEl.style.backgroundImage = `url('${photo || DEFAULT_AVATAR}')`;
            }
        } catch (err) {
            console.warn('获取用户信息失败（header）', err);
        }
    }

    function bindLogout(root) {
        const btn = root.querySelector('[data-role="logout"]');
        if (!btn) return;
        btn.addEventListener('click', () => {
            if (!confirm('确定退出登录吗？')) return;
            if (typeof clearToken === 'function') {
                clearToken();
            }
            const form = document.createElement('form');
            form.method = 'post';
            form.action = '/logout';
            document.body.appendChild(form);
            form.submit();
        });
    }
})();

