package helium.ui.dialogs.mods

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.math.Interp
import arc.math.geom.Rect
import arc.scene.Group
import arc.scene.style.Drawable
import arc.scene.ui.Dialog
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextField
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Cell
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
import helium.ui.elements.HeCollapser
import helium.util.ImageCache
import helium.util.ModStat
import helium.util.VersionCompareHelper.tryCompareVersion
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.graphics.Pal
import mindustry.ui.FileChooser
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import universe.ui.markdown.MarkdownStyles
import kotlin.math.max
import kotlin.math.min

class HeModsBrowser: BaseDialog(Core.bundle["mods.browser"]) {
  companion object{
    private fun Table.cullTable(background: Drawable? = null, build: Cons<Table>? = null) =
      add(CullTable(background).also { t -> build?.also { it.get(t) } })
  }

  private lateinit var rebuildListNow: () -> Unit

  /** 模组图标、头像等远程图片的缓存：同一个 url 只下载一次，失败的条目会被移除以便下次重试 */
  private val imageCache = ImageCache()

  private val mainThread = Thread.currentThread()

  private fun rebuildList() {
    runOnMain { if (::rebuildListNow.isInitialized) rebuildListNow() }
  }

  private fun runOnMain(action: () -> Unit) {
    if (Thread.currentThread() === mainThread) action() else Core.app.post(action)
  }

  private val browserTabs = ObjectMap<ModListing, ModTab>()

  //Favorites
  private val favoriteTabs = ObjectMap<AbstractFavorites, ObjectMap<ModListing, ModTab>>()
  private val expandedFavorites = ObjectSet<AbstractFavorites>()
  private val localFavorites = ArrayList<LocalFavorites>()
  private var githubFavorites: GitHubStarFavorites? = null
  private var localFavoritesLoaded = false
  private val favorites: List<AbstractFavorites>
    get() = githubFavorites?.let { listOf(it) + localFavorites } ?: localFavorites
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

  //Github API
  val githubLoggedIn: Boolean get() = GithubAPI.usable()
  val githubUser: GithubAPI.GithubUser? get() = GithubAPI.currUser()
  val currentFavoritesStatus: GithubAPI.LoginState get() = GithubAPI.state()

  /** @return 某个 mod 是否已被任一收藏夹收藏 */
  fun isFavorite(mod: ModListing): Boolean = favorites.any { it.contains(mod) }
  /** @return 某个 mod 是否已被任一收藏夹收藏 */
  fun isFavorite(name: Name): Boolean = favorites.any { it.contains(name) }

  private fun ensureLocalFavorites() {
    if (localFavoritesLoaded) return

    localFavoritesLoaded = true
    localFavorites.addAll(LocalFavoritesStore.loadAll())
  }

  private fun syncGithubFavorites() {
    if (GithubAPI.usable()) {
      if (githubFavorites != null) return

      githubFavorites = GitHubStarFavorites(
        Core.bundle["dialog.mods.githubStar"],
        onChanged = { rebuildList() },
        onError = { error ->
          Log.err(error)
          UIUtils.showException(error, Core.bundle["dialog.mods.starFailed"])
        },
        onLoaded = { success ->
          favoritesLoadFailed = !success
          rebuildFavoritesView()
        }
      )
      return
    }

    githubFavorites?.also { favoriteTabs.remove(it) }
    githubFavorites = null
    favoritesLoadFailed = false
  }

  /** 重新加载全部收藏夹 */
  fun refreshFavorites(onDone: () -> Unit = {}) {
    syncGithubFavorites()

    localFavorites.forEach { it.loadFavorite() }
    favoritesLoadFailed = false

    githubFavorites?.loadFavorite()
    onDone()
  }

  fun reloadFavorites() {
    refreshFavorites { rebuildFavoritesView() }
  }

  /** 唤起系统浏览器打开 GitHub 认证页，等待授权。 */
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
    syncGithubFavorites()

    localFavorites.forEach { it.loadFavorite() }
    rebuildFavoritesView()
  }

  private fun rebuildFavoritesView() = rebuildList()

  fun rebuild(){
    ensureLocalFavorites()

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
          val folderColumns = ObjectMap<AbstractFavorites, Array<Table>>()

          list.clearChildren()
          buildFavoritesSection(list, n, folderColumns)

          list.row()
          list.add(" " + Core.bundle["dialog.mods.mods"]).color(Pal.accent).padLeft(26f)
          list.row()
          list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
          list.row()

          var normCols: Array<Table>? = null

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
            githubFavorites?.resolve(ls)

            val visible = ls.values()
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
              .toList()

            var normI = 0
            visible.forEach { m ->
              normCols!![normI++%n].add(buildModTab(m)).growX().fillY().pad(4f).minWidth(0f).row()
            }

            folderColumns.forEach { entry ->
              var index = 0
              visible.filter { entry.key.contains(it) }.forEach { m ->
                entry.value[index++%n].add(buildFavoriteModTab(entry.key, m))
                  .growX().fillY().pad(4f).minWidth(0f).row()
              }
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
          favoriteTabs.clear()

          rebuildList()
        }
        if (Core.graphics.isPortrait) bot.row()
        bot.button(Core.bundle["dialog.mods.importFav"], Icon.download, Styles.grayt, 46f) {
          importFavorites()
        }
      }.growX().fillY()
    }.grow()

    if (GithubAPI.usable()) refreshFavorites { rebuildFavoritesView() }
  }

  private fun buildFavoritesSection(
    list: Table,
    n: Int,
    folderColumns: ObjectMap<AbstractFavorites, Array<Table>>,
  ) {
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
          val avatar = imageCache.resolve(user.avatarUrl)

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

    if (favoritesLoadFailed) {
      list.table { tab ->
        tab.image(HeAssets.networkError).size(46f).color(Color.red)
        tab.add(Core.bundle["dialog.mods.favoritesFailed"], Styles.outlineLabel).pad(36f).padLeft(12f)
        tab.button(Core.bundle["misc.retry"], Styles.cleart) { reloadFavorites() }.margin(6f)
      }.fill().colspan(n)
      list.row()
    }

    val folders = favorites
    if (folders.isEmpty()) {
      list.table { tab ->
        tab.image(Icon.box).size(46f).color(Pal.accent)
        tab.add(Core.bundle["dialog.mods.noFavorites"]).pad(36f).padLeft(12f)
      }.fill().colspan(n)
      list.row()
      return
    }

    folders.forEach { folder ->
      folderColumns.put(folder, addFavoritesRow(list, folder, n))
    }
  }

  private fun addFavoritesRow(list: Table, folder: AbstractFavorites, n: Int): Array<Table> {
    var coll: HeCollapser? = null
    var arrowCell: Cell<Image>? = null
    var columns: Array<Table>? = null

    val expanded = expandedFavorites.contains(folder)

    list.button({ t ->
      arrowCell = t.image(if (expanded) Icon.downOpen else Icon.rightOpen).size(28f).pad(4f)

      t.add(folder.name).color(Pal.accent).pad(4f).labelAlign(Align.left)
      t.add().growX()

      t.table { actions ->
        actions.defaults().size(44f).pad(2f)

        if (folder.renamable) {
          actions.button(Icon.edit, Styles.clearNonei, 32f) { renameFavorites(folder) }
            .addTip(Core.bundle["dialog.mods.renameFav"])
        }

        if (folder.deletable) {
          actions.button(Icon.trash, Styles.clearNonei, 32f) { confirmDeleteFavorites(folder) }
            .addTip(Core.bundle["dialog.mods.deleteFav"])
        }

        actions.button(Icon.export, Styles.clearNonei, 32f) { exportFavorites(folder) }
          .addTip(Core.bundle["dialog.mods.exportFav"])

        actions.addEventBlocker()
      }.pad(4f).right()
    }, Styles.grayt) {
      coll?.toggle()

      if (coll?.collapse == false) expandedFavorites.add(folder)
      else expandedFavorites.remove(folder)
    }.growX().fillY().padTop(4f).padLeft(20f).padRight(20f)

    list.row()

    val collapser = HeCollapser(collX = false, collY = true, collapsed = !expanded) { col ->
      col.top()

      columns = Array(n) {
        col.cullTable(HeAssets.grayUIAlpha) {
          it.top().defaults().growX().fillY()
        }.width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f).get()
      }
    }.setDuration(0.3f, Interp.pow3Out)

    coll = collapser
    arrowCell?.update { image ->
      image.drawable = if (coll.collapse) Icon.rightOpen else Icon.downOpen
    }

    list.add(collapser).growX().fillY().padLeft(20f).padRight(20f)
    list.row()

    return columns ?: Array(n) { Table() }
  }

  private fun confirmDeleteFavorites(folder: AbstractFavorites) {
    UIUtils.showConfirm(
      Core.bundle["dialog.mods.deleteFav"],
      Core.bundle.format("dialog.mods.confirmDeleteFav", folder.name)
    ) {
      localFavorites.remove(folder)
      LocalFavoritesStore.delete(folder.name)

      favoriteTabs.remove(folder)
      expandedFavorites.remove(folder)

      rebuildList()
    }
  }

  private val githubStarFavoritesName: String get() = Core.bundle["dialog.mods.githubStar"]

  private fun isFavoritesNameTaken(name: String, exclude: AbstractFavorites? = null): Boolean {
    if (name == exclude?.name) return false
    if (name == githubStarFavoritesName) return true

    return favorites.any { it !== exclude && it.name == name }
  }

  private fun isFavoritesNameUnavailable(name: String, exclude: AbstractFavorites? = null): Boolean {
    val trimmed = name.trim()

    return trimmed.isEmpty() || isFavoritesNameTaken(trimmed, exclude)
  }

  private fun showFavoritesNameDialog(
    title: String,
    initial: String = "",
    exclude: AbstractFavorites? = null,
    onConfirm: (String) -> Unit,
  ) {
    var name = initial

    UIUtils.showPane(
      title,
      UIUtils.cancelBut,
      ButtonEntry(
        Core.bundle["confirm"],
        Icon.ok,
        disabled = { isFavoritesNameUnavailable(name, exclude) }
      ) { d ->
        // 禁用只是界面表现，这里再判一次，绝不让重名写进去
        if (!isFavoritesNameUnavailable(name, exclude)) {
          onConfirm(name.trim())
          d.hide()
        }
      }
    ){ t ->
      t.add(Core.bundle["dialog.mods.favName"]).growX().left().labelAlign(Align.left)
      t.row()
      t.table { row ->
        val field = row.field(initial){ name = it }.growX().fillY().get()
        row.button(Icon.paste, Styles.clearNonei, 32f) {
          field.text = Core.app.clipboardText
          name = field.text
        }.size(48f)
      }.grow().minWidth(420f)
      t.row()
      t.add(Core.bundle["dialog.mods.favNameUnavailable"])
        .color(Color.crimson).left().padTop(4f)
        .visible { isFavoritesNameTaken(name.trim(), exclude) }
    }
  }

  private fun renameFavorites(folder: AbstractFavorites) {
    if (!folder.renamable) return

    showFavoritesNameDialog(
      Core.bundle["dialog.mods.renameFav"],
      initial = folder.name,
      exclude = folder
    ) { name -> applyFavoritesRename(folder, name) }
  }

  private fun applyFavoritesRename(folder: AbstractFavorites, name: String) {
    if (folder !is LocalFavorites || folder.name == name) return
    if (isFavoritesNameTaken(name, folder)) return

    // 名称是只读的：重命名 = 用新名称重建一个本地收藏夹，并顶替它原来的位置
    val renamed = LocalFavorites(name)
    renamed.replaceAll(folder.modList)

    LocalFavoritesStore.delete(folder.name)

    val index = localFavorites.indexOf(folder)
    if (index >= 0) localFavorites[index] = renamed
    else localFavorites.add(renamed)

    if (expandedFavorites.remove(folder)) expandedFavorites.add(renamed)
    favoriteTabs.remove(folder)

    rebuildList()
  }

  private fun exportFavorites(folder: AbstractFavorites) {
    if (folder.isEmpty) {
      UIUtils.showTip(
        null,
        Core.bundle["dialog.mods.noFavorites"]
      )
      return
    }

    val serial = folder.toSerial()

    UIUtils.showPane(
      Core.bundle["dialog.mods.exportFav"],
      UIUtils.closeBut,
      ButtonEntry(Core.bundle["misc.copy"], Icon.copy) {
        Vars.ui.showInfoFade(Core.bundle["infos.copied"])
        Core.app.clipboardText = serial
      },
      ButtonEntry(Core.bundle["misc.save"], Icon.file) {
        FileChooser.save("json").submit { f ->
          f.writer(false).write(serial)
        }
      }
    ){ t ->
      t.add(folder.name).growX().pad(6f).left()
        .labelAlign(Align.left).color(Pal.accent)
      t.row()
      t.add(Core.bundle["dialog.mods.favoritesText"]).growX().pad(6f).left()
        .labelAlign(Align.left).color(Color.lightGray)
      t.row()
      t.table(HeAssets.darkGrayUIAlpha) { l ->
        l.left().top().add(
          serial,
          Label.LabelStyle(MarkdownStyles.defaultMD.codeFont.fontModifier, Color.white)
        ).pad(6f).wrap(true)
      }.margin(12f).minWidth(420f).growX()
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
            i.add(Core.bundle["dialog.mods.copyCode"]).growX().fillY().minWidth(500f).wrap(true)
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
    var favName = ""
    var favText = ""
    // 收藏夹文本里解析出的名称；输入框留空时以它为准
    var serialName: String? = null
    var nameField: TextField? = null

    fun effectiveName(): String = favName.trim().ifBlank { serialName?.trim().orEmpty() }

    // GitHub Star 的名称是保留名称，导入同样不能占用
    fun unavailable(): Boolean = effectiveName() == githubStarFavoritesName

    UIUtils.showPane(
      Core.bundle["dialog.mods.importFav"],
      UIUtils.cancelBut,
      ButtonEntry(Core.bundle["confirm"], Icon.ok, disabled = { unavailable() }) { d ->
        if (!unavailable()) importFavorites(d, favName, favText)
      }
    ){ t ->
      t.add(Core.bundle["dialog.mods.favName"]).growX().left().labelAlign(Align.left)
      t.row()
      t.table { row ->
        nameField = row.field(""){ favName = it }.growX().fillY().get()
        row.button(Icon.paste, Styles.clearNonei, 32f) {
          nameField?.also {
            it.text = Core.app.clipboardText
            favName = it.text
          }
        }.size(48f)
      }.grow().minWidth(420f)
      t.row()
      t.add(Core.bundle["dialog.mods.favNameUnavailable"])
        .color(Color.crimson).left().padTop(4f)
        .visible { unavailable() }
      t.row()
      t.add(Core.bundle["dialog.mods.inputFavText"]).growX().left().padTop(12f)
        .labelAlign(Align.left).color(Color.lightGray)
      t.row()
      t.table { row ->
        val area = row.area(""){ text ->
          favText = text
          serialName = parseFavorites(text)?.name

          if (favName.isBlank()) {
            serialName?.trim()?.takeIf { it.isNotEmpty() }?.also { name ->
              nameField?.text = name
              favName = name
            }
          }
        }.grow().get()

        row.button(Icon.paste, Styles.clearNonei, 32f) {
          area.text = Core.app.clipboardText
          favText = area.text
          serialName = parseFavorites(favText)?.name
        }.size(48f)
      }.grow().minWidth(420f).maxHeight(320f)
    }
  }

  private fun importFavorites(dialog: Dialog, name: String, text: String) {
    val parsed = parseFavorites(text)
    if (parsed == null) {
      UIUtils.showTip(null, Core.bundle["dialog.mods.favInvalidText"])
      return
    }

    val favName = name.trim().ifBlank { parsed.name?.trim().orEmpty() }
    if (favName.isBlank()) {
      UIUtils.showTip(null, Core.bundle["dialog.mods.favEmptyName"])
      return
    }

    // 兜底：正常路径下确认按钮已被禁用
    if (favName == githubStarFavoritesName) {
      UIUtils.showTip(null, Core.bundle["dialog.mods.favNameUnavailable"])
      return
    }

    dialog.hide()
    createImportedFavorites(favName, parsed.mods)
  }

  private fun createImportedFavorites(name: String, mods: List<Name>) {
    val existing = localFavorites.firstOrNull { it.name == name }
    if (existing == null) {
      createLocalFavorites(name, mods)
      rebuildList()
      return
    }

    UIUtils.showPane(
      Core.bundle["dialog.mods.importFav"],
      ButtonEntry(Core.bundle["dialog.mods.favKeepBoth"], Icon.copy) { d ->
        createLocalFavorites(uniqueFavoritesName(name), mods)
        d.hide()
        rebuildList()
      },
      ButtonEntry(Core.bundle["dialog.mods.favMerge"], Icon.add) { d ->
        val merged = ArrayList<Name>(existing.modList)
        mods.forEach { mod -> if (merged.none { it == mod }) merged.add(mod) }

        existing.replaceAll(merged)
        d.hide()
        rebuildList()
      }.row(),
      ButtonEntry(Core.bundle["dialog.mods.favReplace"], Icon.ok) { d ->
        existing.replaceAll(mods)
        d.hide()
        rebuildList()
      },
      UIUtils.cancelBut
    ){ t ->
      t.add(Core.bundle.format("dialog.mods.favExists", name)).growX().wrap(true)
    }
  }

  private fun uniqueFavoritesName(base: String): String {
    var index = 2

    while (localFavorites.any { it.name == "$base ($index)" }) index++

    return "$base ($index)"
  }

  private fun createLocalFavorites(name: String, mods: Collection<Name>): LocalFavorites {
    val folder = LocalFavorites(name)
    folder.replaceAll(mods)
    localFavorites.add(folder)

    return folder
  }

  private fun showAddToFavoritesDialog(mod: ModListing) {
    val checked = ObjectMap<AbstractFavorites, Boolean>()
    favorites.forEach { checked.put(it, it.contains(mod)) }

    fun rebuildContent(t: Table) {
      t.clearChildren()
      t.top().left().defaults().left().growX()

      val folders = favorites
      if (folders.isEmpty()) {
        t.add(Core.bundle["dialog.mods.noFavorites"]).color(Color.lightGray).pad(6f).row()
      }

      folders.forEach { folder ->
        t.button({
          it.left().add(folder.name)
          if (folder is GitHubStarFavorites) {
            it.add().growX()
            it.image(Icon.starSmall).size(24f).addTip(Core.bundle["dialog.mods.githubStarFav"])
          }
        }, Styles.underlineb) {
          checked[folder] = !checked[folder]
        }.margin(10f).marginLeft(14f).pad(6f).update {
          it.isChecked = checked[folder]
        }

        t.row()
      }

      t.row()
      t.button(Core.bundle["dialog.mods.newFav"], Icon.add, Styles.flatt) {
        showNewFavoritesDialog { folder ->
          checked.put(folder, true)
          rebuildContent(t)
        }
      }.pad(6f).left().margin(6f)
      t.row()
    }

    UIUtils.showPane(
      Core.bundle["dialog.mods.addToFav"],
      UIUtils.cancelBut,
      ButtonEntry(Core.bundle["confirm"], Icon.ok) { d ->
        applyFavoritesSelection(mod, checked)
        rebuildList()
        d.hide()
      }
    ){ t -> rebuildContent(t) }
  }

  private fun applyFavoritesSelection(mod: ModListing, checked: ObjectMap<AbstractFavorites, Boolean>) {
    val name = Name(mod)

    favorites.forEach { folder ->
      val wanted = checked.get(folder, folder.contains(mod))

      when {
        wanted && !folder.contains(mod) -> folder.addMod(name)
        !wanted && folder.contains(mod) -> folder.removeMod(name)
      }
    }
  }

  private fun showNewFavoritesDialog(onCreated: (LocalFavorites) -> Unit) {
    showFavoritesNameDialog(Core.bundle["dialog.mods.newFav"]) { name ->
      onCreated(createLocalFavorites(name, emptyList()))
    }
  }

  private class ParsedFavorites(val name: String?, val mods: List<Name>)

  private fun parseFavorites(text: String): ParsedFavorites? {
    val raw = text.trim()
    if (raw.isBlank()) return null

    try {
      val json = Jval.read(raw)
      if (json.isArray) return ParsedFavorites(null, parseMods(json))

      if (json.isObject) {
        val mods = json.get("mods") ?: return null
        if (!mods.isArray) return null

        return ParsedFavorites(json.getString("name"), parseMods(mods))
      }
    }
    catch (_: Exception) {
    }

    return null
  }

  private fun parseMods(array: Jval): List<Name> {
    val result = ArrayList<Name>()

    array.asArray().forEach { entry ->
      val name = entry.getString("name") ?: ""
      if (name.isBlank()) return@forEach

      val mod = Name(entry.getString("author") ?: "", name)
      if (result.none { it == mod }) result.add(mod)
    }

    return result
  }

  private fun buildModTab(mod: ModListing): ModTab {
    browserTabs[mod]?.also { return it }

    val tab = ListingTab(mod).build()
    browserTabs[mod] = tab

    return tab
  }

  private fun buildFavoriteModTab(folder: AbstractFavorites, mod: ModListing): ModTab {
    val cache = favoriteTabs.get(folder) ?: ObjectMap<ModListing, ModTab>().also { favoriteTabs.put(folder, it) }

    cache.get(mod)?.also { return it }

    val tab = buildModTab(mod).clone()
    tab.favoriteOwner = folder
    cache.put(mod, tab)

    return tab
  }

  private inner class ListingTab(private val mod: ModListing) : ModTab(
    mod.name,
    mod.version,
    mod.shortDescription(),
    mod.author,
  ) {
    private val modName = Name(mod)
    private val stat = mod.checkStatus()
    private val loaded = Vars.mods.getMod(mod.internalName)
    private val iconUrl =
      "https://raw.githubusercontent.com/Anuken/MindustryMods/master/icons/" + mod.repo.replace("/", "_")

    override fun buildCopy(): ModTab = ListingTab(mod)

    override fun icon(): Drawable = imageCache.resolve(iconUrl)

    override fun decorateIcon(icon: Table) {
      icon.stack(
        Image(icon()).setScaling(Scaling.fit),
        Table { stars ->
          stars.bottom().left()
          buildStars(stars, mod)
        }
      ).size(80f)
    }

    override fun styleTitle(cell: Cell<Label>) = cell.growX().labelAlign(Align.left)

    override fun styleVersion(cell: Cell<Label>) = cell.growX()

    override fun styleSubtitle(cell: Cell<Label>) = cell.growY().growX()

    override fun buildCornerStatus(status: Table) {
      loaded?.also { loaded ->
        if (tryCompareVersion(loaded.meta.version, mod.version) < 0) {
          status.image(Icon.starSmall).scaling(Scaling.fit).color(HeAssets.lightBlue)
            .addTip(Core.bundle["dialog.mods.newVersion"])
        }
        else {
          status.image(Icon.okSmall).scaling(Scaling.fit).color(Pal.heal)
            .addTip(Core.bundle["dialog.mods.installed"])
        }
      }

      buildModAttrIcons(status, stat)
    }

    override fun buildSideButtons(buttons: Table) {
      buttons.button(Icon.star, Styles.clearNonei, 24f) {
        val owner = favoriteOwner

        // 收藏夹分栏内的卡片：强调色按钮只负责把此 mod 移出所在的收藏夹
        if (owner != null) {
          owner.removeMod(modName)
          rebuildList()
        }
        else showAddToFavoritesDialog(mod)
      }.update { b ->
        b.image.setScale(0.9f)
        // 不再按"是否被收藏"着色：只有已登录且该 mod 已被 Star 时才用强调色
        b.style.imageUpColor = when {
          favoriteOwner != null -> Pal.accent
          githubFavorites?.contains(mod) == true -> Pal.accent
          else -> Color.white
        }
      }

      buttons.row()
      buttons.button(Icon.downloadSmall, Styles.clearNonei, 48f) {
        showDownloadModDialog(mod) {
          browserTabs.clear()
          favoriteTabs.clear()
          He.heModsDialog.rebuildMods()
          rebuildList()
        }
      }
      buttons.row()

      buttons.addEventBlocker()
    }

    override fun buildStatusRows(status: Table) {
      loaded?.also { loaded ->
        if (tryCompareVersion(loaded.meta.version, mod.version) < 0) {
          buildStatus(status, Icon.starSmall, Core.bundle["dialog.mods.newVersion"], HeAssets.lightBlue)
        }
        else {
          buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.installed"], Pal.heal)
        }
      }

      buildModAttrList(status, stat)
    }

    override fun linkName() = modName

    override fun description() = mod.description
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
