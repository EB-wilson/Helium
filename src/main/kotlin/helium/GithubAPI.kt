package helium

import arc.Core
import arc.func.Cons
import arc.func.ConsT
import arc.util.Http
import arc.util.Log
import arc.util.Timer
import arc.util.serialization.Jval
import java.net.URLEncoder
import kotlin.math.max

/**
 * GitHub 账号与仓库星标（Star）接口。
 *
 * ### 登录方式：OAuth 2.0 Device Flow（设备码流程）
 * 用户点击登录后，本模块会：
 *  1. 向 GitHub 申请一次性设备码与用户码；
 *  2. 用 [Core.app.openURI] 唤起系统浏览器打开 GitHub 官方认证页（[DEVICE_VERIFY_URL]）；
 *  3. 把 [DeviceLogin.userCode] 交给界面展示，用户在网页上填入并授权；
 *  4. 轮询换取当前会话的 access token，再拉取用户资料，得到 [GithubUser]。
 *
 * 设备码流程不需要 client_secret，也不需要本地回调端口，适合桌面/移动端内嵌场景。
 *
 * ### 线程约定
 * - 登录、会话恢复、星标列表、单仓库星标查询、退出登录的回调（[Cons]）**均在游戏主线程执行**。
 * - 底层 [star]、[unstar]、[authGET]、[authPOST]、[authRequest] 保留原始行为，
 *   回调在 HTTP 线程执行；若要更新界面请自行 `Core.app.post { }`。
 */
object GithubAPI {
  const val GITHUB_API = "https://api.github.com"

  /** OAuth App 的设备码申请地址。 */
  const val DEVICE_CODE_API = "https://github.com/login/device/code"
  /** OAuth App 的设备码换取 token 地址。 */
  const val ACCESS_TOKEN_API = "https://github.com/login/oauth/access_token"
  /** 需要唤起浏览器打开的 GitHub 官方认证页，用户在此填入用户码并登录授权。 */
  const val DEVICE_VERIFY_URL = "https://github.com/login/device"

  /** mod 仓库在 GitHub 上约定的 topic，用于从用户的星标里筛出「mod 收藏」。 */
  const val MOD_TOPIC = "mindustry-mod"

  /** 需要的权限：读取账号资料 + 对公开仓库标星/取消标星。 */
  const val SCOPE = "read:user public_repo"

  /**
   * OAuth App 的 Client ID（注意不是 client secret，设备码流程不需要 secret）。
   *
   * 申请步骤：
   *  1. 打开 <https://github.com/settings/applications/new>；
   *  2. `Application name` / `Homepage URL` 随意填写，`Authorization callback URL` 也可随意填一个 https 地址
   *     （设备码流程不会使用回调地址）；
   *  3. 创建后进入该 App 详情页，勾选 **Enable Device Flow** 并保存；
   *  4. 把页面上的 Client ID 填到这里，或在游戏内写入全局配置项 `github-client-id`。
   */
  const val DEFAULT_CLIENT_ID = ""

  private const val CLIENT_ID_KEY = "github-client-id"
  private const val TOKEN_KEY = "github-token"
  private const val PAGE_SIZE = 100
  private const val USER_AGENT = "Helium-Mindustry-Mod"
  private const val API_VERSION = "2022-11-28"
  private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

  private const val USER_AUTH_API = "$GITHUB_API/user"
  private const val STAR_API = "$GITHUB_API/user/starred"

  /** 登录状态机，供界面判断当前应展示登录按钮还是账号信息。 */
  enum class LoginState {
    /** 未登录 */
    LoggedOut,
    /** 已唤起浏览器，等待用户在网页上完成授权 */
    Waiting,
    /** 已登录，[currUser] 可用 */
    LoggedIn,
    /** 上一次登录失败，可重新调用 [startLogin] */
    Failed,
  }

  @Volatile
  private var githubUser: GithubUser? = null

  @Volatile
  private var loginState: LoginState = LoginState.LoggedOut

  @Volatile
  private var device: DeviceLogin? = null

  @Volatile
  private var pollTask: Timer.Task? = null

  private var pollInterval = 5
  private var deviceExpireAt = 0L

  // ---------------------------------------------------------------------------------------------
  // 状态查询（供界面/收藏夹实现读取）
  // ---------------------------------------------------------------------------------------------

  /** @return 当前登录用户，未登录时为 null */
  fun currUser(): GithubUser? = githubUser

  /** @return 当前会话是否可用于需要鉴权的 GitHub 接口 */
  fun usable(): Boolean = githubUser != null

  /** @return 当前登录状态 */
  fun state(): LoginState = loginState

  /** @return 正在等待授权的设备码信息；界面可据此展示用户码与认证链接 */
  fun pendingDevice(): DeviceLogin? = device

  /** @return 本地保存的 access token，未保存时为空串 */
  fun savedToken(): String = He.global.getString(TOKEN_KEY, "") ?: ""

  /** @return 实际使用的 OAuth App Client ID（全局配置优先，其次 [DEFAULT_CLIENT_ID]） */
  fun clientId(): String = (He.global.getString(CLIENT_ID_KEY, "") ?: "").ifBlank { DEFAULT_CLIENT_ID }

  /** @return 是否已配置 Client ID；未配置时 [startLogin] 会直接失败 */
  fun hasClientId(): Boolean = clientId().isNotBlank()

  // ---------------------------------------------------------------------------------------------
  // 登录 / 会话
  // ---------------------------------------------------------------------------------------------

  /**
   * 游戏启动时调用：若本地存有 token 则静默恢复登录态。
   * @param onRestored 主线程回调，参数为是否恢复成功
   */
  fun init(onRestored: Cons<Boolean> = Cons{}) = restoreSession(onRestored)

  /**
   * 恢复上次保存的会话：用本地 token 拉取用户资料校验。
   * token 缺失或已失效时回调 false，并清空本地 token。
   * @param onRestored 主线程回调
   */
  fun restoreSession(onRestored: Cons<Boolean> = Cons{}) {
    val token = savedToken()
    if (token.isBlank()) {
      loginState = LoginState.LoggedOut
      onMain { onRestored.get(false) }
      return
    }

    fetchUser(
      token = token,
      onError = { error ->
        Log.warn("a github token has been set, but login failed, the token may have expired. error: ${error.message}")
        logout()
        onRestored.get(false)
      },
      onSuccess = { user ->
        githubUser = user
        loginState = LoginState.LoggedIn
        Log.info("github session restored as @", user.login)
        onRestored.get(true)
      }
    )
  }

  /**
   * 开始 GitHub 设备码登录：唤起浏览器 + 等待用户授权 + 换取 token。
   *
   * @param openBrowser 是否自动唤起系统浏览器打开认证页
   * @param onCode 主线程回调，拿到用户码后立即触发，界面应提示用户填入 [DeviceLogin.userCode]
   * @param onSuccess 主线程回调，登录成功并已取得用户资料
   * @param onError 主线程回调，登录失败（未配置 Client ID、用户拒绝、设备码超时、网络错误等）
   */
  fun startLogin(
    openBrowser: Boolean = true,
    onCode: Cons<DeviceLogin> = Cons{},
    onSuccess: Cons<GithubUser> = Cons{},
    onError: Cons<Throwable> = Cons{},
  ) {
    val clientId = clientId()
    if (clientId.isBlank()) {
      loginState = LoginState.Failed
      onMain {
        onError.get(
          IllegalStateException(
            "no GitHub OAuth client id configured; create an OAuth App with \"Enable Device Flow\" " +
            "and put its client id into GithubAPI.DEFAULT_CLIENT_ID or the \"$CLIENT_ID_KEY\" global setting"
          )
        )
      }
      return
    }

    cancelLogin()
    loginState = LoginState.Waiting

    Http.request(Http.HttpMethod.POST, DEVICE_CODE_API)
      .header("Accept", "application/json")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("User-Agent", USER_AGENT)
      .content("client_id=${form(clientId)}&scope=${form(SCOPE)}")
      .error { error ->
        val json = error.asGithubJson()
        if (json == null) fail(error, onError)
        else handleDeviceCode(json, openBrowser, onCode, onSuccess, onError)
      }
      .submit { response ->
        handleDeviceCode(Jval.read(response.resultAsString), openBrowser, onCode, onSuccess, onError)
      }
  }

  /** 取消正在进行的登录轮询（用户关闭授权界面时调用）。已登录状态不受影响。 */
  fun cancelLogin() {
    stopPolling()
    device = null
    if (loginState == LoginState.Waiting) loginState = LoginState.LoggedOut
  }

  /** 退出登录，并清除本地保存的 token。 */
  fun logout() {
    cancelLogin()
    githubUser = null
    loginState = LoginState.LoggedOut

    He.global.remove(TOKEN_KEY)
    He.global.forceSave()
  }

  private fun handleDeviceCode(
    json: Jval,
    openBrowser: Boolean,
    onCode: Cons<DeviceLogin>,
    onSuccess: Cons<GithubUser>,
    onError: Cons<Throwable>,
  ) {
    val error = json.getString("error") ?: ""
    if (error.isNotBlank()) {
      fail(IllegalStateException("github device code request failed: ${describe(json)}"), onError)
      return
    }

    val deviceCode = json.getString("device_code") ?: ""
    val userCode = json.getString("user_code") ?: ""
    if (deviceCode.isBlank() || userCode.isBlank()) {
      fail(IllegalStateException("github device code response was incomplete: ${describe(json)}"), onError)
      return
    }

    val info = DeviceLogin(
      userCode = userCode,
      verificationUri = json.getString("verification_uri") ?: DEVICE_VERIFY_URL,
      expiresIn = json.getInt("expires_in", 900),
      interval = json.getInt("interval", 5),
      deviceCode = deviceCode,
    )

    device = info
    deviceExpireAt = System.currentTimeMillis() + info.expiresIn * 1000L
    pollInterval = max(1, info.interval)

    if (openBrowser) onMain { Core.app.openURI(info.verificationUri) }
    onMain { onCode.get(info) }

    startPolling(onSuccess, onError)
  }

  private fun startPolling(onSuccess: Cons<GithubUser>, onError: Cons<Throwable>) {
    stopPolling()

    pollTask = Timer.schedule(
      Runnable { pollToken(onSuccess, onError) },
      pollInterval.toFloat(),
      pollInterval.toFloat()
    )
  }

  private fun stopPolling() {
    pollTask?.cancel()
    pollTask = null
  }

  private fun pollToken(onSuccess: Cons<GithubUser>, onError: Cons<Throwable>) {
    if (device == null) {
      stopPolling()
      return
    }

    if (System.currentTimeMillis() > deviceExpireAt) {
      stopPolling()
      device = null
      fail(IllegalStateException("github device code expired before authorization"), onError)
      return
    }

    val info = device ?: return
    val body = "client_id=${form(clientId())}" +
      "&device_code=${form(info.deviceCode)}" +
      "&grant_type=${form(DEVICE_GRANT)}"

    Http.request(Http.HttpMethod.POST, ACCESS_TOKEN_API)
      .header("Accept", "application/json")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("User-Agent", USER_AGENT)
      .content(body)
      .error { error ->
        val json = error.asGithubJson()
        if (json == null) {
          stopPolling()
          fail(error, onError)
        }
        else handleToken(json, onSuccess, onError)
      }
      .submit { response ->
        handleToken(Jval.read(response.resultAsString), onSuccess, onError)
      }
  }

  private fun handleToken(json: Jval, onSuccess: Cons<GithubUser>, onError: Cons<Throwable>) {
    val token = json.getString("access_token") ?: ""
    if (token.isNotBlank()) {
      stopPolling()
      device = null
      completeLogin(token, onSuccess, onError)
      return
    }

    when (json.getString("error") ?: "") {
      // 用户还没在网页上完成授权，继续轮询
      "authorization_pending" -> Unit

      // 轮询过快，按 GitHub 要求放慢频率后继续
      "slow_down" -> {
        pollInterval += 5
        startPolling(onSuccess, onError)
      }

      "expired_token" -> {
        stopPolling()
        device = null
        fail(IllegalStateException("github device code expired before authorization"), onError)
      }

      "access_denied" -> {
        stopPolling()
        device = null
        fail(IllegalStateException("github authorization was denied by the user"), onError)
      }

      else -> {
        stopPolling()
        device = null
        fail(IllegalStateException("github login failed: ${describe(json)}"), onError)
      }
    }
  }

  private fun completeLogin(token: String, onSuccess: Cons<GithubUser>, onError: Cons<Throwable>) {
    fetchUser(
      token = token,
      onError = { error ->
        loginState = LoginState.Failed
        onError.get(error)
      },
      onSuccess = { user ->
        githubUser = user
        loginState = LoginState.LoggedIn

        // 回调在主线程，写配置与持久化都安全
        He.global.put(TOKEN_KEY, token)
        He.global.forceSave()

        Log.info("github login succeeded as @", user.login)
        onSuccess.get(user)
      }
    )
  }

  /** 用 token 拉取用户资料；回调在主线程执行。 */
  private fun fetchUser(token: String, onError: Cons<Throwable>, onSuccess: Cons<GithubUser>) {
    authRequest(Http.HttpMethod.GET, USER_AUTH_API, token)
      .error { error -> onMain { onError.get(error) } }
      .submit { response ->
        try {
          val res = Jval.read(response.resultAsString)

          val user = GithubUser(
            token = token,
            login = res.getString("login") ?: "",
            name = res.getString("name") ?: (res.getString("login") ?: ""),
            url = res.getString("html_url") ?: (res.getString("url") ?: ""),
            avatarUrl = res.getString("avatar_url") ?: "",
          )

          onMain { onSuccess.get(user) }
        } catch (e: Exception) {
          onMain { onError.get(e) }
        }
      }
  }

  // ---------------------------------------------------------------------------------------------
  // 星标（收藏夹数据来源）
  // ---------------------------------------------------------------------------------------------

  /**
   * 为当前登录用户标星某个仓库，仓库名为 `owner/repo`。
   * 注：回调在 HTTP 线程执行，更新界面请自行 `Core.app.post { }`。
   */
  fun star(repo: String, errorHandler: Cons<Throwable> = Cons{}, success: ConsT<Http.HttpResponse, Exception>) {
    authRequest(Http.HttpMethod.PUT, "$STAR_API/$repo")
      // PUT 无请求体，显式置空以保证发送 Content-Length: 0
      .content("")
      .error(errorHandler)
      .submit(success)
  }

  /**
   * 取消当前登录用户对某个仓库的标星，仓库名为 `owner/repo`。
   * 注：回调在 HTTP 线程执行。
   */
  fun unstar(repo: String, errorHandler: Cons<Throwable> = Cons{}, success: ConsT<Http.HttpResponse, Exception>) {
    authRequest(Http.HttpMethod.DELETE, "$STAR_API/$repo")
      .error(errorHandler)
      .submit(success)
  }

  /**
   * 拉取当前登录用户的**全部**星标仓库（自动翻页），结果为一个 JSON 数组。
   * 回调在主线程执行。
   */
  fun listStarred(errorHandler: Cons<Throwable> = Cons{}, result: Cons<Jval>) {
    if (!usable()) {
      onMain { errorHandler.get(IllegalStateException("no auth token")) }
      return
    }

    val acc = Jval.newArray()
    fetchStarredPage(1, acc, errorHandler) {
      onMain { result.get(acc) }
    }
  }

  /**
   * 拉取星标仓库中符合 mod 约定的仓库名（`owner/repo`，已统一小写）。
   * 默认判定规则：仓库 topics 含 [MOD_TOPIC]；需要别的规则请自行遍历 [listStarred] 的结果。
   * 回调在主线程执行。
   */
  fun listStarredRepos(errorHandler: Cons<Throwable> = Cons{}, result: Cons<List<String>>) {
    listStarred(errorHandler) { json ->
      val repos = ArrayList<String>()

      json.asArray().forEach { raw ->
        if (isModRepo(raw)) {
          val full = raw.getString("full_name") ?: ""
          if (full.isNotBlank()) repos.add(full.lowercase())
        }
      }

      result.get(repos)
    }
  }

  /** @return 某个星标仓库是否应被视为 mod 收藏；默认要求 topics 含 [MOD_TOPIC] */
  fun isModRepo(raw: Jval): Boolean {
    val topics = raw.get("topics") ?: return false
    if (!topics.isArray()) return false

    return topics.asArray().any { it.asString() == MOD_TOPIC }
  }

  /**
   * 查询某个仓库是否已被当前用户标星。
   * 204 表示已标星，404 表示未标星。回调在主线程执行。
   */
  fun isStarred(repo: String, errorHandler: Cons<Throwable> = Cons{}, result: Cons<Boolean>) {
    if (!usable()) {
      onMain { errorHandler.get(IllegalStateException("no auth token")) }
      return
    }

    authRequest(Http.HttpMethod.GET, "$STAR_API/$repo")
      .error { error ->
        if ((error as? Http.HttpStatusException)?.status == Http.HttpStatus.NOT_FOUND) {
          onMain { result.get(false) }
        }
        else {
          onMain { errorHandler.get(error) }
        }
      }
      .submit { onMain { result.get(true) } }
  }

  private fun fetchStarredPage(
    page: Int,
    acc: Jval,
    errorHandler: Cons<Throwable>,
    onDone: () -> Unit,
  ) {
    authGET("$STAR_API?per_page=$PAGE_SIZE&page=$page")
      .error { error -> onMain { errorHandler.get(error) } }
      .submit { response ->
        try {
          val arr = Jval.read(response.resultAsString).asArray()
          arr.forEach { acc.asArray().add(it) }

          if (arr.size >= PAGE_SIZE) {
            fetchStarredPage(page + 1, acc, errorHandler, onDone)
          }
          else {
            onDone()
          }
        } catch (e: Exception) {
          onMain { errorHandler.get(e) }
        }
      }
  }

  // ---------------------------------------------------------------------------------------------
  // 底层请求
  // ---------------------------------------------------------------------------------------------

  fun authGET(url: String): Http.HttpRequest {
    return authRequest(Http.HttpMethod.GET, url)
  }

  fun authPOST(url: String): Http.HttpRequest {
    return authRequest(Http.HttpMethod.POST, url)
  }

  /**
   * 构造带鉴权头的请求。未登录时抛 [IllegalStateException]。
   * 注：返回的请求回调在 HTTP 线程执行。
   */
  fun authRequest(method: Http.HttpMethod, url: String): Http.HttpRequest {
    if (githubUser == null) throw IllegalStateException("no auth token")

    return authRequest(method, url, githubUser!!.token)
  }

  private fun authRequest(method: Http.HttpMethod, url: String, token: String): Http.HttpRequest {
    return Http.request(method, url).setupAuthHead(token)
  }

  private fun Http.HttpRequest.setupAuthHead(token: String): Http.HttpRequest {
    header("Accept", "application/vnd.github+json")
    header("Authorization", "Bearer $token")
    header("X-GitHub-Api-Version", API_VERSION)
    header("User-Agent", USER_AGENT)

    return this
  }

  // ---------------------------------------------------------------------------------------------
  // 工具
  // ---------------------------------------------------------------------------------------------

  /** 在游戏主线程执行；应用未就绪时直接执行。 */
  private fun onMain(action: () -> Unit) {
    val app = Core.app
    if (app == null) action() else app.post(action)
  }

  private fun fail(error: Throwable, onError: Cons<Throwable>) {
    loginState = LoginState.Failed
    Log.err(error)
    onMain { onError.get(error) }
  }

  /** GitHub 的错误响应也会带 JSON body（如 invalid_client），尽量取出其中的 JSON。 */
  private fun Throwable.asGithubJson(): Jval? {
    val response = (this as? Http.HttpStatusException)?.response ?: return null

    return try {
      val body = response.resultAsString
      if (body.isBlank()) null else Jval.read(body)
    } catch (e: Throwable) {
      null
    }
  }

  private fun describe(json: Jval): String {
    val description = json.getString("error_description") ?: ""
    if (description.isNotBlank()) return description

    val error = json.getString("error") ?: ""
    return if (error.isNotBlank()) error else json.toString()
  }

  private fun form(value: String): String = URLEncoder.encode(value, "UTF-8")

  /** 设备码登录信息，界面据此提示用户去网页填入用户码。 */
  class DeviceLogin(
    /** 展示给用户的用户码（形如 `WDJB-MJHT`），用户在认证页填入它 */
    val userCode: String,
    /** 需要打开的 GitHub 官方认证页 */
    val verificationUri: String,
    /** 设备码有效期（秒） */
    val expiresIn: Int,
    /** GitHub 建议的轮询间隔（秒） */
    val interval: Int,
    internal val deviceCode: String,
  )

  /** 已登录的 GitHub 账号信息。 */
  class GithubUser(
    internal val token: String,
    /** 账号登录名（handle） */
    val login: String,
    /** 账号昵称（可能为空，回退为 [login]） */
    val name: String,
    /** 账号主页地址 */
    val url: String,
    /** 头像地址 */
    val avatarUrl: String,
  ) {
    /** 兼容旧字段名：即 [login] */
    val username: String get() = login
  }
}
