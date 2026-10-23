package me.xechoz.jsbridge.codegen

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.DescriptorProtos.FileOptions
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorTest {
    @Test
    fun generatesKotlinAndTypeScriptStubs() {
        val output = Generator(CodegenConfig()).generate(fixture())

        val kotlin = output.kotlinFiles["me/xechoz/demo/proto/bridge/NativeApiBridge.kt"]
        assertNotNull(kotlin)
        kotlin!!
        assertTrue(kotlin.contains("public suspend fun echo"))
        assertTrue(kotlin.contains("bridge.call("))
        assertTrue(kotlin.contains("demo.bridge.NativeApi/Echo"))
        assertTrue(kotlin.contains("public fun JsBridge.registerNativeApi"))
        assertTrue(kotlin.contains("EchoRequest.ADAPTER.encode(request)"))
        assertTrue(kotlin.contains("EchoResponse.ADAPTER.decode(bytes)"))
        assertTrue(kotlin.contains("EchoRequest.ADAPTER.decode(call.payload)"))
        assertTrue(
            kotlin.contains(
                "EchoResponse.ADAPTER.encode(handler.echo(request, call.context))",
            ),
        )
        assertTrue(kotlin.contains("import java.io.IOException"))
        assertFalse(kotlin.contains("parseFrom"))
        assertFalse(kotlin.contains("toByteArray"))

        val ts = output.tsFiles["NativeApi.bridge.ts"]
        assertNotNull(ts)
        ts!!
        assertTrue(ts.contains("export class NativeApiClient"))
        assertTrue(ts.contains("EchoRequest.encode(request).finish()"))
        assertTrue(ts.contains("export function registerNativeApi"))
        assertTrue(ts.contains("from './demo'"))

        val manifest = JSONObject(output.manifest)
        assertEquals(1, manifest.getInt("protocolVersion"))
        assertEquals(64, output.schemaHash.length)
        val services = manifest.getJSONArray("services")
        assertEquals(2, services.length())
        val nativeApi = services.getJSONObject(0)
        assertEquals("demo.bridge.NativeApi", nativeApi.getString("name"))
        assertEquals(
            "demo.bridge.NativeApi/Echo",
            nativeApi.getJSONArray("methods").getJSONObject(0).getString("id"),
        )
    }

    @Test
    fun escapesKotlinKeywords() {
        val output = Generator(CodegenConfig()).generate(fixtureWithKeywordMethod())
        val kotlin = output.kotlinFiles["me/xechoz/demo/proto/bridge/KeywordApiBridge.kt"]!!
        assertTrue(kotlin.contains("`is`"))
    }

    @Test
    fun rejectsMissingJavaPackage() {
        val file = FileDescriptorProto.newBuilder()
            .setName("demo.proto")
            .setPackage("demo.bridge")
            .addMessageType(DescriptorProto.newBuilder().setName("Request"))
            .addMessageType(DescriptorProto.newBuilder().setName("Response"))
            .addService(
                ServiceDescriptorProto.newBuilder()
                    .setName("Api")
                    .addMethod(
                        MethodDescriptorProto.newBuilder()
                            .setName("Call")
                            .setInputType(".demo.bridge.Request")
                            .setOutputType(".demo.bridge.Response"),
                    ),
            )
            .build()
        val set = FileDescriptorSet.newBuilder().addFile(file).build()
        assertThrows(IllegalArgumentException::class.java) {
            Generator(CodegenConfig()).generate(set)
        }
    }

    @Test
    fun rejectsStreamingMethods() {
        val file = baseFile()
            .toBuilder()
            .addService(
                ServiceDescriptorProto.newBuilder()
                    .setName("StreamingApi")
                    .addMethod(
                        MethodDescriptorProto.newBuilder()
                            .setName("Watch")
                            .setInputType(".demo.bridge.EchoRequest")
                            .setOutputType(".demo.bridge.EchoResponse")
                            .setServerStreaming(true),
                    ),
            )
            .build()
        val set = FileDescriptorSet.newBuilder().addFile(file).build()
        assertThrows(IllegalArgumentException::class.java) {
            Generator(CodegenConfig()).generate(set)
        }
    }

    private fun fixture(): FileDescriptorSet = FileDescriptorSet.newBuilder()
        .addFile(
            baseFile()
                .toBuilder()
                .addMessageType(message("AddRequest"))
                .addMessageType(message("AddResponse"))
                .addService(
                    ServiceDescriptorProto.newBuilder()
                        .setName("NativeApi")
                        .addMethod(method("Echo", ".demo.bridge.EchoRequest", ".demo.bridge.EchoResponse")),
                )
                .addService(
                    ServiceDescriptorProto.newBuilder()
                        .setName("WebApi")
                        .addMethod(method("Add", ".demo.bridge.AddRequest", ".demo.bridge.AddResponse")),
                )
                .build(),
        )
        .build()

    private fun fixtureWithKeywordMethod(): FileDescriptorSet = FileDescriptorSet.newBuilder()
        .addFile(
            baseFile()
                .toBuilder()
                .addService(
                    ServiceDescriptorProto.newBuilder()
                        .setName("KeywordApi")
                        .addMethod(method("Is", ".demo.bridge.EchoRequest", ".demo.bridge.EchoResponse")),
                )
                .build(),
        )
        .build()

    private fun baseFile(): FileDescriptorProto = FileDescriptorProto.newBuilder()
        .setName("demo.proto")
        .setPackage("demo.bridge")
        .setSyntax("proto3")
        .setOptions(
            FileOptions.newBuilder()
                .setJavaPackage("me.xechoz.demo.proto")
                .setJavaMultipleFiles(true),
        )
        .addMessageType(message("EchoRequest"))
        .addMessageType(message("EchoResponse"))
        .build()

    private fun message(name: String): DescriptorProto = DescriptorProto.newBuilder()
        .setName(name)
        .build()

    private fun method(name: String, input: String, output: String): MethodDescriptorProto =
        MethodDescriptorProto.newBuilder()
            .setName(name)
            .setInputType(input)
            .setOutputType(output)
            .build()
}
