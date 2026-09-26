package helium.ui.dialogs.mods

import arc.Core
import arc.struct.ObjectMap
import arc.struct.ObjectSet
import arc.struct.OrderedMap
import arc.util.Log
import arc.util.serialization.Jval
import helium.GithubAPI
import helium.He

abstract class AbstractFavorites(val name: String) {
  protected val mods = ArrayList<Name>()

  val modList: List<Name> get() = mods

  val size: Int get() = mods.size
  val isEmpty: Boolean get() = mods.isEmpty()

  open val deletable: Boolean get() = true

  open val renamable: Boolean get() = true

  fun contains(mod: Name): Boolean = mods.any { it == mod }

  open fun contains(mod: ModListing): Boolean = contains(Name(mod))

  fun addMod(mod: Name) {
    if (contains(mod)) return

    mods.add(mod)
    onAddMod(mod)
  }

  fun removeMod(mod: Name) {
    if (!mods.removeAll { it == mod }) return

    onRemoveMod(mod)
  }

  abstract fun loadFavorite()
  abstract fun saveFavorite()
  protected abstract fun onAddMod(mod: Name)
  protected abstract fun onRemoveMod(mod: Name)

  fun toSerial(): String {
    val json = Jval.newObject()
    json.put("name", name)

    val list = Jval.newArray()
    mods.forEach { mod ->
      val entry = Jval.newObject()
      entry.put("author", mod.author)
      entry.put("name", mod.name)

      list.add(entry)
    }
    json.put("mods", list)

    return json.toString()
  }

  protected fun replaceMods(values: Collection<Name>) {
    mods.clear()
    values.forEach { value -> if (!contains(value)) mods.add(value) }
  }

  protected fun discardMod(mod: Name) {
    mods.removeAll { it == mod }
  }

  protected fun restoreMod(mod: Name) {
    if (!contains(mod)) mods.add(mod)
  }
}

class LocalFavorites(name: String) : AbstractFavorites(name) {
  override fun loadFavorite() {
    replaceMods(LocalFavoritesStore.loadModList(name))
  }

  override fun saveFavorite() {
    LocalFavoritesStore.upsert(this)
  }

  override fun onAddMod(mod: Name) = saveFavorite()

  override fun onRemoveMod(mod: Name) = saveFavorite()

  fun replaceAll(values: Collection<Name>) {
    replaceMods(values)
    saveFavorite()
  }
}

object LocalFavoritesStore {
  private const val KEY = "favorite-folders"
  private const val LEGACY_KEY = "favorite-mods"

  fun loadAll(): ArrayList<LocalFavorites> {
    migrateLegacy()

    val result = ArrayList<LocalFavorites>()
    read().asArray().forEach { entry ->
      val name = entry.getString("name") ?: ""
      if (name.isBlank()) return@forEach

      result.add(LocalFavorites(name).also { it.loadFavorite() })
    }

    return result
  }

  fun loadModList(name: String): List<Name> {
    val entry = read().asArray().firstOrNull { it.getString("name") == name } ?: return emptyList()

    return parseMods(entry)
  }

  fun upsert(folder: AbstractFavorites) {
    val json = read()
    val array = json.asArray()
    val entry = toJson(folder)
    val index = array.indexOfFirst { it.getString("name") == folder.name }

    if (index >= 0) array.set(index, entry)
    else array.add(entry)

    write(json)
  }

  fun delete(name: String) {
    val json = read()
    val array = json.asArray()
    val index = array.indexOfFirst { it.getString("name") == name }
    if (index < 0) return

    array.remove(index)
    write(json)
  }

  private fun read(): Jval {
    val raw = He.global.getString(KEY, "")
    if (raw.isBlank()) return Jval.newArray()

    return try {
      val json = Jval.read(raw)
      if (json.isArray) json else Jval.newArray()
    }
    catch (error: Exception) {
      Log.err("failed to read the local favorites, they will be reset", error)
      Jval.newArray()
    }
  }

  private fun write(array: Jval) {
    He.global.put(KEY, array.toString())
  }

  private fun toJson(folder: AbstractFavorites): Jval {
    val entry = Jval.newObject()
    entry.put("name", folder.name)

    val mods = Jval.newArray()
    folder.modList.forEach { mod ->
      val modEntry = Jval.newObject()
      modEntry.put("author", mod.author)
      modEntry.put("name", mod.name)

      mods.add(modEntry)
    }
    entry.put("mods", mods)

    return entry
  }

  private fun parseMods(folder: Jval): List<Name> {
    val array = folder.get("mods") ?: return emptyList()
    if (!array.isArray) return emptyList()

    val result = ArrayList<Name>()
    array.asArray().forEach { entry ->
      val name = entry.getString("name") ?: ""
      if (name.isBlank()) return@forEach

      val mod = Name(entry.getString("author") ?: "", name)
      if (!result.any { it == mod }) result.add(mod)
    }

    return result
  }

  private fun migrateLegacy() {
    if (He.global.has(KEY)) return
    if (!He.global.has(LEGACY_KEY)) return

    val legacy = try {
      Jval.read(He.global.getString(LEGACY_KEY, ""))
    }
    catch (error: Exception) {
      Log.err("failed to migrate the legacy favorite mods", error)
      return
    }

    if (!legacy.isArray || legacy.asArray().size <= 0) return

    val entry = Jval.newObject()
    entry.put("name", Core.bundle["dialog.mods.favorites"])
    entry.put("mods", legacy)

    val array = Jval.newArray()
    array.add(entry)

    write(array)
    Log.info("migrated the legacy favorite mods into a local favorites folder")
  }
}

class GitHubStarFavorites(
  name: String,
  private val onChanged: () -> Unit = {},
  private val onError: (Throwable) -> Unit = {},
  private val onLoaded: (Boolean) -> Unit = {},
) : AbstractFavorites(name) {
  private val repos = ObjectSet<String>()
  private val repoByName = ObjectMap<Name, String>()

  @Volatile
  private var loading = false

  override val deletable: Boolean get() = false

  override val renamable: Boolean get() = false

  override fun contains(mod: ModListing): Boolean =
    super.contains(mod) || repos.contains(mod.repo.lowercase())

  override fun loadFavorite() {
    if (loading) return

    loading = true
    GithubAPI.listStarredRepos(
      errorHandler = { error ->
        loading = false
        Log.err(error)
        onLoaded(false)
      }
    ) { list ->
      loading = false

      repos.clear()
      list.forEach { repos.add(it) }

      resolve(ModsDialogHelper.modList)
      onLoaded(true)
    }
  }

  fun resolve(list: OrderedMap<Name, ModListing>?) {
    repoByName.clear()

    val names = ArrayList<Name>()
    list?.values()?.forEach { mod ->
      val name = Name(mod)
      val repo = mod.repo.lowercase()

      if (!repos.contains(repo) || repoByName.containsKey(name)) return@forEach

      repoByName.put(name, repo)
      names.add(name)
    }

    replaceMods(names)
  }

  override fun saveFavorite() {
    // No action
  }

  override fun onAddMod(mod: Name) {
    val repo = repoOf(mod)
    if (repo == null) {
      discardMod(mod)
      onError(IllegalStateException("cannot resolve the repository of $mod"))
      return
    }

    GithubAPI.star(repo, errorHandler = { error ->
      discardMod(mod)
      onError(error)
      onChanged()
    }) {
      repos.add(repo)
      repoByName.put(mod, repo)
      onChanged()
    }
  }

  override fun onRemoveMod(mod: Name) {
    val repo = repoOf(mod)
    if (repo == null) {
      restoreMod(mod)
      onError(IllegalStateException("cannot resolve the repository of $mod"))
      return
    }

    GithubAPI.unstar(repo, errorHandler = { error ->
      restoreMod(mod)
      onError(error)
      onChanged()
    }) {
      repos.remove(repo)
      repoByName.remove(mod)
      onChanged()
    }
  }

  private fun repoOf(mod: Name): String? =
    repoByName.get(mod) ?: ModsDialogHelper.modList?.get(mod)?.repo?.lowercase()
}
