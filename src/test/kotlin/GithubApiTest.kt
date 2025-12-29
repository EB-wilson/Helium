import arc.util.Http
import arc.util.serialization.Jval
import arc.util.serialization.Jval.Jformat

val githubAPI = "https://api.github.com"
val star = "$githubAPI/user/starred"
val repo = "Yuria-Shikibe/NewHorizonMod"

val dostar = "$star/$repo"

fun main() {
  val request = Http.request(Http.HttpMethod.DELETE, dostar)
  request.header("Accept", "application/vnd.github+json")
  request.header("Authorization", "Bearer ")
  request.header("X-GitHub-Api-Version", "2022-11-28")

  request.block {
    println(Jval.read(it.resultAsString).toString(Jformat.formatted))
  }
}
