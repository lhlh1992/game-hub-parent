/**
 * 全局聊天组件
 * 在所有页面右下角显示聊天窗口
 * 状态全局共享：关闭/打开状态在所有页面间同步
 */
(() => {
    const STORAGE_KEY = 'globalChatCollapsed';
    
    const TEMPLATE = `
        <div class="chat-widget" id="globalChatWidget">
            <div class="chat-header" id="globalChatHeader">
                <span class="chat-title">Chat</span>
                <button class="chat-toggle" id="globalChatToggle">−</button>
            </div>
            <div class="chat-body" id="globalChatBody">
                <div class="chat-messages" id="globalChatMessages">
                    <!-- 聊天消息将通过JavaScript动态添加 -->
                </div>
                <div class="chat-input-area">
                    <input type="text" class="chat-input" id="globalChatInput" placeholder="Type a message...">
                    <button class="chat-send" id="globalChatSend">Send</button>
                </div>
            </div>
        </div>
    `;

    // 全局状态管理
    const ChatState = {
        isCollapsed: false,
        
        load() {
            try {
                const saved = localStorage.getItem(STORAGE_KEY);
                this.isCollapsed = saved === 'true';
            } catch (e) {
                this.isCollapsed = false;
            }
        },
        
        save(collapsed) {
            try {
                this.isCollapsed = collapsed;
                localStorage.setItem(STORAGE_KEY, String(collapsed));
                // 触发自定义事件，通知其他页面更新状态
                window.dispatchEvent(new CustomEvent('chatStateChanged', { 
                    detail: { collapsed } 
                }));
            } catch (e) {
                // Failed to save chat state
            }
        },
        
        apply(chatWidget, chatToggle) {
            if (!chatWidget || !chatToggle) return;
            
            if (this.isCollapsed) {
                chatWidget.classList.add('collapsed');
                chatToggle.textContent = '+';
            } else {
                chatWidget.classList.remove('collapsed');
                chatToggle.textContent = '−';
            }
        }
    };

    window.addEventListener('DOMContentLoaded', () => {
        // 加载保存的状态
        ChatState.load();
        
        const containers = document.querySelectorAll('[data-component="global-chat"]');
        if (!containers.length) return;

        containers.forEach((container) => {
            container.innerHTML = TEMPLATE;
            initChatWidget(container);
        });
        
        // 监听其他页面的状态变化（通过storage事件）
        window.addEventListener('storage', (e) => {
            if (e.key === STORAGE_KEY) {
                ChatState.load();
                const chatWidget = document.querySelector('#globalChatWidget');
                const chatToggle = document.querySelector('#globalChatToggle');
                ChatState.apply(chatWidget, chatToggle);
            }
        });
        
        // 监听同页面的状态变化（通过自定义事件）
        window.addEventListener('chatStateChanged', (e) => {
            const chatWidget = document.querySelector('#globalChatWidget');
            const chatToggle = document.querySelector('#globalChatToggle');
            ChatState.apply(chatWidget, chatToggle);
        });
    });

    function initChatWidget(container) {
        const chatWidget = container.querySelector('#globalChatWidget');
        const chatToggle = container.querySelector('#globalChatToggle');
        const chatSend = container.querySelector('#globalChatSend');
        const chatInput = container.querySelector('#globalChatInput');
        const chatMessages = container.querySelector('#globalChatMessages');

        if (!chatWidget || !chatToggle || !chatSend || !chatInput || !chatMessages) return;

        // 应用保存的状态
        ChatState.apply(chatWidget, chatToggle);

        // 聊天窗口折叠/展开
        chatToggle.addEventListener('click', () => {
            const isCollapsed = chatWidget.classList.contains('collapsed');
            const newState = !isCollapsed;
            
            // 更新UI
            if (newState) {
                chatWidget.classList.add('collapsed');
                chatToggle.textContent = '+';
            } else {
                chatWidget.classList.remove('collapsed');
                chatToggle.textContent = '−';
            }
            
            // 保存状态到localStorage
            ChatState.save(newState);
        });

        // 聊天发送
        const sendMessage = () => {
            const text = chatInput.value.trim();
            if (!text) return;

            addChatMessage(text, 'self');
            chatInput.value = '';

            // TODO: 发送消息到服务器
            // sendChatMessageToServer(text);
        };

        chatSend.addEventListener('click', sendMessage);
        chatInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                sendMessage();
            }
        });

        // 暴露全局方法供其他脚本使用
        window.addChatMessage = (text, type = 'self', timestamp = null) => {
            addChatMessage(text, type, timestamp);
        };

        window.addSystemMessage = (text) => {
            addChatMessage(text, 'system');
        };
    }

    /**
     * 添加聊天消息
     */
    function addChatMessage(text, type = 'self', timestamp = null) {
        const chatMessages = document.querySelector('#globalChatMessages');
        if (!chatMessages) return;

        const messageDiv = document.createElement('div');
        messageDiv.className = `chat-message ${type}`;

        const bubble = document.createElement('div');
        bubble.className = 'chat-bubble';
        bubble.textContent = text;

        const time = document.createElement('div');
        time.className = 'chat-timestamp';
        time.textContent = timestamp || new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });

        messageDiv.appendChild(bubble);
        if (type !== 'system') {
            messageDiv.appendChild(time);
        }

        chatMessages.appendChild(messageDiv);
        chatMessages.scrollTop = chatMessages.scrollHeight;

        // 如果聊天窗口是折叠的，展开它并保存状态
        const chatWidget = document.querySelector('#globalChatWidget');
        if (chatWidget && chatWidget.classList.contains('collapsed')) {
            chatWidget.classList.remove('collapsed');
            const chatToggle = document.querySelector('#globalChatToggle');
            if (chatToggle) {
                chatToggle.textContent = '−';
            }
            ChatState.save(false);
        }
    }
})();

