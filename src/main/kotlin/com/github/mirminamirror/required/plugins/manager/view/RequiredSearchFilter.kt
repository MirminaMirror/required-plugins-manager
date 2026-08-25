@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.ide.plugins.newui.PluginUiModel
import com.intellij.ide.plugins.newui.PluginUiModelAdapter
import com.intellij.ide.plugins.newui.PluginsGroup
import com.intellij.ide.plugins.newui.PluginsGroupComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/** 容器 ClientProperty 键名，用于防止重复挂载搜索监听器 */
private const val KEY_SEARCH_LISTENER_ATTACHED = "required.search.listener.attached"

/**
 * 将必需插件搜索过滤器绑定到指定的搜索框与结果容器上。
 *
 * 监听策略：
 * - 使用 ClientProperty 确保对同一个组件容器仅挂载一次监听器；
 * - 监听搜索框文本变动（[DocumentAdapter]）与官方异步检索结果渲染加载（[ContainerListener]），
 *   在 Swing 事件调度线程（EDT）中通过 [SwingUtilities.invokeLater] 触发搜索快照补全。
 *
 * @param searchTextField 关联的搜索文本框。
 * @param searchPanel 搜索结果面板（`InstalledPluginsTabSearchResultPanel` 等）。
 * @param groupComponent 承载搜索结果卡片的根容器组件。
 * @param parentDisposable 挂载注销生命周期的父级 [Disposable]。
 */
internal fun bindSearchFilter(
  searchTextField: SearchTextField,
  searchPanel: Any,
  groupComponent: JComponent,
  parentDisposable: Disposable,
) {
  if (groupComponent.getClientProperty(KEY_SEARCH_LISTENER_ATTACHED) == true) return
  groupComponent.putClientProperty(KEY_SEARCH_LISTENER_ATTACHED, true)
  
  val filterAction = { applySearchFilter(searchTextField, searchPanel, groupComponent) }
  val documentListener = object : DocumentAdapter() {
    override fun textChanged(e: DocumentEvent) {
      SwingUtilities.invokeLater(filterAction)
    }
  }
  val containerListener = object : ContainerListener {
    override fun componentAdded(e: ContainerEvent?) {
      SwingUtilities.invokeLater(filterAction)
    }
    
    override fun componentRemoved(e: ContainerEvent?) {
    }
  }
  searchTextField.addDocumentListener(documentListener)
  groupComponent.addContainerListener(containerListener)
  Disposer.register(parentDisposable) {
    searchTextField.removeDocumentListener(documentListener)
    groupComponent.removeContainerListener(containerListener)
    groupComponent.putClientProperty(KEY_SEARCH_LISTENER_ATTACHED, null)
  }
}

/**
 * 执行搜索结果过滤，展示当前项目标记为必需的全部插件卡片（含已安装未启用、付费及官方标签插件）。
 *
 * 当搜索框包含 `/tag:Required` 查询标记时激活：
 * 1. 检查搜索面板加载状态（`isLoading`），确保在官方异步 handleQuery 结算并渲染后再进行同步，杜绝时序竞争；
 * 2. 委托 [syncSearchGroupRequiredPlugins] 进行原子级模型增删（`removeFromGroup` / `addToGroup`），彻底消除死卡片与空行残留（Zero Gap）；
 * 3. 触发布局重算（`revalidate`）与重绘（`repaint`）。
 *
 * @param searchTextField 关联的搜索输入框。
 * @param searchPanel 搜索结果面板实例。
 * @param groupComponent 搜索结果容器。
 */
internal fun applySearchFilter(searchTextField: SearchTextField, searchPanel: Any?, groupComponent: JComponent) {
  val project = resolveCurrentProject(groupComponent)
  val query = searchTextField.text.trim()
  val isRequiredFilter = query.contains(TAG_REQUIRED_QUERY, ignoreCase = true)
  val listComponents = groupComponent.findComponents<ListPluginComponent>()
  
  if (!isRequiredFilter) {
    for (component in listComponents) {
      component.isVisible = true
    }
    groupComponent.revalidate()
    groupComponent.repaint()
    return
  }
  
  val isLoading = searchPanel?.let { ReflectionCache.getFieldValue<Boolean>(it, "isLoading") } == true
  val searchGroup = searchPanel?.let { ReflectionCache.getFieldValue<PluginsGroup>(it, "group") }
  val session = resolveCurrentSession(groupComponent, project)
  val reqMap = session?.getAll() ?: RequiredPluginsStore.load(project)
  
  if (!isLoading && searchGroup != null && groupComponent is PluginsGroupComponent && searchGroup.ui != null) {
    syncSearchGroupRequiredPlugins(groupComponent, searchGroup, reqMap)
  }
}

/**
 * 精确同步搜索结果分组（[PluginsGroup]）与列表容器中的必需插件状态。
 *
 * 核心保障：
 * 1. 彻底清除 [searchGroup] 及其 UI 插件列表（`uiGroup.plugins`）中的所有非必需插件与重复卡片（通过 [PluginsGroupComponent.removeFromGroup]）；
 * 2. 补全因官方底层缺陷遗漏的未启用必需插件（如 `Refactor-X`，通过 [PluginsGroupComponent.addToGroup]）；
 * 3. 动态刷新所有卡片的 Required 徽标与版本 Tooltip；
 * 4. 实时更新标题计数（`titleWithCount`），杜绝死卡片与空行残留（Zero Gap）。
 *
 * @param groupComponent 搜索结果容器组件。
 * @param searchGroup 搜索分组模型。
 * @param reqMap 当前项目必需插件映射表。
 */
private fun syncSearchGroupRequiredPlugins(
  groupComponent: PluginsGroupComponent,
  searchGroup: PluginsGroup,
  reqMap: Map<String, DependencyOnPlugin>,
) {
  val uiGroup = searchGroup.ui ?: return
  
  // 1. 消除 searchGroup 中的重复项（使用 removeFromGroup 彻底清理容器与 layout 列表）
  val seenIds = mutableSetOf<String>()
  val toRemove = mutableListOf<PluginUiModel>()
  for (model in searchGroup.getModels().toList()) {
    val idString = model.pluginId.idString
    if (!seenIds.add(idString)) {
      toRemove.add(model)
    }
  }
  for (model in toRemove) {
    runCatching {
      groupComponent.removeFromGroup(searchGroup, model)
    }
  }
  
  // 2. 补全 searchGroup 中缺失的必需插件（如未启用的 Refactor-X）
  val currentIds = searchGroup.getModels().map { it.pluginId.idString }.toSet()
  for ((idString, _) in reqMap) {
    if (idString !in currentIds) {
      val descriptor = PluginManagerCore.getPlugin(PluginId.getId(idString))
      if (descriptor != null && PluginManagerCore.isPluginInstalled(descriptor.pluginId)) {
        val model = PluginUiModelAdapter(descriptor)
        runCatching {
          groupComponent.addToGroup(searchGroup, model)
        }
      }
    }
  }
  
  // 3. 刷新所有可见卡片的 Required 徽标
  for (component in uiGroup.plugins) {
    updateRequiredTag(component)
  }
  
  // 4. 精准同步标题计数并触发布局重排
  searchGroup.titleWithCount()
  groupComponent.revalidate()
  groupComponent.repaint()
}

/**
 * 切换搜索输入框中的 `/tag:Required` 标签参数并触发官方搜索面板更新。
 *
 * @param tab 选项卡实例（InstalledPluginsTab 或 MarketplacePluginsTab）。
 * @param searchTextField 关联的搜索框组件。
 * @param state 目标勾选状态（true 表示追加标签，false 表示移除标签）。
 */
internal fun toggleSearchOption(tab: Any?, searchTextField: SearchTextField, state: Boolean) {
  val tokens = searchTextField.text.split(' ').filter { it.isNotBlank() && it != TAG_REQUIRED_QUERY }
  val query = if (state) (tokens + TAG_REQUIRED_QUERY).joinToString(" ") else tokens.joinToString(" ")
  searchTextField.text = query
  
  if (tab != null) {
    if (query.isEmpty()) {
      ReflectionCache.invokeMethod(tab, "hideSearchPanel")
    } else {
      ReflectionCache.invokeMethod(tab, "showSearchPanel", String::class to query)
    }
  }
}
