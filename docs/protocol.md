# Wire Protocol

## Envelope

WebView 通道只支持字符串，因此使用 JSON 信封 + base64 payload：

```json
{
  "v": 1,
  "kind": "request",
  "id": "w-1-abc",
  "method": "native.echo",
  "payload": "aGVsbG8="
}
```

字段：

| field | 说明 |
|---|---|
| `v` | 协议版本，当前固定为 1 |
| `kind` | `hello` / `helloAck` / `request` / `response` / `cancel` |
| `id` | 请求关联 id，发起方生成，`a-` 或 `w-` 前缀 |
| `method` | Part 1 为任意字符串；Part 2 为 `<proto package>.<Service>/<Method>` |
| `payload` | 业务字节的标准 base64 |
| `error` | 仅 `response`：`{ "code": "...", "message": "..." }` |
| `schemaHash` | 仅握手：生成代码的 schema 指纹 |
| `reason` | 仅 `cancel`：`timeout` / `cancelled` |

## 握手与生命周期

```
JS createBridge()
  -> hello { schemaHash }
Android 收到 hello
  -> helloAck { schemaHash }
Android 标记 ready；等待中的 Android -> JS 调用可以发送
```

- Android 必须在 `loadUrl` 前 attach。
- 页面开始导航时，业务调用 `bridge.onPageStarted()`：
  - 递增 generation
  - 旧 pending 以 `PAGE_RELOADED` 失败
  - 取消旧页面的 inbound handler
  - 等待新页面的 hello
- JS 页面 `pagehide` 时，pending 以 `PAGE_RELOADED` 失败。
- `bridge.close()` 使 pending 以 `BRIDGE_CLOSED` 失败。

## 调用流程

```
JS call() -> request -> __jsbridgeHost.postMessage()
  -> BridgeEngine 找到 handler -> suspend 执行
  -> response -> main thread evaluateJavascript
  -> window.__jsbridge.receive() -> Promise resolve
```

反向完全对称。所有发送都串行化；response 可以乱序，通过 `id` 关联。

## 超时与取消

- 超时只在发送方计时，默认 30s，可按调用覆盖；`0`/`INFINITE` 可关闭。
- 超时后发送 `cancel`，本地抛 `DEADLINE_EXCEEDED`。
- JS 使用 `AbortSignal`，Kotlin 使用协程取消。
- 接收方按 `id` 取消 handler job；JS handler 收到 `context.signal`。
- 晚到的 response 直接丢弃。

## 错误码

```
CANCELLED
INVALID_ARGUMENT
DEADLINE_EXCEEDED
RESOURCE_EXHAUSTED
UNIMPLEMENTED
INTERNAL
UNAVAILABLE
PAGE_RELOADED
BRIDGE_CLOSED
MESSAGE_TOO_LARGE
DATA_LOSS
```

## 限制

- 单方向最大 in-flight 64
- 单条 payload 上限 1 MiB
- 不支持函数参数、streaming、多 frame
