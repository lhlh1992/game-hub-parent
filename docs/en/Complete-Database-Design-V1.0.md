# Database Design - Full Version for Multiplayer Online Board/Game Rooms

> Based on an RBAC permission system, extended with game, social, user growth, and matchmaking business modules, suitable for a multiplayer online board/game platform.  
> **Design Principles**: Under a microservices architecture, do not use foreign key constraints; ensure data consistency at the application layer; keep unique indexes for uniqueness; seal terminal states in state machines; standardize idempotency keys.

---

## I. Overall Design

### 1.1 Architecture Positioning
- **Keycloak**: User authentication (username, password)
- **System Database**: Business data (users, roles, permissions, games, social, growth, matchmaking)
- **Link Field**: `keycloak_user_id` (UUID, corresponds to `sub` in JWT)

### 1.2 Module Breakdown
```
System Modules
 ├── RBAC Permission System (user, role, permission, menu, department, log)
 ├── Game Module (game type, room, match, match detail)
 ├── Social Module (friend, friend request, chat session, chat message)
 ├── User Growth Module (points, level, point change record)
 └── Matchmaking Module (queue, record)
```

---

## II. Database Initialization

### 2.1 Create Database

```sql
-- Create database (requires superuser privileges)
-- Unified baseline: UTF8 + en_US.UTF-8 + UTC (cross-platform friendly, Docker defaults)
CREATE DATABASE gamehub_db
    WITH 
    OWNER = postgres                    -- Database owner (adjust as needed)
    ENCODING = 'UTF8'                   -- Character encoding: UTF-8 (supports Chinese/English/Emoji and all Unicode)
    LC_COLLATE = 'en_US.UTF-8'         -- Collation: en_US.UTF-8 (English natural sort, good cross-platform compatibility)
    LC_CTYPE = 'en_US.UTF-8'           -- Character classification: en_US.UTF-8 (good cross-platform compatibility)
    TEMPLATE = template0                -- Use template0 (avoid inheriting other DB settings)
    CONNECTION LIMIT = -1;              -- Connection limit (-1 means no limit)

-- Add database comment
COMMENT ON DATABASE gamehub_db IS 'Main database for multiplayer online board/game platform (international baseline: UTF8 + en_US.UTF-8 + UTC)';
```

> **Notes**:
> - **Unified Baseline**: Use `en_US.UTF-8` as the global collation; do not maintain separate “Chinese DB” / “English DB”; keep only this single setting
> - **English Natural Sort**: A–Z, uppercase before lowercase, matches international expectations
> - **Cross-Platform**: Docker supports it by default; strong cross-platform consistency
> - **Chinese Support**: Chinese, Japanese, etc. store/display correctly
> - **I18N Support**: For Chinese pinyin sort or numeric-aware sort, use ICU Collation per column or per query (see §2.6)
> - **Timezone Unity**: Database uses UTC; application layer localizes display based on user timezone
>
> **Reasons for choosing `en_US.UTF-8`**:
> - ✅ **User Experience**: English natural sort (A–Z) matches most users’ expectations, especially for international projects
> - ✅ **Deployment Convenience**: Docker default, no extra locale setup
> - ✅ **Compatibility**: Consistent results across platforms/environments
> - ⚠️ **Performance**: Slightly slower than `C.UTF-8` (~5–10%), usually negligible
> - ⚠️ **Chinese Sort**: Chinese sorted by Unicode code points (not pinyin); use ICU Collation for pinyin if needed (see §2.6)
>
> **If pursuing extreme performance or fully consistent cross-platform sorting**, consider `C.UTF-8`, but you lose English natural sort.

### 2.2 Connect and Set Extensions

```sql
-- Connect to the newly created database
\c gamehub_db

-- Create extensions (all use IF NOT EXISTS, re-entrant)
-- UUID generation (PostgreSQL 13+ has gen_random_uuid() built-in, but pgcrypto adds more crypto funcs, recommended)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- Provides gen_random_uuid(), etc.

-- Fuzzy/similarity search (supports Chinese fuzzy search)
CREATE EXTENSION IF NOT EXISTS "pg_trgm";    -- Trigram similarity, works with ILIKE fuzzy search

-- Case-insensitive string type
CREATE EXTENSION IF NOT EXISTS "citext";     -- CITEXT type, case-insensitive string comparison

-- Note: JSONB index support is built-in; no extra extension needed
```

> **Notes**:
> - **pgcrypto**: Even though PostgreSQL 13+ includes `gen_random_uuid()`, `pgcrypto` offers more crypto functions; recommended
> - **pg_trgm**: Supports Chinese fuzzy search; use with GIN index and `ILIKE '%keyword%'`
> - **citext**: Suitable for email, username, etc. where case-insensitive comparison is needed

### 2.3 Database Users and Roles (Recommended)

```sql
-- Create database roles (least privilege)
CREATE ROLE gamehub_owner NOLOGIN;  -- DB owner role
CREATE ROLE gamehub_rw NOLOGIN;     -- Read/Write role
CREATE ROLE gamehub_ro NOLOGIN;     -- Read-only role

-- Create application user
CREATE USER gamehub_app WITH PASSWORD '***strong_password***';
GRANT gamehub_rw TO gamehub_app;

-- Set database owner
ALTER DATABASE gamehub_db OWNER TO gamehub_owner;

-- Create application schema (business objects live in app schema for management and permissions)
CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION gamehub_owner;
ALTER DATABASE gamehub_db SET search_path = app, public;

-- Revoke public privileges on public schema (hardening)
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Grant schema usage
GRANT USAGE ON SCHEMA app TO gamehub_rw, gamehub_ro;

-- Default privileges (future objects inherit automatically, no manual grants)
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO gamehub_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT SELECT ON TABLES TO gamehub_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT USAGE, SELECT ON SEQUENCES TO gamehub_rw, gamehub_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT EXECUTE ON FUNCTIONS TO gamehub_rw;
```

> **Notes**:
> - **Schema Isolation**: Keep all business tables in `app` schema for easier management and permission control
> - **Explicit Prefix**: All future objects should explicitly use `app.` prefix (e.g., `app.sys_user`) to avoid `search_path` hijack
> - **Least Privilege**: Application uses `gamehub_app`; avoid using superuser

### 2.4 Database Configuration (Timezone & Connection Guardrails)

```sql
-- Check current DB config
SHOW timezone;
SHOW server_encoding;
SELECT datcollate, datctype FROM pg_database WHERE datname = current_database();

-- Set DB-level parameters (requires superuser)
-- Timezone set to China (Asia/Shanghai)
ALTER DATABASE gamehub_db SET timezone = 'Asia/Shanghai';
ALTER DATABASE gamehub_db SET log_timezone = 'Asia/Shanghai';

-- Connection guardrails (prevent long-running queries from hogging resources)
ALTER DATABASE gamehub_db SET statement_timeout = '30s';
ALTER DATABASE gamehub_db SET lock_timeout = '5s';
ALTER DATABASE gamehub_db SET idle_in_transaction_session_timeout = '60s';
```

> **Notes**:
> - **Timezone set to Asia/Shanghai**: DB timezone = `Asia/Shanghai`, all time fields use `TIMESTAMPTZ`. Reminder: `TIMESTAMPTZ` stores in UTC internally and renders per session timezone (currently `Asia/Shanghai`)
> - **Connection Guardrails**: Adjust timeouts per business needs (complex reports may need larger `statement_timeout`)
> - **Connection Pool (app layer, e.g., HikariCP)**:
>   - `maximumPoolSize`: set by concurrency (recommend 10–50)
>   - `minimumIdle`: keep minimum connections (recommend 5–10)
>   - `connectionTimeout`: connection timeout (recommend 30000ms)
>   - `idleTimeout`: idle timeout (recommend 600000ms)
>   - `maxLifetime`: max lifetime (recommend 1800000ms)

### 2.5 Charset and Collation Notes

- **ENCODING = 'UTF8'**: UTF-8 encoding supports Chinese/English/Emoji and all Unicode

- **LC_COLLATE / LC_CTYPE = 'en_US.UTF-8'**: Unified baseline
  - **English Natural Sort**: A–Z, uppercase before lowercase
  - **Cross-Platform**: Docker default; good compatibility
  - **Chinese Support**: Chinese/Japanese, etc. store/display fine
  - **Unified Standard**: No separate “Chinese DB” / “English DB”; single global setting

- **When Chinese/pinyin/numeric-aware sort is needed**: Use ICU Collation (PostgreSQL 15+)
  - Chinese pinyin sort: `zh_pinyin` (see §2.6)
  - Numeric-aware sort: `en_numeric` (see §2.6)
  - Set per column or per query; no need to change global config

> **Design Principle**: Unified baseline = `UTF8 + en_US.UTF-8 + UTC`; do not maintain two sets of DDL for Chinese/English. If pinyin or natural-language sorting is needed later, add ICU Collation on columns or queries, not global.
>
> **Professional Advice**:
> - **International Projects**: `en_US.UTF-8` is reasonable, meets user expectations, easy to deploy
> - **High Performance**: If sort performance is a bottleneck (large-data sorts), consider `C.UTF-8`, but handle sorting in the app layer
> - **Chinese-Focused**: If mostly Chinese users and pinyin sort is needed, use ICU Collation on key fields (nickname, menu name) instead of changing global config

### 2.6 ICU Collations (Optional, PostgreSQL 15+)

**Default is `en_US.UTF-8`**. When “Chinese pinyin / numeric-aware sort” is needed, apply ICU **only to specific columns or queries**, leaving the global baseline unchanged.

```sql
-- Create ICU collations (as needed)
-- Numeric-aware English sort ('item2' < 'item10')
CREATE COLLATION IF NOT EXISTS "en_numeric"
  (provider = icu, locale = 'en-u-kn-true');

-- Chinese pinyin sort
CREATE COLLATION IF NOT EXISTS "zh_pinyin"
  (provider = icu, locale = 'zh-u-co-pinyin');

-- Usage A: Column-level (column defaults to pinyin/numeric-aware comparison)
-- Example: Add Chinese pinyin collation to nickname column
-- ALTER TABLE app.sys_user
--   ALTER COLUMN nickname TYPE VARCHAR(50) COLLATE "zh_pinyin";

-- Usage B: Query-level (only effective in the statement, recommended)
-- Example: Sort by pinyin in query
-- SELECT nickname FROM app.sys_user ORDER BY nickname COLLATE "zh_pinyin";
-- SELECT menu_name FROM app.sys_menu ORDER BY menu_name COLLATE "en_numeric";
```

> **Notes**:
> - **Use on demand**: Only for columns/queries needing pinyin or numeric-aware sort
> - **Does not affect whole DB**: Baseline remains `en_US.UTF-8`, ensuring cross-platform compatibility
> - **PostgreSQL version**: ICU Collation requires PostgreSQL 15+; ignore if older

### 2.7 Script Organization (Re-entrant)

**Layered scripts**, all statements use `IF NOT EXISTS` / `OR REPLACE`, supporting repeat execution:

```
database/
├── 01_db.sql          # DB creation, extensions, parameters, roles, schema, privileges
├── 02_tables.sql      # Tables (all use app. prefix)
├── 03_indexes.sql     # Index creation
├── 04_triggers.sql    # Trigger creation
└── 05_seed.sql        # Seed data
```

**Execution Principles**:
- All DDL statements repeatable, side-effect free
- Always use `IF NOT EXISTS` / `OR REPLACE`
- Avoid `DROP` unless explicitly rebuilding

### 2.8 Quick Self-Check List

```sql
-- Check DB config
SHOW server_encoding;    -- Should be: UTF8
SHOW lc_collate;         -- Should be: en_US.UTF-8
SHOW lc_ctype;           -- Should be: en_US.UTF-8
SHOW timezone;           -- Should be: UTC
SHOW log_timezone;       -- Should be: UTC

-- Check extensions
SELECT extname FROM pg_extension WHERE extname IN ('pgcrypto', 'pg_trgm', 'citext');
-- Should return: pgcrypto, pg_trgm, citext

-- Check schema and privileges
SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'app';
-- Should return: app

-- Check roles
SELECT rolname FROM pg_roles WHERE rolname IN ('gamehub_owner', 'gamehub_rw', 'gamehub_ro', 'gamehub_app');
-- Should return: gamehub_owner, gamehub_rw, gamehub_ro, gamehub_app
```

---

## III. RBAC Permission System

### 3.1 User Table (sys_user)

```sql
CREATE TABLE sys_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id UUID NOT NULL UNIQUE, -- Keycloak user ID (corresponds to sub in JWT, must be non-null)
    username CITEXT NOT NULL, -- Username (case-insensitive)
    nickname VARCHAR(50), -- Nickname
    email CITEXT, -- Email (case-insensitive)
    phone VARCHAR(20), -- Phone
    avatar_url VARCHAR(500), -- Avatar URL
    user_type VARCHAR(20) DEFAULT 'NORMAL', -- User type: NORMAL, ADMIN
    dept_id UUID, -- Department ID (application layer ensures existence)
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    player_id BIGINT NOT NULL DEFAULT nextval('player_id_seq'), -- Player ID (unique numeric ID, 6–9 digits, sequence)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid user type
    CONSTRAINT chk_user_type_valid CHECK (user_type IN ('NORMAL', 'ADMIN')),
    -- Constraint: valid status
    CONSTRAINT chk_user_status_valid CHECK (status IN (0, 1)),
    -- Constraint: player ID uniqueness
    CONSTRAINT sys_user_player_id_key UNIQUE (player_id)
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_user_keycloak_id ON sys_user(keycloak_user_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_sys_user_username_not_deleted ON sys_user(username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_sys_user_player_id ON sys_user(player_id) WHERE deleted_at IS NULL AND player_id IS NOT NULL;
CREATE INDEX idx_sys_user_email ON sys_user(email) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_user_phone ON sys_user(phone) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_user_dept ON sys_user(dept_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_user_status ON sys_user(status) WHERE deleted_at IS NULL;

-- Sequence (for player_id auto-increment; skip if exists)
CREATE SEQUENCE IF NOT EXISTS player_id_seq
    START WITH 1000000
    INCREMENT BY 1
    MINVALUE 1000000
    MAXVALUE 999999999
    NO CYCLE;

-- Bind sequence ownership
ALTER SEQUENCE player_id_seq OWNED BY sys_user.player_id;

-- Comments
COMMENT ON TABLE sys_user IS 'User table';
COMMENT ON COLUMN sys_user.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_user.keycloak_user_id IS 'Keycloak user ID (maps to JWT sub, must be non-null, unique)';
COMMENT ON COLUMN sys_user.username IS 'Username (unique, case-insensitive CITEXT, must be non-null)';
COMMENT ON COLUMN sys_user.nickname IS 'Display nickname';
COMMENT ON COLUMN sys_user.email IS 'Email (case-insensitive CITEXT)';
COMMENT ON COLUMN sys_user.phone IS 'Phone number';
COMMENT ON COLUMN sys_user.avatar_url IS 'Avatar URL';
COMMENT ON COLUMN sys_user.user_type IS 'User type enum: NORMAL, ADMIN; default NORMAL';
COMMENT ON COLUMN sys_user.dept_id IS 'Department ID (links sys_dept.id; application layer ensures existence)';
COMMENT ON COLUMN sys_user.status IS 'Status enum: 0 (disabled), 1 (enabled); default 1';
COMMENT ON COLUMN sys_user.remark IS 'Remark';
COMMENT ON COLUMN sys_user.player_id IS 'Player ID (unique numeric ID, 6–9 digits, sequence, non-null)';
COMMENT ON COLUMN sys_user.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
COMMENT ON COLUMN sys_user.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
COMMENT ON COLUMN sys_user.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL = not deleted)';
```

### 3.2 User Profile Table (sys_user_profile)

```sql
CREATE TABLE sys_user_profile (
    user_id UUID PRIMARY KEY, -- User ID (application layer ensures existence)
    bio VARCHAR(500), -- Bio
    locale VARCHAR(10) DEFAULT 'zh-CN', -- Language preference
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai', -- Timezone
    settings JSONB DEFAULT '{}'::jsonb, -- User settings (JSONB)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_user_profile_locale ON sys_user_profile(locale);

-- Comments
COMMENT ON TABLE sys_user_profile IS 'User profile table';
COMMENT ON COLUMN sys_user_profile.user_id IS 'User ID (PK, links sys_user.id, app layer ensures)';
COMMENT ON COLUMN sys_user_profile.bio IS 'Bio (self introduction)';
COMMENT ON COLUMN sys_user_profile.locale IS 'Language preference (e.g., zh-CN, en-US), default zh-CN';
COMMENT ON COLUMN sys_user_profile.timezone IS 'Timezone (e.g., Asia/Shanghai, UTC), default Asia/Shanghai';
COMMENT ON COLUMN sys_user_profile.settings IS 'User settings (JSONB, e.g., {"theme": "dark", "notifications": true})';
COMMENT ON COLUMN sys_user_profile.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
COMMENT ON COLUMN sys_user_profile.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
```

### 3.3 Department Table (sys_dept)

```sql
CREATE TABLE sys_dept (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dept_name VARCHAR(100) NOT NULL, -- Department name
    dept_code VARCHAR(50) UNIQUE, -- Department code
    parent_id UUID, -- Parent department ID (app layer ensures)
    leader_id UUID, -- Leader user ID (app layer ensures)
    phone VARCHAR(20), -- Phone
    email VARCHAR(100), -- Email
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    settings JSONB DEFAULT '{}'::jsonb, -- Dept settings (JSONB)
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid status
    CONSTRAINT chk_dept_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_dept_code_not_deleted ON sys_dept(dept_code) WHERE deleted_at IS NULL AND dept_code IS NOT NULL;
CREATE INDEX idx_sys_dept_parent ON sys_dept(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_dept_leader ON sys_dept(leader_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_dept_status ON sys_dept(status) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE sys_dept IS 'Department table';
COMMENT ON COLUMN sys_dept.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_dept.dept_name IS 'Department name (required)';
COMMENT ON COLUMN sys_dept.dept_code IS 'Department code (unique business code)';
COMMENT ON COLUMN sys_dept.parent_id IS 'Parent dept ID (links sys_dept.id, tree support, NULL = root, app layer ensures)';
COMMENT ON COLUMN sys_dept.leader_id IS 'Leader user ID (links sys_user.id, app layer ensures)';
COMMENT ON COLUMN sys_dept.phone IS 'Contact phone';
COMMENT ON COLUMN sys_dept.email IS 'Email';
COMMENT ON COLUMN sys_dept.sort_order IS 'Sort (smaller is earlier), default 0';
COMMENT ON COLUMN sys_dept.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_dept.settings IS 'Dept settings (JSONB)';
COMMENT ON COLUMN sys_dept.remark IS 'Remark';
COMMENT ON COLUMN sys_dept.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
COMMENT ON COLUMN sys_dept.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
COMMENT ON COLUMN sys_dept.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL = not deleted)';
```

### 3.4 Role Table (sys_role)

```sql
CREATE TABLE sys_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code VARCHAR(50) NOT NULL UNIQUE, -- Role code
    role_name VARCHAR(50) NOT NULL, -- Role name
    role_desc VARCHAR(200), -- Role description
    data_scope VARCHAR(20) DEFAULT 'ALL', -- Data scope: ALL, DEPT, DEPT_AND_CHILD, SELF
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid data scope
    CONSTRAINT chk_role_data_scope_valid CHECK (data_scope IN ('ALL', 'DEPT', 'DEPT_AND_CHILD', 'SELF')),
    -- Constraint: valid status
    CONSTRAINT chk_role_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_role_code_not_deleted ON sys_role(role_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_role_status ON sys_role(status) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE sys_role IS 'Role table';
COMMENT ON COLUMN sys_role.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_role.role_code IS 'Role code (unique business code, required)';
COMMENT ON COLUMN sys_role.role_name IS 'Role name (required)';
COMMENT ON COLUMN sys_role.role_desc IS 'Role description';
COMMENT ON COLUMN sys_role.data_scope IS 'Data scope enum: ALL, DEPT, DEPT_AND_CHILD, SELF; default ALL';
COMMENT ON COLUMN sys_role.sort_order IS 'Sort (smaller is earlier), default 0';
COMMENT ON COLUMN sys_role.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_role.remark IS 'Remark';
COMMENT ON COLUMN sys_role.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
COMMENT ON COLUMN sys_role.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
COMMENT ON COLUMN sys_role.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL = not deleted)';
```

### 3.5 Permission Table (sys_permission)

```sql
CREATE TABLE sys_permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_code VARCHAR(100) NOT NULL UNIQUE, -- Permission code
    permission_name VARCHAR(100) NOT NULL, -- Permission name
    permission_type VARCHAR(20) NOT NULL, -- Permission type: MENU, BUTTON, API
    resource_type VARCHAR(50), -- Resource type
    resource_path VARCHAR(500), -- Resource path
    http_method VARCHAR(10), -- HTTP method (GET, POST, PUT, DELETE, etc.)
    data_expr JSONB DEFAULT '{}'::jsonb, -- Data permission expression (JSONB)
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid permission type
    CONSTRAINT chk_permission_type_valid CHECK (permission_type IN ('MENU', 'BUTTON', 'API')),
    -- Constraint: valid HTTP method
    CONSTRAINT chk_permission_http_method_valid CHECK (
        http_method IS NULL OR http_method IN ('GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS')
    ),
    -- Constraint: valid status
    CONSTRAINT chk_permission_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_permission_code_not_deleted ON sys_permission(permission_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_permission_type ON sys_permission(permission_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_permission_resource ON sys_permission(resource_type, resource_path) WHERE deleted_at IS NULL;

-- Unique index: prevent duplicate API permissions (resource_path + http_method)
CREATE UNIQUE INDEX uk_sys_permission_api ON sys_permission(resource_path, http_method) 
    WHERE deleted_at IS NULL AND resource_path IS NOT NULL AND http_method IS NOT NULL;

-- Comments
COMMENT ON TABLE sys_permission IS 'Permission table';
COMMENT ON COLUMN sys_permission.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_permission.permission_code IS 'Permission code (unique business code, required)';
COMMENT ON COLUMN sys_permission.permission_name IS 'Permission name (required)';
COMMENT ON COLUMN sys_permission.permission_type IS 'Permission type enum: MENU, BUTTON, API; required';
COMMENT ON COLUMN sys_permission.resource_type IS 'Resource type (classification)';
COMMENT ON COLUMN sys_permission.resource_path IS 'Resource path (API path or resource identifier)';
COMMENT ON COLUMN sys_permission.http_method IS 'HTTP method enum: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS (for API permissions)';
COMMENT ON COLUMN sys_permission.data_expr IS 'Data permission expression (JSONB, flexible rules)';
COMMENT ON COLUMN sys_permission.sort_order IS 'Sort (smaller is earlier), default 0';
COMMENT ON COLUMN sys_permission.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_permission.remark IS 'Remark';
COMMENT ON COLUMN sys_permission.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
COMMENT ON COLUMN sys_permission.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
COMMENT ON COLUMN sys_permission.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL = not deleted)';
```

### 3.6 Menu Table (sys_menu)

```sql
CREATE TABLE sys_menu (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    menu_name VARCHAR(100) NOT NULL, -- Menu name
    menu_type VARCHAR(20) NOT NULL, -- Menu type: DIRECTORY, MENU, BUTTON
    parent_id UUID, -- Parent menu ID (app layer ensures)
    path VARCHAR(200), -- Route path
    component VARCHAR(200), -- Component path
    icon VARCHAR(100), -- Icon
    permission_code VARCHAR(100), -- Permission code (links permission table)
    route_meta JSONB DEFAULT '{}'::jsonb, -- Route meta (JSONB, frontend routing)
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid menu type
    CONSTRAINT chk_menu_type_valid CHECK (menu_type IN ('DIRECTORY', 'MENU', 'BUTTON')),
    -- Constraint: valid status
    CONSTRAINT chk_menu_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_menu_path ON sys_menu(path) WHERE deleted_at IS NULL AND path IS NOT NULL;
CREATE INDEX idx_sys_menu_parent ON sys_menu(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_menu_type ON sys_menu(menu_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_menu_status ON sys_menu(status) WHERE deleted_at IS NULL;

-- Unique: menu name unique under same parent
CREATE UNIQUE INDEX uk_sys_menu_parent_name ON sys_menu(parent_id, menu_name) WHERE deleted_at IS NULL;

-- Index: permission linkage (via permission_code)
CREATE INDEX idx_sys_menu_permission_code ON sys_menu(permission_code) WHERE deleted_at IS NULL AND permission_code IS NOT NULL;

-- Comments
COMMENT ON TABLE sys_menu IS 'Menu table';
COMMENT ON COLUMN sys_menu.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_menu.menu_name IS 'Menu name (required, unique under same parent)';
COMMENT ON COLUMN sys_menu.menu_type IS 'Menu type enum: DIRECTORY, MENU, BUTTON; required';
COMMENT ON COLUMN sys_menu.parent_id IS 'Parent menu ID (links sys_menu.id, NULL=root, app layer ensures)';
COMMENT ON COLUMN sys_menu.path IS 'Route path (frontend route, globally unique)';
COMMENT ON COLUMN sys_menu.component IS 'Component path (frontend component file path)';
COMMENT ON COLUMN sys_menu.icon IS 'Icon';
COMMENT ON COLUMN sys_menu.permission_code IS 'Permission code (links sys_permission.permission_code for access control)';
COMMENT ON COLUMN sys_menu.route_meta IS 'Route meta (JSONB, frontend route config, e.g., {"title": "Home", "hidden": false, "affix": true})';
COMMENT ON COLUMN sys_menu.sort_order IS 'Sort (smaller is earlier), default 0';
COMMENT ON COLUMN sys_menu.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_menu.remark IS 'Remark';
COMMENT ON COLUMN sys_menu.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
COMMENT ON COLUMN sys_menu.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
COMMENT ON COLUMN sys_menu.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL = not deleted)';
```

### 3.7 User-Role Mapping (sys_user_role)

```sql
CREATE TABLE sys_user_role (
    user_id UUID NOT NULL, -- User ID (app layer ensures)
    role_id UUID NOT NULL, -- Role ID (app layer ensures)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id) -- Composite PK ensures uniqueness
);

-- Indexes (reverse lookup by role)
CREATE INDEX idx_user_role_role ON sys_user_role(role_id);

-- Comments
COMMENT ON TABLE sys_user_role IS 'User-role mapping (many-to-many)';
COMMENT ON COLUMN sys_user_role.user_id IS 'User ID (links sys_user.id, composite PK, app layer ensures)';
COMMENT ON COLUMN sys_user_role.role_id IS 'Role ID (links sys_role.id, composite PK, app layer ensures)';
COMMENT ON COLUMN sys_user_role.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
```

### 3.8 Role-Permission Mapping (sys_role_permission)

```sql
CREATE TABLE sys_role_permission (
    role_id UUID NOT NULL, -- Role ID (app layer ensures)
    permission_id UUID NOT NULL, -- Permission ID (app layer ensures)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id) -- Composite PK ensures uniqueness
);

-- Index (reverse lookup by permission)
CREATE INDEX idx_role_permission_permission ON sys_role_permission(permission_id);

-- Comments
COMMENT ON TABLE sys_role_permission IS 'Role-permission mapping (many-to-many)';
COMMENT ON COLUMN sys_role_permission.role_id IS 'Role ID (links sys_role.id, composite PK, app layer ensures)';
COMMENT ON COLUMN sys_role_permission.permission_id IS 'Permission ID (links sys_permission.id, composite PK, app layer ensures)';
COMMENT ON COLUMN sys_role_permission.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
```

### 3.9 Role-Menu Mapping (sys_role_menu)

```sql
CREATE TABLE sys_role_menu (
    role_id UUID NOT NULL, -- Role ID (app layer ensures)
    menu_id UUID NOT NULL, -- Menu ID (app layer ensures)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, menu_id) -- Composite PK ensures uniqueness
);

-- Index (reverse lookup by menu)
CREATE INDEX idx_role_menu_menu ON sys_role_menu(menu_id);

-- Comments
COMMENT ON TABLE sys_role_menu IS 'Role-menu mapping (many-to-many)';
COMMENT ON COLUMN sys_role_menu.role_id IS 'Role ID (links sys_role.id, composite PK, app layer ensures)';
COMMENT ON COLUMN sys_role_menu.menu_id IS 'Menu ID (links sys_menu.id, composite PK, app layer ensures)';
COMMENT ON COLUMN sys_role_menu.created_at IS 'Created at (TIMESTAMPTZ, auto-set)';
```

### 3.10 Login Log Table (sys_login_log)

```sql
CREATE TABLE sys_login_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sys_user_id UUID, -- User ID (app layer ensures)
    username VARCHAR(50), -- Username (denormalized to avoid JOIN)
    login_type VARCHAR(20), -- Login type: PASSWORD, OAUTH
    ip_address VARCHAR(50), -- IP address
    location VARCHAR(200), -- Login location
    device_type VARCHAR(50), -- Device type
    browser VARCHAR(100), -- Browser
    os VARCHAR(100), -- OS
    status SMALLINT, -- Status: 0-fail, 1-success
    failure_reason VARCHAR(500), -- Failure reason
    login_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid login type
    CONSTRAINT chk_login_type_valid CHECK (login_type IS NULL OR login_type IN ('PASSWORD', 'OAUTH')),
    -- Constraint: valid status
    CONSTRAINT chk_login_status_valid CHECK (status IS NULL OR status IN (0, 1))
);

-- Indexes
CREATE INDEX idx_login_log_user ON sys_login_log(sys_user_id);
CREATE INDEX idx_login_log_time ON sys_login_log(login_time DESC);
CREATE INDEX idx_login_log_ip ON sys_login_log(ip_address);
CREATE INDEX idx_login_log_status ON sys_login_log(status);

-- Comments
COMMENT ON TABLE sys_login_log IS 'Login log table';
COMMENT ON COLUMN sys_login_log.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_login_log.sys_user_id IS 'User ID (links sys_user.id, app layer ensures)';
COMMENT ON COLUMN sys_login_log.username IS 'Username (denormalized to avoid JOIN)';
COMMENT ON COLUMN sys_login_log.login_type IS 'Login type enum: PASSWORD, OAUTH';
COMMENT ON COLUMN sys_login_log.ip_address IS 'IP address (client IP)';
COMMENT ON COLUMN sys_login_log.location IS 'Login location (geo from IP)';
COMMENT ON COLUMN sys_login_log.device_type IS 'Device type (e.g., PC, Mobile, Tablet)';
COMMENT ON COLUMN sys_login_log.browser IS 'Browser (e.g., Chrome, Firefox, Safari)';
COMMENT ON COLUMN sys_login_log.os IS 'Operating system (e.g., Windows, macOS, Linux, iOS, Android)';
COMMENT ON COLUMN sys_login_log.status IS 'Status enum: 0 (fail), 1 (success)';
COMMENT ON COLUMN sys_login_log.failure_reason IS 'Failure reason';
COMMENT ON COLUMN sys_login_log.login_time IS 'Login time (TIMESTAMPTZ, auto-set)';
```

### 3.11 Operation Log Table (sys_op_log)

```sql
CREATE TABLE sys_op_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sys_user_id UUID, -- User ID (app layer ensures)
    username VARCHAR(50), -- Username (denormalized)
    target_name VARCHAR(100), -- Operation target name
    method VARCHAR(20), -- Request method (GET, POST, PUT, DELETE, etc.)
    request_url VARCHAR(500), -- Request URL
    request_params TEXT, -- Request params
    response_code INT, -- Response status code
    ip_address VARCHAR(50), -- IP address
    user_agent VARCHAR(500), -- User-Agent
    error_message TEXT, -- Error message
    duration_ms INT, -- Duration (ms)
    op_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_op_log_user ON sys_op_log(sys_user_id);
CREATE INDEX idx_op_log_time ON sys_op_log(op_time DESC);
CREATE INDEX idx_op_log_url ON sys_op_log(request_url);
CREATE INDEX idx_op_log_method ON sys_op_log(method);

-- Comments
COMMENT ON TABLE sys_op_log IS 'Operation log table';
COMMENT ON COLUMN sys_op_log.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_op_log.sys_user_id IS 'User ID (links sys_user.id, app layer ensures)';
COMMENT ON COLUMN sys_op_log.username IS 'Username (denormalized, avoid JOIN)';
COMMENT ON COLUMN sys_op_log.target_name IS 'Operation target name';
COMMENT ON COLUMN sys_op_log.method IS 'HTTP method (GET, POST, PUT, DELETE, PATCH, etc.)';
COMMENT ON COLUMN sys_op_log.request_url IS 'Request URL';
COMMENT ON COLUMN sys_op_log.request_params IS 'Request params (body or query, TEXT)';
COMMENT ON COLUMN sys_op_log.response_code IS 'Response status code';
COMMENT ON COLUMN sys_op_log.ip_address IS 'IP address';
COMMENT ON COLUMN sys_op_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN sys_op_log.error_message IS 'Error message';
COMMENT ON COLUMN sys_op_log.duration_ms IS 'Duration (ms)';
COMMENT ON COLUMN sys_op_log.op_time IS 'Operation time (TIMESTAMPTZ, auto-set)';
```

> **Note**: `sys_login_log` and `sys_op_log` are high-write tables; consider monthly partitioning. Partition template in §3.13.

### 3.12 Notification Table (sys_notification)

```sql
CREATE TABLE sys_notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,               -- Target user ID (links keycloak_user_id / sys_user.id, app layer ensures)
    type VARCHAR(50) NOT NULL,           -- Notification type: FRIEND_REQUEST, SYSTEM_ALERT, GAME_INVITE, etc.
    title VARCHAR(200) NOT NULL,         -- Title
    content TEXT NOT NULL,               -- Content
    from_user_id VARCHAR(64),            -- Triggering user ID (Keycloak userId, optional)
    ref_type VARCHAR(50),                 -- Related business type (e.g., FRIEND_REQUEST)
    ref_id UUID,                         -- Related business ID
    payload JSONB,                       -- Pass-through data (refId, requestMessage, roomId, etc.)
    actions TEXT[],                      -- Optional actions: ['ACCEPT','REJECT'], etc.
    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', -- UNREAD / READ / ARCHIVED / DELETED
    source_service VARCHAR(50),          -- Source service (system-service, game-service)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid status
    CONSTRAINT chk_sys_notification_status_valid CHECK (status IN ('UNREAD','READ','ARCHIVED','DELETED'))
);

-- Indexes
CREATE INDEX idx_sys_notification_user_status ON sys_notification(user_id, status, created_at DESC);
CREATE INDEX idx_sys_notification_user_created ON sys_notification(user_id, created_at DESC);
CREATE INDEX idx_sys_notification_ref ON sys_notification(ref_type, ref_id) WHERE ref_id IS NOT NULL;

-- Comments
COMMENT ON TABLE sys_notification IS 'Global notification table (unified notifications, offline push + persistence)';
COMMENT ON COLUMN sys_notification.id IS 'Primary key (UUID)';
COMMENT ON COLUMN sys_notification.user_id IS 'Target user ID (links sys_user.id, receiver, app layer ensures, required)';
COMMENT ON COLUMN sys_notification.type IS 'Notification type enum: FRIEND_REQUEST, FRIEND_RESULT, SYSTEM_ALERT, GAME_INVITE, GAME_RESULT, etc., required';
COMMENT ON COLUMN sys_notification.title IS 'Notification title (up to 200 chars, required)';
COMMENT ON COLUMN sys_notification.content IS 'Notification content (TEXT, required)';
COMMENT ON COLUMN sys_notification.from_user_id IS 'Trigger user ID (Keycloak userId, e.g., friend request initiator, optional)';
COMMENT ON COLUMN sys_notification.ref_type IS 'Related business type (e.g., FRIEND_REQUEST, for linking business tables)';
COMMENT ON COLUMN sys_notification.ref_id IS 'Related business ID (e.g., friend_request.id)';
COMMENT ON COLUMN sys_notification.payload IS 'Pass-through data (JSONB, e.g., {"friendRequestId": "...", "requestMessage": "...", "requesterName": "...", "notificationId": "..."})';
COMMENT ON COLUMN sys_notification.actions IS 'Action buttons (TEXT[], e.g., ["ACCEPT", "REJECT"]; NULL/[] means no action)';
COMMENT ON COLUMN sys_notification.status IS 'Notification status enum: UNREAD, READ, ARCHIVED, DELETED; default UNREAD; required';
COMMENT ON COLUMN sys_notification.source_service IS 'Source service (system-service, game-service, chat-service)';
COMMENT ON COLUMN sys_notification.created_at IS 'Created at (TIMESTAMPTZ, auto-set, required)';
COMMENT ON COLUMN sys_notification.read_at IS 'Read time (TIMESTAMPTZ)';
COMMENT ON COLUMN sys_notification.archived_at IS 'Archived time (TIMESTAMPTZ)';
COMMENT ON COLUMN sys_notification.deleted_at IS 'Deleted time (TIMESTAMPTZ)';
COMMENT ON COLUMN sys_notification.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update)';
```

**Dictionary Notes**:

1. **Notification Type (type)**:
   - `FRIEND_REQUEST`: Friend request (receiver sees, with action buttons)
   - `FRIEND_RESULT`: Friend request result (applicant sees, no action)
   - `SYSTEM_ALERT`: System alert (maintenance, events, etc.)
   - `GAME_INVITE`: Game invite (with action buttons)
   - `GAME_RESULT`: Game result (match end)

2. **Notification Status (status)**:
   - `UNREAD`: Unread (shown in unread list, counts toward unread)
   - `READ`: Read (viewed, not counted as unread)
   - `ARCHIVED`: Archived (user archived, hidden from normal list)
   - `DELETED`: Deleted (soft delete)

3. **Actions (actions)**:
   - `ACCEPT`: Accept (e.g., accept friend request)
   - `REJECT`: Reject (e.g., reject friend request)
   - Empty `[]` or `NULL`: No action, display only

4. **Related Business Type (ref_type)**:
   - `FRIEND_REQUEST`: Links friend_request
   - `GAME_MATCH`: Links game_match
   - `GAME_ROOM`: Links game_room

5. **Source Service (source_service)**:
   - `system-service`: System (user, friend, notification)
   - `game-service`: Game (matches, rooms)
   - `chat-service`: Chat (message notifications)

### 3.13 Log Table Partition Template (Optional, for scale)

```sql
-- Login log monthly partition (example: 2024-01)
CREATE TABLE sys_login_log_2024_01 PARTITION OF sys_login_log
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Operation log monthly partition (example: 2024-01)
CREATE TABLE sys_op_log_2024_01 PARTITION OF sys_op_log
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Note: convert base tables to partitioned first (ALTER TABLE ... PARTITION BY RANGE)
-- Suggest: scheduled job creates next month's partition monthly; archive data older than 3 months to cold storage
```

> **Notes**:
> - **Partition Strategy**: Monthly partitions for manageability and performance
> - **Auto-Create**: Cron to create next month’s partition monthly
> - **Archival**: Archive partitions older than 3 months to cold storage
> - **Query Optimization**: PostgreSQL routes queries to partitions automatically; no app changes

### 3.14 Triggers

```sql
-- Trigger function to auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers for tables needing updated_at auto-update
CREATE TRIGGER trg_sys_user_updated_at
    BEFORE UPDATE ON sys_user
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_user_profile_updated_at
    BEFORE UPDATE ON sys_user_profile
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_dept_updated_at
    BEFORE UPDATE ON sys_dept
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_role_updated_at
    BEFORE UPDATE ON sys_role
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_permission_updated_at
    BEFORE UPDATE ON sys_permission
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_menu_updated_at
    BEFORE UPDATE ON sys_menu
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
```

---

## IV. Game Module

### 4.1 Game Type Table (game_type)

```sql
CREATE TABLE game_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_code VARCHAR(50) NOT NULL UNIQUE,
    game_name VARCHAR(50) NOT NULL,
    game_desc VARCHAR(200),
    icon_url VARCHAR(500),
    min_players INT DEFAULT 2,
    max_players INT DEFAULT 2,
    status SMALLINT DEFAULT 1, -- 0-disabled, 1-enabled
    sort_order INT DEFAULT 0,
    config JSONB DEFAULT '{}'::jsonb, -- Game config (rules, board size, etc.)
    remark VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid status
    CONSTRAINT chk_game_type_status_valid CHECK (status IN (0, 1)),
    -- Constraint: player count validity
    CONSTRAINT chk_game_type_players_valid CHECK (min_players > 0 AND max_players >= min_players)
);

-- Indexes
CREATE UNIQUE INDEX uk_game_type_code_not_deleted ON game_type(game_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_game_type_status ON game_type(status);
CREATE INDEX idx_game_type_status_sort ON game_type(status, sort_order) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE game_type IS 'Game type table';
COMMENT ON COLUMN game_type.game_code IS 'Game code (e.g., gomoku, chess, go)';
COMMENT ON COLUMN game_type.game_name IS 'Game name (e.g., Gomoku, Chinese Chess, Go)';
COMMENT ON COLUMN game_type.min_players IS 'Minimum players';
COMMENT ON COLUMN game_type.max_players IS 'Maximum players';
COMMENT ON COLUMN game_type.config IS 'Game config (JSONB, e.g., {"board_size": 15, "rules": ["STANDARD", "RENJU"]})';
```

### 4.2 Game Room Table (game_room)

```sql
CREATE TABLE game_room (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_code VARCHAR(50) UNIQUE, -- Room short code (optional, for sharing)
    game_type_id UUID NOT NULL, -- Game type ID (app layer ensures)
    owner_id UUID NOT NULL, -- Owner user ID (app layer ensures)
    client_op_id VARCHAR(64), -- Client op ID (idempotency, frontend UUID)
    room_name VARCHAR(100), -- Room name
    room_mode VARCHAR(20) NOT NULL, -- PVP (player vs player), PVE (player vs AI)
    room_status VARCHAR(20) DEFAULT 'WAITING', -- WAITING, PLAYING, FINISHED, CLOSED
    max_players INT DEFAULT 2 NOT NULL, -- Max players (non-null, default 2)
    current_players INT DEFAULT 0 NOT NULL, -- Current players (trigger/app maintained, non-null)
    password_hash VARCHAR(200), -- Password hash (optional, bcrypt/argon2)
    is_private BOOLEAN DEFAULT FALSE, -- Private room
    config JSONB DEFAULT '{}'::jsonb, -- Room config (rules, AI difficulty, etc.)
    started_at TIMESTAMPTZ, -- Start time
    finished_at TIMESTAMPTZ, -- End time
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid mode
    CONSTRAINT chk_room_mode_valid CHECK (room_mode IN ('PVP', 'PVE')),
    -- Constraint: valid status
    CONSTRAINT chk_room_status CHECK (
        room_status IN ('WAITING', 'PLAYING', 'FINISHED', 'CLOSED')
    ),
    -- Constraint: private room must have password hash
    CONSTRAINT chk_private_password CHECK (
        (is_private = FALSE) OR (is_private = TRUE AND password_hash IS NOT NULL)
    ),
    -- Constraint: terminal room not modifiable (app also validates)
    CONSTRAINT chk_room_final_state CHECK (
        room_status NOT IN ('FINISHED', 'CLOSED') OR 
        (room_status IN ('FINISHED', 'CLOSED') AND finished_at IS NOT NULL)
    ),
    -- Constraint: player count validity
    CONSTRAINT chk_players_count CHECK (current_players >= 0 AND current_players <= max_players)
);

-- Indexes
CREATE UNIQUE INDEX uk_game_room_code_not_deleted ON game_room(room_code) WHERE deleted_at IS NULL AND room_code IS NOT NULL;
CREATE INDEX idx_game_room_game_type ON game_room(game_type_id);
CREATE INDEX idx_game_room_owner ON game_room(owner_id);

-- No foreign keys; app ensures game_type_id and owner_id validity
CREATE INDEX idx_game_room_status ON game_room(room_status);
CREATE INDEX idx_game_room_status_created ON game_room(room_status, created_at DESC) WHERE deleted_at IS NULL;

-- Unique: client op idempotency (prevent duplicate room creation)
CREATE UNIQUE INDEX uk_game_room_client_op ON game_room(client_op_id) WHERE client_op_id IS NOT NULL AND deleted_at IS NULL;

-- Comments
COMMENT ON TABLE game_room IS 'Game room table';
COMMENT ON COLUMN game_room.room_code IS 'Room short code (shareable)';
COMMENT ON COLUMN game_room.client_op_id IS 'Client op ID (frontend UUID, idempotency)';
COMMENT ON COLUMN game_room.room_mode IS 'Room mode: PVP/PVE, required';
COMMENT ON COLUMN game_room.room_status IS 'Room status: WAITING/PLAYING/FINISHED/CLOSED, default WAITING';
COMMENT ON COLUMN game_room.max_players IS 'Max players (required, default 2)';
COMMENT ON COLUMN game_room.current_players IS 'Current players (synced from game_room_player, required, 0..max_players)';
COMMENT ON COLUMN game_room.password_hash IS 'Room password hash (bcrypt/argon2, never plaintext)';
COMMENT ON COLUMN game_room.config IS 'Room config (JSONB, e.g., {"rule": "STANDARD", "ai_difficulty": "medium"})';
```

### 4.3 Room Player Table (game_room_player)

```sql
CREATE TABLE game_room_player (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL, -- Room ID (app layer ensures)
    user_id UUID NOT NULL, -- User ID (app layer ensures)
    player_role VARCHAR(20) DEFAULT 'PLAYER', -- PLAYER, OBSERVER
    player_side VARCHAR(10), -- Side (X, O, BLACK, WHITE per game)
    seat_key VARCHAR(50), -- Seat key (for reconnection)
    is_ready BOOLEAN DEFAULT FALSE, -- Ready flag
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ, -- Leave time
    UNIQUE(room_id, user_id),
    -- Constraint: valid role
    CONSTRAINT chk_player_role_valid CHECK (
        player_role IS NULL OR player_role IN ('PLAYER', 'OBSERVER')
    ),
    -- Constraint: valid side
    CONSTRAINT chk_side_valid CHECK (
        player_side IS NULL OR player_side IN ('X','O','BLACK','WHITE')
    )
);

-- Indexes
CREATE INDEX idx_room_player_room ON game_room_player(room_id);
CREATE INDEX idx_room_player_user ON game_room_player(user_id);
CREATE INDEX idx_room_player_seat_key ON game_room_player(seat_key) WHERE seat_key IS NOT NULL;

-- Unique: one player per side in a room (NULL not constrained, so observers allowed)
CREATE UNIQUE INDEX uk_room_side ON game_room_player(room_id, player_side) WHERE player_side IS NOT NULL;

-- Unique: seat_key unique per room (reconnect idempotency)
CREATE UNIQUE INDEX uk_room_seat_key ON game_room_player(room_id, seat_key) WHERE seat_key IS NOT NULL;

-- Notes: no foreign keys; app ensures room_id/user_id validity; app cleans related data on delete

-- Comments
COMMENT ON TABLE game_room_player IS 'Room player table';
COMMENT ON COLUMN game_room_player.player_role IS 'Player role: PLAYER, OBSERVER; default PLAYER';
COMMENT ON COLUMN game_room_player.player_side IS 'Side (X, O, BLACK, WHITE; NULL means observer)';
COMMENT ON COLUMN game_room_player.seat_key IS 'Seat key (for reconnection, unique per room)';
COMMENT ON COLUMN game_room_player.joined_at IS 'Join time (required)';
```

### 4.4 Game Match Table (game_match)

```sql
CREATE TABLE game_match (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_code VARCHAR(50) UNIQUE, -- Match number
    room_id UUID, -- Room ID (optional; matched games may have no room; app ensures)
    game_type_id UUID NOT NULL, -- Game type ID (app layer ensures)
    match_type VARCHAR(20) NOT NULL, -- ROOM, MATCH, FRIEND
    match_status VARCHAR(20) DEFAULT 'PLAYING', -- PLAYING, FINISHED, ABANDONED
    player1_id UUID NOT NULL, -- Player1 user ID (app layer ensures)
    player2_id UUID, -- Player2 user ID (PVE may be NULL; app layer ensures)
    player1_side VARCHAR(10), -- Player1 side
    player2_side VARCHAR(10), -- Player2 side
    winner_id UUID, -- Winner user ID (NULL if draw; app layer ensures)
    result VARCHAR(20), -- WIN, DRAW, ABANDON
    match_config JSONB DEFAULT '{}'::jsonb, -- Config
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    duration_seconds INT, -- Duration (seconds)
    total_moves INT DEFAULT 0, -- Total moves
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ, -- Soft delete
    -- Constraint: valid match type
    CONSTRAINT chk_match_type_valid CHECK (match_type IN ('ROOM', 'MATCH', 'FRIEND')),
    -- Constraint: valid status
    CONSTRAINT chk_match_status CHECK (
        match_status IN ('PLAYING', 'FINISHED', 'ABANDONED')
    ),
    -- Constraint: result-winner consistency
    CONSTRAINT chk_match_result_winner CHECK (
        (result = 'DRAW' AND winner_id IS NULL)
        OR (result = 'WIN' AND winner_id IS NOT NULL)
        OR (result = 'ABANDON')
        OR (result IS NULL)
    ),
    -- Constraint: terminal match not modifiable (app also validates)
    CONSTRAINT chk_match_final_state CHECK (
        match_status NOT IN ('FINISHED', 'ABANDONED') OR 
        (match_status IN ('FINISHED', 'ABANDONED') AND finished_at IS NOT NULL)
    )
);

-- Indexes
CREATE UNIQUE INDEX uk_game_match_code ON game_match(match_code) WHERE match_code IS NOT NULL;
CREATE INDEX idx_game_match_room ON game_match(room_id) WHERE room_id IS NOT NULL;
CREATE INDEX idx_game_match_game_type ON game_match(game_type_id);
CREATE INDEX idx_game_match_player1 ON game_match(player1_id);
CREATE INDEX idx_game_match_player2 ON game_match(player2_id) WHERE player2_id IS NOT NULL;
CREATE INDEX idx_game_match_status ON game_match(match_status);
CREATE INDEX idx_game_match_started ON game_match(started_at DESC);
CREATE INDEX idx_game_match_players ON game_match(player1_id, player2_id) WHERE player2_id IS NOT NULL;
CREATE INDEX idx_match_not_deleted ON game_match(id) WHERE deleted_at IS NULL;

-- Notes: no foreign keys; app ensures ID validity; app handles history when deleting users

-- Comments
COMMENT ON TABLE game_match IS 'Game match table';
COMMENT ON COLUMN game_match.match_type IS 'Match type: ROOM, MATCH, FRIEND; required';
COMMENT ON COLUMN game_match.match_status IS 'Match status: PLAYING, FINISHED, ABANDONED; default PLAYING';
COMMENT ON COLUMN game_match.result IS 'Match result: WIN, DRAW, ABANDON';
COMMENT ON COLUMN game_match.match_config IS 'Match config (JSONB, e.g., {"rule": "STANDARD", "board_size": 15})';
COMMENT ON COLUMN game_match.started_at IS 'Start time (required, default now)';
COMMENT ON COLUMN game_match.deleted_at IS 'Soft delete time (terminal matches recommended to keep)';
```

### 4.5 Match Detail Table (game_match_detail)

```sql
CREATE TABLE game_match_detail (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL, -- Match ID (app layer ensures)
    move_number INT NOT NULL, -- Move number (starts at 1)
    player_id UUID NOT NULL, -- Player user ID (app layer ensures)
    move_data JSONB NOT NULL, -- Move data (JSONB, varies by game)
    move_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Move time
    is_valid BOOLEAN DEFAULT TRUE, -- Whether move is valid
    remark VARCHAR(500)
);

-- Indexes
CREATE INDEX idx_match_detail_match ON game_match_detail(match_id);
CREATE INDEX idx_match_detail_match_move ON game_match_detail(match_id, move_number);
CREATE INDEX idx_match_detail_player ON game_match_detail(player_id);
CREATE INDEX idx_match_detail_time ON game_match_detail(move_time);

-- Unique: move number unique per match (ensures replay correctness)
CREATE UNIQUE INDEX uk_match_move ON game_match_detail(match_id, move_number);

-- Notes: no foreign keys; app ensures match_id/player_id validity; app cleans details on delete

-- Comments
COMMENT ON TABLE game_match_detail IS 'Match detail table (records each move)';
COMMENT ON COLUMN game_match_detail.move_number IS 'Move number (starts at 1, unique per match)';
COMMENT ON COLUMN game_match_detail.move_data IS 'Move data (JSONB, e.g., {"x": 7, "y": 7, "piece": "X"})';
```

### 4.6 Match Snapshot Table (game_match_snapshot)

```sql
CREATE TABLE game_match_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL, -- Match ID (app layer ensures)
    snapshot_type VARCHAR(20) NOT NULL, -- BOARD (board snapshot), STATE (state snapshot)
    snapshot_data JSONB NOT NULL, -- Snapshot data
    step_number INT, -- Corresponding move number
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_match_snapshot_match ON game_match_snapshot(match_id);
CREATE INDEX idx_match_snapshot_match_step ON game_match_snapshot(match_id, step_number) WHERE step_number IS NOT NULL;

-- Notes: no foreign keys; app ensures match_id validity; app cleans snapshots on delete

-- Comments
COMMENT ON TABLE game_match_snapshot IS 'Match snapshot table (for replay, reconnection)';
COMMENT ON COLUMN game_match_snapshot.snapshot_type IS 'Snapshot type: BOARD, STATE';
COMMENT ON COLUMN game_match_snapshot.snapshot_data IS 'Snapshot data (JSONB, full board/state)';
```
# Database Design - Full Multiplayer Online Board Game Room

> Based on RBAC permission system, expanded with game, social, user growth, and matchmaking modules, suitable for a multiplayer online board game room platform.  
> **Design Principles**: Under microservices architecture, do not use foreign key constraints; ensure data consistency at the application layer. Keep unique indexes for uniqueness. Seal terminal states in state machines. Standardize idempotency keys.

---

## I. Overall Design

### 1.1 Architecture Positioning
- **Keycloak**: User authentication (username, password)
- **System Database**: Business data (users, roles, permissions, games, social, growth, matchmaking)
- **Association Field**: `keycloak_user_id` (UUID, corresponds to `sub` in JWT)

### 1.2 Module Division
```
System Modules
 ├── RBAC Permission System (user, role, permission, menu, department, log)
 ├── Game Module (game type, room, match, match detail)
 ├── Social Module (friend, friend request, chat session, chat message)
 ├── User Growth Module (score, level, score change record)
 └── Matchmaking Module (match queue, match record)
```

---

## II. Database Initialization

### 2.1 Create Database

```sql
-- Create database (requires superuser privileges)
-- Unified baseline: UTF8 + en_US.UTF-8 + UTC (cross-platform compatible, Docker default supported)
CREATE DATABASE gamehub_db
    WITH 
    OWNER = postgres                    -- Database owner (modify as needed)
    ENCODING = 'UTF8'                   -- Character encoding: UTF-8 (supports Chinese, English, Emoji and all Unicode characters)
    LC_COLLATE = 'en_US.UTF-8'         -- Collation: en_US.UTF-8 (natural English ordering, good cross-platform compatibility)
    LC_CTYPE = 'en_US.UTF-8'           -- Character classification: en_US.UTF-8 (good cross-platform compatibility)
    TEMPLATE = template0                -- Use template0 to avoid inheriting other DB settings
    CONNECTION LIMIT = -1;              -- Connection limit (-1 means unlimited)

-- Add database comment
COMMENT ON DATABASE gamehub_db IS 'Main database for multiplayer online board game room (international baseline: UTF8 + en_US.UTF-8 + UTC)';
```

> **Notes**:
> - **Unified Baseline**: Use `en_US.UTF-8` as global collation; no separate "Chinese DB" vs "English DB"—keep only this set
> - **English Natural Ordering**: A-Z, uppercase before lowercase, follows international standards
> - **Cross-Platform Compatibility**: Docker supports by default, consistent across platforms
> - **Chinese Support**: Chinese/Japanese etc. can be stored and displayed normally
> - **Internationalization Support**: For Chinese pinyin sorting or numeric-aware sorting, use ICU Collation per column or per query (see 2.6)
> - **Timezone Unified**: Database uses UTC; application layer localizes on display by user timezone
>
> **Why choose `en_US.UTF-8`**:
> - ✅ **User Experience**: English natural ordering (A-Z) matches most users' expectations, especially for international projects
> - ✅ **Deployment Convenience**: Docker supports by default, no extra locale setup
> - ✅ **Good Compatibility**: Consistent ordering across environments
> - ⚠️ **Performance**: Slightly lower than `C.UTF-8` (about 5-10%), negligible for most scenarios
> - ⚠️ **Chinese Sorting**: Chinese sorted by Unicode code points (not pinyin); use ICU Collation for pinyin sorting (see 2.6)
>
> **If pursuing extreme performance or absolutely consistent cross-platform sorting**, consider `C.UTF-8`, but it loses the advantage of natural English ordering.

### 2.2 Connect and Set Extensions

```sql
-- Connect to the newly created database
\c gamehub_db

-- Create extensions (all use IF NOT EXISTS, re-entrant)
-- UUID generation (PostgreSQL 13+ already has gen_random_uuid(), but for older versions and more functions, install pgcrypto)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- Provides gen_random_uuid() and other crypto functions

-- Fuzzy/similarity search (supports Chinese fuzzy search)
CREATE EXTENSION IF NOT EXISTS "pg_trgm";    -- Provides trigram similarity, supports ILIKE fuzzy search

-- Case-insensitive string type
CREATE EXTENSION IF NOT EXISTS "citext";     -- Provides CITEXT type, case-insensitive comparisons

-- Note: JSONB index support is built-in, no extra extension needed
```

> **Notes**:
> - **pgcrypto**: PostgreSQL 13+ has `gen_random_uuid()`, but `pgcrypto` provides more crypto functions—recommended
> - **pg_trgm**: Supports Chinese fuzzy search, use with GIN index and `ILIKE '%keyword%'`
> - **citext**: Suitable for fields like email, username requiring case-insensitive comparison

### 2.3 Database Users and Roles (Recommended)

```sql
-- Create database roles (least privilege)
CREATE ROLE gamehub_owner NOLOGIN;  -- Database owner role
CREATE ROLE gamehub_rw NOLOGIN;     -- Read-write role
CREATE ROLE gamehub_ro NOLOGIN;     -- Read-only role

-- Create application user
CREATE USER gamehub_app WITH PASSWORD '***strong_password***';
GRANT gamehub_rw TO gamehub_app;

-- Set database owner
ALTER DATABASE gamehub_db OWNER TO gamehub_owner;

-- Create application schema (put business objects in app schema for management & permissions)
CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION gamehub_owner;
ALTER DATABASE gamehub_db SET search_path = app, public;

-- Revoke public schema privileges (hardening)
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Grant schema usage
GRANT USAGE ON SCHEMA app TO gamehub_rw, gamehub_ro;

-- Default privileges (future objects auto-inherit, no manual grants)
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO gamehub_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT SELECT ON TABLES TO gamehub_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT USAGE, SELECT ON SEQUENCES TO gamehub_rw, gamehub_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA app 
    GRANT EXECUTE ON FUNCTIONS TO gamehub_rw;
```

> **Notes**:
> - **Schema Isolation**: Put all business tables in `app` schema for management and access control
> - **Explicit Prefix**: All later objects should explicitly use `app.` prefix (e.g., `app.sys_user`) to avoid search_path hijack
> - **Least Privilege**: Application uses `gamehub_app` user; avoid superuser

### 2.4 Database Configuration (Timezone and Connection Guardrails)

```sql
-- View current database config
SHOW timezone;
SHOW server_encoding;
SELECT datcollate, datctype FROM pg_database WHERE datname = current_database();

-- Set database-level parameters (requires superuser)
-- Timezone set to China (Asia/Shanghai)
ALTER DATABASE gamehub_db SET timezone = 'Asia/Shanghai';
ALTER DATABASE gamehub_db SET log_timezone = 'Asia/Shanghai';

-- Connection guardrails (prevent long-running queries from hogging resources)
ALTER DATABASE gamehub_db SET statement_timeout = '30s';
ALTER DATABASE gamehub_db SET lock_timeout = '5s';
ALTER DATABASE gamehub_db SET idle_in_transaction_session_timeout = '60s';
```

> **Notes**:
> - **Timezone set to China time**: Database timezone set to `Asia/Shanghai`; all time fields use `TIMESTAMPTZ`. Note: `TIMESTAMPTZ` stores in UTC internally; display rendered per session timezone (currently `Asia/Shanghai`)
> - **Connection guardrails**: Adjust timeouts per business needs (complex reports may need higher `statement_timeout`)
> - **Connection pool config** (at app layer, e.g., HikariCP):
>   - `maximumPoolSize`: set by concurrency (suggest 10-50)
>   - `minimumIdle`: keep minimum connections (suggest 5-10)
>   - `connectionTimeout`: connection timeout (suggest 30000ms)
>   - `idleTimeout`: idle connection timeout (suggest 600000ms)
>   - `maxLifetime`: connection max lifetime (suggest 1800000ms)

### 2.5 Charset and Collation Notes

- **ENCODING = 'UTF8'**: UTF-8 encoding, supports all Unicode (Chinese/English/Emoji)

- **LC_COLLATE / LC_CTYPE = 'en_US.UTF-8'**: Unified baseline  
  - **English Natural Ordering**: A-Z, uppercase first, lowercase later, matches intl standard  
  - **Cross-Platform Compatible**: Docker default, good portability  
  - **Chinese Support**: Chinese/Japanese stored and displayed fine  
  - **Unified Standard**: No more separate "Chinese DB"/"English DB"; one global setting

- **Need Chinese/natural sorting**: Use ICU Collation (PostgreSQL 15+)  
  - Chinese pinyin sort: `zh_pinyin` (see 2.6)  
  - Numeric-aware sort: `en_numeric` (see 2.6)  
  - Specify per column or per query; no global change needed

> **Design Principle**: Unified baseline = `UTF8 + en_US.UTF-8 + UTC`; no dual scripts. For pinyin or natural language sorting, add ICU Collation on column or query—no need to change global config.
>
> **Professional Advice**:
> - **International projects**: `en_US.UTF-8` is reasonable, meets expectations, easy to deploy
> - **High-performance scenarios**: If sort performance is bottleneck (large sorts), consider `C.UTF-8`, but handle sorting logic in app
> - **Chinese-first**: If users are mainly Chinese and need pinyin sorting, use ICU Collation on key fields (nickname, menu name) instead of changing global config

### 2.6 ICU Collation (Optional, PostgreSQL 15+)

**Default use `en_US.UTF-8`**. When needing "Chinese pinyin / numeric-aware sorting", apply ICU only to **specific columns or queries**, leaving the global baseline unaffected.

```sql
-- Create ICU collations (create as needed)
-- Numeric-aware English sort ('item2' < 'item10')
CREATE COLLATION IF NOT EXISTS "en_numeric"
  (provider = icu, locale = 'en-u-kn-true');

-- Chinese pinyin sort
CREATE COLLATION IF NOT EXISTS "zh_pinyin"
  (provider = icu, locale = 'zh-u-co-pinyin');

-- Usage A: Column-level (column defaults to pinyin/numeric-aware comparison)
-- Example: add Chinese pinyin collation to nickname column
-- ALTER TABLE app.sys_user
--   ALTER COLUMN nickname TYPE VARCHAR(50) COLLATE "zh_pinyin";

-- Usage B: Query-level (takes effect only in that query, recommended)
-- Example: sort by Chinese pinyin in query
-- SELECT nickname FROM app.sys_user ORDER BY nickname COLLATE "zh_pinyin";
-- SELECT menu_name FROM app.sys_menu ORDER BY menu_name COLLATE "en_numeric";
```

> **Notes**:
> - **Use on demand**: Only where pinyin or numeric-aware sorting is needed
> - **No global impact**: Baseline remains `en_US.UTF-8`, ensuring cross-platform compatibility
> - **PostgreSQL version**: ICU Collation requires PostgreSQL 15+; ignore if older

### 2.7 Script Organization (Re-entrant)

**Layered scripts**, all statements use `IF NOT EXISTS` / `OR REPLACE`, supporting repeated execution:
```
database/
├── 01_db.sql          # DB creation, extensions, parameters, roles, schema, permissions
├── 02_tables.sql     # Create tables (all with app. prefix)
├── 03_indexes.sql    # Create indexes
├── 04_triggers.sql   # Create triggers
└── 05_seed.sql       # Seed data
```

**Execution Principles**:
- All DDL statements can run repeatedly without side effects
- Uniformly use `IF NOT EXISTS` / `OR REPLACE`
- Avoid `DROP` unless explicitly rebuilding

### 2.8 Quick Self-Check Checklist

```sql
-- Check database config
SHOW server_encoding;    -- Should be: UTF8
SHOW lc_collate;         -- Should be: en_US.UTF-8
SHOW lc_ctype;           -- Should be: en_US.UTF-8
SHOW timezone;           -- Should be: UTC
SHOW log_timezone;       -- Should be: UTC

-- Check extensions
SELECT extname FROM pg_extension WHERE extname IN ('pgcrypto', 'pg_trgm', 'citext');
-- Should return: pgcrypto, pg_trgm, citext

-- Check schema and permissions
SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'app';
-- Should return: app

-- Check roles
SELECT rolname FROM pg_roles WHERE rolname IN ('gamehub_owner', 'gamehub_rw', 'gamehub_ro', 'gamehub_app');
-- Should return: gamehub_owner, gamehub_rw, gamehub_ro, gamehub_app
```

---

## III. RBAC Permission System

### 3.1 User Table (sys_user)

```sql
CREATE TABLE sys_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id UUID NOT NULL UNIQUE, -- Keycloak user ID (corresponds to sub in JWT, must be non-null)
    username CITEXT NOT NULL, -- Username (case-insensitive)
    nickname VARCHAR(50), -- Nickname
    email CITEXT, -- Email (case-insensitive)
    phone VARCHAR(20), -- Phone
    avatar_url VARCHAR(500), -- Avatar URL
    user_type VARCHAR(20) DEFAULT 'NORMAL', -- User type: NORMAL (regular), ADMIN (administrator)
    dept_id UUID, -- Department ID (app layer ensures existence)
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    player_id BIGINT NOT NULL DEFAULT nextval('player_id_seq'), -- Player ID (unique numeric ID for lookup/share, 6-9 digits, sequence)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid user type
    CONSTRAINT chk_user_type_valid CHECK (user_type IN ('NORMAL', 'ADMIN')),
    -- Constraint: valid status
    CONSTRAINT chk_user_status_valid CHECK (status IN (0, 1)),
    -- Constraint: player ID uniqueness
    CONSTRAINT sys_user_player_id_key UNIQUE (player_id)
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_user_keycloak_id ON sys_user(keycloak_user_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_sys_user_username_not_deleted ON sys_user(username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_sys_user_player_id ON sys_user(player_id) WHERE deleted_at IS NULL AND player_id IS NOT NULL;
CREATE INDEX idx_sys_user_email ON sys_user(email) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_user_phone ON sys_user(phone) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_user_dept ON sys_user(dept_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_user_status ON sys_user(status) WHERE deleted_at IS NULL;

-- Sequence (for player_id auto-increment, skip if exists)
CREATE SEQUENCE IF NOT EXISTS player_id_seq
    START WITH 1000000
    INCREMENT BY 1
    MINVALUE 1000000
    MAXVALUE 999999999
    NO CYCLE;

-- Bind sequence ownership for migration/deletion together
ALTER SEQUENCE player_id_seq OWNED BY sys_user.player_id;

-- Comments
COMMENT ON TABLE sys_user IS 'User table';
COMMENT ON COLUMN sys_user.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_user.keycloak_user_id IS 'Keycloak user ID (corresponds to sub in JWT, must be non-null, unique)';
COMMENT ON COLUMN sys_user.username IS 'Username (unique, case-insensitive, CITEXT, must be non-null)';
COMMENT ON COLUMN sys_user.nickname IS 'Nickname (display name)';
COMMENT ON COLUMN sys_user.email IS 'Email (case-insensitive, CITEXT)';
COMMENT ON COLUMN sys_user.phone IS 'Phone';
COMMENT ON COLUMN sys_user.avatar_url IS 'Avatar URL (user avatar image address)';
COMMENT ON COLUMN sys_user.user_type IS 'User type enum: NORMAL (regular), ADMIN (administrator), default NORMAL';
COMMENT ON COLUMN sys_user.dept_id IS 'Department ID (relates to sys_dept.id, app layer ensures existence)';
COMMENT ON COLUMN sys_user.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_user.remark IS 'Remark';
COMMENT ON COLUMN sys_user.player_id IS 'Player ID (unique numeric ID for lookup/share, 6-9 digits, sequence, must be non-null)';
COMMENT ON COLUMN sys_user.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
COMMENT ON COLUMN sys_user.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
COMMENT ON COLUMN sys_user.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL means not deleted)';
```

### 3.2 User Profile Table (sys_user_profile)

```sql
CREATE TABLE sys_user_profile (
    user_id UUID PRIMARY KEY, -- User ID (app layer ensures existence)
    bio VARCHAR(500), -- Bio
    locale VARCHAR(10) DEFAULT 'zh-CN', -- Language preference
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai', -- Timezone
    settings JSONB DEFAULT '{}'::jsonb, -- User settings (JSONB, e.g., {"theme": "dark", "notifications": true})
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_user_profile_locale ON sys_user_profile(locale);

-- Comments
COMMENT ON TABLE sys_user_profile IS 'User profile table';
COMMENT ON COLUMN sys_user_profile.user_id IS 'User ID (PK, relates to sys_user.id, app layer ensures existence)';
COMMENT ON COLUMN sys_user_profile.bio IS 'Bio (self-introduction)';
COMMENT ON COLUMN sys_user_profile.locale IS 'Language preference (e.g., zh-CN, en-US), default zh-CN';
COMMENT ON COLUMN sys_user_profile.timezone IS 'Timezone (e.g., Asia/Shanghai, UTC), default Asia/Shanghai';
COMMENT ON COLUMN sys_user_profile.settings IS 'User settings (JSONB, stores preferences such as {"theme": "dark", "notifications": true})';
COMMENT ON COLUMN sys_user_profile.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
COMMENT ON COLUMN sys_user_profile.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
```

### 3.3 Department Table (sys_dept)

```sql
CREATE TABLE sys_dept (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dept_name VARCHAR(100) NOT NULL, -- Department name
    dept_code VARCHAR(50) UNIQUE, -- Department code
    parent_id UUID, -- Parent department ID (app layer ensures existence)
    leader_id UUID, -- Leader user ID (app layer ensures existence)
    phone VARCHAR(20), -- Phone
    email VARCHAR(100), -- Email
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    settings JSONB DEFAULT '{}'::jsonb, -- Department settings (JSONB)
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid status
    CONSTRAINT chk_dept_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_dept_code_not_deleted ON sys_dept(dept_code) WHERE deleted_at IS NULL AND dept_code IS NOT NULL;
CREATE INDEX idx_sys_dept_parent ON sys_dept(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_dept_leader ON sys_dept(leader_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_dept_status ON sys_dept(status) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE sys_dept IS 'Department table';
COMMENT ON COLUMN sys_dept.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_dept.dept_name IS 'Department name (must be non-null)';
COMMENT ON COLUMN sys_dept.dept_code IS 'Department code (unique business identifier)';
COMMENT ON COLUMN sys_dept.parent_id IS 'Parent department ID (relates to sys_dept.id, supports tree; NULL for root; app layer ensures existence)';
COMMENT ON COLUMN sys_dept.leader_id IS 'Leader user ID (relates to sys_user.id; app layer ensures existence)';
COMMENT ON COLUMN sys_dept.phone IS 'Phone';
COMMENT ON COLUMN sys_dept.email IS 'Email';
COMMENT ON COLUMN sys_dept.sort_order IS 'Sort order (smaller is earlier), default 0';
COMMENT ON COLUMN sys_dept.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_dept.settings IS 'Department settings (JSONB, flexible config)';
COMMENT ON COLUMN sys_dept.remark IS 'Remark';
COMMENT ON COLUMN sys_dept.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
COMMENT ON COLUMN sys_dept.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
COMMENT ON COLUMN sys_dept.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL means not deleted)';
```

### 3.4 Role Table (sys_role)

```sql
CREATE TABLE sys_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code VARCHAR(50) NOT NULL UNIQUE, -- Role code
    role_name VARCHAR(50) NOT NULL, -- Role name
    role_desc VARCHAR(200), -- Role description
    data_scope VARCHAR(20) DEFAULT 'ALL', -- Data scope: ALL, DEPT, DEPT_AND_CHILD, SELF
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid data scope
    CONSTRAINT chk_role_data_scope_valid CHECK (data_scope IN ('ALL', 'DEPT', 'DEPT_AND_CHILD', 'SELF')),
    -- Constraint: valid status
    CONSTRAINT chk_role_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_role_code_not_deleted ON sys_role(role_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_role_status ON sys_role(status) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE sys_role IS 'Role table';
COMMENT ON COLUMN sys_role.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_role.role_code IS 'Role code (unique business identifier, must be non-null)';
COMMENT ON COLUMN sys_role.role_name IS 'Role name (must be non-null)';
COMMENT ON COLUMN sys_role.role_desc IS 'Role description';
COMMENT ON COLUMN sys_role.data_scope IS 'Data scope enum: ALL, DEPT, DEPT_AND_CHILD, SELF; default ALL';
COMMENT ON COLUMN sys_role.sort_order IS 'Sort order (smaller earlier), default 0';
COMMENT ON COLUMN sys_role.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_role.remark IS 'Remark';
COMMENT ON COLUMN sys_role.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
COMMENT ON COLUMN sys_role.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
COMMENT ON COLUMN sys_role.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL means not deleted)';
```

### 3.5 Permission Table (sys_permission)

```sql
CREATE TABLE sys_permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_code VARCHAR(100) NOT NULL UNIQUE, -- Permission code
    permission_name VARCHAR(100) NOT NULL, -- Permission name
    permission_type VARCHAR(20) NOT NULL, -- Permission type: MENU, BUTTON, API
    resource_type VARCHAR(50), -- Resource type
    resource_path VARCHAR(500), -- Resource path
    http_method VARCHAR(10), -- HTTP method (GET, POST, PUT, DELETE, etc.)
    data_expr JSONB DEFAULT '{}'::jsonb, -- Data permission expression (JSONB, flexible rules)
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid permission type
    CONSTRAINT chk_permission_type_valid CHECK (permission_type IN ('MENU', 'BUTTON', 'API')),
    -- Constraint: valid HTTP method
    CONSTRAINT chk_permission_http_method_valid CHECK (
        http_method IS NULL OR http_method IN ('GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS')
    ),
    -- Constraint: valid status
    CONSTRAINT chk_permission_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_sys_permission_code_not_deleted ON sys_permission(permission_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_permission_type ON sys_permission(permission_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_permission_resource ON sys_permission(resource_type, resource_path) WHERE deleted_at IS NULL;

-- Unique index: prevent duplicate API permissions (resource_path + http_method unique)
CREATE UNIQUE INDEX uk_sys_permission_api ON sys_permission(resource_path, http_method) 
    WHERE deleted_at IS NULL AND resource_path IS NOT NULL AND http_method IS NOT NULL;

-- Comments
COMMENT ON TABLE sys_permission IS 'Permission table';
COMMENT ON COLUMN sys_permission.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_permission.permission_code IS 'Permission code (unique business identifier, must be non-null)';
COMMENT ON COLUMN sys_permission.permission_name IS 'Permission name (must be non-null)';
COMMENT ON COLUMN sys_permission.permission_type IS 'Permission type enum: MENU, BUTTON, API, must be non-null';
COMMENT ON COLUMN sys_permission.resource_type IS 'Resource type';
COMMENT ON COLUMN sys_permission.resource_path IS 'Resource path (API path or resource identifier)';
COMMENT ON COLUMN sys_permission.http_method IS 'HTTP method enum: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS (for API permissions)';
COMMENT ON COLUMN sys_permission.data_expr IS 'Data permission expression (JSONB, flexible rules, e.g., {"dept_ids": [1,2,3], "user_ids": [10,20]})';
COMMENT ON COLUMN sys_permission.sort_order IS 'Sort order (smaller earlier), default 0';
COMMENT ON COLUMN sys_permission.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_permission.remark IS 'Remark';
COMMENT ON COLUMN sys_permission.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
COMMENT ON COLUMN sys_permission.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
COMMENT ON COLUMN sys_permission.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL means not deleted)';
```

### 3.6 Menu Table (sys_menu)

```sql
CREATE TABLE sys_menu (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    menu_name VARCHAR(100) NOT NULL, -- Menu name
    menu_type VARCHAR(20) NOT NULL, -- Menu type: DIRECTORY, MENU, BUTTON
    parent_id UUID, -- Parent menu ID (app layer ensures existence)
    path VARCHAR(200), -- Route path
    component VARCHAR(200), -- Component path
    icon VARCHAR(100), -- Icon
    permission_code VARCHAR(100), -- Permission code (links permission table)
    route_meta JSONB DEFAULT '{}'::jsonb, -- Route metadata (JSONB, frontend route config)
    sort_order INT DEFAULT 0, -- Sort
    status SMALLINT DEFAULT 1, -- Status: 0-disabled, 1-enabled
    remark VARCHAR(500), -- Remark
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid menu type
    CONSTRAINT chk_menu_type_valid CHECK (menu_type IN ('DIRECTORY', 'MENU', 'BUTTON')),
    -- Constraint: valid status
    CONSTRAINT chk_menu_status_valid CHECK (status IN (0, 1))
);

-- Indexes
CREATE UNIQUE INDEX uk_menu_path ON sys_menu(path) WHERE deleted_at IS NULL AND path IS NOT NULL;
CREATE INDEX idx_sys_menu_parent ON sys_menu(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_menu_type ON sys_menu(menu_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_menu_status ON sys_menu(status) WHERE deleted_at IS NULL;

-- Unique index: menu name unique under same parent
CREATE UNIQUE INDEX uk_sys_menu_parent_name ON sys_menu(parent_id, menu_name) WHERE deleted_at IS NULL;

-- Index: permission linkage query (via permission_code)
CREATE INDEX idx_sys_menu_permission_code ON sys_menu(permission_code) WHERE deleted_at IS NULL AND permission_code IS NOT NULL;

-- Comments
COMMENT ON TABLE sys_menu IS 'Menu table';
COMMENT ON COLUMN sys_menu.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_menu.menu_name IS 'Menu name (must be non-null, unique under same parent)';
COMMENT ON COLUMN sys_menu.menu_type IS 'Menu type enum: DIRECTORY (folder), MENU (menu item), BUTTON (button), must be non-null';
COMMENT ON COLUMN sys_menu.parent_id IS 'Parent menu ID (relates to sys_menu.id, NULL for root; app layer ensures existence)';
COMMENT ON COLUMN sys_menu.path IS 'Route path (frontend route address, globally unique)';
COMMENT ON COLUMN sys_menu.component IS 'Component path (frontend component file path)';
COMMENT ON COLUMN sys_menu.icon IS 'Icon';
COMMENT ON COLUMN sys_menu.permission_code IS 'Permission code (links sys_permission.permission_code for auth)';
COMMENT ON COLUMN sys_menu.route_meta IS 'Route metadata (JSONB, e.g., {"title": "Home", "hidden": false, "affix": true})';
COMMENT ON COLUMN sys_menu.sort_order IS 'Sort order (smaller earlier), default 0';
COMMENT ON COLUMN sys_menu.status IS 'Status enum: 0 (disabled), 1 (enabled), default 1';
COMMENT ON COLUMN sys_menu.remark IS 'Remark';
COMMENT ON COLUMN sys_menu.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
COMMENT ON COLUMN sys_menu.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
COMMENT ON COLUMN sys_menu.deleted_at IS 'Soft delete time (TIMESTAMPTZ, NULL means not deleted)';
```

### 3.7 User-Role Mapping Table (sys_user_role)

```sql
CREATE TABLE sys_user_role (
    user_id UUID NOT NULL, -- User ID (app layer ensures existence)
    role_id UUID NOT NULL, -- Role ID (app layer ensures existence)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id) -- Composite PK ensures uniqueness
);

-- Index (for reverse lookup by role_id)
CREATE INDEX idx_user_role_role ON sys_user_role(role_id);

-- Comments
COMMENT ON TABLE sys_user_role IS 'User-role mapping (many-to-many)';
COMMENT ON COLUMN sys_user_role.user_id IS 'User ID (relates to sys_user.id, composite PK, app layer ensures existence)';
COMMENT ON COLUMN sys_user_role.role_id IS 'Role ID (relates to sys_role.id, composite PK, app layer ensures existence)';
COMMENT ON COLUMN sys_user_role.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
```

### 3.8 Role-Permission Mapping Table (sys_role_permission)

```sql
CREATE TABLE sys_role_permission (
    role_id UUID NOT NULL, -- Role ID (app layer ensures existence)
    permission_id UUID NOT NULL, -- Permission ID (app layer ensures existence)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id) -- Composite PK ensures uniqueness
);

-- Index (for reverse lookup by permission_id)
CREATE INDEX idx_role_permission_permission ON sys_role_permission(permission_id);

-- Comments
COMMENT ON TABLE sys_role_permission IS 'Role-permission mapping (many-to-many)';
COMMENT ON COLUMN sys_role_permission.role_id IS 'Role ID (relates to sys_role.id, composite PK, app layer ensures existence)';
COMMENT ON COLUMN sys_role_permission.permission_id IS 'Permission ID (relates to sys_permission.id, composite PK, app layer ensures existence)';
COMMENT ON COLUMN sys_role_permission.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
```

### 3.9 Role-Menu Mapping Table (sys_role_menu)

```sql
CREATE TABLE sys_role_menu (
    role_id UUID NOT NULL, -- Role ID (app layer ensures existence)
    menu_id UUID NOT NULL, -- Menu ID (app layer ensures existence)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, menu_id) -- Composite PK ensures uniqueness
);

-- Index (for reverse lookup by menu_id)
CREATE INDEX idx_role_menu_menu ON sys_role_menu(menu_id);

-- Comments
COMMENT ON TABLE sys_role_menu IS 'Role-menu mapping (many-to-many)';
COMMENT ON COLUMN sys_role_menu.role_id IS 'Role ID (relates to sys_role.id, composite PK, app layer ensures existence)';
COMMENT ON COLUMN sys_role_menu.menu_id IS 'Menu ID (relates to sys_menu.id, composite PK, app layer ensures existence)';
COMMENT ON COLUMN sys_role_menu.created_at IS 'Created time (TIMESTAMPTZ, auto set)';
```

### 3.10 Login Log Table (sys_login_log)

```sql
CREATE TABLE sys_login_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sys_user_id UUID, -- User ID (app layer ensures existence)
    username VARCHAR(50), -- Username (redundant to avoid JOIN)
    login_type VARCHAR(20), -- Login type: PASSWORD, OAUTH
    ip_address VARCHAR(50), -- IP address
    location VARCHAR(200), -- Login location
    device_type VARCHAR(50), -- Device type
    browser VARCHAR(100), -- Browser
    os VARCHAR(100), -- OS
    status SMALLINT, -- Status: 0-failed, 1-success
    failure_reason VARCHAR(500), -- Failure reason
    login_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid login type
    CONSTRAINT chk_login_type_valid CHECK (login_type IS NULL OR login_type IN ('PASSWORD', 'OAUTH')),
    -- Constraint: valid status
    CONSTRAINT chk_login_status_valid CHECK (status IS NULL OR status IN (0, 1))
);

-- Indexes
CREATE INDEX idx_login_log_user ON sys_login_log(sys_user_id);
CREATE INDEX idx_login_log_time ON sys_login_log(login_time DESC);
CREATE INDEX idx_login_log_ip ON sys_login_log(ip_address);
CREATE INDEX idx_login_log_status ON sys_login_log(status);

-- Comments
COMMENT ON TABLE sys_login_log IS 'Login log table';
COMMENT ON COLUMN sys_login_log.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_login_log.sys_user_id IS 'User ID (relates to sys_user.id, app layer ensures existence)';
COMMENT ON COLUMN sys_login_log.username IS 'Username (redundant to avoid JOIN)';
COMMENT ON COLUMN sys_login_log.login_type IS 'Login type enum: PASSWORD, OAUTH';
COMMENT ON COLUMN sys_login_log.ip_address IS 'IP address (client IP at login)';
COMMENT ON COLUMN sys_login_log.location IS 'Login location (geo from IP)';
COMMENT ON COLUMN sys_login_log.device_type IS 'Device type (e.g., PC, Mobile, Tablet)';
COMMENT ON COLUMN sys_login_log.browser IS 'Browser (e.g., Chrome, Firefox, Safari)';
COMMENT ON COLUMN sys_login_log.os IS 'Operating system (e.g., Windows, macOS, Linux, iOS, Android)';
COMMENT ON COLUMN sys_login_log.status IS 'Status enum: 0 (fail), 1 (success)';
COMMENT ON COLUMN sys_login_log.failure_reason IS 'Failure reason (error info when login failed)';
COMMENT ON COLUMN sys_login_log.login_time IS 'Login time (TIMESTAMPTZ, auto set)';
```

### 3.11 Operation Log Table (sys_op_log)

```sql
CREATE TABLE sys_op_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sys_user_id UUID, -- User ID (app layer ensures existence)
    username VARCHAR(50), -- Username (redundant)
    target_name VARCHAR(100), -- Operation target name
    method VARCHAR(20), -- Request method (GET, POST, PUT, DELETE, etc.)
    request_url VARCHAR(500), -- Request URL
    request_params TEXT, -- Request parameters
    response_code INT, -- Response status code
    ip_address VARCHAR(50), -- IP address
    user_agent VARCHAR(500), -- User agent
    error_message TEXT, -- Error message
    duration_ms INT, -- Duration (ms)
    op_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_op_log_user ON sys_op_log(sys_user_id);
CREATE INDEX idx_op_log_time ON sys_op_log(op_time DESC);
CREATE INDEX idx_op_log_url ON sys_op_log(request_url);
CREATE INDEX idx_op_log_method ON sys_op_log(method);

-- Comments
COMMENT ON TABLE sys_op_log IS 'Operation log table';
COMMENT ON COLUMN sys_op_log.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_op_log.sys_user_id IS 'User ID (relates to sys_user.id, app layer ensures existence)';
COMMENT ON COLUMN sys_op_log.username IS 'Username (redundant to avoid JOIN)';
COMMENT ON COLUMN sys_op_log.target_name IS 'Operation target name';
COMMENT ON COLUMN sys_op_log.method IS 'Request method (HTTP method: GET, POST, PUT, DELETE, PATCH, etc.)';
COMMENT ON COLUMN sys_op_log.request_url IS 'Request URL (full path)';
COMMENT ON COLUMN sys_op_log.request_params IS 'Request parameters (body or query, TEXT)';
COMMENT ON COLUMN sys_op_log.response_code IS 'Response status code (HTTP, e.g., 200, 404, 500)';
COMMENT ON COLUMN sys_op_log.ip_address IS 'IP address (client IP)';
COMMENT ON COLUMN sys_op_log.user_agent IS 'User agent';
COMMENT ON COLUMN sys_op_log.error_message IS 'Error message (details on failure)';
COMMENT ON COLUMN sys_op_log.duration_ms IS 'Duration in ms (request start to response end)';
COMMENT ON COLUMN sys_op_log.op_time IS 'Operation time (TIMESTAMPTZ, auto set)';
```

### 3.12 Notification Table (sys_notification)

```sql
CREATE TABLE sys_notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,               -- Target user ID (corresponds to keycloak_user_id / sys_user.id, app layer ensures existence)
    type VARCHAR(50) NOT NULL,           -- Notification type: FRIEND_REQUEST, SYSTEM_ALERT, GAME_INVITE, etc.
    title VARCHAR(200) NOT NULL,         -- Notification title
    content TEXT NOT NULL,               -- Notification content
    from_user_id VARCHAR(64),            -- Triggering user ID (Keycloak userId, optional)
    ref_type VARCHAR(50),                 -- Related business type (e.g., FRIEND_REQUEST)
    ref_id UUID,                         -- Related business ID
    payload JSONB,                       -- Pass-through data (e.g., refId, requestMessage, roomId)
    actions TEXT[],                      -- Optional actions: ['ACCEPT','REJECT'], etc.
    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', -- UNREAD / READ / ARCHIVED / DELETED
    source_service VARCHAR(50),          -- Source service (e.g., system-service, game-service)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid status
    CONSTRAINT chk_sys_notification_status_valid CHECK (status IN ('UNREAD','READ','ARCHIVED','DELETED'))
);

-- Indexes
CREATE INDEX idx_sys_notification_user_status ON sys_notification(user_id, status, created_at DESC);
CREATE INDEX idx_sys_notification_user_created ON sys_notification(user_id, created_at DESC);
CREATE INDEX idx_sys_notification_ref ON sys_notification(ref_type, ref_id) WHERE ref_id IS NOT NULL;

-- Comments
COMMENT ON TABLE sys_notification IS 'Global notification table (unified management of all business notifications, supports offline push and persistence)';
COMMENT ON COLUMN sys_notification.id IS 'Primary key ID (UUID)';
COMMENT ON COLUMN sys_notification.user_id IS 'Target user ID (relates to sys_user.id, recipient, app layer ensures existence, must be non-null)';
COMMENT ON COLUMN sys_notification.type IS 'Notification type enum: FRIEND_REQUEST, FRIEND_RESULT, SYSTEM_ALERT, GAME_INVITE, GAME_RESULT, etc., must be non-null';
COMMENT ON COLUMN sys_notification.title IS 'Notification title (shown in list, max 200 chars, must be non-null)';
COMMENT ON COLUMN sys_notification.content IS 'Notification content (detailed text, TEXT, must be non-null)';
COMMENT ON COLUMN sys_notification.from_user_id IS 'Triggering user ID (Keycloak userId, e.g., friend request initiator, optional)';
COMMENT ON COLUMN sys_notification.ref_type IS 'Related business type (e.g., FRIEND_REQUEST, for linking business tables and dedup)';
COMMENT ON COLUMN sys_notification.ref_id IS 'Related business ID (e.g., friend_request.id, for linking records and navigation)';
COMMENT ON COLUMN sys_notification.payload IS 'Pass-through data (JSONB, e.g., {"friendRequestId": "...", "requestMessage": "...", "requesterName": "...", "notificationId": "..."})';
COMMENT ON COLUMN sys_notification.actions IS 'Action buttons (TEXT[], e.g., ["ACCEPT", "REJECT"]; NULL/empty means display only)';
COMMENT ON COLUMN sys_notification.status IS 'Notification status: UNREAD, READ, ARCHIVED, DELETED; default UNREAD; must be non-null';
COMMENT ON COLUMN sys_notification.source_service IS 'Source service (e.g., system-service, game-service, chat-service)';
COMMENT ON COLUMN sys_notification.created_at IS 'Created time (TIMESTAMPTZ, auto set, must be non-null)';
COMMENT ON COLUMN sys_notification.read_at IS 'Read time (TIMESTAMPTZ, set when user reads/marks read)';
COMMENT ON COLUMN sys_notification.archived_at IS 'Archived time (TIMESTAMPTZ, set when user archives)';
COMMENT ON COLUMN sys_notification.deleted_at IS 'Deleted time (TIMESTAMPTZ, soft delete, NULL means not deleted)';
COMMENT ON COLUMN sys_notification.updated_at IS 'Updated time (TIMESTAMPTZ, trigger auto-updates)';
```

**Dictionary Notes**:

1. **Notification Type (type)**:
   - `FRIEND_REQUEST`: Friend request (recipient sees, with action buttons)
   - `FRIEND_RESULT`: Friend request result (applicant sees, no action)
   - `SYSTEM_ALERT`: System alert (maintenance, events, etc.)
   - `GAME_INVITE`: Game invite (with action buttons)
   - `GAME_RESULT`: Game result (post-match)

2. **Notification Status (status)**:
   - `UNREAD`: Unread (shows in unread list, counts in unread total)
   - `READ`: Read (viewed, not counted in unread)
   - `ARCHIVED`: Archived (user archived, not in normal list)
   - `DELETED`: Deleted (soft delete, hidden)

3. **Actions (actions)**:
   - `ACCEPT`: Accept (e.g., approve friend request)
   - `REJECT`: Reject (e.g., decline friend request)
   - Empty array `[]` or `NULL`: No actions, display only

4. **Related Business Type (ref_type)**:
   - `FRIEND_REQUEST`: Links friend_request table
   - `GAME_MATCH`: Links game_match
   - `GAME_ROOM`: Links game_room

5. **Source Service (source_service)**:
   - `system-service`: System service (user, friend, notification, etc.)
   - `game-service`: Game service (matches, rooms, etc.)
   - `chat-service`: Chat service (message notifications, etc.)

### 3.13 Log Table Partition Templates (Optional, for scale)

```sql
-- Login log partitioned monthly (example: Jan 2024)
CREATE TABLE sys_login_log_2024_01 PARTITION OF sys_login_log
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Operation log partitioned monthly (example: Jan 2024)
CREATE TABLE sys_op_log_2024_01 PARTITION OF sys_op_log
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Note: convert original table to partitioned table first (ALTER TABLE ... PARTITION BY RANGE)
-- Suggest: cron job creates next month's partition monthly; archive data older than 3 months to cold storage
```

> **Notes**:
> - **Partition Strategy**: Monthly partitions for easier management and archiving
> - **Auto-create**: Use scheduled job (cron) to create next month partition
> - **Data Archive**: Archive partitions older than 3 months to cold storage to free space
> - **Query Optimization**: PostgreSQL auto-routes queries to partitions; no app-layer handling needed

### 3.14 Triggers

```sql
-- Trigger function to auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create triggers for tables needing auto-updated updated_at
CREATE TRIGGER trg_sys_user_updated_at
    BEFORE UPDATE ON sys_user
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_user_profile_updated_at
    BEFORE UPDATE ON sys_user_profile
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_dept_updated_at
    BEFORE UPDATE ON sys_dept
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_role_updated_at
    BEFORE UPDATE ON sys_role
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_permission_updated_at
    BEFORE UPDATE ON sys_permission
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_sys_menu_updated_at
    BEFORE UPDATE ON sys_menu
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
```

---

## IV. Game Module

### 4.1 Game Type Table (game_type)

```sql
CREATE TABLE game_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_code VARCHAR(50) NOT NULL UNIQUE,
    game_name VARCHAR(50) NOT NULL,
    game_desc VARCHAR(200),
    icon_url VARCHAR(500),
    min_players INT DEFAULT 2,
    max_players INT DEFAULT 2,
    status SMALLINT DEFAULT 1, -- 0-disabled, 1-enabled
    sort_order INT DEFAULT 0,
    config JSONB DEFAULT '{}'::jsonb, -- Game config (rules, board size, etc.)
    remark VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid status
    CONSTRAINT chk_game_type_status_valid CHECK (status IN (0, 1)),
    -- Constraint: player count validity
    CONSTRAINT chk_game_type_players_valid CHECK (min_players > 0 AND max_players >= min_players)
);

-- Indexes
CREATE UNIQUE INDEX uk_game_type_code_not_deleted ON game_type(game_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_game_type_status ON game_type(status);
CREATE INDEX idx_game_type_status_sort ON game_type(status, sort_order) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE game_type IS 'Game type table';
COMMENT ON COLUMN game_type.game_code IS 'Game code (e.g., gomoku, chess, go)';
COMMENT ON COLUMN game_type.game_name IS 'Game name (e.g., 五子棋, 象棋, 围棋)';
COMMENT ON COLUMN game_type.min_players IS 'Minimum players';
COMMENT ON COLUMN game_type.max_players IS 'Maximum players';
COMMENT ON COLUMN game_type.config IS 'Game config (JSONB, e.g., {"board_size": 15, "rules": ["STANDARD", "RENJU"]})';
```

### 4.2 Game Room Table (game_room)

```sql
CREATE TABLE game_room (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_code VARCHAR(50) UNIQUE, -- Room code (optional short code for sharing)
    game_type_id UUID NOT NULL, -- Game type ID (app layer ensures existence)
    owner_id UUID NOT NULL, -- Room owner user ID (app layer ensures existence)
    client_op_id VARCHAR(64), -- Client operation ID (for idempotency, frontend-generated UUID)
    room_name VARCHAR(100), -- Room name
    room_mode VARCHAR(20) NOT NULL, -- PVP (player vs player), PVE (player vs AI)
    room_status VARCHAR(20) DEFAULT 'WAITING', -- WAITING, PLAYING, FINISHED, CLOSED
    max_players INT DEFAULT 2 NOT NULL, -- Max players (non-null, default 2)
    current_players INT DEFAULT 0 NOT NULL, -- Current players (auto-synced via trigger; non-null)
    password_hash VARCHAR(200), -- Room password hash (optional, use bcrypt/argon2)
    is_private BOOLEAN DEFAULT FALSE, -- Private room or not
    config JSONB DEFAULT '{}'::jsonb, -- Room config (rules, AI difficulty, etc.)
    started_at TIMESTAMPTZ, -- Start time
    finished_at TIMESTAMPTZ, -- End time
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid room mode
    CONSTRAINT chk_room_mode_valid CHECK (room_mode IN ('PVP', 'PVE')),
    -- Constraint: valid status
    CONSTRAINT chk_room_status CHECK (
        room_status IN ('WAITING', 'PLAYING', 'FINISHED', 'CLOSED')
    ),
    -- Constraint: private room must have password hash
    CONSTRAINT chk_private_password CHECK (
        (is_private = FALSE) OR (is_private = TRUE AND password_hash IS NOT NULL)
    ),
    -- Constraint: terminal room cannot be modified (app layer also checks)
    CONSTRAINT chk_room_final_state CHECK (
        room_status NOT IN ('FINISHED', 'CLOSED') OR 
        (room_status IN ('FINISHED', 'CLOSED') AND finished_at IS NOT NULL)
    ),
    -- Constraint: player count validity
    CONSTRAINT chk_players_count CHECK (current_players >= 0 AND current_players <= max_players)
);

-- Indexes
CREATE UNIQUE INDEX uk_game_room_code_not_deleted ON game_room(room_code) WHERE deleted_at IS NULL AND room_code IS NOT NULL;
CREATE INDEX idx_game_room_game_type ON game_room(game_type_id);
CREATE INDEX idx_game_room_owner ON game_room(owner_id);

-- Note: no foreign keys; app layer ensures game_type_id and owner_id validity
CREATE INDEX idx_game_room_status ON game_room(room_status);
CREATE INDEX idx_game_room_status_created ON game_room(room_status, created_at DESC) WHERE deleted_at IS NULL;

-- Unique index: client op ID idempotency (prevent duplicate room creation)
CREATE UNIQUE INDEX uk_game_room_client_op ON game_room(client_op_id) WHERE client_op_id IS NOT NULL AND deleted_at IS NULL;

-- Comments
COMMENT ON TABLE game_room IS 'Game room table';
COMMENT ON COLUMN game_room.room_code IS 'Room code (short code for sharing)';
COMMENT ON COLUMN game_room.client_op_id IS 'Client operation ID (frontend-generated UUID for idempotency, prevents duplicate room creation)';
COMMENT ON COLUMN game_room.room_mode IS 'Room mode: PVP (player vs player), PVE (player vs AI), must be non-null';
COMMENT ON COLUMN game_room.room_status IS 'Room status: WAITING, PLAYING, FINISHED, CLOSED; default WAITING';
COMMENT ON COLUMN game_room.max_players IS 'Max players (non-null, default 2)';
COMMENT ON COLUMN game_room.current_players IS 'Current players (auto-synced from game_room_player; non-null; range 0..max_players)';
COMMENT ON COLUMN game_room.password_hash IS 'Room password hash (use bcrypt/argon2; never store plaintext)';
COMMENT ON COLUMN game_room.config IS 'Room config (JSONB, e.g., {"rule": "STANDARD", "ai_difficulty": "medium"})';
```

### 4.3 Room Player Table (game_room_player)

```sql
CREATE TABLE game_room_player (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL, -- Room ID (app layer ensures existence)
    user_id UUID NOT NULL, -- User ID (app layer ensures existence)
    player_role VARCHAR(20) DEFAULT 'PLAYER', -- PLAYER, OBSERVER
    player_side VARCHAR(10), -- Side (e.g., X, O, BLACK, WHITE by game)
    seat_key VARCHAR(50), -- Seat key (for reconnection)
    is_ready BOOLEAN DEFAULT FALSE, -- Ready?
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ, -- Leave time
    UNIQUE(room_id, user_id),
    -- Constraint: valid role
    CONSTRAINT chk_player_role_valid CHECK (
        player_role IS NULL OR player_role IN ('PLAYER', 'OBSERVER')
    ),
    -- Constraint: valid side
    CONSTRAINT chk_side_valid CHECK (
        player_side IS NULL OR player_side IN ('X','O','BLACK','WHITE')
    )
);

-- Indexes
CREATE INDEX idx_room_player_room ON game_room_player(room_id);
CREATE INDEX idx_room_player_user ON game_room_player(user_id);
CREATE INDEX idx_room_player_seat_key ON game_room_player(seat_key) WHERE seat_key IS NOT NULL;

-- Unique index: same room, each side only 1 person (NULL not constrained, allows observers)
CREATE UNIQUE INDEX uk_room_side ON game_room_player(room_id, player_side) WHERE player_side IS NOT NULL;

-- Unique index: seat_key unique per room (for reconnection idempotency)
CREATE UNIQUE INDEX uk_room_seat_key ON game_room_player(room_id, seat_key) WHERE seat_key IS NOT NULL;

-- Note: no foreign keys; app layer ensures room_id and user_id validity
-- On room/user deletion, app layer cleans related data

-- Comments
COMMENT ON TABLE game_room_player IS 'Room player table';
COMMENT ON COLUMN game_room_player.player_role IS 'Player role: PLAYER, OBSERVER; default PLAYER';
COMMENT ON COLUMN game_room_player.player_side IS 'Side (X, O, BLACK, WHITE; NULL for observer)';
COMMENT ON COLUMN game_room_player.seat_key IS 'Seat key (for reconnection, unique within room)';
COMMENT ON COLUMN game_room_player.joined_at IS 'Joined time (must be non-null)';
```

### 4.4 Game Match Table (game_match)

```sql
CREATE TABLE game_match (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_code VARCHAR(50) UNIQUE, -- Match code
    room_id UUID, -- Room ID (may be null for matchmaking; app layer ensures existence)
    game_type_id UUID NOT NULL, -- Game type ID (app layer ensures existence)
    match_type VARCHAR(20) NOT NULL, -- ROOM, MATCH, FRIEND
    match_status VARCHAR(20) DEFAULT 'PLAYING', -- PLAYING, FINISHED, ABANDONED
    player1_id UUID NOT NULL, -- Player1 user ID (app layer ensures existence)
    player2_id UUID, -- Player2 user ID (NULL for PVE, app layer ensures existence)
    player1_side VARCHAR(10), -- Player1 side
    player2_side VARCHAR(10), -- Player2 side
    winner_id UUID, -- Winner user ID (NULL for draw; app layer ensures existence)
    result VARCHAR(20), -- WIN, DRAW, ABANDON
    match_config JSONB DEFAULT '{}'::jsonb, -- Match config
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    duration_seconds INT, -- Duration in seconds
    total_moves INT DEFAULT 0, -- Total moves
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ, -- Soft delete support
    -- Constraint: valid match type
    CONSTRAINT chk_match_type_valid CHECK (match_type IN ('ROOM', 'MATCH', 'FRIEND')),
    -- Constraint: valid status
    CONSTRAINT chk_match_status CHECK (
        match_status IN ('PLAYING', 'FINISHED', 'ABANDONED')
    ),
    -- Constraint: result/winner consistency (draw has no winner; win must have winner)
    CONSTRAINT chk_match_result_winner CHECK (
        (result = 'DRAW' AND winner_id IS NULL)
        OR (result = 'WIN' AND winner_id IS NOT NULL)
        OR (result = 'ABANDON')
        OR (result IS NULL)
    ),
    -- Constraint: terminal matches cannot be modified (app layer also checks)
    CONSTRAINT chk_match_final_state CHECK (
        match_status NOT IN ('FINISHED', 'ABANDONED') OR 
        (match_status IN ('FINISHED', 'ABANDONED') AND finished_at IS NOT NULL)
    )
);

-- Indexes
CREATE UNIQUE INDEX uk_game_match_code ON game_match(match_code) WHERE match_code IS NOT NULL;
CREATE INDEX idx_game_match_room ON game_match(room_id) WHERE room_id IS NOT NULL;
CREATE INDEX idx_game_match_game_type ON game_match(game_type_id);
CREATE INDEX idx_game_match_player1 ON game_match(player1_id);
CREATE INDEX idx_game_match_player2 ON game_match(player2_id) WHERE player2_id IS NOT NULL;
CREATE INDEX idx_game_match_status ON game_match(match_status);
CREATE INDEX idx_game_match_started ON game_match(started_at DESC);
CREATE INDEX idx_game_match_players ON game_match(player1_id, player2_id) WHERE player2_id IS NOT NULL;
CREATE INDEX idx_match_not_deleted ON game_match(id) WHERE deleted_at IS NULL;

-- Note: no foreign keys; app layer ensures all IDs are valid
-- When deleting users, app layer handles history (e.g., set winner_id to NULL or retain)

-- Comments
COMMENT ON TABLE game_match IS 'Game match table';
COMMENT ON COLUMN game_match.match_type IS 'Match type: ROOM, MATCH, FRIEND; must be non-null';
COMMENT ON COLUMN game_match.match_status IS 'Match status: PLAYING, FINISHED, ABANDONED; default PLAYING';
COMMENT ON COLUMN game_match.result IS 'Match result: WIN, DRAW, ABANDON';
COMMENT ON COLUMN game_match.match_config IS 'Match config (JSONB, e.g., {"rule": "STANDARD", "board_size": 15})';
COMMENT ON COLUMN game_match.started_at IS 'Start time (must be non-null, default now)';
COMMENT ON COLUMN game_match.deleted_at IS 'Soft delete time (terminal matches recommended to keep, not delete)';
```

### 4.5 Match Detail Table (game_match_detail)

```sql
CREATE TABLE game_match_detail (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL, -- Match ID (app layer ensures existence)
    move_number INT NOT NULL, -- Move number (starts from 1)
    player_id UUID NOT NULL, -- Player user ID (app layer ensures existence)
    move_data JSONB NOT NULL, -- Move data (JSONB, format varies by game)
    move_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Move time
    is_valid BOOLEAN DEFAULT TRUE, -- Valid move?
    remark VARCHAR(500)
);

-- Indexes
CREATE INDEX idx_match_detail_match ON game_match_detail(match_id);
CREATE INDEX idx_match_detail_match_move ON game_match_detail(match_id, move_number);
CREATE INDEX idx_match_detail_player ON game_match_detail(player_id);
CREATE INDEX idx_match_detail_time ON game_match_detail(move_time);

-- Unique index: move number unique per match (prevents duplicates, ensures replay correctness)
CREATE UNIQUE INDEX uk_match_move ON game_match_detail(match_id, move_number);

-- Note: no foreign keys; app layer ensures match_id and player_id validity
-- On match deletion, app layer cleans details

-- Comments
COMMENT ON TABLE game_match_detail IS 'Match detail table (records every move)';
COMMENT ON COLUMN game_match_detail.move_number IS 'Move number (starts from 1, unique within match)';
COMMENT ON COLUMN game_match_detail.move_data IS 'Move data (JSONB, e.g., {"x": 7, "y": 7, "piece": "X"})';
```

### 4.6 Match Snapshot Table (game_match_snapshot)

```sql
CREATE TABLE game_match_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL, -- Match ID (app layer ensures existence)
    snapshot_type VARCHAR(20) NOT NULL, -- BOARD (board snapshot), STATE (state snapshot)
    snapshot_data JSONB NOT NULL, -- Snapshot data
    step_number INT, -- Corresponding move number
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_match_snapshot_match ON game_match_snapshot(match_id);
CREATE INDEX idx_match_snapshot_match_step ON game_match_snapshot(match_id, step_number) WHERE step_number IS NOT NULL;

-- Note: no foreign keys; app layer ensures match_id validity
-- On match deletion, app layer cleans snapshot data

-- Comments
COMMENT ON TABLE game_match_snapshot IS 'Match snapshot table (for replay, reconnection)';
COMMENT ON COLUMN game_match_snapshot.snapshot_type IS 'Snapshot type: BOARD (board snapshot), STATE (state snapshot)';
COMMENT ON COLUMN game_match_snapshot.snapshot_data IS 'Snapshot data (JSONB, full board or state info)';
```

## V. Social Module

### 5.1 Friend Table (user_friend)

```sql
CREATE TABLE user_friend (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL, -- User ID (app layer ensures existence)
    friend_id UUID NOT NULL, -- Friend user ID (app layer ensures existence)
    friend_nickname VARCHAR(50), -- Remark nickname
    friend_group VARCHAR(50), -- Friend group
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, BLOCKED
    is_favorite BOOLEAN DEFAULT FALSE, -- Favorite
    last_interaction_time TIMESTAMPTZ, -- Last interaction time
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, friend_id),
    -- Constraint: cannot add self
    CONSTRAINT chk_not_self_friend CHECK (user_id != friend_id),
    -- Constraint: valid status
    CONSTRAINT chk_friend_status_valid CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

-- Indexes
CREATE INDEX idx_user_friend_user ON user_friend(user_id);
CREATE INDEX idx_user_friend_friend ON user_friend(friend_id);
CREATE INDEX idx_user_friend_status ON user_friend(user_id, status);
CREATE INDEX idx_user_friend_interaction ON user_friend(user_id, last_interaction_time DESC) WHERE last_interaction_time IS NOT NULL;

-- Notes: no foreign keys; app ensures user_id/friend_id validity; app cleans relations on delete

-- Comments
COMMENT ON TABLE user_friend IS 'Friend relation table (bidirectional; A→B and B→A created)';
COMMENT ON COLUMN user_friend.id IS 'Primary key (UUID)';
COMMENT ON COLUMN user_friend.user_id IS 'User ID (links sys_user.id, current user, app ensures, required)';
COMMENT ON COLUMN user_friend.friend_id IS 'Friend user ID (links sys_user.id, app ensures, required)';
COMMENT ON COLUMN user_friend.friend_nickname IS 'Remark nickname (up to 50 chars, optional)';
COMMENT ON COLUMN user_friend.friend_group IS 'Friend group (user-defined; optional)';
COMMENT ON COLUMN user_friend.status IS 'Relation status enum: ACTIVE, BLOCKED; default ACTIVE; required';
COMMENT ON COLUMN user_friend.is_favorite IS 'Favorite flag (true = pinned), default false, required';
COMMENT ON COLUMN user_friend.last_interaction_time IS 'Last interaction time (TIMESTAMPTZ; optional)';
COMMENT ON COLUMN user_friend.created_at IS 'Created at (TIMESTAMPTZ, auto-set, required)';
COMMENT ON COLUMN user_friend.updated_at IS 'Updated at (TIMESTAMPTZ, trigger auto-update, required)';
```

**Dictionary Notes**:

1. **Relation Status (status)**:
   - `ACTIVE`: Normal friendship (chat, invite, etc.)
   - `BLOCKED`: Blocked (no messages/requests; record kept)

2. **Favorite (is_favorite)**:
   - `true`: Favorite (pinned)
   - `false`: Normal (sorted by last interaction)

3. **Friend Group (friend_group)**:
   - User-defined group name (e.g., game friends, real-life friends, coworkers)
   - Optional, NULL = none

**Design Notes**:
- **Bidirectional**: A→B and B→A records on accept
- **Unique**: `UNIQUE(user_id, friend_id)` prevents duplicates
- **Self-check**: `CHECK (user_id != friend_id)` prevents self-friend

### 5.2 Friend Request Table (friend_request)

```sql
CREATE TABLE friend_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL, -- Requester user ID (app ensures)
    receiver_id UUID NOT NULL, -- Receiver user ID (app ensures)
    request_message VARCHAR(200), -- Request message
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, ACCEPTED, REJECTED, EXPIRED
    handled_at TIMESTAMPTZ, -- Handle time
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: cannot request self
    CONSTRAINT chk_not_self_request CHECK (requester_id != receiver_id),
    -- Constraint: valid status
    CONSTRAINT chk_request_status_valid CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED'))
);

-- Indexes
CREATE INDEX idx_friend_request_requester ON friend_request(requester_id);
CREATE INDEX idx_friend_request_receiver ON friend_request(receiver_id);
CREATE INDEX idx_friend_request_status ON friend_request(receiver_id, status) WHERE status = 'PENDING';
CREATE INDEX idx_friend_request_created ON friend_request(created_at DESC);

-- Unique: one pending request per direction (idempotent)
CREATE UNIQUE INDEX uk_friend_request_pending ON friend_request(requester_id, receiver_id) WHERE status = 'PENDING';

-- Notes: no foreign keys; app ensures requester_id/receiver_id validity; app cleans on delete

-- Comments
COMMENT ON TABLE friend_request IS 'Friend request table (records requests; supports auto-accept when both sides request)';
COMMENT ON COLUMN friend_request.id IS 'Primary key (UUID)';
COMMENT ON COLUMN friend_request.requester_id IS 'Requester user ID (links sys_user.id, required)';
COMMENT ON COLUMN friend_request.receiver_id IS 'Receiver user ID (links sys_user.id, required)';
COMMENT ON COLUMN friend_request.request_message IS 'Request message (max 200 chars, optional)';
COMMENT ON COLUMN friend_request.status IS 'Status enum: PENDING, ACCEPTED, REJECTED, EXPIRED; default PENDING; required';
COMMENT ON COLUMN friend_request.handled_at IS 'Handled time (TIMESTAMPTZ; optional)';
COMMENT ON COLUMN friend_request.created_at IS 'Created at (TIMESTAMPTZ; required)';
```

**Dictionary Notes**:

1. **Status (status)**:
   - `PENDING`: Waiting for receiver decision (frontend shows actions)
   - `ACCEPTED`: Accepted (creates bidirectional user_friend)
   - `REJECTED`: Rejected
   - `EXPIRED`: Timed out (scheduler/app)

2. **Request Message (request_message)**:
   - Verification text (e.g., “I’m XXX”)
   - Max 200 chars, optional

**Business Logic**:

1. **Mutual requests auto-accept**:
   - A→B PENDING and B→A PENDING → both become ACCEPTED, create friends

2. **Unique constraint**:
   - `UNIQUE(requester_id, receiver_id) WHERE status = 'PENDING'` enforces idempotency

3. **Self-check**:
   - Prevent self-request via CHECK

4. **State transitions**:
   - `PENDING` → `ACCEPTED`
   - `PENDING` → `REJECTED`
   - `PENDING` → `EXPIRED`

### 5.3 Chat Session Table (chat_session)

```sql
CREATE TABLE chat_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_type VARCHAR(20) NOT NULL, -- PRIVATE, ROOM, GROUP
    session_name VARCHAR(100), -- Session name (for group)
    support_key VARCHAR(120), -- Private session key (min(user1,user2)||'|'||max(user1,user2))
    room_id UUID, -- Room ID (room chat; app ensures)
    created_by UUID, -- Creator user ID (group; app ensures)
    last_message_id UUID, -- Last message ID
    last_message_time TIMESTAMPTZ, -- Last message time
    member_count INT DEFAULT 0 NOT NULL, -- Member count (non-null)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid type
    CONSTRAINT chk_session_type_valid CHECK (session_type IN ('PRIVATE', 'ROOM', 'GROUP')),
    -- Constraint: member count validity
    CONSTRAINT chk_member_count CHECK (member_count >= 0)
);

-- Indexes
CREATE INDEX idx_chat_session_type ON chat_session(session_type);
CREATE INDEX idx_chat_session_room ON chat_session(room_id) WHERE room_id IS NOT NULL;
CREATE INDEX idx_chat_session_last_message ON chat_session(last_message_time DESC) WHERE last_message_time IS NOT NULL;

-- Unique: private session dedup
CREATE UNIQUE INDEX uk_chat_private_key ON chat_session(support_key) WHERE session_type = 'PRIVATE';

-- Unique: one ROOM session per room
CREATE UNIQUE INDEX uk_chat_session_room_unique ON chat_session(room_id) WHERE session_type = 'ROOM' AND room_id IS NOT NULL;

-- Notes: no foreign keys; app ensures room_id/created_by validity

-- Comments
COMMENT ON TABLE chat_session IS 'Chat session table';
COMMENT ON COLUMN chat_session.id IS 'Primary key (UUID)';
COMMENT ON COLUMN chat_session.session_type IS 'Session type enum: PRIVATE, ROOM, GROUP; required';
COMMENT ON COLUMN chat_session.session_name IS 'Session name (for group; up to 100 chars; optional)';
COMMENT ON COLUMN chat_session.support_key IS 'Private session unique key (min||"|"||max); dedup; up to 120 chars; optional';
COMMENT ON COLUMN chat_session.room_id IS 'Room ID (room chat; links game_room.id; app ensures; optional)';
COMMENT ON COLUMN chat_session.created_by IS 'Creator user ID (group; links sys_user.id; app ensures; optional)';
COMMENT ON COLUMN chat_session.last_message_id IS 'Last message ID (links chat_message.id; optional)';
COMMENT ON COLUMN chat_session.last_message_time IS 'Last message time (TIMESTAMPTZ; optional)';
COMMENT ON COLUMN chat_session.member_count IS 'Member count (required, default 0, >=0; trigger/app sync)';
COMMENT ON COLUMN chat_session.created_at IS 'Created at (TIMESTAMPTZ; required)';
COMMENT ON COLUMN chat_session.updated_at IS 'Updated at (TIMESTAMPTZ; trigger auto-update; required)';
```

### 5.4 Chat Session Member Table (chat_session_member)

```sql
CREATE TABLE chat_session_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL, -- Session ID (app ensures)
    user_id UUID NOT NULL, -- User ID (app ensures)
    member_role VARCHAR(20) DEFAULT 'MEMBER', -- MEMBER, ADMIN, OWNER
    nickname_in_session VARCHAR(50), -- Nickname in session
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ, -- Leave time
    last_read_message_id UUID, -- Last read message ID
    last_read_time TIMESTAMPTZ, -- Last read time
    UNIQUE(session_id, user_id),
    -- Constraint: valid role
    CONSTRAINT chk_member_role_valid CHECK (member_role IN ('MEMBER', 'ADMIN', 'OWNER'))
);

-- Indexes
CREATE INDEX idx_session_member_session ON chat_session_member(session_id);
CREATE INDEX idx_session_member_user ON chat_session_member(user_id);
CREATE INDEX idx_session_member_user_session ON chat_session_member(user_id, session_id);

-- Notes: no foreign keys; app ensures session_id/user_id validity; app cleans on delete

-- Comments
COMMENT ON TABLE chat_session_member IS 'Chat session member table';
COMMENT ON COLUMN chat_session_member.id IS 'Primary key (UUID)';
COMMENT ON COLUMN chat_session_member.session_id IS 'Session ID (links chat_session.id; required)';
COMMENT ON COLUMN chat_session_member.user_id IS 'User ID (links sys_user.id; required)';
COMMENT ON COLUMN chat_session_member.member_role IS 'Role enum: MEMBER, ADMIN, OWNER; default MEMBER; required';
COMMENT ON COLUMN chat_session_member.nickname_in_session IS 'Nickname in session (group), up to 50 chars, optional';
COMMENT ON COLUMN chat_session_member.joined_at IS 'Joined at (TIMESTAMPTZ; required)';
COMMENT ON COLUMN chat_session_member.left_at IS 'Left at (TIMESTAMPTZ; optional; NULL = still in session)';
COMMENT ON COLUMN chat_session_member.last_read_message_id IS 'Last read message ID (links chat_message.id; optional)';
COMMENT ON COLUMN chat_session_member.last_read_time IS 'Last read time (TIMESTAMPTZ; optional)';
```

### 5.5 Chat Message Table (chat_message)

```sql
CREATE TABLE chat_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL, -- Session ID (app ensures)
    sender_id UUID NOT NULL, -- Sender user ID (app ensures)
    client_op_id VARCHAR(64), -- Client op ID (idempotency; frontend UUID)
    message_type VARCHAR(20) DEFAULT 'TEXT', -- TEXT, IMAGE, FILE, SYSTEM
    content TEXT NOT NULL, -- Message content
    content_tsv tsvector, -- Full-text vector (auto)
    extra_data JSONB DEFAULT '{}'::jsonb, -- Extra data
    reply_to_message_id UUID, -- Reply message ID (app ensures)
    is_recalled BOOLEAN DEFAULT FALSE, -- Recalled?
    recalled_at TIMESTAMPTZ, -- Recall time
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid type
    CONSTRAINT chk_message_type_valid CHECK (message_type IN ('TEXT', 'IMAGE', 'FILE', 'SYSTEM'))
);

-- Indexes
CREATE INDEX idx_chat_message_session ON chat_message(session_id);
CREATE INDEX idx_chat_message_sender ON chat_message(sender_id);
CREATE INDEX idx_chat_message_session_time ON chat_message(session_id, created_at DESC);
CREATE INDEX idx_chat_message_reply ON chat_message(reply_to_message_id) WHERE reply_to_message_id IS NOT NULL;

-- Full-text index (GIN)
CREATE INDEX idx_chat_message_tsv ON chat_message USING GIN (content_tsv);

-- Unique: client op idempotency
CREATE UNIQUE INDEX uk_chat_message_client_op ON chat_message(client_op_id) WHERE client_op_id IS NOT NULL;

-- Notes: no foreign keys; app ensures session_id/sender_id/reply_to_message_id validity; app handles cleanup

-- Comments
COMMENT ON TABLE chat_message IS 'Chat message table';
COMMENT ON COLUMN chat_message.id IS 'Primary key (UUID)';
COMMENT ON COLUMN chat_message.session_id IS 'Session ID (links chat_session.id; required)';
COMMENT ON COLUMN chat_message.sender_id IS 'Sender user ID (links sys_user.id; required)';
COMMENT ON COLUMN chat_message.client_op_id IS 'Client op ID (frontend UUID, idempotency; max 64 chars; optional)';
COMMENT ON COLUMN chat_message.message_type IS 'Message type enum: TEXT, IMAGE, FILE, SYSTEM; default TEXT; required';
COMMENT ON COLUMN chat_message.content IS 'Content (TEXT; required; for IMAGE/FILE can store description/filename)';
COMMENT ON COLUMN chat_message.content_tsv IS 'Full-text vector (tsvector; trigger-generated; for FTS)';
COMMENT ON COLUMN chat_message.extra_data IS 'Extra data (JSONB; e.g., {"image_url": "...", "file_name": "...", "file_size": 1024, "width": 800, "height": 600})';
COMMENT ON COLUMN chat_message.reply_to_message_id IS 'Reply-to message ID (links chat_message.id; optional)';
COMMENT ON COLUMN chat_message.is_recalled IS 'Recalled flag (BOOLEAN; default false; required)';
COMMENT ON COLUMN chat_message.recalled_at IS 'Recall time (TIMESTAMPTZ; optional)';
COMMENT ON COLUMN chat_message.created_at IS 'Created at (TIMESTAMPTZ; required)';
```

### 5.6 Chat Message Attachment Table (chat_message_attachment)

```sql
CREATE TABLE chat_message_attachment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL, -- Message ID (app ensures)
    file_url VARCHAR(1000) NOT NULL,
    file_name VARCHAR(255),
    file_size BIGINT,
    file_type VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_msg_attach_msg ON chat_message_attachment(message_id);

-- Notes: no foreign keys; app ensures message_id validity; app cleans on delete

-- Comments
COMMENT ON TABLE chat_message_attachment IS 'Chat message attachment table (large files separated from extra_data)';
COMMENT ON COLUMN chat_message_attachment.file_url IS 'File URL';
COMMENT ON COLUMN chat_message_attachment.file_size IS 'File size (bytes)';
COMMENT ON COLUMN chat_message_attachment.file_type IS 'File type (MIME)';
```

---

## VI. User Growth Module

### 6.1 User Score Table (user_score)

```sql
CREATE TABLE user_score (
    user_id UUID PRIMARY KEY, -- User ID (app ensures)
    total_score BIGINT DEFAULT 0, -- Total score
    current_score BIGINT DEFAULT 0, -- Current usable score
    frozen_score BIGINT DEFAULT 0, -- Frozen score
    level_id UUID, -- Current level ID (app ensures)
    level_name VARCHAR(50), -- Current level name (denormalized)
    experience_points BIGINT DEFAULT 0, -- Experience points
    win_count INT DEFAULT 0, -- Wins
    lose_count INT DEFAULT 0, -- Losses
    draw_count INT DEFAULT 0, -- Draws
    total_matches INT DEFAULT 0, -- Total matches
    win_rate DECIMAL(5,2) DEFAULT 0.00, -- Win rate (%)
    highest_score BIGINT DEFAULT 0, -- Highest score
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_user_score_level ON user_score(level_id);
CREATE INDEX idx_user_score_total ON user_score(total_score DESC);
CREATE INDEX idx_user_score_experience ON user_score(experience_points DESC);

-- Notes: no foreign keys; app ensures user_id/level_id validity; app cleans on delete

-- Comments
COMMENT ON TABLE user_score IS 'User score table';
COMMENT ON COLUMN user_score.total_score IS 'Total score (cumulative)';
COMMENT ON COLUMN user_score.current_score IS 'Current usable score';
COMMENT ON COLUMN user_score.frozen_score IS 'Frozen score (bets, activities)';
COMMENT ON COLUMN user_score.experience_points IS 'Experience points (for leveling)';
COMMENT ON COLUMN user_score.win_rate IS 'Win rate (%; e.g., 65.50 = 65.50%)';
```

### 6.2 User Level Table (user_level)

```sql
CREATE TABLE user_level (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    level_code VARCHAR(50) NOT NULL UNIQUE, -- Level code
    level_name VARCHAR(50) NOT NULL, -- Level name
    level_number INT NOT NULL UNIQUE, -- Level number (1,2,3...)
    min_experience BIGINT NOT NULL, -- Min experience
    max_experience BIGINT, -- Max experience (NULL = no cap)
    level_icon VARCHAR(500), -- Level icon
    level_color VARCHAR(20), -- Level color
    privileges JSONB DEFAULT '{}'::jsonb, -- Level privileges (JSONB)
    sort_order INT DEFAULT 0,
    status SMALLINT DEFAULT 1, -- 0-disabled, 1-enabled
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    -- Constraint: valid status
    CONSTRAINT chk_user_level_status_valid CHECK (status IN (0, 1)),
    -- Constraint: experience validity
    CONSTRAINT chk_user_level_experience_valid CHECK (
        max_experience IS NULL OR max_experience >= min_experience
    ),
    -- Constraint: level number validity
    CONSTRAINT chk_user_level_number_valid CHECK (level_number > 0)
);

-- Indexes
CREATE UNIQUE INDEX uk_user_level_code_not_deleted ON user_level(level_code) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_user_level_number_not_deleted ON user_level(level_number) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_level_status ON user_level(status);
CREATE INDEX idx_user_level_experience ON user_level(min_experience, max_experience) WHERE deleted_at IS NULL;

-- Comments
COMMENT ON TABLE user_level IS 'User level table';
COMMENT ON COLUMN user_level.level_code IS 'Level code (e.g., BRONZE, SILVER, GOLD)';
COMMENT ON COLUMN user_level.level_number IS 'Level number (1,2,3..., higher = higher level)';
COMMENT ON COLUMN user_level.privileges IS 'Level privileges (JSONB, e.g., {"daily_bonus": 100, "match_bonus_rate": 1.2})';
```

### 6.3 Score Change Log (score_change_log)

```sql
CREATE TABLE score_change_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL, -- User ID (app ensures)
    change_type VARCHAR(50) NOT NULL, -- Change type (WIN_MATCH, LOSE_MATCH, DAILY_BONUS, PURCHASE, etc.)
    change_amount BIGINT NOT NULL, -- Change amount (positive add, negative subtract)
    before_score BIGINT NOT NULL, -- Score before
    after_score BIGINT NOT NULL, -- Score after
    related_match_id UUID, -- Related match ID (app ensures)
    related_order_id UUID, -- Related order ID
    description VARCHAR(500), -- Description
    extra_data JSONB DEFAULT '{}'::jsonb, -- Extra data
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_score_change_user ON score_change_log(user_id);
CREATE INDEX idx_score_change_type ON score_change_log(change_type);
CREATE INDEX idx_score_change_match ON score_change_log(related_match_id) WHERE related_match_id IS NOT NULL;
CREATE INDEX idx_score_change_time ON score_change_log(user_id, created_at DESC);

-- Notes: no foreign keys; app ensures user_id/related_match_id validity; app decides retention

-- Comments
COMMENT ON TABLE score_change_log IS 'Score change log table';
COMMENT ON COLUMN score_change_log.change_type IS 'Change type: WIN_MATCH, LOSE_MATCH, DAILY_BONUS, etc.';
COMMENT ON COLUMN score_change_log.change_amount IS 'Change amount (positive=add, negative=subtract)';
```

### 6.4 Experience Change Log (experience_change_log)

```sql
CREATE TABLE experience_change_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL, -- User ID (app ensures)
    change_type VARCHAR(50) NOT NULL, -- Change type
    change_amount BIGINT NOT NULL, -- Change amount
    before_experience BIGINT NOT NULL, -- Before exp
    after_experience BIGINT NOT NULL, -- After exp
    before_level_id UUID, -- Before level ID (app ensures)
    after_level_id UUID, -- After level ID (app ensures)
    related_match_id UUID, -- Related match ID (app ensures)
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_experience_change_user ON experience_change_log(user_id);
CREATE INDEX idx_experience_change_time ON experience_change_log(user_id, created_at DESC);
CREATE INDEX idx_experience_change_match ON experience_change_log(related_match_id) WHERE related_match_id IS NOT NULL;

-- Notes: no foreign keys; app ensures IDs validity; app decides retention

-- Comments
COMMENT ON TABLE experience_change_log IS 'Experience change log';
COMMENT ON COLUMN experience_change_log.change_type IS 'Change type: PLAY_MATCH, DAILY_LOGIN, etc.';
```

---

## VII. Matchmaking Module

### 7.1 Match Queue Table (match_queue)

```sql
CREATE TABLE match_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL, -- User ID (app ensures)
    game_type_id UUID NOT NULL, -- Game type ID (app ensures)
    client_op_id VARCHAR(64), -- Client op ID (idempotency; frontend UUID)
    queue_status VARCHAR(20) DEFAULT 'WAITING', -- WAITING, MATCHED, CANCELLED, TIMEOUT
    match_preferences JSONB DEFAULT '{}'::jsonb, -- Preferences (level range, score range)
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    matched_at TIMESTAMPTZ, -- Matched time
    cancelled_at TIMESTAMPTZ, -- Cancel time
    timeout_at TIMESTAMPTZ, -- Timeout time
    matched_match_id UUID, -- Matched match ID (app ensures)
    -- Constraint: valid status
    CONSTRAINT chk_queue_status_valid CHECK (queue_status IN ('WAITING', 'MATCHED', 'CANCELLED', 'TIMEOUT'))
);

-- Indexes
CREATE INDEX idx_match_queue_user ON match_queue(user_id);
CREATE INDEX idx_match_queue_game_type ON match_queue(game_type_id);
CREATE INDEX idx_match_queue_status ON match_queue(queue_status, game_type_id) WHERE queue_status = 'WAITING';
CREATE INDEX idx_match_queue_joined ON match_queue(joined_at) WHERE queue_status = 'WAITING';

-- Unique: one WAITING per (user, game) (prevent re-entry)
CREATE UNIQUE INDEX uk_queue_user_game_waiting ON match_queue(user_id, game_type_id) WHERE queue_status = 'WAITING';

-- Unique: client op idempotency
CREATE UNIQUE INDEX uk_match_queue_client_op ON match_queue(client_op_id) WHERE client_op_id IS NOT NULL AND queue_status = 'WAITING';

-- Fairness index: FIFO per game type
CREATE INDEX idx_match_queue_fairness ON match_queue(game_type_id, joined_at) WHERE queue_status = 'WAITING';

-- JSONB expression indexes: preference level range
CREATE INDEX idx_mq_pref_min_level ON match_queue((match_preferences->>'min_level')) WHERE queue_status = 'WAITING';
CREATE INDEX idx_mq_pref_max_level ON match_queue((match_preferences->>'max_level')) WHERE queue_status = 'WAITING';

-- Notes: no foreign keys; app ensures user_id/game_type_id/matched_match_id validity; app cleans on delete

-- Comments
COMMENT ON TABLE match_queue IS 'Match queue table';
COMMENT ON COLUMN match_queue.client_op_id IS 'Client op ID (frontend UUID, idempotency)';
COMMENT ON COLUMN match_queue.queue_status IS 'Queue status: WAITING, MATCHED, CANCELLED, TIMEOUT; default WAITING';
COMMENT ON COLUMN match_queue.match_preferences IS 'Match preferences (JSONB, e.g., {"min_level": 1, "max_level": 10, "score_range": [1000, 2000]})';
COMMENT ON COLUMN match_queue.joined_at IS 'Joined queue time (required)';
```

### 7.2 Match Record Table (match_record)

```sql
CREATE TABLE match_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL, -- Match ID (app ensures)
    match_type VARCHAR(20) NOT NULL, -- RANDOM, FRIEND
    player1_id UUID NOT NULL, -- Player1 user ID (app ensures)
    player2_id UUID, -- Player2 user ID (PVE may be NULL; app ensures)
    player1_score_before BIGINT, -- Player1 score before
    player1_score_after BIGINT, -- Player1 score after
    player2_score_before BIGINT, -- Player2 score before
    player2_score_after BIGINT, -- Player2 score after
    match_quality_score DECIMAL(5,2), -- Match quality score (e.g., 85.50)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraint: valid match type
    CONSTRAINT chk_match_record_type_valid CHECK (match_type IN ('RANDOM', 'FRIEND'))
);

-- Indexes
CREATE INDEX idx_match_record_match ON match_record(match_id);
CREATE INDEX idx_match_record_player1 ON match_record(player1_id);
CREATE INDEX idx_match_record_player2 ON match_record(player2_id) WHERE player2_id IS NOT NULL;
CREATE INDEX idx_match_record_type ON match_record(match_type);

-- Notes: no foreign keys; app ensures IDs valid; app decides retention (recommend keep history)

-- Comments
COMMENT ON TABLE match_record IS 'Match record table';
COMMENT ON COLUMN match_record.match_type IS 'Match type: RANDOM, FRIEND; required';
COMMENT ON COLUMN match_record.match_quality_score IS 'Match quality score (0-100, e.g., 85.50)';
```

---

## VIII. Triggers

### 8.1 Auto-Update updated_at Trigger

```sql
-- Create trigger function (if not exists)
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers for tables needing updated_at auto-update
CREATE TRIGGER trg_game_type_updated_at
    BEFORE UPDATE ON game_type
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_game_room_updated_at
    BEFORE UPDATE ON game_room
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_user_friend_updated_at
    BEFORE UPDATE ON user_friend
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_chat_session_updated_at
    BEFORE UPDATE ON chat_session
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_user_score_updated_at
    BEFORE UPDATE ON user_score
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_user_level_updated_at
    BEFORE UPDATE ON user_level
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
```

### 8.2 Auto-Update User Score After Match Trigger

```sql
-- Automatically update user score stats after match ends
CREATE OR REPLACE FUNCTION update_user_score_after_match()
RETURNS TRIGGER AS $$
DECLARE
    v_player1_score_change BIGINT;
    v_player2_score_change BIGINT;
BEGIN
    -- Only when match ends
    IF NEW.match_status = 'FINISHED' AND (OLD.match_status IS NULL OR OLD.match_status != 'FINISHED') THEN
        -- Score calculation (simplified; ELO can be used)
        IF NEW.result = 'WIN' THEN
            IF NEW.winner_id = NEW.player1_id THEN
                v_player1_score_change := 20;
                v_player2_score_change := -10;
            ELSE
                v_player1_score_change := -10;
                v_player2_score_change := 20;
            END IF;
        ELSIF NEW.result = 'DRAW' THEN
            v_player1_score_change := 5;
            v_player2_score_change := 5;
        ELSE
            v_player1_score_change := 0;
            v_player2_score_change := 0;
        END IF;
        
        -- Update player1
        UPDATE user_score
        SET total_score = total_score + v_player1_score_change,
            current_score = current_score + v_player1_score_change,
            win_count = CASE WHEN NEW.result = 'WIN' AND NEW.winner_id = NEW.player1_id THEN win_count + 1 ELSE win_count END,
            lose_count = CASE WHEN NEW.result = 'WIN' AND NEW.winner_id != NEW.player1_id THEN lose_count + 1 ELSE lose_count END,
            draw_count = CASE WHEN NEW.result = 'DRAW' THEN draw_count + 1 ELSE draw_count END,
            total_matches = total_matches + 1,
            win_rate = CASE 
                WHEN total_matches + 1 > 0 THEN 
                    ROUND((win_count + CASE WHEN NEW.result = 'WIN' AND NEW.winner_id = NEW.player1_id THEN 1 ELSE 0 END)::NUMERIC / (total_matches + 1) * 100, 2)
                ELSE 0
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE user_id = NEW.player1_id;
        
        -- Update player2 (if exists)
        IF NEW.player2_id IS NOT NULL THEN
            UPDATE user_score
            SET total_score = total_score + v_player2_score_change,
                current_score = current_score + v_player2_score_change,
                win_count = CASE WHEN NEW.result = 'WIN' AND NEW.winner_id = NEW.player2_id THEN win_count + 1 ELSE win_count END,
                lose_count = CASE WHEN NEW.result = 'WIN' AND NEW.winner_id != NEW.player2_id THEN lose_count + 1 ELSE lose_count END,
                draw_count = CASE WHEN NEW.result = 'DRAW' THEN draw_count + 1 ELSE draw_count END,
                total_matches = total_matches + 1,
                win_rate = CASE 
                    WHEN total_matches + 1 > 0 THEN 
                        ROUND((win_count + CASE WHEN NEW.result = 'WIN' AND NEW.winner_id = NEW.player2_id THEN 1 ELSE 0 END)::NUMERIC / (total_matches + 1) * 100, 2)
                    ELSE 0
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = NEW.player2_id;
        END IF;
        
        -- Score change log for player1
        INSERT INTO score_change_log (user_id, change_type, change_amount, before_score, after_score, related_match_id, description)
        SELECT 
            NEW.player1_id,
            CASE 
                WHEN NEW.result = 'WIN' AND NEW.winner_id = NEW.player1_id THEN 'WIN_MATCH'
                WHEN NEW.result = 'WIN' AND NEW.winner_id != NEW.player1_id THEN 'LOSE_MATCH'
                WHEN NEW.result = 'DRAW' THEN 'DRAW_MATCH'
                ELSE 'ABANDON_MATCH'
            END,
            v_player1_score_change,
            (SELECT current_score FROM user_score WHERE user_id = NEW.player1_id) - v_player1_score_change,
            (SELECT current_score FROM user_score WHERE user_id = NEW.player1_id),
            NEW.id,
            'Match score change'
        WHERE v_player1_score_change != 0;
        
        IF NEW.player2_id IS NOT NULL AND v_player2_score_change != 0 THEN
            INSERT INTO score_change_log (user_id, change_type, change_amount, before_score, after_score, related_match_id, description)
            SELECT 
                NEW.player2_id,
                CASE 
                    WHEN NEW.result = 'WIN' AND NEW.winner_id = NEW.player2_id THEN 'WIN_MATCH'
                    WHEN NEW.result = 'WIN' AND NEW.winner_id != NEW.player2_id THEN 'LOSE_MATCH'
                    WHEN NEW.result = 'DRAW' THEN 'DRAW_MATCH'
                    ELSE 'ABANDON_MATCH'
                END,
                v_player2_score_change,
                (SELECT current_score FROM user_score WHERE user_id = NEW.player2_id) - v_player2_score_change,
                (SELECT current_score FROM user_score WHERE user_id = NEW.player2_id),
                NEW.id,
                'Match score change';
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_game_match_update_score
    AFTER UPDATE ON game_match
    FOR EACH ROW
    EXECUTE FUNCTION update_user_score_after_match();
```

### 8.3 Chat Message Full-Text Trigger

```sql
-- Full-text trigger function
CREATE OR REPLACE FUNCTION chat_message_tsv_trg()
RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv = to_tsvector('simple', coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger: auto-update full-text vector
CREATE TRIGGER trg_chat_message_tsv
    BEFORE INSERT OR UPDATE ON chat_message
    FOR EACH ROW
    EXECUTE FUNCTION chat_message_tsv_trg();
```

### 8.4 Auto-Sync Room Player Count Trigger

```sql
-- Sync game_room.current_players with game_room_player
CREATE OR REPLACE FUNCTION sync_room_player_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE game_room
        SET current_players = current_players + 1
        WHERE id = NEW.room_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE game_room
        SET current_players = GREATEST(0, current_players - 1)
        WHERE id = OLD.room_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Triggers
CREATE TRIGGER trg_sync_room_player_count_ins
    AFTER INSERT ON game_room_player
    FOR EACH ROW
    EXECUTE FUNCTION sync_room_player_count();

CREATE TRIGGER trg_sync_room_player_count_del
    AFTER DELETE ON game_room_player
    FOR EACH ROW
    EXECUTE FUNCTION sync_room_player_count();
```

### 8.5 Terminal-State Guard Triggers (Optional, double protection)

```sql
-- Terminal room/match guard (optional; app already validates)
CREATE OR REPLACE FUNCTION prevent_final_state_modification()
RETURNS TRIGGER AS $$
BEGIN
    -- Allowed updates: updated_at, remark, finished_at
    IF OLD.room_status IN ('FINISHED', 'CLOSED') AND 
       (NEW.room_status != OLD.room_status OR 
        NEW.current_players != OLD.current_players OR
        NEW.room_mode != OLD.room_mode OR
        NEW.max_players != OLD.max_players) THEN
        RAISE EXCEPTION 'Room has finished; cannot modify state or key fields';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Match terminal guard (optional)
CREATE OR REPLACE FUNCTION prevent_match_final_state_modification()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.match_status IN ('FINISHED', 'ABANDONED') AND 
       (NEW.match_status != OLD.match_status OR
        NEW.player1_id != OLD.player1_id OR
        NEW.player2_id != OLD.player2_id OR
        NEW.winner_id != OLD.winner_id OR
        NEW.result != OLD.result) THEN
        RAISE EXCEPTION 'Match has finished; cannot modify key fields';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Optional triggers (double safeguard)
-- CREATE TRIGGER trg_prevent_room_final_modify
--     BEFORE UPDATE ON game_room
--     FOR EACH ROW
--     WHEN (OLD.room_status IN ('FINISHED', 'CLOSED'))
--     EXECUTE FUNCTION prevent_final_state_modification();

-- CREATE TRIGGER trg_prevent_match_final_modify
--     BEFORE UPDATE ON game_match
--     FOR EACH ROW
--     WHEN (OLD.match_status IN ('FINISHED', 'ABANDONED'))
--     EXECUTE FUNCTION prevent_match_final_state_modification();
```

---

## IX. Seed Data

### 9.1 Game Type Initialization

```sql
-- Insert default game types
INSERT INTO game_type (game_code, game_name, game_desc, min_players, max_players, config) VALUES
('gomoku', '五子棋', 'Gomoku game', 2, 2, '{"board_size": 15, "rules": ["STANDARD", "RENJU"]}'),
('chess', '象棋', 'Chinese Chess', 2, 2, '{"board_size": 9, "rules": ["STANDARD"]}'),
('go', '围棋', 'Go game', 2, 2, '{"board_size": 19, "rules": ["STANDARD"]}')
ON CONFLICT (game_code) DO NOTHING;
```

### 9.2 User Level Initialization

```sql
-- Insert default levels
INSERT INTO user_level (level_code, level_name, level_number, min_experience, max_experience, privileges) VALUES
('BRONZE_1', '青铜I', 1, 0, 100, '{"daily_bonus": 10}'),
('BRONZE_2', '青铜II', 2, 100, 300, '{"daily_bonus": 15}'),
('BRONZE_3', '青铜III', 3, 300, 600, '{"daily_bonus": 20}'),
('SILVER_1', '白银I', 4, 600, 1200, '{"daily_bonus": 30, "match_bonus_rate": 1.1}'),
('SILVER_2', '白银II', 5, 1200, 2000, '{"daily_bonus": 40, "match_bonus_rate": 1.15}'),
('GOLD_1', '黄金I', 6, 2000, 3500, '{"daily_bonus": 50, "match_bonus_rate": 1.2}'),
('GOLD_2', '黄金II', 7, 3500, 5500, '{"daily_bonus": 60, "match_bonus_rate": 1.25}'),
('PLATINUM', '铂金', 8, 5500, NULL, '{"daily_bonus": 80, "match_bonus_rate": 1.3}')
ON CONFLICT (level_code) DO NOTHING;
```

### 9.3 RBAC Initialization

```sql
-- Default roles
INSERT INTO sys_role (role_code, role_name, role_desc, data_scope, sort_order) VALUES
('SUPER_ADMIN', '超级管理员', 'System super admin, all permissions', 'ALL', 1),
('ADMIN', '管理员', 'System admin', 'ALL', 2),
('USER', '普通用户', 'Regular user', 'SELF', 3)
ON CONFLICT (role_code) DO NOTHING;

-- Default permissions (example; adjust as needed)
INSERT INTO sys_permission (permission_code, permission_name, permission_type, resource_path, http_method, sort_order) VALUES
('system:user:list', '用户列表', 'API', '/api/system/user', 'GET', 1),
('system:user:add', '新增用户', 'API', '/api/system/user', 'POST', 2),
('system:user:edit', '编辑用户', 'API', '/api/system/user', 'PUT', 3),
('system:user:delete', '删除用户', 'API', '/api/system/user', 'DELETE', 4),
('system:role:list', '角色列表', 'API', '/api/system/role', 'GET', 10),
('system:role:add', '新增角色', 'API', '/api/system/role', 'POST', 11),
('game:room:create', '创建房间', 'API', '/api/game/room', 'POST', 100),
('game:room:join', '加入房间', 'API', '/api/game/room/join', 'POST', 101),
('game:match:list', '对局列表', 'API', '/api/game/match', 'GET', 110)
ON CONFLICT (permission_code) DO NOTHING;

-- Default menus (example; adjust to actual routes)
INSERT INTO sys_menu (menu_name, menu_type, parent_id, path, component, icon, permission_code, sort_order) VALUES
('系统管理', 'DIRECTORY', NULL, '/system', 'Layout', 'system', NULL, 1),
('用户管理', 'MENU', (SELECT id FROM sys_menu WHERE menu_name = '系统管理' LIMIT 1), '/system/user', 'system/user/index', 'user', 'system:user:list', 1),
('角色管理', 'MENU', (SELECT id FROM sys_menu WHERE menu_name = '系统管理' LIMIT 1), '/system/role', 'system/role/index', 'peoples', 'system:role:list', 2),
('游戏大厅', 'MENU', NULL, '/game', 'game/index', 'game', 'game:room:list', 10),
('我的对局', 'MENU', NULL, '/game/match', 'game/match/index', 'list', 'game:match:list', 11)
ON CONFLICT (path) WHERE deleted_at IS NULL DO NOTHING;

-- Grant all permissions to SUPER_ADMIN (example)
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 
    (SELECT id FROM sys_role WHERE role_code = 'SUPER_ADMIN' LIMIT 1),
    id
FROM sys_permission
WHERE deleted_at IS NULL
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grant all menus to SUPER_ADMIN (example)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 
    (SELECT id FROM sys_role WHERE role_code = 'SUPER_ADMIN' LIMIT 1),
    id
FROM sys_menu
WHERE deleted_at IS NULL
ON CONFLICT (role_id, menu_id) DO NOTHING;
```

### 9.4 Full-Text Backfill

```sql
-- Backfill chat_message full-text vector
UPDATE chat_message
SET content_tsv = to_tsvector('simple', coalesce(content, ''))
WHERE content_tsv IS NULL;

-- Optimize stats
VACUUM ANALYZE chat_message;
```

---

## X. Common Query Examples

### 10.1 Query User Records

```sql
-- User total records (by user ID)
SELECT 
    us.win_count,
    us.lose_count,
    us.draw_count,
    us.total_matches,
    us.win_rate,
    us.total_score,
    ul.level_name
FROM user_score us
LEFT JOIN user_level ul ON us.level_id = ul.id
WHERE us.user_id = $1;

-- Lookup by player_id (for friend add/search)
SELECT 
    id,
    username,
    nickname,
    avatar_url,
    player_id,
    status
FROM sys_user
WHERE player_id = $1
  AND deleted_at IS NULL
  AND status = 1;
```

### 10.2 Query Recent Matches

```sql
-- Recent 10 matches
SELECT 
    gm.id,
    gt.game_name,
    gm.match_type,
    gm.result,
    CASE 
        WHEN gm.winner_id = $1 THEN 'WIN'
        WHEN gm.result = 'DRAW' THEN 'DRAW'
        ELSE 'LOSE'
    END AS user_result,
    gm.started_at,
    gm.finished_at
FROM game_match gm
JOIN game_type gt ON gm.game_type_id = gt.id
WHERE (gm.player1_id = $1 OR gm.player2_id = $1)
  AND gm.match_status = 'FINISHED'
ORDER BY gm.finished_at DESC
LIMIT 10;
```

### 10.3 Query Friend List

```sql
-- Friend list
SELECT 
    uf.friend_id,
    u.username,
    u.nickname,
    u.avatar_url,
    uf.friend_nickname,
    uf.friend_group,
    uf.last_interaction_time,
    us.total_score,
    ul.level_name
FROM user_friend uf
JOIN sys_user u ON uf.friend_id = u.id
LEFT JOIN user_score us ON uf.friend_id = us.user_id
LEFT JOIN user_level ul ON us.level_id = ul.id
WHERE uf.user_id = $1
  AND uf.status = 'ACTIVE'
ORDER BY uf.last_interaction_time DESC NULLS LAST;
```

### 10.4 Query Room List

```sql
-- Joinable rooms
SELECT 
    gr.id,
    gr.room_code,
    gr.room_name,
    gt.game_name,
    gr.room_mode,
    gr.current_players,
    gr.max_players,
    u.username AS owner_name,
    gr.created_at
FROM game_room gr
JOIN game_type gt ON gr.game_type_id = gt.id
JOIN sys_user u ON gr.owner_id = u.id
WHERE gr.room_status = 'WAITING'
  AND gr.is_private = FALSE
  AND gr.deleted_at IS NULL
ORDER BY gr.created_at DESC
LIMIT 20;
```

### 10.5 RBAC Permission Queries

```sql
-- User permissions (via roles)
SELECT DISTINCT 
    p.permission_code,
    p.permission_name,
    p.permission_type,
    p.resource_path,
    p.http_method
FROM sys_user_role ur
JOIN sys_role_permission rp ON ur.role_id = rp.role_id
JOIN sys_permission p ON rp.permission_id = p.id
WHERE ur.user_id = $1
  AND p.deleted_at IS NULL
  AND p.status = 1;

-- User menu tree (via roles)
WITH RECURSIVE menu_tree AS (
    -- Root menus
    SELECT 
        m.id,
        m.menu_name,
        m.menu_type,
        m.parent_id,
        m.path,
        m.component,
        m.icon,
        m.permission_code,
        m.sort_order,
        1 AS level
    FROM sys_menu m
    WHERE m.parent_id IS NULL
      AND m.deleted_at IS NULL
      AND m.status = 1
      AND EXISTS (
          SELECT 1 
          FROM sys_user_role ur
          JOIN sys_role_menu rm ON ur.role_id = rm.role_id
          WHERE ur.user_id = $1
            AND rm.menu_id = m.id
      )
    
    UNION ALL
    
    -- Child menus
    SELECT 
        m.id,
        m.menu_name,
        m.menu_type,
        m.parent_id,
        m.path,
        m.component,
        m.icon,
        m.permission_code,
        m.sort_order,
        mt.level + 1
    FROM sys_menu m
    JOIN menu_tree mt ON m.parent_id = mt.id
    WHERE m.deleted_at IS NULL
      AND m.status = 1
      AND EXISTS (
          SELECT 1 
          FROM sys_user_role ur
          JOIN sys_role_menu rm ON ur.role_id = rm.role_id
          WHERE ur.user_id = $1
            AND rm.menu_id = m.id
      )
)
SELECT * FROM menu_tree
ORDER BY level, sort_order;

-- Check if user has specific permission
SELECT EXISTS (
    SELECT 1
    FROM sys_user_role ur
    JOIN sys_role_permission rp ON ur.role_id = rp.role_id
    JOIN sys_permission p ON rp.permission_id = p.id
    WHERE ur.user_id = $1
      AND p.permission_code = $2
      AND p.deleted_at IS NULL
      AND p.status = 1
) AS has_permission;
```

### 10.6 RBAC Menu→Permission Health Check

```sql
-- Check menu-bound permissions exist (for scheduled jobs)
SELECT 
    m.id AS menu_id,
    m.menu_name,
    m.permission_code
FROM sys_menu m
WHERE m.permission_code IS NOT NULL
  AND m.deleted_at IS NULL
  AND m.permission_code NOT IN (
      SELECT permission_code 
      FROM sys_permission 
      WHERE deleted_at IS NULL
  );

-- Check orphan user IDs in matches
SELECT DISTINCT player1_id AS orphan_user_id, 'player1' AS role
FROM game_match 
WHERE player1_id NOT IN (SELECT id FROM sys_user WHERE deleted_at IS NULL)
UNION
SELECT DISTINCT player2_id, 'player2'
FROM game_match 
WHERE player2_id IS NOT NULL 
  AND player2_id NOT IN (SELECT id FROM sys_user WHERE deleted_at IS NULL);

-- Check orphan records in role→permission/menu/user
SELECT 
    rp.role_id,
    rp.permission_id AS orphan_id,
    'permission' AS type
FROM sys_role_permission rp
WHERE rp.permission_id NOT IN (
    SELECT id FROM sys_permission WHERE deleted_at IS NULL
)
UNION ALL
SELECT 
    rm.role_id,
    rm.menu_id AS orphan_id,
    'menu' AS type
FROM sys_role_menu rm
WHERE rm.menu_id NOT IN (
    SELECT id FROM sys_menu WHERE deleted_at IS NULL
)
UNION ALL
SELECT 
    ur.user_id,
    ur.role_id AS orphan_id,
    'role' AS type
FROM sys_user_role ur
WHERE ur.role_id NOT IN (
    SELECT id FROM sys_role WHERE deleted_at IS NULL
);
```

---

## XI. Extension Roadmap (Optional)

### 11.1 Multi-Tenant Support (SaaS Mode)

If SaaS later, add `tenant_id` to core tables and include it in unique indexes.

**Tables**:
- `sys_user`, `sys_role`, `sys_permission`, `sys_menu`, `sys_dept`
- `game_room`, `game_match`, `user_friend`, `chat_session`, etc.

**Example**:
```sql
-- Add tenant_id to sys_user
ALTER TABLE sys_user ADD COLUMN tenant_id UUID;

-- Unique index with tenant_id
DROP INDEX uk_sys_user_username_not_deleted;
CREATE UNIQUE INDEX uk_sys_user_tenant_username ON sys_user(tenant_id, username) 
    WHERE deleted_at IS NULL;

-- Add tenant_id to sys_role
ALTER TABLE sys_role ADD COLUMN tenant_id UUID;
CREATE UNIQUE INDEX uk_sys_role_tenant_code ON sys_role(tenant_id, role_code) 
    WHERE deleted_at IS NULL;
```

**Notes**:
- All queries filter by `tenant_id`
- App enforces isolation
- Recommend RLS for auto tenant filtering

> Single-tenant can ignore; future expansion reference.

---

## XII. Summary

### 12.1 Table Statistics

- **RBAC**: 11 tables (user, role, permission, menu, dept, logs, etc.)
- **Game Module**: 6 tables (game type, room, player, match, detail, snapshot)
- **Social Module**: 6 tables (friend, friend request, session, member, message, attachment)
- **User Growth Module**: 4 tables (score, level, score change, experience change)
- **Matchmaking Module**: 2 tables (queue, record)

**Total: 29 tables**

### 12.2 Design Features

✅ Modular; ✅ Complete indexes; ✅ Player ID uniqueness; ✅ No foreign keys; ✅ Terminal-state guard; ✅ Idempotency keys; ✅ Private chat dedup; ✅ Room session uniqueness; ✅ Password security; ✅ Player count sync; ✅ CHECK constraints; ✅ Triggers; ✅ JSONB; ✅ Soft delete; ✅ Full-text search; ✅ Full comments.

### 12.3 Extensibility

- Add games via `game_type`
- Configure levels via `user_level`
- Adjust score rules via triggers/app logic
- Complex matchmaking via `match_preferences` JSONB

> 🎯 Suitable for multiplayer online board/game platform; supports game, social, growth, matchmaking; production-ready.

---

## XIII. Application-Layer Implementation Notes

### 13.1 Terminal-State Guard

**Database**: CHECK ensures `finished_at` not null only.

**Application**:
- Service: all writes check state; terminal → reject
- Repository: WHERE includes `room_status NOT IN ('FINISHED', 'CLOSED')`
- Gateway: optional double guard

**Example**:
```java
if (room.getRoomStatus() == RoomStatus.FINISHED || room.getRoomStatus() == RoomStatus.CLOSED) {
    throw new BusinessException("Room has ended, operation not allowed");
}
```

### 13.2 Private Chat Dedup

DB unique index on `support_key`.

App:
- Compute `support_key = min(user1,user2) + "|" + max(user1,user2)` before create
- Query or create; on conflict, query existing

### 13.3 Idempotency Keys

- Frontend sends `clientOpId` for replayable APIs
- Backend checks existing; return if present; else process and store
- Optional Redis cache (5 min) to reduce DB lookups

### 13.4 Room Password Hash

- Hash with bcrypt/argon2; store `password_hash`
- Verify via hash; never plaintext

### 13.5 Data Consistency Validation

- Validate referenced IDs before insert
- Clean related data on delete; keep history as needed
- Scheduled orphan checks; alert and optionally auto-clean
- RBAC health check: menu-bound permissions must exist

### 13.6 Partition Management (Optional)

- Tables: `chat_message`, `game_match_detail`, `score_change_log`
- Monthly create next partition; archive >3 months
- PostgreSQL auto-routes; no code change

---

## XIV. Application-Layer Data Consistency Assurance

### 14.1 Why No Foreign Keys?

Service boundaries, performance, maintenance, distributed TX complexity, flexibility.

### 14.2 How App Ensures Consistency

- Validate on insert (check existence)
- Clean on delete (room players, friends, queues; matches winner_id NULL)
- Unique indexes enforce uniqueness
- Periodic orphan checks

### 14.3 Best Practices

Service-layer validation; `@Transactional`; prefer soft delete; async cleanup; monitoring/alerts.

### 14.4 Notes

⚠️ Must validate IDs, clean relations, use unique indexes, run checks.  
✅ Benefits: performance, flexibility, microservice fit.