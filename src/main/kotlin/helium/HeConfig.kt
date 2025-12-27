package helium

import arc.files.Fi
import arc.input.KeyCode
import arc.util.Log
import arc.util.Threads
import arc.util.serialization.Jval
import helium.graphics.HeShaders
import helium.ui.fragments.entityinfo.displays.EntityRangeDisplay
import java.io.IOException

private typealias RArray = java.lang.reflect.Array

class HeConfig(configDir: Fi, internalSource: Fi) {
  companion object {
    private var exec = Threads.unboundedExecutor("HTTP", 1)
    private val commentMatcher = Regex("(//.*)|(/\\*(.|\\s)+\\*/)")
    private val keyMatcher = Regex("\"?(\\w+)\"?\\s*:\\s*")
    private val jsonArrayMatcher = Regex("\\[(.|\\s)*]")
    private val jsonObjectMatcher = Regex("\\{(.|\\s)*\\}")
    private val jsonValueMatcher = Regex("(\".*\")|([\\w.]+)")

    private val configs = HeConfig::class.java.declaredFields
      .filter { it.getAnnotation(ConfigItem::class.java) != null }
  }

  private val configFile = configDir.child("mod_config.hjson")
  private val configBack = configDir.child("mod_config.hjson.bak")
  private val internalConfigFile = internalSource
  private val configVersion = Jval.read(internalConfigFile.reader()).getInt("configVersion", 0)
  
  @ConfigItem var loadInfo = true

  @ConfigItem var enableBlur = true
  @ConfigItem var blurScl = 4
  @ConfigItem var blurSpace = 1.25f

  @ConfigItem var enableEntityInfoDisplay = true
  @ConfigItem var enableHealthBarDisplay = true
    set(value){ field = value; He.entityInfo.displaySetupUpdated() }
  @ConfigItem var hideHealthText = false
  @ConfigItem var enableUnitStatusDisplay = true
    set(value){ field = value; He.entityInfo.displaySetupUpdated() }
  @ConfigItem var enableRangeDisplay = true
    set(value){ field = value; He.entityInfo.displaySetupUpdated() }
  @ConfigItem var rangeRenderLevel = 0
    set(value){
      field = value
      EntityRangeDisplay.renderer = when(value){
        0 -> HeShaders.entityRangeRenderer
        1 -> HeShaders.lowEntityRangeRenderer
        else -> null
      }
    }
  @ConfigItem var showAttackRange = true
    set(value){ field = value; He.entityInfo.displaySetupUpdated() }
  @ConfigItem var showHealRange = true
    set(value){ field = value; He.entityInfo.displaySetupUpdated() }
  @ConfigItem var showOverdriveRange = true
    set(value){ field = value; He.entityInfo.displaySetupUpdated() }
  @ConfigItem var entityInfoHotKey = KeyCode.altLeft
  @ConfigItem var entityInfoScale = 1f
  @ConfigItem var entityInfoAlpha = 1f

  @ConfigItem var enableBetterPlacement = true
  @ConfigItem var useFixedHoveringInfoPane = true
  @ConfigItem var blockColumns = 5
    set(value){ field = value; He.placement.rebuildCategory() }

  @ConfigItem var enableBetterModsDialog = true

  fun load() {
    if (!configFile.exists()) {
      internalConfigFile.copyTo(configFile)
      Log.info("Configuration file is not exist, copying the default configuration")
      load(configFile)
    } else {
      if (!load(configFile)) {
        val backup = configBack
        configFile.copyTo(backup)
        internalConfigFile.copyTo(configFile)
        Log.info("default configuration file version updated, old config should be override(backup file for old file was created)")
        save()
      }
    }

    if (loadInfo) printConfig()
  }

  private fun printConfig() {
    val results = StringBuilder()

    configs.forEach { cfg ->
      results.append("  ")
          .append(cfg.name)
          .append(" = ")
          .append(
            cfg.get(this).let {
              when(it){
                is Array<*> -> it.contentToString()
                is ByteArray -> it.contentToString()
                is ShortArray -> it.contentToString()
                is IntArray -> it.contentToString()
                is LongArray -> it.contentToString()
                is FloatArray -> it.contentToString()
                is DoubleArray -> it.contentToString()
                is CharArray -> it.contentToString()
                is BooleanArray -> it.contentToString()
                else -> it.toString()
              }
            }
          )
          .append(";")
          .append(System.lineSeparator())
    }

    Log.info("Mod config loaded! The config data:[${System.lineSeparator()}$results]")
  }

  private fun load(file: Fi): Boolean {
    val sb = StringBuilder()
    file.reader().use { reader ->
      val part = CharArray(8192)
      var n: Int
      while (reader.read(part, 0, part.size).also { n = it } != -1) {
        sb.appendRange(part, 0, n)
      }
    }

    val config = Jval.read(file.reader())

    val old = config.get("configVersion").asInt() != configVersion

    configs.forEach { cfg ->
      if (!config.has(cfg.name)) return@forEach

      val temp = config.get(cfg.name).toString()
      cfg.set(this, warp(cfg.type, temp))
    }

    return !old
  }

  fun saveAsync() {
    exec.submit {
      synchronized(this, ::save)
    }
  }

  fun save() {
    try {
      save(configFile)
    } catch (e: IOException) {
      Log.err(e.toString())
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun save(file: Fi) {
    val tree = Jval.newObject()
    val map = tree.asObject()
    map.put("configVersion", Jval.valueOf(configVersion))

    configs.forEach { cfg ->
      val key = cfg.name
      val obj = cfg.get(this)
      when {
        obj == null -> when {
          CharSequence::class.java.isAssignableFrom(cfg.type) -> map.put(key, Jval.valueOf(""))
          cfg.type.isArray -> map.put(key, Jval.newArray())
          cfg.type.isEnum -> map.put(key, Jval.valueOf(firstEnum(cfg.type as Class<Enum<*>>).name))
          else -> throw RuntimeException("Unhandled null type")
        }
        else -> map.put(key, pack(obj))
      }
    }

    val stringBuilder = StringBuilder()

    val fileContext = file.reader().readText()
    var lastStart = 0
    commentMatcher.findAll(fileContext).forEach { res ->
      val end = res.range.first
      val subString = fileContext.substring(lastStart, end)

      handleText(subString, stringBuilder, map)

      stringBuilder.append(res.value)

      lastStart = res.range.last + 1
    }
    if (lastStart < fileContext.length) {
      val subString = fileContext.substring(lastStart)
      handleText(subString, stringBuilder, map)
    }

    file.writeString(stringBuilder.toString())
  }

  private fun handleText(
    subString: String,
    stringBuilder: StringBuilder,
    map: Jval.JsonMap,
  ) {
    var lastKeyEnd = 0
    keyMatcher.findAll(subString).forEach pair@{ key ->
      val keyName = key.groups[1]!!

      val perpend = subString.substring(lastKeyEnd, key.range.first)
      stringBuilder.append(perpend)

      stringBuilder.append(key.value)
      stringBuilder.append(map.get(keyName.value))

      lastKeyEnd = key.range.last + 1
    }
    if (lastKeyEnd < subString.length) {
      stringBuilder.append(
        subString
          .substring(lastKeyEnd)
          .replace(jsonValueMatcher, "")
          .replace(jsonArrayMatcher, "")
          .replace(jsonObjectMatcher, "")
      )
    }
  }

  private fun pack(value: Any): Jval {
    return when (value) {
      is Int -> Jval.valueOf(value)
      is Byte -> Jval.valueOf(value.toInt())
      is Short -> Jval.valueOf(value.toInt())
      is Boolean -> Jval.valueOf(value)
      is Long -> Jval.valueOf(value)
      is Char -> Jval.valueOf(value.code)
      is Float -> Jval.valueOf(value)
      is Double -> Jval.valueOf(value)
      is CharSequence -> Jval.valueOf(value.toString())
      else -> when {
        value.javaClass.isArray -> packArray(value)
        value.javaClass.isEnum -> Jval.valueOf((value as Enum<*>).name)
        else -> throw RuntimeException("invalid type: ${value.javaClass}")
      }
    }
  }

  private fun packArray(array: Any): Jval {
    if (!array.javaClass.isArray) throw RuntimeException("given object was not an array")

    val len = RArray.getLength(array)
    val res = Jval.newArray()
    val arr = res.asArray()
    (0 until len).forEach { i ->
        arr.add(pack(RArray.get(array, i)))
    }
    return res
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> warp(type: Class<T>, value: String): T {
    return when (type) {
      Int::class.java -> value.toInt() as T
      Byte::class.java -> value.toByte() as T
      Short::class.java -> value.toShort() as T
      Boolean::class.java -> value.toBoolean() as T
      Long::class.java -> value.toLong() as T
      Char::class.java -> value[0] as T
      Float::class.java -> value.toFloat() as T
      Double::class.java -> value.toDouble() as T
      else -> when {
        CharSequence::class.java.isAssignableFrom(type) -> value as T
        type.isArray -> toArray(type, value)
        type.isEnum -> (type as Class<Enum<*>>).enumConstants.find { it.name == value } as T
        else -> throw RuntimeException("invalid type: $type")
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> toArray(type: Class<T>, value: String): T {
    if (!type.isArray) throw RuntimeException("class $type was not an array")
    val a = Jval.read(value).asArray()
    val eleType = type.componentType
    val res = RArray.newInstance(eleType, a.size)
    (0 until a.size).forEach { i ->
        RArray.set(res, i, warp(eleType, a[i].toString()))
    }
    return res as T
  }

  private fun <T: Enum<*>> firstEnum(type: Class<T>): T {
    if (!type.isEnum) throw RuntimeException("class $type was not an enum")

    return type.enumConstants[0]
  }

  fun reset() {
    configFile.copyTo(configBack)
    configFile.delete()
    Log.info("[INFO][${He.MOD_NAME}] mod config has been reset, old config file saved to file named \"mod_config.hjson.bak\"")
    load()
  }
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class ConfigItem
