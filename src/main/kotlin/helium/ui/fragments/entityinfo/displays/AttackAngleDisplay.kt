package helium.ui.fragments.entityinfo.displays

import arc.Core
import arc.graphics.g2d.Draw
import arc.math.Interp
import arc.math.Mathf
import arc.scene.ui.layout.Table
import arc.util.Scaling
import arc.util.Tmp
import helium.He
import helium.graphics.DrawUtils
import helium.ui.elements.HeCollapser
import helium.ui.fragments.entityinfo.*
import helium.util.enterSt
import helium.util.exitSt
import mindustry.gen.Icon
import mindustry.gen.Posc
import mindustry.gen.Unitc
import mindustry.graphics.Layer
import mindustry.logic.Ranged
import mindustry.ui.Styles
import mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild

class AttackAngleDisplayProvider: DisplayProvider<Ranged, AttackAngleDisplay>(){
  override val typeID: Int get() = 826592238
  override val hoveringOnly: Boolean get() = true

  override fun targetGroup() = listOf(
    TargetGroup.build,
    TargetGroup.unit
  )
  override fun valid(entity: Posc): Boolean = entity is Unitc || entity is BaseTurretBuild
  override fun enabled() = He.config.enableAttackAngleDisplay

  override fun provide(
    entity: Ranged,
    id: Int
  ) = AttackAngleDisplay(entity, id).apply {
    when(entity) {
      is Unitc -> isUnit = true
      is BaseTurretBuild -> isTurret = true
    }
  }

  override fun buildConfig(table: Table) {
    table.image(Icon.downOpen).size(80f).scaling(Scaling.fit)
    table.row()
    table.add(HeCollapser(collX = false, collY = true, collapsed = true){
      it.add(Core.bundle["infos.attackAngle"], Styles.outlineLabel)
    }.also { col ->
      col.setDuration(0.35f, Interp.pow2Out)
      table.parent.enterSt { col.setCollapsed(false) }
      table.parent.exitSt { col.setCollapsed(true) }
    })
  }
}

class AttackAngleDisplay(
  entity: Ranged,
  id: Int
): WorldDrawOnlyDisplay<Ranged>(entity, id) {
  override val typeID: Int get() = 826592238

  var isUnit = false
  var isTurret = false

  override fun update(delta: Float, alpha: Float, isHovering: Boolean, isHolding: Boolean) { /*no action*/ }

  override fun draw(alpha: Float) {
    Draw.z(Layer.light + 5)
    Draw.color(entity.team().color, (0.1f + Mathf.absin(8f, 0.15f))*alpha)
    if (isTurret && entity is TurretBuild) drawTurretAttackCone(entity)
    else if (isUnit) drawUnitAttackCone(entity as Unitc)
  }

  private fun drawUnitAttackCone(unit: Unitc) {
    unit.mounts().forEach { weapon ->
      val type = weapon.weapon
      val coneAngle = type.shootCone
      val weaponRot = if (weapon.rotate) weapon.rotation else type.baseRotation
      val dir = weaponRot + unit.rotation()
      val off = Tmp.v1.set(type.x, type.y).rotate(unit.rotation() - 90)
      off.add(Tmp.v2.set(type.shootX, type.shootY).rotate(unit.rotation() - 90 + weaponRot))

      val dx = unit.x() + off.x
      val dy = unit.y() + off.y

      DrawUtils.circleFan(
        dx, dy, type.range(),
        coneAngle*2, dir - coneAngle
      )
    }
  }

  private fun drawTurretAttackCone(turretBuild: TurretBuild) {
    val block = turretBuild.block as Turret
    val dir = turretBuild.buildRotation()
    val coneAngle = block.shootCone
    val offset = Tmp.v1.set(block.shootX, block.shootY).rotate(turretBuild.buildRotation() - 90)

    DrawUtils.circleFan(
      turretBuild.x() + offset.x, turretBuild.y() + offset.y,
      block.range, coneAngle*2, dir - coneAngle
    )
  }
}