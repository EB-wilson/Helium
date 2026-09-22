package helium.ui.dialogs.mods

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.math.Interp
import arc.math.geom.Rect
import arc.scene.Group
import arc.scene.style.Drawable
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.ObjectMap
import arc.struct.ObjectSet
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

  /** 收藏夹的数据来源。 */
  enum class FavoritesMode {
    /** 未登录 GitHub：使用本地存档，离线可用 */
    Local,
    /** 已登录 GitHub：使用该账号的仓库 Star 作为收藏夹 */
    Github,
  }

  /**
   * 收藏夹加载状态，供界面按状态切换提示文本：
   * 对应 bundle 的 `dialog.mods.shouldLogin` / `dialog.mods.loading` / `dialog.mods.noFavorites` / `dialog.mods.checkFailed`。
   */
  enum class FavoritesStatus {
    /** 离线模式，未登录 GitHub */
    NonLogin,
    /** 正在拉取账号 Star */
    Loading,
    /** 数据就绪 */
    Ready,
    /** 拉取失败 */
    Error,
  }

  private lateinit var rebuildList: () -> Unit

  private val browserTabs = ObjectMap<ModListing, Table>()

  /** 收藏的 mod：离线模式来自本地存档，GitHub 模式来自账号 Star */
  private val favoritesMods = ObjectSet<Name>()

  /** 收藏的仓库全名（`owner/repo`，小写）。与 [favoritesMods] 同步维护，用于按仓库地址精确匹配 */
  private val favoriteRepos = ObjectSet<String>()

  private var favoriteStatus = FavoritesStatus.NonLogin
  private var favoritesLoading = false

  private var search = ""
  private var orderDate = false
  private var reverse = false
  private var hideInvalid = true

  init {
    shown(::rebuild)
    resized(::rebuild)
  }

  // =============================================================================================
  // 收藏夹挂点（离线 / GitHub 双实现）
  //
  // 说明：界面尚未对接，以下函数即为对接入口。
  //  - 状态查询：favoritesMode / githubLoggedIn / githubUser / currentFavoritesStatus
  //              / isFavorite / favoriteRepoList
  //  - 行为挂点：reloadFavorites / toggleFavorite / loginGithub / logoutGithub / cancelGithubLogin
  // =============================================================================================

  /** @return 当前收藏夹实现：登录 GitHub 后自动切换为账号 Star */
  val favoritesMode: FavoritesMode
    get() = if (GithubAPI.usable()) FavoritesMode.Github else FavoritesMode.Local

  /** @return 当前是否已登录 GitHub */
  val githubLoggedIn: Boolean get() = GithubAPI.usable()

  /** @return 当前登录的 GitHub 账号，未登录时为 null */
  val githubUser: GithubAPI.GithubUser? get() = GithubAPI.currUser()

  /** @return 收藏夹加载状态，供界面展示对应提示 */
  val currentFavoritesStatus: FavoritesStatus get() = favoriteStatus

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
   * 读取本地离线收藏夹（不访问网络）。这是「离线模式」的收藏夹实现。
   */
  fun loadFavorites(){
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

  /**
   * 保存本地离线收藏夹。这是「离线模式」的收藏夹实现；
   * GitHub 模式下收藏状态由账号 Star 决定，不写本地存档。
   */
  fun saveFavorites() {
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

  /**
   * 按当前模式刷新收藏夹数据（不改动界面）。
   * - 离线模式：直接读取本地存档；
   * - GitHub 模式：拉取登录账号的 Star 仓库（仅 topics 含 `mindustry-mod` 的仓库）。
   *
   * @param onDone 主线程回调，数据刷新完成后触发
   */
  fun refreshFavorites(onDone: () -> Unit = {}) {
    if (favoritesMode == FavoritesMode.Local) {
      loadFavorites()
      favoriteStatus = FavoritesStatus.NonLogin
      onDone()
      return
    }

    if (favoritesLoading) return

    favoritesLoading = true
    favoriteStatus = FavoritesStatus.Loading

    GithubAPI.listStarredRepos(
      errorHandler = { error ->
        favoritesLoading = false
        favoriteStatus = FavoritesStatus.Error

        Log.err(error)
        onDone()
      }
    ) { repos ->
      favoritesLoading = false
      favoritesMods.clear()
      favoriteRepos.clear()

      repos.forEach { repo ->
        favoriteRepos.add(repo)

        val bi = repo.split("/")
        if (bi.size == 2) favoritesMods.add(Name(bi[0], bi[1]))
      }

      favoriteStatus = FavoritesStatus.Ready
      onDone()
    }
  }

  /**
   * 刷新收藏夹并重建列表，界面上的「刷新」按钮可直接挂这里。
   */
  fun reloadFavorites() {
    refreshFavorites { rebuildFavoritesView() }
  }

  /**
   * 切换某个 mod 的收藏状态。
   * - 离线模式：读写本地存档；
   * - GitHub 模式：调用 star / unstar 接口，成功后同步内存缓存。
   *
   * @param onError 主线程回调，失败时触发（界面可据此提示）
   * @param onResult 主线程回调，参数为切换后的收藏状态
   */
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

      saveFavorites()
      onResult.get(nowFavorite)
      return
    }

    if (!GithubAPI.usable()) {
      onError.get(IllegalStateException("no github session"))
      return
    }

    if (favoritesMods.contains(name) || favoriteRepos.contains(lower)) {
      GithubAPI.unstar(repo, { error -> onError.get(error) }) {
        Core.app.post {
          favoritesMods.remove(name)
          favoriteRepos.remove(lower)
          onResult.get(false)
        }
      }
    }
    else {
      GithubAPI.star(repo, { error -> onError.get(error) }) {
        Core.app.post {
          favoritesMods.add(name)
          favoriteRepos.add(lower)
          onResult.get(true)
        }
      }
    }
  }

  /**
   * 登录挂点：唤起系统浏览器打开 GitHub 认证页，并等待用户授权。
   *
   * @param onCode 主线程回调，界面应在此展示用户码 `device.userCode`
   * @param onError 主线程回调，登录失败
   * @param onDone 主线程回调，登录成功后触发，参数为登录账号
   */
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

  /** 取消正在进行的 GitHub 登录（用户关闭授权界面时调用）。 */
  fun cancelGithubLogin() {
    GithubAPI.cancelLogin()
  }

  /** 退出 GitHub 登录，并回落到本地离线收藏夹。 */
  fun logoutGithub() {
    GithubAPI.logout()
    loadFavorites()
    favoriteStatus = FavoritesStatus.NonLogin
    rebuildFavoritesView()
  }

  /** 供界面挂点安全重建收藏夹列表（界面尚未构建时不做事）。 */
  private fun rebuildFavoritesView() {
    if (::rebuildList.isInitialized) rebuildList()
  }

  fun rebuild(){
    loadFavorites()

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

        rebuildList = {
          var favCols: Array<Table>? = null
          var normCols: Array<Table>? = null

          list.clearChildren()
          list.add(" " + Core.bundle["dialog.mods.favorites"]).color(Pal.accent).padLeft(26f)
          list.row()
          list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
          list.row()
          list.cullTable { fav ->
            //when (favoriteStatus) {
            //  NonLogin -> {
            //    fav.table { tab ->
            //      tab.image(Icon.github).size(46f)
            //      tab.add(Core.bundle["dialog.mods.shouldLogin"]).pad(36f).padLeft(12f)
            //    }.fill().colspan(n)
            //    fav.row()
            //  }
            //  Loading -> {
            //    fav.table { tab ->
            //      tab.image(HeAssets.loading).size(46f).color(Pal.accent)
            //      tab.add(Core.bundle["dialog.mods.loading"]).color(Pal.accent).pad(36f).padLeft(12f)
            //    }.fill().colspan(n)
            //    fav.row()
            //  }
            //  Ready -> {
            //    if (favoritesMods.isEmpty) {
            //      fav.table { tab ->
            //        tab.image(Icon.box).size(46f).color(Pal.accent)
            //        tab.add(Core.bundle["dialog.mods.noFavorites"]).pad(36f).padLeft(12f)
            //      }.fill().colspan(n)
            //      fav.row()
            //    }
            //  }
            //  Error -> {
            //    fav.table { tab ->
            //      tab.image(HeAssets.networkError).size(46f).color(Color.red)
            //      tab.add(Core.bundle["dialog.mods.checkFailed"], Styles.outlineLabel).pad(36f).padLeft(12f)
            //    }.fill().colspan(n)
            //    fav.row()
            //  }
            //}

            if (favoritesMods.isEmpty) {
              fav.table { tab ->
                tab.image(Icon.box).size(46f).color(Pal.accent)
                tab.add(Core.bundle["dialog.mods.noFavorites"]).pad(36f).padLeft(12f)
              }.fill().colspan(n)
              fav.row()
            }

            fav.defaults().width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f)
            favCols = Array(n) {
              fav.cullTable(HeAssets.grayUIAlpha) { it.top().defaults().growX().fillY() }.get()
            }
          }
          list.row()
          list.add(" " + Core.bundle["dialog.mods.mods"]).color(Pal.accent).padLeft(26f)
          list.row()
          list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
          list.row()
          list.cullTable { norm ->
            norm.top().defaults().width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f)
            normCols = Array(n) {
              norm.cullTable(HeAssets.grayUIAlpha) { it.top().defaults().growX().fillY() }.get()
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
                  if (favoritesMods.contains(Name(m))) favCols!![favI++%n]
                  else normCols!![normI++%n]
                val tab = buildModTab(m)

                col.add(tab).growX().fillY().pad(4f).row()
              }
          }
        }

        //rebuildFavorites = rebuildFavorites@{
        //  favoriteStatus = if (!GithubAPI.usable()) NonLogin else Loading
        //  favoritesMods.clear()
        //  rebuildList()
//
        //  if (!GithubAPI.usable()) return@rebuildFavorites
        //  GithubAPI.listStarred({ err ->
        //    favoriteStatus = Error
        //    rebuildList()
        //    Log.err(err)
        //  }) { list ->
        //    list.asArray().forEach { raw ->
        //      val topics = raw.get("topics").asArray()
        //      if (topics.contains { it.asString().contains("mindustry-mod") }){
        //        val repo = raw.getString("full_name")
        //        val bi = repo.split("/")
        //        val author = bi[0].lowercase()
        //        val name = bi[1].lowercase()
        //        val modName = Name(author, name)
//
        //        favoritesMods.add(modName)
        //      }
        //    }
//
        //    favoriteStatus = Ready
        //    rebuildList()
        //  }
        //}

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

    // 已登录 GitHub 时，改用账号 Star 作为收藏夹：异步覆盖本地缓存，完成后重建列表
    if (favoritesMode == FavoritesMode.Github) {
      refreshFavorites { rebuildFavoritesView() }
    }
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
            info.add(mod.name).color(Pal.accent).growX().labelAlign(Align.left).padRight(160f).wrap()
            info.row()
            info.add(mod.version, 0.8f).color(Color.lightGray).growX().padRight(50f).wrap()
            info.row()
            info.add(mod.shortDescription()).growY().growX().padRight(50f).wrap()
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
                // TODO 对接挂点（暂未接入界面）：登录 GitHub 后应改为调用
                //   toggleFavorite(mod,
                //     onError = { e -> Core.app.post { UIUtils.showException(e, Core.bundle["infos.handleFailed"]); Log.err(e) } },
                //     onResult = { rebuildList() })
                // 当前仍直接读写本地离线收藏夹（favoritesMods + saveFavorites）。
                buttons.button(Icon.star, Styles.clearNonei, 24f) {
                  if (!favoritesMods.add(modName)) favoritesMods.remove(modName)
                  saveFavorites()

                  rebuildList()
                  //if (favoritesMods.contains(modName)) {
                  //  GithubAPI.unstar(
                  //    mod.repo,
                  //    { e ->
                  //      Core.app.post {
                  //        UIUtils.showException(e, Core.bundle["infos.handleFailed"])
                  //        Log.err(e)
                  //      }
                  //    }
                  //  ) {
                  //    Core.app.post {
                  //      favoritesMods.remove(modName)
                  //      rebuildList()
                  //    }
                  //  }
                  //}
                  //else {
                  //  GithubAPI.star(
                  //    mod.repo,
                  //    { e ->
                  //      Core.app.post {
                  //        UIUtils.showException(e, Core.bundle["infos.handleFailed"])
                  //        Log.err(e)
                  //      }
                  //    }
                  //  ) {
                  //    Core.app.post {
                  //      favoritesMods.add(modName)
                  //      rebuildList()
                  //    }
                  //  }
                  //}
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
          .growX().padRight(50f).wrap().color(Pal.accent).labelAlign(Align.left)
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
              1 -> desc.add(mod.description).wrap()
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

  //private enum class FavoritesStatus{
  //  NonLogin,
  //  Loading,
  //  Ready,
  //  Error,
  //}
}