package me.xechoz.jsbridge.codegen

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import java.io.File
import kotlin.system.exitProcess

private data class Options(
    val descriptor: File,
    val kotlinOut: File?,
    val tsOut: File?,
    val manifestOut: File?,
    val bridgePackage: String,
    val bridgeImport: String,
    val kotlinPackageSuffix: String,
)

fun main(args: Array<String>) {
    if (args.contains("--help") || args.isEmpty()) {
        printUsage()
        return
    }

    try {
        val options = parseOptions(args)
        val set = FileDescriptorSet.parseFrom(options.descriptor.readBytes())
        val generator = Generator(
            CodegenConfig(
                bridgePackage = options.bridgePackage,
                bridgeImport = options.bridgeImport,
                kotlinPackageSuffix = options.kotlinPackageSuffix,
            ),
        )
        val output = generator.generate(set)

        options.kotlinOut?.let { root ->
            output.kotlinFiles.forEach { (path, content) -> writeFile(root, path, content) }
        }
        options.tsOut?.let { root ->
            output.tsFiles.forEach { (path, content) -> writeFile(root, path, content) }
        }
        options.manifestOut?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(output.manifest)
        }

        println(
            "jsbridge codegen: ${output.kotlinFiles.size} Kotlin file(s), " +
                "${output.tsFiles.size} TypeScript file(s), schema=${output.schemaHash}",
        )
    } catch (exception: Exception) {
        System.err.println("jsbridge codegen failed: ${exception.message}")
        exception.printStackTrace()
        exitProcess(1)
    }
}

private fun parseOptions(args: Array<String>): Options {
    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        require(argument.startsWith("--")) { "Unexpected argument: $argument" }
        val withoutPrefix = argument.removePrefix("--")
        val equalsIndex = withoutPrefix.indexOf('=')
        if (equalsIndex >= 0) {
            values[withoutPrefix.substring(0, equalsIndex)] = withoutPrefix.substring(equalsIndex + 1)
            index += 1
        } else {
            require(index + 1 < args.size) { "Missing value for $argument" }
            values[withoutPrefix] = args[index + 1]
            index += 2
        }
    }

    val descriptor = values["descriptor"]
        ?: error("--descriptor is required")

    val kotlinOut = values["kotlin-out"]?.let(::File)
    val tsOut = values["ts-out"]?.let(::File)
    val manifestOut = values["manifest-out"]?.let(::File)
    require(kotlinOut != null || tsOut != null || manifestOut != null) {
        "At least one of --kotlin-out, --ts-out, --manifest-out is required"
    }

    return Options(
        descriptor = File(descriptor),
        kotlinOut = kotlinOut,
        tsOut = tsOut,
        manifestOut = manifestOut,
        bridgePackage = values["bridge-package"] ?: "me.xechoz.jsbridge",
        bridgeImport = values["bridge-import"] ?: "../bridge",
        kotlinPackageSuffix = values["kotlin-package-suffix"] ?: "bridge",
    )
}

private fun writeFile(root: File, relativePath: String, content: String) {
    val file = File(root, relativePath)
    file.parentFile?.mkdirs()
    file.writeText(content)
}

private fun printUsage() {
    println(
        """
        Usage: codegen --descriptor <file> [options]

        Required:
          --descriptor <file>           FileDescriptorSet produced by protoc

        Outputs (at least one):
          --kotlin-out <dir>            Write Kotlin client/handler/schema files
          --ts-out <dir>                Write TypeScript client/handler/schema files
          --manifest-out <file>         Write the JSON method manifest

        Options:
          --bridge-package <package>    Kotlin bridge package (default: me.xechoz.jsbridge)
          --bridge-import <specifier>   TS bridge import (default: ../bridge)
          --kotlin-package-suffix <s>   Kotlin stub package suffix (default: bridge)
        """.trimIndent(),
    )
}
