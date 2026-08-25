@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsManagerBundle
import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginsGroupType
import com.intellij.ide.plugins.newui.PluginsGroup
import com.intellij.ide.plugins.newui.UIPluginGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent

/** 容器 ClientProperty 键名，用于存储已安装选项卡中的未启用必需插件分组对象 */
private const val KEY_DISABLED_REQUIRED_GROUP = "required.disabled.group"

/**
 * 为 Installed 选项卡注入并维护顶部的“未启用的必需插件”独立分组。
 *
 * @param installedPanel 承载 Installed 插件分组的 PluginsGroupComponent 容器。
 * @param parentDisposable 绑定的父级生命周期 [Disposable]。
 */
internal fun setupInstalledDisabledGroup(installedPanel: JComponent, parentDisposable: Disposable) {
  bindContainerUpdateListener(installedPanel, parentDisposable) {
    updateInstalledDisabledGroup(installedPanel)
  }
}

/**
 * 刷新并同步 Installed 选项卡顶部的“未启用的必需插件”分组及“全部启用”操作。
 *
 * 业务规则：
 * 1. 严格筛选当前已安装但处于禁用状态（`isPluginInstalled && isDisabled`）的必需插件；
 * 2. 若不存在此类插件，且当前存在顶部分组，则从面板中安全卸载并移除该分组；
 * 3. 若存在此类插件，在顶部（索引 0）注入带有 "Enable All" 超链接操作的 [PluginsGroup]；
 * 4. 点击 "Enable All" 时，批量触发该分组内各卡片的原生启用按钮（`myEnableDisableButton`）。
 *
 * @param installedPanel 承载 Installed 插件分组的容器组件。
 */
internal fun updateInstalledDisabledGroup(installedPanel: JComponent) {
  val project = resolveCurrentProject(installedPanel)
  val session = resolveCurrentSession(installedPanel)
  val reqMap = session?.getAll() ?: RequiredPluginsStore.load(project)
  val disabledReqIds = reqMap.keys.filter { idString ->
    val pluginId = PluginId.getId(idString)
    PluginManagerCore.isPluginInstalled(pluginId) && PluginManagerCore.isDisabled(pluginId)
  }.toSet()
  val existingGroup = installedPanel.getClientProperty(KEY_DISABLED_REQUIRED_GROUP) as? PluginsGroup
  if (existingGroup?.ui != null) {
    @Suppress("UNCHECKED_CAST")
    val groups = ReflectionCache.getFieldValue<Any>(installedPanel, "myGroups") as? MutableList<UIPluginGroup>
    if (groups != null && existingGroup.ui != null) {
      val index = groups.indexOf(existingGroup.ui)
      if (index > 0) {
        val uiGroup = groups.removeAt(index)
        groups.add(0, uiGroup)
        installedPanel.doLayout()
        installedPanel.revalidate()
        installedPanel.repaint()
      }
    }
    return
  }
  if (disabledReqIds.isEmpty()) return
  
  val descriptors = disabledReqIds.mapNotNull { PluginManagerCore.getPlugin(PluginId.getId(it)) }
  if (descriptors.isEmpty()) return
  
  val disabledGroupName = RequiredPluginsManagerBundle.message("plugins.group.disabled.required")
  val group = PluginsGroup(disabledGroupName, PluginsGroupType.INTERNAL)
  @Suppress("DEPRECATION")
  group.addDescriptors(descriptors)
  
  val enableAllLink = LinkLabel<Any?>(
    RequiredPluginsManagerBundle.message("plugins.group.action.enable.all"),
    null,
  )
  enableAllLink.setPaintUnderline(false)
  enableAllLink.border = JBUI.Borders.emptyRight(8)
  enableAllLink.setListener(
    { _, _ ->
      enableAllLink.isEnabled = false
      val uiGroup = group.ui
      val plugins = uiGroup?.plugins.orEmpty()
      for (comp in plugins) {
        val enableBtn = ReflectionCache.getFieldValue<AbstractButton>(comp, "myEnableDisableButton")
        if (enableBtn != null && enableBtn.isVisible && enableBtn.isEnabled && !enableBtn.isSelected) {
          enableBtn.doClick()
        }
      }
    },
    null
  )
  group.mainAction = enableAllLink
  
  ReflectionCache.invokeMethod(
    installedPanel,
    "addGroup",
    PluginsGroup::class to group,
    Int::class to 0,
  )
  installedPanel.putClientProperty(KEY_DISABLED_REQUIRED_GROUP, group)
  installedPanel.revalidate()
  installedPanel.repaint()
}

/**
 * 为 Marketplace 主界面的“未安装的必需插件”分组注入并维护“全部安装”头部快捷操作按钮。
 *
 * @param marketplacePanel 承载 Marketplace 插件分组的容器组件。
 * @param parentDisposable 绑定的父级生命周期 [Disposable]。
 */
internal fun setupMissingRequiredGroupActions(marketplacePanel: JComponent, parentDisposable: Disposable) {
  bindContainerUpdateListener(marketplacePanel, parentDisposable) {
    updateMissingRequiredGroupHeader(marketplacePanel)
  }
}

/**
 * 刷新并挂载“未安装的必需插件”分组头部的“全部安装”按钮（原生 [PluginsGroup.mainAction] 机制）。
 *
 * @param marketplacePanel 承载 Marketplace 插件分组的容器组件。
 */
internal fun updateMissingRequiredGroupHeader(marketplacePanel: JComponent) {
  val groups = ReflectionCache.getFieldValue<List<*>>(marketplacePanel, "myGroups") ?: return
  val missingGroup =
    groups.filterIsInstance<PluginsGroup>().firstOrNull { it.type == PluginsGroupType.INTERNAL } ?: return
  val uiGroup = missingGroup.ui ?: return
  val plugins = uiGroup.plugins
  
  val hasInstallable = plugins.any { comp ->
    val installBtn = ReflectionCache.getFieldValue<JButton>(comp, "myInstallButton")
    installBtn != null && installBtn.isVisible && installBtn.isEnabled
  }
  
  if (!hasInstallable) {
    missingGroup.mainAction = null
    uiGroup.panel?.revalidate()
    uiGroup.panel?.repaint()
    return
  }
  
  if (missingGroup.mainAction == null) {
    val linkButton = LinkLabel<Any?>(
      RequiredPluginsManagerBundle.message("plugins.group.action.install.all"),
      null
    ).apply {
      setPaintUnderline(false)
      border = JBUI.Borders.emptyRight(8)
      setListener({ _, _ ->
                    isEnabled = false
                    for (comp in plugins) {
                      val installBtn = ReflectionCache.getFieldValue<JButton>(comp, "myInstallButton")
                      if (installBtn != null && installBtn.isVisible && installBtn.isEnabled) {
                        installBtn.doClick()
                      }
                    }
                  }, null)
    }
    missingGroup.mainAction = linkButton
  } else {
    missingGroup.mainAction?.isEnabled = true
  }
  
  uiGroup.panel?.revalidate()
  uiGroup.panel?.repaint()
}
