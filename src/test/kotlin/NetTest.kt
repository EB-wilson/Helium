import arc.util.Http

fun main() {
  val req = Http.get("https://github.com")

  req.error {
    println(it.stackTraceToString())
  }
  req.block {
    println(it.headers.toString())
  }
}