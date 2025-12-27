package helium.ui.fragments.placement

import arc.Core
import arc.Events
import arc.func.*
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Lines
import arc.input.KeyCode
import arc.math.Interp
import arc.math.Mathf
import arc.scene.Element
import arc.scene.Group
import arc.scene.actions.Actions
import arc.scene.event.ClickListener
import arc.scene.event.HandCursorListener
import arc.scene.event.InputEvent
import arc.scene.event.Touchable
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.*
import arc.scene.ui.Tooltip.Tooltips
import arc.scene.ui.layout.*
import arc.struct.*
import arc.util.Align
import arc.util.Time
import arc.util.Tmp
import helium.He
import helium.He.config
import helium.ui.HeAssets
import helium.ui.UIUtils.line
import helium.ui.elements.HeCollapser
import helium.util.accessField
import helium.util.ifInst
import mindustry.Vars
import mindustry.ai.UnitCommand
import mindustry.ai.UnitStance
import mindustry.ai.types.CommandAI
import mindustry.content.Blocks
import mindustry.core.UI
import mindustry.game.EventType
import mindustry.game.EventType.BlockInfoEvent
import mindustry.game.EventType.WorldLoadEvent
import mindustry.gen.*
import mindustry.gen.Unit
import mindustry.graphics.Pal
import mindustry.input.Binding
import mindustry.input.InputHandler
import mindustry.type.Category
import mindustry.ui.Fonts
import mindustry.ui.Styles
import mindustry.ui.fragments.PlacementFragment
import mindustry.world.Block
import mindustry.world.blocks.ConstructBlock.ConstructBuild
import mindustry.world.meta.StatValues
import kotlin.math.roundToInt

class HePlacementFrag {
  companion object {
    private val returnArray = Seq<Block>()
    private val PlacementFragment.togglerRef: Table by accessField("toggler")
    private var currBlock: Block?
      get() = Vars.control.input.block
      set(value) { Vars.control.input.block = value }

    private val savePattern = Regex("^save#((\\w|-)+)-fast-slot-\\d+-\\d+$")
  }

  private val toolEntries = OrderedMap<String, ToolEntry>()

  private var selectionShown = false

  var menuHoverBlock: Block? = null
  var currentCategory = Category.distribution
  val returnCatArray = Seq<Category>()
  val scrollPositions = ObjectFloatMap<Category>()
  var categoryEmpty = BooleanArray(Category.all.size)
  var selectedBlocks = ObjectMap<Category, Block>()

  private val invSlots = Seq<InvSlot>()
  private var currentSlot: InvSlot? = null
  private var invPage = 0
    set(value) {
      field = value
      invAnimateActivating = true
      invAnimateProgress = 0f
    }

  private lateinit var topLevel: Table
  private lateinit var container: Table
  private lateinit var topInfo: Table
  private lateinit var commandPane: Table

  private var wasHovered = false
  private var lastDisplayBlock: Block? = null

  private lateinit var fastInventory: Table
  private lateinit var blockSelection: Table
  private lateinit var toolButtons: Table
  private var toolsTable: Table? = null

  private lateinit var blockTable: Table
  private lateinit var blockPane: ScrollPane

  private lateinit var foldBlocks: HeCollapser
  private lateinit var foldTools: HeCollapser
  private lateinit var foldIcon: Group

  private var invAnimateActivating = false
  private var invAnimateProgress = 0f

  init {
    Events.on(WorldLoadEvent::class.java) { event ->
      Core.app.post {
        currentCategory = Category.distribution
        currBlock = null

        rebuildCategory()

        loadSlots()
      }
    }
  }

  fun loadSlots(){
    invSlots.forEach { it.load() }
  }

  fun cleanGlobal(){
    val saves = Vars.control.saves.saveSlots
      .map { it.file.nameWithoutExtension() }
      .toSet()

    He.global.keys()
      .filter { savePattern.matches(it) }
      .associateBy { savePattern.find(it)?.groupValues[1] }
      .filter { it.key !in saves }
      .forEach {
        He.global.remove(it.value)
      }
  }

  fun addTool(
    name: String,
    icon: Drawable,
    checked: Boolp? = null,
    tip: Prov<String>? = null,
    listener: Runnable,
  ) {
    addTool(name, { icon }, checked, tip, listener)
  }

  fun addTool(
    name: String,
    icon: Prov<Drawable>,
    checked: Boolp? = null,
    tip: Prov<String>? = null,
    listener: Runnable,
  ) {
    toolEntries.put(name, ToolEntry(icon, checked, tip, listener))
    toolsTable?.also { rebuildTools(it) }
  }

  fun removeTool(name: String) {
    toolEntries.remove(name)
    toolsTable?.also { rebuildTools(it) }
  }

  fun hideTool(name: String) {
    setShown(name, false)
  }

  fun showTool(name: String) {
    setShown(name, true)
  }

  fun setShown(name: String, shown: Boolean) {
    val entry = toolEntries.get(name)
    if (entry != null) {
      entry.shown = shown
    }

    toolsTable?.also { rebuildTools(it) }
  }

  fun clearTools() {
    toolEntries.clear()
  }

  fun build(parent: Group) {
    parent.fill{ toggler ->
      topLevel = toggler

      val old = Vars.ui.hudfrag.blockfrag.togglerRef
      val ind = parent.children.indexOf(old)
      toggler.zIndex = ind + 1

      toggler.update {
        val old = Vars.ui.hudfrag.blockfrag.togglerRef
        old.visible = !config.enableBetterPlacement

        update()
      }
      toggler.bottom().right().visible { config.enableBetterPlacement && Vars.ui.hudfrag.shown }

      toggler.stack(
        Table{ blocks ->
          blocks.bottom().right().add(HeCollapser(collX = true, collY = false) { cont ->
            container = cont

            cont.table(Tex.buttonEdge2) { top ->
              top.left()
              topInfo = top
              buildTopInfo(top)
            }.growX().fillY().touchable(Touchable.enabled)
              .visible { Vars.control.input.block != null || menuHoverBlock != null }
            cont.row()
            cont.table(Tex.pane) { bottom ->
              bottom.margin(4f).top().defaults().fillX().growY()

              bottom.table { fastInv ->
                fastInv.top()
                fastInventory = fastInv
                buildFastInventory(fastInventory)
              }.padLeft(4f)
              bottom.line(Color.darkGray, false, 4f)
                .padLeft(4f).padRight(-2f)
              bottom.add(HeCollapser(collX = true, collY = false, collapsed = true) { blocks ->
                blocks.top()
                blockSelection = blocks
                buildBlockSelection(blockSelection)
              }.setDuration(0.3f, Interp.pow3Out).also { foldBlocks = it })
              bottom.line(Color.darkGray, false, 4f)
                .padLeft(-2f).padRight(0f)
              bottom.table { tools ->
                tools.top()
                toolButtons = tools
                buildToolbar(toolButtons)
              }
            }.fill()
          }.setDuration(0.2f, Interp.pow3Out).setCollapsed { Vars.control.input.commandMode }).fillX()
        },
        Table{ commands ->
          commands.bottom().right().add(HeCollapser(collX = false, collY = true, collapsed = true, Tex.pane) { com ->
            commandPane = com
            buildCommands(com)
          }.setDuration(0.2f, Interp.pow3Out).setCollapsed { !Vars.control.input.commandMode }).fillY()
        }
      ).fill()
    }
  }

  private fun buildCommands(table: Table) {
    table.touchable = Touchable.enabled
    table.add(Core.bundle.get("commandmode.name")).fill().center().labelAlign(Align.center).row()
    table.image().color(Pal.accent).growX().pad(20f).padTop(0f).padBottom(4f).row()
    table.table { u ->
      val activeCommands = Bits(Vars.content.unitCommands().size)
      val activeStances = Bits(Vars.content.unitStances().size)

      val availableCommands = Bits(Vars.content.unitCommands().size)
      val availableStances = Bits(Vars.content.unitStances().size)

      u.left()
      val curCount = intArrayOf(0)
      val usedCommands = Bits(Vars.content.unitCommands().size)
      val commands = Seq<UnitCommand>()

      val usedStances = Bits(Vars.content.unitStances().size)
      val stances = Seq<UnitStance>()
      val stancesOut = Seq<UnitStance>()

      val rebuildCommand = {
        u.clearChildren()
        val units = Vars.control.input.selectedUnits
        if (units.size > 0) {
          usedCommands.clear()
          usedStances.clear()
          commands.clear()
          stances.clear()

          val counts = IntArray(Vars.content.units().size)

          for (unit in units) {
            counts[unit.type.id.toInt()]++

            stancesOut.clear()
            unit.type.getUnitStances(unit, stancesOut)

            for (stance in stancesOut) {
              if (!usedStances.get(stance.id.toInt())) {
                stances.add(stance)
                usedStances.set(stance.id.toInt())
              }
            }
          }

          val unitlist = u.table().growX().left().get()
          unitlist.left()

          var col = 0
          for (i in counts.indices) {
            if (counts[i] > 0) {
              val type = Vars.content.unit(i)
              unitlist.add<Stack?>(StatValues.stack(type, counts[i])).pad(4f).with { b: Stack? ->
                b!!.clearListeners()
                b.addListener(Tooltips.getInstance().create(type.localizedName, false))

                val listener = ClickListener()

                //left click -> select
                b.clicked(KeyCode.mouseLeft) {
                  Vars.control.input.selectedUnits.removeAll(Boolf { unit: Unit? -> unit!!.type !== type })
                  Events.fire<EventType.Trigger?>(EventType.Trigger.unitCommandChange)
                }
                //right click -> remove
                b.clicked(KeyCode.mouseRight) {
                  Vars.control.input.selectedUnits.removeAll(Boolf { unit: Unit? -> unit!!.type === type })
                  Events.fire<EventType.Trigger?>(EventType.Trigger.unitCommandChange)
                }

                b.addListener(listener)
                b.addListener(HandCursorListener())
                //gray on hover
                b.update {
                  (b.getChildren().first() as Group).getChildren().first()
                    .setColor(if (listener.isOver) Color.lightGray else Color.white)
                }
              }

              if (++col%7 == 0) {
                unitlist.row()
              }

              for (command in type.commands) {
                if (!usedCommands.get(command.id.toInt())) {
                  commands.add(command)
                  usedCommands.set(command.id.toInt())
                }
              }
            }
          }

          //list commands
          if (commands.size > 1) {
            u.row()

            u.table { coms: Table? ->
              coms!!.left()
              var scol = 0
              for (command in commands) {
                coms.button(Icon.icons.get(command.icon, Icon.cancel), Styles.clearNoneTogglei) {
                  Call.setUnitCommand(
                    Vars.player,
                    units.mapInt(
                      Intf { un: Unit? -> un!!.id },
                      Boolf { un: Unit? -> un!!.type.allowCommand(un, command) }).toArray(),
                    command
                  )
                }.checked { activeCommands.get(command.id.toInt()) }.size(50f)
                  .tooltip(command.localized(), true)

                if (++scol%6 == 0) coms.row()
              }
            }.fillX().padTop(4f).left()
          }

          //list stances
          if (stances.size > 1) {
            u.row()

            if (commands.size > 1) {
              u.add<Image?>(Image(Tex.whiteui)).height(3f).color(Pal.gray).pad(7f).growX().row()
            }

            u.table { coms ->
              coms.left()
              var scol = 0
              for (stance in stances) {
                coms.button(stance.getIcon(), Styles.clearNoneTogglei) {
                  Call.setUnitStance(
                    Vars.player,
                    units.mapInt(
                      Intf { un: Unit? -> un!!.id },
                      Boolf { un: Unit? -> un!!.type.allowStance(un, stance) }).toArray(),
                    stance,
                    !activeStances.get(stance.id.toInt())
                  )
                }.checked { activeStances.get(stance.id.toInt()) }.size(50f)
                  .tooltip(stance.localized(), true)

                if (++scol%6 == 0) coms.row()
              }
            }.fillX().padTop(4f).left()
          }
        }
        else {
          u.add(Core.bundle.get("commandmode.nounits"))
            .color(Color.lightGray).growX().center().labelAlign(Align.center).pad(6f)
        }
      }

      u.update {
        run {
          activeCommands.clear()
          activeStances.clear()
          availableCommands.clear()
          availableStances.clear()

          //find the command that all units have, or null if they do not share one
          for (unit in Vars.control.input.selectedUnits) {
            unit.controller().ifInst<CommandAI>{
              activeCommands.set(it.command.id.toInt())
              activeStances.set(it.stances)
            }

            stancesOut.clear()
            unit.type.getUnitStances(unit, stancesOut)

            for (stance in stancesOut) {
              availableStances.set(stance.id.toInt())
            }

            for (command in unit.type.commands) {
              availableCommands.set(command.id.toInt())
            }
          }

          val size = Vars.control.input.selectedUnits.size
          if (curCount[0] != size || (usedCommands != availableCommands) || (usedStances != availableStances)) {
            if (curCount[0] + size != 0) {
              rebuildCommand()
            }
            curCount[0] = size
          }

          //not a huge fan of running input logic here, but it's convenient as the stance arrays are all here...
          for (stance in stances) {
            //first stance must always be the stop stance
            if (stance.keybind != null && Core.input.keyTap(stance.keybind)) {
              Call.setUnitStance(
                Vars.player,
                Vars.control.input.selectedUnits.mapInt(
                  Intf { un: Unit? -> un!!.id },
                  Boolf { un: Unit? -> un!!.type.allowStance(un, stance) }).toArray(),
                stance,
                !activeStances.get(stance.id.toInt())
              )
            }
          }
          for (command in commands) {
            //first stance must always be the stop stance
            if (command.keybind != null && Core.input.keyTap(command.keybind)) {
              Call.setUnitCommand(
                Vars.player,
                Vars.control.input.selectedUnits.mapInt(
                  Intf { un: Unit? -> un!!.id },
                  Boolf { un: Unit? -> un!!.type.allowCommand(un, command) }).toArray(),
                command
              )
            }
          }
        }
      }
      rebuildCommand()
    }.grow()
  }

  private fun buildTopInfo(table: Table) {
    table.update {
      val topTable = table

      val displayBlock = if (menuHoverBlock != null) menuHoverBlock else Vars.control.input.block
      val isHovered = displayBlock == null

      if (wasHovered == isHovered && lastDisplayBlock === displayBlock) return@update

      topTable.clear()
      topTable.top().left().margin(14f)

      lastDisplayBlock = displayBlock
      wasHovered = isHovered

      if (displayBlock != null) {
        topTable.table{ name ->
          name.table { header ->
            header.left()
            header.add(Image(displayBlock.uiIcon)).size((8*4).toFloat())
            header.labelWrap { if (!unlocked(displayBlock)) Core.bundle.get("block.unknown") else displayBlock.localizedName }
              .left().growX().padLeft(5f)
          }.growX().left()
          if (unlocked(displayBlock)) {
            name.button("?", Styles.flatBordert) {
              Vars.ui.content.show(displayBlock)
              Events.fire(BlockInfoEvent())
            }.size((8*5).toFloat()).padTop(-5f).padRight(-5f).right().fill().name("blockinfo")
          }
        }.growX().fillY()
        topTable.row()
        //add requirement table
        topTable.table { req ->
          req.top().left()
          displayBlock.requirements.forEach { stack ->
            req.table { line ->
              line.left()
              line.image(stack.item.uiIcon).size((8*2).toFloat())

              line.add(HeCollapser(collX = true, collY = false, collapsed = !selectionShown) { col ->
                col.add(stack.item.localizedName).maxWidth(140f).fillX().color(Color.lightGray).padLeft(2f).left()
                  .get().setEllipsis(true)
              }.setCollapsed { !selectionShown }.setDuration(0.3f, Interp.pow3Out)).fill().pad(0f)

              line.labelWrap {
                val core = Vars.player.core()
                val stackamount = (stack.amount*Vars.state.rules.buildCostMultiplier).roundToInt()
                if (core == null || Vars.state.rules.infiniteResources) return@labelWrap "*/$stackamount"

                val amount = core.items.get(stack.item)
                val color =
                  (if (amount < stackamount/2f) "[scarlet]" else if (amount < stackamount) "[accent]" else "[white]")
                color + UI.formatAmount(amount.toLong()) + "[white]/" + stackamount
              }.padLeft(5f)
            }.left()
            req.row()
          }
        }.growX().left().margin(3f)

        if ((!displayBlock.isPlaceable || !Vars.player.isBuilder) && !Vars.state.rules.editor) {
          topTable.row()
          topTable.table { b ->
            b.image(Icon.cancel).padRight(2f).color(Color.scarlet)
            b.add(if (!Vars.player.isBuilder) "@unit.nobuild" else if (!displayBlock.supportsEnv(Vars.state.rules.env)) "@unsupported.environment" else "@banned")
              .growX().wrap()
            b.left()
          }.padTop(2f).left()
        }
      }
    }
  }

  private fun update() {
    if (handleInput()){
      rebuildCategory()
    }

    if (selectionShown) {
      currentSlot?.also { slot ->
        if (slot.block != currBlock && currBlock != null) {
          slot.block = currBlock
          slot.save()
        }
      }
    }

    if (invAnimateActivating) {
      invAnimateProgress = Mathf.approachDelta(invAnimateProgress, 1f, 0.06f)

      if (invAnimateProgress >= 1) {
        invAnimateActivating = false
      }
    }
  }

  private fun handleInput(): Boolean {
    val input = Vars.control.input

    if (updatePick(input)) return true

    if (Vars.ui.chatfrag.shown() || Vars.ui.consolefrag.shown() || Core.scene.hasKeyboard()) return false

    if (Core.input.keyTap(Binding.categoryPrev)) {
      var i = 0
      do {
        currentCategory = currentCategory.prev()
        i++
      } while (categoryEmpty[currentCategory.ordinal] && i < categoryEmpty.size)
      input.block = getSelectedBlock(currentCategory)
      return true
    }

    if (Core.input.keyTap(Binding.categoryNext)) {
      var i = 0
      do {
        currentCategory = currentCategory.next()
        i++
      } while (categoryEmpty[currentCategory.ordinal] && i < categoryEmpty.size)
      input.block = getSelectedBlock(currentCategory)
      return true
    }

    if (Core.input.keyTap(Binding.blockInfo)) {
      val build = Vars.world.buildWorld(Core.input.mouseWorld().x, Core.input.mouseWorld().y)
      val hovering = if (build == null) null else if (build is ConstructBuild) build.current else build.block
      val displayBlock =
        if (menuHoverBlock != null) menuHoverBlock else if (input.block != null) input.block else hovering
      if (displayBlock != null && displayBlock.unlockedNow()) {
        Vars.ui.content.show(displayBlock)
        Events.fire(BlockInfoEvent())
      }
    }

    return false
  }

  fun updatePick(input: InputHandler): Boolean {
    if (Core.input.keyTap(Binding.pick) && Vars.player.isBuilder && !Core.scene.hasDialog()) { //mouse eyedropper select
      var build = Vars.world.buildWorld(Core.input.mouseWorld().x, Core.input.mouseWorld().y)

      if (build != null && build.inFogTo(Vars.player.team())) build = null

      var tryRecipe = if (build == null) null else if (build is ConstructBuild) build.current else build.block
      var tryConfig = if (build == null || !build.block.copyConfig) null else build.config()

      for (req in Vars.player.unit().plans()) {
        if (!req.breaking && req.block.bounds(req.x, req.y, Tmp.r1).contains(Core.input.mouseWorld())) {
          tryRecipe = req.block
          tryConfig = req.config
          break
        }
      }

      if (tryRecipe == null && Vars.state.rules.editor) {
        val tile = Vars.world.tileWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY())
        if (tile != null) {
          tryRecipe =
            if (tile.block() !== Blocks.air) tile.block() else if (tile.overlay() !== Blocks.air) tile.overlay() else if (tile.floor() !== Blocks.air) tile.floor() else null
        }
      }

      if (tryRecipe != null && ((tryRecipe.isVisible && unlocked(tryRecipe)) || Vars.state.rules.editor)) {
        input.block = tryRecipe
        tryRecipe.lastConfig = tryConfig
        if (tryRecipe.isVisible) {
          currentCategory = input.block.category
        }
        return true
      }
    }
    return false
  }

  private fun buildBlockSelection(table: Table) {
    val selection = table.table().growY().fillX().get()

    selection.table(HeAssets.darkGrayUIAlpha) { blocksSelect ->
      blocksSelect.margin(4f).marginTop(0f)
      blockPane = blocksSelect.top().pane(Styles.smallPane) { blocks -> blockTable = blocks }
        .update { pane ->
          if (pane.hasScroll()) {
            val result = Core.scene.hoverElement
            if (result == null || !result.isDescendantOf(pane)) {
              Core.scene.setScrollFocus(null)
            }
          }
        }.fill().maxHeight(5*48f).get()
    }.growY().bottom().touchable(Touchable.enabled)
    selection.table(Styles.black6) { categories ->
      categories.top().pane(Styles.noBarPane) { catPane ->
        catPane.defaults().size(48f)
        val group = ButtonGroup<ImageButton>()

        for (cat in Category.all) {
          val blocks = getUnlockedByCategory(cat)
          categoryEmpty[cat.ordinal] = blocks.isEmpty
        }

        var needsAssign = categoryEmpty[currentCategory.ordinal]

        var f = 0
        for (cat in getCategories()) {
          if (f++%2 == 0) catPane.row()

          if (categoryEmpty[cat.ordinal]) {
            catPane.add()
            continue
          }

          if (needsAssign) {
            currentCategory = cat
            needsAssign = false
          }

          catPane.button(Vars.ui.getIcon(cat.name), Styles.clearNoneTogglei) {
            currentCategory = cat
            if (currBlock != null) {
              currBlock = getSelectedBlock(currentCategory)
            }
            rebuildCategory()
          }.group(group).update { i -> i.setChecked(currentCategory == cat) }.name("category-" + cat.name)
        }
      }.update { pane ->
        if (pane.hasScroll()) {
          val result = Core.scene.hoverElement
          if (result == null || !result.isDescendantOf(pane)) {
            Core.scene.setScrollFocus(null)
          }
        }
      }.fill().maxHeight(5*48f)
    }.growY().top().touchable(Touchable.enabled)

    rebuildCategory()
  }

  fun rebuildCategory() {
    blockTable.clear()
    blockTable.top().margin(5f)

    var index = 0

    val group = ButtonGroup<ImageButton>()
    group.setMinCheckCount(0)

    val columns = config.blockColumns
    for (block in getUnlockedByCategory(currentCategory)) {
      if (!unlocked(block)) continue
      if (index++%columns == 0) {
        blockTable.row()
      }

      val button = blockTable.button(TextureRegionDrawable(block.uiIcon), Styles.selecti) {
        if (unlocked(block)) {
          if ((Core.input.keyDown(KeyCode.shiftLeft) || Core.input.keyDown(KeyCode.controlLeft))
              && Fonts.getUnicode(block.name) != 0) {
            Core.app.clipboardText = Fonts.getUnicode(block.name).toChar().toString() + ""
            Vars.ui.showInfoFade("@copied")
          }
          else {
            currBlock = if (currBlock === block) null else block
            selectedBlocks.put(currentCategory, currBlock)
          }
        }
      }.size(46f).group(group).name("block-" + block.name).get()
      button.resizeImage(Vars.iconMed)

      button.update {
        val core = Vars.player.core()
        val color =
          if ((Vars.state.rules.infiniteResources
               || (core != null && (core.items.has(block.requirements, Vars.state.rules.buildCostMultiplier)
                                    || Vars.state.rules.infiniteResources))
              ) && Vars.player.isBuilder
          ) Color.white
          else Color.gray
        button.forEach { elem -> elem.setColor(color) }
        button.setChecked(currBlock === block)
        if (!block.isPlaceable) {
          button.forEach { elem -> elem.setColor(Color.darkGray) }
        }
      }

      button.hovered { menuHoverBlock = block }
      button.exited {
        if (menuHoverBlock === block) {
          menuHoverBlock = null
        }
      }
    }

    if (index < 4) {
      for (i in 0..<4 - index) blockTable.add().size(46f)
    }
    blockTable.act(0f)
    blockPane.setScrollYForce(scrollPositions.get(currentCategory, 0f))
    Core.app.post {
      blockPane.setScrollYForce(scrollPositions.get(currentCategory, 0f))
      blockPane.act(0f)
      blockPane.layout()
    }
  }

  private fun getCategories() =
    returnCatArray.clear().addAll(*Category.all)
      .sort{ c1, c2 -> categoryEmpty[c1.ordinal].compareTo(categoryEmpty[c2.ordinal]) }

  private fun getSelectedBlock(cat: Category) =
    selectedBlocks.get(cat) { getByCategory(cat).find { block -> this.unlocked(block) } }

  private fun getByCategory(cat: Category) =
    returnArray.selectFrom(Vars.content.blocks())
    { block -> block.category == cat && block.isVisible && block.environmentBuildable() }

  private fun getUnlockedByCategory(cat: Category) =
    returnArray.selectFrom(Vars.content.blocks()) { block ->
      block.category == cat && block.isVisible && unlocked(block)
    }.sort{ b1, b2 -> b2.isPlaceable.compareTo(b1.isPlaceable) }

  private fun unlocked(block: Block) =
    block.unlockedNowHost() && block.placeablePlayer && block.environmentBuildable()
    && block.supportsEnv(Vars.state.rules.env)

  private fun buildFastInventory(table: Table) {
    table.defaults().size(48f)
    for (i in 0 until 8) {
      if (i > 0 && i % 2 == 0) table.row()
      val slot = InvSlot(i)
      table.add(slot)

      invSlots.add(slot)
    }

    table.row()

    val event = InputEvent()
    fun listen(b: ImageButton, key: KeyCode) {
      if (Core.scene.hasKeyboard()) return

      if (Core.input.keyDown(key)) {
        for (listener in b.listeners) {
          if (listener is ClickListener) {
            listener.touchDown(event, 0f, 0f, 0, listener.button)
          }
        }
      }
      if (Core.input.keyRelease(key)) {
        for (listener in b.listeners) {
          if (listener is ClickListener) {
            listener.touchUp(event, 0f, 0f, 0, listener.button)
          }
        }
      }
    }

    event.listenerActor = table.button(Icon.refresh, Styles.clearNonei, 24f){
      if (invAnimateActivating) return@button

      invPage = (invPage + 1)%3
      currentSlot = null
    }.update { b -> listen(b, config.switchFastPageHotKey) }.get().also {
      if (Core.app.isDesktop) {
        it.fill { x, y, w, h ->
          Fonts.outline.draw(
            config.switchFastPageHotKey.value,
            x, y + h - Fonts.outline.capHeight*0.6f,
            Color.white, 0.65f, true,
            Align.left
          )
        }
      }
    }

    table.stack(
      ImageButton(Icon.leftOpen, Styles.clearNonei).also {
        foldIcon = it
        it.resizeImage(28f)
        it.clicked { toggleSelectionShown() }
        it.update { listen(it, config.placementFoldHotKey) }
      },
      object: Element(){
        override fun draw() {
          validate()
          if (Core.app.isDesktop) {
            Fonts.outline.draw(
              config.placementFoldHotKey.value,
              x, y + height - Fonts.outline.capHeight*0.65f,
              Color.white, 0.65f, true,
              Align.left
            )
          }
        }
      }.also { it.touchable = Touchable.disabled }
    )
  }

  private fun toggleSelectionShown() {
    selectionShown = !selectionShown
    if (selectionShown) {
      currentSlot = null

      foldBlocks.setCollapsed(false)
      foldTools.setCollapsed(true)

      foldIcon.clearActions()
      foldIcon.setOrigin(Align.center)
      foldIcon.isTransform = true
      foldIcon.actions(Actions.scaleTo(-1f, 1f, 0.3f))
    }
    else {
      foldBlocks.setCollapsed(true)
      foldTools.setCollapsed(false)

      foldIcon.clearActions()
      foldIcon.setOrigin(Align.center)
      foldIcon.isTransform = true
      foldIcon.actions(Actions.scaleTo(1f, 1f, 0.3f))
    }
  }

  private fun buildToolbar(table: Table) {
    table.table { edits ->
      edits.top().defaults().size(48f)

      val tmp = Table().also { Vars.control.input.buildPlacementUI(it) }
      tmp.children.begin().forEach { but ->
        edits.add(but)
        edits.row()
      }
      tmp.children.end()
    }.fillX().growY()
    table.add(HeCollapser(collX = true, collY = false, background = HeAssets.grayUIAlpha) { tools ->
      tools.top().defaults().size(48f)
      toolsTable = tools
      rebuildTools(tools)
    }.setDuration(0.3f, Interp.pow3Out).also { foldTools = it }).fillX().growY()
  }

  private fun rebuildTools(table: Table) {
    table.clearChildren()
    table.top().defaults().top().size(50f).pad(0f)

    toolEntries.values().forEach { entry ->
      if (!entry.shown) return@forEach

      val button = table.button(
        Tex.clear,
        if (entry.checked == null) Styles.clearNonei else Styles.clearNoneTogglei,
        entry.listener
      ).update { b ->
        b.style.imageUp = entry.icon.get()
        b.resizeImage(36f)
        entry.checked?.also { b.setChecked(it.get()) }
      }.get()
      entry.hoverTip?.also { hoverTip ->
        button.addListener(Tooltip { t ->
          t.background(Tex.button).add(hoverTip.get()).update { l ->
            l.setText(hoverTip.get())
            l.pack()
          }
        }.also { it.allowMobile = true })
      }
      table.row()
    }
  }

  private inner class InvSlot(val id: Int, val background: Drawable? = HeAssets.slotsBack): Element() {
    private val blocks: Array<Block?> = arrayOfNulls(3)

    var block: Block?
      get() = blocks[invPage]
      set(value) { blocks[invPage] = value }
    private val selected: Boolean get() = currentSlot == this

    private val numKey = KeyCode.all[KeyCode.num1.ordinal + id]

    private var lastClick = 0f

    init {
      clicked {
        if (Time.globalTime - lastClick < 15f) {
          currBlock = null
          block = null
          return@clicked
        }
        lastClick = Time.globalTime
        if (currentSlot == this) {
          currentSlot = null
          currBlock = null
        }
        else {
          currentSlot = this
          currBlock = block
        }
      }

      addListener(HandCursorListener())
      addListener(object : ClickListener(){
        override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Element?) {
          super.enter(event, x, y, pointer, fromActor)

          if (pointer != -1) return

          menuHoverBlock = block
        }

        override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Element?) {
          super.exit(event, x, y, pointer, toActor)

          if (pointer == -1) {
            menuHoverBlock = null
          }
        }
      })
    }

    private fun slotName(): String? {
      return if (Vars.net.server()) {
        if (Vars.state.isCampaign) Vars.state.planet?.let { "planet#${"${it.name}"}}" } ?: "#unknow"
        else {
          "save#${Vars.control.saves.current.file.nameWithoutExtension().replace("-", "_")}"
        }
      }
      else null //unsave when remote
    }

    fun save(){
      val currPlanet = slotName()?: return

      blocks.forEachIndexed { i, block ->
        He.global.put("${currPlanet}-fast-slot-$id-$i", block?.name?:"!empty")
      }
    }

    fun load(){
      val currPlanet = slotName()?: return

      for (i in 0 until blocks.size) {
        val name = He.global.getString("${currPlanet}-fast-slot-$id-$i", "!empty")

        blocks[i] = Vars.content.block(name)
      }
    }

    override fun act(delta: Float) {
      super.act(delta)
      if (block != null && selected && currBlock != block) currentSlot = null

      if (!Core.scene.hasKeyboard() && Core.input.keyTap(numKey)) fireClick()
    }

    override fun draw() {
      super.draw()

      background?.draw(x, y, width, height)

      val fullSize = Scl.scl(32f)
      val smallSize = Scl.scl(14f)
      val prog = Interp.pow3Out.apply(invAnimateProgress)
      if (invAnimateActivating) {
        val last = blocks[(invPage + 2)%3]
        val center = blocks[invPage%3]
        val next = blocks[(invPage + 1)%3]

        last?.also {
          val s = Mathf.lerp(fullSize, smallSize, prog)
          Draw.rect(
            it.uiIcon,
            Mathf.lerp(x + width / 2, x + smallSize/2, prog),
            Mathf.lerp(y + height / 2, y + smallSize/2, prog),
            s, s
          )
        }
        center?.also {
          val s = Mathf.lerp(smallSize, fullSize, prog)
          Draw.rect(
            it.uiIcon,
            Mathf.lerp(x + width - smallSize/2, x + width / 2, prog),
            Mathf.lerp(y + smallSize/2, y + height / 2, prog),
            s, s
          )
        }
        next?.also {
          Draw.rect(
            it.uiIcon,
            Mathf.lerp(x + smallSize/2, x + width - smallSize/2, prog),
            y + smallSize/2,
            smallSize, smallSize
          )
        }
      }
      else {
        val left = blocks[(invPage + 2)%3]
        val center = blocks[(invPage)%3]
        val right = blocks[(invPage + 1)%3]

        left?.also { Draw.rect(it.uiIcon, x + smallSize/2, y + smallSize/2, smallSize, smallSize) }
        center?.also {
          Draw.rect(
            it.uiIcon, x + width / 2, y + height / 2,
            fullSize, fullSize
          )
        }
         right?.also { Draw.rect(it.uiIcon, x + width - smallSize/2, y + smallSize/2, smallSize, smallSize) }
      }

      if (selected) {
        Lines.stroke(Scl.scl(4f), Pal.accent)
        Lines.rect(x, y, width, height)
      }

      if (Core.app.isDesktop) {
        Fonts.outline.draw(
          numKey.value,
          x, y + height - Fonts.outline.capHeight*0.65f,
          Color.white, 0.65f, true,
          Align.left
        )
      }

      Draw.reset()
    }
  }
}

private class ToolEntry(
  val icon: Prov<Drawable>,
  val checked: Boolp?,
  val hoverTip: Prov<String>?,
  val listener: Runnable,
) {
  var shown: Boolean = true
}