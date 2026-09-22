package helium

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.func.ConsT
import arc.util.Http
import arc.util.Log
import arc.util.Timer
import arc.util.serialization.Jval
import helium.util.SecureStore
import java.net.URLEncoder
import java.nio.ByteBuffer
import kotlin.math.max


object GithubAPI {
  private const val SESSION_RECORD_VERSION: Byte = 1
  private const val SESSION_RECORD_HEADER = 30
  private const val SESSION_IDLE_TIMEOUT_MS = 24L * 60 * 60 * 1000
  private const val SESSION_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
  private const val TOKEN_REFRESH_SKEW_MS = 5L * 60 * 1000
  private const val LAST_USED_WRITE_INTERVAL_MS = 60L * 1000

  const val GITHUB_API = "https://api.github.com"

  const val DEVICE_CODE_API = "https://github.com/login/device/code"
  const val ACCESS_TOKEN_API = "https://github.com/login/oauth/access_token"
  const val DEVICE_VERIFY_URL = "https://github.com/login/device"

  const val MOD_TOPIC = "mindustry-mod"

  /** classic OAuth App 默认请求的 scope。*/
  const val SCOPE = "read:user public_repo"

  const val DEFAULT_CLIENT_ID = "Ov23licXN5NAlcbFfaKI"

  private const val CLIENT_ID_KEY = "github-client-id"
  private const val SCOPE_KEY = "github-scope"

  private const val PAGE_SIZE = 100
  private const val USER_AGENT = "Helium-Mindustry-Mod"
  private const val API_VERSION = "2022-11-28"
  private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
  private const val REFRESH_GRANT = "refresh_token"

  private const val SESSION_SECRET_ID = "github.session"

  private const val USER_AUTH_API = "$GITHUB_API/user"
  private const val STAR_API = "$GITHUB_API/user/starred"

  enum class LoginState {
    LoggedOut,
    Waiting,
    LoggedIn,
    Failed,
  }

  private val refreshLock = Any()

  @Volatile
  private var githubUser: GithubUser? = null

  @Volatile
  private var refreshing = false

  private val refreshWaiters = ArrayList<Pair<Cons<Throwable>, () -> Unit>>()

  @Volatile
  private var loginState: LoginState = LoginState.LoggedOut

  @Volatile
  private var device: DeviceLogin? = null

  @Volatile
  private var pollTask: Timer.Task? = null

  private var pollInterval = 5
  private var deviceExpireAt = 0L

  /** @return 当前登录用户，未登录时为 null */
  fun currUser(): GithubUser? = githubUser

  /** @return 当前会话是否可用于需要鉴权的 GitHub 接口 */
  fun usable(): Boolean = githubUser != null

  /** @return 当前登录状态 */
  fun state(): LoginState = loginState

  /** @return 正在等待授权的设备码信息；界面可据此展示用户码与认证链接 */
  fun pendingDevice(): DeviceLogin? = device

  fun clientId(): String = He.global.getString(CLIENT_ID_KEY, DEFAULT_CLIENT_ID)

  /** @return 设备码请求里携带的 scope。*/
  fun scope(): String = He.global.getString(SCOPE_KEY, SCOPE)

  /** @return 是否已配置 Client ID；未配置时 [startLogin] 会直接失败 */
  fun hasClientId(): Boolean = clientId().isNotBlank()

  fun init(onRestored: Cons<Boolean> = Cons{}) {
    restoreSession(onRestored)
  }

  fun restoreSession(onRestored: Cons<Boolean> = Cons{}) {
    val stored = loadSession()
    if (stored == null) {
      loginState = LoginState.LoggedOut
      onMain { onRestored.get(false) }
      return
    }

    if (!stored.isWithinPolicy()) {
      Log.info("stored github session is out of policy (idle > 24h or older than 30d), a new login is required")
      stored.close()
      discardSession()
      onMain { onRestored.get(false) }
      return
    }

    if (stored.needsRefresh() && stored.refreshToken != null) {
      synchronized(refreshLock) { refreshing = true }

      refreshSession(
        record = stored,
        onError = { error ->
          // 凭据是否需要清除由 refreshSession 判定（只有被 GitHub 明确拒绝才清），
          // 这里只回落到未登录，避免网络抖动把用户登出
          Log.warn("stored github session could not be refreshed. error: ${error.message}")
          githubUser = null
          loginState = LoginState.LoggedOut
          onMain { onRestored.get(false) }
        }
      ) { verifyRestoredSession(onRestored) }

      stored.close()
      return
    }

    verifyRestoredSession(onRestored)
    stored.close()
  }

  private fun verifyRestoredSession(onRestored: Cons<Boolean>) {
    try {
      fetchUser(
        token = null, // 用存储中的令牌
        onError = { error ->
          if (error.isCredentialRejected()) {
            // 401/403：令牌确实过期或被撤销，这时才清掉凭据
            Log.warn("the stored github token was rejected, a new login is required. error: ${error.message}")
            logout()
          }
          else {
            // 网络/服务端故障：**保留**凭据，只回落到未登录，下次启动或手动刷新时重试
            Log.warn("could not verify the stored github session (network/server), keeping the credential. error: ${error.message}")
            githubUser = null
            loginState = LoginState.LoggedOut
          }

          onMain { onRestored.get(false) }
        },
        onSuccess = { user ->
          githubUser = user
          loginState = LoginState.LoggedIn
          markUsed()
          Log.info("github session restored as @", user.login)
          onMain { onRestored.get(true) }
        }
      )
    }
    catch (error: Throwable) {
      // 同步异常（存储读不出来、请求构造失败等）只回落到未登录，**不**清磁盘凭据：
      // 一次偶发异常不该把用户的登录吃掉。真正的失效（401/403、刷新令牌被拒）走上面的 onError。
      Log.err("failed to verify the stored github session, keeping the credential", error)
      githubUser = null
      loginState = LoginState.LoggedOut
      onMain { onRestored.get(false) }
    }
  }

  /**
   * @return 是否是"凭据被 GitHub 拒绝"（令牌过期/被撤销），而不是网络或服务端偶发故障。
   * 只有前者才应该清掉本地凭据——否则一次断网就会把用户登出。
   */
  private fun Throwable.isCredentialRejected(): Boolean {
    val status = (this as? Http.HttpStatusException)?.status ?: return false

    return status == Http.HttpStatus.UNAUTHORIZED || status == Http.HttpStatus.FORBIDDEN
  }

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
      .content("client_id=${form(clientId)}" + scope().takeIf { it.isNotBlank() }?.let { "&scope=${form(it)}" }.orEmpty())
      .error { error ->
        val json = error.asGithubJson()
        if (json == null) fail(error, onError)
        else handleDeviceCode(json, openBrowser, onCode, onSuccess, onError)
      }
      .submit { response ->
        handleDeviceCode(Jval.read(response.resultAsString), openBrowser, onCode, onSuccess, onError)
      }
  }

  fun cancelLogin() {
    stopPolling()
    device = null
    if (loginState == LoginState.Waiting) loginState = LoginState.LoggedOut
  }

  fun logout() {
    cancelLogin()
    githubUser = null
    discardSession()
    loginState = LoginState.LoggedOut
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

      val access = token.toByteArray(Charsets.UTF_8)
      val refresh = json.getString("refresh_token", "").takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
      val expiresIn = json.getInt("expires_in", 0)

      completeLogin(
        accessToken = access,
        refreshToken = refresh,
        accessExpiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L,
        onSuccess = onSuccess,
        onError = onError,
      )
      return
    }

    when (json.getString("error") ?: "") {
      "authorization_pending" -> Unit

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

  private fun completeLogin(
    accessToken: ByteArray,
    refreshToken: ByteArray?,
    accessExpiresAt: Long,
    onSuccess: Cons<GithubUser>,
    onError: Cons<Throwable>,
  ) {
    val now = System.currentTimeMillis()
    val record = SessionRecord(accessToken, refreshToken, accessExpiresAt, now, now)

    try {
      fetchUser(
        // 必须显式传入刚拿到的令牌：此时它还没落盘，走存储取会取不到（登录必然失败）
        token = record.accessToken,
        onError = { error ->
          record.close()
          loginState = LoginState.Failed
          onError.get(error)
        },
        onSuccess = { user ->
          persist(record)
          record.close()

          githubUser = user
          loginState = LoginState.LoggedIn

          Log.info("github login succeeded as @", user.login)
          onSuccess.get(user)
        }
      )
    }
    catch (error: Throwable) {
      // 本方法由设备码轮询的 HTTP 回调调用，异常路径也必须回主线程：
      // 调用方（登录面板）会在这个回调里 hide() 面板并重建列表，跨线程改场景图会崩在 Table 布局。
      record.close()
      loginState = LoginState.Failed
      onMain { onError.get(error) }
    }
  }

  /**
   * 拉取用户资料。
   *
   * @param token 要使用的令牌；传 null 表示用存储中的当前令牌（恢复会话时用）。
   *   登录流程必须传刚拿到的令牌——那时它还没写入存储。
   * 回调在主线程执行。
   */
  private fun fetchUser(token: ByteArray?, onError: Cons<Throwable>, onSuccess: Cons<GithubUser>) {
    val request = if (token != null) {
      Http.request(Http.HttpMethod.GET, USER_AUTH_API).setupAuthHead(token)
    }
    else {
      authRequest(Http.HttpMethod.GET, USER_AUTH_API)
    }

    request
      .error { error -> onMain { onError.get(error) } }
      .submit { response ->
        try {
          val res = Jval.read(response.resultAsString)

          val user = GithubUser(
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

  /**
   * 为当前登录用户标星某个仓库，仓库名为 `owner/repo`。
   *
   * 会先确保 access token 未过期（必要时刷新）。
   * 两个回调都保证在**主线程**执行；界面里可以直接改控件，不需要自己 post。
   */
  fun star(repo: String, errorHandler: Cons<Throwable> = Cons{}, success: ConsT<Http.HttpResponse, Exception>) {
    withFreshToken(errorHandler) {
      authRequest(Http.HttpMethod.PUT, "$STAR_API/$repo")
        // PUT 无请求体，显式置空以保证发送 Content-Length: 0
        .content("")
        .error { error -> onMain { errorHandler.get(error) } }
        .submit { response -> onMain { success.get(response) } }
    }
  }

  /**
   * 取消当前登录用户对某个仓库的标星，仓库名为 `owner/repo`。
   * 两个回调都保证在**主线程**执行。
   */
  fun unstar(repo: String, errorHandler: Cons<Throwable> = Cons{}, success: ConsT<Http.HttpResponse, Exception>) {
    withFreshToken(errorHandler) {
      authRequest(Http.HttpMethod.DELETE, "$STAR_API/$repo")
        .error { error -> onMain { errorHandler.get(error) } }
        .submit { response -> onMain { success.get(response) } }
    }
  }

  fun listStarred(errorHandler: Cons<Throwable> = Cons{}, result: Cons<Jval>) {
    withFreshToken(errorHandler) {
      val acc = Jval.newArray()
      fetchStarredPage(1, acc, errorHandler) {
        onMain { result.get(acc) }
      }
    }
  }

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

  fun isModRepo(raw: Jval): Boolean {
    val topics = raw.get("topics") ?: return false
    return topics.isArray && topics.asArray().any { it.asString() == MOD_TOPIC }
  }

  fun isStarred(repo: String, errorHandler: Cons<Throwable> = Cons{}, result: Cons<Boolean>) {
    withFreshToken(errorHandler) {
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
  }

  private fun fetchStarredPage(
    page: Int,
    acc: Jval,
    errorHandler: Cons<Throwable>,
    onDone: () -> Unit,
  ) {
    authRequest(Http.HttpMethod.GET, "$STAR_API?per_page=$PAGE_SIZE&page=$page")
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

  fun authGET(url: String): Http.HttpRequest {
    return authRequest(Http.HttpMethod.GET, url)
  }

  fun authPOST(url: String): Http.HttpRequest {
    return authRequest(Http.HttpMethod.POST, url)
  }

  /**
   * 用存储中的当前令牌构造鉴权请求。
   *
   * ⚠️ **不能**把 `githubUser` 是否为空当前置条件：游戏启动时正是"存储里有凭据、`githubUser` 还是 null"
   * 的空窗期（恢复会话就发生在此时）。曾经这里多了一次 `githubUser == null` 判断，导致每次启动恢复会话
   * 都在 [fetchUser] 里同步抛 "no auth token"，被 [verifyRestoredSession] 的兜底当成失效处理并清掉
   * 磁盘凭据 —— 表现就是"每次重启都掉登录"。
   */
  fun authRequest(method: Http.HttpMethod, url: String): Http.HttpRequest {
    val record = loadSession() ?: throw IllegalStateException("no auth token")

    return record.use { Http.request(method, url).setupAuthHead(it.accessToken) }
  }

  private fun Http.HttpRequest.setupAuthHead(token: ByteArray): Http.HttpRequest {
    header("Accept", "application/vnd.github+json")
    header("Authorization", "Bearer " + String(token, Charsets.UTF_8))
    header("X-GitHub-Api-Version", API_VERSION)
    header("User-Agent", USER_AGENT)

    return this
  }

  private fun withFreshToken(onError: Cons<Throwable>, action: () -> Unit) {
    val record = loadSession()

    if (record == null || githubUser == null) {
      onMain { onError.get(IllegalStateException("no auth token")) }
      return
    }

    record.use { record ->
      if (!record.isWithinPolicy()) {
        Log.info("github session reached its idle/absolute limit, a new login is required")
        discardSession()
        onMain { onError.get(IllegalStateException("github session expired, please log in again")) }
        return
      }

      if (!record.needsRefresh() || record.refreshToken == null) {
        markUsed()
        runAction(onError, action)
        return
      }

      var queued = false
      synchronized(refreshLock) {
        if (refreshing) {
          refreshWaiters.add(onError to action)
          queued = true
        }
        else {
          refreshing = true
        }
      }

      if (queued) return

      refreshSession(record, onError) {
        markUsed()
        runAction(onError, action)
      }
    }
  }

  private fun refreshSession(record: SessionRecord, onError: Cons<Throwable>, onSuccess: () -> Unit) {
    val refreshSource = record.refreshToken
    if (refreshSource == null) {
      onSuccess()
      return
    }

    val refresh = refreshSource.copyOf()
    val authorizedAt = record.authorizedAt

    fun finish() {
      val waiters = synchronized(refreshLock) {
        refreshing = false
        refreshWaiters.toList().also { refreshWaiters.clear() }
      }

      waiters.forEach { (waiterError, waiterAction) -> withFreshToken(waiterError, waiterAction) }
    }

    fun failed(json: Jval?) {
      refresh.fill(0)
      val reason = json?.let { describe(it) } ?: "unknown error"

      if (json == null) {
        // 拿不到结构化的拒绝响应 = 网络/服务端故障：**保留**凭据，下次使用或下次启动再试。
        // 否则一次断网刷新失败就会把用户的登录吃掉。
        Log.warn("github token refresh failed due to a network/server error (@), keeping the stored credential", reason)
        finish()
        onMain { onError.get(IllegalStateException("could not refresh the github session: $reason")) }
        return
      }

      // GitHub 明确拒绝了刷新请求（如 bad_refresh_token / invalid_grant）：凭据确实失效
      Log.warn("github token refresh was rejected (@), a new login is required", reason)
      discardSession()
      finish()
      onMain { onError.get(IllegalStateException("github session expired, please log in again: $reason")) }
    }

    val body = "client_id=${form(clientId())}" +
      "&grant_type=${form(REFRESH_GRANT)}" +
      "&refresh_token=${form(String(refresh, Charsets.UTF_8))}"

    Http.request(Http.HttpMethod.POST, ACCESS_TOKEN_API)
      .header("Accept", "application/json")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("User-Agent", USER_AGENT)
      .content(body)
      .error { error -> failed(error.asGithubJson()) }
      .submit { response ->
        try {
          val json = Jval.read(response.resultAsString)
          val access = json.getString("access_token") ?: ""

          if (access.isBlank()) {
            failed(json)
          }
          else {
            val now = System.currentTimeMillis()
            val expiresIn = json.getInt("expires_in", 0)
            val rotated = json.getString("refresh_token", "").takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)

            val next = SessionRecord(
              accessToken = access.toByteArray(Charsets.UTF_8),
              refreshToken = rotated ?: refresh.copyOf(),
              accessExpiresAt = if (expiresIn > 0) now + expiresIn * 1000L else 0L,
              authorizedAt = authorizedAt,
              lastUsedAt = now,
            )

            try {
              persist(next)
            }
            finally {
              next.close()
              refresh.fill(0)
              rotated?.fill(0)
            }

            finish()
            onSuccess()
          }
        }
        catch (e: Exception) {
          failed(null)
        }
      }
  }

  /**
   * 记录一次使用，用于 24 小时空闲上限。
   *
   * 独立 load → 更新 lastUsedAt → persist → close：不依赖任何常驻对象。
   * 写盘有节流，避免每次请求都重写密文。
   */
  private fun markUsed() {
    val record = loadSession() ?: return

    try {
      val now = System.currentTimeMillis()
      if (now - record.lastUsedAt < LAST_USED_WRITE_INTERVAL_MS) return

      persist(record.accessToken, record.refreshToken, record.accessExpiresAt, record.authorizedAt, now)
    }
    finally {
      record.close()
    }
  }

  /** 把凭据加密写入独立文件（不进 [He.global]，也不进 global_vars.bin 的备份链）。 */
  private fun persist(record: SessionRecord) {
    persist(record.accessToken, record.refreshToken, record.accessExpiresAt, record.authorizedAt, record.lastUsedAt)
  }

  /**
   * 加密落盘。明文只在编码缓冲里存在，写完立即清零；本方法不保留任何引用。
   */
  private fun persist(
    accessToken: ByteArray,
    refreshToken: ByteArray?,
    accessExpiresAt: Long,
    authorizedAt: Long,
    lastUsedAt: Long,
  ) {
    val plain = encodeSession(accessToken, refreshToken, accessExpiresAt, authorizedAt, lastUsedAt)

    try {
      SecureStore.write(SESSION_SECRET_ID, plain)
    }
    finally {
      plain.fill(0)
    }
  }

  private fun loadSession(): SessionRecord? {
    val raw = SecureStore.readBytes(SESSION_SECRET_ID) ?: return null

    val decoded = decodeSession(raw)
    if (decoded == null) {
      Log.warn("stored github session could not be parsed, discarding it")
      SecureStore.erase(SESSION_SECRET_ID)
    }

    return decoded
  }

  private fun discardSession() {
    githubUser = null
    loginState = LoginState.LoggedOut
    SecureStore.erase(SESSION_SECRET_ID)
  }

  private class SessionRecord(
    val accessToken: ByteArray,
    val refreshToken: ByteArray?,
    val accessExpiresAt: Long,
    val authorizedAt: Long,
    val lastUsedAt: Long,
  ) : AutoCloseable {
    fun isWithinPolicy(): Boolean {
      val now = System.currentTimeMillis()
      if (now - lastUsedAt > SESSION_IDLE_TIMEOUT_MS) return false
      if (now - authorizedAt > SESSION_MAX_AGE_MS) return false

      return true
    }

    fun needsRefresh(): Boolean =
      accessExpiresAt > 0 && System.currentTimeMillis() + TOKEN_REFRESH_SKEW_MS >= accessExpiresAt

    override fun close() {
      accessToken.fill(0)
      refreshToken?.fill(0)
    }
  }
  private fun encodeSession(
    accessToken: ByteArray,
    refreshToken: ByteArray?,
    accessExpiresAt: Long,
    authorizedAt: Long,
    lastUsedAt: Long,
  ): ByteArray {
    val buffer = ByteBuffer.allocate(
      SESSION_RECORD_HEADER + accessToken.size + (refreshToken?.size ?: 0)
    )

    buffer.put(SESSION_RECORD_VERSION)
    buffer.put(if (refreshToken == null) 0 else 1)
    buffer.putLong(accessExpiresAt)
    buffer.putLong(authorizedAt)
    buffer.putLong(lastUsedAt)
    buffer.putShort(accessToken.size.toShort())
    buffer.putShort((refreshToken?.size ?: 0).toShort())
    buffer.put(accessToken)
    refreshToken?.also { buffer.put(it) }

    return buffer.array()
  }

  private fun decodeSession(payload: ByteArray): SessionRecord? {
    var access: ByteArray? = null
    var refresh: ByteArray? = null

    try {
      val buffer = ByteBuffer.wrap(payload)

      if (buffer.get() != SESSION_RECORD_VERSION) return null

      val flags = buffer.get().toInt()
      val accessExpiresAt = buffer.getLong()
      val authorizedAt = buffer.getLong()
      val lastUsedAt = buffer.getLong()
      val accessLength = buffer.short.toInt()
      val refreshLength = buffer.short.toInt()

      if (accessLength <= 0 || accessLength > buffer.remaining()) return null

      access = ByteArray(accessLength).also { buffer.get(it) }

      if ((flags and 1) != 0) {
        if (refreshLength <= 0 || refreshLength > buffer.remaining()) return null
        refresh = ByteArray(refreshLength).also { buffer.get(it) }
      }

      return SessionRecord(access, refresh, accessExpiresAt, authorizedAt, lastUsedAt)
    }
    catch (error: Throwable) {
      access?.fill(0)
      refresh?.fill(0)
      return null
    }
    finally {
      payload.fill(0)
    }
  }

  /**
   * 在游戏主线程执行；应用未就绪时直接执行。
   *
   * **本对象的对外回调一律必须经过这里**（onError / onSuccess / result / listener）。
   * 一旦漏掉，调用方（界面）就会在 HTTP / 定时器线程上改场景图，
   * 与渲染线程的 `Table.layout()/computeSize()` 并发，抛出
   * `ArrayIndexOutOfBoundsException`（实测崩在 `Table.computeSize:940`、`Table.layout:1092`）。
   */
  private fun onMain(action: () -> Unit) {
    val app = Core.app
    if (app == null) action() else app.post(action)
  }

  /**
   * 执行 [action] 并把**同步抛出的异常**也送进 [onError]（同样回主线程）。
   * 主要是防止 [authRequest] 在"检查通过之后、真正取令牌之前"会话被作废时把异常抛进 Arc 的 Http 回调，
   * 那样调用方既拿不到错误回调、也不会重试，界面就会静默卡住。
   */
  private fun runAction(onError: Cons<Throwable>, action: () -> Unit) {
    try {
      action()
    }
    catch (error: Throwable) {
      onMain { onError.get(error) }
    }
  }

  private fun fail(error: Throwable, onError: Cons<Throwable>) {
    loginState = LoginState.Failed
    Log.err(error)
    onMain { onError.get(error) }
  }

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

  class DeviceLogin(
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
    internal val deviceCode: String,
  )

  class GithubUser(
    val login: String,
    val name: String,
    val url: String,
    val avatarUrl: String,
  ) {
    val username: String get() = login
  }
}
