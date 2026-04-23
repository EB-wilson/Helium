import arc.util.Strings
import java.lang.NumberFormatException

private fun tryCompareVersion(aVer: String, bVer: String): Int{
  val paragraphMatcher = "pre-alpha|alpha|beta|rc|ga|pre-release|release|stable|\\d+|\\w".toRegex()
  val testLevel = mapOf(
    "pre-alpha" to 0,
    "alpha" to 1,
    "beta" to 2,
    "rc" to 3,
    "ga" to 4,
    "pre-release" to 5,
    "blank" to 6,
    "release" to 7,
    "stable" to 8,
  )

  val aParagraph = paragraphMatcher.findAll(aVer.lowercase()).map {
    it.value
  }.toList().also { if(it.isEmpty()) return 1 }
  val bParagraph = paragraphMatcher.findAll(bVer.lowercase()).map {
    it.value
  }.toList().also { if(it.isEmpty()) return 1 }

  val maxSize = maxOf(aParagraph.size, bParagraph.size)

  0.until(maxSize).forEach { i ->
    val pa = if (i >= aParagraph.size) "blank" else aParagraph[i]
    val pb = if (i >= bParagraph.size) "blank" else bParagraph[i]

    if (testLevel.containsKey(pa)) {
      if (testLevel.containsKey(pb)) {
        val res = testLevel.getValue(pa) - testLevel.getValue(pb)
        if (res > 0) return 1
        else if (res < 0) return -1
      }
      else return 1
    }
    else try {
      val na = Strings.parseInt(pa)
      val nb = Strings.parseInt(pb)
      val res = na - nb
      if (res > 0) return 1
      else if (res < 0) return -1
    } catch (_: NumberFormatException) {
      val res = pa.compareTo(pb)
      if (res > 0) return 1
      else if (res < 0) return -1
    }
  }

  return 0
}

fun main() {
  println(tryCompareVersion("1.0.0", "1.0.0-alpha"))
  println(tryCompareVersion("1.0.0", "1.0.0-beta"))
  println(tryCompareVersion("1.0.0", "1.0.0"))
  println(tryCompareVersion("1.0.0-alpha", "1.0.0-beta"))
  println(tryCompareVersion("1.0.0-alpha", "1.0.0"))
}