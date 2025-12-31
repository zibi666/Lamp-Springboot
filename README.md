# 灵犀台灯后端服务 (LAMP)

一个基于 Spring Boot 的智能台灯后端系统，整合了**阿里云语音识别/合成服务**和**Coze智能体**，提供语音交互、灯光控制和实时通信能力。

## 📋 目录

- [项目概述](#项目概述)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [环境配置](#环境配置)
- [API 文档](#api-文档)
- [功能模块说明](#功能模块说明)
- [常见问题](#常见问题)

## 🎯 项目概述

灵犀台灯后端服务是一个全栈的 IoT + AI 系统，支持：

- **🎤 语音识别** - 使用阿里云 ASR 将用户语音转换为文本指令
- **🤖 智能对话** - 使用 Coze AI 智能体处理业务逻辑和意图识别
- **🔊 语音合成** - 使用阿里云 TTS 将系统回复转换为语音
- **💡 灯光控制** - HTTP API 控制台灯的亮度、色温等参数
- **🔗 WebSocket 实时通信** - 与台灯设备及客户端保持双向实时连接

## 🛠️ 技术栈

| 层级 | 技术 | 版本 |
|-----|------|------|
| **Java** | OpenJDK | 17 |
| **框架** | Spring Boot | 3.2.5 |
| **Web** | Spring MVC + WebSocket | - |
| **数据库** | MySQL 8.0（腾讯云CynosDB） | - |
| **语音服务** | 阿里云 NLS（语音识别、语音合成） | 2.2.x |
| **AI智能体** | Coze API | v3 |
| **构建工具** | Maven | 3.x |
| **辅助库** | Lombok、pinyin4j、Concentus | - |

## 📁 项目结构

```
Lamp-Springboot/
├── src/main/
│   ├── java/com/example/edog/
│   │   ├── LampApplication.java          # Spring Boot 启动类
│   │   ├── configurer/
│   │   │   └── WebSocketConfig.java      # WebSocket 配置
│   │   ├── controller/
│   │   │   ├── LightController.java      # 灯光控制 HTTP 接口
│   │   │   └── WebSocketController.java  # WebSocket 消息处理
│   │   ├── service/
│   │   │   ├── AliyunRealtimeASR.java    # 实时语音识别服务
│   │   │   ├── AliyunTTSService.java     # 语音合成服务
│   │   │   ├── AliyunTokenService.java   # 阿里云 Token 管理
│   │   │   └── WebSocketServer.java      # WebSocket 服务端
│   │   └── utils/
│   │       ├── AliyunCredentials.java    # 阿里云凭证管理
│   │       ├── AudioConverter.java       # 音频格式转换
│   │       ├── CozeAPI.java              # Coze AI 接口
│   │       └── PinyinUtils.java          # 拼音处理工具
│   └── resources/
│       └── application.yaml              # 应用配置文件
├── pom.xml                               # Maven 项目配置
├── mvnw / mvnw.cmd                       # Maven Wrapper
└── README.md                             # 项目说明（本文件）
```

## 🚀 快速开始

### 前置条件

- JDK 17 或更高版本
- Maven 3.6.0 或更高版本
- MySQL 8.0 数据库（或使用已有的数据库连接）
- 阿里云账号（语音服务）
- Coze 账号（智能体 API）

### 安装步骤

1. **克隆项目**
   ```bash
   git clone <项目地址>
   cd Lamp-Springboot
   ```

2. **配置环境变量**  
   编辑 `src/main/resources/application.yaml`，填入以下信息：
   ```yaml
   spring:
     datasource:
       url: jdbc:mysql://[主机]:[端口]/[数据库名]
       username: [用户名]
       password: [密码]
   
   aliyun:
     appKey: [阿里云 AppKey]
     accessKeyId: [阿里云 AccessKey ID]
     accessKeySecret: [阿里云 AccessKey Secret]
   
   kouzi:
     agent:
       token: [Coze API Token]
       bot-id: "[Coze Bot ID]"
   ```

3. **编译项目**
   ```bash
   mvn clean install
   ```
   或使用 Maven Wrapper：
   ```bash
   ./mvnw clean install  # Unix/Linux/Mac
   mvnw.cmd clean install  # Windows
   ```

4. **运行应用**
   ```bash
   mvn spring-boot:run
   ```
   应用将在 `http://localhost:6060` 启动

## ⚙️ 环境配置

### application.yaml 详解

```yaml
spring:
  application:
    name: LAMP                    # 应用名称
  datasource:                    # 数据库配置
    url: jdbc:mysql://...
    username: root
    password: ***
    hikari:
      maximum-pool-size: 10      # 最大连接数
      minimum-idle: 2            # 最小空闲连接数

server:
  port: 6060                     # 服务端口

aliyun:                          # 阿里云配置
  appKey: xxx                    # 语音服务 App Key
  accessKeyId: xxx               # 访问密钥 ID
  accessKeySecret: xxx           # 访问密钥

kouzi:                           # Coze 智能体配置
  agent:
    base-url: https://api.coze.cn/v3/chat
    token: xxx                   # API Token
    bot-id: "xxxxx"              # 机器人 ID
```

## 📡 API 文档

### 灯光控制 API

#### 1. 开启/关闭灯光
```http
POST /api/light/switch
Content-Type: application/json

{
  "status": true  // true: 开启, false: 关闭
}
```

#### 2. 调节亮度
```http
POST /api/light/brightness
Content-Type: application/json

{
  "brightness": 80  // 0-100
}
```

#### 3. 设置色温
```http
POST /api/light/color-temperature
Content-Type: application/json

{
  "temperature": 6500  // K (开尔文)
}
```

### WebSocket 接口

**连接地址**: `ws://localhost:6060/ws`

#### 消息格式
```json
{
  "type": "audio",           // 消息类型: audio, command, etc.
  "data": "base64编码音频"
}
```

#### 消息类型说明
- **audio** - 语音数据（base64 编码）
- **command** - 文本命令
- **heartbeat** - 心跳包（保持连接活跃）

## 🔧 功能模块说明

### 1. 语音识别 (ASR)

**类**: [AliyunRealtimeASR.java](src/main/java/com/example/edog/service/AliyunRealtimeASR.java)

将用户的语音指令实时转换为文本，支持：
- 实时流式识别
- 中文普通话识别
- 噪声抑制和语音增强

### 2. 智能对话引擎

**类**: [CozeAPI.java](src/main/java/com/example/edog/utils/CozeAPI.java)

使用 Coze 平台的 AI 智能体处理：
- 意图识别和理解
- 业务逻辑处理
- 多轮对话管理

### 3. 语音合成 (TTS)

**类**: [AliyunTTSService.java](src/main/java/com/example/edog/service/AliyunTTSService.java)

将系统回复合成为自然语音输出，支持：
- 多种音色选择
- 语速和音量调控
- 实时流式合成

### 4. 灯光控制

**类**: [LightController.java](src/main/java/com/example/edog/controller/LightController.java)

提供 RESTful API 进行：
- 开关灯光
- 调节亮度和色温
- 预设场景控制

### 5. WebSocket 实时通信

**类**: [WebSocketServer.java](src/main/java/com/example/edog/service/WebSocketServer.java)

管理客户端连接，支持：
- 双向实时消息传输
- 连接生命周期管理
- 广播和单点消息

### 6. Token 管理

**类**: [AliyunTokenService.java](src/main/java/com/example/edog/service/AliyunTokenService.java)

自动管理阿里云服务的认证 Token：
- 定期刷新过期 Token
- 缓存管理
- 异常重试

## ❓ 常见问题

### Q1: 如何获取阿里云 AppKey 和 AccessKey？
A: 访问 [阿里云控制台](https://console.aliyun.com)，在 **NLS 语音服务** 页面获取。

### Q2: Coze Bot ID 在哪里获取？
A: 登录 [Coze 开发平台](https://www.coze.cn)，在创建的 Bot 设置页面可以找到 Bot ID。

### Q3: WebSocket 连接频繁断开如何解决？
A: 
- 检查防火墙设置是否允许 WebSocket 连接
- 在客户端实现心跳机制（每 30 秒发送一次）
- 确保服务器 `application.yaml` 中 WebSocket 配置正确

### Q4: 语音识别准确率较低？
A: 
- 检查音频质量，确保采样率为 16000 Hz
- 在噪声环境中，使用 AudioConverter 进行预处理
- 验证阿里云 AppKey 是否有效

### Q5: 如何修改服务端口？
A: 在 `application.yaml` 中修改：
   ```yaml
   server:
     port: 8080  # 改为需要的端口
   ```

## 📝 开发建议

- 确保所有配置信息（密钥、Token）存储在环境变量中，不要提交到版本控制
- 使用 Lombok 注解减少模板代码
- 为语音服务添加重试机制，处理网络不稳定情况
- 定期检查依赖更新，特别是阿里云 SDK

## 📞 支持

如有问题或建议，请提交 Issue 或联系开发团队。

---

**最后更新**: 2025年12月31日
