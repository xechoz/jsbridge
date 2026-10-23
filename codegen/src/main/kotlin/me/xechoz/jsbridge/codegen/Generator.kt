package me.xechoz.jsbridge.codegen

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class CodegenConfig(
    val bridgePackage: String = "me.xechoz.jsbridge",
    val bridgeImport: String = "../bridge",
    val kotlinPackageSuffix: String = "bridge",
)

data class CodegenOutput(
    val kotlinFiles: Map<String, String>,
    val tsFiles: Map<String, String>,
    val manifest: String,
    val schemaHash: String,
)

class Generator(private val config: CodegenConfig) {
    private data class MessageType(
        val protoFullName: String,
        val javaPackage: String,
        val javaClassName: String,
        val tsImportPath: String,
        val tsTypeName: String,
    )

    private data class MethodInfo(
        val name: String,
        val kotlinName: String,
        val tsName: String,
        val id: String,
        val input: MessageType,
        val output: MessageType,
        val deprecated: Boolean,
    )

    private data class ServiceInfo(
        val name: String,
        val fullName: String,
        val kotlinPackage: String,
        val methods: List<MethodInfo>,
    )

    fun generate(set: FileDescriptorSet): CodegenOutput {
        val messages = buildMessageIndex(set)
        val services = buildServices(set, messages)
        val schemaHash = computeSchemaHash(set)

        val kotlinFiles = mutableMapOf<String, String>()
        val tsFiles = mutableMapOf<String, String>()

        services.forEach { service ->
            val kotlinSource = generateKotlinService(service)
            kotlinFiles["${service.kotlinPackage.replace('.', '/')}/${service.name}Bridge.kt"] = kotlinSource
            tsFiles["${service.name}.bridge.ts"] = generateTsService(service)
        }

        if (services.isNotEmpty()) {
            val schemaPackage = services.first().kotlinPackage
            kotlinFiles["${schemaPackage.replace('.', '/')}/BridgeSchema.kt"] = generateKotlinSchema(
                schemaPackage,
                schemaHash,
            )
        }
        tsFiles["schema.ts"] = generateTsSchema(schemaHash)

        return CodegenOutput(
            kotlinFiles = kotlinFiles.toSortedMap(),
            tsFiles = tsFiles.toSortedMap(),
            manifest = generateManifest(services, schemaHash),
            schemaHash = schemaHash,
        )
    }

    private fun buildMessageIndex(set: FileDescriptorSet): Map<String, MessageType> {
        val messages = mutableMapOf<String, MessageType>()
        set.fileList
            .filterNot { it.name.startsWith("google/protobuf/") }
            .forEach { file ->
                val javaPackage = file.options.javaPackage
                if (file.messageTypeList.isNotEmpty()) {
                    require(javaPackage.isNotBlank()) {
                        "${file.name} contains messages but has no java_package option"
                    }
                    require(file.options.javaMultipleFiles) {
                        "${file.name} must set java_multiple_files = true"
                    }
                }
                file.messageTypeList.forEach { message ->
                    val protoFullName = protoFullName(file, message.name)
                    messages[protoFullName] = MessageType(
                        protoFullName = protoFullName,
                        javaPackage = javaPackage,
                        javaClassName = message.name,
                        tsImportPath = tsImportPath(file),
                        tsTypeName = message.name,
                    )
                }
            }
        return messages
    }

    private fun buildServices(
        set: FileDescriptorSet,
        messages: Map<String, MessageType>,
    ): List<ServiceInfo> {
        val services = mutableListOf<ServiceInfo>()
        val usedTsFiles = mutableSetOf<String>()

        set.fileList
            .filterNot { it.name.startsWith("google/protobuf/") }
            .forEach { file ->
                if (file.serviceList.isEmpty()) return@forEach
                val javaPackage = file.options.javaPackage
                require(javaPackage.isNotBlank()) {
                    "${file.name} contains services but has no java_package option"
                }
                val kotlinPackage = if (config.kotlinPackageSuffix.isBlank()) {
                    javaPackage
                } else {
                    "$javaPackage.${config.kotlinPackageSuffix}"
                }
                file.serviceList.forEach { service ->
                    val fullName = serviceFullName(file, service.name)
                    val tsFileName = "${service.name}.bridge.ts"
                    require(usedTsFiles.add(tsFileName)) {
                        "Duplicate generated TypeScript file: $tsFileName"
                    }
                    val methodNames = mutableSetOf<String>()
                    val methods = service.methodList.map { method ->
                        require(!method.clientStreaming && !method.serverStreaming) {
                            "Streaming methods are not supported: $fullName/${method.name}"
                        }
                        val input = messages[method.inputType]
                            ?: error(
                                "Unknown or nested input type '${method.inputType}' for $fullName/${method.name}",
                            )
                        val output = messages[method.outputType]
                            ?: error(
                                "Unknown or nested output type '${method.outputType}' for $fullName/${method.name}",
                            )
                        val kotlinName = lowerCamel(method.name)
                        require(methodNames.add(kotlinName)) {
                            "Duplicate generated method name '$kotlinName' in $fullName"
                        }
                        val tsName = lowerCamel(method.name)
                        require(tsName != "constructor") {
                            "Method name '$tsName' is not supported in TypeScript classes"
                        }
                        MethodInfo(
                            name = method.name,
                            kotlinName = escapeKotlinIdentifier(kotlinName),
                            tsName = tsName,
                            id = "$fullName/${method.name}",
                            input = input,
                            output = output,
                            deprecated = method.options.deprecated,
                        )
                    }
                    services += ServiceInfo(
                        name = service.name,
                        fullName = fullName,
                        kotlinPackage = kotlinPackage,
                        methods = methods,
                    )
                }
            }

        return services.sortedBy { it.fullName }
    }

    private fun generateKotlinService(service: ServiceInfo): String {
        val file = KotlinFile(service.kotlinPackage)
        val jsBridge = file.ref("${config.bridgePackage}.JsBridge")
        val callOptions = file.ref("${config.bridgePackage}.CallOptions")
        val callContext = file.ref("${config.bridgePackage}.CallContext")
        val registration = file.ref("${config.bridgePackage}.Registration")
        val exception = file.ref("${config.bridgePackage}.BridgeException")
        val errorCode = file.ref("${config.bridgePackage}.BridgeErrorCode")
        val ioException = file.ref("java.io.IOException")
        val handlerType = "${service.name}Handler"
        val clientType = "${service.name}Client"

        val methods = service.methods
        val inputType = methods.associateWith { file.ref("${it.input.javaPackage}.${it.input.javaClassName}") }
        val outputType = methods.associateWith { file.ref("${it.output.javaPackage}.${it.output.javaClassName}") }

        file.body.appendLine("public class $clientType(private val bridge: $jsBridge) {")
        methods.forEachIndexed { index, method ->
            if (index > 0) file.body.appendLine()
            file.body.appendLine("  public suspend fun ${method.kotlinName}(")
            file.body.appendLine("    request: ${inputType.getValue(method)},")
            file.body.appendLine("    options: $callOptions = $callOptions(),")
            file.body.appendLine("  ): ${outputType.getValue(method)} {")
            file.body.appendLine("    val bytes = bridge.call(")
            file.body.appendLine("      \"${method.id}\",")
            file.body.appendLine("      ${inputType.getValue(method)}.ADAPTER.encode(request),")
            file.body.appendLine("      options,")
            file.body.appendLine("    )")
            file.body.appendLine("    return try {")
            file.body.appendLine("      ${outputType.getValue(method)}.ADAPTER.decode(bytes)")
            file.body.appendLine("    } catch (e: $ioException) {")
            file.body.appendLine("      throw $exception(")
            file.body.appendLine("        $errorCode.DATA_LOSS,")
            file.body.appendLine("        \"decode ${method.output.javaClassName} failed\",")
            file.body.appendLine("        cause = e,")
            file.body.appendLine("      )")
            file.body.appendLine("    }")
            file.body.appendLine("  }")
        }
        file.body.appendLine("}")

        file.body.appendLine()
        file.body.appendLine("public interface $handlerType {")
        methods.forEachIndexed { index, method ->
            if (index > 0) file.body.appendLine()
            file.body.appendLine("  public suspend fun ${method.kotlinName}(")
            file.body.appendLine("    request: ${inputType.getValue(method)},")
            file.body.appendLine("    context: $callContext,")
            file.body.appendLine("  ): ${outputType.getValue(method)}")
        }
        file.body.appendLine("}")

        file.body.appendLine()
        file.body.appendLine(
            "public fun $jsBridge.register${service.name}(handler: $handlerType): $registration {",
        )
        file.body.appendLine("  val registrations: List<$registration> = listOf(")
        methods.forEach { method ->
            file.body.appendLine("    register(\"${method.id}\") { call ->")
            file.body.appendLine("      val request = try {")
            file.body.appendLine("        ${inputType.getValue(method)}.ADAPTER.decode(call.payload)")
            file.body.appendLine("      } catch (e: $ioException) {")
            file.body.appendLine("        throw $exception(")
            file.body.appendLine("          $errorCode.INVALID_ARGUMENT,")
            file.body.appendLine("          \"decode ${method.input.javaClassName} failed\",")
            file.body.appendLine("          cause = e,")
            file.body.appendLine("        )")
            file.body.appendLine("      }")
            file.body.appendLine(
                "      ${outputType.getValue(method)}.ADAPTER.encode(" +
                    "handler.${method.kotlinName}(request, call.context))",
            )
            file.body.appendLine("    },")
        }
        file.body.appendLine("  )")
        file.body.appendLine("  return $registration { registrations.forEach { it.close() } }")
        file.body.appendLine("}")

        return file.render()
    }

    private fun generateTsService(service: ServiceInfo): String {
        val messageImports = sortedMapOf<String, MutableSet<String>>()
        service.methods.forEach { method ->
            messageImports.getOrPut(method.input.tsImportPath) { sortedSetOf() }
                .add(method.input.tsTypeName)
            messageImports.getOrPut(method.output.tsImportPath) { sortedSetOf() }
                .add(method.output.tsTypeName)
        }

        val result = StringBuilder()
        result.appendLine("// GENERATED CODE - DO NOT EDIT")
        result.appendLine(
            "import { BridgeError, type Bridge, type CallContext, type CallOptions, type Disposable } from '${config.bridgeImport}';",
        )
        messageImports.forEach { (path, names) ->
            result.appendLine("import { ${names.joinToString(", ")} } from '$path';")
        }
        result.appendLine()

        result.appendLine("export class ${service.name}Client {")
        result.appendLine("  constructor(private readonly bridge: Bridge) {}")
        service.methods.forEach { method ->
            result.appendLine()
            result.appendLine(
                "  async ${method.tsName}(request: ${method.input.tsTypeName}, options?: CallOptions): Promise<${method.output.tsTypeName}> {",
            )
            result.appendLine("    const bytes = await this.bridge.call(")
            result.appendLine("      '${method.id}',")
            result.appendLine("      ${method.input.tsTypeName}.encode(request).finish(),")
            result.appendLine("      options,")
            result.appendLine("    );")
            result.appendLine("    try {")
            result.appendLine("      return ${method.output.tsTypeName}.decode(bytes);")
            result.appendLine("    } catch (e) {")
            result.appendLine(
                "      throw new BridgeError('DATA_LOSS', 'decode ${method.output.tsTypeName} failed', undefined, {",
            )
            result.appendLine("        cause: e,")
            result.appendLine("      });")
            result.appendLine("    }")
            result.appendLine("  }")
        }
        result.appendLine("}")
        result.appendLine()

        result.appendLine("export interface ${service.name}Handler {")
        service.methods.forEach { method ->
            result.appendLine(
                "  ${method.tsName}(request: ${method.input.tsTypeName}, context: CallContext): ${method.output.tsTypeName} | Promise<${method.output.tsTypeName}>;",
            )
        }
        result.appendLine("}")
        result.appendLine()

        result.appendLine("export function register${service.name}(")
        result.appendLine("  bridge: Bridge,")
        result.appendLine("  handler: ${service.name}Handler,")
        result.appendLine("): Disposable {")
        result.appendLine("  const registrations: Disposable[] = [")
        service.methods.forEach { method ->
            result.appendLine("    bridge.register('${method.id}', async (payload, context) => {")
            result.appendLine("      let request: ${method.input.tsTypeName};")
            result.appendLine("      try {")
            result.appendLine("        request = ${method.input.tsTypeName}.decode(payload);")
            result.appendLine("      } catch (e) {")
            result.appendLine(
                "        throw new BridgeError('INVALID_ARGUMENT', 'decode ${method.input.tsTypeName} failed', undefined, {",
            )
            result.appendLine("          cause: e,")
            result.appendLine("        });")
            result.appendLine("      }")
            result.appendLine("      const response = await handler.${method.tsName}(request, context);")
            result.appendLine("      return ${method.output.tsTypeName}.encode(response).finish();")
            result.appendLine("    }),")
        }
        result.appendLine("  ];")
        result.appendLine("  return {")
        result.appendLine("    dispose() {")
        result.appendLine("      registrations.forEach((registration) => registration.dispose());")
        result.appendLine("    },")
        result.appendLine("  };")
        result.appendLine("}")
        return result.toString()
    }

    private fun generateKotlinSchema(packageName: String, schemaHash: String): String = buildString {
        appendLine("// GENERATED CODE - DO NOT EDIT")
        appendLine("package $packageName")
        appendLine()
        appendLine("public object BridgeSchema {")
        appendLine("  public const val HASH: String = \"$schemaHash\"")
        appendLine("}")
    }

    private fun generateTsSchema(schemaHash: String): String = buildString {
        appendLine("// GENERATED CODE - DO NOT EDIT")
        appendLine("export const SCHEMA_HASH = '$schemaHash';")
    }

    private fun generateManifest(services: List<ServiceInfo>, schemaHash: String): String {
        val root = JSONObject()
        root.put("protocolVersion", 1)
        root.put("schemaHash", schemaHash)
        val servicesArray = JSONArray()
        services.forEach { service ->
            val serviceJson = JSONObject()
            serviceJson.put("name", service.fullName)
            val methodsArray = JSONArray()
            service.methods.forEach { method ->
                val methodJson = JSONObject()
                methodJson.put("name", method.tsName)
                methodJson.put("id", method.id)
                methodJson.put("input", method.input.protoFullName.removePrefix("."))
                methodJson.put("output", method.output.protoFullName.removePrefix("."))
                methodsArray.put(methodJson)
            }
            serviceJson.put("methods", methodsArray)
            servicesArray.put(serviceJson)
        }
        root.put("services", servicesArray)
        return root.toString(2)
    }

    private fun computeSchemaHash(set: FileDescriptorSet): String {
        val digest = MessageDigest.getInstance("SHA-256")
        set.fileList
            .filterNot { it.name.startsWith("google/protobuf/") }
            .sortedBy { it.name }
            .forEach { file ->
                val normalized = file.toBuilder().clearSourceCodeInfo().build()
                digest.update(normalized.name.toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                digest.update(normalized.toByteArray())
            }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun protoFullName(file: FileDescriptorProto, messageName: String): String {
        val prefix = if (file.`package`.isEmpty()) "" else ".${file.`package`}"
        return "$prefix.$messageName"
    }

    private fun serviceFullName(file: FileDescriptorProto, serviceName: String): String =
        if (file.`package`.isEmpty()) serviceName else "${file.`package`}.$serviceName"

    private fun tsImportPath(file: FileDescriptorProto): String {
        val base = file.name.substringAfterLast('/').removeSuffix(".proto")
        return "./$base"
    }

    private fun lowerCamel(name: String): String {
        val result = StringBuilder(name.length)
        var upperNext = false
        var first = true
        name.forEach { character ->
            if (character == '_') {
                upperNext = true
                return@forEach
            }
            val mapped = when {
                first -> character.lowercaseChar()
                upperNext -> character.uppercaseChar()
                else -> character
            }
            result.append(mapped)
            first = false
            upperNext = false
        }
        return result.toString()
    }

    private fun escapeKotlinIdentifier(name: String): String =
        if (name in KOTLIN_KEYWORDS) "`$name`" else name

    private class KotlinFile(private val packageName: String) {
        private val imports = sortedSetOf<String>()
        private val simpleNames = mutableMapOf<String, String>()
        val body = StringBuilder()

        fun ref(fqn: String): String {
            val simpleName = fqn.substringAfterLast('.')
            val previous = simpleNames[simpleName]
            require(previous == null || previous == fqn) {
                "Kotlin import collision for '$simpleName': $previous vs $fqn"
            }
            simpleNames[simpleName] = fqn
            imports += fqn
            return simpleName
        }

        fun render(): String = buildString {
            appendLine("// GENERATED CODE - DO NOT EDIT")
            appendLine("package $packageName")
            appendLine()
            imports.forEach { appendLine("import $it") }
            if (imports.isNotEmpty()) appendLine()
            append(body)
        }
    }

    private companion object {
        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
            "var", "when", "while", "by", "catch", "constructor", "delegate",
            "dynamic", "field", "file", "finally", "get", "import", "init", "param",
            "property", "receiver", "set", "setparam", "where", "actual", "abstract",
            "annotation", "companion", "const", "crossinline", "data", "enum",
            "expect", "external", "final", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out", "override", "private",
            "protected", "public", "reified", "sealed", "suspend", "tailrec",
            "vararg", "value",
        )
    }
}
