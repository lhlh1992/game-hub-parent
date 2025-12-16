# Complete Implementation Guide for Single Device Login System

> Educational-level documentation: Complete implementation covering login, logout, JWT blacklist, WebSocket management, single device login (new login kicks out old login), Kafka notification for WebSocket disconnection, etc.

---

## 📑 Table of Contents

### Authentication System Architecture
- [Authentication System Architecture Design](#authentication-system-architecture-design)

### Chapter 1: Conceptual Foundation
- [I. Background and Problem Analysis](#i-background-and-problem-analysis)
- [II. Core Design Philosophy](#ii-core-design-philosophy)
- [III. Key Concept Definitions](#iii-key-concept-definitions)

### Chapter 2: Core Processes
- [IV. Complete Login Flow Implementation](#iv-complete-login-flow-implementation)
- [V. Complete JWT Validation Flow Implementation](#v-complete-jwt-validation-flow-implementation)
- [VI. Token Retrieval Interface Implementation](#vi-token-retrieval-interface-implementation)
- [VII. Complete Logout Flow Implementation](#vii-complete-logout-flow-implementation)
- [VII.5. Frontend Authentication Implementation](#vii5-frontend-authentication-implementation)

### Chapter 3: Extended Features
- [VIII. WebSocket Connection Management](#viii-websocket-connection-management)
- [IX. Kafka Event Notification Mechanism](#ix-kafka-event-notification-mechanism)

### Chapter 4: Core Components
- [X. SessionRegistry Core Implementation](#x-sessionregistry-core-implementation)
- [XI. JWT Blacklist Mechanism](#xi-jwt-blacklist-mechanism)

### Chapter 5: Summary and Deepening
- [XII. Complete Data Flow Diagram](#xii-complete-data-flow-diagram)
- [XIII. Key Design Decisions](#xiii-key-design-decisions)
- [XIV. Edge Case Handling](#xiv-edge-case-handling)

### Chapter 6: Practice and Operations
- [XV. Testing and Verification](#xv-testing-and-verification)
- [XVI. Common Issues and Solutions](#xvi-common-issues-and-solutions)
- [XVII. Performance Optimization Recommendations](#xvii-performance-optimization-recommendations)
- [XVIII. Monitoring and Operations](#xviii-monitoring-and-operations)

### Chapter 7: Appendix
- [Appendix](#appendix)

---

## Authentication System Architecture Design

> **Chapter Objective**: Understand the architecture design of the login authentication system from a top-level perspective, and master the interaction relationships and data flows between components.
> 
> **Note**: This chapter only describes the system architecture related to **login, authentication, authorization, and logout**, and does not involve business system architecture.

---

### Architecture Overview

This authentication system adopts a microservices architecture of **Keycloak + Gateway + Kafka + Redis** to implement single device login, session management, and WebSocket connection control.

**Architecture Characteristics**:
- **Microservices Architecture**: Gateway, application services, etc. are independent services with clear responsibilities
- **State Sharing**: Session state sharing through Redis, supporting multi-instance deployment
  - **Unified Redis Database**: All services (Gateway, game-service, system-service, etc.) use the same Redis database (default database 0) to ensure session data sharing
- **Event-Driven**: Asynchronous inter-service communication through Kafka, decoupling service dependencies
- **Horizontal Scaling**: Gateway and application services can be deployed in multiple instances, coordinated through shared storage

**Document Scope**:
- ✅ This document only describes functions related to **login, authentication, authorization, and logout**
- ✅ Does not involve business logic, role permission control, and other business functions
- ✅ Uses game-service as an application service example to demonstrate WebSocket connection management

#### Authentication System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Login Authentication System Architecture (Single Device Login Architecture)                                │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                          OAuth2/OIDC Role Definitions                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│  Resource Owner (资源所有者): User (User)                                      │
│  Client (客户端): Gateway (Confidential Client)                               │
│    - Gateway acts as OAuth2 Client, handling authorization code flow with Keycloak                    │
│    - Frontend applications obtain tokens through Gateway's /token interface         						│
│  Authorization Server (授权服务器): Keycloak                                  │
│  Resource Server (资源服务器): Gateway, Application Services (game-service, etc.)              │
└─────────────────────────────────────────────────────────────────────────────┘

        ┌──────────────┐
        │   User       │  Resource Owner
        │  (用户)       │
        └──────┬───────┘
               │
               │ HTTP/HTTPS
               │
        ┌──────▼──────────────────────────────────────────────────┐
        │  Frontend Application                                    │
        │  (前端应用 - React)                                        │
        │  • AuthContext: Manages global authentication state                            │
        │  • authService: Encapsulates authentication service (get token, verify, logout)      │
        │  • apiClient: HTTP requests automatically carry token                        │
        │  • gomokuSocket: WebSocket connection passes token                    │
        │  • ProtectedRoute: Route protection                               │
        │  • Obtain token through Gateway /token interface                     │
        │  • Trigger login through Gateway /oauth2/authorization/keycloak     │
        │  • Logout through Gateway /logout interface                          │
        │  • Access Gateway (carrying JWT Token)                          │
        │  • Token stored in localStorage                             │
        └──────┬──────────────────────────────────────────────────┘
               │
               │ OAuth2/OIDC Authorization Code Flow
               │
        ┌──────▼──────────────────────────────────────────────────┐
        │  Gateway                                                │
        │  (Confidential Client + Resource Server)                │
        │                                                         │
        │  • OAuth2 Login (handles Keycloak callback)                      │
        │  • /token (provides Token retrieval interface)                           │
        │  • /logout (handles logout flow)                                 │
        │  • JWT Validation (three-layer validation: blacklist/signature/session state)                  │
        │  • TokenRelay (transparently passes JWT to downstream services)                        │
        │  • SessionRegistry (session state management)                          │
        │  • Publishes Kafka events (SESSION_INVALIDATED)                  │
        └──────┬──────────────────────────────────────────────────┘
               │                          
               │ OAuth2/OIDC               
               │                          
        ┌──────▼──────────────┐           
        │  Keycloak           │           
        │  (Authorization     │           
        │   Server)           │           
        │                     │          
        │  • User login/registration      │           
        │  • Generate JWT Token    │          
        │    (contains sid/sub/jti)│           
        │  • User state management       │           
        │  • Webhook push events   │
        │    (REGISTER/UPDATE │			  
        │     _PROFILE/...)   │			  
        └─────────────────────┘           
                             │              
                             │         
                          ┌──▼────────────────────────────┐
                          │  system-service               │
                          │  (Resource Server)            │
                          │                               │
                          │  • Receives Webhook events            │
                          │    (/internal/keycloak/events)│
                          │  • User synchronization (REGISTER)          │
                          │  • Event processing                     │
                          └──────────────────────────────┘

        ┌──────────────────────────────────────────────────────────┐
        │  Kafka (Event Bus)                                          │
        │                                                           │
        │  Topic: session-invalidated                               │
        │  • SESSION_INVALIDATED events                               │
        │  • Source: Gateway (logout/single device login new login kicks out old login)                      │
        └──────┬───────────────────────────────────────────────────┘
               │
               │ Consume events
               │
        ┌──────▼──────────────────────────────────────────────────┐
        │  Application Services (Example: game-service)                            │
        │  (Resource Server)                                      │
        │                                                         │
        │  • JWT Validation (WebSocket/REST API)                         │
        │  • WebSocket Auth (validates JWT during handshake)                        │
        │  • WebSocket SSO (listens to Kafka events)                        │
        │  • Register/kick WebSocket connections                                │
        └──────┬──────────────────────────────────────────────────┘
               │
               │ Read/Write
               │
        ┌──────▼──────────────────────────────────────────────────┐
        │   Redis (Single Device Login State Storage)                                    │
        │                                                          │
        │  • session:login:loginSession:{loginSessionId} (LoginSessionInfo)     │
        │  • ws:session:{loginSessionId} (WebSocket mapping)          │
        │  • jwt:blacklist:{jti} (JWT blacklist)                       │
        └──────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                              Core Processes                                        								  │
├───────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  1. Login Flow:                                                                								  │
│     User → Frontend → Gateway (OAuth2 Client) → Keycloak → Gateway → Frontend → User   								│
│                                                                              								│
│  2. Logout Flow:                                                                								  │
│     User → Gateway → Redis (token blacklist) → KeycloakSsoLogoutService invalidates SSO session → Kafka message → Application Services (disconnect WebSocket)│
│                                                                              								 │
│  3. Single Device Login (New Login Kicks Out Old Login):                                                     									│
│     New Login → Gateway → Redis (mark old session KICKED) → KeycloakSsoLogoutService invalidates old SSO session → Kafka message → Application Services (disconnect WebSocket)│
│                                                                              								 │
│  4. Resource Access:                                                                								   │
│     User → Gateway (validates JWT) → TokenRelay → Application Services (validates JWT) → Response    									   │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

#### Architecture Component Descriptions

**1. User (用户 / Resource Owner)**

- **OAuth2 Role**: Resource Owner (资源所有者)
- **Responsibility**: Initiates login requests and uses system resources
- **Interaction Method**: Accesses Gateway through browser/frontend

**2. Frontend Application (前端应用)**

- **OAuth2 Role**: Not an OAuth2 Client, just a client application that obtains tokens through Gateway
- **Responsibility**: Obtains JWT Token through Gateway and accesses resources with Token
- **Key Functions**:
  - Obtains token through Gateway's `/token` interface (Gateway acts as OAuth2 Client to handle interactions with Keycloak)
  - Triggers login flow through Gateway's `/oauth2/authorization/keycloak` endpoint
  - Carries JWT Token in Authorization Header to access Gateway protected resources
  - Token stored in browser `localStorage`
- **Authentication Flow**:
  1. Frontend calls Gateway `/token` interface
  2. If not logged in, Gateway returns 401, frontend redirects to `/oauth2/authorization/keycloak`
  3. Gateway acts as OAuth2 Client to handle authorization code flow with Keycloak
  4. After successful login, frontend calls `/token` interface again to obtain token

**Note**:
- **Browser (浏览器)** is not an OAuth2 Client, it is just a User Agent (用户代理) used to run frontend application code
- **Frontend Application (前端应用)** is also not an OAuth2 Client, it just obtains tokens through Gateway
- **Gateway** is the OAuth2 Client (Confidential Client), responsible for handling OAuth2 flow with Keycloak

**3. Keycloak (认证服务器 / Authorization Server)**

- **OAuth2 Role**: Authorization Server (授权服务器)
- **Responsibility**: Provides OAuth2/OIDC standard authentication services
- **Functions**:
  - User login/registration
  - Generates JWT Token (contains `sid`, `sub`, `jti` and other claims)
  - Manages user session state
  - User state changes (disable, enable, password modification, etc.)
- **Output**:
  - JWT Token (contains `sub`, `sid`, `jti` and other claims)
  - **Webhook Events**: Pushed directly to `system-service` via HTTP POST (**not Kafka**)
- **Webhook Event Types**:
  - `REGISTER`: User registration
  - `LOGIN`: User login
  - `LOGOUT`: User logout
  - `UPDATE_PROFILE`: User profile update
  - `USER_DISABLED`: User disabled (administrative event)
  - `PASSWORD_CHANGED`: Password changed (administrative event)
- **Webhook Push Method**: Keycloak → HTTP POST → `system-service` `/internal/keycloak/events`

**4. Gateway (网关服务 / Confidential Client + Resource Server)**
- **OAuth2 Roles**:
  - **Confidential Client**: Performs OAuth2 authorization code flow with Keycloak (uses client_secret)
  - **Resource Server**: Validates JWT Token and protects resources
- **Client Type**: Confidential Client (requires client_secret, can securely store credentials)
- **Responsibility**: Unified entry point, handles authentication, authorization, and routing
- **Core Functions**:
  - **OAuth2 Login**: Handles Keycloak login callback, establishes Gateway Session
  - **/token**: Provides Token retrieval interface with automatic refresh support
  - **/logout**: Handles logout flow, writes JWT to blacklist, publishes Kafka events
  - **JWT Validation**: Three-layer validation (blacklist check, signature verification, session state check)
  - **TokenRelay**: Automatically transparently passes JWT Token to downstream application services
  - **SessionRegistry**: Manages all active sessions (stored in Redis)
  - **Publish Kafka Events**: Publishes `SESSION_INVALIDATED` events on logout and new login kicks out old login
  - **WebSocketTokenFilter**: Converts `access_token` (query/header) to `Authorization: Bearer` during WebSocket handshake, ensuring downstream services can reuse unified JWT validation chain
- **Key Design**:
  - Uses `sid` (loginSessionId) as session identifier
  - Maintains `SessionRegistry` to manage all active sessions
  - Implements "new login kicks out old login" logic

**5. system-service (系统服务 / Resource Server)**
- **OAuth2 Role**: Resource Server (资源服务器)
- **Responsibility**: Receives and processes Keycloak user events, synchronizes user state
- **Core Functions**:
  - **Receives Webhook Events**: Listens to `/internal/keycloak/events` endpoint (**HTTP POST, not Kafka**)
  - **User Synchronization**: Processes `REGISTER` events, synchronizes user information to local database
  - **Event Processing**: Processes `UPDATE_PROFILE`, `USER_DISABLED` and other events
- **Key Design**:
  - Uses Basic Auth to verify Webhook requests (shared secret)
  - After event processing, can trigger session invalidation (e.g., when user is disabled)
- **Note**: This document uses system-service as an example to demonstrate how to receive Keycloak Webhook events

**6. Kafka (Event Bus)**
- **Responsibility**: Asynchronous event notification mechanism for session state change notifications
- **Event Type**: `SESSION_INVALIDATED`
- **Trigger Scenarios**:
  - User actively logs out
  - New device login (new login kicks out old login)
  - Session expiration
  - User disabled (triggered by system-service, future extension)
  - Password changed (triggered by system-service, future extension)
- **Event Content**:
  ```json
  {
    "loginSessionId": "sid-xxx",
    "userId": "user-123",
    "reason": "LOGOUT|KICKED|EXPIRED|USER_DISABLED|PASSWORD_CHANGED",
    "timestamp": "2024-01-01T00:00:00Z"
  }
  ```
- **Event Sources**:
  - **Gateway Publishes**: Login, logout, new login kicks out old login
  - **system-service Publishes**: User disabled, password changed, etc. (optional, future extension)
- **Important Note**: Kafka event stream and Keycloak Webhook event stream are **completely independent** event streams

**7. Application Services (Example: game-service / Resource Server)**
- **OAuth2 Role**: Resource Server (资源服务器)
- **Responsibility**: Handles WebSocket connection management (as downstream service example for single device login system)
- **Core Functions**:
  - **JWT Validation**: Validates JWT Token relayed from Gateway (for extracting user information, ensuring local validation)
  - **WebSocket Auth**: Validates JWT Token during WebSocket handshake
  - **WebSocket SSO**: Listens to Kafka events, disconnects kicked WebSocket connections
  - **Register/Kick WS**: Manages mapping relationship between WebSocket connections and loginSessionId
- **Key Design**:
  - When WebSocket connection is established, binds `loginSessionId` to connection
  - When receiving `SESSION_INVALIDATED` event, finds and disconnects corresponding WebSocket connection
  - **JWT Validation**: Even though Gateway has validated, application services also validate JWT again (for extracting user information, ensuring local validation)
- **Note**: This document uses game-service as an example, but it can be any application service that needs WebSocket connection management

**8. Redis (Single Device Login State Storage)**
- **Responsibility**: Stores session state and WebSocket connection mappings
- **Data Structures**:
  - **`session:login:loginSession:{loginSessionId}`**: Stores `LoginSessionInfo` (session state, user information, etc.)
  - **`ws:session:{loginSessionId}`**: Stores WebSocket connection identifiers (for cross-service lookup)
  - **`jwt:blacklist:{jti}`**: Stores revoked JWT Tokens (blacklist)
- **TTL Strategy**:
  - Session information: Consistent with JWT refresh token validity period
  - WebSocket mapping: Automatically cleaned when connection is disconnected
  - JWT blacklist: Consistent with JWT Token validity period

---

### Data Flow Descriptions

#### 1. Login Flow (Frontend-Backend Interaction)

```
Frontend Application (React)
    │
    │ 1. User accesses protected resource (e.g., /lobby)
    │ 2. AuthProvider initializes, calls ensureAuthenticated(true)
    │ 3. Attempts to get token from Gateway (GET /token, with cookie)
    │ 4. If not logged in (returns 401), redirects to /oauth2/authorization/keycloak
    ▼
Gateway API
    │
    │ 5. Spring Security OAuth2 Client Filter intercepts
    │ 6. Builds authorization URL, redirects to Keycloak
    ▼
Keycloak
    │
    │ 7. User enters username and password on Keycloak login page
    │ 8. Keycloak validates user credentials
    │ 9. Keycloak generates authorization code, redirects back to Gateway
    │ 10. Callback URL: /login/oauth2/code/keycloak?code=xxx&state=xxx
    ▼
Gateway API
    │
    │ 11. Gateway exchanges authorization code for access_token
    │ 12. LoginSessionKickHandler handles login success
    │     ├─ Parses JWT, extracts sid (loginSessionId), jti (sessionId), userId
    │     ├─ Stores to Redis: session:login:loginSession:{sid}
    │     ├─ Registers to SessionRegistry (registerLoginSessionEnforceSingle)
    │     ├─ Checks if there are old sessions, if yes, kicks them out (marks as KICKED, adds to blacklist, publishes Kafka event)
    │     └─ Stores loginSessionId to HTTP Session
    │ 13. Gateway redirects back to frontend (original address or homepage)
    ▼
Frontend Application
    │
    │ 14. Frontend calls ensureAuthenticated() again
    │ 15. Frontend calls GET /token (with cookie: JSESSIONID)
    │ 16. Gateway gets token from Session, returns to frontend
    │ 17. Frontend saves token to localStorage (key name: access_token)
    │ 18. Frontend sets authentication state (isAuthenticated = true)
    │ 19. Frontend loads user information (getUserInfo())
    │ 20. Frontend renders application
    ▼
User Browser (obtains Session Cookie + token in localStorage)
```

#### 2. Token Retrieval Flow (Frontend-Backend Interaction)

```
Frontend Application
    │
    │ 1. Frontend needs token (HTTP request or WebSocket connection)
    │ 2. Calls ensureAuthenticated()
    │ 3. Prioritizes getting latest token from Gateway
    │    GET /token (carries cookie: JSESSIONID, credentials: 'include')
    ▼
Gateway API
    │
    │ 4. TokenController.getToken()
    │    ├─ Checks Authentication (returns 401 if not authenticated)
    │    ├─ Gets OAuth2AuthorizedClient from Session
    │    ├─ Verifies loginSessionId matches Session
    │    ├─ Checks session state in SessionRegistry (if state is KICKED, returns 401)
    │    ├─ Verifies token's jti matches session's sessionId
    │    └─ If state is ACTIVE, returns access_token (if token expired, automatically refreshes)
    ▼
Frontend Application
    │
    │ 5. If Gateway returns 401, indicates not logged in, triggers login flow
    │ 6. If Gateway returns 200, obtains token
    │ 7. Frontend verifies token validity (GET /game-service/me, Authorization: Bearer {token})
    │ 8. If token is valid, saves to localStorage (key name: access_token)
    │ 9. Returns token for subsequent use
    ▼
User Browser (JWT Token saved in localStorage)
```

#### 3. Logout Flow (Frontend-Backend Interaction)

```
Frontend Application
    │
    │ 1. User clicks logout button
    │ 2. Calls logoutFromGateway()
    │ 3. POST /logout (carries cookie: JSESSIONID, credentials: 'include')
    ▼
Gateway API
    │
    │ 4. Spring Security Logout Filter intercepts
    │ 5. jwtBlacklistLogoutHandler executes logout processing
    │    ├─ Gets OAuth2AuthorizedClient (from Session)
    │    ├─ Extracts current loginSessionId (sid)
    │    ├─ Writes JWT to blacklist (JwtBlacklistService)
    │    ├─ Publishes Kafka event: SESSION_INVALIDATED (contains loginSessionId)
    │    ├─ Cleans SessionRegistry (removeAllSessions(userId))
    │    └─ Removes OAuth2AuthorizedClient
    │ 6. OidcClientInitiatedServerLogoutSuccessHandler
    │    └─ Calls KeycloakSsoLogoutService (Keycloak Admin API) to invalidate corresponding SSO session
    │ 7. Gateway redirects to frontend homepage (http://localhost:5173/)
    ▼
Kafka
    │
    │ Event: SESSION_INVALIDATED (contains loginSessionId)
    ▼
game-service
    │
    │ 8. SessionInvalidatedListener listens to Kafka event
    │ 9. Queries WebSocket sessions based on loginSessionId
    │ 10. Disconnects corresponding WebSocket connection
    ▼
Frontend Application
    │
    │ 11. Frontend clears local token (localStorage.removeItem('access_token'))
    │ 12. Frontend sets authentication state (isAuthenticated = false)
    │ 13. Frontend renders homepage (not logged in state)
    ▼
User Browser (WebSocket connection disconnected, token cleared)
```

#### 4. New Login Kicks Out Old Login Flow (Frontend-Backend Interaction)

```
Device 1 (Frontend): Logged in, WebSocket connection active
    │
    │ 1. Device 1 has established WebSocket connection
    │ 2. Device 1 has saved token to localStorage
    │
Device 2 (Frontend): User logs in
    │
    │ 3. Device 2 redirects to /oauth2/authorization/keycloak
    │ 4. Device 2 successfully logs in on Keycloak
    ▼
Gateway API
    │
    │ 5. LoginSessionKickHandler handles login success
    │    ├─ Detects Device 1's old session (queries all ACTIVE sessions by userId)
    │    ├─ Marks Device 1 session as KICKED in Redis / SessionRegistry
    │    ├─ Deletes old session record (unregisterLoginSession)
    │    ├─ Writes old JWT to blacklist (JwtBlacklistService)
    │    ├─ Calls KeycloakSsoLogoutService to immediately invalidate Device 1 session on Keycloak side
    │    ├─ Publishes Kafka event: SESSION_KICKED (loginSessionId = Device 1's sid)
    │    └─ Registers Device 2's new session (registerLoginSessionEnforceSingle)
    ▼
Kafka
    │
    │ Event: SESSION_KICKED (contains loginSessionId = Device 1's sid)
    ▼
game-service
    │
    │ 6. SessionInvalidatedListener listens to Kafka event
    │ 7. Queries WebSocket sessions based on loginSessionId (SessionRegistry)
    │ 8. Disconnects Device 1's WebSocket connection (WebSocketDisconnectHelper)
    │ 9. Sends KICKED message to Device 1 (/user/queue/gomoku.kicked)
    ▼
Device 1 (Frontend)
    │
    │ 10. WebSocket connection disconnected (onDisconnect callback)
    │ 11. Frontend detects connection disconnection
    │ 12. Frontend displays authentication expired popup
    │     ├─ Prompt: "Your login has expired or been kicked offline by another device, please log in again to continue."
    │     └─ Provides "Log In Again" button
    │ 13. User clicks "Log In Again"
    │ 14. Frontend redirects to /oauth2/authorization/keycloak
    │ 15. Re-login flow
    ▼
Device 2 (Frontend)
    │
    │ 16. Gateway redirects back to Device 2
    │ 17. Device 2 obtains token, saves to localStorage
    │ 18. Device 2 establishes WebSocket connection
    │ 19. Device 2 uses normally
```
game-service
    │
    │ 1. Listens to Kafka events
    │ 2. Disconnects Device 1's WebSocket connection
    ▼
Device 1: WebSocket disconnected, subsequent API requests return 401
```

---

### Architecture Design Principles

1. **Decoupling Design**
   - Decouples login session ID (`sid`) from access token (`jti`)
   - Separates session state management from Token management
   - Decouples Gateway from downstream application services through Kafka events

2. **Unified Entry Point**
   - All authentication requests are handled uniformly through Gateway
   - Gateway is responsible for JWT parsing and session state validation (three-layer validation)
   - Gateway transparently passes JWT to downstream services through TokenRelay
   - Downstream application services also validate JWT (for extracting user information, ensuring local validation)

3. **Event-Driven**
   - **Keycloak Webhook Events**: Keycloak pushes user events (registration, disable, etc.) to system-service
   - **Kafka Session Events**: Gateway publishes session invalidation events, downstream application services listen and disconnect WebSocket connections
   - Avoids polling, improves performance and real-time capability

4. **Centralized State Management**
   - Session state is uniformly stored in Redis (shared storage)
   - SessionRegistry maintains in-memory index (fast query)
   - **Supports Horizontal Scaling**: Multiple Gateway instances share SessionRegistry state through Redis, enabling distributed deployment

---

### Chapter Summary

This chapter presents the architecture design of the **login authentication system** from a top-level perspective, including:

- ✅ **8 Core Components**: User (用户), Browser/Frontend (浏览器/前端), Keycloak (认证服务器), Gateway (网关), system-service (系统服务), Kafka (事件总线), Application Services (示例: game-service), Redis (状态存储)
- ✅ **4 Core Flows**: Login, Token Retrieval, Logout, New Login Kicks Out Old Login
- ✅ **4 Design Principles**: Decoupling, Unified Entry Point, Event-Driven, Centralized State Management

**Key Points**:
- Gateway is the core control point of the authentication system, responsible for session management and state validation
- Kafka is used for asynchronous event notifications, achieving service decoupling
- Redis serves as shared storage, achieving session state sharing (supporting multi-instance deployment)
- `sid` (loginSessionId) is the core identifier of the authentication system

**Architecture Scope**:
- ✅ This architecture diagram only shows components and interactions of the **login authentication system**
- ✅ Does not include business system architecture (such as game logic, order system, etc.)
- ✅ system-service only shows user event reception and synchronization functions (does not involve complete user management business)
- ✅ Application services (game-service) are only used as examples to demonstrate how to integrate the authentication system

**Event-Driven Mechanism**:

This system uses **two completely independent event streams** to handle different event types:

1. **Keycloak Webhook Event Stream** (User State Events)
   - **Flow**: Keycloak → **HTTP POST** → `system-service` `/internal/keycloak/events`
   - **Transport Method**: **HTTP POST** (not Kafka)
   - **Event Types**: `REGISTER` (user registration), `UPDATE_PROFILE` (profile update), `USER_DISABLED` (user disabled), `PASSWORD_CHANGED` (password changed), etc.
   - **Purpose**: Synchronize user state to local database, handle user-related events
   - **Characteristics**: Keycloak actively pushes, system-service passively receives

2. **Kafka Session Event Stream** (Session Invalidation Events)
   - **Flow**: Gateway → **Kafka** → Application Services (e.g., game-service)
   - **Transport Method**: **Kafka** (message queue)
   - **Event Type**: `SESSION_INVALIDATED` (session invalidation)
   - **Trigger Scenarios**: User logout, new login kicks out old login, session expiration, etc.
   - **Purpose**: Notify all application services to disconnect corresponding WebSocket connections
   - **Characteristics**: Gateway publishes, application services subscribe and consume

**Important Distinctions**:
- **Webhook Event Stream**: Keycloak directly pushes to system-service via HTTP POST for user state synchronization
- **Kafka Event Stream**: Gateway publishes to Kafka, application services subscribe and consume for session invalidation notifications
- **Completely Independent**: Webhook events do not enter Kafka, Kafka events do not come from Keycloak Webhook

**Future Extensions**: After system-service receives user disabled/password changed events, it can publish Kafka events to notify all services to invalidate all sessions for that user

**About "Distributed"**:
- The architecture diagram shows single-instance deployment (for ease of understanding)
- Actual production environment supports **horizontal scaling**:
  - Gateway can be deployed in multiple instances (sharing SessionRegistry state through Redis)
  - Application services can be deployed in multiple instances (consuming events through Kafka, sharing WebSocket mappings through Redis)
  - All instances coordinate state and communicate events through Redis and Kafka



---

## I. Background and Problem Analysis

> **Chapter Objective**: Understand functional requirements and technical challenges, clarify why this solution is needed, and why `sid` is chosen as `loginSessionId`.

---

### 1.1 Functional Requirements

#### 1.1.1 Single Device Login Requirement

**Application Scenario**:
- Users use the system on multiple devices (PC, mobile, tablet)
- System requirement: **The same user can only be logged in on one device at a time**
- When a new device logs in, the old device should automatically log out

**Expected Effect**:
```
User A logs in on Device 1 → Normal usage
User A logs in on Device 2 → Device 2 becomes the only valid login
All operations on Device 1 → Immediately invalid, prompt "Account logged in on another device"
```

#### 1.1.2 New Login Kicks Out Old Login Requirement

**Application Scenario**:
- User forgets to log out on old device
- User logs in on new device
- **New login should automatically kick out old login**, no manual operation required

**Expected Effect**:
```
Old Device: User is using the system
New Device: User logs in
Result: All requests on old device are rejected, prompt "Account logged in on another device"
```

#### 1.1.3 WebSocket Disconnection on Logout Requirement

**Application Scenario**:
- Users communicate in real-time through WebSocket connections
- When user logs out, all WebSocket connections should be immediately disconnected
- When user logs in on another device, old device's WebSocket connections should also be disconnected

**Expected Effect**:
```
User logs out → All WebSocket connections immediately disconnected
User logs in on another device → Old device's WebSocket connections immediately disconnected
```

---

### 1.2 Technical Challenges

#### 1.2.1 JWT Token Refresh Causes jti Change

**Problem Description**:
- JWT token has validity period (e.g., 15 minutes)
- After token expires, system automatically refreshes using `refresh_token`
- **New token's `jti` (JWT ID) may change after refresh**

**Problem Example**:
```
Initial Login:
  Token 1: jti = "jti-001", loginSessionId = "sid-001"

After Token Refresh:
  Token 2: jti = "jti-002", loginSessionId = "sid-001" ← jti changed, but loginSessionId unchanged
```

**Impact**:
- ❌ If using `jti` as session identifier, cannot associate with same session after refresh
- ❌ Cannot determine if new token and old token belong to same login session
- ❌ Cannot implement single device login (new login kicks out old login)

#### 1.2.2 OAuth2AuthorizedClientService Overwrites by userId

**Problem Description**:
- Spring Security's `OAuth2AuthorizedClientService` uses `userId` as key to store authorized clients
- All authorized clients for the same user share the same key
- **New login overwrites old login's authorized client**

**Problem Example**:
```
Device 1 Login:
  OAuth2AuthorizedClientService[userId="user-123"] = Client1

Device 2 Login:
  OAuth2AuthorizedClientService[userId="user-123"] = Client2 ← Overwrote Client1
```

**Impact**:
- ❌ When Device 1 calls `/token` interface, may get Device 2's token
- ❌ Cannot distinguish different login sessions for same user
- ❌ Cannot implement single device login (new login kicks out old login)

#### 1.2.3 HTTP Session Cannot Be Cleared by userId

**Problem Description**:
- Spring Security's HTTP Session uses Session ID (`JSESSIONID` in Cookie) as key
- **Cannot directly find or clear other devices' Sessions by `userId`**
- Each device's Session is independent

**Problem Example**:
```
Device 1: Session ID = "session-001", userId = "user-123"
Device 2: Session ID = "session-002", userId = "user-123"

After Device 2 logs in, cannot clear Device 1's Session
```

**Impact**:
- ❌ Device 1's `Authentication` is still valid
- ❌ Device 1 can continue using cached token to call API
- ❌ Cannot implement single device login (new login kicks out old login)

#### 1.2.4 WebSocket Long Connection Management

**Problem Description**:
- WebSocket is a long connection, connection does not automatically disconnect after establishment
- When user logs out or logs in on new device, need to actively disconnect old device's WebSocket connection
- **Need a mechanism to notify all services to disconnect specific user's WebSocket connections**

**Problem Example**:
```
Device 1: WebSocket connection active
Device 2: User logs in
Result: Need to disconnect Device 1's WebSocket connection
```

**Impact**:
- ❌ If cannot precisely disconnect, old device's WebSocket connection may remain
- ❌ User may receive messages they shouldn't receive
- ❌ Resource waste (maintaining invalid connections)

---

### 1.3 Why loginSessionId (sid) Is Needed

#### 1.3.1 Problem with jti

**Problem**: `jti` may change when token is refreshed

**Example**:
```
Initial Login:
  Token: jti = "jti-001"

After Refresh:
  Token: jti = "jti-002" ← Changed!
```

**Impact**:
- ❌ Cannot use `jti` as stable session identifier
- ❌ Cannot associate with same login session after refresh
- ❌ Cannot implement single device login

#### 1.3.2 Problem with session_state

**Problem**: `session_state` may be unstable or not always available

**Example**:
```
Some Keycloak versions:
  - session_state may not be in JWT claim
  - session_state may change when token is refreshed
  - session_state format may be inconsistent
```

**Impact**:
- ❌ Relying on `session_state` may be unstable
- ❌ Different Keycloak versions may behave differently
- ❌ Requires additional configuration (Mapper)

#### 1.3.3 Advantages of sid

**Advantage**: Keycloak native session ID, stable and unchanging

**Characteristics**:
- ✅ **Stability**: Stable and unchanging throughout entire login lifecycle
- ✅ **Native Support**: Provided natively by Keycloak, no additional configuration needed
- ✅ **Standard Specification**: Complies with OIDC specification (Session ID)
- ✅ **Unchanged on Token Refresh**: `sid` remains unchanged when token is refreshed

**Example**:
```
Initial Login:
  Token: sid = "sid-001", jti = "jti-001"

After Refresh:
  Token: sid = "sid-001", jti = "jti-002" ← sid unchanged, jti changed
```

**Conclusion**:
- ✅ Using `sid` as `loginSessionId` is the best choice
- ✅ Can stably identify entire login session
- ✅ Can solve all technical challenges

---

### 1.4 Problem Summary

**Core Problems**:
1. ❌ Using `jti` as session identifier → Changes when token is refreshed
2. ❌ `OAuth2AuthorizedClientService` overwrites by `userId` → Cannot distinguish different login sessions
3. ❌ HTTP Session cannot be cleared by `userId` → Old device still valid
4. ❌ WebSocket long connection management → Needs precise disconnection mechanism

**Solutions**:
1. ✅ Use `sid` as `loginSessionId` → Stable and unchanging
2. ✅ Introduce `SessionRegistry` → Centralized session state management
3. ✅ Three-layer validation mechanism → Signature + Blacklist + State
4. ✅ Kafka event notification → Precise WebSocket disconnection

---

### 1.5 Chapter Summary

**Functional Requirements**:
- Single device login
- New login kicks out old login
- WebSocket disconnection on logout

**Technical Challenges**:
- JWT token refresh causes `jti` change
- `OAuth2AuthorizedClientService` overwrites by `userId`
- HTTP Session cannot be cleared by `userId`
- WebSocket long connection management

**Solutions**:
- Use `sid` as `loginSessionId` (stable and unchanging)
- Introduce `SessionRegistry` (centralized management)
- Three-layer validation mechanism (Signature + Blacklist + State)
- Kafka event notification (precise disconnection)

---

## II. Core Design Philosophy

> **Chapter Objective**: Understand core design philosophy and master how to solve the technical challenges proposed in Chapter I through design.

---

### 2.1 Decoupling Login Session ID from Access Token

#### 2.1.1 Why Decoupling Is Needed

**Problem**: If using token's `jti` as session identifier, `jti` may change when token is refreshed, making it impossible to associate with the same session.

**Solution**: Decouple login session ID (`loginSessionId`) from access token (token).

**Design Principle**:
- ✅ Token can be refreshed multiple times and changed
- ✅ Login session ID must remain stable throughout the entire login lifecycle

#### 2.1.2 loginSessionId (sid) vs sessionId (jti)

**Comparison**:

| Feature | loginSessionId (sid) | sessionId (jti) |
|------|----------------------|------------------|
| **Scope** | Entire login session | Single token |
| **Stability** | ✅ Stable (unchanged during login lifecycle) | ⚠️ Unstable (may change on token refresh) |
| **Source** | Keycloak's `sid` claim | JWT's `jti` claim |
| **Usage** | Session management, single device login | Token identification, backward compatibility |
| **Recommended Use** | ✅ Yes (core identifier) | ⚠️ Backward compatibility only |

**Relationship Diagram**:
```
LoginSession (loginSessionId: "sid-001") ← Stable and unchanging
  ├── Token 1 (jti: "jti-001") ← May change
  ├── Token 2 (jti: "jti-002") ← Changed after refresh
  └── Token 3 (jti: "jti-003") ← Changed after another refresh
```

#### 2.1.3 How to Decouple

**Implementation Method**:

1. **Extract loginSessionId**: Extract from JWT's `sid` claim
2. **Store loginSessionId**: Store `loginSessionId` in `LoginSessionInfo`
3. **Index Design**: Build indexes by both `loginSessionId` and `sessionId` (jti)

**Code Example**:
```java
// Extract loginSessionId (sid) from JWT
String loginSessionId = extractLoginSessionId(jwt); // Extract from sid claim

// Build LoginSessionInfo
LoginSessionInfo sessionInfo = LoginSessionInfo.builder()
    .sessionId(jwt.getId())              // jti, backward compatibility
    .loginSessionId(loginSessionId)      // sid, core identifier
    .userId(jwt.getSubject())
    .token(tokenValue)
    .status(SessionStatus.ACTIVE)
    .build();
```

**Storage Structure**:
```
Redis Key 1: session:login:token:{sessionId} → LoginSessionInfo
Redis Key 2: session:login:loginSession:{loginSessionId} → LoginSessionInfo
```

**Advantages**:
- ✅ Can query by `loginSessionId` (stable)
- ✅ Can query by `sessionId` (jti) (backward compatibility)
- ✅ `loginSessionId` remains unchanged when token is refreshed

---

### 2.2 Using "User + Login Session" as Control Unit

#### 2.2.1 From Token Dimension to loginSession Dimension

**Old Solution (Token Dimension)**:
- Uses single token as control unit
- Problem: Cannot associate with same session after token refresh
- Problem: Cannot implement single device login

**New Solution (loginSession Dimension)**:
- Uses `loginSessionId` as control unit
- Advantage: `loginSessionId` remains unchanged when token is refreshed
- Advantage: Can stably manage entire login session

**Comparison Diagram**:
```
Old Solution (Token Dimension):
  Token 1 (jti: "jti-001") → Session 1
  Token 2 (jti: "jti-002") → Session 2 ← Cannot associate with Session 1

New Solution (loginSession Dimension):
  LoginSession (loginSessionId: "sid-001")
    ├── Token 1 (jti: "jti-001")
    └── Token 2 (jti: "jti-002") ← Belongs to same loginSession
```

#### 2.2.2 Session State Management (ACTIVE/KICKED/EXPIRED)

**Design Principle**:
- For the same user, only one login session is allowed to be in `ACTIVE` state
- When new session is established, must explicitly mark old session as `KICKED`
- First mark as `KICKED`, then delete session record in `blacklistKickedSessions()`

**State Transition**:
```
[New Login] → ACTIVE
    ↓
[User logs in on another device] → KICKED (old session)
    ↓
[User actively logs out] → EXPIRED
    ↓
[Session expired] → EXPIRED
```

**Implementation Logic**:
```java
// When new login, mark old session as KICKED
List<LoginSessionInfo> activeSessions = getActiveLoginSessions(userId);
for (LoginSessionInfo oldSession : activeSessions) {
    // Skip sessions with same loginSessionId (token refresh)
    if (newSession.getLoginSessionId().equals(oldSession.getLoginSessionId())) {
        continue;
    }
    // Mark as KICKED
    updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
}
// New session state is ACTIVE
newSession.setStatus(SessionStatus.ACTIVE);
```

**Advantages**:
- ✅ Can precisely control which session is valid
- ✅ First mark KICKED, then delete session record (retain audit information in logs)
- ✅ Can track session history

---

### 2.3 Three-Layer Validation Mechanism

#### 2.3.1 Design Principle

**All services uniformly use "three-layer validation"** (in execution order):
1. **Blacklist Validation**: Immediately invalidate revoked tokens (executed first, quick rejection)
2. **Token Signature Validation**: Prevent forgery (delegated to Nimbus JWT decoder)
3. **Session State Validation**: Determine if loginSession is kicked (executed last, queries Redis)

#### 2.3.2 Layer 1: Token Signature Validation (Spring Security)

**Purpose**: Verify if token is issued by legitimate authorization server (Keycloak).

**Implementation**:
- Automatically completed by Spring Security
- Uses Nimbus JWT library to verify signature
- Verifies token's signature, expiration time, issuer, etc.

**Code Location**:
```java
// JwtDecoderConfig.java
ReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder
    .withIssuerLocation(issuerUri)
    .build();
```

**Validation Content**:
- ✅ Whether signature is valid
- ✅ Whether token is expired
- ✅ Whether issuer is correct
- ✅ Whether audience is correct

#### 2.3.3 Layer 2: Blacklist Validation (Immediate Invalidation)

**Purpose**: Immediately invalidate revoked tokens (e.g., logout, kicked offline).

**Implementation**:
- Uses Redis to store blacklist
- Key format: `jwt:blacklist:{token}`
- TTL automatically expires (consistent with token validity period)

**Code Location**:
```java
// JwtDecoderConfig.java
return jwtBlacklistService.isBlacklisted(token)
    .flatMap(blacklisted -> {
        if (Boolean.TRUE.equals(blacklisted)) {
            return Mono.error(new JwtException("Token has been revoked"));
        }
        // Continue subsequent validation
    });
```

**Usage Scenarios**:
- User actively logs out
- User logs in on another device (old token added to blacklist)
- Administrator forces offline

**Advantages**:
- ✅ Immediate invalidation, no need to wait for token expiration
- ✅ Supports TTL automatic cleanup
- ✅ High performance (Redis query)

#### 2.3.4 Layer 3: Session State Validation (Core)

**Purpose**: Determine if loginSession is kicked offline (core mechanism).

**Implementation**:
1. Extract `loginSessionId` (sid) from JWT
2. Query `SessionRegistry` to get session information
3. Check if session state is `ACTIVE`

**Code Location**:
```java
// JwtDecoderConfig.java
private Mono<Void> checkSessionStatus(Jwt jwt, String token) {
    // 1. Extract loginSessionId
    String loginSessionId = extractLoginSessionId(jwt);
    
    // 2. Query SessionRegistry
    LoginSessionInfo sessionInfo = sessionRegistry
        .getLoginSessionByLoginSessionId(loginSessionId);
    
    // 3. Check state
    if (sessionInfo == null || sessionInfo.getStatus() != SessionStatus.ACTIVE) {
        return Mono.error(new JwtException("Session is not active"));
    }
    
    return Mono.empty();
}
```

**Validation Logic**:
```
1. Extract loginSessionId (sid)
   ↓
2. Query SessionRegistry
   ↓
3. Check state
   - Not exists → Reject (backward compatibility: may skip)
   - ACTIVE → Pass
   - KICKED → Reject
   - EXPIRED → Reject
```

**Advantages**:
- ✅ Can precisely control which session is valid
- ✅ Can implement single device login (new login kicks out old login)
- ✅ Can retain audit records

#### 2.3.5 Three-Layer Validation Flow Diagram

```
Client Request
  ↓
[Layer 1] Token Signature Validation
  ├── Invalid signature → 401 Unauthorized
  ├── Token expired → 401 Unauthorized
  └── Pass → Continue
  ↓
[Layer 2] Blacklist Validation
  ├── In blacklist → 401 Unauthorized
  └── Pass → Continue
  ↓
[Layer 3] Session State Validation
  ├── State not ACTIVE → 401 Unauthorized
  └── Pass → Allow access
```

**Key Points**:
- ✅ All three layers are indispensable
- ✅ Order is important (signature first, then blacklist, finally state)
- ✅ Failure at any layer will reject access

---

### 2.4 Design Philosophy Summary

**Core Design Principles**:

1. **Decouple Login Session ID from Access Token**
   - Token can change, `loginSessionId` must be stable
   - Use `sid` as `loginSessionId`

2. **Use "User + Login Session" as Control Unit**
   - From token dimension to loginSession dimension
   - Session state management (ACTIVE/KICKED/EXPIRED)

3. **Three-Layer Validation Mechanism**
   - Token signature validation (prevent forgery)
   - Blacklist validation (immediate invalidation)
   - Session state validation (core mechanism)

**Design Advantages**:

- ✅ **Stability**: `loginSessionId` is stable and unchanging, unaffected by token refresh
- ✅ **Precision**: Can precisely control which session is valid
- ✅ **Security**: Three-layer validation ensures security
- ✅ **Traceability**: Mark KICKED before deletion, retain audit information in logs

**Technical Challenges Solved**:

1. ✅ JWT token refresh causes `jti` change → Use `sid` as `loginSessionId`
2. ✅ `OAuth2AuthorizedClientService` overwrites by `userId` → Introduce `SessionRegistry` to manage session state
3. ✅ HTTP Session cannot be cleared by `userId` → Reject access through session state validation
4. ✅ WebSocket long connection management → Precise disconnection through Kafka event notification

---

### 2.5 Chapter Summary

**Core Design Philosophy**:
1. **Decoupling**: Decouple login session ID from access token
2. **Control Unit**: Use "User + Login Session" as control unit
3. **Three-Layer Validation**: Signature + Blacklist + State

**Design Advantages**:
- Stability, precision, security, traceability

**Problems Solved**:
- All technical challenges proposed in Chapter I



---

## III. Key Concept Definitions

> **Chapter Objective**: Establish a unified conceptual system and understand all core concepts and their relationships in the system. This is the foundation for understanding all subsequent chapters.

---

### 3.1 User

#### 3.1.1 Definition

**User (用户)** is a real user entity in the system and the subject of authentication and authorization.

#### 3.1.2 User Identifier: userId

- **Source**: From Keycloak JWT's `sub` (subject) claim
- **Format**: Usually a UUID string, e.g., `"123e4567-e89b-12d3-a456-426614174000"`
- **Uniqueness**: Uniquely identifies a user in the entire system
- **Stability**: User ID remains unchanged during user lifecycle

**Code Example**:
```java
// Extract userId from JWT
Jwt jwt = ...; // Get from Spring Security
String userId = jwt.getSubject(); // Get sub claim
```

#### 3.1.3 Relationship Between User and Login Session

**Important Relationship**:
- **One-to-Many Relationship**: One user can have multiple login sessions (different devices, different browsers)
- **Session Policy**: Only one login session is allowed to be in `ACTIVE` state at the same time
- **Session Management**: Can query all login sessions for a user through `userId`

**Relationship Diagram**:
```
User (userId: "user-123")
  ├── LoginSession 1 (loginSessionId: "sid-001", status: KICKED)
  ├── LoginSession 2 (loginSessionId: "sid-002", status: ACTIVE) ← Currently valid
  └── LoginSession 3 (loginSessionId: "sid-003", status: EXPIRED)
```

---

### 3.2 Login Session

#### 3.2.1 Definition

**Login Session (登录会话)** is the complete lifecycle of a user's login, starting from successful login and ending at logout or expiration.

#### 3.2.2 Core Identifiers

##### 3.2.2.1 loginSessionId (Login Session ID)

**Definition**: A stable and unchanging identifier throughout the entire login lifecycle.

**Source**:
- **Recommended**: Keycloak's `sid` (Session ID) claim
- **Alternative**: Keycloak's `session_state` claim (backward compatibility)

**Characteristics**:
- ✅ **Stability**: Stable and unchanging during one login
- ✅ **Uniqueness**: Each login generates a new `loginSessionId`
- ✅ **Persistence**: `loginSessionId` remains unchanged when token is refreshed
- ✅ **Traceability**: Can track all operations of the entire login session

**Code Example**:
```java
// Extract loginSessionId from JWT (prefer sid)
Object sidObj = jwt.getClaim("sid");
String loginSessionId = null;
if (sidObj != null) {
    String sid = sidObj.toString();
    if (sid != null && !sid.isBlank()) {
        loginSessionId = sid; // Use sid
    }
}
// If no sid, try using session_state (backward compatibility)
if (loginSessionId == null) {
    Object sessionStateObj = jwt.getClaim("session_state");
    if (sessionStateObj != null) {
        loginSessionId = sessionStateObj.toString();
    }
}
```

##### 3.2.2.2 sessionId (Session ID)

**Definition**: JWT token's `jti` (JWT ID) claim, used to identify a single token.

**Characteristics**:
- ⚠️ **Instability**: `jti` may change when token is refreshed
- ⚠️ **Token Level**: Each token has its own `jti`
- ✅ **Backward Compatibility**: Used for backward compatibility with old systems

**Code Example**:
```java
// Extract sessionId (jti) from JWT
String sessionId = jwt.getId(); // Get jti claim
```

**Comparison Table**:

| Feature | loginSessionId (sid) | sessionId (jti) |
|------|----------------------|------------------|
| Stability | ✅ Stable (unchanged during login lifecycle) | ⚠️ Unstable (may change on token refresh) |
| Scope | Entire login session | Single token |
| Usage | Single device login, session management | Token identification, backward compatibility |
| Recommended Use | ✅ Yes | ⚠️ Backward compatibility only |

#### 3.2.3 Session State: SessionStatus

**Definition**: Used to identify the current state of a login session.

**State Enumeration**:

```java
public enum SessionStatus {
    /** Currently valid. Session is active, user can use normally. */
    ACTIVE,
    
    /** Kicked offline by subsequent login. After user logs in on another device/browser, this session is marked as KICKED. */
    KICKED,
    
    /** Normal timeout or logout. Session has expired or been actively logged out by user. */
    EXPIRED
}
```

**State Transition Diagram**:
```
[New Login] → ACTIVE
    ↓
[User logs in on another device] → KICKED
    ↓
[User actively logs out] → EXPIRED
    ↓
[Session expired] → EXPIRED
```

**State Descriptions**:

1. **ACTIVE (Active)**
   - Session is currently valid, user can use normally
   - All requests using this session should be allowed
   - This is the only state that allows resource access

2. **KICKED (Kicked Offline)**
   - After user logs in on another device/browser, this session is marked as KICKED
   - All requests using this session should be rejected
   - Return 401 or business prompt "Account logged in on another device"

3. **EXPIRED (Expired)**
   - Session has expired or been actively logged out by user
   - All requests using this session should be rejected
   - Return 401 or business prompt "Session expired"

#### 3.2.4 LoginSessionInfo Data Structure

**Complete Data Structure**:

```java
@Data
@Builder
public class LoginSessionInfo {
    /** Session unique identifier (JWT's jti, backward compatibility) */
    private String sessionId;
    
    /** Login session ID (Keycloak's sid, core identifier) */
    private String loginSessionId;
    
    /** Session state (ACTIVE/KICKED/EXPIRED) */
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;
    
    /** Original Token value */
    private String token;
    
    /** Associated user ID */
    private String userId;
    
    /** Issued time (millisecond timestamp) */
    private Long issuedAt;
    
    /** Expiration time (millisecond timestamp) */
    private Long expiresAt;
    
    /** Additional information (IP, User-Agent, device name, etc.) */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();
}
```

**Data Example**:
```json
{
  "sessionId": "jti-abc123",
  "loginSessionId": "sid-xyz789",
  "status": "ACTIVE",
  "token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "user-123",
  "issuedAt": 1704067200000,
  "expiresAt": 1704070800000,
  "attributes": {
    "ip": "192.168.1.100",
    "userAgent": "Mozilla/5.0..."
  }
}
```

---

### 3.3 Access Token

#### 3.3.1 Definition

**Access Token (访问令牌)** is a credential used in OAuth2/OIDC protocol to access protected resources.

#### 3.3.2 Token Types

##### 3.3.2.1 access_token (Access Token)

**Definition**: Token used to access protected resources.

**Characteristics**:
- **Format**: JWT (JSON Web Token)
- **Validity Period**: Usually short (e.g., 5 minutes, 15 minutes)
- **Usage**: Carried in HTTP request's `Authorization` header
- **Contains**: User ID (sub), session ID (sid), etc.
- **Note**: JWT may contain claims like roles, but this document does not involve role permission control, only focuses on authentication and session management

**Usage Example**:
```http
GET /api/users/me HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

##### 3.3.2.2 refresh_token (Refresh Token)

**Definition**: Token used to refresh `access_token`.

**Characteristics**:
- **Format**: Usually an opaque string (not JWT)
- **Validity Period**: Usually long (e.g., 30 days, 90 days)
- **Usage**: Used to obtain new `access_token` when `access_token` expires
- **Security**: Needs to be properly kept, high risk of leakage

**Refresh Flow**:
```
1. access_token expires
2. Use refresh_token to call refresh interface
3. Get new access_token and refresh_token
4. New token's loginSessionId remains unchanged (sid unchanged)
```

#### 3.3.3 Relationship Between Token and LoginSession

**Important Relationship**:
- **Many-to-One Relationship**: Multiple tokens can belong to the same `loginSessionId`
- **Token Refresh**: New token after refresh still belongs to the same `loginSessionId`
- **New Login**: New login generates new `loginSessionId`, not shared with old login

**Relationship Diagram**:
```
LoginSession (loginSessionId: "sid-001")
  ├── Token 1 (jti: "jti-001", expired)
  ├── Token 2 (jti: "jti-002", currently valid) ← Generated after Token 1 refresh
  └── Token 3 (jti: "jti-003", future refresh) ← Generated after Token 2 refresh

New Login:
LoginSession (loginSessionId: "sid-002") ← New loginSessionId
  └── Token 4 (jti: "jti-004", currently valid)
```

**Key Points**:
- ✅ `loginSessionId` remains unchanged when token is refreshed
- ⚠️ `jti` may change when token is refreshed
- ✅ New login generates new `loginSessionId`

---

### 3.4 WebSocket Session

#### 3.4.1 Definition

**WebSocket Session (WebSocket 会话)** is a long connection session between client and server for real-time bidirectional communication.

#### 3.4.2 WebSocket sessionId

**Definition**: Unique identifier for WebSocket connection.

**Source**:
- Spring WebSocket: STOMP session ID
- SockJS: Connection ID
- Native WebSocket: Connection ID

**Characteristics**:
- **Uniqueness**: Each WebSocket connection has a unique `sessionId`
- **Lifecycle**: Created when connection is established, destroyed when connection is disconnected
- **Scope**: Single WebSocket connection

#### 3.4.3 Association Between WebSocket Session and LoginSession

**Association Method**: Associated through `loginSessionId`.

**Relationship Diagram**:
```
LoginSession (loginSessionId: "sid-001", userId: "user-123")
  ├── WebSocket Session 1 (sessionId: "ws-001", service: "game-service")
  └── WebSocket Session 2 (sessionId: "ws-002", service: "chat-service")

After New Login:
LoginSession (loginSessionId: "sid-002", userId: "user-123")
  └── WebSocket Session 3 (sessionId: "ws-003", service: "game-service")

Old session's WebSocket connections will be disconnected (queried based on loginSessionId)
```

**WebSocketSessionInfo Data Structure**:

```java
@Data
@Builder
public class WebSocketSessionInfo {
    /** WebSocket session ID */
    private String sessionId;
    
    /** Associated login session ID (loginSessionId) */
    private String loginSessionId;
    
    /** Associated user ID */
    private String userId;
    
    /** Service name that generated this connection */
    private String service;
    
    /** Connection establishment time */
    private Long connectedAt;
    
    /** Additional information */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();
}
```

**Association Purpose**:
- ✅ **Single Device Login**: When new login, can query and disconnect old connection's WebSocket based on `loginSessionId`
- ✅ **Precise Disconnection**: When logout, can precisely disconnect all WebSocket connections for that `loginSessionId`
- ✅ **Session Management**: Can query all WebSocket connections for a `loginSessionId`

---

### 3.5 Concept Relationship Summary

**Complete Relationship Diagram**:
```
User (userId: "user-123")
  │
  ├── LoginSession 1 (loginSessionId: "sid-001", status: KICKED)
  │   ├── Token 1 (jti: "jti-001", expired)
  │   ├── Token 2 (jti: "jti-002", expired)
  │   └── WebSocket Session 1 (sessionId: "ws-001", disconnected)
  │
  └── LoginSession 2 (loginSessionId: "sid-002", status: ACTIVE) ← Currently valid
      ├── Token 3 (jti: "jti-003", currently valid)
      ├── WebSocket Session 2 (sessionId: "ws-002", active)
      └── WebSocket Session 3 (sessionId: "ws-003", active)
```

**Key Relationship Summary**:

1. **User → LoginSession**: One-to-many, one user can have multiple login sessions
2. **LoginSession → Token**: One-to-many, one login session can have multiple tokens (token refresh)
3. **LoginSession → WebSocket Session**: One-to-many, one login session can have multiple WebSocket connections
4. **loginSessionId**: Is the core identifier that associates all resources

---

### 3.6 Terminology Reference Table

| Term | English | Description | Field Name in Code |
|------|------|------|---------------|
| 用户ID | userId | Keycloak JWT's sub | `userId` |
| 登录会话ID | loginSessionId | Keycloak's sid | `loginSessionId` |
| 会话ID | sessionId | JWT's jti | `sessionId` |
| 访问令牌 | access_token | OAuth2 access token | `token` |
| 刷新令牌 | refresh_token | OAuth2 refresh token | `refreshToken` |
| WebSocket会话ID | WebSocket sessionId | WebSocket connection ID | `sessionId` (WebSocketSessionInfo) |
| 会话状态 | SessionStatus | ACTIVE/KICKED/EXPIRED | `status` |

---

### 3.7 Chapter Summary

**Core Concepts**:
1. **User (用户)**: Real user in the system, identified by `userId`
2. **LoginSession (登录会话)**: Complete lifecycle of a user's login, identified by `loginSessionId`
3. **Token (令牌)**: Credential for accessing resources, multiple tokens can belong to the same `loginSessionId`
4. **WebSocket Session (WebSocket 会话)**: Long connection session, associated with login session through `loginSessionId`

**Key Points**:
- ✅ `loginSessionId` (sid) is the core identifier, stable and unchanging
- ✅ `sessionId` (jti) is used for backward compatibility, but unstable
- ✅ Session state (ACTIVE/KICKED/EXPIRED) is used to control access authorization (whether access is allowed)
- ✅ All resources (Token, WebSocket) are associated through `loginSessionId`



---

## IV. Complete Login Flow Implementation

> **Chapter Objective**: Understand the complete login flow and master each step and code implementation details from user login to session registration and single device login processing.

---

### 4.1 Login Flow Overview

#### 4.1.1 OAuth2 Login Flow (Frontend-Backend Interaction)

**Complete Flow**:
1. **Frontend Triggers Login**: User accesses page requiring authentication, or frontend calls `initAndLogin()`
   - Frontend redirects to `/oauth2/authorization/keycloak`
2. **Gateway Processing**: Spring Security OAuth2 Client Filter intercepts request
   - Builds authorization URL, redirects to Keycloak
3. **Keycloak Login**: User enters username and password on Keycloak login page
   - Keycloak validates user credentials
4. **Keycloak Callback**: Keycloak generates authorization code, redirects back to Gateway
   - Callback URL: `/login/oauth2/code/keycloak?code=xxx&state=xxx`
5. **Gateway Exchanges Token**: Gateway uses authorization code to exchange for access_token
   - Calls Keycloak Token endpoint
   - Spring Security creates Authentication
6. **Gateway Handles Login Success**: Executes custom login success handler
   - Single device login processing (kick old connections)
   - Stores loginSessionId to HTTP Session
7. **Gateway Redirects**: Redirects back to frontend (original address or homepage)
8. **Frontend Gets Token**: Frontend calls `GET /token` (with cookie)
   - Gateway gets token from Session, returns to frontend
   - Frontend saves token to localStorage
9. **Frontend Loads User Information**: Frontend calls `getUserInfo()`
   - Calls `/system-service/api/users/me` or `/game-service/me`

#### 4.1.2 Post-Login Processing

**Custom Processing Logic** (`LoginSessionKickHandler`):
1. Extract JWT information (userId, sessionId, loginSessionId)
2. Build `LoginSessionInfo`
3. Call `SessionRegistry.registerLoginSessionEnforceSingle()` (single device login core)
4. Delete kicked old sessions from SessionRegistry and add tokens to blacklist
5. Publish SESSION_KICKED event (notify services to disconnect WebSocket)
6. Store `loginSessionId` to HTTP Session

---

### 4.2 Code Call Chain (Login)

#### 4.2.1 Complete Call Chain

```
User accesses /oauth2/authorization/keycloak
  ↓
Spring Security OAuth2 Client Filter
  ↓
Build authorization URL, redirect to Keycloak
  ↓
User enters username and password on Keycloak login page
  ↓
Keycloak validates user credentials
  ↓
Keycloak generates authorization code, redirects back to Gateway
  ↓
Callback URL: /login/oauth2/code/keycloak?code=xxx&state=xxx
  ↓
Spring Security OAuth2 Client Filter handles callback
  ↓
Exchange authorization code for access_token (call Keycloak Token endpoint)
  ↓
Keycloak returns access_token and refresh_token
  ↓
Spring Security saves OAuth2AuthorizedClient to Session
  ↓
Spring Security creates Authentication object
  ↓
Call LoginSessionKickHandler.onAuthenticationSuccess()
  ↓
  ├─ 1. Get OAuth2AuthorizedClient
  │     authorizedClientManager.authorize()
  │
  ├─ 2. Extract JWT information
  │     extractJwtInfo(tokenValue)
  │     ├─ userId (sub)
  │     ├─ sessionId (jti)
  │     ├─ loginSessionId (sid)
  │     ├─ expiresAt
  │     └─ issuedAt
  │
  ├─ 3. Build LoginSessionInfo
  │     LoginSessionInfo.builder()
  │     ├─ sessionId
  │     ├─ loginSessionId
  │     ├─ userId
  │     ├─ token
  │     ├─ status = ACTIVE
  │     └─ attributes (IP, User-Agent)
  │
  ├─ 4. Call registerLoginSessionEnforceSingle()
  │     SessionRegistry.registerLoginSessionEnforceSingle()
  │     ├─ Query all ACTIVE sessions for this user
  │     ├─ Mark old sessions as KICKED
  │     └─ Return list of kicked old sessions
  │
  ├─ 5. Delete old sessions and add tokens to blacklist
  │     blacklistKickedSessions()
  │     ├─ Delete old sessions from SessionRegistry
  │     └─ Add old tokens to blacklist
  │     └─ JwtBlacklistService.addToBlacklist()
  │
  ├─ 6. Publish SESSION_KICKED event
  │     publishKickedEvent()
  │     └─ SessionEventPublisher.publishSessionInvalidated()
  │
  └─ 7. Store loginSessionId to HTTP Session
        storeLoginSessionIdInSession()
        └─ WebSession.getAttributes().put("LOGIN_SESSION_ID", loginSessionId)
  ↓
Call default success handler (redirect to homepage)
  ↓
Login complete
```

#### 4.2.2 Key Node Descriptions

**Node 1: OAuth2 Authorization Code Flow**
- Automatically handled by Spring Security
- No manual implementation needed

**Node 2: Login Success Handler**
- Custom `LoginSessionKickHandler`
- Implements `ServerAuthenticationSuccessHandler` interface

**Node 3: Single Device Login Core**
- `registerLoginSessionEnforceSingle()` method
- Marks old sessions as KICKED

**Node 4: Blacklist Processing**
- Immediately invalidates old tokens
- Prevents old devices from continuing to use

**Node 5: Event Notification**
- Kafka event notifies services
- Disconnects old devices' WebSocket connections

---

### 4.3 Key Code Analysis

#### 4.3.1 LoginSessionKickHandler Class Structure

**File Location**: `apps/gateway/src/main/java/com/gamehub/gateway/handler/LoginSessionKickHandler.java`

**Class Definition**:
```java
@Slf4j
@Component
public class LoginSessionKickHandler implements ServerAuthenticationSuccessHandler {
    private static final String REGISTRATION_ID = "keycloak";
    private static final String SESSION_LOGIN_SESSION_ID_KEY = "LOGIN_SESSION_ID";
    
    private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;
    private final SessionRegistry sessionRegistry;
    private final JwtBlacklistService blacklistService;
    private final ServerAuthenticationSuccessHandler defaultSuccessHandler;
    private final SessionEventPublisher sessionEventPublisher;
}
```

**Key Dependencies**:
- `ReactiveOAuth2AuthorizedClientManager`: Gets OAuth2 authorized client
- `SessionRegistry`: Session registry, manages login sessions
- `JwtBlacklistService`: JWT blacklist service
- `SessionEventPublisher`: Session event publisher (Kafka)

#### 4.3.2 onAuthenticationSuccess Method

**Method Signature**:
```java
@Override
public Mono<Void> onAuthenticationSuccess(WebFilterExchange exchange, Authentication authentication)
```

**Complete Implementation Flow**:

**Step 1: Get OAuth2AuthorizedClient**

```java
OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
        .withClientRegistrationId(REGISTRATION_ID)
        .principal(authentication)
        .build();

return authorizedClientManager.authorize(authorizeRequest)
        .flatMap(authorizedClient -> {
            // authorizedClient contains access_token and refresh_token
        });
```

**Note**:
- `authorizedClientManager.authorize()` gets saved `OAuth2AuthorizedClient` from Session
- If token has expired, automatically refreshes using `refresh_token`

**Step 2: Extract JWT Information**
```java
OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
String tokenValue = accessToken.getTokenValue();

return extractJwtInfo(tokenValue)
        .flatMap(jwtInfo -> {
            String userId = (String) jwtInfo.get("userId");
            String sessionId = (String) jwtInfo.get("sessionId"); // jti
            String loginSessionId = (String) jwtInfo.get("loginSessionId"); // sid
            Instant expiresAt = (Instant) jwtInfo.get("expiresAt");
            Instant issuedAt = (Instant) jwtInfo.get("issuedAt");
        });
```

**Note**:
- Need to manually parse JWT (Spring Security hasn't parsed it yet)
- Extract key information: userId, sessionId, loginSessionId

**Step 3: Build LoginSessionInfo**
```java
LoginSessionInfo newSession = LoginSessionInfo.builder()
        .sessionId(sessionId)                    // jti, backward compatibility
        .loginSessionId(loginSessionId)          // sid, core identifier
        .userId(userId)                          // sub
        .token(tokenValue)                       // access_token
        .status(SessionStatus.ACTIVE)            // New session status is ACTIVE
        .issuedAt(issuedAt != null ? issuedAt.toEpochMilli() : Instant.now().toEpochMilli())
        .expiresAt(expiresAt != null ? expiresAt.toEpochMilli() : null)
        .attributes(buildAttributes(exchange.getExchange())) // IP, User-Agent
        .build();
```

**Note**:
- New session status automatically set to `ACTIVE`
- Contains attributes like IP, User-Agent (for auditing)

**Step 4: Calculate TTL and Call Single Device Login Registration**

```java
// Calculate TTL (seconds)
// Login session (sid) lifecycle should be consistent with refresh_token
// - Created on login, deleted on logout
// - If refresh_token expires, user cannot refresh token, login session should also expire
// - Use refresh_token expiration time as TTL, not access_token expiration time
long ttlSeconds = 0; // Default uses default TTL (12 hours)
if (refreshToken != null && refreshToken.getExpiresAt() != null) {
    Instant refreshExpiresAt = refreshToken.getExpiresAt();
    ttlSeconds = Duration.between(Instant.now(), refreshExpiresAt).getSeconds();
    ttlSeconds = Math.max(ttlSeconds, 0);
    log.debug("Using refresh_token expiration time to calculate TTL: refreshExpiresAt={}, ttlSeconds={}", 
            refreshExpiresAt, ttlSeconds);
} else {
    log.debug("refresh_token does not exist or has no expiration time, using default TTL (12 hours)");
}

// Call registerLoginSessionEnforceSingle (will mark old sessions as KICKED)
List<LoginSessionInfo> kickedSessions = sessionRegistry.registerLoginSessionEnforceSingle(newSession, ttlSeconds);

log.info("【Single Device Login】New login session registered: userId={}, sessionId={}, loginSessionId={}, kicked old sessions count={}", 
        userId, sessionId, loginSessionId, kickedSessions.size());
```

**Note**:
- `registerLoginSessionEnforceSingle()` will:
  1. Query all ACTIVE sessions for this user
  2. Mark old sessions as KICKED (skip sessions with same loginSessionId)
  3. Register new session (status is ACTIVE)
  4. Return list of kicked old sessions
- **Note**: After marking as KICKED, old session records will be deleted from SessionRegistry in `blacklistKickedSessions()` method

**Steps 5-7: Subsequent Processing (Parallel Execution)**
```java
return storeLoginSessionIdInSession(exchange.getExchange(), loginSessionId)
        .then(blacklistKickedSessions(kickedSessions))
        .then(publishKickedEvent(userId, loginSessionId, kickedSessions))
        .then(defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication));
```

**Note**:
- Uses `then()` chained calls, sequential execution
- Finally calls default success handler (redirects to homepage)

#### 4.3.3 extractJwtInfo Method

**Method Signature**:

```java
private Mono<Map<String, Object>> extractJwtInfo(String tokenValue)
```

**Implementation Logic**:
```java
return Mono.fromCallable(() -> {
    Map<String, Object> result = new HashMap<>();
    
    // Manually parse JWT (Base64 decode payload)
    String[] parts = tokenValue.split("\\.");
    if (parts.length < 2) {
        throw new IllegalArgumentException("Invalid JWT format");
    }
    
    // Decode payload (Base64URL)
    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
    com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(payload);
    
    // Extract required information
    result.put("userId", json.getString("sub"));
    result.put("sessionId", json.getString("jti"));
    result.put("loginSessionId", extractSid(json)); // Extract from sid claim
    
    // Extract time information
    Long exp = json.getLong("exp");
    Long iat = json.getLong("iat");
    result.put("expiresAt", exp != null ? Instant.ofEpochSecond(exp) : null);
    result.put("issuedAt", iat != null ? Instant.ofEpochSecond(iat) : null);
    
    return result;
});
```

**Key Points**:
- ⚠️ **Does Not Verify Signature**: Only extracts claims, signature verification is handled by Spring Security
- ✅ **Base64URL Decode**: JWT uses Base64URL encoding
- ✅ **Extract Key Information**: userId, sessionId, loginSessionId, time information

#### 4.3.4 extractSid Method

**Method Signature**:
```java
private String extractSid(com.alibaba.fastjson2.JSONObject json)
```

**Implementation Logic**:
```java
// Prefer using sid
String sid = json.getString("sid");
if (sid != null && !sid.isBlank()) {
    return sid;
}

// If no sid, try using session_state (backward compatibility)
String sessionState = json.getString("session_state");
if (sessionState != null && !sessionState.isBlank()) {
    return sessionState;
}

return null;
```

**Key Points**:
- ✅ **Prefer Using sid**: Keycloak native session ID
- ✅ **Backward Compatibility**: If no sid, use session_state
- ✅ **Return null**: If neither exists, return null (backward compatibility)

#### 4.3.5 blacklistKickedSessions Method

**Method Signature**:
```java
private Mono<Void> blacklistKickedSessions(List<LoginSessionInfo> kickedSessions)
```

**Implementation Logic**:
```java
if (kickedSessions.isEmpty()) {
    return Mono.empty();
}

return Mono.fromRunnable(() -> {
    for (LoginSessionInfo kickedSession : kickedSessions) {
        // 1. Delete old session from SessionRegistry
        try {
            sessionRegistry.unregisterLoginSession(kickedSession.getSessionId());
            log.debug("Deleted old session from SessionRegistry: sessionId={}, loginSessionId={}", 
                    kickedSession.getSessionId(), kickedSession.getLoginSessionId());
        } catch (Exception e) {
            log.warn("Failed to delete old session from SessionRegistry: sessionId={}", kickedSession.getSessionId(), e);
        }
        
        // 2. Add old token to blacklist
        if (kickedSession.getToken() != null && !kickedSession.getToken().isBlank()) {
            // Calculate remaining TTL
            long ttlSeconds = 0;
            if (kickedSession.getExpiresAt() != null && kickedSession.getExpiresAt() > 0) {
                ttlSeconds = (kickedSession.getExpiresAt() - Instant.now().toEpochMilli()) / 1000;
                ttlSeconds = Math.max(ttlSeconds, 0);
            }
            
            // Add to blacklist
            blacklistService.addToBlacklist(kickedSession.getToken(), ttlSeconds)
                    .subscribe(
                            null,
                            error -> log.warn("Failed to add old session token to blacklist: sessionId={}", 
                                    kickedSession.getSessionId(), error)
                    );
            
            log.debug("Added old session token to blacklist: sessionId={}, loginSessionId={}", 
                    kickedSession.getSessionId(), kickedSession.getLoginSessionId());
        }
    }
});
```

**Key Points**:
- ✅ **Delete Old Sessions**: Calls `unregisterLoginSession()` to delete old session records from SessionRegistry
- ✅ **Calculate TTL**: Uses token remaining validity period as blacklist TTL
- ✅ **Asynchronous Processing**: Uses `subscribe()` for asynchronous execution, doesn't block main flow
- ✅ **Error Handling**: Logs warning, doesn't throw exception

#### 4.3.6 publishKickedEvent Method

**Method Signature**:
```java
private Mono<Void> publishKickedEvent(String userId, String loginSessionId, List<LoginSessionInfo> kickedSessions)
```

**Implementation Logic**:
```java
if (sessionEventPublisher == null || kickedSessions.isEmpty()) {
    return Mono.empty();
}

return Mono.fromRunnable(() -> {
    try {
        // Publish event: notify services to disconnect WebSocket connections of kicked sessions
        for (LoginSessionInfo kickedSession : kickedSessions) {
            String kickedLoginSessionId = kickedSession.getLoginSessionId();
            if (kickedLoginSessionId != null && !kickedLoginSessionId.isBlank()) {
                SessionInvalidatedEvent event = SessionInvalidatedEvent.of(
                        userId,
                        kickedLoginSessionId,
                        SessionInvalidatedEvent.EventType.FORCE_LOGOUT,
                        "单设备登录：被新登录踢下线"
                );
                sessionEventPublisher.publishSessionInvalidated(event);
            }
        }
    } catch (Exception e) {
        log.error("Failed to publish SESSION_KICKED event", e);
        // Don't throw exception, avoid affecting login flow
    }
});
```

**Key Points**:
- ✅ **Publish Kicked Session Events**: Each kicked session publishes an event
- ✅ **Contains loginSessionId**: Event contains kicked session's `loginSessionId`
- ✅ **Synchronously Logout Keycloak Session**: Calls `keycloakSsoLogoutService.logout()` to logout Keycloak-side session
- ✅ **Error Handling**: Logs error, doesn't throw exception

**Complete Implementation** (includes Keycloak session logout):
```java
for (LoginSessionInfo kickedSession : kickedSessions) {
    String kickedLoginSessionId = kickedSession.getLoginSessionId();
    if (kickedLoginSessionId == null || kickedLoginSessionId.isBlank()) {
        continue;
    }
    try {
        // 1. Publish Kafka event
        if (sessionEventPublisher != null) {
            SessionInvalidatedEvent event = SessionInvalidatedEvent.of(
                    userId,
                    kickedLoginSessionId,
                    SessionInvalidatedEvent.EventType.FORCE_LOGOUT,
                    "单设备登录：被新登录踢下线"
            );
            sessionEventPublisher.publishSessionInvalidated(event);
        }
    } catch (Exception e) {
        log.error("Failed to publish SESSION_KICKED event", e);
    } finally {
        // 2. Synchronously logout Keycloak SSO session
        try {
            keycloakSsoLogoutService.logout(userId, kickedLoginSessionId);
        } catch (Exception ex) {
            log.error("Failed to logout Keycloak session", ex);
        }
    }
}
```

#### 4.3.7 storeLoginSessionIdInSession Method

**Method Signature**:
```java
private Mono<Void> storeLoginSessionIdInSession(ServerWebExchange exchange, String loginSessionId)
```

**Implementation Logic**:
```java
if (loginSessionId == null || loginSessionId.isBlank()) {
    return Mono.empty();
}

return exchange.getSession()
        .doOnNext((WebSession session) -> {
            session.getAttributes().put(SESSION_LOGIN_SESSION_ID_KEY, loginSessionId);
            log.debug("Stored loginSessionId to HTTP Session: loginSessionId={}, sessionId={}", 
                    loginSessionId, session.getId());
        })
        .then();
```

**Key Points**:
- ✅ **Store to HTTP Session**: Used for subsequent verification in `TokenController`
- ✅ **Prevent Token Overwrite**: Verify returned token belongs to current Session

#### 4.3.8 handleAuthenticationFailure: 401/403 Response Strategy

`handleAuthenticationFailure()` is Gateway's unified entry point for 401/403, latest logic includes:

- **Clean Session + Write Blacklist**: Consistent with active logout, invalidate current `WebSession`, remove `OAuth2AuthorizedClient` and add token to blacklist;
- **Return by Request Type**:
  - If it's a normal HTML request, return 303, `Location` points to `/oauth2/authorization/keycloak`, also write `X-Auth-Redirect-To` in response header;
  - If it's an AJAX/Fetch request, return 401 JSON (`{\"code\":\"AUTH_EXPIRED\",...}`), also include `X-Auth-Redirect-To`.
- **Frontend Cooperation**: `handleAuthExpiredResponse()`/`showAuthModal()` in `auth.js` will pop up "Login Status Expired" modal based on response header, guide user to click "Log In Again" (internally clears local token and redirects to Keycloak).

> ✅ This way, whether refreshing page or sending AJAX request in button, only one unified popup appears, no longer relies on browser `alert`.

#### 4.3.9 KeycloakSsoLogoutService: Background Synchronous Logout

Just cleaning Gateway local state is not enough. The kicked browser may still retain Keycloak's SSO session, causing "silent login". To solve this problem:

- Gateway adds `KeycloakAdminProperties` / `KeycloakAdminConfig`, creates a Keycloak Admin Client based on `keycloak.admin.*` configuration (default uses `admin-cli` + administrator account).
- New `KeycloakSsoLogoutService.logout(userId, loginSessionId)` will prioritize calling Keycloak Admin API's `deleteSession(loginSessionId, false)` to precisely logout corresponding online session, falls back to `users().get(userId).logout()` (clears all sessions for that user) when session ID is missing.
- Trigger Locations:
  1. `SecurityConfig.publishSessionInvalidatedEvent()`: User actively logs out;
  2. `SecurityConfig.publishSessionInvalidatedEvent(Jwt, ...)`: 401/403, token invalidation scenarios;
  3. `LoginSessionKickHandler.publishKickedEvent()`: Single device login "new login kicks out old login".

> ✅ This way, kicked or expired browsers accessing `/oauth2/authorization/keycloak` again will definitely see Keycloak login page, cannot silently refresh anymore.

---

### 4.4 Data Flow

#### 4.4.1 Redis Storage Structure

**Login Session Storage**:

```
Key 1: session:login:token:{sessionId}
Value: LoginSessionInfo JSON
TTL: token remaining validity period

Key 2: session:login:loginSession:{loginSessionId}
Value: LoginSessionInfo JSON
TTL: token remaining validity period

Key 3: session:login:user:{userId}
Type: Set
Members: [sessionId1, sessionId2, ...]
TTL: Consistent with Key 1
```

**Example**:
```
session:login:token:jti-abc123 → {
  "sessionId": "jti-abc123",
  "loginSessionId": "sid-xyz789",
  "userId": "user-123",
  "status": "ACTIVE",
  ...
}

session:login:loginSession:sid-xyz789 → {
  "sessionId": "jti-abc123",
  "loginSessionId": "sid-xyz789",
  "userId": "user-123",
  "status": "ACTIVE",
  ...
}

session:login:user:user-123 → Set["jti-abc123"]
```

#### 4.4.2 SessionRegistry Data Structure

**In-Memory Data Structure**:
- `LoginSessionInfo` objects
- Stored in Redis, queried by key

**Query Methods**:
1. Query by `sessionId` (jti): `session:login:token:{sessionId}`
2. Query by `loginSessionId` (sid): `session:login:loginSession:{loginSessionId}`
3. Query by `userId`: `session:login:user:{userId}` → Get all sessionIds → Query one by one

#### 4.4.3 HTTP Session Storage

**Storage Content**:
```
WebSession.getAttributes()
  └── "LOGIN_SESSION_ID" → "sid-xyz789"
```

**Purpose**:
- Verify in `TokenController.getToken()`
- Ensure returned token belongs to current Session

---

### 4.5 Single Device Login Core Logic

#### 4.5.1 registerLoginSessionEnforceSingle Method

**Method Location**: `libs/session-common/src/main/java/com/gamehub/session/SessionRegistry.java`

**Implementation Logic**:
```java
public List<LoginSessionInfo> registerLoginSessionEnforceSingle(LoginSessionInfo sessionInfo, long ttlSeconds) {
    // 1) Get all ACTIVE sessions for this user
    List<LoginSessionInfo> activeSessions = getActiveLoginSessions(sessionInfo.getUserId());
    
    // 2) Mark old sessions as KICKED (will be deleted in blacklistKickedSessions() later)
    List<LoginSessionInfo> kicked = new ArrayList<>();
    for (LoginSessionInfo oldSession : activeSessions) {
        // Skip self (if new session's loginSessionId equals old session's, it's token refresh for same login session)
        if (sessionInfo.getLoginSessionId() != null 
                && sessionInfo.getLoginSessionId().equals(oldSession.getLoginSessionId())) {
            continue; // Skip, don't kick
        }
        
        // Update status to KICKED
        updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
        oldSession.setStatus(SessionStatus.KICKED);
        kicked.add(oldSession);
    }
    
    // 3) Ensure new session status is ACTIVE
    sessionInfo.setStatus(SessionStatus.ACTIVE);
    
    // 4) Register current session
    registerLoginSession(sessionInfo, ttlSeconds);
    
    return kicked;
}
```

**Key Logic**:
1. ✅ **Query ACTIVE Sessions**: Only query ACTIVE sessions for this `userId`
2. ✅ **Skip Same loginSessionId**: Token refresh scenario, don't kick self
3. ✅ **Mark as KICKED**: Old sessions marked as KICKED (will be deleted in `blacklistKickedSessions()` later)
4. ✅ **Register New Session**: New session status is ACTIVE

#### 4.5.2 Single Device Login Flow Diagram

```
User A logs in on Device 1
  ↓
registerLoginSessionEnforceSingle()
  ├─ Query ACTIVE sessions for userId="user-A" → []
  ├─ No old sessions, don't kick
  └─ Register new session (status=ACTIVE)
  ↓
Device 1 login successful

User A logs in on Device 2
  ↓
registerLoginSessionEnforceSingle()
  ├─ Query ACTIVE sessions for userId="user-A" → [Device 1's session]
  ├─ Device 1's session loginSessionId != Device 2's loginSessionId
  ├─ Mark Device 1's session as KICKED
  ├─ Delete Device 1's session and add token to blacklist
  ├─ Publish SESSION_KICKED event (Device 1's loginSessionId)
  └─ Register new session (Device 2, status=ACTIVE)
  ↓
Device 2 login successful, Device 1 kicked offline
```

---

### 4.6 Error Handling

#### 4.6.1 Error Handling Strategy

**Principle**: **Don't Block Login Flow**

**Implementation**:
```java
.onErrorResume(ex -> {
    log.error("Login session management failed", ex);
    // Even if failed, continue executing default success handler, don't block login flow
    return defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication);
});
```

**Error Scenarios**:
1. **Failed to Get OAuth2AuthorizedClient**: Log error, continue login
2. **Failed to Parse JWT**: Log error, continue login
3. **Failed to Register Session**: Log error, continue login
4. **Blacklist Processing Failed**: Log warning, continue login
5. **Event Publishing Failed**: Log error, continue login

**Reason**:
- Login flow should not be interrupted by session management failure
- User has completed authentication, should be able to login successfully
- Session management failure can be discovered through JWT validation in subsequent requests

---

### 4.7 Chapter Summary

**Core Flow**:
1. OAuth2 authorization code flow (automatically handled by Spring Security)
2. Login success handler (`LoginSessionKickHandler`)
3. Single device login registration (`registerLoginSessionEnforceSingle`)
4. Blacklist processing (`blacklistKickedSessions`)
5. Event publishing (`publishKickedEvent`)
6. HTTP Session storage (`storeLoginSessionIdInSession`)

**Key Code**:
- `LoginSessionKickHandler.onAuthenticationSuccess()`: Main flow
- `extractJwtInfo()`: JWT information extraction
- `extractSid()`: sid extraction
- `registerLoginSessionEnforceSingle()`: Single device login core

**Data Flow**:
- Redis storage: Dual index by `sessionId` and `loginSessionId`
- HTTP Session: Stores `loginSessionId`
- Kafka events: Notify services to disconnect WebSocket

---

## V. Complete JWT Validation Flow Implementation

> **Chapter Objective**: Understand the complete JWT validation flow and master the implementation details of the three-layer validation mechanism (signature, blacklist, state).

---

### 5.1 JWT Validation Overview

#### 5.1.1 Spring Security JWT Validation Flow

**Standard Flow**:
1. Client request carries `Authorization: Bearer {token}`
2. Spring Security Filter Chain intercepts request
3. Calls `ReactiveJwtDecoder.decode(token)` to parse JWT
4. After validation passes, creates `JwtAuthenticationToken`
5. Request continues processing

#### 5.1.2 Custom Validation Logic

**Three-Layer Validation Mechanism**:
1. **Layer 1: Token Signature Validation** (Spring Security automatic)
2. **Layer 2: Blacklist Validation** (custom)
3. **Layer 3: Session State Validation** (custom, core)

---

### 5.2 Code Call Chain (JWT Validation)

#### 5.2.1 Complete Call Chain

```
Client Request
  ↓
GET /api/users/me
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
  ↓
Spring Security Filter Chain
  ↓
OAuth2ResourceServerFilter (Resource Server Filter)
  ↓
BearerTokenAuthenticationFilter
  ↓
Extract token: Authorization header → Bearer token
  ↓
Call ReactiveJwtDecoder.decode(token)
  ↓
JwtDecoderConfig.jwtDecoder() (custom decoder)
  ↓
  ├─ [Layer 1] Check blacklist
  │     JwtBlacklistService.isBlacklisted(token)
  │     ├─ Redis EXISTS jwt:blacklist:{token}
  │     ├─ Hit → Throw JwtException("Token has been revoked")
  │     └─ Miss → Continue
  │
  ├─ [Layer 2] Token signature validation
  │     NimbusReactiveJwtDecoder.decode(token)
  │     ├─ Verify signature (using Keycloak public key)
  │     ├─ Verify expiration time (exp)
  │     ├─ Verify issuer (iss)
  │     ├─ Verify audience (aud)
  │     ├─ Failed → Throw JwtException
  │     └─ Success → Return Jwt object
  │
  └─ [Layer 3] Session state validation
        checkSessionStatus(jwt, token)
        ├─ Extract loginSessionId (sid)
        ├─ Query SessionRegistry
        │     getLoginSessionByLoginSessionId(loginSessionId)
        ├─ Check session state
        │     ├─ Not exists → Skip (backward compatibility)
        │     ├─ ACTIVE → Pass
        │     ├─ KICKED → Throw JwtException("Session is not active: KICKED")
        │     └─ EXPIRED → Throw JwtException("Session is not active: EXPIRED")
        └─ Pass → Return Jwt object
  ↓
Create JwtAuthenticationToken
  ↓
Set to SecurityContext
  ↓
Request continues processing
```

#### 5.2.2 Key Node Descriptions

**Node 1: Blacklist Check**
- Executed first, highest performance
- Immediately rejects revoked tokens

**Node 2: Signature Validation**
- Automatically completed by Spring Security
- Verifies token legitimacy

**Node 3: Session State Validation**
- Core mechanism
- Determines if loginSession is kicked

---

### 5.3 Key Code Analysis

#### 5.3.1 JwtDecoderConfig Class Structure

**File Location**: `apps/gateway/src/main/java/com/gamehub/gateway/config/JwtDecoderConfig.java`

**Class Definition**:
```java
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtDecoderConfig {
    private final JwtBlacklistService jwtBlacklistService;
    private final SessionRegistry sessionRegistry;
    
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;
}
```

**Key Dependencies**:
- `JwtBlacklistService`: JWT blacklist service
- `SessionRegistry`: Session registry

#### 5.3.2 jwtDecoder Bean Method

**Method Signature**:
```java
@Bean
public ReactiveJwtDecoder jwtDecoder()
```

**Complete Implementation**:
```java
@Bean
public ReactiveJwtDecoder jwtDecoder() {
    // Create default Nimbus JWT decoder (for signature validation)
    ReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder
            .withIssuerLocation(issuerUri)
            .build();
    
    // Return custom decoder (wraps default decoder)
    return token -> jwtBlacklistService.isBlacklisted(token)
            .flatMap(blacklisted -> {
                // [Layer 1] Blacklist check
                if (Boolean.TRUE.equals(blacklisted)) {
                    log.warn("【JWT Validation】JWT hit blacklist, access denied. token={}...", shorten(token));
                    return Mono.error(new JwtException("Token has been revoked"));
                }
                
                // [Layer 2] Signature validation (delegated to Nimbus)
                return delegate.decode(token)
                        .flatMap(jwt -> {
                            // [Layer 3] Session state check
                            return checkSessionStatus(jwt, token)
                                    .then(Mono.just(jwt))
                                    .doOnSuccess(j -> log.debug("【JWT Validation】JWT validation passed, sub={}, token={}...", 
                                            j.getSubject(), shorten(token)));
                        });
            });
}
```

**Key Points**:
- ✅ **Three-Layer Validation Order**: Blacklist → Signature → State
- ✅ **Chained Calls**: Uses `flatMap` for chained processing
- ✅ **Error Handling**: Failure at any layer throws `JwtException`

#### 5.3.3 checkSessionStatus Method

**Method Signature**:
```java
private Mono<Void> checkSessionStatus(Jwt jwt, String token)
```

**Complete Implementation**:
```java
private Mono<Void> checkSessionStatus(Jwt jwt, String token) {
    try {
        // 1. Extract loginSessionId from JWT (prefer using sid)
        String loginSessionId = extractLoginSessionId(jwt);
        
        log.info("【JWT Validation】Starting session state check: sub={}, loginSessionId={}, token first 10 chars={}", 
                jwt.getSubject(), loginSessionId, shorten(token));
        
        // 2. If no loginSessionId, skip state check (backward compatibility)
        if (loginSessionId == null || loginSessionId.isBlank()) {
            log.warn("【JWT Validation】JWT has no loginSessionId, skipping state check (backward compatibility): sub={}, jti={}", 
                    jwt.getSubject(), jwt.getId());
            return Mono.empty(); // Skip, don't reject
        }
        
        // 3. Query SessionRegistry, check session state
        LoginSessionInfo sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
        
        // 4. If session not found, skip state check (may be old token or first login)
        if (sessionInfo == null) {
            log.warn("【JWT Validation】Session not found in SessionRegistry, skipping state check: loginSessionId={}, sub={}, jti={}", 
                    loginSessionId, jwt.getSubject(), jwt.getId());
            return Mono.empty(); // Skip, don't reject
        }
        
        // 5. Verify queried session matches (prevent querying wrong session)
        String jwtJti = jwt.getId(); // jti in JWT
        String sessionJti = sessionInfo.getSessionId(); // sessionId (jti) in SessionRegistry
        
        if (!jwtJti.equals(sessionJti)) {
            log.warn("【JWT Validation】⚠️ JWT's jti does not match queried session's sessionId, may have queried wrong session: " +
                    "jwtJti={}, sessionJti={}, loginSessionId={}, sub={}", 
                    jwtJti, sessionJti, loginSessionId, jwt.getSubject());
            // Continue checking state, but log warning
        }
        
        // 6. Check session state
        SessionStatus status = sessionInfo.getStatus();
        if (status == null) {
            // Backward compatibility: if state is null, default to ACTIVE
            status = SessionStatus.ACTIVE;
        }
        
        log.info("【JWT Validation】Session state check: loginSessionId={}, status={}, sub={}, sessionId={}, jwtJti={}, sessionJti={}", 
                loginSessionId, status, jwt.getSubject(), sessionInfo.getSessionId(), jwtJti, sessionJti);
        
        if (status != SessionStatus.ACTIVE) {
            log.warn("【JWT Validation】❌ Session state is not ACTIVE, access denied: loginSessionId={}, status={}, sub={}, token first 10 chars={}", 
                    loginSessionId, status, jwt.getSubject(), shorten(token));
            return Mono.error(new JwtException("Session is not active: " + status));
        }
        
        log.info("【JWT Validation】✅ Session state check passed: loginSessionId={}, status={}, sub={}", 
                loginSessionId, status, jwt.getSubject());
        return Mono.empty(); // Pass
        
    } catch (Exception e) {
        // If exception occurs during check, log but don't block (backward compatibility)
        log.error("【JWT Validation】Session state check exception, skipping check: sub={}", jwt.getSubject(), e);
        return Mono.empty(); // Skip, don't reject
    }
}
```

**Key Logic**:

1. **Extract loginSessionId**
   - Prefer using `sid`
   - If not available, use `session_state` (backward compatibility)

2. **Backward Compatibility Handling**
   - If no `loginSessionId`, skip check
   - If session not found, skip check
   - If state is null, default to ACTIVE

3. **State Check**
   - ACTIVE → Pass
   - KICKED → Reject
   - EXPIRED → Reject

4. **jti Match Verification**
   - Verify JWT's `jti` matches session's `sessionId`
   - Prevent querying wrong session

#### 5.3.4 extractLoginSessionId Method

**Method Signature**:
```java
private String extractLoginSessionId(Jwt jwt)
```

**Implementation Logic**:
```java
// Prefer using sid
Object sidObj = jwt.getClaim("sid");
if (sidObj != null) {
    String sid = sidObj.toString();
    if (sid != null && !sid.isBlank()) {
        return sid;
    }
}

// If no sid, try using session_state (backward compatibility)
Object sessionStateObj = jwt.getClaim("session_state");
if (sessionStateObj != null) {
    String sessionState = sessionStateObj.toString();
    if (sessionState != null && !sessionState.isBlank()) {
        return sessionState;
    }
}

return null;
```

**Key Points**:
- ✅ **Prefer Using sid**: Keycloak native session ID
- ✅ **Backward Compatibility**: If no sid, use session_state
- ✅ **Return null**: If neither exists, return null (backward compatibility)

---

### 5.4 Access Denial Scenarios

#### 5.4.1 Scenario 1: Token in Blacklist

**Trigger Condition**:
- Token added to blacklist (logout, kicked offline)

**Processing Logic**:
```java
if (Boolean.TRUE.equals(blacklisted)) {
    return Mono.error(new JwtException("Token has been revoked"));
}
```

**Result**:
- Returns 401 Unauthorized
- Error message: `Token has been revoked`

#### 5.4.2 Scenario 2: Session State is KICKED

**Trigger Condition**:
- User logged in on another device, current session marked as KICKED

**Processing Logic**:
```java
if (status != SessionStatus.ACTIVE) {
    return Mono.error(new JwtException("Session is not active: " + status));
}
```

**Result**:
- Returns 401 Unauthorized
- Error message: `Session is not active: KICKED`

#### 5.4.3 Scenario 3: Session State is EXPIRED

**Trigger Condition**:
- Session expired or actively logged out by user

**Processing Logic**:
```java
if (status != SessionStatus.ACTIVE) {
    return Mono.error(new JwtException("Session is not active: " + status));
}
```

**Result**:
- Returns 401 Unauthorized
- Error message: `Session is not active: EXPIRED`

#### 5.4.4 Scenario 4: Session Not Found (Backward Compatibility Handling)

**Trigger Condition**:
- Session not found in SessionRegistry (may be old token or first login)

**Processing Logic**:
```java
if (sessionInfo == null) {
    log.warn("Session not found in SessionRegistry, skipping state check");
    return Mono.empty(); // Skip, don't reject
}
```

**Result**:
- **Not Rejected**: Backward compatibility, allows access
- Logs warning

**Reason**:
- Old tokens may not be registered in SessionRegistry
- On first login, SessionRegistry may not have data yet
- Backward compatibility, avoid affecting existing functionality

---

### 5.5 Three-Layer Validation Detailed Description

#### 5.5.1 Layer 1: Blacklist Validation

**Execution Timing**: Executed first

**Implementation**:
```java
jwtBlacklistService.isBlacklisted(token)
    .flatMap(blacklisted -> {
        if (Boolean.TRUE.equals(blacklisted)) {
            return Mono.error(new JwtException("Token has been revoked"));
        }
        // Continue subsequent validation
    });
```

**Purpose**:
- ✅ Immediately invalidate revoked tokens
- ✅ High performance (Redis query)

**Usage Scenarios**:
- User actively logs out
- User logs in on another device (old token added to blacklist)

#### 5.5.2 Layer 2: Signature Validation

**Execution Timing**: After blacklist check passes

**Implementation**:
```java
ReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder
        .withIssuerLocation(issuerUri)
        .build();

return delegate.decode(token);
```

**Purpose**:
- ✅ Verify token is issued by legitimate authorization server
- ✅ Verify token is not expired
- ✅ Verify issuer, audience, etc.

**Validation Content**:
- Whether signature is valid (using Keycloak public key)
- Expiration time (exp)
- Issuer (iss)
- Audience (aud)

#### 5.5.3 Layer 3: Session State Validation

**Execution Timing**: After signature validation passes

**Implementation**:
```java
return checkSessionStatus(jwt, token)
        .then(Mono.just(jwt));
```

**Purpose**:
- ✅ Determine if loginSession is kicked offline
- ✅ Implement single device login (new login kicks out old login)

**Validation Logic**:
1. Extract `loginSessionId` (sid)
2. Query SessionRegistry
3. Check if session state is ACTIVE

---

### 5.6 Validation Flow Diagram

```
Client Request (carrying token)
  ↓
[Layer 1] Blacklist Validation
  ├─ Hit → 401 Unauthorized (Token has been revoked)
  └─ Miss → Continue
  ↓
[Layer 2] Signature Validation
  ├─ Invalid signature → 401 Unauthorized
  ├─ Token expired → 401 Unauthorized
  └─ Pass → Continue
  ↓
[Layer 3] Session State Validation
  ├─ No loginSessionId → Skip (backward compatibility)
  ├─ Session not found → Skip (backward compatibility)
  ├─ State is KICKED → 401 Unauthorized (Session is not active: KICKED)
  ├─ State is EXPIRED → 401 Unauthorized (Session is not active: EXPIRED)
  └─ State is ACTIVE → Pass
  ↓
Create JwtAuthenticationToken
  ↓
Request continues processing
```

---

### 5.7 Backward Compatibility Handling

#### 5.7.1 Why Backward Compatibility Is Needed

**Reasons**:
- Old tokens may not have `loginSessionId`
- Old tokens may not be registered in SessionRegistry
- On first login, SessionRegistry may not have data yet

#### 5.7.2 Backward Compatibility Strategy

**Strategy 1: No loginSessionId**
```java
if (loginSessionId == null || loginSessionId.isBlank()) {
    return Mono.empty(); // Skip, don't reject
}
```

**Strategy 2: Session Not Found**
```java
if (sessionInfo == null) {
    return Mono.empty(); // Skip, don't reject
}
```

**Strategy 3: State is null**
```java
if (status == null) {
    status = SessionStatus.ACTIVE; // Default to ACTIVE
}
```

**Strategy 4: Exception Handling**
```java
catch (Exception e) {
    log.error("Session state check exception, skipping check", e);
    return Mono.empty(); // Skip, don't reject
}
```

---

### 5.8 Chapter Summary

**Core Flow**:
1. Blacklist validation (Layer 1)
2. Signature validation (Layer 2)
3. Session state validation (Layer 3)

**Key Code**:
- `JwtDecoderConfig.jwtDecoder()`: Custom decoder
- `checkSessionStatus()`: Session state check
- `extractLoginSessionId()`: loginSessionId extraction

**Access Denial Scenarios**:
- Token in blacklist
- Session state is KICKED
- Session state is EXPIRED

**Backward Compatibility**:
- No loginSessionId → Skip
- Session not found → Skip
- State is null → Default to ACTIVE

**Next Step**: After understanding the JWT validation flow, we can continue learning the logout flow to understand how to clean up sessions and publish events.

---

## VI. Token Retrieval Interface Implementation

> **Chapter Objective**: Understand the implementation details of the `/token` interface, master why multiple layers of validation are needed to prevent returning wrong tokens, and how to solve the OAuth2AuthorizedClientService overwrite problem.

---

### 6.1 TokenController Overview

#### 6.1.1 Purpose of /token Interface

**Functions**:
- Provides frontend with `access_token` for currently logged-in user
- Supports automatic refresh (if token expired and has `refresh_token`)

**Usage Scenarios**:
- Frontend needs token to call backend API
- Frontend needs token to establish WebSocket connection (pass token when connecting)

#### 6.1.2 Why This Interface Is Needed

**Problem**:
- Gateway uses WebFlux, token after OAuth2 login is saved in server-side Session
- Frontend cannot directly access server-side Session
- Frontend must include `JSESSIONID` cookie saved in browser after login when calling `/token`, so server can find correct Session
- Need interface to get token

**Solution**:
- Provide `/token` interface
- Frontend includes cookie in request, backend locates Session through `JSESSIONID` in cookie, then gets token from Session
- Return to frontend

---

### 6.2 Code Call Chain (Get Token)

#### 6.2.1 Frontend-Backend Interaction Flow

**Frontend Call**:
```javascript
// authService.js
export async function getTokenFromGateway() {
  const res = await fetch('/token', {
    credentials: 'include',  // Important: must include cookie (JSESSIONID)
  })
  
  if (!res.ok) {
    if (res.status === 401) {
      handleAuthExpiredResponse(res, 'GET /token returned 401')
      return null
    }
    throw new Error(`Failed to get token (HTTP ${res.status})`)
  }
  
  const data = await res.json()
  const token = data?.access_token
  saveToken(token)  // Save to localStorage
  return token
}
```

**Backend Processing**:

#### 6.2.2 Complete Call Chain

```
Frontend calls GET /token (with cookie: JSESSIONID)
  ↓
TokenController.getToken()
  ↓
  ├─ 1. Check Authentication
  │     ├─ Not authenticated → Return 401
  │     └─ Authenticated → Continue
  │
  ├─ 2. Call authorizedClientManager.authorize()
  │     └─ Get OAuth2AuthorizedClient from Session
  │
  ├─ 3. Get access_token
  │     └─ authorizedClient.getAccessToken()
  │
  ├─ 4. Parse JWT
  │     jwtDecoder.decode(tokenValue)
  │     ├─ Extract userId (sub)
  │     ├─ Extract jti
  │     └─ Extract loginSessionId (sid)
  │
  ├─ 5. Verify loginSessionId matches HTTP Session
  │     ├─ Get loginSessionId from HTTP Session
  │     ├─ Compare token's loginSessionId with Session's loginSessionId
  │     ├─ Mismatch → Return 401 (token has been overwritten)
  │     └─ Match → Continue
  │
  ├─ 6. Verify session state is ACTIVE
  │     sessionRegistry.getLoginSessionByLoginSessionId()
  │     ├─ State not ACTIVE → Return 401
  │     └─ State is ACTIVE → Continue
  │
  ├─ 7. Verify token's jti matches session's sessionId
  │     ├─ Compare jwt.getId() with sessionInfo.getSessionId()
  │     ├─ Mismatch → Call refreshLoginSession to update SessionRegistry (token refresh)
  │     └─ Match → Continue
  │
  └─ 8. Return token
        └─ Return access_token and refresh_token
```

#### 6.2.2 Key Node Descriptions

**Node 1: Authentication Check**
- Ensures user is logged in
- Returns 401 if not logged in

**Node 2: Get OAuth2AuthorizedClient**
- Get from Session
- Automatically refreshes if token expired

**Nodes 3-4: Parse JWT**
- Extract key information
- Used for subsequent validation

**Nodes 5-7: Multi-Layer Validation**
- Prevent returning wrong token
- Solve OAuth2AuthorizedClientService overwrite problem

---

### 6.3 Key Code Analysis

#### 6.3.1 TokenController Class Structure

**File Location**: `apps/gateway/src/main/java/com/gamehub/gateway/controller/TokenController.java`

**Class Definition**:
```java
@Slf4j
@RestController
public class TokenController {
    private static final String SESSION_LOGIN_SESSION_ID_KEY = "LOGIN_SESSION_ID";
    
    private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;
    private final ReactiveJwtDecoder jwtDecoder;
    private final SessionRegistry sessionRegistry;
}
```

**Key Dependencies**:
- `ReactiveOAuth2AuthorizedClientManager`: Gets OAuth2 authorized client
- `ReactiveJwtDecoder`: Parses JWT
- `SessionRegistry`: Queries session state

#### 6.3.2 getToken Method

**Method Signature**:
```java
@GetMapping("/token")
public Mono<ResponseEntity<Map<String, Object>>> getToken(Authentication authentication, ServerWebExchange exchange)
```

**Complete Implementation Flow**:

**Step 1: Check Authentication**
```java
if (authentication == null || !authentication.isAuthenticated()) {
    return Mono.just(ResponseEntity.status(401).body(
            Map.<String, Object>of("error", "未登录", "message", "请先通过 /oauth2/authorization/keycloak 登录")
    ));
}
```

**Step 2: Get OAuth2AuthorizedClient**
```java
OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
        .withClientRegistrationId("keycloak")
        .principal(authentication)
        .build();

return authorizedClientManager.authorize(authorizeRequest)
        .flatMap(authorizedClient -> {
            // authorizedClient contains access_token and refresh_token
        });
```

**Steps 3-4: Parse JWT**
```java
OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
String tokenValue = accessToken.getTokenValue();

return jwtDecoder.decode(tokenValue)
        .flatMap(jwt -> {
            String loginSessionId = extractLoginSessionId(jwt);
            String jwtJti = jwt.getId();
            String userId = jwt.getSubject();
        });
```

**Step 5: Verify loginSessionId Matches HTTP Session**
```java
if (loginSessionId != null && !loginSessionId.isBlank()) {
    return exchange.getSession()
            .flatMap((WebSession session) -> {
                String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
                
                // If Session has no loginSessionId, skip validation (backward compatibility)
                if (sessionLoginSessionId == null || sessionLoginSessionId.isBlank()) {
                    // Skip validation
                } else if (!loginSessionId.equals(sessionLoginSessionId)) {
                    // Token's loginSessionId doesn't match Session's, token has been overwritten
                    return Mono.just(ResponseEntity.status(401).body(
                            Map.<String, Object>of("error", "Token 已失效", "message", "请重新登录")
                    ));
                }
            });
}
```

**Key Points**:
- ✅ **Prevent Token Overwrite**: If token's `loginSessionId` doesn't match Session's, token has been overwritten by another login
- ✅ **Backward Compatibility**: If Session has no `loginSessionId`, skip validation

**Step 6: Verify Session State is ACTIVE**
```java
var sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
if (sessionInfo != null) {
    if (sessionInfo.getStatus() != null 
            && sessionInfo.getStatus() != SessionStatus.ACTIVE) {
        // Session state is not ACTIVE, reject returning token
        return Mono.just(ResponseEntity.status(401).body(
                Map.<String, Object>of("error", "会话已失效", "message", "请重新登录")
        ));
    }
}
```

**Key Points**:
- ✅ **Check Session State**: Ensure session state is ACTIVE
- ✅ **Prevent Using Kicked Session**: If session state is KICKED or EXPIRED, reject returning token

**Step 7: Verify Token's jti Matches Session's sessionId, Update SessionRegistry if Token Refreshed**
```java
String sessionJti = sessionInfo.getSessionId();
if (!jwtJti.equals(sessionJti)) {
    // Token's jti doesn't match session's sessionId, token has been refreshed
    // Update sessionId, token, issuedAt, expiresAt in SessionRegistry
    log.info("Detected token refresh, updating SessionRegistry: userId={}, loginSessionId={}, oldJti={}, newJti={}",
            userId, loginSessionId, sessionJti, jwtJti);
    
    sessionInfo.setSessionId(jwtJti);
    sessionInfo.setToken(tokenValue);
    sessionInfo.setIssuedAt(issuedAtMillis);
    sessionInfo.setExpiresAt(expiresAtMillis);
    
    long refreshTtlSeconds = resolveRefreshTtlSeconds(refreshToken);
    sessionRegistry.refreshLoginSession(sessionInfo, sessionJti, refreshTtlSeconds);
}
```

**Key Points**:
- ✅ **Detect Token Refresh**: If token's `jti` doesn't match session's `sessionId`, token has been refreshed
- ✅ **Update SessionRegistry**: Call `refreshLoginSession()` to update session information (delete old sessionId record, add new sessionId record)
- ✅ **Keep loginSessionId Unchanged**: `loginSessionId` remains unchanged when token is refreshed, ensuring session association is correct

**Step 8: Return Token**
```java
Map<String, Object> result = new HashMap<>();
result.put("access_token", tokenValue);
result.put("token_type", accessToken.getTokenType().getValue());
if (accessToken.getExpiresAt() != null) {
    result.put("expires_at", accessToken.getExpiresAt().toEpochMilli());
}
OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
if (refreshToken != null) {
    result.put("refresh_token", refreshToken.getTokenValue());
    if (refreshToken.getExpiresAt() != null) {
        result.put("refresh_token_expires_at", refreshToken.getExpiresAt().toEpochMilli());
    }
}
return Mono.just(ResponseEntity.ok(result));
```

---

### 6.4 Why Validation Is Needed

#### 6.4.1 OAuth2AuthorizedClientService Overwrite Problem

**Problem Description**:

When `OAuth2AuthorizedClientService` stores `OAuth2AuthorizedClient`, it uses `userId` as key. This means:

```
User A logs in on Device 1
  ↓
OAuth2AuthorizedClientService stores:
  key: userId="user-A"
  value: OAuth2AuthorizedClient(token1, loginSessionId="sid-001")

User A logs in on Device 2
  ↓
OAuth2AuthorizedClientService stores:
  key: userId="user-A"
  value: OAuth2AuthorizedClient(token2, loginSessionId="sid-002")
  ↓
Device 1's token1 is overwritten!
```

**Result**:
- When Device 1 calls `/token` interface, may get Device 2's token
- Device 1 uses wrong token to access resources, causing access control chaos

#### 6.4.2 Solution: Multi-Layer Validation

**Validation 1: loginSessionId Matches HTTP Session**
```java
String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
if (!loginSessionId.equals(sessionLoginSessionId)) {
    // Token's loginSessionId doesn't match Session's, token has been overwritten
    return 401;
}
```

**Principle**:
- On login, store `loginSessionId` to HTTP Session
- When getting token, verify token's `loginSessionId` matches Session's
- If mismatch, token has been overwritten by another login

**Validation 2: Session State is ACTIVE**
```java
if (sessionInfo.getStatus() != SessionStatus.ACTIVE) {
    // Session state is not ACTIVE, reject returning token
    return 401;
}
```

**Principle**:
- If session state is KICKED or EXPIRED, session has expired
- Reject returning token, force user to re-login

**Validation 3: Token's jti Matches Session's sessionId (Update SessionRegistry on Token Refresh)**
```java
if (!jwtJti.equals(sessionJti)) {
    // Token's jti doesn't match session's sessionId, token has been refreshed
    // Update session information in SessionRegistry
    sessionInfo.setSessionId(jwtJti);
    sessionInfo.setToken(tokenValue);
    sessionInfo.setIssuedAt(issuedAtMillis);
    sessionInfo.setExpiresAt(expiresAtMillis);
    
    long refreshTtlSeconds = resolveRefreshTtlSeconds(refreshToken);
    sessionRegistry.refreshLoginSession(sessionInfo, sessionJti, refreshTtlSeconds);
}
```

**resolveRefreshTtlSeconds Method**:
```java
private long resolveRefreshTtlSeconds(OAuth2RefreshToken refreshToken) {
    if (refreshToken != null && refreshToken.getExpiresAt() != null) {
        long ttl = Duration.between(Instant.now(), refreshToken.getExpiresAt()).getSeconds();
        return Math.max(ttl, 0);
    }
    return 0;
}
```

**Principle**:
- If `jti` doesn't match, token has been refreshed (`jti` changes on refresh)
- Call `refreshLoginSession()` to update SessionRegistry:
  - Delete old `sessionId` record
  - Add new `sessionId` record
  - Update `loginSessionId` index (keep `loginSessionId` unchanged)
- TTL Calculation: Use `refresh_token` expiration time to calculate TTL, ensuring session lifecycle is consistent with refresh_token
- Ensure session information in SessionRegistry matches current token

#### 6.4.3 Validation Flow Diagram

```
Device 1 calls /token
  ↓
Get OAuth2AuthorizedClient (may be overwritten by Device 2)
  ↓
Parse JWT, extract loginSessionId="sid-002" (Device 2's)
  ↓
Get loginSessionId="sid-001" from HTTP Session (Device 1's)
  ↓
Compare: sid-002 != sid-001
  ↓
Return 401 (Token expired, please re-login)
  ↓
Device 1 re-login
```

---

### 6.5 Backward Compatibility Handling

#### 6.5.1 No loginSessionId Case

**Processing Logic**:
```java
if (loginSessionId == null || loginSessionId.isBlank()) {
    // No loginSessionId, directly return token (backward compatibility)
    return Mono.just(ResponseEntity.ok(result));
}
```

**Reason**:
- Old tokens may not have `loginSessionId`
- Backward compatibility, avoid affecting existing functionality

#### 6.5.2 No loginSessionId in Session Case

**Processing Logic**:
```java
if (sessionLoginSessionId == null || sessionLoginSessionId.isBlank()) {
    // Skip validation (backward compatibility)
}
```

**Reason**:
- Old logins may not have stored `loginSessionId` in Session
- Backward compatibility, avoid affecting existing functionality

---

### 6.6 Chapter Summary

**Core Functions**:
1. Provides interface for frontend to get token
2. Supports automatic token refresh
3. Multi-layer validation prevents returning wrong token

**Key Validations**:
1. **loginSessionId Matches HTTP Session**: Prevents token overwrite
2. **Session State is ACTIVE**: Prevents using kicked session
3. **Token's jti Matches Session's sessionId**: Ensures returned token belongs to current session

**Problems Solved**:
- ✅ **OAuth2AuthorizedClientService Overwrite Problem**: Through multi-layer validation, ensures returned token belongs to current session
- ✅ **Single Device Login Problem**: If token has been overwritten by another login, reject returning token



---

## VII. Complete Logout Flow Implementation

> **Chapter Objective**: Understand the complete logout flow, master how to clean up sessions, add to blacklist, publish events, and why the JWT token is added to blacklist on logout.

---

### 7.1 Logout Flow Overview

#### 7.1.1 User Logout Flow

**Standard Flow**:
1. User clicks logout button
2. Frontend calls `/logout` interface
3. Spring Security Logout Filter intercepts request
4. Executes custom logout handler
5. Calls Keycloak OIDC logout interface
6. Redirects to login page or homepage

#### 7.1.2 Logout Processing Logic

**Custom Processing Logic** (`jwtBlacklistLogoutHandler`):
1. Get `OAuth2AuthorizedClient` (contains access_token)
2. Add `access_token` to blacklist
3. Extract `loginSessionId` from JWT
4. Publish SESSION_INVALIDATED event (contains `loginSessionId`)
5. Clean up login sessions in SessionRegistry (call `removeAllSessions(userId)`)
6. Remove `OAuth2AuthorizedClient`
7. Call Keycloak OIDC logout interface

---

### 7.2 Code Call Chain (Logout)

#### 7.2.1 Frontend-Backend Interaction Flow

**Frontend Call**:
```javascript
// authService.js
export async function logoutFromGateway(redirectUri = window.location.origin) {
  try {
    await fetch('/logout', {
      method: 'POST',
      credentials: 'include',  // Important: must include cookie (JSESSIONID)
    })
  } catch (error) {
    console.warn('Calling /logout failed, directly clean local state', error)
  } finally {
    clearToken()  // Clear token in localStorage
    sessionLoggingOut = false
    if (redirectUri) {
      window.location.href = redirectUri  // Redirect to homepage
    }
  }
}
```

**Backend Processing**:

#### 7.2.2 Complete Call Chain

```
User clicks logout button
  ↓
Frontend calls POST /logout (with cookie: JSESSIONID)
  ↓
Spring Security Logout Filter
  ↓
SecurityConfig.logout() configuration
  ↓
Execute jwtBlacklistLogoutHandler (custom logout handler)
  ↓
  ├─ 1. Get OAuth2AuthorizedClient
  │     authorizedClientRepository.loadAuthorizedClient()
  │
  ├─ 2. Add access_token to blacklist
  │     addTokenToBlacklist()
  │     └─ JwtBlacklistService.addToBlacklist(token, ttl)
  │         └─ Redis SET jwt:blacklist:{token} "1" EX {ttl}
  │
  ├─ 3. Extract loginSessionId from JWT
  │     publishSessionInvalidatedEvent()
  │     ├─ Get JWT from Authentication
  │     └─ Extract loginSessionId (sid)
  │
  ├─ 4. Publish SESSION_INVALIDATED event
  │     SessionEventPublisher.publishSessionInvalidated()
  │     └─ Kafka sends event (contains loginSessionId)
  │
  ├─ 5. Clean up login sessions in SessionRegistry
  │     sessionRegistry.removeAllSessions(userId)
  │     └─ Delete all login session records for this user
  │
  └─ 6. Remove OAuth2AuthorizedClient
        authorizedClientRepository.removeAuthorizedClient()
  ↓
Call OIDC logout success handler
  ↓
OidcClientInitiatedServerLogoutSuccessHandler
  ↓
Call Keycloak OIDC logout interface
  ↓
Redirect to frontend homepage (http://localhost:5173/)
  ↓
Logout complete
```

#### 7.2.2 Key Node Descriptions

**Node 1: Get OAuth2AuthorizedClient**
- Get saved authorized client from Session
- Contains access_token and refresh_token

**Node 2: Add to Blacklist**
- Add access_token to blacklist
- Immediately invalidate token

**Node 3: Publish Event**
- Publish SESSION_INVALIDATED event
- Notify services to disconnect WebSocket connections

**Node 5: Clean SessionRegistry**
- Call `removeAllSessions(userId)` to clear all login sessions for this user
- Ensure session data is completely cleaned

**Node 6: Remove Authorized Client**
- Clear authorized client from Session
- Prevent token from being reused

---

### 7.3 Key Code Analysis

#### 7.3.1 SecurityConfig.logout Configuration

**File Location**: `apps/gateway/src/main/java/com/gamehub/gateway/config/SecurityConfig.java`

**Configuration Code**:
```java
http.logout(l -> {
    // Add custom logout handler (responsible for blacklist and event publishing)
    l.logoutHandler(jwtBlacklistLogoutHandler(blacklistService, authorizedClientRepository, sessionEventPublisher));
    
    // Set OIDC logout success handler (calls Keycloak logout interface)
    l.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository));
});
```

**Key Points**:
- ✅ **Logout Handler**: Executes blacklist and event publishing
- ✅ **Logout Success Handler**: Calls Keycloak logout interface

#### 7.3.2 jwtBlacklistLogoutHandler Method

**Method Signature**:
```java
@Bean
public ServerLogoutHandler jwtBlacklistLogoutHandler(
        JwtBlacklistService blacklistService,
        ServerOAuth2AuthorizedClientRepository authorizedClientRepository,
        @Autowired(required = false) SessionEventPublisher sessionEventPublisher)
```

**Complete Implementation**:
```java
@Bean
public ServerLogoutHandler jwtBlacklistLogoutHandler(...) {
    return (WebFilterExchange exchange, Authentication authentication) -> {
        if (authentication == null) {
            return Mono.empty();
        }
        
        return authorizedClientRepository
                .loadAuthorizedClient(REGISTRATION_ID, authentication, exchange.getExchange())
                .flatMap(client -> {
                    // 1. Write to blacklist
                    Mono<Void> blacklistMono = addTokenToBlacklist(client, blacklistService);
                    
                    // 2. Publish session invalidation event (extract user ID from JWT, if SessionEventPublisher is available)
                    Mono<Void> publishMono = (sessionEventPublisher != null) 
                            ? publishSessionInvalidatedEvent(authentication, sessionEventPublisher)
                            : Mono.empty();
                    
                    // 3. Clean up login sessions in SessionRegistry
                    Mono<Void> cleanupMono = Mono.fromRunnable(() -> {
                        try {
                            String userId = null;
                            if (authentication.getPrincipal() instanceof Jwt jwt) {
                                userId = jwt.getSubject();
                            } else if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal principal) {
                                userId = principal.getName();
                            } else {
                                userId = authentication.getName();
                            }
                            if (userId != null && !userId.isBlank()) {
                                sessionRegistry.removeAllSessions(userId);
                                log.info("Cleaning SessionRegistry on logout: userId={}", userId);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to clean SessionRegistry on logout", e);
                        }
                    });
                    
                    // Execute in parallel, don't block
                    return Mono.when(blacklistMono, publishMono, cleanupMono);
                })
                .then(authorizedClientRepository.removeAuthorizedClient(REGISTRATION_ID, authentication, exchange.getExchange()))
                .doOnError(ex -> log.error("Logout processing failed", ex))
                .onErrorResume(ex -> Mono.empty());
    };
}
```

**Key Logic**:
1. ✅ **Get Authorized Client**: Get from Session
2. ✅ **Parallel Execution**: Blacklist and event publishing execute in parallel
3. ✅ **Remove Authorized Client**: Clear authorized client from Session
4. ✅ **Error Handling**: Log errors, don't block logout flow

#### 7.3.3 addTokenToBlacklist Method

**Method Signature**:
```java
private Mono<Void> addTokenToBlacklist(OAuth2AuthorizedClient client, JwtBlacklistService blacklistService)
```

**Complete Implementation**:
```java
private Mono<Void> addTokenToBlacklist(OAuth2AuthorizedClient client, JwtBlacklistService blacklistService) {
    if (client == null || client.getAccessToken() == null) {
        return Mono.empty();
    }
    
    String token = client.getAccessToken().getTokenValue();
    Instant expiresAt = client.getAccessToken().getExpiresAt();
    
    // Calculate remaining TTL (seconds)
    long expiresIn = 0;
    if (expiresAt != null) {
        expiresIn = Duration.between(Instant.now(), expiresAt).getSeconds();
    }
    expiresIn = Math.max(expiresIn, 0);
    
    // Add to blacklist
    return blacklistService.addToBlacklist(token, expiresIn);
}
```

**Key Points**:
- ✅ **Calculate TTL**: Use token remaining validity period as blacklist TTL
- ✅ **Immediate Invalidation**: Token is immediately invalidated after being added to blacklist

#### 7.3.4 publishSessionInvalidatedEvent Method

**Method Signature**:
```java
private Mono<Void> publishSessionInvalidatedEvent(Authentication authentication, SessionEventPublisher sessionEventPublisher)
```

**Complete Implementation**:
```java
private Mono<Void> publishSessionInvalidatedEvent(Authentication authentication, 
                                                   SessionEventPublisher sessionEventPublisher) {
    return Mono.fromRunnable(() -> {
        try {
            String userId = null;
            String loginSessionId = null;
            
            // Extract user ID and loginSessionId from Authentication
            if (authentication.getPrincipal() instanceof Jwt jwt) {
                userId = jwt.getSubject();
                
                // Extract loginSessionId from JWT (prefer using sid)
                Object sidObj = jwt.getClaim("sid");
                if (sidObj != null) {
                    String sid = sidObj.toString();
                    if (sid != null && !sid.isBlank()) {
                        loginSessionId = sid;
                    }
                }
                
                // If no sid, try using session_state (backward compatibility)
                if (loginSessionId == null) {
                    Object sessionStateObj = jwt.getClaim("session_state");
                    if (sessionStateObj != null) {
                        String sessionState = sessionStateObj.toString();
                        if (sessionState != null && !sessionState.isBlank()) {
                            loginSessionId = sessionState;
                        }
                    }
                }
            } else {
                userId = authentication.getName();
            }
            
            if (userId != null && !userId.isBlank()) {
                // If loginSessionId is extracted, use factory method that includes loginSessionId
                SessionInvalidatedEvent event;
                if (loginSessionId != null && !loginSessionId.isBlank()) {
                    event = SessionInvalidatedEvent.of(userId, loginSessionId, 
                            SessionInvalidatedEvent.EventType.LOGOUT, "用户主动登出");
                } else {
                    // If no loginSessionId, use factory method without loginSessionId (backward compatibility)
                    event = SessionInvalidatedEvent.of(userId, 
                            SessionInvalidatedEvent.EventType.LOGOUT, "用户主动登出");
                }
                sessionEventPublisher.publishSessionInvalidated(event);
            }
        } catch (Exception e) {
            log.error("Failed to publish session invalidation event", e);
            // Don't throw exception, avoid affecting logout flow
        }
    });
}
```

**Key Points**:
- ✅ **Extract loginSessionId**: Extract from JWT (prefer using sid)
- ✅ **Publish Event**: Contains `loginSessionId` for precise WebSocket disconnection
- ✅ **Error Handling**: Log errors, don't block logout flow

---

### 7.4 Blacklist Mechanism on Logout

#### 7.4.1 Why JWT Token Is Added to Blacklist on Logout

**Question**: Why is the JWT token added to blacklist, not `loginSessionId`?

**Answer**:

1. **JWT token is access credential**
   - Clients use JWT token to access resources
   - Purpose of blacklist is to immediately invalidate issued tokens
   - After adding to blacklist, the token immediately becomes unusable

2. **loginSessionId is session identifier**
   - `loginSessionId` is used to identify entire login session
   - One `loginSessionId` can correspond to multiple tokens (token refresh)
   - If only `loginSessionId` is added to blacklist, cannot immediately invalidate issued tokens

3. **Both work together**
   - **Blacklist**: Immediately invalidate issued tokens
   - **Session State**: Manage state of entire login session

**Example**:
```
User logs out:
  Token: jti="jti-001", loginSessionId="sid-001"
  
Add to blacklist:
  jwt:blacklist:{token} → "1"  ← Full token string is added
  
Result:
  - This token is immediately invalidated (blacklist check)
  - This loginSessionId's session state can be marked as EXPIRED (optional)
```

#### 7.4.2 JWT vs loginSessionId

**Comparison**:

| Feature | JWT token | loginSessionId |
|------|-----------|----------------|
| **Purpose** | Access credential | Session identifier |
| **Add to Blacklist** | ✅ Yes (immediate invalidation) | ❌ No (not access credential) |
| **Scope** | Single token | Entire login session |
| **Invalidation Method** | Blacklist check | Session state check |

**Relationship**:
```
LoginSession (loginSessionId: "sid-001")
  ├── Token 1 (jti: "jti-001") → Added to blacklist
  ├── Token 2 (jti: "jti-002") → Not added to blacklist (if refreshed)
  └── Token 3 (jti: "jti-003") → Not added to blacklist (if refreshed)
```

**On Logout**:
- ✅ Add current token to blacklist (immediate invalidation)
- ✅ Publish SESSION_INVALIDATED event (contains `loginSessionId`)
- ✅ Services receive event and disconnect all WebSocket connections for this `loginSessionId`

#### 7.4.3 TTL Calculation

**Calculation Logic**:
```java
Instant expiresAt = client.getAccessToken().getExpiresAt();
long expiresIn = 0;
if (expiresAt != null) {
    expiresIn = Duration.between(Instant.now(), expiresAt).getSeconds();
}
expiresIn = Math.max(expiresIn, 0);
```

**Key Points**:
- ✅ **Use Token Remaining Validity Period**: As blacklist TTL
- ✅ **Automatic Expiration**: After token expires, blacklist record is automatically deleted
- ✅ **Save Space**: No need to manually clean expired blacklist records

**Example**:
```
Token expiration time: 2024-01-01 12:00:00
Current time: 2024-01-01 11:45:00
Remaining time: 15 minutes = 900 seconds

Add to blacklist:
  Redis SET jwt:blacklist:{token} "1" EX 900
```

---

### 7.5 Logout Flow Diagram

```
User clicks logout
  ↓
Call /logout interface
  ↓
Spring Security Logout Filter
  ↓
jwtBlacklistLogoutHandler
  ├─ Get OAuth2AuthorizedClient
  ├─ Add access_token to blacklist
  ├─ Extract loginSessionId
  ├─ Publish SESSION_INVALIDATED event
  └─ Remove OAuth2AuthorizedClient
  ↓
OIDC logout success handler
  ↓
Call Keycloak OIDC logout interface
  ↓
Redirect to homepage
  ↓
Logout complete

Meanwhile:
Kafka event → Service listeners
  ↓
SessionInvalidatedListener.onSessionInvalidated()
  ↓
Query WebSocket sessions based on loginSessionId
  ↓
Disconnect all WebSocket connections
```

---

### 7.6 Data Cleanup on Logout

#### 7.6.1 Cleanup Content

**Cleanup Items**:
1. ✅ **OAuth2AuthorizedClient**: Removed from Session
2. ✅ **Token Added to Blacklist**: Immediately invalidated
3. ✅ **Event Published**: Notify services to disconnect WebSocket
4. ✅ **Session Records in SessionRegistry**: Call `removeAllSessions(userId)` to clear all login sessions for this user

**Cleanup When Kicked by Single Device Login**:
- ✅ **Kicked Old Sessions**: Call `unregisterLoginSession(sessionId)` to delete old session records from SessionRegistry

---

### 7.7 Chapter Summary

**Core Flow**:
1. Get OAuth2AuthorizedClient
2. Add access_token to blacklist
3. Extract loginSessionId
4. Publish SESSION_INVALIDATED event
5. Clean up login sessions in SessionRegistry (`removeAllSessions(userId)`)
6. Remove OAuth2AuthorizedClient
7. Call Keycloak OIDC logout interface

**Key Code**:
- `SecurityConfig.jwtBlacklistLogoutHandler()`: Logout handler
- `addTokenToBlacklist()`: Blacklist processing
- `publishSessionInvalidatedEvent()`: Event publishing

**Blacklist Mechanism**:
- ✅ What is added to blacklist is **JWT token** (full token string)
- ✅ Not `loginSessionId` (`loginSessionId` is used for session management)
- ✅ TTL uses token remaining validity period



---

## VII.5. Frontend Authentication Implementation

> **Chapter Objective**: Understand the complete frontend authentication implementation, master how frontend interacts with Gateway to obtain tokens, how to manage authentication state, how to establish WebSocket connections, and the APIs used for frontend-backend interaction.

---

### 7.5.1 Frontend Authentication Architecture Overview

#### 7.5.1.1 Frontend Authentication Flow

**Core Components**:
- **AuthContext**: React Context, manages global authentication state
- **authService**: Authentication service, encapsulates interactions with Gateway
- **apiClient**: HTTP client, automatically carries token
- **gomokuSocket**: WebSocket service, passes token when establishing connection

**Authentication Flow**:
1. When application starts, `AuthProvider` initializes, calls `ensureAuthenticated()`
2. Prioritize getting latest token from Gateway (`GET /token`, automatic refresh)
3. Verify token validity (call `/game-service/me`)
4. If token invalid or doesn't exist, redirect to login page (`/oauth2/authorization/keycloak`)
5. After successful login, Gateway redirects back to frontend, frontend gets token again
6. Save token to localStorage for subsequent use

#### 7.5.1.2 APIs for Frontend-Backend Interaction

**APIs Provided by Gateway**:
- `GET /token`: Get access_token for currently logged-in user (supports automatic refresh)
- `GET /oauth2/authorization/keycloak`: Trigger OAuth2 login flow
- `POST /logout`: Logout interface

**APIs Provided by Application Services** (for token validation):
- `GET /game-service/me`: Get current user information (for validating token validity)
- `GET /system-service/api/users/me`: Get detailed user information (backup)

---

### 7.5.2 Frontend Authentication Service (authService)

#### 7.5.2.1 Core Methods

**File Location**: `game-hub-web/src/services/auth/authService.js`

**Key Constants**:
```javascript
const TOKEN_STORAGE_KEY = 'access_token'  // Token key name in localStorage
const GATEWAY_LOGIN_URL = '/oauth2/authorization/keycloak'  // Login entry
const GATEWAY_TOKEN_URL = '/token'  // Token retrieval interface
```

**Core Methods**:

1. **`getTokenFromGateway()`**: Get token from Gateway
   - Calls `GET /token` (with cookie, credentials: 'include')
   - Gateway locates Session through JSESSIONID in cookie, returns token
   - If returns 401, indicates not logged in, triggers login flow
   - After success, saves token to localStorage

2. **`ensureAuthenticated(autoLogin = true)`**: Ensure authenticated
   - **autoLogin=true**: Prioritize getting latest token from Gateway (can auto refresh)
     - Calls `getTokenFromGateway()`
     - Validates token validity (`validateToken()`)
     - If valid, returns token
     - If invalid, clears local token
   - Then try using locally saved token
     - Reads token from localStorage
     - Validates token validity
     - If valid, returns token
   - Finally triggers login flow (if autoLogin=true)
     - Calls `initAndLogin()`, redirects to login page

3. **`validateToken(token)`**: Validate token validity
   - Calls `GET /game-service/me` (carries token)
   - If returns 401, token invalid, triggers login flow
   - If returns 200, token valid

4. **`getUserInfo()`**: Get user information
   - Prioritize calling `GET /system-service/api/users/me` (get detailed user information, includes id, username, nickname, email, etc.)
   - If fails (returns non-200), fallback to `GET /game-service/me` (extract user information from JWT, includes sub, username, email, etc.)
   - If both fail, returns null

5. **`initAndLogin()`**: Initialize and login
   - First try getting token from Gateway
   - If fails, redirect to `/oauth2/authorization/keycloak`

6. **`logoutFromGateway()`**: Logout
   - Calls `POST /logout` (with cookie)
   - Gateway executes logout processing (blacklist, event publishing, clean Session)
   - Clears local token
   - Redirects to homepage

#### 7.5.2.2 Authentication Expiration Handling

**Authentication Expiration Scenarios**:
- HTTP request returns 401
- WebSocket connection rejected (401)
- Token validation failed

**Handling Mechanism**:
- Display authentication expired popup (`showAuthModal()`)
- Provide "Log In Again" button, redirect to login page
- Clear local token

**Key Code**:
```javascript
export function handleAuthExpiredResponse(res, context) {
  // Check if response header has X-Auth-Redirect-To
  const redirect = res.headers.get('X-Auth-Redirect-To')
  if (redirect) {
    showAuthModal('登录状态已过期，请重新登录。')
    return
  }
  performSessionLogout(context)
}

export function performSessionLogout(reason = '') {
  clearToken()
  showAuthModal(reason || '会话已失效，请点击"重新登录"再次进入游戏。')
}
```

---

### 7.5.3 Frontend Authentication Context (AuthContext)

#### 7.5.3.1 AuthProvider Component

**File Location**: `game-hub-web/src/contexts/AuthContext.jsx`

**Responsibilities**:
- Manages global authentication state (`isAuthenticated`, `isLoading`, `user`)
- Automatically initializes authentication state when application starts
- Provides methods for login, logout, refresh user information

**Initialization Flow**:
```javascript
useEffect(() => {
  async function bootstrap() {
    // 1. Ensure authenticated (non-public routes trigger login)
    const token = await ensureAuthenticated(!isPublicRoute)
    
    // 2. If token obtained, set authentication state
    if (token) {
      setIsAuthenticated(true)
      await loadUserProfile()  // Load user information
    } else {
      setIsAuthenticated(false)
    }
    
    setIsLoading(false)
  }
  bootstrap()
}, [isPublicRoute, loadUserProfile])
```

**Public Routes**:
- `/sessions`: Session monitoring page (for debugging, no authentication required)

**Key Methods**:
- `login()`: Calls `initAndLogin()`, redirects to login page
- `logout()`: Calls `logoutFromGateway()`, executes logout
- `refreshUser()`: Reloads user information

#### 7.5.3.2 useAuth Hook

**File Location**: `game-hub-web/src/hooks/useAuth.js` (re-exported from `AuthContext.jsx`)

**Purpose**: Extract authentication state and methods from AuthContext

**Implementation**:
```javascript
// hooks/useAuth.js is just a re-export, actual implementation is in AuthContext.jsx
export { useAuth } from '../contexts/AuthContext.jsx'
```

**Usage Example**:
```javascript
const { isAuthenticated, isLoading, user, login, logout } = useAuth()
```

---

### 7.5.4 Route Protection (ProtectedRoute)

#### 7.5.4.1 ProtectedRoute Component

**File Location**: `game-hub-web/src/components/common/ProtectedRoute.jsx`

**Responsibilities**:
- Protects routes requiring authentication
- Redirects to homepage when not authenticated

**Implementation**:
```javascript
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../../hooks/useAuth.js'

const ProtectedRoute = ({ children }) => {
  const location = useLocation()
  const { isAuthenticated, isLoading } = useAuth()
  
  if (isLoading) {
    return <div className="page page--centered">正在检查登录状态...</div>
  }
  
  if (!isAuthenticated) {
    return <Navigate to="/" replace state={{ from: location }} />
  }
  
  return children
}
```

**Usage**:
```javascript
<Route
  path="/lobby"
  element={
    <ProtectedRoute>
      <LobbyPage />
    </ProtectedRoute>
  }
/>
```

---

### 7.5.5 HTTP Request Authentication (apiClient)

#### 7.5.5.1 authenticatedFetch Method

**File Location**: `game-hub-web/src/services/api/apiClient.js`

**Responsibilities**:
- Encapsulates HTTP requests, automatically carries token
- Unified handling of 401 errors

**Implementation**:
```javascript
export async function authenticatedFetch(url, options = {}) {
  // 1. Ensure authenticated, get token
  const token = await ensureAuthenticated()
  if (!token) {
    throw new Error('未登录')
  }
  
  // 2. Add Authorization to request headers
  const headers = new Headers(options.headers || {})
  headers.set('Authorization', `Bearer ${token}`)
  
  // 3. Send request
  const response = await fetch(url, {
    ...options,
    headers,
  })
  
  // 4. If returns 401, trigger authentication expiration handling
  if (response.status === 401) {
    handleAuthExpiredResponse(response, `${options.method || 'GET'} ${url} 返回 401`)
    throw new Error('认证失败')
  }
  
  return response
}
```

**Usage**:
```javascript
// GET request
const data = await get('/game-service/api/gomoku/rooms')

// POST request
const result = await post('/game-service/api/gomoku/new', { mode: 'PVE' })
```

---

### 7.5.6 WebSocket Connection Authentication (gomokuSocket)

#### 7.5.6.1 connectWebSocket Method

**File Location**: `game-hub-web/src/services/ws/gomokuSocket.js`

**Responsibilities**:
- Establishes WebSocket connection
- Passes token when connecting

**Implementation**:
```javascript
export async function connectWebSocket(callbacks = {}) {
  // 1. Ensure authenticated, get token
  const token = await ensureAuthenticated()
  if (!token) {
    throw new Error('未登录')
  }
  
  // 2. Pass token via URL parameter (SockJS handshake request cannot pass custom header in request header)
  const wsUrl = `/game-service/ws?access_token=${encodeURIComponent(token)}`
  
  // 3. Establish SockJS connection
  socket = new SockJS(wsUrl)
  
  // 4. Use STOMP protocol
  stomp = Stomp.over(socket)
  
  // 5. Pass token in STOMP CONNECT frame (as Authorization header)
  const headers = { Authorization: 'Bearer ' + token }
  
  // 6. Establish connection
  stomp.connect(headers, (frame) => {
    callbacks.onConnect?.()
  }, (error) => {
    // If 401, trigger authentication expiration handling
    if (isUnauthorizedWebSocketError(error)) {
      performSessionLogout('WebSocket 会话已失效，请重新登录')
      return
    }
    callbacks.onError?.(error)
  })
}
```

**Key Points**:
- ✅ **Pass Token via URL Parameter**: SockJS handshake request cannot pass custom header in request header, so pass via URL parameter
- ✅ **Pass Token in STOMP CONNECT Frame**: Pass Authorization in STOMP CONNECT frame header, backend validates through `WebSocketAuthChannelInterceptor`
- ✅ **401 Error Handling**: If connection rejected (401), automatically triggers logout flow

---

### 7.5.7 Frontend-Backend Interaction API Summary

#### 7.5.7.1 Authentication APIs Provided by Gateway

| Method | Path | Description | Request/Response |
|------|------|------|----------|
| GET | `/oauth2/authorization/keycloak` | Trigger OAuth2 login flow | Redirects to Keycloak login page |
| GET | `/token` | Get access_token for currently logged-in user | Request: with cookie (JSESSIONID)<br>Response: `{ access_token: string, refresh_token?: string }` |
| POST | `/logout` | Logout interface | Request: with cookie (JSESSIONID)<br>Response: Redirects to homepage |

#### 7.5.7.2 Validation APIs Provided by Application Services

| Method | Path | Description | Request/Response |
|------|------|------|----------|
| GET | `/game-service/me` | Get current user information (extracted from JWT) | Request: `Authorization: Bearer {token}`<br>Response: `{ code, message, data: { sub, username, nickname, email, ... } }` |
| GET | `/system-service/api/users/me` | Get detailed user information | Request: `Authorization: Bearer {token}`<br>Response: `{ code, message, data: { id, username, nickname, email, ... } }` |

#### 7.5.7.3 WebSocket Connection

| Connection Address | Description | Authentication Method |
|---------|------|---------|
| `/game-service/ws?access_token={token}` | WebSocket connection address | Pass token via URL parameter, pass Authorization in STOMP CONNECT frame header |

---

### 7.5.8 Frontend Authentication Data Flow

#### 7.5.8.1 Application Startup Flow

```
Application starts (main.jsx)
  ↓
AuthProvider initializes
  ↓
ensureAuthenticated(!isPublicRoute)
  ↓
1. Prioritize getting token from Gateway
   GET /token (credentials: 'include')
   ↓
   Gateway locates Session through cookie, returns token
   ↓
   Save token to localStorage
  ↓
2. Validate token validity
   GET /game-service/me (Authorization: Bearer {token})
   ↓
   If returns 200, token valid
   If returns 401, token invalid, clear local token
  ↓
3. If token invalid and autoLogin=true
   Redirect to /oauth2/authorization/keycloak
  ↓
4. After successful login, Gateway redirects back to frontend
  ↓
5. Frontend calls ensureAuthenticated() again
  ↓
6. Get token, set authentication state
  ↓
7. Load user information
   getUserInfo() → GET /system-service/api/users/me
  ↓
8. Render application
```

#### 7.5.8.2 HTTP Request Flow

```
Frontend calls API
  ↓
authenticatedFetch(url, options)
  ↓
1. ensureAuthenticated() → Get token
   - Prioritize getting from Gateway (auto refresh)
   - Then read from localStorage
   - If invalid, trigger login
  ↓
2. Add Authorization to request headers
   Authorization: Bearer {token}
  ↓
3. Send request
  ↓
4. If returns 401
   handleAuthExpiredResponse()
   ↓
   Display authentication expired popup
   ↓
   User clicks "Log In Again"
   ↓
   Redirect to /oauth2/authorization/keycloak
```

#### 7.5.8.3 WebSocket Connection Flow

```
Frontend establishes WebSocket connection
  ↓
connectWebSocket(callbacks)
  ↓
1. ensureAuthenticated() → Get token
  ↓
2. Build WebSocket URL
   /game-service/ws?access_token={token}
  ↓
3. Establish SockJS connection
  ↓
4. Use STOMP protocol
  ↓
5. Send STOMP CONNECT frame
   headers: { Authorization: 'Bearer {token}' }
  ↓
6. Backend validates token (WebSocketAuthChannelInterceptor)
  ↓
7. If validation succeeds, connection established
   If validation fails (401), connection rejected
  ↓
8. If connection rejected
   performSessionLogout('WebSocket 会话已失效')
   ↓
   Display authentication expired popup
```

---

### 7.5.9 Frontend Authentication State Management

#### 7.5.9.1 Token Storage

**Storage Location**: `localStorage`

**Key Name**: `access_token`

**Storage Timing**:
- After successfully getting token from Gateway
- After successful login

**Clear Timing**:
- On logout
- When token validation fails
- On authentication expiration

#### 7.5.9.2 Authentication State

**Global State** (AuthContext):
- `isAuthenticated`: Whether authenticated
- `isLoading`: Whether loading authentication state
- `user`: Current user information

**State Update Timing**:
- On application startup: Initialize authentication state
- After successful login: Set `isAuthenticated = true`, load user information
- On logout: Set `isAuthenticated = false`, clear user information
- When token validation fails: Set `isAuthenticated = false`

---

### 7.5.10 Frontend Authentication Key Code

#### 7.5.10.1 authService.js Core Methods

**getTokenFromGateway**:
```javascript
export async function getTokenFromGateway() {
  const res = await fetch(GATEWAY_TOKEN_URL, {
    credentials: 'include',  // Important: must include cookie
  })
  
  if (!res.ok) {
    if (res.status === 401) {
      handleAuthExpiredResponse(res, 'GET /token 返回 401')
      return null
    }
    throw new Error(`获取 token 失败 (HTTP ${res.status})`)
  }
  
  const data = await res.json()
  const token = data?.access_token
  if (!token) {
    throw new Error('Gateway 返回的 token 为空')
  }
  
  saveToken(token)  // Save to localStorage
  return token
}
```

**ensureAuthenticated**:
```javascript
export async function ensureAuthenticated(autoLogin = true) {
  // In autoLogin mode, prioritize getting latest token from gateway (can auto refresh)
  if (autoLogin) {
    let token = await getTokenFromGateway()
    if (token) {
      const isValid = await validateToken(token)
      if (isValid) {
        return token
      }
      clearToken()
    }
  }
  
  // Then try using locally saved token
  let localToken = getToken()
  if (localToken) {
    const isLocalValid = await validateToken(localToken)
    if (isLocalValid) {
      return localToken
    }
    clearToken()
  }
  
  // Non-autoLogin mode doesn't trigger redirect to login
  if (!autoLogin) {
    return null
  }
  
  // Finally trigger login flow
  const token = await initAndLogin()
  return token
}
```

#### 7.5.10.2 AuthContext.jsx Initialization

```javascript
export function AuthProvider({ children }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [user, setUser] = useState(null)
  const location = useLocation()
  const isPublicRoute = PUBLIC_ROUTES.some((path) => location.pathname.startsWith(path))
  
  useEffect(() => {
    async function bootstrap() {
      try {
        // Ensure authenticated (non-public routes trigger login)
        const token = await ensureAuthenticated(!isPublicRoute)
        
        if (token) {
          setIsAuthenticated(true)
          await loadUserProfile()  // Load user information
        } else {
          setIsAuthenticated(false)
        }
      } catch (error) {
        console.warn('初始化认证状态失败', error)
        setIsAuthenticated(false)
      } finally {
        setIsLoading(false)
      }
    }
    
    bootstrap()
  }, [isPublicRoute, loadUserProfile])
  
  // ...
}
```

#### 7.5.10.3 gomokuSocket.js WebSocket Connection

```javascript
export async function connectWebSocket(callbacks = {}) {
  // Ensure authenticated
  const token = await ensureAuthenticated()
  if (!token) {
    throw new Error('未登录')
  }
  
  // Pass token via URL parameter (SockJS handshake request cannot pass custom header in request header)
  const wsUrl = `/game-service/ws?access_token=${encodeURIComponent(token)}`
  socket = new SockJS(wsUrl)
  stomp = Stomp.over(socket)
  
  // Pass token in STOMP CONNECT frame
  const headers = { Authorization: 'Bearer ' + token }
  
  stomp.connect(headers, (frame) => {
    callbacks.onConnect?.()
  }, (error) => {
    // If 401, trigger authentication expiration handling
    if (isUnauthorizedWebSocketError(error)) {
      performSessionLogout('WebSocket 会话已失效，请重新登录')
      return
    }
    callbacks.onError?.(error)
  })
}
```

---

### 7.5.11 Frontend Authentication and Backend Flow Integration

#### 7.5.11.1 Login Flow Integration

**Frontend**:
1. User accesses page requiring authentication
2. `AuthProvider` initializes, calls `ensureAuthenticated(true)`
3. If not logged in, redirects to `/oauth2/authorization/keycloak`
4. Gateway redirects to Keycloak login page
5. User logs in on Keycloak
6. Keycloak callbacks Gateway, Gateway processes login (single device login, blacklist, event publishing)
7. Gateway redirects back to frontend
8. Frontend calls `ensureAuthenticated()` again, gets token
9. Saves token, sets authentication state, loads user information

**Backend** (Gateway):
- Processes OAuth2 authorization code flow
- Calls `LoginSessionKickHandler` to implement single device login
- Stores `loginSessionId` to HTTP Session
- Redirects back to frontend

#### 7.5.11.2 Token Retrieval Flow Integration

**Frontend**:
1. Calls `GET /token` (with cookie)
2. Gateway locates Session through cookie
3. Gateway gets token from Session
4. Gateway validates token (multi-layer validation: Session, SessionRegistry, state)
5. Gateway returns token
6. Frontend saves token to localStorage

**Backend** (Gateway - TokenController):
- Gets `OAuth2AuthorizedClient` from Session
- Verifies `loginSessionId` matches Session
- Verifies session state is ACTIVE
- Verifies token's jti matches session's sessionId
- Returns token

#### 7.5.11.3 Logout Flow Integration

**Frontend**:
1. User clicks logout button
2. Calls `logoutFromGateway()`
3. Sends `POST /logout` (with cookie)
4. Gateway executes logout processing (blacklist, event publishing, clean SessionRegistry)
5. Gateway calls Keycloak OIDC logout interface
6. Gateway redirects to homepage
7. Frontend clears local token

**Backend** (Gateway - SecurityConfig):
- `jwtBlacklistLogoutHandler`: Adds token to blacklist, publishes event, cleans SessionRegistry
- `OidcClientInitiatedServerLogoutSuccessHandler`: Calls Keycloak logout interface

#### 7.5.11.4 WebSocket Connection Flow Integration

**Frontend**:
1. Establishes WebSocket connection: `/game-service/ws?access_token={token}`
2. Sends STOMP CONNECT frame, passes `Authorization: Bearer {token}` in header
3. Backend validates token, establishes connection
4. If validation fails (401), connection rejected, frontend triggers logout

**Backend** (game-service):
- `WebSocketAuthChannelInterceptor`: Validates token in STOMP CONNECT frame
- `WebSocketSessionManager`: Registers WebSocket session, implements single device login (kick old connections)

---

### 7.5.12 Frontend Authentication Error Handling

#### 7.5.12.1 Authentication Expiration Handling

**Scenarios**:
- HTTP request returns 401
- WebSocket connection rejected (401)
- Token validation failed

**Handling**:
1. Display authentication expired popup (`showAuthModal()`)
2. Provide "Log In Again" button
3. Clear local token
4. User clicks "Log In Again", redirects to `/oauth2/authorization/keycloak`

**Popup Implementation**:
```javascript
export function showAuthModal(message) {
  // Dynamically create popup DOM
  const modal = document.createElement('div')
  modal.id = AUTH_MODAL_ID
  modal.innerHTML = `
    <div class="auth-modal-backdrop">
      <div class="auth-modal-card">
        <h3>登录状态失效</h3>
        <p>${message || '很抱歉，您的登录已过期或被其他设备挤下线，请重新登录后继续。'}</p>
        <div class="auth-modal-actions">
          <button id="auth-modal-retry" class="auth-modal-btn primary">重新登录</button>
          <button id="auth-modal-cancel" class="auth-modal-btn secondary">稍后</button>
        </div>
      </div>
    </div>
  `
  document.body.appendChild(modal)
  
  // Bind events
  document.getElementById('auth-modal-retry').addEventListener('click', () => {
    window.location.href = `${GATEWAY_LOGIN_URL}?redirect_uri=${encodeURIComponent(window.location.href)}`
  })
}
```

#### 7.5.12.2 Network Error Handling

**Scenarios**:
- Network connection failed
- Gateway service unavailable

**Handling**:
- Log error
- Trigger authentication expiration handling (`performSessionLogout()`)
- Display error prompt

---

### 7.5.13 Frontend Authentication Best Practices

#### 7.5.13.1 Token Management

**Storage**:
- ✅ Use localStorage to store token (persistent)
- ✅ Token key name: `access_token`

**Refresh**:
- ✅ Prioritize getting latest token from Gateway (`GET /token`, supports automatic refresh)
- ✅ If Gateway returns invalid token, then try using local token

**Clear**:
- ✅ Clear on logout
- ✅ Clear when token validation fails
- ✅ Clear on authentication expiration

#### 7.5.13.2 Authentication State Management

**Global State**:
- ✅ Use React Context to manage global authentication state
- ✅ Automatically initialize on application startup
- ✅ Check authentication state on route changes

**State Updates**:
- ✅ Update immediately after successful login
- ✅ Update immediately on logout
- ✅ Update immediately when token validation fails

#### 7.5.13.3 Error Handling

**Unified Handling**:
- ✅ HTTP requests uniformly use `authenticatedFetch`, automatically handles 401
- ✅ WebSocket connections uniformly handle 401 errors
- ✅ Authentication expiration uniformly displays popup

**User Prompts**:
- ✅ Display friendly prompt when authentication expires
- ✅ Provide "Log In Again" button for user convenience

---

### 7.5.14 Chapter Summary

**Frontend Authentication Core Points**:

1. **Token Retrieval**:
   - Prioritize getting from Gateway (`GET /token`, supports automatic refresh)
   - Then use locally saved token
   - If both invalid, trigger login flow

2. **Token Validation**:
   - Validate token validity by calling `/game-service/me`
   - If returns 401, token invalid, trigger login flow

3. **HTTP Requests**:
   - Use `authenticatedFetch` to encapsulate requests, automatically carries token
   - Unified handling of 401 errors

4. **WebSocket Connections**:
   - Pass token via URL parameter (SockJS handshake limitation)
   - Pass Authorization in STOMP CONNECT frame header
   - Unified handling of 401 errors

5. **Authentication State Management**:
   - Use React Context to manage global state
   - Automatically initialize on application startup
   - Use `ProtectedRoute` for route protection

6. **Error Handling**:
   - Uniformly display authentication expired popup
   - Provide "Log In Again" button

---

## VIII. WebSocket Connection Management

> **Chapter Objective**: Understand the complete WebSocket connection management implementation, master how to extract `loginSessionId` from JWT, how to implement WebSocket single device login, and how to kick out old connections.

---

### 8.1 WebSocket Connection Overview

#### 8.1.1 STOMP Protocol

**STOMP (Simple Text Oriented Messaging Protocol)**:
- Text message protocol based on WebSocket
- Spring WebSocket supports STOMP
- Provides subscribe/publish pattern

**Connection Flow (Frontend-Backend Interaction)**:
1. **Frontend Establishes Connection**: Frontend calls `connectWebSocket()`
   - Ensure authenticated, get token
   - Build WebSocket URL: `/game-service/ws?access_token={token}` (pass token via URL parameter, because SockJS handshake request cannot pass custom header in request header)
   - Establish SockJS connection
2. **Frontend Sends STOMP CONNECT Frame**: Pass `Authorization: Bearer {token}` in STOMP CONNECT frame header
3. **Backend Validates Token**: `WebSocketAuthChannelInterceptor` intercepts CONNECT frame, validates token
4. **Backend Establishes Connection**: After validation succeeds, establishes WebSocket connection
5. **Backend Registers Session**: `WebSocketSessionManager` registers WebSocket session to SessionRegistry, implements single device login (kick old connections)
6. **Client Subscribes to Destination**: Frontend subscribes to `/topic/room.{roomId}`, etc.
7. **Server Pushes Messages**: Backend pushes messages through `SimpMessagingTemplate`

#### 8.1.2 WebSocket Session Registration

**Session Management**:
- On connection establishment, register WebSocket session to `SessionRegistry`
- On connection disconnection, unregister session from `SessionRegistry`
- Supports single device login: new connection kicks out old connection

---

### 8.2 Code Call Chain (WebSocket Connection)

#### 8.2.1 Frontend-Backend Interaction Flow

**Frontend Establishes Connection**:
```javascript
// gomokuSocket.js
export async function connectWebSocket(callbacks = {}) {
  // 1. Ensure authenticated, get token
  const token = await ensureAuthenticated()
  if (!token) {
    throw new Error('未登录')
  }
  
  // 2. Pass token via URL parameter (SockJS handshake request cannot pass custom header in request header)
  const wsUrl = `/game-service/ws?access_token=${encodeURIComponent(token)}`
  socket = new SockJS(wsUrl)
  stomp = Stomp.over(socket)
  
  // 3. Pass token in STOMP CONNECT frame
  const headers = { Authorization: 'Bearer ' + token }
  
  stomp.connect(headers, (frame) => {
    callbacks.onConnect?.()
  }, (error) => {
    // If 401, trigger authentication expiration handling
    if (isUnauthorizedWebSocketError(error)) {
      performSessionLogout('WebSocket 会话已失效，请重新登录')
      return
    }
    callbacks.onError?.(error)
  })
}
```

**Backend Processing**:

#### 8.2.2 Complete Call Chain

```
Frontend establishes WebSocket connection
  ↓
ws://localhost:8080/game-service/ws?access_token=xxx
  ↓
Gateway: WebSocketTokenFilter (if Gateway proxies WebSocket)
  ↓
Extract token from URL parameter, put into Authorization header
  ↓
Spring WebSocket handshake
  ↓
STOMP CONNECT command arrives
  ↓
WebSocketAuthChannelInterceptor.preSend()
  ↓
Extract token from STOMP header, validate and set user identity to session
  ↓
Validation passes, connection established
  ↓
Trigger SessionConnectEvent
  ↓
WebSocketSessionManager.handleSessionConnect()
  ↓
  ├─ 1. Extract information from Principal (Principal obtained from WebSocket session)
  │     ├─ userId (principal.getName())
  │     ├─ sessionId (accessor.getSessionId())
  │     └─ loginSessionId (extractLoginSessionId(principal))
  │
  ├─ 2. Build WebSocketSessionInfo
  │     WebSocketSessionInfo.builder()
  │     ├─ sessionId
  │     ├─ userId
  │     ├─ loginSessionId
  │     └─ service = "game-service"
  │
  ├─ 3. Call registerWebSocketSessionEnforceSingle()
  │     SessionRegistry.registerWebSocketSessionEnforceSingle()
  │     ├─ Query all WebSocket sessions for this user
  │     ├─ Delete old sessions (return list of kicked old connections)
  │     └─ Register new session
  │
  ├─ 4. Process kicked old connections
  │     ├─ Send kick notification
  │     │     WebSocketDisconnectHelper.sendKickMessage()
  │     │     └─ Send to /queue/system.kick
  │     │
  │     └─ Force disconnect
  │           WebSocketDisconnectHelper.forceDisconnect()
  │           └─ Send DISCONNECT command
  │
  └─ 5. Connection establishment complete
```

#### 8.2.2 Connection Disconnection Flow

```
Client disconnects WebSocket connection
  ↓
Trigger SessionDisconnectEvent
  ↓
WebSocketSessionManager.handleSessionDisconnect()
  ↓
SessionRegistry.unregisterWebSocketSession(sessionId)
  ↓
Delete session record from Redis
  ↓
Connection disconnection complete
```

**Disconnection Detection Mechanism**:

The system automatically detects all types of WebSocket connection disconnections through Spring WebSocket's `SessionDisconnectEvent`, including:

1. ✅ **Normal Browser/Tab Close**: When user closes browser or tab, TCP connection disconnects, triggers `SessionDisconnectEvent`
2. ✅ **Force Close Browser**: When task manager forcefully terminates browser process, TCP connection disconnects, triggers event
3. ✅ **Network Interruption**: When network connection interrupts, TCP connection disconnects, triggers event
4. ✅ **System Crash**: When browser or system crashes, TCP connection disconnects, triggers event

**Key Points**:
- ✅ **Based on TCP Connection Detection**: More reliable than browser-side JavaScript events, doesn't depend on frontend code
- ✅ **Automatic Redis Cleanup**: On disconnection, automatically calls `unregisterWebSocketSession()`, cleans session records in Redis
- ✅ **No Frontend Cooperation Needed**: Even if frontend doesn't send disconnection message, backend can detect connection disconnection
- ✅ **Real-Time Response**: Event triggered immediately after TCP connection disconnects, very low latency

**Note**:
- This mechanism only cleans WebSocket session records, doesn't affect login session (loginSession)
- Login session lifecycle is consistent with refresh_token, won't expire because WebSocket disconnects

---

### 8.3 WebSocket Authentication Interceptor

#### 8.3.1 WebSocketAuthChannelInterceptor Overview

**Purpose**: Validates JWT token in STOMP CONNECT phase and sets user identity to WebSocket session for subsequent message processing.

**Trigger Timing**:
- Intercepts all STOMP messages entering `clientInboundChannel`
- Only processes `CONNECT` command (on connection establishment), other messages (SEND, SUBSCRIBE, etc.) pass through directly

**Workflow**:
1. Extract token from STOMP header (supports `Authorization` or `access_token`)
2. Use `JwtDecoder` to validate token (signature, expiration, etc.)
3. Extract user information from JWT
4. Set user identity to WebSocket session through `accessor.setUser()`

**File Location**: `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/WebSocketAuthChannelInterceptor.java`

#### 8.3.2 WebSocketAuthChannelInterceptor Class Structure

**Class Definition**:
```java
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder;
    private final JwtGrantedAuthoritiesConverter authoritiesConverter;
}
```

**Key Dependencies**:
- `JwtDecoder`: JWT decoder, used to validate token
- `JwtGrantedAuthoritiesConverter`: JWT information converter, extracts user information from JWT (this document doesn't involve permission control, only used to extract user identity)

#### 8.3.3 preSend Method

**Method Signature**:
```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel)
```

**Complete Implementation**:
```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    
    // Only process CONNECT command
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        // Extract token from header (supports multiple formats)
        String auth = firstHeader(accessor, "Authorization");
        if (auth == null) auth = firstHeader(accessor, "authorization");
        if (auth == null) {
            String tokenOnly = firstHeader(accessor, "access_token");
            if (tokenOnly != null && !tokenOnly.isBlank()) 
                auth = "Bearer " + tokenOnly.trim();
        }
        
        // Validate token and set user identity
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            String token = auth.substring(7).trim();
            try {
                Jwt jwt = jwtDecoder.decode(token);
                Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
                String name = Objects.requireNonNullElse(
                        jwt.getClaimAsString("preferred_username"), 
                        jwt.getSubject()
                );
                accessor.setUser(new JwtAuthenticationToken(jwt, authorities, name));
            } catch (Exception ignore) {
                // If validation fails, don't set user, subsequent operations will fail due to missing user
            }
        }
    }
    return message;
}
```

**Key Logic**:
1. ✅ **Intercept CONNECT Command**: Only processes STOMP CONNECT messages
2. ✅ **Extract Token**: Extracts token from STOMP header (supports multiple formats)
3. ✅ **Validate Token**: Uses `JwtDecoder` to validate token signature and validity
4. ✅ **Set User Identity**: Stores user identity to WebSocket session through `accessor.setUser()`

#### 8.3.4 How setUser() Works

**Purpose of `accessor.setUser()`**:
- Stores `JwtAuthenticationToken` to message header
- Spring framework automatically saves this Principal to WebSocket session storage
- Session uses `sessionId` as key to store user identity

**Session Storage Mechanism**:
```
WebSocket Session Store (Managed by Spring Framework)
├─ sessionId: "abc123"
│  └─ Principal: JwtAuthenticationToken(userId="user1", ...)
├─ sessionId: "def456"
│  └─ Principal: JwtAuthenticationToken(userId="user2", ...)
└─ ...
```

**Key Points**:
- ✅ User identity is stored in WebSocket session, not in message header
- ✅ Subsequent messages can get user identity from session, no need to pass token again
- ✅ Session is automatically cleaned on connection disconnection

#### 8.3.5 How Subsequent Messages Get User Identity

**Flow**:
```
1. Client sends STOMP SEND message (e.g., /app/gomoku.place)
   ↓
2. Message contains sessionId: "abc123"
   ↓
3. Spring framework gets user identity from WebSocket session:
   Principal = Session["abc123"].Principal
   ↓
4. Sets user identity to message header
   ↓
5. @MessageMapping method gets user identity through sha.getUser()
```

**Code Example**:
```java
@MessageMapping("/gomoku.place")
public void place(PlaceCmd cmd, SimpMessageHeaderAccessor sha) {
    // Get user identity from message header (Spring framework has set from session)
    final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();
    // Use userId for service logic processing
}
```

**Key Points**:
- ✅ Subsequent messages no longer need to carry token
- ✅ Spring framework automatically gets user identity from session
- ✅ Application service logic gets user identity through `sha.getUser()`

#### 8.3.6 ChannelInterceptor Registration

**Registration Location**: `WebSocketStompConfig.java`

**Registration Code**:
```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
}
```

**Purpose**:
- Registers `WebSocketAuthChannelInterceptor` to `clientInboundChannel` (client inbound channel)
- All STOMP messages entering this channel will be intercepted

---

### 8.4 Key Code Analysis

#### 8.4.1 WebSocketSessionManager Class Structure

**File Location**: `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/WebSocketSessionManager.java`

**Class Definition**:
```java
@Slf4j
@Component
public class WebSocketSessionManager {
    private final SessionRegistry sessionRegistry;
    private final WebSocketDisconnectHelper disconnectHelper;
}
```

**Key Dependencies**:
- `SessionRegistry`: Session registry, manages WebSocket sessions
- `WebSocketDisconnectHelper`: WebSocket disconnection helper class

#### 8.3.2 handleSessionConnect Method

**Method Signature**:
```java
@EventListener
public void handleSessionConnect(SessionConnectEvent event)
```

**Complete Implementation**:
```java
@EventListener
public void handleSessionConnect(SessionConnectEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    Principal principal = accessor.getUser();
    if (principal == null) {
        log.warn("Received SessionConnectEvent but missing user information, session={}", accessor.getSessionId());
        return; // Ignore unauthenticated users
    }
    
    String sessionId = accessor.getSessionId();
    if (sessionId == null) {
        log.warn("SessionConnectEvent missing sessionId, user={}", principal.getName());
        return;
    }
    
    String userId = principal.getName();
    
    // Extract loginSessionId from JWT (if Principal is JwtAuthenticationToken)
    String loginSessionId = extractLoginSessionId(principal);
    
    WebSocketSessionInfo info = WebSocketSessionInfo.builder()
            .sessionId(sessionId)
            .userId(userId)
            .loginSessionId(loginSessionId) // May be null (backward compatibility)
            .service("game-service")
            .build();
    
    List<WebSocketSessionInfo> kicked = sessionRegistry.registerWebSocketSessionEnforceSingle(info, 0);
    if (!CollectionUtils.isEmpty(kicked)) {
        log.info("User {} WebSocket single device login, new connection {} kicks out {} old connections, loginSessionId={}", 
                userId, sessionId, kicked.size(), loginSessionId);
        kicked.forEach(old -> {
            disconnectHelper.sendKickMessage(userId, old.getSessionId(), "账号已在其他终端登录");
            disconnectHelper.forceDisconnect(old.getSessionId());
        });
    } else {
        log.debug("User {} WebSocket connection {} registered, no old connections, loginSessionId={}", 
                userId, sessionId, loginSessionId);
    }
}
```

**Key Logic**:
1. ✅ **Extract User Information**: Extract `userId` and `sessionId` from `Principal`
   - **Note**: `Principal` is set by `WebSocketAuthChannelInterceptor` during STOMP CONNECT
   - Spring framework gets `Principal` from WebSocket session and sets it to message header
2. ✅ **Extract loginSessionId**: Extract from JWT (if Principal is `JwtAuthenticationToken`)
3. ✅ **Build Session Information**: Create `WebSocketSessionInfo` object
4. ✅ **Single Device Login Registration**: Call `registerWebSocketSessionEnforceSingle()`, returns kicked old connections
5. ✅ **Process Old Connections**: Send kick notification and force disconnect

#### 8.4.2 extractLoginSessionId Method

**Method Signature**:
```java
private String extractLoginSessionId(Principal principal)
```

**Complete Implementation**:
```java
private String extractLoginSessionId(Principal principal) {
    if (principal instanceof JwtAuthenticationToken jwtAuth) {
        Jwt jwt = jwtAuth.getToken();
        // Prefer using sid
        Object sidObj = jwt.getClaim("sid");
        if (sidObj != null) {
            String sid = sidObj.toString();
            if (sid != null && !sid.isBlank()) {
                return sid;
            }
        }
        // If no sid, try using session_state (backward compatibility)
        Object sessionStateObj = jwt.getClaim("session_state");
        if (sessionStateObj != null) {
            String sessionState = sessionStateObj.toString();
            if (sessionState != null && !sessionState.isBlank()) {
                return sessionState;
            }
        }
    }
    return null;
}
```

**Key Points**:
- ✅ **Type Check**: Check if Principal is `JwtAuthenticationToken`
- ✅ **Prefer Using sid**: Extract `sid` from JWT claim
- ✅ **Backward Compatibility**: If no `sid`, try using `session_state`
- ✅ **Return null**: If cannot extract, return null (backward compatibility)

#### 8.4.3 handleSessionDisconnect Method

**Method Signature**:
```java
@EventListener
public void handleSessionDisconnect(SessionDisconnectEvent event)
```

**Complete Implementation**:
```java
/**
 * Clean up session on connection disconnection.
 * 
 * Important: This method detects all types of disconnections, including:
 * - Normal browser/tab close
 * - Force close browser
 * - Network interruption
 * - System crash
 * 
 * This is based on TCP connection disconnection detection, more reliable than browser-side events.
 */
@EventListener
public void handleSessionDisconnect(SessionDisconnectEvent event) {
    String sessionId = event.getSessionId();
    if (sessionId == null) {
        log.warn("【WebSocket Disconnection Detection】Received SessionDisconnectEvent but missing sessionId");
        return;
    }
    
    // 1. Query session information from SessionRegistry (includes userId)
    WebSocketSessionInfo sessionInfo = sessionRegistry.getWebSocketSession(sessionId);
    String userId = null;
    String loginSessionId = null;
    
    if (sessionInfo != null) {
        userId = sessionInfo.getUserId();
        loginSessionId = sessionInfo.getLoginSessionId();
        log.info("【WebSocket Disconnection Detection】Detected disconnection: sessionId={}, userId={}, loginSessionId={}, service={}, connectedAt={}", 
                sessionId, userId, loginSessionId, sessionInfo.getService(), 
                sessionInfo.getConnectedAt() != null ? 
                    java.time.Instant.ofEpochMilli(sessionInfo.getConnectedAt()) : null);
    } else {
        // If cannot get from SessionRegistry, try extracting from event
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal != null) {
            userId = principal.getName();
            loginSessionId = extractLoginSessionId(principal);
            log.info("【WebSocket Disconnection Detection】Detected disconnection (extracted from event): sessionId={}, userId={}, loginSessionId={}", 
                    sessionId, userId, loginSessionId);
        } else {
            log.warn("【WebSocket Disconnection Detection】Detected disconnection but cannot get user information: sessionId={}", sessionId);
        }
    }
    
    // 2. Record disconnection information (for testing and debugging)
    if (userId != null) {
        log.info("【WebSocket Disconnection Detection】Player disconnection details: userId={}, sessionId={}, loginSessionId={}, disconnection time={}", 
                userId, sessionId, loginSessionId, java.time.Instant.now());
    }
    
    // 3. Clean up WebSocket session registration
    sessionRegistry.unregisterWebSocketSession(sessionId);
    log.debug("【WebSocket Disconnection Detection】Session registration cleaned: sessionId={}", sessionId);
}
```

**Key Points**:
- ✅ **Unregister Session**: Unregister session from `SessionRegistry`
- ✅ **Clean Redis**: Delete session records from Redis
- ✅ **Automatic Detection**: Based on TCP connection disconnection detection, doesn't depend on frontend code
- ✅ **Supports Multiple Disconnection Scenarios**: Normal close, force close, network interruption, system crash, etc.
- ✅ **Detailed Logging**: Records disconnection information for debugging and troubleshooting

**Disconnection Detection Principle**:
- Spring WebSocket detects disconnection based on underlying TCP connection state
- When TCP connection disconnects, automatically triggers `SessionDisconnectEvent`
- More reliable than browser-side `beforeunload` or `unload` events because:
  - Doesn't depend on frontend JavaScript execution
  - Can detect even when browser crashes
  - Can detect promptly on network interruption

#### 8.4.4 WebSocketDisconnectHelper Class Structure

**File Location**: `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/WebSocketDisconnectHelper.java`

**Class Definition**:
```java
@Slf4j
@Component
public class WebSocketDisconnectHelper {
    private static final String KICK_DESTINATION = "/queue/system.kick";
    
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageChannel clientInboundChannel;
}
```

**Key Dependencies**:
- `SimpMessagingTemplate`: STOMP message template, used to send messages
- `clientInboundChannel`: Client inbound message channel, used to force disconnect

#### 8.3.6 sendKickMessage Method

**Method Signature**:
```java
public void sendKickMessage(String userId, String sessionId, String reason)
```

**Complete Implementation**:
```java
public void sendKickMessage(String userId, String sessionId, String reason) {
    try {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        
        messagingTemplate.convertAndSendToUser(
                userId,
                KICK_DESTINATION,
                Map.of("type", "WS_KICK", "reason", reason),
                headerAccessor.getMessageHeaders()
        );
    } catch (Exception e) {
        log.warn("Failed to send kick notification: userId={}, sessionId={}", userId, sessionId, e);
    }
}
```

**Key Points**:
- ✅ **Send to User Queue**: Uses `convertAndSendToUser()` to send to `/user/{userId}/queue/system.kick`
- ✅ **Message Format**: `{"type": "WS_KICK", "reason": "..."}`
- ✅ **Error Handling**: Logs warning, doesn't throw exception

#### 8.3.7 forceDisconnect Method

**Method Signature**:
```java
public void forceDisconnect(String sessionId)
```

**Complete Implementation**:
```java
public void forceDisconnect(String sessionId) {
    try {
        StompHeaderAccessor header = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        header.setSessionId(sessionId);
        header.setLeaveMutable(true);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], header.getMessageHeaders()));
    } catch (Exception e) {
        log.warn("Failed to force disconnect: sessionId={}", sessionId, e);
    }
}
```

**Key Points**:
- ✅ **Create DISCONNECT Command**: Uses `StompCommand.DISCONNECT`
- ✅ **Set sessionId**: Specifies session ID to disconnect
- ✅ **Send to Inbound Channel**: Sends through `clientInboundChannel`, triggers Spring WebSocket disconnection

---

### 8.4 WebSocket Single Device Login

#### 8.4.1 Handling Multiple Connections for Same User

**Problem**:
- User may establish WebSocket connections on multiple devices
- Business requirement: Same user can only have one WebSocket connection at a time

**Solution**:
- **Single Device Login**: When new connection is established, kick out old connection
- **Implementation**: `registerWebSocketSessionEnforceSingle()` method

#### 8.4.2 New Connection Kicks Out Old Connection

**Flow**:
```
User A establishes WebSocket connection on Device 1
  ↓
registerWebSocketSessionEnforceSingle()
  ├─ Query all WebSocket sessions for userId="user-A" → []
  ├─ No old connections, don't kick
  └─ Register new session
  ↓
Device 1 connection successful

User A establishes WebSocket connection on Device 2
  ↓
registerWebSocketSessionEnforceSingle()
  ├─ Query all WebSocket sessions for userId="user-A" → [Device 1's session]
  ├─ Delete Device 1's session (return list of kicked sessions)
  ├─ Register new session (Device 2)
  ├─ Send kick notification to Device 1
  └─ Force disconnect Device 1's connection
  ↓
Device 2 connection successful, Device 1 kicked offline
```

**Key Code**:
```java
List<WebSocketSessionInfo> kicked = sessionRegistry.registerWebSocketSessionEnforceSingle(info, 0);
if (!CollectionUtils.isEmpty(kicked)) {
    kicked.forEach(old -> {
        disconnectHelper.sendKickMessage(userId, old.getSessionId(), "账号已在其他终端登录");
        disconnectHelper.forceDisconnect(old.getSessionId());
    });
}
```

---

### 8.5 Chapter Summary

**Core Functions**:
1. Register session when WebSocket connection is established
2. Extract `loginSessionId` from JWT
3. WebSocket single device login (new connection kicks out old connection)
4. Unregister session when connection disconnects

**Key Code**:
- `WebSocketSessionManager.handleSessionConnect()`: Connection establishment handling
- `extractLoginSessionId()`: Extract `loginSessionId` from JWT
- `WebSocketDisconnectHelper.sendKickMessage()`: Send kick notification
- `WebSocketDisconnectHelper.forceDisconnect()`: Force disconnect

**Single Device Login Mechanism**:
- ✅ When new connection is established, query all WebSocket sessions for this user
- ✅ Delete old sessions, return list of kicked sessions
- ✅ Send kick notification and force disconnect old connections



---

## IX. Kafka Event Notification Mechanism

> **Chapter Objective**: Understand the complete Kafka event notification mechanism implementation, master each step of event publishing, consumption, and processing, and how to precisely disconnect WebSocket connections based on `loginSessionId`.

---

### 9.1 Event Notification Overview

#### 9.1.1 Why Event Notification Is Needed

**Problem**:
- Gateway service handles login/logout
- xxx-Service services manage WebSocket connections
- Need cross-service notification: disconnect WebSocket connections on login/logout

**Solution**:
- **Kafka Event Notification**: Gateway publishes events, xxx-Service consumes events
- **Decoupling**: Gateway doesn't need to directly call xxx-Service
- **Scalability**: Multiple services can subscribe to the same event

#### 9.1.2 Kafka as Message Middleware

**Advantages**:
- ✅ **Decoupling**: Publishers and subscribers are decoupled
- ✅ **Reliability**: Message persistence, supports retry
- ✅ **Scalability**: Multiple services can subscribe to the same event
- ✅ **Ordering**: Events for the same user are processed in order

---

### 9.2 Event Publishing Flow

#### 9.2.1 Complete Publishing Flow

```
Login/Logout triggers event
  ↓
SessionEventPublisher.publishSessionInvalidated(event)
  ↓
  ├─ 1. Serialize event to JSON
  │     JSON.toJSONString(event)
  │
  ├─ 2. Send to Kafka topic
  │     kafkaTemplate.send(topic, userId, message)
  │     ├─ topic: session-invalidated
  │     ├─ key: userId (for partitioning)
  │     └─ value: JSON string
  │
  ├─ 3. Asynchronously process result
  │     future.whenComplete()
  │     ├─ Success → Log
  │     └─ Failure → Log error
  │
  └─ 4. Event publishing complete
```

#### 9.2.2 Event Publishing Scenarios

**Scenario 1: Kick Old Session on Login**
```java
// LoginSessionKickHandler.publishKickedEvent()
for (LoginSessionInfo kickedSession : kickedSessions) {
    String kickedLoginSessionId = kickedSession.getLoginSessionId();
    if (kickedLoginSessionId != null && !kickedLoginSessionId.isBlank()) {
        SessionInvalidatedEvent event = SessionInvalidatedEvent.of(
                userId,
                kickedLoginSessionId,
                SessionInvalidatedEvent.EventType.FORCE_LOGOUT,
                "单设备登录：被新登录踢下线"
        );
        sessionEventPublisher.publishSessionInvalidated(event);
    }
}
```

**Scenario 2: Invalidate Session on Logout**
```java
// SecurityConfig.publishSessionInvalidatedEvent()
SessionInvalidatedEvent event = SessionInvalidatedEvent.of(
        userId,
        loginSessionId,
        SessionInvalidatedEvent.EventType.LOGOUT,
        "用户主动登出"
);
sessionEventPublisher.publishSessionInvalidated(event);
```

---

### 9.3 Event Consumption Flow

#### 9.3.1 Complete Consumption Flow

```
Kafka message arrives
  ↓
SessionEventConsumer.consumeSessionInvalidated(message, ack)
  ↓
  ├─ 1. Parse JSON to SessionInvalidatedEvent
  │     JSON.parseObject(message, SessionInvalidatedEvent.class)
  │
  ├─ 2. Iterate all SessionEventListener
  │     for (SessionEventListener listener : listeners)
  │
  ├─ 3. Call listener.onSessionInvalidated(event)
  │     ├─ SessionInvalidatedListener.onSessionInvalidated()
  │     ├─ Other service listeners (if any)
  │     └─ Handle exceptions, log errors
  │
  ├─ 4. Check if all listeners succeeded
  │     ├─ All succeeded → Commit offset (ack.acknowledge())
  │     └─ Some failed → Don't commit offset (message will be re-consumed)
  │
  └─ 5. Event consumption complete
```

#### 9.3.2 Listener Registration Mechanism

**Spring Auto Injection**:
```java
@Autowired(required = false)
public SessionEventConsumer(List<SessionEventListener> listeners) {
    this.listeners = listeners != null ? listeners : List.of();
    log.info("会话事件消费者初始化完成，发现 {} 个监听器", this.listeners.size());
}
```

**Principle**:
- Spring automatically collects all Beans implementing `SessionEventListener` interface
- Injects into `SessionEventConsumer`'s `listeners` list
- After receiving Kafka message, iterates all listeners and calls them

**Examples**:
- `game-service`'s `SessionInvalidatedListener`: Disconnects WebSocket connections
- Other application service listeners: Their own service logic (e.g., clean sessions, disconnect connections, etc.)

---

### 9.4 Key Code Analysis

#### 9.4.1 SessionEventPublisher Class Structure

**File Location**: `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/publisher/SessionEventPublisher.java`

**Class Definition**:
```java
@Slf4j
@Component
@ConditionalOnBean(name = "sessionKafkaTemplate")
public class SessionEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    @Value("${session.kafka.topic:session-invalidated}")
    private String topic;
}
```

**Key Dependencies**:
- `KafkaTemplate`: Kafka message template
- `topic`: Kafka topic name (default: `session-invalidated`)

#### 9.4.2 publishSessionInvalidated Method

**Method Signature**:
```java
public void publishSessionInvalidated(SessionInvalidatedEvent event)
```

**Complete Implementation**:
```java
public void publishSessionInvalidated(SessionInvalidatedEvent event) {
    try {
        // 1. Serialize event to JSON
        String message = JSON.toJSONString(event);
        
        // 2. Send to Kafka topic (use userId as key for partitioning)
        CompletableFuture<SendResult<String, String>> future = 
                kafkaTemplate.send(topic, event.getUserId(), message);
        
        // 3. Asynchronously process result
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.debug("Session invalidation event published successfully: userId={}, offset={}", 
                        event.getUserId(), result.getRecordMetadata().offset());
            } else {
                log.error("Session invalidation event publishing failed: userId={}", event.getUserId(), ex);
            }
        });
    } catch (Exception e) {
        log.error("Exception publishing session invalidation event: userId={}", event.getUserId(), e);
    }
}
```

**Key Points**:
- ✅ **Use userId as key**: Ensures events for same user are sent to same partition, guaranteeing ordering
- ✅ **Asynchronous sending**: Uses `CompletableFuture` to asynchronously process results
- ✅ **Error Handling**: Logs errors, doesn't throw exceptions (doesn't block main flow)

#### 9.4.3 SessionEventConsumer Class Structure

**File Location**: `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/listener/SessionEventConsumer.java`

**Class Definition**:
```java
@Slf4j
@Component
public class SessionEventConsumer {
    private final List<SessionEventListener> listeners;
    
    @KafkaListener(topics = "${session.kafka.topic:session-invalidated}", 
                   containerFactory = "sessionKafkaListenerContainerFactory")
    public void consumeSessionInvalidated(String message, Acknowledgment ack) {
        // ...
    }
}
```

**Key Dependencies**:
- `List<SessionEventListener>`: List of all registered listeners (Spring auto-injected)

#### 9.4.4 consumeSessionInvalidated Method

**Method Signature**:
```java
@KafkaListener(topics = "${session.kafka.topic:session-invalidated}", 
               containerFactory = "sessionKafkaListenerContainerFactory")
public void consumeSessionInvalidated(String message, Acknowledgment ack)
```

**Complete Implementation**:
```java
public void consumeSessionInvalidated(String message, Acknowledgment ack) {
    try {
        // 1. Parse event
        SessionInvalidatedEvent event = JSON.parseObject(message, SessionInvalidatedEvent.class);
        log.debug("Received session invalidation event: userId={}, eventType={}", event.getUserId(), event.getEventType());
        
        // 2. If no listeners, log warning but commit offset
        if (listeners.isEmpty()) {
            log.warn("Received session invalidation event, but no SessionEventListener implementations found: userId={}", event.getUserId());
            ack.acknowledge();
            return;
        }
        
        // 3. Call all listeners
        boolean allSuccess = true;
        for (SessionEventListener listener : listeners) {
            try {
                listener.onSessionInvalidated(event);
            } catch (Exception e) {
                log.error("Listener failed to process session invalidation event: listener={}, userId={}", 
                        listener.getClass().getName(), event.getUserId(), e);
                allSuccess = false;
            }
        }
        
        // 4. Only commit offset if all listeners succeeded
        if (allSuccess) {
            ack.acknowledge();
            log.debug("Session invalidation event processing completed and committed: userId={}", event.getUserId());
        } else {
            log.warn("Some listeners failed for session invalidation event, not committing offset: userId={}", event.getUserId());
            // Note: Don't call ack.acknowledge(), message will be re-consumed
        }
        
    } catch (Exception e) {
        log.error("Exception consuming session invalidation event: message={}", message, e);
        // If parsing fails, don't commit, let message be re-consumed
    }
}
```

**Key Logic**:
1. ✅ **Parse Event**: Parse JSON string to `SessionInvalidatedEvent` object
2. ✅ **Iterate Listeners**: Iterate all registered `SessionEventListener` and call them
3. ✅ **Error Handling**: Single listener failure doesn't affect other listeners, but logs error
4. ✅ **Manual Offset Commit**: Only commit offset if all listeners succeeded, otherwise message will be re-consumed

#### 9.4.5 SessionInvalidatedEvent Event Structure

**File Location**: `libs/session-common/src/main/java/com/gamehub/session/event/SessionInvalidatedEvent.java`

**Class Definition**:
```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionInvalidatedEvent {
    private String userId;
    private String loginSessionId; // Optional field
    private EventType eventType;
    private Long timestamp;
    private String reason; // Optional field
    
    public enum EventType {
        LOGOUT,
        PASSWORD_CHANGED,
        USER_DISABLED,
        FORCE_LOGOUT,
        OTHER
    }
}
```

**Factory Methods**:
```java
// Without loginSessionId (backward compatibility)
public static SessionInvalidatedEvent of(String userId, EventType eventType, String reason)

// With loginSessionId (core functionality)
public static SessionInvalidatedEvent of(String userId, String loginSessionId, EventType eventType, String reason)
```

**Key Fields**:
- ✅ **userId**: User ID (required)
- ✅ **loginSessionId**: Login session ID (optional, for precise query)
- ✅ **eventType**: Event type (required)
- ✅ **timestamp**: Event trigger time (automatically set)
- ✅ **reason**: Trigger reason (optional)

> ✅ Event model is extracted to `session-common`, Kafka module only responsible for publishing/consuming; all services reference the same domain event, avoiding serialization incompatibility caused by multiple definitions.

#### 9.4.6 SessionInvalidatedListener Implementation

**File Location**: `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/SessionInvalidatedListener.java`

**Class Definition**:
```java
@Slf4j
@Component
public class SessionInvalidatedListener implements SessionEventListener {
    private final SessionRegistry sessionRegistry;
    private final WebSocketDisconnectHelper disconnectHelper;
}
```

**Key Dependencies**:
- `SessionRegistry`: Query WebSocket sessions
- `WebSocketDisconnectHelper`: Disconnect WebSocket connections

#### 9.4.7 onSessionInvalidated Method

**Method Signature**:
```java
@Override
public void onSessionInvalidated(SessionInvalidatedEvent event)
```

**Complete Implementation**:
```java
@Override
public void onSessionInvalidated(SessionInvalidatedEvent event) {
    String userId = event.getUserId();
    String loginSessionId = event.getLoginSessionId();
    log.info("Received session invalidation event, starting to disconnect user WebSocket connections: userId={}, loginSessionId={}, eventType={}, reason={}", 
            userId, loginSessionId, event.getEventType(), event.getReason());
    
    List<WebSocketSessionInfo> gameServiceSessions;
    
    // If event contains loginSessionId, query precisely based on loginSessionId
    if (loginSessionId != null && !loginSessionId.isBlank()) {
        log.debug("Querying WebSocket sessions based on loginSessionId: loginSessionId={}", loginSessionId);
        List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessionsByLoginSessionId(loginSessionId);
        // Filter for game-service sessions
        gameServiceSessions = wsSessions.stream()
                .filter(session -> "game-service".equals(session.getService()))
                .toList();
    } else {
        // If event only has userId, query based on userId (backward compatibility)
        log.debug("Querying WebSocket sessions based on userId (backward compatibility): userId={}", userId);
        List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessions(userId);
        // Filter for game-service sessions
        gameServiceSessions = wsSessions.stream()
                .filter(session -> "game-service".equals(session.getService()))
                .toList();
    }
    
    if (CollectionUtils.isEmpty(gameServiceSessions)) {
        log.debug("User {} has no WebSocket connections in game-service", userId);
        return;
    }
    
    log.info("User {} has {} WebSocket connections in game-service, starting to disconnect", userId, gameServiceSessions.size());
    
    // Generate kick reason
    String reason = getKickReason(event);
    
    // Disconnect each session
    for (WebSocketSessionInfo session : gameServiceSessions) {
        try {
            // 1. Send kick notification
            disconnectHelper.sendKickMessage(userId, session.getSessionId(), reason);
            
            // 2. Force disconnect
            disconnectHelper.forceDisconnect(session.getSessionId());
            
            // 3. Remove from session registry
            sessionRegistry.unregisterWebSocketSession(session.getSessionId());
            
            log.debug("Disconnected user {}'s WebSocket connection: sessionId={}", userId, session.getSessionId());
        } catch (Exception e) {
            log.error("Failed to disconnect user {} WebSocket connection: sessionId={}", userId, session.getSessionId(), e);
        }
    }
    
    log.info("All WebSocket connections for user {} disconnected: total {} connections", userId, gameServiceSessions.size());
}
```

**Key Logic**:
1. ✅ **Extract Event Information**: Extract `userId` and `loginSessionId` from event
2. ✅ **Precise Query**: If event contains `loginSessionId`, query based on `loginSessionId` (precise)
3. ✅ **Backward Compatibility**: If event only has `userId`, query based on `userId` (backward compatibility)
4. ✅ **Filter Service**: Only process `game-service` sessions
5. ✅ **Disconnect**: Send kick notification and force disconnect
6. ✅ **Clean Registry**: Remove session from `SessionRegistry`

#### 9.4.8 getKickReason Method

**Method Signature**:
```java
private String getKickReason(SessionInvalidatedEvent event)
```

**Complete Implementation**:
```java
private String getKickReason(SessionInvalidatedEvent event) {
    if (event.getReason() != null && !event.getReason().isEmpty()) {
        return event.getReason();
    }
    
    return switch (event.getEventType()) {
        case LOGOUT -> "用户已登出";
        case PASSWORD_CHANGED -> "密码已修改，请重新登录";
        case USER_DISABLED -> "账号已被禁用";
        case FORCE_LOGOUT -> "管理员强制下线";
        case OTHER -> "会话已失效";
    };
}
```

**Key Points**:
- ✅ **Prefer Using Event Reason**: If event contains `reason`, use it directly
- ✅ **Generate Based on Event Type**: If no `reason`, generate default reason based on `eventType`

#### 9.4.9 Keycloak Webhook Notification (Registration Synchronization)

Kafka events solve "how to notify services after a loginSession is invalidated". In addition, we also need to know Keycloak's own user events (registration, disable, etc.). Currently we use the official webhook-http plugin:

1. **Keycloak Side Configuration**  
   - In `docker-compose.yml`, set `WEBHOOK_HTTP_BASE_PATH=http://system-service:8080/internal/keycloak/events` for Keycloak;  
   - In Realm's `Events → Event Listeners`, check `webhook-http` to push REGISTER/LOGIN/LOGOUT events to system-service.
2. **system-service Listener Endpoint**  
   - `apps/system-service/src/main/java/com/gamehub/systemservice/controller/internal/KeycloakEventController.java` exposes `/internal/keycloak/events`, validates signature and records Keycloak events;  
   - Currently focuses on `REGISTER`, when receiving new user registration event, calls `KeycloakAdminClient` to pull profile, synchronizes nickname and other information to platform database.
3. **Coordination with Session Events**  
   - Webhook channel emphasizes "user state changes", Kafka channel emphasizes "session state changes";  
   - In the future, if listening to PASSWORD_UPDATE / USER_DISABLED, just publish `SessionInvalidatedEvent` or call `KeycloakSsoLogoutService` inside the controller to achieve "Keycloak management operations → business session invalidation" closed loop.

---

### 9.5 Precise Query Based on loginSessionId

#### 9.5.1 Why Precise Query Is Needed

**Problem**:
- If only querying based on `userId`, will disconnect all WebSocket connections for this user
- But actually only need to disconnect connections for specific `loginSessionId`

**Example**:
```
User A logs in on Device 1 (loginSessionId="sid-001")
  ├─ Establishes WebSocket connection 1

User A logs in on Device 2 (loginSessionId="sid-002")
  ├─ Establishes WebSocket connection 2
  ├─ Device 1's login session marked as KICKED
  └─ Publish event (loginSessionId="sid-001")

If only querying based on userId:
  → Will disconnect connection 1 and connection 2 (wrong!)

If querying based on loginSessionId:
  → Only disconnect connection 1 (correct!)
```

#### 9.5.2 Precise Query Implementation

**Code Location**: `SessionInvalidatedListener.onSessionInvalidated()`

**Implementation Logic**:
```java
if (loginSessionId != null && !loginSessionId.isBlank()) {
    // Query precisely based on loginSessionId
    List<WebSocketSessionInfo> wsSessions = 
            sessionRegistry.getWebSocketSessionsByLoginSessionId(loginSessionId);
    gameServiceSessions = wsSessions.stream()
            .filter(session -> "game-service".equals(session.getService()))
            .toList();
} else {
    // Query based on userId (backward compatibility)
    List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessions(userId);
    gameServiceSessions = wsSessions.stream()
            .filter(session -> "game-service".equals(session.getService()))
            .toList();
}
```

**Key Points**:
- ✅ **Prefer Using loginSessionId**: If event contains `loginSessionId`, use precise query
- ✅ **Backward Compatibility**: If event only has `userId`, use `userId` query

---

### 9.6 Event Processing Flow Diagram

```
User logs out
  ↓
SecurityConfig.publishSessionInvalidatedEvent()
  ↓
SessionEventPublisher.publishSessionInvalidated()
  ↓
Kafka sends message (topic: session-invalidated, key: userId)
  ↓
SessionEventConsumer.consumeSessionInvalidated()
  ↓
Iterate all SessionEventListener
  ↓
SessionInvalidatedListener.onSessionInvalidated()
  ├─ Query WebSocket sessions based on loginSessionId
  ├─ Filter for game-service sessions
  ├─ Iterate all sessions
  │     ├─ Send kick notification
  │     ├─ Force disconnect
  │     └─ Clean SessionRegistry
  └─ All listeners succeeded → Commit offset
```

---

### 9.7 Chapter Summary

**Core Functions**:
1. Event publishing: Gateway publishes session invalidation events to Kafka
2. Event consumption: Game-Service consumes events and processes
3. Precise query: Precisely disconnect WebSocket connections based on `loginSessionId`
4. Listener mechanism: Supports multiple services subscribing to the same event

**Key Code**:
- `SessionEventPublisher.publishSessionInvalidated()`: Publish event
- `SessionEventConsumer.consumeSessionInvalidated()`: Consume event
- `SessionInvalidatedListener.onSessionInvalidated()`: Process event
- `SessionInvalidatedEvent`: Event structure

**Event Flow**:
- ✅ Gateway publishes event (contains `loginSessionId`)
- ✅ Kafka message queue (decoupling, reliable)
- ✅ Game-Service consumes event
- ✅ Query WebSocket sessions precisely based on `loginSessionId`
- ✅ Disconnect and clean registry



---

## X. SessionRegistry Core Implementation

> **Chapter Objective**: Understand the core implementation of SessionRegistry, master Redis storage structure, dual index design, session status management, and other key mechanisms.

---

### 10.1 SessionRegistry Overview

#### 10.1.1 Unified Session Registry

**Responsibilities**:
- Record/query/clean "login sessions (JWT/Token)"
- Record/query/clean "WebSocket long connection sessions"
- Provide aggregated views and batch cleanup capabilities (for backend "force logout")

**Storage**:
- All based on Redis
- Use JSON serialization to store session information

#### 10.1.2 Core Functions

**Login Session Management**:

- Register login session
- Single device login (new connection kicks out old)
- Query session (by sessionId, loginSessionId, userId)
- Update session status
- Unregister session

**WebSocket Session Management**:
- Register WebSocket session
- Single device WebSocket (new connection kicks out old)
- Query session (by sessionId, userId, loginSessionId)
- Unregister session

---

### 10.2 Redis Storage Structure

#### 10.2.1 Login Session Storage

**Key Space Design**:

```
Key 1: session:login:user:{userId}
Type: Set
Members: [sessionId1, sessionId2, ...]
TTL: Same as Key 2
Purpose: Quickly query all login sessions for a user

Key 2: session:login:token:{sessionId}
Type: String
Value: LoginSessionInfo JSON
TTL: Token remaining validity (or default 12 hours)
Purpose: Query session details by sessionId (jti) (backward compatibility)

Key 3: session:login:loginSession:{loginSessionId}
Type: String
Value: LoginSessionInfo JSON
TTL: Token remaining validity (or default 12 hours)
Purpose: Query session details by loginSessionId (sid) (core)
```

**Example**:
```
session:login:user:user-123 → Set["jti-abc123", "jti-def456"]

session:login:token:jti-abc123 → {
  "sessionId": "jti-abc123",
  "loginSessionId": "sid-xyz789",
  "userId": "user-123",
  "status": "ACTIVE",
  "token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "issuedAt": 1704067200000,
  "expiresAt": 1704070800000,
  "attributes": {
    "ip": "192.168.1.100",
    "userAgent": "Mozilla/5.0..."
  }
}

session:login:loginSession:sid-xyz789 → {
  "sessionId": "jti-abc123",
  "loginSessionId": "sid-xyz789",
  "userId": "user-123",
  "status": "ACTIVE",
  ...
}
```

#### 10.2.2 WebSocket Session Storage

**Key Space Design**:

```
Key 1: session:ws:user:{userId}
Type: Set
Members: [sessionId1, sessionId2, ...]
TTL: Same as Key 2
Purpose: Quickly query all WebSocket sessions for a user

Key 2: session:ws:session:{sessionId}
Type: String
Value: WebSocketSessionInfo JSON
TTL: Default 24 hours
Purpose: Query WebSocket session details by sessionId
```

**Example**:
```
session:ws:user:user-123 → Set["ws-session-001", "ws-session-002"]

session:ws:session:ws-session-001 → {
  "sessionId": "ws-session-001",
  "userId": "user-123",
  "loginSessionId": "sid-xyz789",
  "service": "game-service",
  "connectedAt": 1704067200000,
  "attributes": {}
}
```

---

### 10.3 Core Method Implementation

#### 10.3.1 registerLoginSession Method

**Method Signature**:
```java
public void registerLoginSession(LoginSessionInfo sessionInfo, long ttlSeconds)
```

**Implementation Logic**:
```java
public void registerLoginSession(LoginSessionInfo sessionInfo, long ttlSeconds) {
    // 1. Parameter validation
    Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
    requireText(sessionInfo.getSessionId(), "sessionId");
    requireText(sessionInfo.getUserId(), "userId");
    
    // 2. Set default values
    if (sessionInfo.getIssuedAt() == null) {
        sessionInfo.setIssuedAt(Instant.now().toEpochMilli());
    }
    if (sessionInfo.getAttributes() == null) {
        sessionInfo.setAttributes(new HashMap<>());
    }
    if (sessionInfo.getStatus() == null) {
        sessionInfo.setStatus(SessionStatus.ACTIVE);
    }
    
    // 3. Calculate TTL
    Duration ttl = resolveTtl(ttlSeconds, DEFAULT_LOGIN_TTL);
    
    // 4. Build Keys
    String userKey = LOGIN_USER_KEY_PREFIX + sessionInfo.getUserId();
    String sessionKey = LOGIN_SESSION_KEY_PREFIX + sessionInfo.getSessionId();
    String sessionJson = JSON.toJSONString(sessionInfo);
    
    // 5. Store (by sessionId)
    redis.opsForSet().add(userKey, sessionInfo.getSessionId());
    redis.opsForValue().set(sessionKey, sessionJson, ttl);
    
    // 6. If loginSessionId is provided, also store by loginSessionId (dual index)
    if (sessionInfo.getLoginSessionId() != null && !sessionInfo.getLoginSessionId().isBlank()) {
        String loginSessionKey = LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + sessionInfo.getLoginSessionId();
        redis.opsForValue().set(loginSessionKey, sessionJson, ttl);
    }
}
```

**Key Points**:
- ✅ **Dual Index Storage**: Store by both `sessionId` and `loginSessionId`
- ✅ **User Index**: Maintain set of all session IDs for a user
- ✅ **Auto Set Defaults**: Status defaults to ACTIVE

#### 10.3.2 registerLoginSessionEnforceSingle Method

**Method Signature**:
```java
public List<LoginSessionInfo> registerLoginSessionEnforceSingle(LoginSessionInfo sessionInfo, long ttlSeconds)
```

**Implementation Logic**:
```java
public List<LoginSessionInfo> registerLoginSessionEnforceSingle(LoginSessionInfo sessionInfo, long ttlSeconds) {
    // 1. Get all ACTIVE sessions for this user
    List<LoginSessionInfo> activeSessions = getActiveLoginSessions(sessionInfo.getUserId());
    
    // 2. Mark old sessions as KICKED (will be deleted in blacklistKickedSessions())
    List<LoginSessionInfo> kicked = new ArrayList<>();
    for (LoginSessionInfo oldSession : activeSessions) {
        // Skip self (if new session's loginSessionId equals old session's, it's a token refresh for the same login session)
        if (sessionInfo.getLoginSessionId() != null 
                && sessionInfo.getLoginSessionId().equals(oldSession.getLoginSessionId())) {
            continue; // Skip, don't kick
        }
        
        // Update status to KICKED
        updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
        oldSession.setStatus(SessionStatus.KICKED);
        kicked.add(oldSession);
    }
    
    // 3. Ensure new session status is ACTIVE
    sessionInfo.setStatus(SessionStatus.ACTIVE);
    
    // 4. Register current session
    registerLoginSession(sessionInfo, ttlSeconds);
    
    return kicked;
}
```

**Key Logic**:
1. ✅ **Query ACTIVE Sessions**: Only query ACTIVE sessions for this `userId`
2. ✅ **Skip Same loginSessionId**: Token refresh scenario, don't kick self
3. ✅ **Mark as KICKED**: Old sessions marked as KICKED (will be deleted in `blacklistKickedSessions()`)
4. ✅ **Register New Session**: New session status is ACTIVE

#### 10.3.3 getLoginSessionByLoginSessionId Method

**Method Signature**:
```java
public LoginSessionInfo getLoginSessionByLoginSessionId(String loginSessionId)
```

**Implementation Logic**:
```java
public LoginSessionInfo getLoginSessionByLoginSessionId(String loginSessionId) {
    if (loginSessionId == null || loginSessionId.isBlank()) {
        return null;
    }
    
    // Directly query from loginSessionId index (O(1) time complexity)
    String json = redis.opsForValue().get(LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + loginSessionId);
    if (json == null) {
        return null;
    }
    
    try {
        LoginSessionInfo info = JSON.parseObject(json, LoginSessionInfo.class);
        // Backward compatibility: if status is null, default to ACTIVE
        if (info != null && info.getStatus() == null) {
            info.setStatus(SessionStatus.ACTIVE);
        }
        return info;
    } catch (Exception ex) {
        log.warn("Failed to deserialize LoginSessionInfo: loginSessionId={}", loginSessionId, ex);
        return null;
    }
}
```

**Key Points**:
- ✅ **O(1) Query**: Directly query from `loginSessionId` index, high performance
- ✅ **Backward Compatibility**: If status is null, default to ACTIVE

#### 10.3.4 updateSessionStatus Method

**Method Signature**:
```java
public void updateSessionStatus(String sessionId, SessionStatus status)
```

**Implementation Logic**:
```java
public void updateSessionStatus(String sessionId, SessionStatus status) {
    if (sessionId == null || sessionId.isBlank() || status == null) {
        return;
    }
    
    // 1. Get session information
    LoginSessionInfo session = getLoginSession(sessionId);
    if (session == null) {
        log.warn("Failed to update session status: session does not exist: sessionId={}", sessionId);
        return;
    }
    
    // 2. Update status
    session.setStatus(status);
    String sessionJson = JSON.toJSONString(session);
    
    // 3. Calculate remaining TTL
    Duration ttl = DEFAULT_LOGIN_TTL;
    if (session.getExpiresAt() != null && session.getExpiresAt() > 0) {
        long remainingSeconds = (session.getExpiresAt() - Instant.now().toEpochMilli()) / 1000;
        if (remainingSeconds > 0) {
            ttl = Duration.ofSeconds(remainingSeconds);
        }
    }
    
    // 4. Update data stored by sessionId
    String sessionKey = LOGIN_SESSION_KEY_PREFIX + sessionId;
    redis.opsForValue().set(sessionKey, sessionJson, ttl);
    
    // 5. If loginSessionId exists, also update data stored by loginSessionId (dual index sync)
    if (session.getLoginSessionId() != null && !session.getLoginSessionId().isBlank()) {
        String loginSessionKey = LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + session.getLoginSessionId();
        redis.opsForValue().set(loginSessionKey, sessionJson, ttl);
    }
}
```

**Key Points**:
- ✅ **Dual Index Sync**: Simultaneously update data stored by both `sessionId` and `loginSessionId`
- ✅ **TTL Calculation**: Use remaining validity as TTL

---

### 10.4 Dual Index Design

#### 10.4.1 Why Dual Index Is Needed

**Problem**:
- Need to query by `sessionId` (jti) (backward compatibility)
- Need to query by `loginSessionId` (sid) (core functionality)

**Solution**:
- **Dual Index**: Store by both `sessionId` and `loginSessionId`
- **Synchronous Update**: Update both indexes when updating

#### 10.4.2 sessionId Index

**Purpose**:

- Backward compatibility with old code
- Query session by `sessionId` (jti)

**Key Format**:
```
session:login:token:{sessionId}
```

**Query Method**:
```java
public LoginSessionInfo getLoginSession(String sessionId)
```

#### 10.4.3 loginSessionId Index

**Purpose**:
- Core functionality: Query session by `loginSessionId` (sid)
- Query session status during JWT validation
- Query session during single device login

**Key Format**:
```
session:login:loginSession:{loginSessionId}
```

**Query Method**:
```java
public LoginSessionInfo getLoginSessionByLoginSessionId(String loginSessionId)
```

#### 10.4.4 Dual Index Synchronization

**On Write**:
```java
// Write to both indexes simultaneously
redis.opsForValue().set(sessionKey, sessionJson, ttl); // sessionId index
redis.opsForValue().set(loginSessionKey, sessionJson, ttl); // loginSessionId index
```

**On Update**:
```java
// Update both indexes simultaneously
redis.opsForValue().set(sessionKey, sessionJson, ttl); // sessionId index
redis.opsForValue().set(loginSessionKey, sessionJson, ttl); // loginSessionId index
```

**On Delete**:
```java
// Delete both indexes simultaneously
redis.delete(sessionKey); // sessionId index
redis.delete(loginSessionKey); // loginSessionId index
```

#### 10.4.5 refreshLoginSession Method

**Method Signature**:
```java
public void refreshLoginSession(LoginSessionInfo sessionInfo, String previousSessionId, long ttlSeconds)
```

**Purpose**:
- When token is refreshed, `jti` changes, need to update `sessionId` in SessionRegistry
- Delete old `sessionId` record, add new `sessionId` record
- Keep `loginSessionId` unchanged, ensure session association is correct

**Implementation Logic**:
```java
public void refreshLoginSession(LoginSessionInfo sessionInfo, String previousSessionId, long ttlSeconds) {
    // 1. If previousSessionId differs from new sessionId, delete old record
    if (previousSessionId != null && !previousSessionId.equals(sessionInfo.getSessionId())) {
        redis.opsForSet().remove(LOGIN_USER_KEY_PREFIX + userId, previousSessionId);
        redis.delete(LOGIN_SESSION_KEY_PREFIX + previousSessionId);
    }
    
    // 2. Add new sessionId record
    Duration ttl = resolveTtl(ttlSeconds, DEFAULT_LOGIN_TTL);
    String userKey = LOGIN_USER_KEY_PREFIX + sessionInfo.getUserId();
    String sessionKey = LOGIN_SESSION_KEY_PREFIX + sessionInfo.getSessionId();
    String sessionJson = JSON.toJSONString(sessionInfo);
    
    redis.opsForSet().add(userKey, sessionInfo.getSessionId());
    redis.opsForValue().set(sessionKey, sessionJson, ttl);
    
    // 3. Update loginSessionId index (keep loginSessionId unchanged)
    if (sessionInfo.getLoginSessionId() != null && !sessionInfo.getLoginSessionId().isBlank()) {
        String loginSessionKey = LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + sessionInfo.getLoginSessionId();
        redis.opsForValue().set(loginSessionKey, sessionJson, ttl);
    }
}
```

**Key Points**:
- ✅ **Delete Old Record**: Delete old `sessionId` record (remove from user set and session details)
- ✅ **Add New Record**: Add new `sessionId` record
- ✅ **Update loginSessionId Index**: Update data stored by `loginSessionId` (keep `loginSessionId` unchanged)
- ✅ **TTL Calculation**: Use `refresh_token` expiration time to calculate TTL

**Usage Scenario**:
- Called when TokenController detects token refresh
- Ensure session information in SessionRegistry matches current token

---

### 10.5 Chapter Summary

**Core Functions**:
1. Unified session registry (login sessions + WebSocket sessions)
2. Dual index design (sessionId + loginSessionId)
3. Single device login (new connection kicks out old)
4. Session status management

**Storage Structure**:
- Login sessions: Dual index by `sessionId` and `loginSessionId`
- WebSocket sessions: Index by `sessionId`
- User index: Maintain set of all session IDs for a user

**Key Methods**:
- `registerLoginSession()`: Register login session
- `registerLoginSessionEnforceSingle()`: Single device login registration
- `getLoginSessionByLoginSessionId()`: Query by loginSessionId
- `updateSessionStatus()`: Update session status
- `refreshLoginSession()`: Refresh login session (update sessionId when token is refreshed)



---

## XI. JWT Blacklist Mechanism

> **Chapter Objective**: Understand the JWT blacklist mechanism, master how to immediately invalidate revoked tokens, and the storage design and TTL management of the blacklist.

---

### 11.1 Blacklist Overview

#### 11.1.1 Why Blacklist Is Needed

**Problem**:
- JWT is stateless, once issued cannot be revoked
- After user logs out, token is still valid (until expiration)
- After user logs in on another device, old device's token is still valid

**Solution**:
- **Blacklist Mechanism**: Add revoked tokens to blacklist
- During JWT validation, check blacklist first
- If token is in blacklist, deny access

#### 11.1.2 Blacklist Functions

**Functions**:
- ✅ **Immediately Invalidate Token**: Immediately invalidate token on logout
- ✅ **Single Device Login Support**: Immediately invalidate old token on new login
- ✅ **Security**: Prevent revoked tokens from being used

---

### 11.2 Code Call Chain (Blacklist)

#### 11.2.1 Add to Blacklist

```
Kick old session on login
  ↓
LoginSessionKickHandler.blacklistKickedSessions()
  ↓
JwtBlacklistService.addToBlacklist(token, ttl)
  ↓
Redis SET jwt:blacklist:{token} "1" EX {ttl}
  ↓
Blacklist record created
```

#### 11.2.2 Check Blacklist

```
During JWT validation
  ↓
JwtDecoderConfig.jwtDecoder()
  ↓
JwtBlacklistService.isBlacklisted(token)
  ↓
Redis EXISTS jwt:blacklist:{token}
  ├─ Exists → Return true (hit blacklist)
  └─ Not exists → Return false (miss)
  ↓
Hit blacklist → Throw JwtException("Token has been revoked")
Miss → Continue subsequent validation
```

---

### 11.3 Key Code Analysis

#### 11.3.1 JwtBlacklistService Class Structure

**File Location**: `apps/gateway/src/main/java/com/gamehub/gateway/service/JwtBlacklistService.java`

**Class Definition**:
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {
    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";
    private static final long DEFAULT_TTL_SECONDS = 3600;
    
    private final ReactiveStringRedisTemplate redisTemplate;
}
```

**Key Dependencies**:
- `ReactiveStringRedisTemplate`: Redis operation template (reactive)

#### 11.3.2 addToBlacklist Method

**Method Signature**:
```java
public Mono<Void> addToBlacklist(String token, long expiresInSeconds)
```

**Complete Implementation**:
```java
public Mono<Void> addToBlacklist(String token, long expiresInSeconds) {
    if (token == null || token.isBlank()) {
        return Mono.empty();
    }
    
    // Calculate TTL (use token remaining validity, fallback 1 hour)
    long ttl = expiresInSeconds > 0 ? expiresInSeconds : DEFAULT_TTL_SECONDS;
    
    // Write to Redis
    return redisTemplate.opsForValue()
            .set(buildKey(token), "1", Duration.ofSeconds(ttl))
            .doOnSuccess(success -> {
                if (Boolean.FALSE.equals(success)) {
                    log.warn("Redis set returned false, token={}", token);
                }
            })
            .doOnError(ex -> log.error("Failed to write JWT blacklist", ex))
            .onErrorResume(ex -> Mono.empty())
            .then();
}
```

**Key Points**:
- ✅ **TTL Calculation**: Use token remaining validity as blacklist TTL
- ✅ **Fallback Strategy**: If TTL <= 0, use default 1 hour
- ✅ **Error Handling**: Log errors, don't throw exceptions (don't block main flow)

#### 11.3.3 isBlacklisted Method

**Method Signature**:
```java
public Mono<Boolean> isBlacklisted(String token)
```

**Complete Implementation**:
```java
public Mono<Boolean> isBlacklisted(String token) {
    if (token == null || token.isBlank()) {
        return Mono.just(false);
    }
    
    return redisTemplate.hasKey(buildKey(token))
            .onErrorResume(ex -> {
                log.error("Failed to query JWT blacklist, defaulting to hit", ex);
                return Mono.just(true); // On error, default to hit (fail-closed)
            });
}
```

**Key Points**:
- ✅ **O(1) Query**: Use `hasKey()` query, high performance
- ✅ **Security Strategy**: On error, default to hit, fail-closed

#### 11.3.4 buildKey Method

**Method Signature**:
```java
private static String buildKey(String token)
```

**Implementation Logic**:
```java
private static String buildKey(String token) {
    return BLACKLIST_KEY_PREFIX + token;
}
```

**Key Format**:
```
jwt:blacklist:{完整的token字符串}
```

**Example**:
```
jwt:blacklist:eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLTEyMyIsImppdCI6Imp0aS1hYmMxMjMiLCJzaWQiOiJzaWQteHl6Nzg5IiwiaWF0IjoxNzA0MDY3MjAwLCJleHAiOjE3MDQwNzA4MDB9.signature
```

---

### 11.4 Redis Storage Design

#### 11.4.1 Key Design

**Key Format**:
```
jwt:blacklist:{完整的token字符串}
```

**Characteristics**:
- ✅ **Uniqueness**: Each token corresponds to a unique key
- ✅ **Readability**: Key contains token prefix, easy to identify

#### 11.4.2 Value Design

**Value Format**:
```
"1"
```

**Characteristics**:
- ✅ **Simple**: Only need to mark existence, no need to store additional information
- ✅ **Space Saving**: Only store one character

#### 11.4.3 TTL Design

**TTL Calculation**:
```java
long ttl = expiresInSeconds > 0 ? expiresInSeconds : DEFAULT_TTL_SECONDS;
```

**Characteristics**:
- ✅ **Auto Expiration**: Use token remaining validity as TTL
- ✅ **Auto Cleanup**: After token expires, blacklist record automatically deleted
- ✅ **Space Saving**: No need to manually clean expired blacklist records

**Example**:
```
Token expiration time: 2024-01-01 12:00:00
Current time: 2024-01-01 11:45:00
Remaining time: 15 minutes = 900 seconds

Add to blacklist:
  Redis SET jwt:blacklist:{token} "1" EX 900
```

---

### 11.5 Blacklist Usage Scenarios

#### 11.5.1 Scenario 1: Kick Old Token on Login

**Trigger Timing**:
- User logs in on another device
- Old device's session is deleted, token is added to blacklist

**Code Location**:
```java
// LoginSessionKickHandler.blacklistKickedSessions()
for (LoginSessionInfo kickedSession : kickedSessions) {
    // 1. Delete old session from SessionRegistry
    sessionRegistry.unregisterLoginSession(kickedSession.getSessionId());
    
    // 2. Add old token to blacklist
    if (kickedSession.getToken() != null && !kickedSession.getToken().isBlank()) {
        long ttlSeconds = (kickedSession.getExpiresAt() - Instant.now().toEpochMilli()) / 1000;
        blacklistService.addToBlacklist(kickedSession.getToken(), ttlSeconds);
    }
}
```

**Effect**:
- Old device's token immediately invalidated
- When old device uses token to access resources, will be rejected

#### 11.5.2 Scenario 2: Invalidate Token on Logout

**Trigger Timing**:
- User actively logs out
- Current token is added to blacklist

**Code Location**:
```java
// SecurityConfig.addTokenToBlacklist()
String token = client.getAccessToken().getTokenValue();
Instant expiresAt = client.getAccessToken().getExpiresAt();
long expiresIn = Duration.between(Instant.now(), expiresAt).getSeconds();
blacklistService.addToBlacklist(token, expiresIn);
```

**Effect**:
- After logout, token immediately invalidated
- Even if token is not expired, cannot continue to use

#### 11.5.3 Scenario 3: Check Blacklist During JWT Validation

**Trigger Timing**:
- Every time JWT is validated
- Check blacklist first, then check signature and status

**Code Location**:
```java
// JwtDecoderConfig.jwtDecoder()
return token -> jwtBlacklistService.isBlacklisted(token)
        .flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                return Mono.error(new JwtException("Token has been revoked"));
            }
            // Continue subsequent validation
        });
```

**Effect**:
- Tokens that hit blacklist are immediately rejected
- High performance (O(1) query)

---

### 11.6 Performance Considerations

#### 11.6.1 Query Performance

**Time Complexity**:
- `isBlacklisted()`: O(1) (Redis `EXISTS` operation)

**Performance Optimization**:
- ✅ **Check First**: Blacklist check placed first, highest performance
- ✅ **Fast Fail**: Immediately reject if hits blacklist, don't perform subsequent validation

#### 11.6.2 Storage Space

**Space Usage**:
- Each token corresponds to a key
- Key length = `jwt:blacklist:` prefix length + token length
- Value only stores `"1"` (1 byte)

**Space Optimization**:
- ✅ **Auto Expiration**: Use TTL, automatically delete after token expires
- ✅ **No Manual Cleanup Needed**: Redis automatically cleans expired keys

#### 11.6.3 Scalability

**Problem**:
- If token is long, key will also be long
- Large number of tokens will cause Redis memory usage to increase

**Solution**:
- ✅ **TTL Auto Cleanup**: Expired tokens automatically deleted
- ✅ **Redis Memory Management**: Use Redis memory eviction policy
- ✅ **Future Optimization**: Can consider using token's hash value as key (reduce key length)

---

### 11.7 Chapter Summary

**Core Functions**:
1. Immediately invalidate revoked tokens
2. Support single device login (new login kicks out old token)
3. Support invalidating token on logout

**Storage Design**:
- Key: `jwt:blacklist:{完整的token字符串}`
- Value: `"1"`
- TTL: Token remaining validity (auto expiration)

**Key Methods**:
- `addToBlacklist()`: Add to blacklist
- `isBlacklisted()`: Check blacklist

**Performance Characteristics**:
- ✅ O(1) query performance
- ✅ Auto expiration, space saving
- ✅ Check first, fast fail



---

## XII. Complete Data Flow Diagram

> **Chapter Objective**: Through complete data flow diagrams, understand the data flow process of the entire single device login system, including login, JWT validation, WebSocket connection, logout, and other complete flows.

---

### 12.1 Login Data Flow

#### 12.1.1 Complete Login Data Flow Diagram

```
┌─────────┐
│  用户   │
└────┬────┘
     │ 1. 访问 /oauth2/authorization/keycloak
     ↓
┌─────────────────┐
│  Gateway        │
│  (Spring Security)│
└────┬────────────┘
     │ 2. 重定向到 Keycloak
     ↓
┌─────────────┐
│  Keycloak   │
│  (登录页面)  │
└────┬────────┘
     │ 3. 用户输入用户名密码
     ↓
┌─────────────┐
│  Keycloak   │
│  (验证用户)  │
└────┬────────┘
     │ 4. 生成授权码，重定向回 Gateway
     ↓
┌─────────────────┐
│  Gateway        │
│  (OAuth2 Client)│
└────┬────────────┘
     │ 5. 使用授权码换取 access_token
     ↓
┌─────────────┐
│  Keycloak   │
│  (Token端点) │
└────┬────────┘
     │ 6. 返回 access_token (包含 sid)
     ↓
┌─────────────────────────────────────┐
│  Gateway - LoginSessionKickHandler  │
└────┬────────────────────────────────┘
     │
     ├─ 7. 提取 JWT 信息
     │     ├─ userId (sub)
     │     ├─ sessionId (jti)
     │     └─ loginSessionId (sid)
     │
     ├─ 8. 构建 LoginSessionInfo
     │
     ├─ 9. 调用 registerLoginSessionEnforceSingle()
     │     ↓
     │  ┌──────────────────────┐
     │  │  SessionRegistry     │
     │  └────┬─────────────────┘
     │       │
     │       ├─ 9.1 查询该用户的所有 ACTIVE 会话
     │       │     ↓
     │       │  ┌──────┐
     │       │  │Redis │
     │       │  │查询  │
     │       │  └──────┘
     │       │
     │       ├─ 9.2 将旧会话标记为 KICKED
     │       │     ↓
     │       │  ┌──────┐
     │       │  │Redis │
     │       │  │更新  │
     │       │  └──────┘
     │       │
     │       └─ 9.3 注册新会话（状态=ACTIVE）
     │             ↓
     │          ┌──────┐
     │          │Redis │
     │          │存储  │
     │          └──────┘
     │
     ├─ 10. 删除旧会话并将 token 加入黑名单
     │      ↓
     │   ┌──────────────────┐
     │   │JwtBlacklistService│
     │   └────┬─────────────┘
     │        │
     │        └─ Redis SET jwt:blacklist:{token} "1" EX {ttl}
     │
     ├─ 11. 发布 SESSION_KICKED 事件
     │      ↓
     │   ┌──────────────────┐
     │   │SessionEventPublisher│
     │   └────┬─────────────┘
     │        │
     │        └─ Kafka 发送消息
     │              ↓
     │           ┌──────┐
     │           │Kafka │
     │           │Topic │
     │           └──────┘
     │
     └─ 12. 存储 loginSessionId 到 HTTP Session
           ↓
        ┌──────┐
        │Redis │
        │Session│
        └──────┘
```

#### 12.1.2 Data Flow Explanation

**Steps 1-6: OAuth2 Authorization Code Flow**
- Spring Security automatically handles
- User completes authentication, obtains access_token

**Steps 7-9: Session Registration**
- Extract JWT information
- Build `LoginSessionInfo`
- Call `registerLoginSessionEnforceSingle()` to implement single device login

**Step 10: Delete Old Session and Add to Blacklist**
- Delete kicked old session from SessionRegistry (`unregisterLoginSession()`)
- Add kicked old token to blacklist (`addToBlacklist()`)
- Immediately invalidate old token

**Step 11: Event Publishing**
- Publish SESSION_KICKED event to Kafka
- Notify services to disconnect WebSocket connections

**Step 12: HTTP Session Storage**
- Store `loginSessionId` in HTTP Session
- Used for subsequent validation in `TokenController`

---

### 12.2 JWT Validation Data Flow

#### 12.2.1 Complete JWT Validation Data Flow Diagram

```
┌─────────┐
│ 客户端  │
└────┬────┘
     │ 1. 请求携带 Authorization: Bearer {token}
     ↓
┌─────────────────┐
│  Gateway        │
│  (Spring Security)│
└────┬────────────┘
     │ 2. BearerTokenAuthenticationFilter
     ↓
┌──────────────────────┐
│  JwtDecoderConfig    │
│  jwtDecoder()        │
└────┬─────────────────┘
     │
     ├─ [第一层] 黑名单检查
     │     ↓
     │  ┌──────────────────┐
     │  │JwtBlacklistService│
     │  └────┬─────────────┘
     │       │
     │       └─ Redis EXISTS jwt:blacklist:{token}
     │             ├─ 存在 → 401 Unauthorized
     │             └─ 不存在 → 继续
     │
     ├─ [第二层] 签名校验
     │     ↓
     │  ┌──────────────────┐
     │  │NimbusReactiveJwtDecoder│
     │  └────┬─────────────┘
     │       │
     │       ├─ 验证签名（Keycloak 公钥）
     │       ├─ 验证过期时间（exp）
     │       ├─ 验证颁发者（iss）
     │       ├─ 验证受众（aud）
     │       ├─ 失败 → 401 Unauthorized
     │       └─ 成功 → 返回 Jwt 对象
     │
     └─ [第三层] 会话状态检查
           ↓
        ┌──────────────────────┐
        │checkSessionStatus()   │
        └────┬──────────────────┘
             │
             ├─ 提取 loginSessionId (sid)
             │
             ├─ 查询 SessionRegistry
             │     ↓
             │  ┌──────────────────┐
             │  │SessionRegistry  │
             │  └────┬────────────┘
             │       │
             │       └─ getLoginSessionByLoginSessionId(loginSessionId)
             │             ↓
             │          ┌──────┐
             │          │Redis │
             │          │查询  │
             │          └──────┘
             │
             ├─ 检查会话状态
             │     ├─ ACTIVE → 通过
             │     ├─ KICKED → 401 Unauthorized
             │     └─ EXPIRED → 401 Unauthorized
             │
             └─ 通过 → 创建 JwtAuthenticationToken
                   ↓
               请求继续处理
```

#### 12.2.2 Three-Layer Validation Explanation

**First Layer: Blacklist Check**
- Executed first, highest performance
- Immediately reject revoked tokens

**Second Layer: Signature Validation**
- Automatically completed by Spring Security
- Verify token legitimacy

**Third Layer: Session Status Check**
- Core mechanism
- Determine if loginSession is kicked

---

### 12.3 WebSocket Connection Data Flow

#### 12.3.1 Complete WebSocket Connection Data Flow Diagram

```
┌─────────┐
│ 客户端  │
└────┬────┘
     │ 1. 建立 WebSocket 连接（携带 JWT token）
     │    URL: /ws?access_token=xxx 或 Header: Authorization: Bearer xxx
     ↓
┌─────────────────┐
│  Gateway        │
│  WebSocketTokenFilter│
└────┬────────────┘
     │ 1.1 从 URL 参数提取 token，放入 Authorization header
     ↓
┌─────────────────┐
│  Game-Service   │
│  (Spring WebSocket)│
└────┬────────────┘
     │ 2. STOMP CONNECT 命令到达
     ↓
┌──────────────────────────┐
│  WebSocketAuthChannelInterceptor│
│  preSend()                │
└────┬─────────────────────┘
     │ 2.1 从 STOMP header 提取 token
     │ 2.2 验证 token（JwtDecoder）
     │ 2.3 设置用户身份到 WebSocket 会话
     │     accessor.setUser(JwtAuthenticationToken)
     │     ↓
     │     Spring 框架保存到会话存储：
     │     Session[sessionId] = Principal
     ↓
┌──────────────────────┐
│  WebSocketSessionManager│
│  handleSessionConnect()│
└────┬─────────────────┘
     │
     ├─ 3. 从 Principal 提取信息
     │     ├─ userId (principal.getName())
     │     ├─ sessionId (accessor.getSessionId())
     │     └─ loginSessionId (extractLoginSessionId(principal))
     │     **注意**：Principal 从 WebSocket 会话中获取（由拦截器设置）
     │
     ├─ 4. 构建 WebSocketSessionInfo
     │
     ├─ 5. 调用 registerWebSocketSessionEnforceSingle()
     │     ↓
     │  ┌──────────────────────┐
     │  │  SessionRegistry      │
     │  └────┬─────────────────┘
     │       │
     │       ├─ 5.1 查询该用户的所有 WebSocket 会话
     │       │     ↓
     │       │  ┌──────┐
     │       │  │Redis │
     │       │  │查询  │
     │       │  └──────┘
     │       │
     │       ├─ 5.2 删除旧会话（返回被踢掉的会话列表）
     │       │     ↓
     │       │  ┌──────┐
     │       │  │Redis │
     │       │  │删除  │
     │       │  └──────┘
     │       │
     │       └─ 5.3 注册新会话
     │             ↓
     │          ┌──────┐
     │          │Redis │
     │          │存储  │
     │          └──────┘
     │
     └─ 6. 处理被踢掉的旧连接
           ├─ 发送踢人通知
           │     ↓
           │  ┌──────────────────┐
           │  │WebSocketDisconnectHelper│
           │  └────┬────────────┘
           │       │
           │       └─ 发送到 /queue/system.kick
           │
           └─ 强制断开连接
                 ↓
              ┌──────────────────┐
              │WebSocketDisconnectHelper│
              └────┬─────────────┘
                   │
                   └─ 发送 DISCONNECT 命令
```

#### 12.3.2 WebSocket Authentication Flow Explanation

**Authentication Flow**:
1. **Gateway Layer**: `WebSocketTokenFilter` extracts token from URL parameters, puts into `Authorization` header
2. **Interceptor Layer**: `WebSocketAuthChannelInterceptor` on STOMP CONNECT:
   - Extract token from STOMP header
   - Validate token (signature, expiration, etc.)
   - Set user identity to WebSocket session via `accessor.setUser()`
3. **Session Storage**: Spring framework saves user identity to session storage (keyed by `sessionId`)
4. **Subsequent Messages**: Spring framework automatically gets user identity from session, sets in message header
5. **Application Service Logic**: Get user identity via `sha.getUser()`

**Key Points**:
- ✅ Token only used once during STOMP CONNECT
- ✅ User identity stored in WebSocket session
- ✅ Subsequent messages no longer need to carry token
- ✅ Application service logic gets user identity from message header (Spring framework has set from session)

#### 12.3.3 WebSocket Single Device Login Explanation

**Flow**:
1. When new connection is established, query all WebSocket sessions for this user
2. Delete old sessions, return list of kicked sessions
3. Register new session
4. Send kick notification and force disconnect old connections

---

### 12.4 Logout Data Flow

#### 12.4.1 Complete Logout Data Flow Diagram

```
┌─────────┐
│  用户   │
└────┬────┘
     │ 1. 访问 /logout
     ↓
┌─────────────────┐
│  Gateway        │
│  (Spring Security)│
└────┬────────────┘
     │ 2. Logout Filter
     ↓
┌─────────────────────────────────┐
│  SecurityConfig                 │
│  jwtBlacklistLogoutHandler()   │
└────┬────────────────────────────┘
     │
     ├─ 3. 获取 OAuth2AuthorizedClient
     │
     ├─ 4. 将 access_token 加入黑名单
     │      ↓
     │   ┌──────────────────┐
     │   │JwtBlacklistService│
     │   └────┬─────────────┘
     │        │
     │        └─ Redis SET jwt:blacklist:{token} "1" EX {ttl}
     │
     ├─ 5. 从 JWT 中提取 loginSessionId
     │
     ├─ 6. 发布 SESSION_INVALIDATED 事件
     │      ↓
     │   ┌──────────────────┐
     │   │SessionEventPublisher│
     │   └────┬─────────────┘
     │        │
     │        └─ Kafka 发送消息
     │              ↓
     │           ┌──────┐
     │           │Kafka │
     │           │Topic │
     │           └──────┘
     │
     └─ 7. 移除 OAuth2AuthorizedClient
           ↓
        ┌──────┐
        │Redis │
        │Session│
        └──────┘

同时（异步）：
┌──────┐
│Kafka │
│Topic │
└──┬───┘
   │ 8. 消息到达
   ↓
┌──────────────────────┐
│  SessionEventConsumer│
│  consumeSessionInvalidated()│
└────┬─────────────────┘
     │ 9. 遍历所有 SessionEventListener
     ↓
┌──────────────────────────────┐
│  SessionInvalidatedListener  │
│  onSessionInvalidated()      │
└────┬─────────────────────────┘
     │
     ├─ 10. 基于 loginSessionId 查询 WebSocket 会话
     │      ↓
     │   ┌──────────────────────┐
     │   │  SessionRegistry      │
     │   └────┬─────────────────┘
     │        │
     │        └─ getWebSocketSessionsByLoginSessionId(loginSessionId)
     │              ↓
     │           ┌──────┐
     │           │Redis │
     │           │查询  │
     │           └──────┘
     │
     ├─ 11. 遍历所有会话
     │      ├─ 发送踢人通知
     │      ├─ 强制断开连接
     │      └─ 清理 SessionRegistry
     │
     └─ 12. 所有监听器成功 → 提交 offset
```

#### 12.4.2 Logout Flow Explanation

**Synchronous Processing**:
1. Add token to blacklist
2. Publish event to Kafka
3. Remove OAuth2AuthorizedClient

**Asynchronous Processing**:
1. Kafka message arrives
2. Consume event
3. Disconnect WebSocket connections

---

### 12.5 Single Device Login Complete Data Flow

#### 12.5.1 User A Logs In on Device 1

```
设备 1 登录
  ↓
LoginSessionKickHandler
  ├─ 查询 userId="user-A" 的 ACTIVE 会话 → []
  ├─ 没有旧会话，不踢掉
  └─ 注册新会话（loginSessionId="sid-001", status=ACTIVE）
  ↓
设备 1 登录成功
```

#### 12.5.2 User A Logs In on Device 2 (Single Device Login)

```
设备 2 登录
  ↓
LoginSessionKickHandler
  ├─ 查询 userId="user-A" 的 ACTIVE 会话 → [设备1的会话]
  ├─ 设备1的会话 loginSessionId="sid-001"
  ├─ 设备2的会话 loginSessionId="sid-002"
  ├─ loginSessionId 不同，需要踢掉设备1
  │
  ├─ 将设备1的会话标记为 KICKED
  │     ↓
  │  SessionRegistry.updateSessionStatus()
  │     ↓
  │  Redis 更新（双索引同步）
  │
  ├─ 删除设备1的会话，并将 token 加入黑名单
  │     ├─ SessionRegistry.unregisterLoginSession()（删除会话）
  │     └─ JwtBlacklistService.addToBlacklist()（加入黑名单）
  │         └─ Redis SET jwt:blacklist:{token1} "1" EX {ttl}
  │
  ├─ 发布 SESSION_KICKED 事件（loginSessionId="sid-001"）
  │     ↓
  │  SessionEventPublisher.publishSessionInvalidated()
  │     ↓
  │  Kafka 发送消息
  │
  └─ 注册新会话（设备2, loginSessionId="sid-002", status=ACTIVE）
  ↓
设备 2 登录成功

同时（异步）：
Kafka 消息到达
  ↓
SessionEventConsumer.consumeSessionInvalidated()
  ↓
SessionInvalidatedListener.onSessionInvalidated()
  ├─ 基于 loginSessionId="sid-001" 查询 WebSocket 会话
  ├─ 找到设备1的 WebSocket 连接
  ├─ 发送踢人通知
  ├─ 强制断开连接
  └─ 清理 SessionRegistry
  ↓
设备 1 的 WebSocket 连接被断开

同时（设备1后续请求）：
设备 1 使用 token1 访问资源
  ↓
JwtDecoderConfig.jwtDecoder()
  ├─ [第一层] 黑名单检查 → 命中 → 401 Unauthorized
  └─ 请求被拒绝
```

---

### 12.6 Chapter Summary

**Core Data Flows**:
1. **Login Data Flow**: User login → Session registration → Single device login processing → Blacklist → Event publishing
2. **JWT Validation Data Flow**: Three-layer validation (blacklist → signature → status)
3. **WebSocket Connection Data Flow**: Connection establishment → Session registration → Single device login check → Kick old connections
4. **Logout Data Flow**: Logout request → Blacklist → Event publishing → Kafka → WebSocket disconnection

**Data Storage**:
- **Redis**: SessionRegistry, blacklist, HTTP Session
- **Kafka**: Event notification

**Key Components**:
- `LoginSessionKickHandler`: Login processing
- `JwtDecoderConfig`: JWT validation
- `SessionRegistry`: Session management
- `SessionEventPublisher`: Event publishing
- `SessionInvalidatedListener`: Event processing



---

## XIII. Key Design Decisions

> **Chapter Objective**: Understand the reasons and basis for key design decisions, master why `sid` is chosen as `loginSessionId`, why mark KICKED instead of deleting, why HTTP Session storage is needed, and why three-layer validation is needed.

---

### 13.1 Why Use sid as loginSessionId

#### 13.1.1 Stability of sid

**Question**: Why can't we use `jti` (JWT ID) as `loginSessionId`?

**Answer**:

**Problems with jti**:
- ⚠️ **Unstable**: When token is refreshed, `jti` may change (depends on Keycloak configuration)
- ⚠️ **Small scope**: `jti` only identifies a single token, cannot identify the entire login session
- ⚠️ **Cannot track**: Cannot track all tokens of the same login session

**Advantages of sid**:
- ✅ **Stable and unchanged**: `sid` remains unchanged during one login lifecycle
- ✅ **Unchanged on token refresh**: Even when token is refreshed, `sid` remains unchanged
- ✅ **Large scope**: `sid` identifies the entire login session, can associate all tokens
- ✅ **Trackable**: Can track all operations of the entire login session

**Example**:
```
User logs in
  Token 1: jti="jti-001", sid="sid-xyz789"
  ↓
Token refresh
  Token 2: jti="jti-002", sid="sid-xyz789"  ← sid unchanged!
  ↓
Token refresh again
  Token 3: jti="jti-003", sid="sid-xyz789"  ← sid still unchanged!

All tokens share the same sid="sid-xyz789"
```

#### 13.1.2 Keycloak Native Support

**Keycloak's sid claim**:
- Keycloak automatically includes `sid` claim in JWT
- `sid` is Keycloak's session ID, stable and unchanged throughout the login lifecycle
- Complies with OIDC specification (OpenID Connect Session Management)

**Code Implementation**:
```java
// Extract sid from JWT
Object sidObj = jwt.getClaim("sid");
if (sidObj != null) {
    String sid = sidObj.toString();
    if (sid != null && !sid.isBlank()) {
        loginSessionId = sid; // Use sid
    }
}
```

#### 13.1.3 Unchanged on Token Refresh

**Key Characteristics**:
- ✅ **Generated on login**: When user logs in, Keycloak generates a new `sid`
- ✅ **Maintained on refresh**: When token is refreshed, `sid` remains unchanged
- ✅ **Invalidated on logout**: When user logs out, `sid` is invalidated

**Implementation Principle**:
- Keycloak's `sid` is bound to Keycloak's session
- As long as Keycloak session exists, `sid` remains unchanged
- Token refresh only generates a new token, doesn't change Keycloak session

---

### 13.2 Why Mark KICKED Before Deleting

#### 13.2.1 Two-Phase Processing Mechanism

**Processing Flow**:
1. **Phase 1**: `registerLoginSessionEnforceSingle()` marks old session as KICKED
2. **Phase 2**: `blacklistKickedSessions()` deletes old session from SessionRegistry and adds token to blacklist

**Why Two Phases**:
- ✅ **State Consistency**: Mark KICKED first, ensure state update completes
- ✅ **Error Handling**: If deletion fails, at least state is marked, won't affect new session registration
- ✅ **Code Clarity**: Separation of responsibilities, `registerLoginSessionEnforceSingle()` responsible for state management, `blacklistKickedSessions()` responsible for cleanup

**Code Implementation**:
```java
// Phase 1: Mark as KICKED
updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
oldSession.setStatus(SessionStatus.KICKED);
kicked.add(oldSession);

// Phase 2: Delete session and add to blacklist (executed in blacklistKickedSessions())
sessionRegistry.unregisterLoginSession(kickedSession.getSessionId());
blacklistService.addToBlacklist(kickedSession.getToken(), ttlSeconds);
```

**Final Result**:
- ✅ **Session Deleted**: Old session record deleted from SessionRegistry and Redis
- ✅ **Token Invalidated**: Old token added to blacklist, cannot continue to use
- ✅ **State Marked**: Marked as KICKED before deletion, convenient for logging and troubleshooting

#### 13.2.2 Troubleshooting

**Scenario**:
- User reports "account kicked offline"
- Need to check login history, confirm if logged in on another device

**Benefits of Marking KICKED**:
- ✅ **State Identification**: Mark as KICKED before deletion, convenient for logging and troubleshooting
- ✅ **Time Window**: In the time window between marking and deletion, can view kicked offline session information
- ✅ **Error Handling**: If deletion fails, at least state is marked, won't affect new session registration

**Note**:
- After marking as KICKED, session record will be deleted from SessionRegistry in `blacklistKickedSessions()` method
- Deletion operation will clean session data in Redis, but logs will retain kicked offline records

**Example**:
```
User A's login flow:
  - 2024-01-01 10:00:00 Login (Device 1, loginSessionId="sid-001")
  - 2024-01-01 10:05:00 Login (Device 2, loginSessionId="sid-002")
    ├─ Device 1's session marked as KICKED
    ├─ Device 1's session deleted from SessionRegistry
    └─ Device 1's token added to blacklist
  
Log records:
  - Device 1 logged in at 10:00
  - Device 2 logged in at 10:05, kicked Device 1 (recorded in logs)
  - Device 1's session deleted (no longer exists in Redis)
```

#### 13.2.3 Two-Phase Processing Mechanism

**Processing Flow**:
1. **Phase 1**: Mark as KICKED (state management)
2. **Phase 2**: Delete session record (data cleanup)

**Principles**:
- ✅ **State Management**: Mark state first, ensure state consistency
- ✅ **Data Cleanup**: Delete session record, free storage space
- ✅ **Logging**: Record logs before deletion, retain audit information

**Implementation**:
```java
// Phase 1: Mark as KICKED
updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);

// Phase 2: Delete session (in blacklistKickedSessions())
sessionRegistry.unregisterLoginSession(kickedSession.getSessionId());

// Old approach (direct deletion)
redis.delete(LOGIN_SESSION_KEY_PREFIX + sessionId); // ❌ Delete data

// New approach (mark)
updateSessionStatus(sessionId, SessionStatus.KICKED); // ✅ Keep data, only update state
```

---

### 13.3 Why HTTP Session Storage for loginSessionId Is Needed

#### 13.3.1 TokenController Validation Requirement

**Question**: Why do we need to store `loginSessionId` in HTTP Session?

**Answer**:

**OAuth2AuthorizedClientService Overwrite Problem**:
- When `OAuth2AuthorizedClientService` stores `OAuth2AuthorizedClient`, it uses `userId` as key
- New login of the same user will overwrite old login's `OAuth2AuthorizedClient`
- Causes Device 1 calling `/token` may get Device 2's token

**Solution**:
- ✅ **HTTP Session Storage**: On login, store `loginSessionId` in HTTP Session
- ✅ **TokenController Validation**: When getting token, validate token's `loginSessionId` matches the one in Session
- ✅ **Prevent Overwrite**: If not matching, it means token has been overwritten by another login, reject returning token

**Code Implementation**:
```java
// Store on login
session.getAttributes().put(SESSION_LOGIN_SESSION_ID_KEY, loginSessionId);

// TokenController validation
String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
if (!loginSessionId.equals(sessionLoginSessionId)) {
    return 401; // token has been overwritten
}
```

#### 13.3.2 Prevent Token Overwrite

**Scenario**:
```
Device 1 logs in
  HTTP Session: loginSessionId="sid-001"
  OAuth2AuthorizedClientService: userId="user-A" → token1 (sid="sid-001")

Device 2 logs in
  HTTP Session: loginSessionId="sid-002"
  OAuth2AuthorizedClientService: userId="user-A" → token2 (sid="sid-002")  ← Overwritten!

Device 1 calls /token
  OAuth2AuthorizedClientService returns token2 (sid="sid-002")
  HTTP Session stores loginSessionId="sid-001"
  sid-002 != sid-001 → Reject returning token
```

**Key Points**:
- ✅ **HTTP Session Isolation**: Each device's HTTP Session is independent
- ✅ **Exact Match**: Ensure returned token belongs to current Session
- ✅ **Prevent Overwrite**: Even if `OAuth2AuthorizedClientService` is overwritten, can detect it

---

### 13.4 Why Three-Layer Validation Is Needed

#### 13.4.1 Signature Validation: Prevent Forgery

**Function**:
- ✅ **Prevent Forgery**: Verify token is issued by legitimate authorization server
- ✅ **Prevent Tampering**: Verify token content hasn't been tampered
- ✅ **Standard Validation**: Complies with OAuth2/JWT standards

**Implementation**:
```java
ReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder
        .withIssuerLocation(issuerUri)
        .build();
```

**Validation Content**:
- Whether signature is valid (using Keycloak public key)
- Expiration time (exp)
- Issuer (iss)
- Audience (aud)

#### 13.4.2 Blacklist Validation: Immediate Invalidation

**Function**:
- ✅ **Immediate Invalidation**: Immediately invalidate token on logout
- ✅ **Single Device Login Support**: Immediately invalidate old token on new login
- ✅ **High Performance**: O(1) query, executed first

**Implementation**:
```java
jwtBlacklistService.isBlacklisted(token)
    .flatMap(blacklisted -> {
        if (Boolean.TRUE.equals(blacklisted)) {
            return Mono.error(new JwtException("Token has been revoked"));
        }
        // Continue subsequent validation
    });
```

**Usage Scenarios**:
- User actively logs out
- User logs in on another device (old token added to blacklist)

#### 13.4.3 Status Validation: Session Management

**Function**:
- ✅ **Session Management**: Determine if loginSession is kicked offline
- ✅ **Single Device Login Core**: Implement single device login (new connection kicks out old)
- ✅ **Precise Control**: Precise control based on `loginSessionId`

**Implementation**:
```java
LoginSessionInfo sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
if (sessionInfo != null && sessionInfo.getStatus() != SessionStatus.ACTIVE) {
    return Mono.error(new JwtException("Session is not active: " + sessionInfo.getStatus()));
}
```

**Usage Scenarios**:
- User logs in on another device (old session marked as KICKED)
- Session expired or actively logged out by user (status is EXPIRED)

#### 13.4.4 Synergistic Effect of Three-Layer Validation

**Synergistic Mechanism**:
1. **First Layer (Blacklist)**: Immediately invalidate revoked tokens (highest performance)
2. **Second Layer (Signature)**: Verify token legitimacy (standard validation)
3. **Third Layer (Status)**: Determine if loginSession is kicked (core functionality)

**Why Three Layers Are Needed**:
- ✅ **Complementary**: Each layer solves different problems
- ✅ **Performance**: Blacklist checked first, fast fail
- ✅ **Security**: Signature validation prevents forgery, status validation prevents being kicked
- ✅ **Complete**: Three-layer validation covers all scenarios

**Example**:
```
Scenario 1: User logs out
  - First layer (blacklist) → Hit → Reject ✅

Scenario 2: User logs in on another device
  - First layer (blacklist) → Hit → Reject ✅
  - Third layer (status) → KICKED → Reject ✅

Scenario 3: Forged token
  - Second layer (signature) → Invalid signature → Reject ✅

Scenario 4: Normal request
  - First layer (blacklist) → Miss → Continue
  - Second layer (signature) → Pass → Continue
  - Third layer (status) → ACTIVE → Pass ✅
```

---

### 13.5 Why Dual Index Design Is Needed

#### 13.5.1 Backward Compatibility Requirement

**Question**: Why do we need to store by both `sessionId` and `loginSessionId`?

**Answer**:

**Backward Compatibility**:
- Old code may use `sessionId` (jti) to query session
- New code uses `loginSessionId` (sid) to query session
- Need to support both query methods

**Implementation**:
```java
// Store by sessionId (backward compatibility)
redis.opsForValue().set(LOGIN_SESSION_KEY_PREFIX + sessionId, sessionJson, ttl);

// Store by loginSessionId (core functionality)
if (loginSessionId != null && !loginSessionId.isBlank()) {
    redis.opsForValue().set(LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + loginSessionId, sessionJson, ttl);
}
```

#### 13.5.2 Query Performance

**Performance Comparison**:

**Single Index (only sessionId)**:
- Query by `loginSessionId` needs to traverse all sessions (O(n))
- Poor performance, not suitable for high concurrency scenarios

**Dual Index (sessionId + loginSessionId)**:
- Query by `sessionId`: O(1)
- Query by `loginSessionId`: O(1)
- Good performance, suitable for high concurrency scenarios

---

### 13.6 Chapter Summary

**Key Design Decisions**:
1. **Use sid as loginSessionId**: Stable, unchanged on token refresh, Keycloak native support
2. **Mark KICKED instead of deleting**: Audit requirements, troubleshooting, data integrity
3. **HTTP Session storage for loginSessionId**: TokenController validation requirement, prevent token overwrite
4. **Three-layer validation**: Signature validation (prevent forgery), blacklist validation (immediate invalidation), status validation (session management)
5. **Dual index design**: Backward compatibility, query performance

**Design Principles**:
- ✅ **Stability First**: Choose stable identifier (sid)
- ✅ **State Management**: Mark KICKED first, then delete session record
- ✅ **Security First**: Multi-layer validation, ensure security
- ✅ **Performance Optimization**: Dual index design, improve query performance



---

## XIV. Edge Case Handling

> **Chapter Objective**: Understand the handling logic for various edge cases, master how to correctly handle same browser multiple tabs, token refresh, network delay, backward compatibility, and other edge cases.

---

### 14.1 Same Browser Multiple Tabs

#### 14.1.1 Shared loginSessionId

**Scenario**:
- User opens application in multiple tabs of the same browser
- All tabs share the same HTTP Session
- All tabs share the same `loginSessionId`

**Behavior**:
```
Tab 1 opens application
  ↓
Login successful, loginSessionId="sid-001"
  ↓
HTTP Session stores loginSessionId="sid-001"

Tab 2 opens application (same browser)
  ↓
Share the same HTTP Session
  ↓
HTTP Session already has loginSessionId="sid-001"
  ↓
Tab 2 also uses loginSessionId="sid-001"
```

**Key Points**:
- ✅ **Shared Session**: Multiple tabs of the same browser share the same HTTP Session
- ✅ **Shared loginSessionId**: All tabs use the same `loginSessionId`
- ✅ **Normal Behavior**: This is normal browser behavior, no special handling needed

#### 14.1.2 Impact After New Device Login

**Scenario**:
- User uses application in multiple tabs of the same browser
- User logs in on a new device
- New device login will kick out all tabs of the old device

**Flow**:
```
Tab 1, Tab 2, Tab 3 (same browser, loginSessionId="sid-001")
  ↓
User logs in on new device (loginSessionId="sid-002")
  ↓
Old device's session marked as KICKED, then session deleted and token added to blacklist
  ↓
Old device's token added to blacklist
  ↓
Publish SESSION_KICKED event (loginSessionId="sid-001")
  ↓
All requests from Tab 1, Tab 2, Tab 3
  ├─ [First layer] Blacklist check → Hit → 401 Unauthorized
  └─ All tabs kicked offline
```

**Key Points**:
- ✅ **All Tabs Affected**: All tabs of the same browser share the same `loginSessionId`, all will be kicked offline
- ✅ **As Expected**: This is normal behavior for single device login
- ✅ **User Experience**: Frontend should prompt user "Account logged in on another device"

---

### 14.2 Token Refresh

#### 14.2.1 refresh_token Handling

**Scenario**:
- After user logs in, access_token expires
- Frontend uses refresh_token to refresh access_token
- New token's `jti` may change, but `sid` remains unchanged

**Flow**:
```
User logs in
  Token 1: jti="jti-001", sid="sid-xyz789"
  ↓
Token expires
  ↓
Frontend uses refresh_token to refresh
  ↓
  Token 2: jti="jti-002", sid="sid-xyz789"  ← sid unchanged!
  ↓
Frontend uses new token to access resources
  ↓
JWT validation
  ├─ Extract loginSessionId="sid-xyz789"
  ├─ Query SessionRegistry
  ├─ Find session (loginSessionId="sid-xyz789")
  └─ Status is ACTIVE → Pass ✅
```

**Key Points**:
- ✅ **sid Unchanged**: When token is refreshed, `sid` remains unchanged
- ✅ **Normal Use**: New token can be used normally
- ✅ **No Special Handling**: System automatically handles token refresh

#### 14.2.2 Skip Sessions with Same loginSessionId

**Scenario**:
- After user logs in, token is refreshed
- New token's `jti` may change, but `loginSessionId` remains unchanged
- If new token triggers login flow again, should not kick itself

**Code Implementation**:
```java
// SessionRegistry.registerLoginSessionEnforceSingle()
for (LoginSessionInfo oldSession : activeSessions) {
    // Skip self (if new session's loginSessionId equals old session's, it's a token refresh for the same login session)
    if (sessionInfo.getLoginSessionId() != null 
            && sessionInfo.getLoginSessionId().equals(oldSession.getLoginSessionId())) {
        log.debug("Skip session with same loginSessionId: loginSessionId={}", sessionInfo.getLoginSessionId());
        continue; // Skip, don't kick
    }
    
    // Update status to KICKED
    updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
}
```

**Key Logic**:
- ✅ **Skip Self**: If new session's `loginSessionId` equals old session's, skip (don't kick)
- ✅ **Only Kick Other Logins**: Only kick sessions with different `loginSessionId`
- ✅ **Support Token Refresh**: Token refresh won't cause self to be kicked offline

**Example**:
```
User A logs in on Device 1
  loginSessionId="sid-001", sessionId="jti-001", status=ACTIVE
  ↓
Token refresh (may be frontend auto refresh, or re-login)
  ↓
New session: loginSessionId="sid-001", sessionId="jti-002"
  ↓
registerLoginSessionEnforceSingle()
  ├─ Query ACTIVE sessions → [Old session (loginSessionId="sid-001")]
  ├─ New session's loginSessionId="sid-001" == Old session's loginSessionId="sid-001"
  ├─ Skip, don't kick ✅
  └─ Register new session (update sessionId="jti-002")
```

#### 14.2.3 New Token's loginSessionId

**Validation**:
- ✅ **Remains Unchanged**: When token is refreshed, `loginSessionId` (sid) remains unchanged
- ✅ **Normal Use**: New token can be used normally
- ✅ **No Re-login Needed**: User doesn't need to re-login

---

### 14.3 Network Delay/Retry

#### 14.3.1 Race Condition Handling

**Scenario**:
- User logs in on two devices almost simultaneously
- Two login requests may arrive at server simultaneously
- Race condition may occur

**Problem**:
```
Device 1 login request arrives (time T1)
  ↓
Query ACTIVE sessions → []
  ↓
Register new session (loginSessionId="sid-001")

Device 2 login request arrives (time T2, T2 ≈ T1)
  ↓
Query ACTIVE sessions → [] (Device 1's session may not be registered yet)
  ↓
Register new session (loginSessionId="sid-002")
  ↓
Result: Both sessions are ACTIVE (wrong!)
```

**Solution**:

**Solution 1: Redis Atomic Operations**
- Use Redis atomic operations (such as SETNX, WATCH/MULTI/EXEC)
- Ensure only one ACTIVE session per user

**Current Implementation**:
- ⚠️ **Non-atomic Operation**: Current implementation is not atomic
- ✅ **Eventual Consistency**: Through subsequent JWT validation and status checks, ensure eventual consistency
- ✅ **Small Actual Impact**: Probability of race condition is very low (requires almost simultaneous login)

**Improvement Suggestions**:
- Can use Redis `SETNX` or `WATCH/MULTI/EXEC` to implement atomic operations
- Or use distributed lock (such as Redisson)

#### 14.3.2 Eventual Consistency

**Principle**:
- ✅ **Eventual Consistency**: System will eventually reach consistent state
- ✅ **Multi-layer Validation**: Ensure security through multi-layer validation

**Implementation**:
```
Even if race condition occurs (both sessions are ACTIVE)
  ↓
Subsequent request's JWT validation
  ├─ Query SessionRegistry
  ├─ If multiple ACTIVE sessions found
  ├─ Can choose:
  │     ├─ Only allow latest session (by timestamp)
  │     └─ Or mark all old sessions as KICKED
  └─ Ensure eventually only one ACTIVE session
```

**Current Implementation**:
- ✅ **JWT Validation**: Every request checks session status
- ✅ **Status Check**: If session status is not ACTIVE, reject access
- ✅ **Eventual Consistency**: Even if race condition occurs, will eventually reach consistent state

---

### 14.4 Backward Compatibility

#### 14.4.1 Old Token Without loginSessionId

**Scenario**:
- Old token may not have `sid` claim
- Old token may not be registered in SessionRegistry
- Need backward compatibility, avoid affecting existing functionality

**Handling Strategy**:

**Strategy 1: During JWT Validation**
```java
// JwtDecoderConfig.checkSessionStatus()
if (loginSessionId == null || loginSessionId.isBlank()) {
    log.warn("JWT does not have loginSessionId, skip status check (backward compatibility)");
    return Mono.empty(); // Skip, don't reject
}
```

**Strategy 2: During TokenController Validation**
```java
// TokenController.getToken()
if (loginSessionId == null || loginSessionId.isBlank()) {
    log.warn("JWT does not have loginSessionId, skip validation (backward compatibility)");
    // Directly return token
    return Mono.just(ResponseEntity.ok(result));
}
```

**Strategy 3: During SessionRegistry Query**
```java
// SessionRegistry.getLoginSessionByLoginSessionId()
if (loginSessionId == null || loginSessionId.isBlank()) {
    return null; // Return null, don't throw exception
}
```

**Key Points**:
- ✅ **Skip Check**: If no `loginSessionId`, skip related checks
- ✅ **Don't Reject Access**: Backward compatibility, don't reject access
- ✅ **Log Warning**: Record warning logs for troubleshooting

#### 14.4.2 Session Not Found in SessionRegistry

**Scenario**:
- Old token may not be registered in SessionRegistry
- On first login, SessionRegistry may not have data yet
- Need backward compatibility

**Handling Strategy**:

**Strategy 1: During JWT Validation**
```java
// JwtDecoderConfig.checkSessionStatus()
LoginSessionInfo sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
if (sessionInfo == null) {
    log.warn("Session not found in SessionRegistry, skip status check");
    return Mono.empty(); // Skip, don't reject
}
```

**Strategy 2: During TokenController Validation**
```java
// TokenController.getToken()
var sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
if (sessionInfo == null) {
    log.warn("Session not found in SessionRegistry");
    // Continue returning token (backward compatibility)
}
```

**Key Points**:
- ✅ **Skip Check**: If session not found, skip related checks
- ✅ **Don't Reject Access**: Backward compatibility, don't reject access
- ✅ **Log Warning**: Record warning logs

#### 14.4.3 Handling Status as null

**Scenario**:
- Old data may not have `status` field
- Need backward compatibility, default to ACTIVE

**Handling Strategy**:
```java
// SessionRegistry.getLoginSessionByLoginSessionId()
if (info != null && info.getStatus() == null) {
    info.setStatus(SessionStatus.ACTIVE); // Default to ACTIVE
}
```

**Key Points**:
- ✅ **Default ACTIVE**: If status is null, default to ACTIVE
- ✅ **Backward Compatibility**: Old data can be used normally
- ✅ **Auto Fix**: Automatically set default value when reading

#### 14.4.4 No loginSessionId in HTTP Session

**Scenario**:
- Old login may not have stored `loginSessionId` in HTTP Session
- Need backward compatibility

**Handling Strategy**:
```java
// TokenController.getToken()
String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
if (sessionLoginSessionId == null || sessionLoginSessionId.isBlank()) {
    log.warn("No loginSessionId in HTTP Session, skip Session validation (backward compatibility)");
    // Skip validation, continue returning token
}
```

**Key Points**:
- ✅ **Skip Validation**: If no `loginSessionId` in Session, skip validation
- ✅ **Don't Reject Access**: Backward compatibility, don't reject access
- ✅ **Log Warning**: Record warning logs

---

### 14.5 Other Edge Cases

#### 14.5.1 Same User Simultaneous Login (Race Condition)

**Scenario**:
- User logs in on two devices almost simultaneously
- May result in both sessions being ACTIVE

**Handling**:
- ⚠️ **Current Implementation**: Non-atomic operation, race condition may occur
- ✅ **Eventual Consistency**: Through subsequent JWT validation and status checks, ensure eventual consistency
- ✅ **Improvement Suggestions**: Can use Redis atomic operations or distributed lock

#### 14.5.2 Token Expired But Not Refreshed

**Scenario**:
- Token expired, but frontend didn't refresh in time
- User continues using expired token

**Handling**:
- ✅ **Signature Validation**: JWT signature validation checks expiration time (exp)
- ✅ **Auto Reject**: Expired tokens are automatically rejected
- ✅ **Frontend Handling**: Frontend should automatically refresh token

#### 14.5.3 WebSocket Disconnected But Not Cleaned

**Scenario**:
- WebSocket connection abnormally disconnected
- Record in SessionRegistry may not be cleaned in time

**Handling**:
- ✅ **TTL Auto Expiration**: Redis TTL automatically cleans expired records
- ✅ **Periodic Cleanup**: Can periodically clean expired WebSocket session records
- ✅ **Disconnect Event**: Normal disconnect triggers `SessionDisconnectEvent`, automatically cleans

---

### 14.6 Chapter Summary

**Edge Cases**:
1. **Same Browser Multiple Tabs**: Share `loginSessionId`, all tabs kicked offline after new device login
2. **Token Refresh**: `loginSessionId` remains unchanged, skip sessions with same `loginSessionId`
3. **Network Delay/Retry**: Race condition may occur, guaranteed by eventual consistency
4. **Backward Compatibility**: Old token without `loginSessionId`, session not found, status is null, etc.

**Handling Strategies**:
- ✅ **Skip Check**: Backward compatibility, don't reject access
- ✅ **Log Warning**: Record warning logs for troubleshooting
- ✅ **Default Value Handling**: Default to ACTIVE when status is null
- ✅ **Eventual Consistency**: Ensure eventual consistency through multi-layer validation

**Key Code**:
- `registerLoginSessionEnforceSingle()`: Skip sessions with same `loginSessionId`
- `checkSessionStatus()`: Backward compatibility handling
- `TokenController.getToken()`: Backward compatibility handling



---

## XV. Testing and Verification

> **Chapter Objective**: Master how to test various functions of the single device login system, including functional testing, edge case testing, verification methods, etc.

---

### 15.1 Functional Test Scenarios

#### 15.1.1 Single Device Login Test

**Test Objective**: Verify that when a new device logs in, the old device is automatically kicked offline.

**Test Steps**:

**Step 1: Device 1 Login**
```
1. On Device 1 (Browser 1) access /oauth2/authorization/keycloak
2. Enter username and password, complete login
3. Record Device 1's loginSessionId (obtain from logs or Redis)
```

**Step 2: Device 2 Login**
```
1. On Device 2 (Browser 2) access /oauth2/authorization/keycloak
2. Use the same username and password to log in
3. Record Device 2's loginSessionId
```

**Step 3: Verify Device 1 Kicked Offline**
```
1. On Device 1 try to access an authenticated endpoint (e.g., create room)
2. Should return 401 Unauthorized
3. Check logs, confirm Device 1's session status is KICKED
```

**Verification Points**:
- ✅ Device 1's session status changes to KICKED
- ✅ Device 1's token is added to blacklist
- ✅ Device 1's requests are rejected (401)
- ✅ Device 2 can be used normally

**Log Check**:
```bash
# Gateway logs
grep "单设备登录" gateway.log
# Should see: New login session registered, kicked old sessions count=1

# Check Redis
redis-cli
> GET session:login:loginSession:{Device1's loginSessionId}
# Should see status: "KICKED"
```

#### 15.1.2 Token Refresh Test

**Test Objective**: Verify that when token is refreshed, `loginSessionId` remains unchanged.

**Test Steps**:

**Step 1: Login and Get Token**
```
1. User logs in, obtains access_token and refresh_token
2. Record loginSessionId (extract from JWT's sid claim)
```

**Step 2: Refresh Token**
```
1. Wait for access_token to expire (or manually refresh)
2. Use refresh_token to refresh access_token
3. Obtain new access_token
```

**Step 3: Verify loginSessionId Unchanged**
```
1. Parse new token's JWT
2. Extract sid claim
3. Verify sid is the same as loginSessionId from Step 1
```

**Verification Points**:
- ✅ New token's `sid` is the same as old token's `sid`
- ✅ New token can be used normally
- ✅ Session status is still ACTIVE
- ✅ Won't be kicked offline by itself

**Code Verification**:
```java
// Extract sid from new token
Jwt jwt = jwtDecoder.decode(newToken);
String sid = jwt.getClaim("sid").toString();
// Verify sid is the same as sid at login time
```

#### 15.1.3 WebSocket Disconnect Test

**Test Objective**: Verify that when user logs out or new device logs in, WebSocket connection is disconnected.

**Test Steps**:

**Step 1: Establish WebSocket Connection**
```
1. Log in on Device 1
2. Establish WebSocket connection (using JWT token)
3. Confirm connection successful
```

**Step 2: Trigger Disconnect**
```
Method 1: User logout
  1. On Device 1 access /logout
  2. Observe if WebSocket connection is disconnected

Method 2: New device login
  1. Log in on Device 2 (same user)
  2. Observe if Device 1's WebSocket connection is disconnected
```

**Step 3: Verify Disconnect**
```
1. Check WebSocket connection status (should be disconnected)
2. Check if kick notification received (/queue/system.kick)
3. Check logs, confirm disconnect operation executed
```

**Verification Points**:
- ✅ WebSocket connection is disconnected
- ✅ Kick notification message received
- ✅ Disconnect operation recorded in logs
- ✅ WebSocket session in SessionRegistry is cleaned

**Log Check**:
```bash
# Game-Service logs
grep "收到会话失效事件" game-service.log
# Should see: Received session invalidation event, starting to disconnect user WebSocket connections

grep "已断开用户" game-service.log
# Should see: Disconnected user's WebSocket connection
```

#### 15.1.4 Logout Test

**Test Objective**: Verify that when user logs out, token is added to blacklist, WebSocket connection is disconnected.

**Test Steps**:

**Step 1: Login and Establish Connection**
```
1. User logs in
2. Establish WebSocket connection
3. Record token and loginSessionId
```

**Step 2: Logout**
```
1. Access /logout
2. Confirm logout successful
```

**Step 3: Verify Logout Effect**
```
1. Use old token to access endpoint → Should return 401
2. Check blacklist → Token should exist
3. Check WebSocket connection → Should be disconnected
4. Check session status → Should be EXPIRED or deleted
```

**Verification Points**:
- ✅ Token is added to blacklist
- ✅ Requests using old token are rejected
- ✅ WebSocket connection is disconnected
- ✅ Session status updated to EXPIRED

**Redis Check**:
```bash
redis-cli
> EXISTS jwt:blacklist:{token's hash value}
# Should return 1 (exists)
```

---

### 15.2 Edge Case Testing

#### 15.2.1 Same Browser Multiple Tabs Test

**Test Objective**: Verify that multiple tabs of the same browser share the same `loginSessionId`.

**Test Steps**:

**Step 1: Tab1 Login**
```
1. Log in in browser Tab1
2. Record loginSessionId
```

**Step 2: Tab2 Open**
```
1. Open new Tab (Tab2) in the same browser
2. Access application
3. Check loginSessionId (should be the same as Tab1)
```

**Step 3: New Device Login**
```
1. Log in on Device 2 (same user)
2. Observe if Tab1 and Tab2 are both kicked offline
```

**Verification Points**:
- ✅ Tab1 and Tab2 share the same `loginSessionId`
- ✅ After new device login, Tab1 and Tab2 are both kicked offline
- ✅ All tabs' requests are rejected

#### 15.2.2 Network Delay Test

**Test Objective**: Verify that system still works normally under network delay conditions.

**Test Steps**:

**Step 1: Simulate Network Delay**
```
1. Use network delay tool (e.g., tc) to simulate delay
2. Device 1 logs in
3. Device 2 logs in (almost simultaneously)
```

**Step 2: Verify Eventual Consistency**
```
1. Wait for a period of time
2. Check final state (should have only one ACTIVE session)
3. Verify kicked device cannot access
```

**Verification Points**:
- ✅ Even with network delay, eventually reaches consistent state
- ✅ Only one ACTIVE session
- ✅ Kicked device cannot access

#### 15.2.3 Concurrent Login Test

**Test Objective**: Verify handling during concurrent login.

**Test Steps**:

**Step 1: Concurrent Login**
```
1. Use stress testing tool (e.g., JMeter) to simulate concurrent login
2. Same user logs in on multiple devices almost simultaneously
3. Observe system behavior
```

**Step 2: Verify Results**
```
1. Check final state (should have only one ACTIVE session)
2. Check logs, confirm all logins are processed
3. Verify kicked devices cannot access
```

**Verification Points**:
- ✅ All login requests are processed
- ✅ Eventually only one ACTIVE session
- ✅ System doesn't crash or have exceptions

---

### 15.3 Verification Methods

#### 15.3.1 Log Check

**Key Log Locations**:

**Gateway Logs**:
```bash
# Login success
grep "单设备登录" gateway.log
# Should see: New login session registered, kicked old sessions count=X

# JWT validation
grep "JWT 校验" gateway.log
# Should see: JWT validation passed or session status not ACTIVE

# Token retrieval
grep "Token获取" gateway.log
# Should see: Token validation passed or Token invalidated
```

**Game-Service Logs**:
```bash
# WebSocket connection
grep "WebSocket 连接" game-service.log
# Should see: User WebSocket connection registration completed

# Session invalidation event
grep "收到会话失效事件" game-service.log
# Should see: Received session invalidation event, starting to disconnect user WebSocket connections
```

**Log Format Example**:
```
【单设备登录】新登录会话已注册: userId=user-123, sessionId=jti-001, loginSessionId=sid-xyz789, 踢掉旧会话数=1
【JWT 校验】✅ 会话状态检查通过: loginSessionId=sid-xyz789, status=ACTIVE
【Token获取】✅ Token 验证通过: loginSessionId=sid-xyz789, jti=jti-001
```

#### 15.3.2 Redis Data Check

**Check Login Sessions**:
```bash
redis-cli

# Query all sessions by userId
> SMEMBERS session:login:user:user-123
# Returns: ["jti-001", "jti-002"]

# Query session details by sessionId
> GET session:login:token:jti-001
# Returns: LoginSessionInfo JSON (contains status, loginSessionId, etc.)

# Query session details by loginSessionId
> GET session:login:loginSession:sid-xyz789
# Returns: LoginSessionInfo JSON
```

**Check Blacklist**:
```bash
# Check if token is in blacklist
> EXISTS jwt:blacklist:{token's hash value}
# Returns: 1 (exists) or 0 (not exists)

# View blacklist TTL
> TTL jwt:blacklist:{token's hash value}
# Returns: Remaining seconds
```

**Check WebSocket Sessions**:
```bash
# Query WebSocket sessions by userId
> SMEMBERS session:ws:user:user-123
# Returns: ["ws-session-001", "ws-session-002"]

# Query WebSocket session details by sessionId
> GET session:ws:session:ws-session-001
# Returns: WebSocketSessionInfo JSON
```

#### 15.3.3 Kafka Message Check

**Check Kafka Messages**:
```bash
# Use kafka-console-consumer to consume messages
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic session-invalidated \
  --from-beginning

# Should see messages similar to:
{
  "userId": "user-123",
  "loginSessionId": "sid-xyz789",
  "eventType": "FORCE_LOGOUT",
  "timestamp": 1704067200000,
  "reason": "单设备登录：被新登录踢下线"
}
```

**Verify Message Content**:
- ✅ `userId` is correct
- ✅ `loginSessionId` is correct (if provided)
- ✅ `eventType` is correct (LOGOUT, FORCE_LOGOUT, etc.)
- ✅ `timestamp` is correct
- ✅ `reason` is correct

---

### 15.4 Testing Tools

#### 15.4.1 Browser Developer Tools

**Usage Scenarios**:
- View HTTP requests and responses
- View WebSocket connection status
- View Console logs

**Key Checkpoints**:
- Network tab: View request status codes (401, 200, etc.)
- Console tab: View frontend logs
- Application tab: View Session Storage, Local Storage

#### 15.4.2 Redis Client

**Recommended Tools**:
- RedisInsight (GUI)
- redis-cli (command line tool)

**Usage Scenarios**:
- View session data
- Check blacklist
- Verify data consistency

#### 15.4.3 Kafka Tools

**Recommended Tools**:
- Kafka Tool (GUI)
- kafka-console-consumer (command line tool)

**Usage Scenarios**:
- View Kafka messages
- Verify event publishing
- Check message delay

---

### 15.5 Test Checklist

#### 15.5.1 Functional Test Checklist

- [ ] Single device login: New device logs in, old device kicked offline
- [ ] Token refresh: `loginSessionId` unchanged after refresh
- [ ] WebSocket disconnect: WebSocket connection disconnected on logout or new login
- [ ] Logout: Token added to blacklist, WebSocket disconnected

#### 15.5.2 Edge Case Test Checklist

- [ ] Same browser multiple tabs: Share `loginSessionId`, kicked offline simultaneously
- [ ] Network delay: Eventual consistency guaranteed
- [ ] Concurrent login: Only one ACTIVE session

#### 15.5.3 Verification Method Checklist

- [ ] Log check: Key logs output normally
- [ ] Redis data check: Data stored correctly
- [ ] Kafka message check: Messages published correctly

---

### 15.6 Chapter Summary

**Test Scenarios**:
1. **Functional Testing**: Single device login, token refresh, WebSocket disconnect, logout
2. **Edge Case Testing**: Same browser multiple tabs, network delay, concurrent login
3. **Verification Methods**: Log check, Redis data check, Kafka message check

**Key Verification Points**:
- ✅ Session status correct (ACTIVE, KICKED, EXPIRED)
- ✅ Token blacklist correct
- ✅ WebSocket connection correctly disconnected
- ✅ Kafka messages correctly published

**Testing Tools**:
- Browser developer tools
- Redis client
- Kafka tools



---

## XVI. Common Issues and Solutions

> **Chapter Objective**: Master troubleshooting methods and solutions for common issues, be able to quickly locate and solve actual problems.

---

### 16.1 Old Session Still Valid After Login

#### 16.1.1 Problem Symptom

**Symptom**:
- After Device 1 logs in, Device 2 logs in
- Device 1 can still access authenticated endpoints
- Device 1 is not kicked offline

#### 16.1.2 Root Cause Analysis

**Possible Causes**:

**Cause 1: Blacklist Not Effective**
- Old token not added to blacklist
- Blacklist check logic has issues

**Cause 2: Session Status Not Updated**
- Old session status not marked as KICKED
- SessionRegistry query logic has issues

**Cause 3: JWT Validation Not Executed**
- JWT validation logic not correctly executed
- Session status check skipped

**Cause 4: Token Refresh Bypasses Check**
- Device 1 refreshed token before Device 2 logged in
- New token not in blacklist

#### 16.1.3 Troubleshooting Steps

**Step 1: Check Blacklist**
```bash
# Check if old token is in blacklist
redis-cli
> EXISTS jwt:blacklist:{old token's hash value}
# If returns 0, not added to blacklist
```

**Step 2: Check Session Status**
```bash
# Check old session status
> GET session:login:loginSession:{old loginSessionId}
# Check status field, should be "KICKED"
```

**Step 3: Check Logs**
```bash
# Gateway logs
grep "单设备登录" gateway.log
# Should see: New login session registered, kicked old sessions count=1

# JWT validation logs
grep "JWT 校验" gateway.log
# Should see: Session status not ACTIVE, reject access
```

**Step 4: Check TokenController Validation**
```bash
# Token retrieval logs
grep "Token获取" gateway.log
# Should see: Token validation passed or Token invalidated
```

#### 16.1.4 Solutions

**Solution 1: Ensure Delete Session and Add to Blacklist**
```java
// Check LoginSessionKickHandler.blacklistKickedSessions()
// Ensure all kicked sessions are deleted, tokens are added to blacklist
for (LoginSessionInfo kickedSession : kickedSessions) {
    // 1. Delete session
    sessionRegistry.unregisterLoginSession(kickedSession.getSessionId());
    
    // 2. Add to blacklist
    if (kickedSession.getToken() != null) {
        blacklistService.addToBlacklist(kickedSession.getToken(), ttl);
    }
}
```

**Solution 2: Ensure Session Status Updated**
```java
// Check SessionRegistry.registerLoginSessionEnforceSingle()
// Ensure old session status is marked as KICKED
updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
```

**Solution 3: Ensure JWT Validation Executed**
```java
// Check JwtDecoderConfig.checkSessionStatus()
// Ensure session status check logic is correctly executed
if (sessionInfo.getStatus() != SessionStatus.ACTIVE) {
    return Mono.error(new JwtException("Session is not active: " + status));
}
```

**Solution 4: Handle Token Refresh**
```java
// In TokenController, check token's loginSessionId
// If doesn't match Session's, reject returning token
if (!loginSessionId.equals(sessionLoginSessionId)) {
    return 401; // token has been overwritten
}
```

---

### 16.2 Token Retrieval Returns Other User's Token

#### 16.2.1 Problem Symptom

**Symptom**:
- Device 1 calls `/token` endpoint
- Returned token belongs to Device 2 (another device)
- Device 1 can use this token to access resources

#### 16.2.2 Root Cause Analysis

**Root Cause**:
- `OAuth2AuthorizedClientService` stores by `userId`
- New login of the same user overwrites old login's `OAuth2AuthorizedClient`
- When Device 1 calls `/token`, gets Device 2's token

**Detailed Flow**:
```
Device 1 logs in
  OAuth2AuthorizedClientService[userId="user-A"] = Client1

Device 2 logs in
  OAuth2AuthorizedClientService[userId="user-A"] = Client2  ← Overwritten!

Device 1 calls /token
  authorizedClientManager.authorize() gets Client2
  Returns Client2's token (Device 2's token)
```

#### 16.2.3 Troubleshooting Steps

**Step 1: Check TokenController Logs**
```bash
# View token retrieval logs
grep "Token获取" gateway.log
# Should see: Token's loginSessionId doesn't match HTTP Session
```

**Step 2: Check HTTP Session**
```bash
# Check loginSessionId stored in Device 1's HTTP Session
# Should not match returned token's loginSessionId
```

**Step 3: Check OAuth2AuthorizedClient**
```bash
# Check data in OAuth2AuthorizedClientService
# Should see: userId="user-A" corresponds to Device 2's Client
```

#### 16.2.4 Solutions

**Solution 1: HTTP Session Validation (Implemented)**
```java
// TokenController.getToken()
String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
if (!loginSessionId.equals(sessionLoginSessionId)) {
    return 401; // token has been overwritten
}
```

**Solution 2: Token User Validation (Implemented)**
```java
// TokenController.getToken()
String tokenUserId = jwt.getSubject();
if (!tokenUserId.equals(currentUserId)) {
    return 401; // token doesn't belong to current user
}
```

**Solution 3: SessionRegistry Validation (Implemented)**
```java
// TokenController.getToken()
var sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
if (sessionInfo.getStatus() != SessionStatus.ACTIVE) {
    return 401; // session status not ACTIVE
}
```

**Key Points**:
- ✅ On login, store `loginSessionId` in HTTP Session
- ✅ When getting token, validate token's `loginSessionId` matches Session's
- ✅ If not matching, reject returning token

---

### 16.3 WebSocket Connection Not Disconnected

#### 16.3.1 Problem Symptom

**Symptom**:
- User logs out or new device logs in
- Old device's WebSocket connection still maintained
- Old device can still receive messages

#### 16.3.2 Root Cause Analysis

**Possible Causes**:

**Cause 1: Kafka Event Not Published**
- On logout or new login, session invalidation event not published
- Event publishing failed

**Cause 2: Kafka Event Not Consumed**
- Event published, but Game-Service didn't consume
- Kafka consumer not started or misconfigured

**Cause 3: WebSocket Query Failed**
- Query WebSocket sessions based on `loginSessionId` failed
- Query logic has issues

**Cause 4: Disconnect Operation Failed**
- Sending kick notification failed
- Force disconnect failed

#### 16.3.3 Troubleshooting Steps

**Step 1: Check Kafka Event Publishing**
```bash
# Check Gateway logs
grep "会话失效事件发布" gateway.log
# Should see: Session invalidation event published successfully

# Check Kafka messages
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic session-invalidated \
  --from-beginning
# Should see session invalidation event messages
```

**Step 2: Check Kafka Event Consumption**
```bash
# Check Game-Service logs
grep "收到会话失效事件" game-service.log
# Should see: Received session invalidation event, starting to disconnect user WebSocket connections
```

**Step 3: Check WebSocket Session Query**
```bash
# Check WebSocket sessions in Redis
redis-cli
> GET session:ws:session:{wsSessionId}
# Should find corresponding WebSocket session

# Check query by loginSessionId
> GET session:ws:loginSession:{loginSessionId}
# Should find corresponding WebSocket session
```

**Step 4: Check Disconnect Operation**
```bash
# Check Game-Service logs
grep "已断开用户" game-service.log
# Should see: Disconnected user's WebSocket connection
```

#### 16.3.4 Solutions

**Solution 1: Ensure Event Publishing**
```java
// Check LoginSessionKickHandler.publishKickedEvent()
// Ensure event is correctly published
SessionInvalidatedEvent event = SessionInvalidatedEvent.of(
    userId, loginSessionId, EventType.FORCE_LOGOUT, reason
);
sessionEventPublisher.publishSessionInvalidated(event);
```

**Solution 2: Ensure Event Consumption**
```java
// Check SessionEventConsumer.consumeSessionInvalidated()
// Ensure event is correctly consumed
@KafkaListener(topics = "${session.kafka.topic:session-invalidated}")
public void consumeSessionInvalidated(String message, Acknowledgment ack) {
    // Process event
}
```

**Solution 3: Ensure WebSocket Query**
```java
// Check SessionInvalidatedListener.onSessionInvalidated()
// Ensure query based on loginSessionId
if (loginSessionId != null && !loginSessionId.isBlank()) {
    List<WebSocketSessionInfo> wsSessions = 
        sessionRegistry.getWebSocketSessionsByLoginSessionId(loginSessionId);
}
```

**Solution 4: Ensure Disconnect Operation**
```java
// Check WebSocketDisconnectHelper
// Ensure send kick notification and force disconnect
disconnectHelper.sendKickMessage(userId, sessionId, reason);
disconnectHelper.forceDisconnect(sessionId);
```

---

### 16.4 Kafka Event Not Received

#### 16.4.1 Problem Symptom

**Symptom**:
- On logout or new login, Kafka event not received
- Game-Service didn't execute disconnect operation
- WebSocket connection not disconnected

#### 16.4.2 Root Cause Analysis

**Possible Causes**:

**Cause 1: Event Not Published**
- Publishing logic not executed
- Publishing failed (exception caught)

**Cause 2: Kafka Configuration Error**
- Topic configuration error
- Bootstrap servers configuration error

**Cause 3: Consumer Not Started**
- Kafka consumer not started
- Consumer configuration error

**Cause 4: Message Serialization Failed**
- Event object serialization failed
- Message format error

#### 16.4.3 Troubleshooting Steps

**Step 1: Check Event Publishing Logs**
```bash
# Gateway logs
grep "会话失效事件发布" gateway.log
# Should see: Session invalidation event published successfully or failed
```

**Step 2: Check Kafka Configuration**
```yaml
# application.yml
session:
  kafka:
    bootstrap-servers: localhost:9092
    topic: session-invalidated
```

**Step 3: Check Kafka Messages**
```bash
# Use kafka-console-consumer to consume messages
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic session-invalidated \
  --from-beginning
# Should see messages
```

**Step 4: Check Consumer Logs**
```bash
# Game-Service logs
grep "会话事件消费者初始化" game-service.log
# Should see: Session event consumer initialization completed, found X listeners

grep "收到会话失效事件" game-service.log
# Should see: Received session invalidation event
```

#### 16.4.4 Solutions

**Solution 1: Ensure Event Publishing**
```java
// Check SessionEventPublisher.publishSessionInvalidated()
// Ensure event is correctly published, log errors
try {
    String message = JSON.toJSONString(event);
    kafkaTemplate.send(topic, event.getUserId(), message);
} catch (Exception e) {
    log.error("Exception publishing session invalidation event", e);
}
```

**Solution 2: Check Kafka Configuration**
```yaml
# Ensure Kafka configuration is correct
session:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    topic: ${SESSION_KAFKA_TOPIC:session-invalidated}
```

**Solution 3: Check Consumer Configuration**
```java
// Ensure consumer is correctly configured
@KafkaListener(
    topics = "${session.kafka.topic:session-invalidated}",
    containerFactory = "sessionKafkaListenerContainerFactory"
)
public void consumeSessionInvalidated(String message, Acknowledgment ack) {
    // Process event
}
```

**Solution 4: Check Listener Registration**
```java
// Ensure SessionEventListener implementation class is correctly registered
@Component
public class SessionInvalidatedListener implements SessionEventListener {
    // Implement onSessionInvalidated()
}
```

---

### 16.5 Other Common Issues

#### 16.5.1 Issue: No sid in JWT

**Symptom**:
- JWT doesn't have `sid` claim
- `loginSessionId` is null
- Session status check skipped

**Solution**:
- ✅ Check Keycloak configuration, ensure `sid` claim is included in JWT
- ✅ Backward compatibility: If no `sid`, try using `session_state`
- ✅ Log warning for troubleshooting

#### 16.5.2 Issue: Session Not Found in SessionRegistry

**Symptom**:
- During JWT validation, session not found in SessionRegistry
- Session status check skipped

**Solution**:
- ✅ Backward compatibility: If session not found, skip status check
- ✅ Check if session is correctly registered on login
- ✅ Check Redis connection and data storage

#### 16.5.3 Issue: loginSessionId Changes After Token Refresh

**Symptom**:
- After token refresh, `loginSessionId` changes
- Cannot associate with the same login session

**Solution**:
- ✅ Use `sid` as `loginSessionId` (stable and unchanged)
- ✅ Don't use `session_state` (may change)
- ✅ Check Keycloak configuration, ensure `sid` is stable

---

### 16.6 Problem Troubleshooting Flow

```
Problem occurs
  ↓
Check logs
  ├─ Gateway logs → Login, JWT validation, Token retrieval
  ├─ Game-Service logs → WebSocket connection, event processing
  └─ Kafka logs → Event publishing, consumption
  ↓
Check Redis data
  ├─ Session data → Status, loginSessionId
  ├─ Blacklist → Whether token exists
  └─ WebSocket sessions → Connection status
  ↓
Check Kafka messages
  ├─ Whether event is published
  ├─ Whether event is consumed
  └─ Whether message content is correct
  ↓
Locate problem
  ├─ Blacklist not effective → Check publishing logic
  ├─ Session status not updated → Check registration logic
  ├─ WebSocket not disconnected → Check event processing
  └─ Kafka event not received → Check configuration and consumption
  ↓
Solve problem
  ├─ Fix code logic
  ├─ Fix configuration
  └─ Fix data
```

---

### 16.7 Chapter Summary

**Common Issues**:
1. **Old session still valid after login**: Blacklist not effective, session status not updated, JWT validation not executed
2. **Token retrieval returns other user's token**: OAuth2AuthorizedClientService overwrite problem
3. **WebSocket connection not disconnected**: Kafka event not published/consumed, query failed, disconnect operation failed
4. **Kafka event not received**: Event not published, configuration error, consumer not started

**Troubleshooting Methods**:
- ✅ Log check: Gateway, Game-Service, Kafka
- ✅ Redis data check: Session data, blacklist, WebSocket sessions
- ✅ Kafka message check: Event publishing, consumption, message content

**Solutions**:
- ✅ Ensure blacklist is effective
- ✅ Ensure session status is updated
- ✅ Ensure JWT validation is executed
- ✅ Ensure HTTP Session validation
- ✅ Ensure Kafka event publishing and consumption

**Next Step**: After understanding common issues and solutions, we can continue learning performance optimization recommendations to understand how to optimize system performance.



---

## XVII. Performance Optimization Recommendations

> **Chapter Objective**: Understand how to optimize system performance, including Redis query optimization, Kafka message optimization, log optimization, etc.

---

### 17.1 Redis Query Optimization

#### 17.1.1 Index Design

**Current Design**:
- ✅ **Dual Index**: Build indexes by both `sessionId` and `loginSessionId`
- ✅ **O(1) Query**: Both query types are O(1) time complexity

**Optimization Recommendations**:

**Recommendation 1: Use Hash Structure to Store Session Information**
```java
// Current implementation: String stores JSON
redis.opsForValue().set(key, json, ttl);

// Optimization suggestion: Hash storage (if only need to query partial fields)
redis.opsForHash().putAll(key, map);
redis.expire(key, ttl);
```

**Advantages**:
- ✅ Can query only needed fields (reduce network transmission)
- ✅ Can partially update (no need to serialize entire object)

**Recommendation 2: Use Sorted Set to Store User Session List**
```java
// Current implementation: Set stores sessionId list
redis.opsForSet().add(userKey, sessionId);

// Optimization suggestion: Sorted Set storage (sorted by time)
redis.opsForZSet().add(userKey, sessionId, timestamp);
```

**Advantages**:
- ✅ Can query sorted by time
- ✅ Can easily clean expired sessions

#### 17.1.2 Batch Query

**Current Implementation**:
```java
// Query sessions one by one
for (String sessionId : sessionIds) {
    LoginSessionInfo info = getLoginSession(sessionId);
}
```

**Optimization Suggestion**:
```java
// Use Pipeline for batch query
List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
    for (String sessionId : sessionIds) {
        connection.get((LOGIN_SESSION_KEY_PREFIX + sessionId).getBytes());
    }
    return null;
});
```

**Advantages**:
- ✅ Reduce network round trips
- ✅ Improve query performance (especially in high concurrency scenarios)

#### 17.1.3 Cache Strategy

**Recommendation 1: Local Cache for Hot Data**
```java
// Use Caffeine or Guava Cache to cache hot sessions
Cache<String, LoginSessionInfo> cache = Caffeine.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .build();

// Query local cache first when querying
LoginSessionInfo info = cache.get(loginSessionId, key -> {
    return sessionRegistry.getLoginSessionByLoginSessionId(key);
});
```

**Advantages**:
- ✅ Reduce Redis queries
- ✅ Improve response speed

**Recommendation 2: Asynchronous Cache Update**
```java
// Asynchronously update cache when querying
LoginSessionInfo info = getLoginSessionByLoginSessionId(loginSessionId);
CompletableFuture.runAsync(() -> {
    cache.put(loginSessionId, info);
});
```

**Advantages**:
- ✅ Don't block main flow
- ✅ Improve concurrent performance

---

### 17.2 Kafka Message Optimization

#### 17.2.1 Batch Sending

**Current Implementation**:
```java
// Send messages one by one
for (LoginSessionInfo kickedSession : kickedSessions) {
    SessionInvalidatedEvent event = ...;
    sessionEventPublisher.publishSessionInvalidated(event);
}
```

**Optimization Suggestion**:
```java
// Batch send messages
List<ProducerRecord<String, String>> records = new ArrayList<>();
for (LoginSessionInfo kickedSession : kickedSessions) {
    SessionInvalidatedEvent event = ...;
    String message = JSON.toJSONString(event);
    records.add(new ProducerRecord<>(topic, event.getUserId(), message));
}
kafkaTemplate.send(records);
```

**Advantages**:
- ✅ Reduce network round trips
- ✅ Improve sending performance

#### 17.2.2 Asynchronous Processing

**Current Implementation**:
```java
// Synchronous sending (blocking)
CompletableFuture<SendResult<String, String>> future = 
    kafkaTemplate.send(topic, key, message);
future.whenComplete((result, ex) -> {
    // Process result
});
```

**Optimization Suggestion**:
```java
// Use thread pool for asynchronous processing
ExecutorService executor = Executors.newFixedThreadPool(10);
for (LoginSessionInfo kickedSession : kickedSessions) {
    executor.submit(() -> {
        SessionInvalidatedEvent event = ...;
        sessionEventPublisher.publishSessionInvalidated(event);
    });
}
```

**Advantages**:
- ✅ Don't block main flow
- ✅ Improve concurrent performance

#### 17.2.3 Error Retry

**Current Implementation**:
```java
// Simple error handling
try {
    kafkaTemplate.send(topic, key, message);
} catch (Exception e) {
    log.error("Exception publishing session invalidation event", e);
}
```

**Optimization Suggestion**:
```java
// Use retry mechanism
@Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 1000))
public void publishSessionInvalidated(SessionInvalidatedEvent event) {
    try {
        kafkaTemplate.send(topic, key, message);
    } catch (Exception e) {
        log.error("Exception publishing session invalidation event", e);
        throw e; // Throw exception to trigger retry
    }
}
```

**Advantages**:
- ✅ Automatically retry failed messages
- ✅ Improve message reliability

---

### 17.3 Log Optimization

#### 17.3.1 Log Level

**Current Implementation**:
```java
// Heavy use of INFO level logs
log.info("【单设备登录】新登录会话已注册: userId={}, sessionId={}, loginSessionId={}", ...);
log.info("【JWT 校验】开始检查会话状态: sub={}, loginSessionId={}", ...);
```

**Optimization Suggestion**:
```java
// Use DEBUG level for detailed logs
log.debug("【单设备登录】新登录会话已注册: userId={}, sessionId={}, loginSessionId={}", ...);
log.debug("【JWT 校验】开始检查会话状态: sub={}, loginSessionId={}", ...);

// Use INFO level for key events
log.info("【单设备登录】用户 {} 登录，踢掉 {} 个旧会话", userId, kickedCount);
log.info("【JWT 校验】会话状态检查失败: loginSessionId={}, status={}", loginSessionId, status);
```

**Advantages**:
- ✅ Reduce log volume
- ✅ Improve log readability

#### 17.3.2 Log Format

**Current Implementation**:
```java
// Simple log format
log.info("【单设备登录】新登录会话已注册: userId={}, sessionId={}, loginSessionId={}", ...);
```

**Optimization Suggestion**:
```java
// Structured log format (JSON)
log.info("{\"event\":\"login\",\"userId\":\"{}\",\"sessionId\":\"{}\",\"loginSessionId\":\"{}\"}", ...);
```

**Advantages**:
- ✅ Easy for log analysis tools to process
- ✅ Easy to query and statistics

#### 17.3.3 Log Volume Control

**Recommendation 1: Conditional Logging**
```java
// Only log when needed
if (log.isDebugEnabled()) {
    log.debug("Detailed log: {}", expensiveOperation());
}
```

**Recommendation 2: Sampling Logs**
```java
// Sample logging (e.g., log once every 100 times)
private int logCounter = 0;
if (++logCounter % 100 == 0) {
    log.info("Sampling log: {}", data);
}
```

**Recommendation 3: Asynchronous Logging**
```java
// Use asynchronous logging framework (e.g., Logback AsyncAppender)
// Don't block main thread
```

---

### 17.4 JWT Validation Optimization

#### 17.4.1 Blacklist Check Optimization

**Current Implementation**:
```java
// Query Redis on every request
jwtBlacklistService.isBlacklisted(token)
    .flatMap(blacklisted -> {
        // Process result
    });
```

**Optimization Suggestion**:
```java
// Use local cache (Bloom Filter)
BloomFilter<String> blacklistFilter = BloomFilter.create(
    Funnels.stringFunnel(Charset.defaultCharset()),
    1000000, // Expected element count
    0.01     // False positive rate
);

// Query Bloom Filter first when querying
if (blacklistFilter.mightContain(token)) {
    // Then query Redis to confirm
    return jwtBlacklistService.isBlacklisted(token);
} else {
    // Not in blacklist
    return Mono.just(false);
}
```

**Advantages**:
- ✅ Reduce Redis queries (most requests don't need to query Redis)
- ✅ Improve performance

#### 17.4.2 Session Status Check Optimization

**Current Implementation**:
```java
// Query Redis on every request
LoginSessionInfo sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
```

**Optimization Suggestion**:
```java
// Use local cache
Cache<String, LoginSessionInfo> cache = Caffeine.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(1, TimeUnit.MINUTES)
    .build();

// Query local cache first when querying
LoginSessionInfo sessionInfo = cache.get(loginSessionId, key -> {
    return sessionRegistry.getLoginSessionByLoginSessionId(key);
});
```

**Advantages**:
- ✅ Reduce Redis queries
- ✅ Improve response speed

---

### 17.5 Database Optimization

#### 17.5.1 Redis Connection Pool Optimization

**Recommendation**:
```yaml
# application.yml
spring:
  redis:
    lettuce:
      pool:
        max-active: 20    # Maximum connections
        max-idle: 10      # Maximum idle connections
        min-idle: 5       # Minimum idle connections
        max-wait: 1000    # Maximum wait time (milliseconds)
```

#### 17.5.2 Redis Cluster Optimization

**Recommendation**:
- ✅ Use Redis cluster to improve availability and performance
- ✅ Use Redis Sentinel for high availability
- ✅ Reasonably set sharding strategy

---

### 17.6 Chapter Summary

**Optimization Directions**:
1. **Redis Query Optimization**: Index design, batch query, cache strategy
2. **Kafka Message Optimization**: Batch sending, asynchronous processing, error retry
3. **Log Optimization**: Log level, log format, log volume control
4. **JWT Validation Optimization**: Blacklist check optimization, session status check optimization

**Key Optimization Points**:
- ✅ Use local cache to reduce Redis queries
- ✅ Use batch operations to reduce network round trips
- ✅ Use asynchronous processing to improve concurrent performance
- ✅ Reasonably set log levels to reduce log volume



---

## XVIII. Monitoring and Operations

> **Chapter Objective**: Understand how to monitor system running status, including key metrics, log auditing, alert rules, etc.

---

### 18.1 Key Metrics

#### 18.1.1 ACTIVE Session Count

**Metric Description**:
- Number of login sessions in ACTIVE status in current system
- Reflects system's online user count

**Monitoring Method**:
```bash
# Use Redis command to count
redis-cli
> EVAL "
local count = 0
local keys = redis.call('KEYS', 'session:login:user:*')
for _, key in ipairs(keys) do
    local sessionIds = redis.call('SMEMBERS', key)
    for _, sessionId in ipairs(sessionIds) do
        local session = redis.call('GET', 'session:login:token:' .. sessionId)
        if session then
            local info = cjson.decode(session)
            if info.status == 'ACTIVE' then
                count = count + 1
            end
        end
    end
end
return count
" 0
```

**Monitoring Tools**:
- Prometheus + Grafana
- Custom monitoring scripts

**Alert Threshold**:
- Exceeds expected value: May be abnormal (e.g., large number of bot logins)
- Below expected value: May be system abnormal

#### 18.1.2 Kick Count

**Metric Description**:
- Number of sessions kicked offline per unit time
- Reflects single device login activity

**Monitoring Method**:
```bash
# Count sessions in KICKED status
redis-cli
> EVAL "
local count = 0
local keys = redis.call('KEYS', 'session:login:user:*')
for _, key in ipairs(keys) do
    local sessionIds = redis.call('SMEMBERS', key)
    for _, sessionId in ipairs(sessionIds) do
        local session = redis.call('GET', 'session:login:token:' .. sessionId)
        if session then
            local info = cjson.decode(session)
            if info.status == 'KICKED' then
                count = count + 1
            end
        end
    end
end
return count
" 0
```

**Monitoring Tools**:
- Log analysis tools (e.g., ELK)
- Custom monitoring scripts

**Alert Threshold**:
- Large number of kicks in short time: May be abnormal (e.g., account compromised)

#### 18.1.3 Blacklist Hit Rate

**Metric Description**:
- Hit rate of blacklist check during JWT validation
- Reflects blacklist effectiveness

**Monitoring Method**:
```java
// Statistics in JwtDecoderConfig
private AtomicLong blacklistHits = new AtomicLong(0);
private AtomicLong blacklistChecks = new AtomicLong(0);

public Mono<Jwt> jwtDecoder(String token) {
    blacklistChecks.incrementAndGet();
    return jwtBlacklistService.isBlacklisted(token)
        .flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                blacklistHits.incrementAndGet();
                return Mono.error(new JwtException("Token has been revoked"));
            }
            // Continue processing
        });
}

// Calculate hit rate
public double getBlacklistHitRate() {
    long checks = blacklistChecks.get();
    if (checks == 0) {
        return 0.0;
    }
    return (double) blacklistHits.get() / checks;
}
```

**Monitoring Tools**:
- Prometheus + Grafana
- Custom monitoring endpoint

**Alert Threshold**:
- Hit rate too high: May be abnormal (e.g., large number of tokens added to blacklist)

#### 18.1.4 Kafka Message Delay

**Metric Description**:
- Time difference from event publishing to consumption
- Reflects Kafka message processing delay

**Monitoring Method**:
```java
// Record timestamp in SessionInvalidatedEvent
public class SessionInvalidatedEvent {
    private Long timestamp; // Event publishing time
    
    // Calculate delay when consuming
    public long getDelay() {
        return System.currentTimeMillis() - timestamp;
    }
}
```

**Monitoring Tools**:
- Kafka monitoring tools (e.g., Kafka Manager)
- Custom monitoring scripts

**Alert Threshold**:
- Delay exceeds threshold (e.g., 5 seconds): May be Kafka consumption abnormal

---

### 18.2 Log Auditing

#### 18.2.1 Login Logs

**Key Logs**:
```java
// LoginSessionKickHandler
log.info("【单设备登录】新登录会话已注册: userId={}, sessionId={}, loginSessionId={}, 踢掉旧会话数={}", 
    userId, sessionId, loginSessionId, kickedCount);
```

**Audit Content**:
- User ID
- Login time
- Login IP
- User-Agent
- Number of old sessions kicked

**Storage Recommendation**:
- Store in database (convenient for query and analysis)
- Retain for a certain period (e.g., 90 days)

#### 18.2.2 Logout Logs

**Key Logs**:
```java
// SecurityConfig
log.info("用户登出: userId={}, loginSessionId={}", userId, loginSessionId);
```

**Audit Content**:
- User ID
- Logout time
- Logout IP
- Logout reason (active logout, timeout, etc.)

**Storage Recommendation**:
- Store in database
- Associate with login logs

#### 18.2.3 Session Status Change Logs

**Key Logs**:
```java
// SessionRegistry
log.info("【会话状态更新】更新会话状态: sessionId={}, loginSessionId={}, status={}", 
    sessionId, loginSessionId, status);
```

**Audit Content**:
- Session ID
- Status change time
- Status before change
- Status after change
- Change reason

**Storage Recommendation**:
- Store in database
- Retain complete status change history

---

### 18.3 Alert Rules

#### 18.3.1 Abnormal Login Detection

**Alert Conditions**:
- Same user logs in multiple times in short time
- Same IP logs in multiple users in short time
- Abnormal geographic location login

**Implementation Method**:
```java
// Detect in LoginSessionKickHandler
public void detectAbnormalLogin(String userId, String ip) {
    // Check login count in short time
    String key = "login:count:" + userId;
    Long count = redis.opsForValue().increment(key);
    redis.expire(key, 1, TimeUnit.HOURS);
    
    if (count > 10) { // More than 10 logins in 1 hour
        log.warn("【异常登录】用户 {} 在1小时内登录 {} 次", userId, count);
        // Send alert
        alertService.sendAlert("异常登录", userId, count);
    }
}
```

**Alert Methods**:
- Email notification
- SMS notification
- Enterprise WeChat/DingTalk notification

#### 18.3.2 Large Number of Kicks Alert

**Alert Conditions**:
- Large number of sessions kicked offline in short time
- Single user kicked multiple times in short time

**Implementation Method**:
```java
// Statistics in LoginSessionKickHandler
private AtomicLong kickedCount = new AtomicLong(0);

public void onAuthenticationSuccess(...) {
    List<LoginSessionInfo> kicked = ...;
    long count = kickedCount.addAndGet(kicked.size());
    
    // Reset counter every minute
    if (count > 100) { // More than 100 kicks in 1 minute
        log.warn("【大量被踢】1分钟内被踢 {} 次", count);
        alertService.sendAlert("大量被踢", count);
    }
}
```

**Alert Methods**:
- Email notification
- SMS notification

#### 18.3.3 Kafka Consumption Delay Alert

**Alert Conditions**:
- Kafka consumption delay exceeds threshold (e.g., 5 seconds)
- Kafka consumption failure rate too high

**Implementation Method**:
```java
// Monitor in SessionEventConsumer
public void consumeSessionInvalidated(String message, Acknowledgment ack) {
    SessionInvalidatedEvent event = JSON.parseObject(message, SessionInvalidatedEvent.class);
    long delay = System.currentTimeMillis() - event.getTimestamp();
    
    if (delay > 5000) { // Delay exceeds 5 seconds
        log.warn("【Kafka延迟】消息延迟 {} 毫秒", delay);
        alertService.sendAlert("Kafka消费延迟", delay);
    }
}
```

**Alert Methods**:
- Email notification
- SMS notification

---

### 18.4 Monitoring Tools

#### 18.4.1 Prometheus + Grafana

**Configuration Example**:
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'gateway'
    static_configs:
      - targets: ['localhost:8080']
```

**Monitoring Metrics**:
- ACTIVE session count
- Kick count
- Blacklist hit rate
- JWT validation delay

#### 18.4.2 ELK (Elasticsearch + Logstash + Kibana)

**Configuration Example**:
```yaml
# logstash.conf
input {
  file {
    path => "/var/log/gateway.log"
    codec => json
  }
}

filter {
  if [message] =~ /单设备登录/ {
    mutate {
      add_field => { "event_type" => "login" }
    }
  }
}

output {
  elasticsearch {
    hosts => ["localhost:9200"]
    index => "single-device-login-logs-%{+YYYY.MM.dd}"
  }
}
```

**Usage Scenarios**:
- Log query and analysis
- Abnormal log alerts
- Log visualization

---

### 18.5 Operations Checklist

#### 18.5.1 Daily Checks

- [ ] Check if ACTIVE session count is normal
- [ ] Check if kick count is normal
- [ ] Check if blacklist hit rate is normal
- [ ] Check if Kafka message delay is normal
- [ ] Check if Redis connection is normal
- [ ] Check if Kafka connection is normal

#### 18.5.2 Periodic Checks

- [ ] Clean expired session data
- [ ] Clean expired blacklist data
- [ ] Check log file size
- [ ] Check system resource usage

#### 18.5.3 Fault Handling

- [ ] Check logs to locate problem
- [ ] Check Redis data
- [ ] Check Kafka messages
- [ ] Check system configuration

---

### 18.6 Chapter Summary

**Key Metrics**:
1. **ACTIVE Session Count**: Reflects online user count
2. **Kick Count**: Reflects single device login activity
3. **Blacklist Hit Rate**: Reflects blacklist effectiveness
4. **Kafka Message Delay**: Reflects message processing delay

**Log Auditing**:
- ✅ Login logs: Record login information
- ✅ Logout logs: Record logout information
- ✅ Session status change logs: Record status change history

**Alert Rules**:
- ✅ Abnormal login detection: Detect abnormal login behavior
- ✅ Large number of kicks alert: Detect abnormal kick situations
- ✅ Kafka consumption delay alert: Detect message processing abnormalities

**Monitoring Tools**:
- Prometheus + Grafana: Metric monitoring
- ELK: Log analysis

**Next Step**: After understanding monitoring and operations, we have completed the learning of practice and operations. Next, you can check the appendix section to understand key code file list, configuration instructions, glossary, etc.



---

## Appendix

> **Chapter Objective**: Provide key code file list, configuration instructions, glossary, references, etc., for quick lookup and understanding.

---

### A. Key Code File List

#### A.1 Gateway Service

**Core Files**:

**Login Processing**:
- `apps/gateway/src/main/java/com/gamehub/gateway/handler/LoginSessionKickHandler.java`
  - Login success handler
  - Implements single device login logic
  - Blacklist processing
  - Event publishing

**JWT Validation**:
- `apps/gateway/src/main/java/com/gamehub/gateway/config/JwtDecoderConfig.java`
  - Custom JWT decoder
  - Three-layer validation (blacklist, signature, status)
  - Session status check

**Token Retrieval**:
- `apps/gateway/src/main/java/com/gamehub/gateway/controller/TokenController.java`
  - `/token` endpoint implementation
  - Multi-layer validation (Session, SessionRegistry, status)
  - Prevent token overwrite

**Security Configuration**:
- `apps/gateway/src/main/java/com/gamehub/gateway/config/SecurityConfig.java`
  - Spring Security configuration
  - OAuth2 login configuration
  - Logout processing
  - Event publishing

**Blacklist Service**:
- `apps/gateway/src/main/java/com/gamehub/gateway/service/JwtBlacklistService.java`
  - JWT blacklist management
  - Redis storage

**Configuration Files**:
- `apps/gateway/src/main/resources/application.yml`
  - Spring Security configuration
  - Redis configuration
  - Kafka configuration
  - Session configuration

#### A.2 Game-Service

**Core Files**:

**WebSocket Management**:
- `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/WebSocketSessionManager.java`
  - WebSocket connection management
  - Session registration
  - Single device login processing

**Event Listener**:
- `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/SessionInvalidatedListener.java`
  - Kafka event listener
  - WebSocket disconnect processing

**Disconnect Helper**:
- `apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/WebSocketDisconnectHelper.java`
  - Send kick notification
  - Force disconnect

**Configuration Files**:
- `apps/game-service/src/main/resources/application.yml`
  - Redis configuration
  - Kafka configuration
  - Session configuration

#### A.3 Session-Common Library

**Core Files**:

**Session Registry**:
- `libs/session-common/src/main/java/com/gamehub/session/SessionRegistry.java`
  - Login session management
  - WebSocket session management
  - Single device login implementation

**Data Models**:
- `libs/session-common/src/main/java/com/gamehub/session/model/LoginSessionInfo.java`
  - Login session information
- `libs/session-common/src/main/java/com/gamehub/session/model/WebSocketSessionInfo.java`
  - WebSocket session information
- `libs/session-common/src/main/java/com/gamehub/session/model/SessionStatus.java`
  - Session status enumeration

**Configuration Classes**:
- `libs/session-common/src/main/java/com/gamehub/session/config/SessionRedisConfig.java`
  - Redis configuration
- `libs/session-common/src/main/java/com/gamehub/session/config/SessionCommonAutoConfiguration.java`
  - Auto configuration

#### A.4 Session-Kafka-Notifier Library

**Core Files**:

**Event Publishing**:
- `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/publisher/SessionEventPublisher.java`
  - Session invalidation event publishing
  - Kafka message sending

**Event Consumption**:
- `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/listener/SessionEventConsumer.java`
  - Kafka message consumption
  - Listener invocation

**Event Models**:
- `libs/session-common/src/main/java/com/gamehub/session/event/SessionInvalidatedEvent.java`
  - Session invalidation event
- `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/listener/SessionEventListener.java`
  - Event listener interface

**Configuration Classes**:
- `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/config/SessionKafkaConfig.java`
  - Kafka configuration
- `libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/config/SessionKafkaNotifierAutoConfiguration.java`
  - Auto configuration

---

### B. Configuration Instructions

#### B.1 Spring Security Configuration

**Gateway Service Configuration**:

**File Location**: `apps/gateway/src/main/resources/application.yml`

**Key Configuration**:
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: ${KEYCLOAK_CLIENT_ID:game-hub-client}
            client-secret: ${KEYCLOAK_CLIENT_SECRET:your-secret}
            scope: openid,profile,email
            authorization-grant-type: authorization_code
            redirect-uri: ${KEYCLOAK_REDIRECT_URI:http://localhost:8080/login/oauth2/code/keycloak}
        provider:
          keycloak:
            issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/my-realm}
            user-name-attribute: preferred_username
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/my-realm}
```

**Description**:
- `client-id`: OAuth2 client ID
- `client-secret`: OAuth2 client secret
- `issuer-uri`: Keycloak issuer URI
- `scope`: Requested permission scopes

#### B.2 Redis Configuration

**Session-Common Configuration**:

**File Location**: `apps/gateway/src/main/resources/application.yml`, `apps/game-service/src/main/resources/application.yml`

**Key Configuration**:
```yaml
# session session management configuration
session:
  redis:
    host: ${SESSION_REDIS_HOST:127.0.0.1}
    port: ${SESSION_REDIS_PORT:6379}
    database: ${SESSION_REDIS_DATABASE:0}
    password: ${SESSION_REDIS_PASSWORD:zaqxsw}
```

**Important Notes**:
- ✅ **Unified Redis Database**: All services (Gateway, game-service, system-service, etc.) must use the same Redis database (default database 0)
- ✅ **Session Data Sharing**: Through unified Redis database, services can share session state, achieving cross-service session management
- ✅ **Configuration Method**: Configure uniformly through environment variable `SESSION_REDIS_DATABASE` to ensure all services use the same database
- ✅ **Separate from Business Redis**: Each service also has its own business Redis configuration (`spring.data.redis.database`), used for storing business data, separated from session Redis

**Business Redis Configuration Example** (different for each service):
```yaml
spring:
  data:
    redis:
      database: 1  # Gateway uses database 1
      # database: 2  # game-service uses database 2
      # database: 3  # system-service uses database 3
```

**Session Redis Configuration** (same for all services):
```yaml
session:
  redis:
    database: 0  # All services uniformly use database 0
```

**Description**:
- `host`: Redis host address
- `port`: Redis port
- `database`: Redis database number
- `password`: Redis password

**Redis Key Space**:
```
session:login:user:{userId}                    → Set<sessionId>
session:login:token:{sessionId}                → LoginSessionInfo JSON
session:login:loginSession:{loginSessionId}    → LoginSessionInfo JSON
session:ws:user:{userId}                       → Set<sessionId>
session:ws:session:{sessionId}                 → WebSocketSessionInfo JSON
jwt:blacklist:{tokenHash}                      → "1" (TTL)
```

#### B.3 Kafka Configuration

**Session-Kafka-Notifier Configuration**:

**File Location**: `apps/gateway/src/main/resources/application.yml`, `apps/game-service/src/main/resources/application.yml`

**Key Configuration**:
```yaml
session:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    topic: ${SESSION_KAFKA_TOPIC:session-invalidated}
    consumer:
      group-id: ${SESSION_KAFKA_GROUP_ID:game-service-session-group}
```

**Description**:
- `bootstrap-servers`: Kafka server address
- `topic`: Event topic name
- `group-id`: Consumer group ID

#### B.4 Keycloak Configuration

**Key Configuration**:

**Realm Configuration**:
- Realm name: `my-realm`
- Client ID: `game-hub-client`
- Client type: `public` or `confidential`

**Token Configuration**:
- Access Token validity: 15 minutes (default)
- Refresh Token validity: 30 days (default)
- Ensure JWT contains `sid` claim

**Mapper Configuration** (if needed):
- Add `sid` claim to access_token
- Or use Keycloak's default `sid` claim

---

### C. Glossary

#### C.1 loginSessionId (Login Session ID)

**Definition**: Identifier that remains stable throughout the login lifecycle.

**Source**:
- **Recommended**: Keycloak's `sid` (Session ID) claim
- **Alternative**: Keycloak's `session_state` claim (backward compatibility)

**Characteristics**:
- ✅ Stable and unchanged during one login
- ✅ Remains unchanged when token is refreshed
- ✅ New login generates new `loginSessionId`

**Usage**:
- Single device login (new connection kicks out old)
- Session management
- WebSocket connection management

#### C.2 sessionId (Session ID)

**Definition**: JWT token's `jti` (JWT ID) claim, used to identify a single token.

**Characteristics**:
- ⚠️ May change when token is refreshed
- ⚠️ Each token has its own `jti`
- ✅ Used for backward compatibility

**Usage**:
- Token identification
- Backward compatibility query

#### C.3 jti (JWT ID)

**Definition**: `jti` (JWT ID) claim in JWT standard, used to uniquely identify JWT token.

**Characteristics**:
- ⚠️ May change when token is refreshed (depends on Keycloak configuration)
- ⚠️ Only identifies a single token
- ✅ Complies with JWT standard

**Usage**:
- Token identification
- Backward compatibility

#### C.4 sid (Session ID)

**Definition**: Keycloak's `sid` (Session ID) claim, used to identify Keycloak session.

**Characteristics**:
- ✅ Stable and unchanged throughout login lifecycle
- ✅ Remains unchanged when token is refreshed
- ✅ Complies with OIDC specification

**Usage**:
- Used as `loginSessionId`
- Core identifier for single device login

#### C.5 session_state

**Definition**: Keycloak's `session_state` claim, used to identify Keycloak session state.

**Characteristics**:
- ⚠️ May be unstable or not always available
- ⚠️ May not be in JWT claim in some Keycloak versions
- ✅ Alternative to `sid`

**Usage**:
- Backward compatibility
- Alternative when `sid` is unavailable

#### C.6 SessionStatus (Session Status)

**Definition**: Used to identify the current status of a login session.

**Status Enumeration**:
- `ACTIVE`: Currently valid
- `KICKED`: Kicked offline by subsequent login
- `EXPIRED`: Normal timeout or logout

#### C.7 LoginSessionInfo (Login Session Information)

**Definition**: Complete information of a login session.

**Key Fields**:
- `sessionId`: JWT's `jti`
- `loginSessionId`: Keycloak's `sid`
- `userId`: User ID
- `status`: Session status
- `token`: access_token
- `issuedAt`: Issuance time
- `expiresAt`: Expiration time

#### C.8 WebSocketSessionInfo (WebSocket Session Information)

**Definition**: Complete information of a WebSocket connection.

**Key Fields**:
- `sessionId`: WebSocket session ID
- `userId`: User ID
- `loginSessionId`: Login session ID
- `service`: Service name (e.g., "game-service")

---

### D. References

#### D.1 Spring Security OAuth2 Documentation

**Official Documentation**:
- Spring Security OAuth2 Client: https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html
- Spring Security OAuth2 Resource Server: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html

**Key Concepts**:
- OAuth2 Client
- OAuth2 Resource Server
- JWT Decoder
- OAuth2AuthorizedClient

#### D.2 Keycloak Documentation

**Official Documentation**:
- Keycloak Official Documentation: https://www.keycloak.org/documentation
- Keycloak Server Administration Guide: https://www.keycloak.org/docs/latest/server_admin/

**Key Concepts**:
- Realm
- Client
- User Session
- Session ID (sid)
- Token Claims

#### D.3 Redis Documentation

**Official Documentation**:
- Redis Official Documentation: https://redis.io/docs/
- Redis Command Reference: https://redis.io/commands/

**Key Concepts**:
- String
- Set
- Sorted Set
- Hash
- TTL
- Pipeline

#### D.4 Kafka Documentation

**Official Documentation**:
- Kafka Official Documentation: https://kafka.apache.org/documentation/
- Spring Kafka Documentation: https://docs.spring.io/spring-kafka/reference/html/

**Key Concepts**:
- Topic
- Partition
- Consumer Group
- Offset
- Producer
- Consumer

#### D.5 Spring WebSocket Documentation

**Official Documentation**:
- Spring WebSocket Documentation: https://docs.spring.io/spring-framework/reference/web/websocket.html
- STOMP Protocol: https://stomp.github.io/

**Key Concepts**:
- WebSocket
- STOMP
- Session
- Message Channel

---

### E. Quick Reference

#### E.1 Key Constants

**SessionRegistry Key Prefixes**:
```java
LOGIN_USER_KEY_PREFIX = "session:login:user:"
LOGIN_SESSION_KEY_PREFIX = "session:login:token:"
LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX = "session:login:loginSession:"
WS_USER_KEY_PREFIX = "session:ws:user:"
WS_SESSION_KEY_PREFIX = "session:ws:session:"
```

**HTTP Session Keys**:
```java
SESSION_LOGIN_SESSION_ID_KEY = "LOGIN_SESSION_ID"
```

**Blacklist Key Prefix**:
```java
BLACKLIST_KEY_PREFIX = "jwt:blacklist:"
```

#### E.2 Key Methods

**SessionRegistry**:
- `registerLoginSessionEnforceSingle()`: Register login session (single device login)
- `getLoginSessionByLoginSessionId()`: Query by loginSessionId
- `updateSessionStatus()`: Update session status
- `registerWebSocketSessionEnforceSingle()`: Register WebSocket session (single device login)

**JwtBlacklistService**:
- `addToBlacklist()`: Add to blacklist
- `isBlacklisted()`: Check if in blacklist

**SessionEventPublisher**:
- `publishSessionInvalidated()`: Publish session invalidation event

#### E.3 Key Log Identifiers

**Log Identifiers**:
- `【单设备登录】`: Login-related logs
- `【JWT 校验】`: JWT validation-related logs
- `【Token获取】`: Token retrieval-related logs
- `【会话状态更新】`: Session status update logs

---

### F. Complete Document Navigation

**Document Structure**:
1. **Background and Problem Analysis**: Understand functional requirements and technical challenges
2. **Core Design Philosophy**: Understand design principles and solutions
3. **Key Concept Definitions**: Understand core concepts and terminology
4. **Complete Login Flow Implementation**: Understand login flow and code implementation
5. **Complete JWT Validation Flow Implementation**: Understand JWT validation and three-layer validation
6. **Token Retrieval Interface Implementation**: Understand token retrieval and validation
7. **Complete Logout Flow Implementation**: Understand logout flow and event publishing
8. **WebSocket Connection Management**: Understand WebSocket connection management
9. **Kafka Event Notification Mechanism**: Understand event publishing and consumption
10. **SessionRegistry Core Implementation**: Understand session registry implementation
11. **JWT Blacklist Mechanism**: Understand blacklist implementation
12. **Complete Data Flow Diagram**: Understand complete data flow
13. **Key Design Decisions**: Understand design decision reasons
14. **Edge Case Handling**: Understand edge case handling
15. **Testing and Verification**: Understand testing methods
16. **Common Issues and Solutions**: Understand troubleshooting methods
17. **Performance Optimization Recommendations**: Understand performance optimization methods
18. **Monitoring and Operations**: Understand monitoring and operations methods

---

### G. Chapter Summary

**Appendix Content**:
1. **Key Code File List**: Gateway, Game-Service, Session-Common, Session-Kafka-Notifier
2. **Configuration Instructions**: Spring Security, Redis, Kafka, Keycloak
3. **Glossary**: loginSessionId, sessionId, jti, sid, session_state, etc.
4. **References**: Spring Security, Keycloak, Redis, Kafka official documentation
5. **Quick Reference**: Key constants, methods, log identifiers

**Usage Recommendations**:
- ✅ As a quick reference manual
- ✅ Find key code file locations
- ✅ Understand configuration item meanings
- ✅ Understand terminology definitions

**Document Completion**: At this point, we have completed all content of the Complete Implementation Guide for Single Device Login System document. We hope this document can help you deeply understand the implementation principles and details of the entire system.



















