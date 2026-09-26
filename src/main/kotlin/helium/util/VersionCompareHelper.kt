package helium.util

object VersionCompareHelper {
  private val versionTokenMatcher = Regex("\\p{L}+|\\d+")
  private val versionPrefixMatcher = Regex("^[vV]+(?=\\d)")

  private const val VERSION_NUMERIC = -8  // 预发布段里的数字标识：比所有阶段词都早
  private const val VERSION_UNKNOWN = -2  // 认不出的单词：按预发布处理
  private const val VERSION_RELEASE = 0   // 正式版；主体里的数字段也归到这一档
  private const val VERSION_POST = 1      // 正式版之后的修补：hotfix、build 等

  private val versionStages = mapOf(
    "snapshot" to -7, "nightly" to -7, "canary" to -7, "dev" to -7, "devel" to -7, "development" to -7,
    "edge" to -7,
    "pre" to -6, "preview" to -6, "prerelease" to -6, "ea" to -6,
    "alpha" to -5, "a" to -5,
    "beta" to -4, "b" to -4, "milestone" to -4, "m" to -4,
    "rc" to -3, "cr" to -3,
    "release" to VERSION_RELEASE, "rel" to VERSION_RELEASE, "stable" to VERSION_RELEASE,
    "ga" to VERSION_RELEASE, "final" to VERSION_RELEASE, "gold" to VERSION_RELEASE,
    "hotfix" to VERSION_POST, "hf" to VERSION_POST, "sr" to VERSION_POST, "sp" to VERSION_POST,
    "build" to VERSION_POST, "bld" to VERSION_POST
  )

  fun tryCompareVersion(aVer: String, bVer: String): Int {
    val (aCore, aTag, aBuild) = splitVersion(aVer)
    val (bCore, bTag, bBuild) = splitVersion(bVer)

    val core = compareVersionTokens(aCore, bCore)
    if (core != 0) return core

    val tag = compareVersionTags(aTag, bTag)
    if (tag != 0) return tag

    return compareBuildTokens(aBuild, bBuild)
  }

  private fun splitVersion(version: String): Triple<List<String>, List<String>, List<String>> {
    val text = version.trim().replace(versionPrefixMatcher, "").lowercase()

    val plus = text.indexOf('+')
    val main = if (plus < 0) text else text.substring(0, plus)
    val build = if (plus < 0) "" else text.substring(plus + 1)

    val dash = main.indexOf('-')
    val core = if (dash < 0) main else main.substring(0, dash)
    val tag = if (dash < 0) "" else main.substring(dash + 1)

    return Triple(tokenizeVersion(core), tokenizeVersion(tag), tokenizeVersion(build))
  }

  private fun tokenizeVersion(text: String): List<String> =
    versionTokenMatcher.findAll(text).map { it.value }.toList()

  private fun compareVersionTokens(a: List<String>, b: List<String>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
      val res = compareCoreToken(a.getOrNull(i) ?: "0", b.getOrNull(i) ?: "0")
      if (res != 0) return res
    }

    return 0
  }

  private fun compareCoreToken(pa: String, pb: String): Int {
    val aNumber = pa[0].isDigit()
    val bNumber = pb[0].isDigit()

    if (aNumber && bNumber) return compareNumeric(pa, pb)

    val la = if (aNumber) VERSION_RELEASE else versionLevel(pa)
    val lb = if (bNumber) VERSION_RELEASE else versionLevel(pb)
    if (la != lb) return la.compareTo(lb)

    return if (aNumber != bNumber) (if (aNumber) -1 else 1) else pa.compareTo(pb)
  }

  private fun compareVersionTags(a: List<String>, b: List<String>): Int {
    if (a.isEmpty() || b.isEmpty()) {
      if (a.isEmpty() && b.isEmpty()) return 0

      val present = if (a.isEmpty()) b else a
      val first = present[0]
      val level = if (first[0].isDigit()) VERSION_NUMERIC else versionLevel(first)

      val res = if (level != VERSION_RELEASE) level.compareTo(VERSION_RELEASE)
      else if (present.size > 1) 1   // "1.0.0-release.1" 比 "1.0.0-release" 靠后
      else 0

      return if (a.isEmpty()) -res else res
    }

    for (i in 0 until maxOf(a.size, b.size)) {
      val pa = a.getOrNull(i) ?: return -1   // 前缀相同时，预发布段更少的一方更早
      val pb = b.getOrNull(i) ?: return 1

      val res = compareSemverToken(pa, pb)
      if (res != 0) return res
    }

    return 0
  }

  private fun compareSemverToken(pa: String, pb: String): Int {
    val aNumber = pa[0].isDigit()
    val bNumber = pb[0].isDigit()

    return when {
      aNumber && bNumber -> compareNumeric(pa, pb)
      aNumber -> -1
      bNumber -> 1
      else -> {
        val la = versionLevel(pa)
        val lb = versionLevel(pb)
        if (la != lb) la.compareTo(lb)
        else if (la == VERSION_RELEASE) 0   // release / stable / ga / final 是同一个意思
        else pa.compareTo(pb)
      }
    }
  }

  private fun versionLevel(token: String): Int = versionStages[token] ?: VERSION_UNKNOWN

  private fun compareNumeric(a: String, b: String): Int {
    val x = a.trimStart('0')
    val y = b.trimStart('0')

    return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
  }

  private fun compareBuildTokens(a: List<String>, b: List<String>): Int {
    if (a.isEmpty() || b.isEmpty()) return a.size.compareTo(b.size)

    for (i in 0 until maxOf(a.size, b.size)) {
      val pa = a.getOrNull(i) ?: return -1   // 前缀相同时，段更少的一方更早
      val pb = b.getOrNull(i) ?: return 1

      val res = compareSemverToken(pa, pb)
      if (res != 0) return res
    }

    return 0
  }
}