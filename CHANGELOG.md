# Changelog

## 0.1.0 - 2026-10-23

首个公开版本。

### Part 1：核心桥

- Android WebView 与 Web 双向异步 RPC
- Kotlin `suspend` / Web `Promise` 统一异步 API
- JSON 信封 + base64 payload
- 超时、协程取消、`AbortSignal` 取消
- 页面重载 `PAGE_RELOADED`、桥关闭 `BRIDGE_CLOSED`
- 错误码与 `BridgeException` / `BridgeError`
- 1 MiB payload 上限、64 in-flight 限制
- `addJavascriptInterface` + `evaluateJavascript` transport

### Part 2：protobuf 强类型 API

- 从 `.proto` 生成 Kotlin / TypeScript typed client、handler
- Android 消息类使用 Square Wire 生成（构建时）
- Web 消息类使用 ts-proto
- schema hash 握手校验
- `scripts/gen.sh --check` 生成物漂移检查

### 发布

- Maven Central：`io.github.xechoz:jsbridge:0.1.0`
- Apache-2.0
- GitHub Actions CI / tag 发布流程
