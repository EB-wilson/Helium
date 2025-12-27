package helium.ui.fragments.placement

import arc.Core
import arc.math.Interp
import arc.scene.Group
import arc.scene.ui.layout.Table
import helium.He
import helium.ui.elements.HeCollapser
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.entities.Units
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Tex
import mindustry.ui.Displayable
import sun.nio.cs.ISO_8859_5
import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.charset.StandardCharsets

class HoveringInfoFrag {
  private var hovering: Displayable? = null
  private var nextFlowBuild: Building? = null

  private var lastHover: Displayable? = null
  private var lastTeam: Team? = null

  private lateinit var topTable: Table
  private lateinit var infoPane: Table

  fun build(parent: Group){
    parent.fill { topLevel ->
      topTable = topLevel

      topLevel.bottom().left().visible { He.config.useFixedHoveringInfoPane && Vars.ui.hudfrag.shown }

      topLevel.table { info ->
        info.add(
          HeCollapser(collX = true, collY = true, collapsed = true){ table ->
            infoPane = table
            buildInfoTable(table)
          }.setCollapsed { !checkDisplaying() }
            .setDuration(0.25f, Interp.pow3Out)
        )
      }.fill()
    }
  }

  fun currHovering() = hovering

  private fun buildInfoTable(infoTab: Table) {
    infoTab.table(Tex.buttonEdge3) { top ->
      topTable = top
      top.add(Table()).growX().update { topTable ->

        val hovered: Displayable? = hovering
        if (hovered == null || (hovered == lastHover && lastTeam == Vars.player.team())) return@update

        lastHover = hovered
        lastTeam = Vars.player.team()

        topTable.clear()
        topTable.top().left().margin(5f)
        hovered.display(topTable)
      }
    }
  }

  fun checkDisplaying(): Boolean {
    hovering = hovered()
    return hovering != null
  }

  //Copy form source code
  fun hovered(): Displayable? {
    val v = infoPane.stageToLocalCoordinates(Core.input.mouse())

    //if the mouse intersects the table or the UI has the mouse, no hovering can occur
    if (Core.scene.hasMouse(Core.input.mouseX().toFloat(), Core.input.mouseY().toFloat())
    || (v.x < infoPane.width && v.y < infoPane.height))
      return null

    //check for a unit
    val unit = Units.closestOverlap(
      Vars.player.team(),
      Core.input.mouseWorldX(),
      Core.input.mouseWorldY(),
      5f
    ) { u -> !u!!.isLocal && u.displayable() }
    //if cursor has a unit, display it
    if (unit != null) return unit

    //check tile being hovered over
    val hoverTile = Vars.world.tileWorld(Core.input.mouseWorld().x, Core.input.mouseWorld().y)
    if (hoverTile != null) {
      //if the tile has a building, display it
      if (hoverTile.build != null && hoverTile.build.displayable() && !hoverTile.build.inFogTo(Vars.player.team())) {
        return hoverTile.build.also { nextFlowBuild = it }
      }

      //if the tile has a drop, display the drop
      if ((hoverTile.drop() != null && hoverTile.block() === Blocks.air) || hoverTile.wallDrop() != null || hoverTile.floor().liquidDrop != null) {
        return hoverTile
      }
    }

    return null
  }
}