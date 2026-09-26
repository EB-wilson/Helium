package helium.ui.dialogs.mods

import arc.Core
import arc.graphics.Color
import arc.math.Interp
import arc.scene.style.Drawable
import arc.scene.ui.Label
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table
import arc.util.Align
import arc.util.Scaling
import helium.ui.HeAssets
import helium.ui.UIUtils.line
import helium.ui.dialogs.mods.ModsDialogHelper.buildDescSelector
import helium.ui.dialogs.mods.ModsDialogHelper.buildLinkButton
import helium.ui.dialogs.mods.ModsDialogHelper.setupContentsList
import helium.ui.elements.HeCollapser
import mindustry.ctype.UnlockableContent
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.Styles
import universe.ui.markdown.Markdown
import universe.ui.markdown.MarkdownStyles

abstract class ModTab(
  protected val title: String,
  protected val version: String,
  protected val subtitle: String,
  protected val author: String?,
) : Table(), Cloneable {
  private var coll: HeCollapser? = null

  var favoriteOwner: AbstractFavorites? = null

  protected var current = -1
  protected var setupContent: (Int) -> Unit = {}

  fun build(): ModTab {
    buildHeader()
    row()
    buildCollapsible()

    return this
  }

  public override fun clone(): ModTab = buildCopy().build()

  protected abstract fun buildCopy(): ModTab

  private fun buildHeader() {
    button({ top ->
      top.table(Tex.buttonSelect) { icon -> decorateIcon(icon) }
        .pad(10f).margin(4f).size(88f)

      top.stack(
        Table { info -> buildInfo(info) },
        Table { over -> buildOver(over) },
      ).grow()
    }, Styles.grayt) {
      coll?.toggle()
      if (coll?.collapse == false) setupContent(0)
    }.growX().fillY()
  }

  private fun buildInfo(info: Table) {
    info.left().top().margin(12f).marginLeft(6f).defaults().left()

    styleTitle(info.add(title).color(Pal.accent).padRight(160f).wrap(true))
    info.row()
    styleVersion(info.add(version, 0.8f).color(Color.lightGray).padRight(50f).wrap(true))
    info.row()
    styleSubtitle(info.add(subtitle).padRight(50f).wrap(true))
  }

  private fun buildOver(over: Table) {
    over.right()

    over.table { status ->
      status.top().defaults().size(26f).pad(4f)
      buildCornerStatus(status)
    }.fill().pad(4f)

    styleSideCell(over.table { side -> buildSide(side) })
  }

  protected open fun decorateIcon(icon: Table) {
    icon.image(icon()).scaling(Scaling.fit).size(80f)
  }

  protected open fun icon(): Drawable = Tex.nomap

  protected open fun styleTitle(cell: Cell<Label>): Cell<Label> = cell.grow()

  protected open fun styleVersion(cell: Cell<Label>): Cell<Label> = cell.grow()

  protected open fun styleSubtitle(cell: Cell<Label>): Cell<Label> = cell.grow()

  protected open fun buildCornerStatus(status: Table) {}

  protected open fun buildSide(side: Table) {
    side.line(Color.darkGray, false, 3f)
    side.table { buttons ->
      buttons.defaults().size(48f)
      buildSideButtons(buttons)
    }.fill()
  }

  protected open fun buildSideButtons(buttons: Table) {}

  protected open fun styleSideCell(cell: Cell<Table>): Cell<Table> = cell.fill()

  private fun buildCollapsible() {
    val right = buildRightColumn()

    coll = add(
      HeCollapser(collX = false, collY = true, collapsed = true, Styles.grayPanel) { col ->
        if (right == null) {
          col.table { details -> buildDetails(details) }.grow()
        }
        else {
          col.stack(
            Table { details -> buildDetails(details) },
            Table { conf -> right(conf) },
          ).grow()
        }
      }.setDuration(0.3f, Interp.pow3Out)
    ).growX().fillY().colspan(2).get()
  }

  protected open fun buildDetails(details: Table) {
    details.left().defaults().growX().pad(4f).padLeft(12f).padRight(12f)

    details.add(Core.bundle.format("dialog.mods.author", author))
      .growX().padRight(50f).wrap(true).color(Pal.accent).labelAlign(Align.left)
    details.row()

    details.table { link -> buildLinkButton(link, linkName()) }
    details.row()

    details.table { status ->
      status.left().defaults().left()
      buildStatusRows(status)
    }
    details.row()

    details.line(Color.gray, true, 4f).pad(6f).padLeft(-6f).padRight(-6f)
    details.row()

    val contents = contents()
    buildDescSelector(details, { current }, { i -> setupContent(i) }, contents)
    details.row()

    details.table(HeAssets.grayUI) { desc ->
      desc.defaults().grow()
      setupContent = a@{ i ->
        if (i == current) return@a

        desc.clearChildren()
        current = i

        when (i) {
          0 -> desc.add(Markdown(description() ?: "", MarkdownStyles.defaultMD))
          1 -> desc.add(description() ?: "").wrap(true)
          2 -> setupContentsList(desc, contents)
        }
      }
    }.grow().margin(12f).padTop(0f)
  }

  protected open fun buildStatusRows(status: Table) {}

  protected open fun buildRightColumn(): ((Table) -> Unit)? = null

  protected abstract fun linkName(): Name

  protected open fun description(): String? = null

  protected open fun contents(): List<UnlockableContent> = emptyList()
}
