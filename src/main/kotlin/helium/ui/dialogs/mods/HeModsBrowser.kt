package helium.ui.dialogs.mods

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.math.Interp
import arc.math.geom.Rect
import arc.scene.Group
import arc.scene.style.Drawable
import arc.scene.style.Style
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Dialog
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.ObjectMap
import arc.struct.ObjectSet
import arc.struct.OrderedMap
import arc.util.Align
import arc.util.Log
import arc.util.Scaling
import arc.util.serialization.Jval
import helium.GithubAPI
import helium.He
import helium.addEventBlocker
import helium.set
import helium.ui.ButtonEntry
import helium.ui.HeAssets
import helium.ui.UIUtils
import helium.ui.UIUtils.line
import helium.GithubAPI.LoginState.*
import helium.ui.dialogs.mods.ModsDialogHelper.addTip
import helium.ui.dialogs.mods.ModsDialogHelper.buildModAttrIcons
import helium.ui.dialogs.mods.ModsDialogHelper.buildModAttrList
import helium.ui.dialogs.mods.ModsDialogHelper.buildStars
import helium.ui.dialogs.mods.ModsDialogHelper.buildStatus
import helium.ui.dialogs.mods.ModsDialogHelper.getModList
import helium.ui.dialogs.mods.ModsDialogHelper.showDownloadModDialog
import helium.ui.dialogs.mods.ModsDialogHelper.switchBut
import helium.ui.elements.HeCollapser
import helium.util.Downloader
import helium.util.ModStat
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.FileChooser
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import universe.ui.markdown.Markdown
import universe.ui.markdown.MarkdownStyles
import kotlin.math.max
import kotlin.math.min

class HeModsBrowser: BaseDialog(Core.bundle["mods.browser"]) {
  companion object{
    private fun Table.cullTable(background: Drawable? = null, build: Cons<Table>? = null) =
      add(CullTable(background).also { t -> build?.also { it.get(t) } })
  }

  enum class FavoritesMode {
    Local,
    Github,
  }

  /** 真正的重建实现，在构建 ScrollPane 时赋值 */
  private lateinit var rebuildListNow: () -> Unit

  /** 构造线程即游戏主线程；用于判断回调当前在哪个线程 */
  private val mainThread = Thread.currentThread()

  /**
   * 重建入口：**保证只在主线程执行**。
   *
   * 场景图不能被异步回调改。曾经 GitHub 登录失败的回调在 HTTP 线程里直接 `hide()` + 重建列表，
   * 与渲染线程的 `Table.layout()/computeSize()` 并发，抛 `ArrayIndexOutOfBoundsException`
   * （实测 `Table.computeSize:940` 的 `Index 1 out of bounds for length 1`、
   * `Table.layout:1092` 的 `Index 8 out of bounds for length 8`）。这里兜底，即使调用方漏了线程判断也不会崩。
   */
  private fun rebuildList() {
    runOnMain { if (::rebuildListNow.isInitialized) rebuildListNow() }
  }

  private fun runOnMain(action: () -> Unit) {
    if (Thread.currentThread() === mainThread) action() else Core.app.post(action)
  }

  private val browserTabs = ObjectMap<ModListing, Table>()

  private val favoritesMods = ObjectSet<Name>()

  /** 收藏的仓库全名（`owner/repo`，小写）。与 [favoritesMods] 同步维护，用于按仓库地址精确匹配 */
  private val favoriteRepos = ObjectSet<String>()

  private var favoritesLoading = false

  /**
   * 收藏夹列表是否拉取失败。这是独立于登录状态的状态：
   * 用户可能已登录（[GithubAPI.LoginState.LoggedIn]）但 star 列表没拉回来，登录枚举表达不了。
   */
  var favoritesLoadFailed = false
    private set

  private var search = ""
  private var orderDate = false
  private var reverse = false
  private var hideInvalid = true

  init {
    shown(::rebuild)
    resized(::rebuild)
  }

  val favoritesMode: FavoritesMode
    get() = if (GithubAPI.usable()) FavoritesMode.Github else FavoritesMode.Local

  /** @return 当前是否已登录 GitHub */
  val githubLoggedIn: Boolean get() = GithubAPI.usable()

  /** @return 当前登录的 GitHub 账号，未登录时为 null */
  val githubUser: GithubAPI.GithubUser? get() = GithubAPI.currUser()

  /**
   * @return 当前收藏夹/登录状态，直接取 [GithubAPI.state]，不维护平行枚举。
   * LoggedOut 离线收藏夹 / Waiting 等待网页授权 / LoggedIn 使用账号 Star / Failed 登录失败。
   */
  val currentFavoritesStatus: GithubAPI.LoginState get() = GithubAPI.state()

  /** @return 某个 mod 是否已收藏（按当前模式判断） */
  fun isFavorite(mod: ModListing): Boolean =
    favoritesMods.contains(Name(mod)) || favoriteRepos.contains(mod.repo.lowercase())

  /** @return 某个 mod 是否已收藏 */
  fun isFavorite(name: Name): Boolean = favoritesMods.contains(name)

  /** @return 当前收藏夹内的仓库全名（`owner/repo`，小写） */
  fun favoriteRepoList(): List<String> {
    val result = ArrayList<String>(favoriteRepos.size)
    favoriteRepos.forEach { result.add(it) }

    return result
  }

  /**
   * 用 modList 的条目补全收藏夹的 [Name]。
   *
   * GitHub 模式下收藏夹只记录 Star 到的仓库全名（`owner/repo`），而 [Name] 的 author/name 取自仓库内
   * mod.json / mod.hjson 的实际上报值（由索引脚本对符合 topic 条件的仓库提取），二者并不一致：
   * 用仓库地址直接拼 [Name] 永远匹配不到 modList 里的条目。所以这里按 repo 反查条目后再取它的 [Name]。
   *
   * modList 未就绪时不做事：此时 [favoriteRepos] 已能按仓库地址精确匹配，等列表重建时会再次调用本方法补全。
   */
  private fun resolveFavoriteNames(list: OrderedMap<Name, ModListing>) {
    if (favoriteRepos.isEmpty) return

    list.values().forEach { m ->
      if (favoriteRepos.contains(m.repo.lowercase())) favoritesMods.add(Name(m))
    }
  }

  fun loadLocalFavorites(){
    favoritesMods.clear()
    favoriteRepos.clear()

    val listRaw = He.global.getString("favorite-mods", "none")

    if (listRaw == "none" || listRaw.isNullOrBlank()) return
    val list = Jval.read(listRaw).asArray()
    list.forEach{
      val author = it.getString("author") ?: ""
      val name = it.getString("name") ?: ""

      favoritesMods.add(Name(author, name))
      favoriteRepos.add("${author.lowercase()}/${name.lowercase()}")
    }
  }

  fun saveLocalFavorites() {
    if (favoritesMode == FavoritesMode.Github) return

    val list = Jval.newArray()
    favoritesMods.forEach {
      val mod = Jval.newObject()
      mod.put("author", it.author)
      mod.put("name", it.name)

      list.add(mod)
    }
    He.global.put("favorite-mods", list.toString())
  }

  fun refreshFavorites(onDone: () -> Unit = {}) {
    if (favoritesMode == FavoritesMode.Local) {
      loadLocalFavorites()
      favoritesLoadFailed = false
      onDone()
      return
    }

    if (favoritesLoading) return

    favoritesLoading = true
    favoritesLoadFailed = false

    GithubAPI.listStarredRepos(
      errorHandler = { error ->
        favoritesLoading = false
        favoritesLoadFailed = true

        Log.err(error)
        onDone()
      }
    ) { repos ->
      favoritesLoading = false
      favoritesLoadFailed = false
      favoritesMods.clear()
      favoriteRepos.clear()

      // Star 接口只给出仓库全名，且它和 Name 的 author/name 不一致，不能在这里直接拼 Name；
      // 先只记录仓库全名，Name 由 resolveFavoriteNames 回到 modList 中按 repo 反查（索引已就绪则立即补全）。
      repos.forEach { favoriteRepos.add(it) }

      ModsDialogHelper.modList?.also(::resolveFavoriteNames)

      onDone()
    }
  }

  fun reloadFavorites() {
    refreshFavorites { rebuildFavoritesView() }
  }

  fun toggleFavorite(
    mod: ModListing,
    onError: Cons<Throwable> = Cons{},
    onResult: Cons<Boolean> = Cons{},
  ) {
    val name = Name(mod)
    val repo = mod.repo
    val lower = repo.lowercase()

    if (favoritesMode == FavoritesMode.Local) {
      val nowFavorite = if (favoritesMods.contains(name)) {
        favoritesMods.remove(name)
        favoriteRepos.remove(lower)
        false
      }
      else {
        favoritesMods.add(name)
        favoriteRepos.add(lower)
        true
      }

      saveLocalFavorites()
      onResult.get(nowFavorite)
      return
    }

    if (!GithubAPI.usable()) {
      onError.get(IllegalStateException("no github session"))
      return
    }

    // star/unstar 的回调已由 GithubAPI 保证在主线程投递，这里直接改状态与界面即可
    if (favoritesMods.contains(name) || favoriteRepos.contains(lower)) {
      GithubAPI.unstar(repo, onError) {
        favoritesMods.remove(name)
        favoriteRepos.remove(lower)
        onResult.get(false)
      }
    }
    else {
      GithubAPI.star(repo, onError) {
        favoritesMods.add(name)
        favoriteRepos.add(lower)
        onResult.get(true)
      }
    }
  }

  /** 唤起系统浏览器打开 GitHub 认证页，等待用户授权。 */
  fun loginGithub(
    onCode: Cons<GithubAPI.DeviceLogin> = Cons{},
    onError: Cons<Throwable> = Cons{},
    onDone: Cons<GithubAPI.GithubUser> = Cons{},
  ) {
    GithubAPI.startLogin(
      openBrowser = true,
      onCode = onCode,
      onError = onError,
      onSuccess = { user ->
        refreshFavorites { rebuildFavoritesView() }
        onDone.get(user)
      }
    )
  }

  /** 取消正在进行的 GitHub 登录 */
  fun cancelGithubLogin() {
    GithubAPI.cancelLogin()
  }

  /** 退出 GitHub 登录 */
  fun logoutGithub() {
    GithubAPI.logout()
    loadLocalFavorites()
    favoritesLoadFailed = false
    rebuildFavoritesView()
  }

  private fun rebuildFavoritesView() = rebuildList()

  fun rebuild(){
    loadLocalFavorites()

    cont.clearChildren()
    cont.table { main ->
      main.top()
      main.table { top ->
        top.image(Icon.zoom).size(64f).scaling(Scaling.fit)
        top.field(""){
          search = it.lowercase()
          rebuildList()
        }.growX()
        top.button(Icon.list, Styles.emptyi, 32f) {
          orderDate = !orderDate
          rebuildList()
        }.update { b -> b.style.imageUp = (if (orderDate) Icon.list else Icon.star) }
          .size(48f).get()
          .addListener(Tooltip { tip ->
            tip!!.label { if (orderDate) "@mods.browser.sortdate" else "@mods.browser.sortstars" }.left()
          })
        top.button(Icon.list, Styles.emptyi, 32f) {
          reverse = !reverse
          rebuildList()
        }.update { b -> b.style.imageUp = (if (reverse) Icon.upOpen else Icon.downOpen) }
          .size(48f).get()
          .addListener(Tooltip { tip ->
            tip!!.label { if (reverse) "@misc.reverse" else "@misc.sequence" }.left()
          })
        top.check(Core.bundle["dialog.mods.hideInvalid"], hideInvalid) {
          hideInvalid = it
          rebuildList()
        }
      }.growX().padLeft(40f).padRight(40f)
      main.row()
      main.line(Pal.accent, true, 4f).padTop(4f)
      main.row()
      main.add(ScrollPane(CullTable { list ->
        list.top().defaults().fill()

        val n = max((Core.graphics.width/Scl.scl(540f)).toInt(), 1)

        rebuildListNow = {
          var favCols: Array<Table>? = null
          var normCols: Array<Table>? = null

          list.clearChildren()
          list.add(" " + Core.bundle["dialog.mods.favorites"]).color(Pal.accent).padLeft(26f)
          list.row()
          list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
          list.row()

          list.table(HeAssets.grayUIAlpha) { github ->
            when(currentFavoritesStatus){
              LoggedOut -> {
                github.add(Core.bundle["dialog.mods.nonLogin"]).pad(4f)
                  .wrap(true).growX().labelAlign(Align.center)
                github.row()
                github.button(Core.bundle["misc.login"], Icon.githubSmall, Styles.cleart, 32f){
                  onLogin()
                  rebuildList()
                }.pad(4f).margin(6f)
              }
              Waiting -> {
                github.image(HeAssets.loading).size(32f).pad(4f)
                github.add(Core.bundle["dialog.mods.logining"]).padLeft(8f)
              }
              LoggedIn -> {
                val user = githubUser!!
                val avatar = Downloader.downloadLazyDrawable(
                  user.avatarUrl,
                  (Tex.nomap as TextureRegionDrawable).region
                )

                github.add(Core.bundle["dialog.mods.loggedStar"]).pad(4f)
                  .wrap(true).growX().labelAlign(Align.center)
                github.row()
                github.table { account ->
                  account.add(Core.bundle["dialog.mods.loginedAccount"])

                  account.button({ t ->
                    t.image(avatar).size(32f).pad(4f)
                    t.add(user.username).pad(4f)
                  }, Styles.cleart){
                    Core.app.openURI(user.url)
                  }.pad(4f).margin(6f)

                  account.button(Core.bundle["misc.logout"], Icon.exitSmall, Styles.cleart, 32f){
                    logoutGithub()
                    rebuildList()
                  }.pad(4f).margin(6f)
                }
              }
              Failed -> {
                github.image(HeAssets.networkError).size(32f).pad(4f)
                github.add(Core.bundle["dialog.mods.loginFailed"], Styles.outlineLabel).padLeft(8f)
                github.button(Core.bundle["misc.retry"], Styles.cleart){
                  onLogin()
                  rebuildList()
                }.pad(4f).margin(6f)
              }
            }
          }.growX().padLeft(20f).padRight(20f).margin(8f)
          list.row()

          list.cullTable { fav ->
            if (favoritesLoadFailed) {
              fav.table { tab ->
                tab.image(HeAssets.networkError).size(46f).color(Color.red)
                tab.add(Core.bundle["dialog.mods.favoritesFailed"], Styles.outlineLabel).pad(36f).padLeft(12f)
                tab.button(Core.bundle["misc.retry"], Styles.cleart) { reloadFavorites() }.margin(6f)
              }.fill().colspan(n)
              fav.row()
            }
            else if (favoritesMods.isEmpty && favoriteRepos.isEmpty) {
              fav.table { tab ->
                tab.image(Icon.box).size(46f).color(Pal.accent)
                tab.add(Core.bundle["dialog.mods.noFavorites"]).pad(36f).padLeft(12f)
              }.fill().colspan(n)
              fav.row()
            }

            favCols = Array(n) {
              fav.cullTable(HeAssets.grayUIAlpha) {
                it.top().defaults().growX().fillY()
              }.width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f).get()
            }
          }
          list.row()
          list.add(" " + Core.bundle["dialog.mods.mods"]).color(Pal.accent).padLeft(26f)
          list.row()
          list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
          list.row()
          list.cullTable { norm ->
            normCols = Array(n) {
              norm.cullTable(HeAssets.grayUIAlpha) {
                it.top().defaults().growX().fillY()
              }.width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f).get()
            }
          }
          getModList(
            errHandler = { e ->
              Log.err(e)
              list.clearChildren()
              list.image(HeAssets.networkError).size(48f).pad(6f).color(Color.red)
              list.add(Core.bundle["dialog.mods.checkFailed"], Styles.outlineLabel)
            }
          ) { ls ->
            var favI = 0
            var normI = 0

            resolveFavoriteNames(ls)

            ls.values()
              .filter {
                search.isBlank()
                || it.name.lowercase().contains(search)
                || it.internalName.lowercase().contains(search)
              }
              .filter { !hideInvalid || ModStat.run { it.checkStatus().isValid() } }
              .let { l ->
                if (reverse) {
                  if (orderDate) l.reversed()
                  else l.sortedBy { it.stars }
                }
                else {
                  if (orderDate) l
                  else l.sortedBy { -it.stars }
                }
              }
              .forEach { m ->
                val col =
                  if (isFavorite(m)) favCols!![favI++%n]
                  else normCols!![normI++%n]
                val tab = buildModTab(m)

                col.add(tab).growX().fillY().pad(4f).minWidth(0f).row()
              }
          }
        }

        rebuildList()
      }, Styles.smallPane)).growY().fillX()
      main.row()
      main.line(Color.gray, true, 4f).padTop(6f).padBottom(6f)
      main.row()
      main.table { bot ->
        bot.defaults().width(242f).height(62f).pad(6f)
        bot.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f)
        { hide() }
        bot.button(Core.bundle["dialog.mods.refresh"], Icon.refresh, Styles.grayt, 46f) {
          ModsDialogHelper.resetModListCache()
          browserTabs.clear()

          rebuildList()
        }
        if (Core.graphics.isPortrait) bot.row()
        bot.button(Core.bundle["dialog.mods.importFav"], Icon.download, Styles.grayt, 46f) {
          importFavorites()
        }
        bot.button(Core.bundle["dialog.mods.exportFav"], Icon.export, Styles.grayt, 46f) {
          exportFavorites()
        }
      }.growX().fillY()
    }.grow()

    if (favoritesMode == FavoritesMode.Github) {
      refreshFavorites { rebuildFavoritesView() }
    }
  }

  private fun onLogin() {
    var codePane: Dialog? = null

    loginGithub(
      onCode = { code ->
        runOnMain {
          Core.app.clipboardText = code.userCode

          codePane = UIUtils.showPane(
            Core.bundle["dialog.mods.logining"],
            ButtonEntry(
              Core.bundle["misc.cancel"],
              Icon.cancelSmall
            ){
              cancelGithubLogin()
              it.hide()
              rebuildList()
            }
          ){ i ->
            i.add(Core.bundle["dialog.mods.copyCode"]).growX().fillY().minWidth(500f).wrap()
            i.row()
            i.table { c ->
              c.add(code.userCode, Styles.outlineLabel).fontScale(2.5f)
              c.button(Icon.copySmall, Styles.clearNonei, 24f) {
                Core.app.clipboardText = code.userCode
              }.margin(4f).top().left()
            }.fill().padTop(20f)
            i.row()
            i.add(Core.bundle.format("dialog.mods.codeVaildTime", code.expiresIn/60))
              .color(Color.lightGray).padTop(6f).padBottom(18f)
          }

          codePane.hidden { cancelGithubLogin() }

          rebuildList()
        }
      },
      // 这两个回调会 hide() 对话框并重建列表，都是在动场景图，必须钉在主线程
      onError = {
        runOnMain {
          codePane?.hide()
          rebuildList()
        }
      },
      onDone = {
        runOnMain {
          codePane?.hide()
          rebuildList()
        }
      }
    )
  }

  private fun importFavorites() {
    UIUtils.showInput(
      Core.bundle["dialog.mods.importFav"],
      Core.bundle["dialog.mods.inputFavText"],
      true
    ){ d, t ->
      val repos = t.split(";").map { it.trim() }.toSet()
      getModList { list ->
        list.values()
          .filter { repos.contains(it.repo) }
          .forEach { m ->
            val key = "mod.favorites.${m.internalName}"
            He.global.put(key, true)
          }

        rebuildList()
        d.hide()
      }
    }
  }

  private fun exportFavorites() {
    if (favoritesMods.isEmpty){
      UIUtils.showTip(
        null,
        Core.bundle["dialog.mods.noFavorites"]
      )
      return
    }

    val mods = StringBuilder()
    favoritesMods.forEach {  m ->
      mods.append("${m.author}/${m.name}").append(";\n")
    }

    UIUtils.showPane(
      Core.bundle["dialog.mods.exportFav"],
      UIUtils.closeBut,
      ButtonEntry(Core.bundle["misc.copy"], Icon.copy) {
        Vars.ui.showInfoFade(Core.bundle["infos.copied"])
        Core.app.clipboardText = mods.toString()
      },
      ButtonEntry(Core.bundle["misc.save"], Icon.file) {
        FileChooser.save("zip").submit { f ->
          f.writer(false).write(mods.toString())
        }
      }
    ){ t ->
      t.add(Core.bundle["dialog.mods.favoritesText"]).growX().pad(6f).left()
        .labelAlign(Align.left).color(Color.lightGray)
      t.row()
      t.table(HeAssets.darkGrayUIAlpha) { l ->
        l.left().top().add(
          mods,
          Label.LabelStyle(MarkdownStyles.defaultMD.codeFont.fontModifier, Color.white)
        ).pad(6f).wrap()
      }.margin(12f).minWidth(420f).growX()
    }
  }

  private fun buildModTab(mod: ModListing): Table {
    browserTabs[mod]?.also { return it }

    val modName = Name(mod)
    val res = Table()
    val stat = mod.checkStatus()
    var coll: HeCollapser? = null
    var setupContent = { _: Int -> }

    browserTabs[mod] = res

    val iconLink = "https://raw.githubusercontent.com/Anuken/MindustryMods/master/icons/" + mod.repo.replace("/", "_")
    val image = Downloader.downloadLazyDrawable(iconLink, Core.atlas.find("nomap"))
    val loaded = Vars.mods.getMod(mod.internalName)

    res.button(
      { top ->
        top.table(Tex.buttonSelect) { icon ->
          icon.stack(
            Image(image).setScaling(Scaling.fit),
            Table { stars ->
              stars.bottom().left()
              buildStars(stars, mod)
            }
          ).size(80f)
        }.pad(10f).margin(4f).size(88f)
        top.stack(
          Table { info ->
            info.left().top().margin(12f).marginLeft(6f).defaults().left()
            info.add(mod.name).color(Pal.accent).growX().labelAlign(Align.left).padRight(160f).wrap(true)
            info.row()
            info.add(mod.version, 0.8f).color(Color.lightGray).growX().padRight(50f).wrap(true)
            info.row()
            info.add(mod.shortDescription()).growY().growX().padRight(50f).wrap(true)
          },
          Table { over ->
            over.right()

            over.table { status ->
              status.top().defaults().size(26f).pad(4f)

              loaded?.also { loaded ->
                if (loaded.meta.version != mod.version) {
                  status.image(Icon.starSmall).scaling(Scaling.fit).color(HeAssets.lightBlue)
                    .addTip(Core.bundle["dialog.mods.newVersion"])
                }
                else {
                  status.image(Icon.okSmall).scaling(Scaling.fit).color(Pal.heal)
                    .addTip(Core.bundle["dialog.mods.installed"])
                }
              }

              buildModAttrIcons(status, stat)
            }.fill().pad(4f)

            over.table { side ->
              side.line(Color.darkGray, false, 3f)
              side.table { buttons ->
                buttons.defaults().size(48f)
                buttons.button(Icon.star, Styles.clearNonei, 24f) {
                  toggleFavorite(
                    mod,
                    onError = { e ->
                      UIUtils.showException(e, Core.bundle["dialog.mods.starFailed"])
                      Log.err(e)
                    },
                    onResult = { rebuildList() }
                  )
                }.update { b ->
                  b.image.setScale(0.9f)
                  b.style.imageUpColor = if (favoritesMods.contains(modName)) Pal.accent else Color.white
                }

                buttons.row()
                buttons.button(Icon.downloadSmall, Styles.clearNonei, 48f) {
                  showDownloadModDialog(mod) {
                    browserTabs.clear()
                    He.heModsDialog.rebuildMods()
                    rebuildList()
                  }
                }
                buttons.row()

                buttons.addEventBlocker()
              }.fill()
            }.fill()
          }
        ).grow()
      }, Styles.grayt) {
      coll!!.toggle()
      if (!coll!!.collapse){
        setupContent(0)
      }
    }.growX().fillY()

    res.row()
    coll = res.add(HeCollapser(collX = false, collY = true, collapsed = true, Styles.grayPanel) { col ->
      col.table { details ->
        details.left().defaults().growX().pad(4f).padLeft(12f).padRight(12f)

        details.add(Core.bundle.format("dialog.mods.author", mod.author))
          .growX().padRight(50f).wrap(true).color(Pal.accent).labelAlign(Align.left)
        details.row()
        details.table { link ->
          link.left().image(Icon.githubSmall).scaling(Scaling.fit).size(24f).color(Color.lightGray)
          val linkButton = link.button("...", Styles.nonet) {}
            .padLeft(4f).wrapLabel(true)
            .growX().left().align(Align.left).height(30f).disabled(true).get()

          linkButton.label.setAlignment(Align.left)
          linkButton.label.setFontScale(0.9f)

          val url = "https://github.com/${mod.repo}"
          linkButton.isDisabled = false
          linkButton.setText(url)
          linkButton.clicked { Core.app.openURI(url) }
        }
        details.row()
        details.table { status ->
          status.left().defaults().left()

          loaded?.also { loaded ->
            if (loaded.meta.version != mod.version) {
              buildStatus(status, Icon.starSmall, Core.bundle["dialog.mods.newVersion"], HeAssets.lightBlue)
            }
            else {
              buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.installed"], Pal.heal)
            }
          }

          buildModAttrList(status, stat)
        }
        details.row()
        details.line(Color.gray, true, 4f).pad(6f).padLeft(-6f).padRight(-6f)
        details.row()

        var current = -1
        details.table { switch ->
          switch.left().defaults().center()
          switch.button({ it.add(Core.bundle["dialog.mods.description"], 0.85f) }, switchBut) { setupContent(0) }
            .margin(12f).checked { current == 0 }.disabled { t -> t.isChecked }
          switch.button({ it.add(Core.bundle["dialog.mods.rawText"], 0.85f) }, switchBut) { setupContent(1) }
            .margin(12f).checked { current == 1 }.disabled { t -> t.isChecked }
        }.grow().padBottom(0f)
        details.row()
        details.table(HeAssets.grayUI) { desc ->
          desc.defaults().grow()
          setupContent = a@{ i ->
            if (i == current) return@a

            desc.clearChildren()
            current = i

            when (i) {
              0 -> desc.add(Markdown(mod.description?:"", MarkdownStyles.defaultMD))
              1 -> desc.add(mod.description).wrap(true)
            }
          }
        }.grow().margin(12f).padTop(0f)
      }.grow()
    }.also { it.setDuration(0.3f, Interp.pow3Out) }).growX().fillY().colspan(2).get()

    return res
  }

  private class CullTable: Table{
    constructor(background: Drawable?): super(background)
    constructor(build: Cons<Table>): super(build)
    constructor(background: Drawable?, build: Cons<Table>): super(background, build)

    override fun drawChildren() {
      cullingArea?.also { widgetAreaBounds ->
        children.forEach { widget ->
          if (widget is Group) {
            val set = widget.cullingArea?: Rect()
            set.set(widgetAreaBounds)
            set.x -= widget.x
            set.y -= widget.y
            widget.setCullingArea(set)
          }
        }
      }
      super.drawChildren()
    }
  }
}