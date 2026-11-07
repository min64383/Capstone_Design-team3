package hearspace.core.calibration

import hearspace.core.types.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Generate an opt-in override from operator measurements. No estimate is substituted for a missing measurement. */
object ChestMountConfigMain {
    fun generate(baseJson: String, parametersJson: String, arguments: Map<String, String>, existingOverride: String? = null): String {
        val expected = setOf("rightCm", "upCm", "backCm", "yawDeg")
        require(arguments.keys == expected) { "Supply measured rightCm, upCm, backCm and yawDeg; no defaults. Got ${arguments.keys}" }
        fun measured(key: String): Double = arguments.getValue(key).toDouble().also { require(it.isFinite()) { "$key must be finite" } }
        val parameters = MiniJson.parse(parametersJson) as? JsonObject ?: error("parameters must be an object")
        require(parameters.fields.keys == setOf("minHorizontalNorm")) { "unexpected measurement parameters" }
        val minNorm = parameters.fields.getValue("minHorizontalNorm") as? JsonNumber ?: error("minHorizontalNorm must be a number")
        // Lens -> head: body right/up/forward. backCm positive means the head is behind the lens.
        val mount = JsonObject(linkedMapOf(
            "yawOffsetDeg" to JsonNumber(measured("yawDeg")),
            "headOffsetBodyM" to JsonArray(listOf(JsonNumber(measured("rightCm") / 100),
                JsonNumber(measured("upCm") / 100), JsonNumber(-measured("backCm") / 100))),
            "minHorizontalNorm" to minNorm,
        ))
        val generated = JsonObject(mapOf("heading" to JsonObject(linkedMapOf(
            "cameraSigmaDeg" to JsonNumber(0.0), "chestMount" to mount,
        ))))
        fun merge(a: JsonObject, b: JsonObject): JsonObject = JsonObject(LinkedHashMap(a.fields).also { out ->
            for ((k, v) in b.fields) {
                val old = out[k]
                out[k] = if (old is JsonObject && v is JsonObject) merge(old, v) else v
            }
        })
        val existing = existingOverride?.let { MiniJson.parse(it) as? JsonObject ?: error("existing override must be an object") }
        val text = MiniJson.write(if (existing == null) generated else merge(existing, generated)) + "\n"
        ConfigLoader.load(baseJson, text) // validate the complete bundle before writing anything
        return text
    }

    @JvmStatic fun main(args: Array<String>) {
        val input = linkedMapOf<String, String>()
        for (arg in args) {
            val pair = arg.split('=', limit = 2)
            require(pair.size == 2 && pair[0] !in input) { "Expected unique key=value arguments" }
            input[pair[0]] = pair[1]
        }
        val dest = File(input.remove("out") ?: error("out is required"))
        val existing = input.remove("existingOverride")?.let { File(it).readText() }
        val text = generate(File(System.getProperty("hearspace.defaultConfig")).readText(),
            File(System.getProperty("hearspace.chestParameters")).readText(), input, existing)
        dest.absoluteFile.parentFile.mkdirs()
        Files.writeString(dest.toPath(), text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        println("Measured chest mount override written: ${dest.absolutePath}")
        println(text)
        println("Review measured values. Restart the session upright after applying; remounting requires new measurements.")
    }
}
