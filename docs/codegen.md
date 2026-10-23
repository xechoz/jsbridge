# Code Generation

## pipeline

```
proto/demo.proto
  ├─ com.squareup.wire (Gradle plugin, :demo)
  │    -> demo/build/generated/source/wire/...  Kotlin messages
  ├─ protoc-gen-ts_proto
  │    -> web/src/generated/demo.ts             TS messages
  └─ :codegen (Kotlin CLI)
       ├─ --kotlin-out                          Kotlin client / handler / schema
       └─ --ts-out                              TS client / handler / schema
```

`scripts/gen.sh` 负责串起来：

- 生成 descriptor
- 触发 Wire 生成 Kotlin 消息
- 生成 ts-proto 消息
- 生成 Kotlin / TS bridge stubs
- 写出 manifest 和 schema hash

Wire 消息是构建产物，位于 `demo/build/generated/source/wire/<variant>/...`，不提交到 git；每次 Android 构建都会从 `proto/` 重新生成。Kotlin stubs 和 TS 文件是提交的，`scripts/gen.sh --check` 会把它们重新生成到临时目录并 diff。

## Proto 规则

- 必须显式 `option java_package = "...";`（Wire 用它决定 Kotlin 消息包名，codegen 用它生成 import）
- 必须 `option java_multiple_files = true;`（codegen 约束；Wire 本身不依赖）
- 只支持 unary RPC
- RPC 输入/输出必须是顶层 message
- 方法 ID 使用 proto package，而不是 Java / TS 包名

## 生成命名

对 service `Foo`：

Kotlin：

- `FooClient`：`suspend fun method(request, options)`
- `FooHandler`：`suspend fun method(request, context)`
- `JsBridge.registerFoo(handler)`
- `BridgeSchema.HASH`

TypeScript：

- `FooClient`：`method(request, options): Promise<Response>`
- `FooHandler`
- `registerFoo(bridge, handler): Disposable`
- `SCHEMA_HASH`

## 消息运行时

Android 消息类由 Square Wire 7.1.0 生成：

```kotlin
val request = EchoRequest(text = "hello")
val bytes = EchoRequest.ADAPTER.encode(request)
val response = EchoResponse.ADAPTER.decode(bytes)
```

生成的是不可变 Kotlin 类，带 `copy`、`equals`、`hashCode`、`toString`；因为继承 Wire 的 `Message` 并自行实现 equals/hashCode，所以不是字面意义上的 `data class`。

bridge stubs 使用 `ADAPTER` 编解码，不再使用 Java protobuf 的 `parseFrom` / `toByteArray`。

## schema hash

对除 `google/protobuf/*` 外的 descriptor 文件按名称排序，清除 `source_code_info` 后做 SHA-256。Kotlin 与 TS 握手时带上该值；不一致仅记录警告（当前不做强制失败）。
