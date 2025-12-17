# Game Hub 🎮

> Real-time multiplayer **mini-game** platform (v1.0) built with Java 21, Spring Boot 3, Spring Cloud & WebSocket.

Game Hub focuses on **engineering capabilities** behind a real-time battle platform: session governance, room isolation, state recovery, and cross-service collaboration – with a complete, runnable microservice stack.

---

## 🚀 What You Get in v1.0

- **Real-time multiplayer mini-games**  
  - Current implementation: **Gomoku** (PVP / PVE) with AI (Threat-first + Alpha-Beta pruning)
- **Rooms, lobby & chat**
  - Game rooms with isolation, lobby/room/private chat, system notifications
- **Rejoin & state recovery**  
  - Full **match re-entry & state restore**: board, timers, and room status are rebuilt from Redis after client reconnects or service restarts
- **Session governance & single-device login**  
  - Keycloak + JWT + session registry + token blacklist, with “kick other devices” support
- **Distributed countdown timer**
  - Turn timers and timeout handling coordinated via Redis-based scheduler
- **Event-driven architecture**
  - Kafka for session/notification/room events, decoupling game, chat, and system services

> **Version:** v1.0 – to **ship quickly**, this version only supports **single-instance deployment**.  
> For the impact of multi-instance deployment in v1.0 and the upgrade plan for future multi-instance support, see:  
> - `docs/zh/1.0版本多实例部署影响分析.md` (Chinese)  
> - `docs/en/1.0-Multi-Instance-Deployment-Impact-Analysis.md` (English)

---

## 🧱 Tech Highlights

- **Backend**
  - Java 21, Spring Boot 3.3.x, Spring Cloud 2023.x
  - Spring WebSocket + STOMP, Spring Security, OpenFeign
- **Infra & Storage**
  - PostgreSQL for user/auth/chat data
  - Redis for room / match / session state & countdown
  - Kafka as event bus
  - MinIO for avatars / replays / assets
- **Identity & Auth**
  - Keycloak (OAuth2 / OIDC) with per-session control and login events integration

---

## 📖 Documentation

- 🇨🇳 **中文文档**  
  👉 [docs/zh/README.md](docs/zh/README.md)

- 🇺🇸 **English Documentation**  
  👉 [docs/en/README.md](docs/en/README.md)

