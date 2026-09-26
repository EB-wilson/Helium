// 由 src/main/kotlin/helium/ui/dialogs/mods/ModsDialogHelper.kt 的 tryCompareVersion 一族原样复制而来，
// 用于在无 Mindustry 运行时的环境下（IDE 里直接运行 main）验证版本号比较结果。
// 注意：改动 ModsDialogHelper.kt 后需要同步这里。

private object VersionCompare {
  /** 版本串里连续的数字、连续的字母各算一段。 */
  private val versionTokenMatcher = Regex("\\p{L}+|\\d+")

  /** 仅当 'v' 后面紧跟数字时才剥掉前缀，避免把 "version" 这类单词的前缀也削掉。 */
  private val versionPrefixMatcher = Regex("^[vV]+(?=\\d)")

  private const val VERSION_NUMERIC = -8  // 预发布段里的数字标识：比所有阶段词都早
  private const val VERSION_UNKNOWN = -2  // 认不出的单词：按预发布处理
  private const val VERSION_RELEASE = 0   // 正式版；主体里的数字段也归到这一档
  private const val VERSION_POST = 1      // 正式版之后的修补：hotfix、build 等

  /** 阶段词 → 先后等级，等级越小越早。 */
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

  /**
   * 比较两个版本号：[aVer] 比 [bVer] 旧返回负数，相同返回 0，更新返回正数。
   *
   * 对任意输入都满足自反性与反对称性；解析不出任何内容的版本串等价于版本 0。
   */
  fun tryCompareVersion(aVer: String, bVer: String): Int {
    val (aCore, aTag, aBuild) = splitVersion(aVer)
    val (bCore, bTag, bBuild) = splitVersion(bVer)

    val core = compareVersionTokens(aCore, bCore)
    if (core != 0) return core

    val tag = compareVersionTags(aTag, bTag)
    if (tag != 0) return tag

    return compareBuildTokens(aBuild, bBuild)
  }

  /** 把版本串拆成「主体」「预发布段」「构建元数据」三段。 */
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

  /** 主体比较：缺省的段一律按数字 0 补齐，所以 "1.0" == "1.0.0" 而 "1.0" < "1.0.1"。 */
  private fun compareVersionTokens(a: List<String>, b: List<String>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
      val res = compareCoreToken(a.getOrNull(i) ?: "0", b.getOrNull(i) ?: "0")
      if (res != 0) return res
    }

    return 0
  }

  /** 主体段比较：数字段与正式版同级，字母段按阶段等级；同级时数字段在前。 */
  private fun compareCoreToken(pa: String, pb: String): Int {
    val aNumber = pa[0].isDigit()
    val bNumber = pb[0].isDigit()

    if (aNumber && bNumber) return compareNumeric(pa, pb)

    val la = if (aNumber) VERSION_RELEASE else versionLevel(pa)
    val lb = if (bNumber) VERSION_RELEASE else versionLevel(pb)
    if (la != lb) return la.compareTo(lb)

    return if (aNumber != bNumber) (if (aNumber) -1 else 1) else pa.compareTo(pb)
  }

  /**
   * 预发布段比较（语义化版本 §11）。一方没有预发布段时，等价于另一方以「正式版」标记开头：
   * "1.0.0-1"、"1.0.0-rc.1" 早于 "1.0.0"，"1.0.0-release"、"1.0.0-ga" 与之相同，
   * "1.0.0-hotfix"、"1.0.0-build.3" 则更新。
   */
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

  /** 语义化版本的标识符比较：数字标识永远早于字母标识，字母标识先比阶段、再比字面。 */
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

  /** 不限位数的十进制比较：去掉前导零后先比长度，再逐位比较。 */
  private fun compareNumeric(a: String, b: String): Int {
    val x = a.trimStart('0')
    val y = b.trimStart('0')

    return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
  }

  /** 构建元数据只在主体与预发布段都相同时才参与比较：带元数据的一方视为更靠后。 */
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

private var checks = 0
private var failures = 0

private fun sign(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0

private fun check(a: String, b: String, expected: Int, note: String = "") {
  checks++
  val r = sign(VersionCompare.tryCompareVersion(a, b))
  if (r != expected) {
    failures++
    println("FAIL  cmp(\"$a\", \"$b\") = $r, 期望 $expected  ${if (note.isEmpty()) "" else "// $note"}")
  }
}

private fun eq(a: String, b: String, note: String = "") = check(a, b, 0, note)
private fun newer(a: String, b: String, note: String = "") = check(a, b, 1, note)
private fun older(a: String, b: String, note: String = "") = check(a, b, -1, note)

private val corpus = listOf(
  "", "0", "0.0.0", "1", "1.0", "1.0.0", "1.0.0.0", "1.0.1", "1.1", "2.0", "9.0", "10.0", "0.9.9",
  "146.1", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
  "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0-rc1", "1.0.0-rc.2", "1.0.0-rc9", "1.0.0-rc10", "1.0.0-cr1",
  "1.0.0-dev", "1.0.0-SNAPSHOT", "1.0.0-nightly.20240101", "1.0.0-fox", "1.0.0-pre-alpha",
  "1.0.0-pre", "1.0.0-preview2", "1.0.0-ea", "1.0.0-hotfix", "1.0.0-hotfix.2", "1.0.0-hf1",
  "1.0.0-sr1", "1.0.0-stable", "1.0.0-release", "1.0.0-ga", "1.0.0-final", "1.0.0-gold",
  "1.0.0-rel", "1.0.0+build.1", "1.0.0+build.2", "1.0.0+build", "1.0.0-build.1", "1.0.0-build.2",
  "1.0.0-build", "v1.0.0", "V1.0.0", "1.0.0-BETA-3", "1.0 BETA 3", "beta-1.7", "beta-1.8",
  "beta-1.10", "202401011200", "2024.06.01", "2024.6.1", "1.0.0.5", "1.0.0#5", "1.0.0_5", "1.0.0a",
  "1.0.0b", "1.0.0a1", "1.0.0 (build 3)", "1.0.0-测试", "0.1", "01.0", "1.00", "1.0.0-rc-1",
  "1.0.0.rc.1", "1.0.0-ALPHA", "1.0.0-1", "1.0.0-2", "1.0.0-0", "1.0.0-milestone.1", "1.0.0-m1",
  "6.0.1", "3.0.0", "1.0.0+", "v146.1", "146.1-beta", "!!", "-", "1.0.0.1-alpha", "1.0.0.1-alpha.2",
  "2.0_v154_1", "2.0_v154_2", "3.3.0.1-hotfix1", "3.3.0.1-hotfix2", "1.15.2", "1.15.10", "1.7.2", "1.7.10"
)

fun main() {
  println("== 语义化版本规范 §11 的示例链（两两检查）==")
  val chain = listOf(
    "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
    "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"
  )
  for (i in chain.indices) {
    for (j in chain.indices) {
      check(chain[i], chain[j], sign(i.compareTo(j)), "${chain[i]} vs ${chain[j]}")
    }
  }

  println("== 预发布 / 正式版 ==")
  older("1.0.0-alpha", "1.0.0")
  older("1.0.0-beta", "1.0.0")
  older("1.0.0-rc1", "1.0.0")
  older("1.0.0-dev", "1.0.0")
  older("1.0.0-SNAPSHOT", "1.0.0")
  older("1.0.0-fox", "1.0.0")
  older("1.0.0-测试", "1.0.0")
  older("1.0.0-0", "1.0.0")
  older("1.0.0-1", "1.0.0")
  newer("1.0.0", "1.0.0-1")
  older("1.0.0-alpha", "1.0.0-beta")
  older("1.0.0-dev", "1.0.0-alpha")
  older("1.0.0-pre", "1.0.0-alpha")
  older("1.0.0-alpha", "1.0.0-milestone.1")
  older("1.0.0-milestone.1", "1.0.0-rc1")
  older("1.0.0-rc1", "1.0.0-stable")
  eq("1.0.0-stable", "1.0.0")
  eq("1.0.0-release", "1.0.0")
  eq("1.0.0-ga", "1.0.0")
  eq("1.0.0-final", "1.0.0")
  newer("1.0.0-hotfix", "1.0.0")
  newer("1.0.0-sr1", "1.0.0")
  newer("1.0.0-hotfix", "1.0.0-rc1")
  newer("1.0.0-hotfix.2", "1.0.0-hotfix")
  older("1.0.0-build.1", "1.0.0-hotfix")
  older("1.0.0-0", "1.0.0-alpha")

  println("== 数字段 / 字母段 ==")
  older("1.0.0-1", "1.0.0-alpha")
  newer("1.0.0-alpha", "1.0.0-1")
  older("9.0", "10.0")
  newer("10.0", "9.0")
  older("1.0.0-rc9", "1.0.0-rc10")
  newer("1.0.0-rc10", "1.0.0-rc9")
  older("1.0.0-beta.2", "1.0.0-beta.11")
  older("1.0.0a", "1.0.0b")
  older("1.0.0a", "1.0.0")
  eq("1.0.0-alpha", "1.0.0-ALPHA")
  newer("1.0.0-rc.1", "1.0.0rc1")
  older("1.0.0rc1", "1.0.0")
  older("1.0.0_rc_1", "1.0.0")
  older("1.0.0_rc_1", "1.0.0-rc.1")
  newer("1.0.0-rc-1", "1.0.0.rc.1")
  older("1.0.0a", "1.0.0-a")
  older("1.0.0a1", "1.0.0-a.1")
  older("1.0.0-2", "1.0.0-hotfix")
  older("1.0.0-release.1", "1.0.0-hotfix")
  older("1.0.0-release", "1.0.0-release.1")

  println("== 位数 / 前导零 / 溢出 ==")
  older("1.0", "1.0.1")
  eq("1.0", "1.0.0")
  eq("1.0", "1.0.0.0")
  eq("1", "1.0")
  newer("1.0.0.1", "1.0.0")
  newer("1.0.0.1", "1.0")
  older("202401011199", "202401011200")
  newer("202401011200", "202401011199")
  eq("2024.6.1", "2024.06.01")
  eq("1.00", "1.0")
  eq("01.0", "1.0")
  older("1.0.202401011200", "1.0.202401011201")
  older("1.0.0", "1.0.0.5")

  println("== v 前缀 / 大小写 / 分隔符 ==")
  eq("v1.2.3", "1.2.3")
  eq("V1.2.3", "v1.2.3")
  eq("vv1.2.3", "1.2.3")
  eq("1.0.0_5", "1.0.0.5")
  eq("1.0.0#5", "1.0.0.5")
  eq("1.0.0", "  1.0.0  ")
  eq("beta-1.7", "BETA-1.7")
  newer("1.0.0 (build 3)", "1.0.0")
  newer("1.0.0 (build 3)", "1.0.0 (build 2)")
  older("1.0 BETA 3", "1.0-beta.3")
  older("1.0 BETA 3", "1.0")
  older("beta-1.7", "beta-1.8")
  older("beta-1.8", "beta-1.10")

  println("== 构建元数据（只在前面相同时决胜）==")
  eq("1.0.0+build.1", "1.0.0+build.1")
  newer("1.0.0+build.2", "1.0.0+build.1")
  newer("1.0.0+build.10", "1.0.0+build.9")
  newer("1.0.0+build.1", "1.0.0")
  older("1.0.0", "1.0.0+build")
  eq("1.0.0+", "1.0.0+")
  eq("1.0.0+", "1.0.0")
  newer("1.0.0-build.2", "1.0.0-build.1")
  newer("1.0.0-build.1", "1.0.0")
  older("1.0.0-alpha+build.9", "1.0.0")
  older("1.0.0-alpha+build.9", "1.0.0-beta+build.1")
  eq("1.0.0+20240101", "1.0.0+20240101")

  println("== 实际模组里出现过的写法 ==")
  older("1.7.2", "1.7.10")
  older("1.15.2", "1.15.10")
  newer("3.3.0.1-hotfix1", "3.3.0.1")
  newer("3.3.0.1-hotfix2", "3.3.0.1-hotfix1")
  newer("2.0_v154_2", "2.0_v154_1")
  older("2.0_v154_1", "2.0")
  older("1.2.2", "1.3")
  newer("beta-1.8", "beta-1.7")
  newer("1.0.0", "beta-1.7")
  older("1.0.0.1", "1.0.1")
  older("0.9.9", "1.0")

  println("== 退化的输入 ==")
  eq("", "")
  eq("!!", "-")
  eq("   ", "")
  older("", "1.0.0")
  newer("1.0.0", "")
  older("!!", "1.0.0")
  newer("1.0.0", "-")
  older("-", "1.0")

  println("== 群性质：自反 / 反对称（对 ${corpus.size} 个样本两两组合）==")
  var asym = 0
  for (a in corpus) {
    if (sign(VersionCompare.tryCompareVersion(a, a)) != 0) {
      failures++
      println("FAIL  自反性: cmp(\"$a\", \"$a\") != 0")
    }
    for (b in corpus) {
      checks++
      val ab = sign(VersionCompare.tryCompareVersion(a, b))
      val ba = sign(VersionCompare.tryCompareVersion(b, a))
      if (ab != -ba) {
        asym++
        if (asym <= 10) println("FAIL  反对称: cmp(\"$a\", \"$b\") = $ab 但 cmp(\"$b\", \"$a\") = $ba")
      }
    }
  }
  failures += asym
  println("反对称违例：$asym")

  println("== 传递性：用比较器排序，检查结果确实升序 ==")
  checks++
  val sorted = try {
    corpus.sortedWith { x, y -> VersionCompare.tryCompareVersion(x, y) }
  } catch (e: IllegalArgumentException) {
    failures++
    println("FAIL  排序时违反比较器契约: ${e.message}")
    corpus
  }

  var trans = 0
  for (i in sorted.indices) for (j in sorted.indices) {
    checks++
    val expected = sign(i.compareTo(j))
    val res = sign(VersionCompare.tryCompareVersion(sorted[i], sorted[j]))
    if (res != expected && res != 0) {
      trans++
      if (trans <= 10) println("FAIL  排序结果不是升序: \"${sorted[i]}\"($i) vs \"${sorted[j]}\"($j) = $res")
    }
  }
  failures += trans
  println("排序逆序违例：$trans")

  println()
  println("== 从旧到新的排序结果 ==")
  sorted.forEach { println("  ${if (it.isEmpty()) "(空)" else it}") }

  println()
  println("检查 $checks 次，失败 $failures 次")
  if (failures > 0) throw AssertionError("$failures 项检查失败")
}