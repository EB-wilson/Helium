package helium

import arc.func.Cons
import arc.func.ConsT
import arc.util.Http
import arc.util.Log
import arc.util.serialization.Jval

object GithubAPI {
  const val GITHUB_API = "https://api.github.com"
  private const val USER_AUTH_API = "$GITHUB_API/user"
  private const val STAR_API = "$GITHUB_API/user/starred"

  private var githubUser: GithubUser? = null

  fun init(){
    val token = "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"//He.global.getString("github-token")

    if (token.isNullOrBlank()) return

    loginUser(token){
      Log.warn("a github token has been set, but login failed, the token may have expired. error: ${it.message}")
    }
  }

  fun currUser() = githubUser
  fun usable() = githubUser != null
  fun loginUser(token: String, errorHandler: Cons<Throwable> = Cons{}) {
    val auth = Http.request(Http.HttpMethod.GET, USER_AUTH_API)
      .setupAuthHead(token)

    auth.error(errorHandler)
    auth.submit {
      val res = Jval.read(it.resultAsString)
      val url = res["url"].asString()
      val username = res["name"].asString()
      val avatarUrl = res["avatar_url"].asString()

      He.global.put("github-token", username)

      githubUser = GithubUser(
        token = token,
        url = url,
        username = username,
        avatarUrl = avatarUrl
      )
    }
  }

  fun star(repo: String, errorHandler: Cons<Throwable> = Cons{}, success: ConsT<Http.HttpResponse, Exception>) {
    val url = "$STAR_API/$repo"

    authRequest(Http.HttpMethod.PUT, url)
      .error(errorHandler)
      .submit(success)
  }

  fun unstar(repo: String, errorHandler: Cons<Throwable> = Cons{}, success: ConsT<Http.HttpResponse, Exception>) {
    val url = "$STAR_API/$repo"

    authRequest(Http.HttpMethod.DELETE, url)
      .error(errorHandler)
      .submit(success)
  }

  fun listStarred(errorHandler: Cons<Throwable> = Cons{}, result: Cons<Jval>) {
    authGET(STAR_API)
      .error(errorHandler)
      .submit{
        val res = Jval.read(it.resultAsString)
        result.get(res)
      }
  }

  fun authGET(url: String): Http.HttpRequest {
    return authRequest(Http.HttpMethod.GET, url)
  }

  fun authPOST(url: String): Http.HttpRequest {
    return authRequest(Http.HttpMethod.POST, url)
  }

  fun authRequest(method: Http.HttpMethod, url: String): Http.HttpRequest {
    if (githubUser == null) throw IllegalStateException("no auth token")

    val token = githubUser!!.token
    return Http.request(method, url).setupAuthHead(token)
  }

  private fun Http.HttpRequest.setupAuthHead(token: String): Http.HttpRequest {
    header("Accept", "application/vnd.github+json")
    header("Authorization", "Bearer $token")
    header("X-GitHub-Api-Version", "2022-11-28")

    return this
  }

  class GithubUser(
    internal val token: String,
    val url: String,
    val username: String,
    val avatarUrl: String,
  )
}