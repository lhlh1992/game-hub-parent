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
                    <div class="avatar-pill is-compact" data-role="avatar-trigger">
                        <div class="avatar" data-role="avatar"></div>
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
            ensureProfileDrawer();
            await hydrateUserInfo(container);
            bindLogout(container);
            bindAvatarTrigger(container);
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
            const drawerAvatar = document.querySelector('[data-drawer-avatar]');
            const drawerName = document.querySelector('[data-drawer-name]');
            const drawerId = document.querySelector('[data-drawer-id]');
            if (drawerAvatar) {
                drawerAvatar.style.backgroundImage = `url('${photo || DEFAULT_AVATAR}')`;
            }
            if (drawerName) {
                drawerName.textContent = display;
            }
            if (drawerId) {
                drawerId.textContent = profile?.username || '';
            }
        } catch (err) {
            console.warn('获取用户信息失败（header）', err);
        }
    }

    function bindAvatarTrigger(root) {
        const trigger = root.querySelector('[data-role="avatar-trigger"]');
        if (!trigger) return;
        trigger.addEventListener('click', () => {
            toggleDrawer(true);
        });
    }

    let drawer;
    let drawerOverlay;
    let drawerClose;

    function ensureProfileDrawer() {
        if (drawer) return;
        const tpl = document.createElement('div');
        tpl.className = 'profile-drawer';
        tpl.setAttribute('data-profile-drawer', '');
        tpl.innerHTML = `
            <div class="profile-drawer__overlay" data-drawer-overlay></div>
            <aside class="profile-drawer__panel">
                <button class="profile-drawer__close" aria-label="关闭侧边栏">&times;</button>
                <div class="profile-drawer__hero">
                    <div class="profile-drawer__avatar" data-drawer-avatar></div>
                    <div class="profile-drawer__name" data-drawer-name>玩家</div>
                    <div class="profile-drawer__id" data-drawer-id></div>
                </div>
                <div class="profile-drawer__actions">
                    <button class="profile-drawer__action">隐私模式</button>
                    <button class="profile-drawer__action">消息中心</button>
                    <button class="profile-drawer__action">个人资料</button>
                    <button class="profile-drawer__action" data-role="logout-drawer">退出登录</button>
                </div>
            </aside>
        `;
        document.body.appendChild(tpl);
        drawer = tpl;
        drawerOverlay = tpl.querySelector('[data-drawer-overlay]');
        drawerClose = tpl.querySelector('.profile-drawer__close');
        const logoutBtn = tpl.querySelector('[data-role="logout-drawer"]');

        drawerOverlay?.addEventListener('click', () => toggleDrawer(false));
        drawerClose?.addEventListener('click', () => toggleDrawer(false));
        logoutBtn?.addEventListener('click', () => {
            toggleDrawer(false);
            if (confirm('确定退出登录吗？')) {
                if (typeof clearToken === 'function') {
                    clearToken();
                }
                const form = document.createElement('form');
                form.method = 'post';
                form.action = '/logout';
                document.body.appendChild(form);
                form.submit();
            }
        });
    }

    function toggleDrawer(show) {
        if (!drawer) return;
        drawer.classList.toggle('is-visible', show);
        document.body.classList.toggle('profile-drawer-open', show);
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

