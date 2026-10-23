# jsbridge

[![CI](https://github.com/xechoz/jsbridge/actions/workflows/ci.yml/badge.svg)](https://github.com/xechoz/jsbridge/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-0.1.0-blue.svg)](https://central.sonatype.com/artifact/io.github.xechoz/jsbridge/0.1.0)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Android WebView 与 Web 的双向异步 RPC，分两层：

1. **Part 1：核心 JS ↔ Android 交互**
   - Kotlin 侧统一 `suspend`
   - Web 侧统一 `Promise`
   - 支持返回值、错误、超时、取消、页面重载
2. **Part 2：protobuf 强类型代码生成**
   - 由 `.proto` 生成 Kotlin / TypeScript 的 typed client、handler
   - 生成代码复用 Part 1，无独立运行时

## 安装

Maven Central：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.xechoz:jsbridge:0.1.0")
}
```

发布页：

- Central Portal：https://central.sonatype.com/artifact/io.github.xechoz/jsbridge/0.1.0
- GitHub Releases：https://github.com/xechoz/jsbridge/releases
- Maven Central 目录：https://repo1.maven.org/maven2/io/github/xechoz/jsbridge/

已用发布版在独立 Android library 工程中做过消费验证：依赖可解析，`JsBridge` API 可编译。

## 范围与限制

当前只实现 `addJavascriptInterface` + `evaluateJavascript`：

- 不做 origin 校验、`WebMessageListener`、allowlist
- 不做多 frame
- 不支持 streaming / 事件订阅
- 不支持把函数作为参数传过桥；回调语义用 `suspend` / `Promise` 表达
- 单条 payload 默认上限 1 MiB

只应在可信页面中使用。需要安全隔离时，后续增加 `WebMessageListener` transport。

## 仓库结构

```
jsbridge/                 Android library：桥核心 + WebView transport
  src/main/kotlin/me/xechoz/jsbridge/
    BridgeEngine.kt       协议状态机、pending、handler、超时/取消
    JsBridge.kt           WebView 包装
    Envelope.kt           JSON 信封
    ...
demo/                     Android demo
  src/main/assets/web/    由 scripts/dev-install.sh 生成的 Web 产物
  src/main/kotlin/...      demo + codegen 生成的 Kotlin stubs
  build/generated/source/wire/...  Wire 生成的 Kotlin 消息类（构建产物）
  src/androidTest/...      真实 WebView 双向 smoke test
codegen/                  Kotlin CLI，同时生成 Kotlin/TS stubs
proto/demo.proto          示例 API
web/
  src/bridge/             TS 运行时
  src/demo/               demo 页面
  src/generated/          ts-proto + codegen 生成物
scripts/gen.sh            protobuf 代码生成 / 校验
scripts/dev-install.sh    构建 Web + 安装 demo
```

## Part 1：核心 API

### Android

```kotlin
val bridge = JsBridge.attach(
    webView = webView,
    scope = lifecycleScope,
    config = JsBridgeConfig(
        defaultTimeout = 30.seconds,
        readyTimeout = 5.seconds,
    ),
)

// Android -> JS
val reply: ByteArray = bridge.call("js.echo", payload)

// JS -> Android
bridge.register("native.echo") { call ->
    call.payload
}

// 页面导航时由业务调用
override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
    bridge.onPageStarted()
}

// WebView 销毁时
bridge.close()
```

文本便捷方法：

```kotlin
val reply = bridge.callText("js.echo", "hello")
bridge.registerText("native.echo") { text, context -> text }
```

### Web

```ts
const bridge = createBridge();
await bridge.ready();

// JS -> Android
const reply = await bridge.callText('native.echo', 'hello');

// Android -> JS
bridge.registerText('js.echo', async (text) => `js:${text}`);
```

取消与超时：

```ts
const controller = new AbortController();
setTimeout(() => controller.abort(), 100);
await bridge.callText('native.delayed', '', { signal: controller.signal });
```

底层是 JSON 信封 + base64 payload。见 [docs/protocol.md](docs/protocol.md)。

## Part 2：protobuf 代码生成

`proto/demo.proto`：

```proto
syntax = "proto3";
package demo.bridge;
option java_package = "me.xechoz.jsbridge.demo.proto";
option java_multiple_files = true;

service NativeApi {
  rpc Echo(EchoRequest) returns (EchoResponse);
}
service WebApi {
  rpc Add(AddRequest) returns (AddResponse);
}
```

生成：

```bash
./scripts/gen.sh
# 校验生成物是否最新
./scripts/gen.sh --check
```

会产出：

- `demo/build/generated/source/wire/.../proto/*.kt`：Wire 生成的 Kotlin 消息类（构建时生成，不提交）
- `demo/src/main/kotlin/me/xechoz/jsbridge/demo/proto/bridge/*.kt`：Kotlin typed client / handler
- `web/src/generated/demo.ts`：ts-proto 消息类型
- `web/src/generated/*.bridge.ts`：TypeScript typed client / handler
- `build/generated/jsbridge-manifest.json`：方法表 + schema hash

Android 客户端全部是 Kotlin `suspend` API：

```kotlin
bridge.registerNativeApi(object : NativeApiHandler {
    override suspend fun echo(
        request: EchoRequest,
        context: CallContext,
    ): EchoResponse = EchoResponse(text = "android:${request.text}")
})

val webApi = WebApiClient(bridge)
val sum = webApi.add(AddRequest(a = 2, b = 3)).sum
```

Web 客户端：

```ts
const nativeApi = new NativeApiClient(bridge);
const response = await nativeApi.echo({ text: 'hello' });

registerWebApi(bridge, {
  async add(request) {
    return { sum: request.a + request.b };
  },
});
```

> 说明：Android 侧从调用层到消息类都是 Kotlin。消息类由 Square Wire 7.1.0 在构建时生成，使用 `Message.ADAPTER` 编解码。Wire 生成的是不可变 Kotlin 类，带 `copy`、`equals`、`hashCode`、`toString`，语义上等价 data class，但没有 `data class` 关键字。

### Codegen 约束

- 必须写 `java_package`
- 必须 `java_multiple_files = true`
- RPC 只支持 unary
- RPC 输入/输出必须是顶层 message
- 方法 ID：`<proto package>.<Service>/<Method>`

## 构建与测试

环境基线：

- JDK 21（`gradle.properties` 里固定了路径）
- Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.20
- Wire 7.1.0（Kotlin 消息生成）
- Android SDK：`/home/xechoz/Android/Sdk`，compileSdk 36，minSdk 24
- Node 26 / npm 11
- protoc 36.1（只用于 descriptor 和 ts-proto）

```bash
# 核心单元测试
./gradlew :jsbridge:test

# codegen 单元测试
./gradlew :codegen:test

# Web 单元测试 + 类型检查
cd web && npm test && npm run typecheck

# 生成 protobuf 代码（包含 Wire Kotlin 消息 + Kotlin/TS stubs）
cd .. && ./scripts/gen.sh

# 构建 Web 产物、安装并启动 demo（默认 emulator-5554）
./scripts/dev-install.sh

# 真实 WebView 双向 smoke test
ANDROID_SERIAL=emulator-5554 ./gradlew :demo:connectedDebugAndroidTest
```

## Demo 能验证什么

- JS → Android echo
- Android → JS 反向调用（嵌套 `native.reverseEcho` → `js.echo`）
- 超时 `DEADLINE_EXCEEDED`
- `AbortSignal` 取消 `CANCELLED`
- handler 抛 `BridgeException` 的错误传播
- typed `NativeApi.echo`（JS 调 Android）
- typed `WebApi.add`（Android 调 JS）
- 页面 reload 后重新握手

## 已知取舍

- 页面导航必须由业务调用 `bridge.onPageStarted()`；当前没有自动包装 `WebViewClient`。
- `JsBridge.attach` 必须在主线程、且在 `loadUrl` 之前。
- `@JavascriptInterface` 的 `postMessage` 只入队后立即返回，解析和 handler 派发在 bridge dispatcher 上。
- Maven Central 发布坐标：`io.github.xechoz:jsbridge:0.1.0`。

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
