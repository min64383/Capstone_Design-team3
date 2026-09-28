package walkassist.core.types

import walkassist.core.geometry.Vec3

/**
 * JSON 텍스트 → [Config]. 모든 키가 반드시 있어야 하고, 모르는 키·타입 불일치·범위 위반은 [ConfigException].
 * 실험 패널의 덮어쓰기는 부분 JSON을 기본 JSON에 깊게 병합한 뒤 같은 규칙으로 다시 검증한다.
 */
object ConfigLoader {

    /** [baseJson]을 읽고, [overrideJson]이 있으면 그 값을 덮어쓴 설정을 만든다. */
    fun load(baseJson: String, overrideJson: String? = null): Config {
        val base = rootObject(baseJson, "base")
        val merged = if (overrideJson == null) base else merge(base, rootObject(overrideJson, "override"), "")
        return build(Section(merged, ""))
    }

    private fun rootObject(text: String, name: String): JsonObject {
        val v = try {
            MiniJson.parse(text)
        } catch (e: JsonParseException) {
            throw ConfigException("<$name>", "invalid JSON: ${e.message}")
        }
        return v as? JsonObject ?: throw ConfigException("<$name>", "top level must be an object")
    }

    /** 객체끼리는 재귀 병합, 그 외는 덮어쓰기. 기본에 없는 키는 [build]에서 미지의 키로 걸린다. */
    private fun merge(base: JsonObject, over: JsonObject, prefix: String): JsonObject {
        val out = LinkedHashMap(base.fields)
        for ((k, v) in over.fields) {
            val b = out[k]
            out[k] = if (b is JsonObject && v is JsonObject) merge(b, v, "$prefix$k.") else v
        }
        return JsonObject(out)
    }

    private fun build(root: Section): Config {
        val config = Config(
            head = root.section("head") { HeadConfig(offsetFromCameraM = vec3("offsetFromCameraM")) },
            heading = root.section("heading") {
                HeadingConfig(windowS = positive("windowS"), minTravelM = nonNegative("minTravelM"))
            },
            corridor = root.section("corridor") {
                CorridorConfig(
                    widthM = positive("widthM"),
                    heightM = positive("heightM"),
                    lengthM = positive("lengthM"),
                    behindM = nonNegative("behindM"),
                )
            },
            depth = root.section("depth") {
                DepthConfig(
                    subsample = atLeast1("subsample"),
                    source = enumValue<DepthSource>("source"),
                    minConfidence = intIn("minConfidence", 0, 255),
                )
            },
            map = root.section("map") {
                MapConfig(
                    voxelSizeM = positive("voxelSizeM"),
                    hitGain = unit("hitGain"),
                    minHits = atLeast1("minHits"),
                    minScore = unit("minScore"),
                    freeMarginM = nonNegative("freeMarginM"),
                    decayPerObservation = unit("decayPerObservation"),
                    passedMarginM = nonNegative("passedMarginM"),
                    maxUnseenS = positive("maxUnseenS"),
                    radiusM = positive("radiusM"),
                )
            },
            floor = root.section("floor") {
                FloorConfig(
                    searchBandM = positive("searchBandM"),
                    toleranceM = positive("toleranceM"),
                    binM = positive("binM"),
                    emaAlpha = unit("emaAlpha"),
                    minPoints = atLeast1("minPoints"),
                    belowMarginM = positive("belowMarginM"),
                )
            },
            cluster = root.section("cluster") {
                ClusterConfig(
                    epsM = positive("epsM"),
                    minSamples = atLeast1("minSamples"),
                    headMinM = positive("headMinM"),
                    bodyMinM = positive("bodyMinM"),
                )
            },
            track = root.section("track") {
                TrackConfig(matchRadiusM = positive("matchRadiusM"), emaAlpha = unit("emaAlpha"))
            },
            repPoint = root.section("repPoint") { RepPointConfig(strategy = enumValue<RepStrategy>("strategy")) },
            policy = root.section("policy") {
                PolicyConfig(
                    stopM = positive("stopM"),
                    warnMaxM = positive("warnMaxM"),
                    silentMaxM = positive("silentMaxM"),
                    hysteresisM = nonNegative("hysteresisM"),
                    maxSources = atLeast1("maxSources"),
                    maxInfoAgeMs = positive("maxInfoAgeMs"),
                )
            },
            state = root.section("state") {
                StateConfig(
                    recoverFrames = atLeast1("recoverFrames"),
                    recoverScoreScale = unit("recoverScoreScale"),
                    unknownRepeatS = positive("unknownRepeatS"),
                    maxSpeedMps = positive("maxSpeedMps"),
                    maxAngularSpeedDps = positive("maxAngularSpeedDps"),
                )
            },
            audio = root.section("audio") {
                AudioConfig(
                    sampleRate = atLeast1("sampleRate"),
                    blockSize = atLeast1("blockSize"),
                    masterGainDb = float("masterGainDb"),
                )
            },
            record = root.section("record") {
                RecordConfig(
                    depthEveryN = atLeast1("depthEveryN"),
                    rgbEveryN = atLeast1("rgbEveryN"),
                    deviceLogIntervalS = positive("deviceLogIntervalS"),
                )
            },
            align = root.section("align") { AlignConfig(fitLengthM = positive("fitLengthM")) },
        )
        root.rejectUnknown()
        crossCheck(config)
        return config
    }

    /** 여러 키에 걸친 일관성 검사. */
    private fun crossCheck(c: Config) {
        val p = c.policy
        if (!(p.stopM < p.warnMaxM && p.warnMaxM < p.silentMaxM)) {
            throw ConfigException("policy", "must satisfy stopM < warnMaxM < silentMaxM")
        }
        if (c.floor.belowMarginM <= c.floor.toleranceM) {
            throw ConfigException("floor", "must satisfy toleranceM < belowMarginM")
        }
        if (c.cluster.bodyMinM >= c.cluster.headMinM) {
            throw ConfigException("cluster", "must satisfy bodyMinM < headMinM")
        }
    }

    /** 한 JSON 객체를 읽는 도우미. 읽은 키를 기록해 두었다가 [rejectUnknown]에서 나머지를 오류로 만든다. */
    private class Section(private val obj: JsonObject, private val prefix: String) {
        private val used = HashSet<String>()

        fun path(key: String) = prefix + key

        fun raw(key: String): JsonValue {
            used += key
            return obj.fields[key] ?: throw ConfigException(path(key), "missing")
        }

        fun <T> section(key: String, block: Section.() -> T): T {
            val v = raw(key) as? JsonObject ?: throw ConfigException(path(key), "must be an object")
            val s = Section(v, path(key) + ".")
            val result = s.block()
            s.rejectUnknown()
            return result
        }

        fun rejectUnknown() {
            val extra = obj.fields.keys - used
            if (extra.isNotEmpty()) throw ConfigException(path(extra.first()), "unknown key")
        }

        private fun number(key: String): Double {
            val v = raw(key) as? JsonNumber ?: throw ConfigException(path(key), "must be a number")
            if (!v.value.isFinite()) throw ConfigException(path(key), "must be finite")
            return v.value
        }

        fun float(key: String): Float = number(key).toFloat()

        fun positive(key: String): Float =
            float(key).also { if (it <= 0f) throw ConfigException(path(key), "must be > 0, got $it") }

        fun nonNegative(key: String): Float =
            float(key).also { if (it < 0f) throw ConfigException(path(key), "must be >= 0, got $it") }

        fun unit(key: String): Float =
            float(key).also { if (it < 0f || it > 1f) throw ConfigException(path(key), "must be in [0, 1], got $it") }

        fun atLeast1(key: String): Int = intIn(key, 1, Int.MAX_VALUE)

        fun intIn(key: String, lo: Int, hi: Int): Int {
            val d = number(key)
            if (d != Math.rint(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) {
                throw ConfigException(path(key), "must be an integer, got $d")
            }
            if (d < lo || d > hi) {
                throw ConfigException(path(key), if (hi == Int.MAX_VALUE) "must be >= $lo, got ${d.toInt()}" else "must be in [$lo, $hi], got ${d.toInt()}")
            }
            return d.toInt()
        }

        fun vec3(key: String): Vec3 {
            val arr = raw(key) as? JsonArray ?: throw ConfigException(path(key), "must be an array of 3 numbers")
            val xs = arr.items.map {
                ((it as? JsonNumber)?.value ?: throw ConfigException(path(key), "must be an array of 3 numbers")).toFloat()
            }
            if (xs.size != 3) throw ConfigException(path(key), "must have 3 elements, got ${xs.size}")
            return Vec3(xs[0], xs[1], xs[2])
        }

        inline fun <reified E : Enum<E>> enumValue(key: String): E {
            val s = (raw(key) as? JsonString)?.value ?: throw ConfigException(path(key), "must be a string")
            return enumValues<E>().firstOrNull { it.name == s }
                ?: throw ConfigException(path(key), "must be one of ${enumValues<E>().joinToString { it.name }}, got '$s'")
        }
    }
}
