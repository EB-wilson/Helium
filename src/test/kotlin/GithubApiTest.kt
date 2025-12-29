import arc.util.Http
import arc.util.serialization.Jval
import arc.util.serialization.Jval.Jformat

//val token = "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"
val token = "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"
val githubAPI = "https://api.github.com"
val star = "$githubAPI/user/starred"
val repo = "Yuria-Shikibe/NewHorizonMod"

val dostar = "$star/$repo"

fun main() {
  val request = Http.request(Http.HttpMethod.DELETE, dostar)
  request.header("Accept", "application/vnd.github+json")
  request.header("Authorization", "Bearer $token")
  request.header("X-GitHub-Api-Version", "2022-11-28")

  request.block {
    println(Jval.read(it.resultAsString).toString(Jformat.formatted))
  }
}
