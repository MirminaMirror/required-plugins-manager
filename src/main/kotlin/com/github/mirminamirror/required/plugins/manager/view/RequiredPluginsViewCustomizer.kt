@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsManagerBundle
import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.ide.plugins.InstalledPluginsTabSearchResultPanel
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginNode
import com.intellij.ide.plugins.marketplace.MarketplaceRequests
import com.intellij.ide.plugins.newui.MyPluginModel
import com.intellij.ide.plugins.newui.PluginsViewCustomizer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * 必需插件管理器官方插件视图定制器。
 *
 * 本类实现 IntelliJ Platform 官方扩展点 [PluginsViewCustomizer]，作为视图层总装配入口，
 * 负责在官方原生的 Plugins 配置页中协调各定制模块完成零侵入式的 UI 与功能注入：
 * 1. **Marketplace 顶部未安装分组**：提供未安装必需插件描述符及 "Install All" 批量安装（[setupMissingRequiredGroupActions]）；
 * 2. **Installed 顶部未启用分组**：维护已禁用必需插件独立分组及 "Enable All" 批量启用（[setupInstalledDisabledGroup]）；
 * 3. **详情面板版本配置注入**：委托至 [RequiredPluginDetailsCustomizer] 注入复选框与 Min/Max 版本区间面板；
 * 4. **卡片徽标与右键菜单**：委托至 [RequiredListPluginComponentCustomizer] 挂载 "Required" 徽标、Tooltip 与批量标记/取消动作；
 * 5. **搜索栏专用标签过滤**：注入 `/tag:Required` 选项并动态过滤结果容器（[bindSearchFilter]）；
 * 6. **Configurable 事务生命周期管控**：挂载 [StagedRequiredPluginsSession] 自动对接平台 Apply/OK/Cancel/Reset 事务闭环。
 */
class RequiredPluginsViewCustomizer : PluginsViewCustomizer {
  
  /**
   * 提供 Marketplace 界面顶部的未安装必需插件分组描述符。
   *
   * @return 包含未安装必需插件列表的分组描述符；若无需提示则返回 null。
   */
  override fun getInternalPluginsGroupDescriptor(): PluginsViewCustomizer.PluginsGroupDescriptor? {
    val project = resolveCurrentProject()
    val session = resolveCurrentSession()
    val reqMap = session?.getAll() ?: RequiredPluginsStore.load(project)
    if (reqMap.isEmpty()) return null
    
    val uninstalledReqIds = reqMap.keys.filter { idString ->
      val pluginId = PluginId.getId(idString)
      !PluginManagerCore.isPluginInstalled(pluginId)
    }
    if (uninstalledReqIds.isEmpty()) return null
    
    val uninstalledReqDescriptors = uninstalledReqIds.map { idString ->
      val pluginId = PluginId.getId(idString)
      PluginManagerCore.getPlugin(pluginId)
      ?: runCatching {
        MarketplaceRequests.getInstance().getLastCompatiblePluginUpdateModel(pluginId)?.getDescriptor()
      }.getOrNull()
      ?: PluginNode(pluginId).apply { name = idString }
    }
    
    return PluginsViewCustomizer.PluginsGroupDescriptor(
      name = RequiredPluginsManagerBundle.message("plugins.group.uninstalled.required"),
      plugins = uninstalledReqDescriptors,
      showAllQuery = TAG_REQUIRED_QUERY,
    )
  }
  
  /**
   * 获取插件详情面板定制器。
   *
   * @param pluginModel 当前界面的插件模型。
   * @return 负责在详情页注入必需复选框与版本面板的定制器实例。
   */
  override fun getPluginDetailsCustomizer(pluginModel: MyPluginModel): PluginsViewCustomizer.PluginDetailsCustomizer =
    RequiredPluginDetailsCustomizer()
  
  /**
   * 获取插件列表卡片定制器。
   *
   * @return 负责在卡片注入徽标、Tooltip 与右键菜单的定制器单例。
   */
  override fun getListPluginComponentCustomizer(): PluginsViewCustomizer.ListPluginComponentCustomizer =
    RequiredListPluginComponentCustomizer
  
  /**
   * 处理并增强官方插件配置页根容器 [PluginManagerConfigurable]。
   *
   * @param pluginManagerConfigurable 官方插件配置页实例。
   */
  override fun processConfigurable(pluginManagerConfigurable: PluginManagerConfigurable) {
    val panel = ReflectionCache.getFieldValue(pluginManagerConfigurable, "myPanel") ?: return
    val rootComponent =
      ReflectionCache.getFieldValue<JComponent>(panel, "cardPanel")
      ?: ReflectionCache.getFieldValue<JComponent>(panel, "mainPanel")
      ?: ReflectionCache.invokeMethod<JComponent>(pluginManagerConfigurable, "createComponent")
    val project = resolveCurrentProject(rootComponent)
    val parentDisposable = (panel as? Disposable) ?: (project as? Disposable) ?: Disposer.newDisposable()
    
    val facade = ReflectionCache.getFieldValue(panel, "pluginModelFacade")
    val model = facade?.let { ReflectionCache.invokeMethod(it, "getModel") }
    val session = StagedRequiredPluginsSession(project, model, rootComponent)
    rootComponent?.putClientProperty(KEY_STAGED_SESSION, session)
    Disposer.register(parentDisposable, session)
    
    if (rootComponent != null) {
      session.addChangeListener {
        SwingUtilities.invokeLater {
          refreshAllListComponents(rootComponent)
          syncPluginCustomizations(parentDisposable, project)
          rootComponent.revalidate()
          rootComponent.repaint()
        }
      }
    }
    
    syncPluginCustomizations(parentDisposable, project)
    
    runCatching {
      val installedTab = ReflectionCache.getFieldValue(panel, "installedTab")
      if (installedTab != null) {
        val searchTextField = ReflectionCache.getFieldValue<SearchTextField>(installedTab, "searchTextField")
        val searchPanel = ReflectionCache.getFieldValue<Any>(installedTab, "searchPanel")
        
        if (searchPanel is InstalledPluginsTabSearchResultPanel) {
          val existingGroup = ReflectionCache.getFieldValue<DefaultActionGroup>(searchPanel, "mySearchActionGroup")
          val originalActions = existingGroup?.getChildren(ActionManager.getInstance())?.toList().orEmpty()
          ReflectionCache.findField(searchPanel.javaClass, "mySearchActionGroup")
            ?.set(searchPanel, DefaultActionGroup(originalActions))
        }
        
        val installedSearchGroup =
          ReflectionCache.getFieldValue<DefaultActionGroup>(installedTab, "installedSearchGroup")
        if (installedSearchGroup != null && searchTextField != null) {
          val originalActions = installedSearchGroup.getChildren(ActionManager.getInstance()).toList()
          installedSearchGroup.removeAll()
          installedSearchGroup.add(object : ToggleAction(
            RequiredPluginsManagerBundle.message("plugins.search.option.required"),
          ) {
            override fun isSelected(e: AnActionEvent): Boolean =
              searchTextField.text.contains(TAG_REQUIRED_QUERY, ignoreCase = true)
            
            override fun setSelected(e: AnActionEvent, state: Boolean) {
              toggleSearchOption(installedTab, searchTextField, state)
            }
            
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
          })
          installedSearchGroup.addSeparator()
          installedSearchGroup.addAll(originalActions)
        }
        
        if (searchTextField != null && searchPanel != null) {
          val groupComponent = ReflectionCache.getFieldValue<JComponent>(searchPanel, "myPanel")
          if (groupComponent != null) {
            bindSearchFilter(searchTextField, searchPanel, groupComponent, parentDisposable)
          }
        }
        
        val installedPanel = ReflectionCache.getFieldValue<JComponent>(installedTab, "installedPanel")
        if (installedPanel != null) {
          setupInstalledDisabledGroup(installedPanel, parentDisposable)
        }
      }
      
      val marketplaceTab = ReflectionCache.getFieldValue(panel, "marketplaceTab")
      if (marketplaceTab != null) {
        val searchTextField = ReflectionCache.getFieldValue<SearchTextField>(marketplaceTab, "searchTextField")
        val searchPanel = ReflectionCache.getFieldValue<Any>(marketplaceTab, "searchPanel")
        if (searchTextField != null && searchPanel != null) {
          val groupComponent = ReflectionCache.getFieldValue<JComponent>(searchPanel, "myPanel")
          if (groupComponent != null) {
            bindSearchFilter(searchTextField, searchPanel, groupComponent, parentDisposable)
          }
        }
        
        val marketplacePanel = ReflectionCache.getFieldValue<JComponent>(marketplaceTab, "marketplacePanel")
        if (marketplacePanel != null) {
          setupMissingRequiredGroupActions(marketplacePanel, parentDisposable)
        }
      }
    }
  }
}
