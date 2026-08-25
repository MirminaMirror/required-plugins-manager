@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsManagerBundle
import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.ide.HelpTooltip
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.ide.plugins.newui.PluginsViewCustomizer
import com.intellij.ide.plugins.newui.TagComponent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.labels.LinkListener
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.ui.JBUI
import java.awt.event.KeyEvent
import javax.swing.JComponent

/**
 * 带有文字基线对齐支持的标签复合容器。
 *
 * 覆写 [getBaseline] 将基线精确委托给内部第一个可见的 [TagComponent]，确保与插件名称 100% 保持在同一水平线上，杜绝垂直偏移与重叠。
 */
private class BaselineTagPanel : NonOpaquePanel(HorizontalLayout(JBUI.scale(4))) {
  override fun getBaseline(width: Int, height: Int): Int {
    val first = components.firstOrNull { it.isVisible } as? JComponent ?: return -1
    return first.getBaseline(first.preferredSize.width, first.preferredSize.height)
  }
}

/**
 * 原生插件卡片定制器。
 *
 * 负责在官方原生卡片右键菜单中追加 Mark/Unmark 操作并在卡片上挂载渲染 [TagComponent] "Required" 徽标与版本提示。
 */
internal object RequiredListPluginComponentCustomizer : PluginsViewCustomizer.ListPluginComponentCustomizer {
  
  override fun processListPluginComponent(listPluginComponent: ListPluginComponent) {
    updateRequiredTag(listPluginComponent)
  }
  
  override fun processCreateButtons(listPluginComponent: ListPluginComponent) {
    updateRequiredTag(listPluginComponent)
  }
  
  override fun processRemoveButtons(listPluginComponent: ListPluginComponent) {}
  
  override fun processUpdateEnabledState(listPluginComponent: ListPluginComponent) {
    updateRequiredTag(listPluginComponent)
  }
  
  /**
   * 在插件卡片弹出菜单中注入"设为项目必需插件" / "取消项目必需插件标记"动作。
   *
   * @param listPluginComponent 当前触发右键菜单的卡片组件。
   * @param group 官方卡片弹出菜单动作组。
   * @param selection 当前选中的卡片组件列表（支持批量操作）。
   */
  override fun processCreatePopupMenu(
    listPluginComponent: ListPluginComponent,
    group: DefaultActionGroup,
    selection: List<ListPluginComponent>,
  ) {
    if (selection.isEmpty()) return
    val session = resolveCurrentSession(listPluginComponent)
    val reqMap = session?.getAll() ?: RequiredPluginsStore.load(resolveCurrentProject(listPluginComponent))
    val allRequired = selection.all { it.getPluginModel().pluginId.idString in reqMap }
    val noneRequired = selection.none { it.getPluginModel().pluginId.idString in reqMap }
    
    group.addSeparator()
    
    if (!allRequired) {
      group.add(DumbAwareAction.create(RequiredPluginsManagerBundle.message("plugins.action.mark.required")) {
        listPluginComponent.updateRequiredPlugins { stagedMap ->
          for (item in selection) {
            val id = item.getPluginModel().pluginId.idString
            stagedMap.putIfAbsent(id, DependencyOnPlugin(id, null, null))
          }
        }
        selection.forEach { updateRequiredTag(it) }
      })
    }
    
    if (!noneRequired) {
      group.add(DumbAwareAction.create(RequiredPluginsManagerBundle.message("plugins.action.unmark.required")) {
        listPluginComponent.updateRequiredPlugins { stagedMap ->
          for (item in selection) {
            stagedMap.remove(item.getPluginModel().pluginId.idString)
          }
        }
        selection.forEach { updateRequiredTag(it) }
      })
    }
  }
  
  override fun processHandleKeyAction(
    listPluginComponent: ListPluginComponent,
    event: KeyEvent,
    selection: List<ListPluginComponent>,
  ) {
  }
}

/**
 * 为插件卡片动态刷新 "Required" [TagComponent] 徽标与版本区间 Tooltip 提示。
 *
 * 显隐与 Tooltip 闭环管理：
 * 1. 当当前插件为必需插件（`isReq == true`）：
 *    - 若卡片原本无原生标签，直接将唯一的 `[Required]` 注入 `myTagComponent` 插槽；
 *    - 若卡片原本存在官方原生标签（如 `Ultimate` / `Editor`），通过 [BaselineTagPanel] 复合容器包装并排展示，绝不抢占或覆盖原生标签；
 *    - 版本 Tooltip **100% 仅挂载在专属的 Required 徽标上**，杜绝误挂在原生标签上；
 * 2. 当当前插件非必需（`isReq == false`）：
 *    - 立即卸载专属 Required 徽标与 Tooltip；
 *    - 若存在原生标签，完整解包并恢复原生标签的单独立项渲染；
 * 3. 触发布局重排与重绘。
 *
 * @param component 待更新徽标的目标 [ListPluginComponent] 卡片。
 */
internal fun updateRequiredTag(component: ListPluginComponent) {
  val descriptor = component.getPluginModel().getDescriptor()
  val session = resolveCurrentSession(component)
  val reqMap = session?.getAll() ?: RequiredPluginsStore.load(resolveCurrentProject(component))
  val reqData = reqMap[descriptor.pluginId.idString]
  val isReq = reqData != null
  
  val layout = component.layout ?: return
  val tagField = ReflectionCache.findField(layout.javaClass, "myTagComponent") ?: return
  val currentTagSlot = runCatching { tagField.get(layout) as? JComponent }.getOrNull()
  
  if (isReq) {
    val reqTag: TagComponent
    val searchListener = ReflectionCache.getFieldValue<LinkListener<Any>>(component, "mySearchListener")
    if (currentTagSlot is BaselineTagPanel) {
      val existingReqTag =
        currentTagSlot.components.filterIsInstance<TagComponent>().firstOrNull { it.text == TAG_REQUIRED_NAME }
      if (existingReqTag != null) {
        reqTag = existingReqTag
        reqTag.isVisible = true
        if (searchListener != null) {
          reqTag.setListener(searchListener, reqTag)
        }
      } else {
        reqTag = createRequiredTag(component)
        currentTagSlot.add(reqTag)
      }
    } else if (currentTagSlot is TagComponent) {
      if (currentTagSlot.text == TAG_REQUIRED_NAME) {
        reqTag = currentTagSlot
        reqTag.isVisible = true
        if (searchListener != null) {
          reqTag.setListener(searchListener, reqTag)
        }
      } else {
        component.remove(currentTagSlot)
        reqTag = createRequiredTag(component)
        val panel = BaselineTagPanel()
        panel.add(currentTagSlot)
        panel.add(reqTag)
        runCatching { tagField.set(layout, panel) }
        component.add(panel)
      }
    } else {
      reqTag = createRequiredTag(component)
      runCatching { tagField.set(layout, reqTag) }
      component.add(reqTag)
    }
    
    val tooltipText = getRequiredTooltipText(reqData)
    HelpTooltip().setTitle(HtmlChunk.text(tooltipText)).installOn(reqTag)
  } else {
    if (currentTagSlot is BaselineTagPanel) {
      val reqTag =
        currentTagSlot.components.filterIsInstance<TagComponent>().firstOrNull { it.text == TAG_REQUIRED_NAME }
      if (reqTag != null) {
        HelpTooltip.dispose(reqTag)
        currentTagSlot.remove(reqTag)
      }
      val remaining = currentTagSlot.components.filterIsInstance<JComponent>()
      if (remaining.size == 1) {
        val officialTag = remaining[0]
        currentTagSlot.remove(officialTag)
        component.remove(currentTagSlot)
        runCatching { tagField.set(layout, officialTag) }
        component.add(officialTag)
      } else if (remaining.isEmpty()) {
        component.remove(currentTagSlot)
        runCatching { tagField.set(layout, null) }
      }
    } else if (currentTagSlot is TagComponent && currentTagSlot.text == TAG_REQUIRED_NAME) {
      HelpTooltip.dispose(currentTagSlot)
      component.remove(currentTagSlot)
      runCatching { tagField.set(layout, null) }
    }
  }
  
  component.revalidate()
  component.repaint()
}

/**
 * 根据插件依赖配置的版本约束构建卡片 Tooltip 提示文本。
 *
 * @param dependency 必需插件依赖配置项。
 * @return 格式化后的 Tooltip 文本内容。
 */
private fun getRequiredTooltipText(dependency: DependencyOnPlugin): String {
  val min = dependency.rawMinVersion?.trim()?.ifEmpty { null }
  val max = dependency.rawMaxVersion?.trim()?.ifEmpty { null }
  return when {
    min != null && max != null -> RequiredPluginsManagerBundle.message("plugins.tag.tooltip.range", min, max)
    min != null -> RequiredPluginsManagerBundle.message("plugins.tag.tooltip.min", min)
    max != null -> RequiredPluginsManagerBundle.message("plugins.tag.tooltip.max", max)
    else -> RequiredPluginsManagerBundle.message("plugins.details.required.checkbox")
  }
}

/**
 * 为插件卡片创建配置了点击搜索监听器与微型字体的 "Required" [TagComponent] 徽标。
 *
 * @param component 宿主插件卡片。
 * @return 构造完成并绑定了搜索监听器的 [TagComponent] 实例。
 */
private fun createRequiredTag(component: ListPluginComponent): TagComponent {
  val reqTag = PluginManagerConfigurable.setTinyFont(TagComponent(TAG_REQUIRED_NAME))
  val searchListener = ReflectionCache.getFieldValue<LinkListener<Any>>(component, "mySearchListener")
  if (searchListener != null) {
    reqTag.setListener(searchListener, reqTag)
  }
  return reqTag
}
