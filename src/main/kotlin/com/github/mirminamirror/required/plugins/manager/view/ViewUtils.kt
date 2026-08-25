@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.intellij.ide.DataManager
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import javax.swing.SwingUtilities

/** 官方原生卡片渲染与搜索过滤所使用的 "Required" 标签名称 */
internal const val TAG_REQUIRED_NAME = "Required"

/** 搜索框过滤参数 `/tag:Required` */
internal const val TAG_REQUIRED_QUERY = "/tag:$TAG_REQUIRED_NAME"

/** 版本约束输入变更的防抖延迟时间（毫秒） */
internal const val SAVE_DEBOUNCE_DELAY_MS = 500

/**
 * 深度优先递归查找容器及其子容器中所有符合目标类型 [T] 的组件列表。
 *
 * @return 匹配的组件列表。
 */
internal inline fun <reified T : Component> Container.findComponents(): List<T> =
  findComponentsByType(this, T::class.java)

/**
 * 深度优先递归收集指定类型的组件。
 *
 * @param container 待遍历的父容器。
 * @param targetClass 目标组件类型 Class。
 * @return 匹配的组件列表。
 */
internal fun <T : Component> findComponentsByType(container: Container, targetClass: Class<T>): List<T> {
  val result = mutableListOf<T>()
  fun collect(c: Container) {
    for (child in c.components) {
      if (targetClass.isInstance(child)) {
        @Suppress("UNCHECKED_CAST")
        result.add(child as T)
      }
      if (child is Container) {
        collect(child)
      }
    }
  }
  collect(container)
  return result
}

/**
 * 监听容器组件变动并在 EDT 中触发更新动作，同时将监听器注销绑定至 [parentDisposable]。
 *
 * @param container 目标监听容器。
 * @param parentDisposable 挂载注销生命周期的父级 [Disposable]。
 * @param action 变动时执行的回调。
 */
internal fun bindContainerUpdateListener(
  container: Container,
  parentDisposable: Disposable,
  action: () -> Unit,
) {
  SwingUtilities.invokeLater(action)
  
  val containerListener = object : ContainerListener {
    override fun componentAdded(e: ContainerEvent?) = SwingUtilities.invokeLater(action)
    override fun componentRemoved(e: ContainerEvent?) = SwingUtilities.invokeLater(action)
  }
  container.addContainerListener(containerListener)
  Disposer.register(parentDisposable) {
    container.removeContainerListener(containerListener)
  }
}

/**
 * 遍历容器及其子容器中的所有 [ListPluginComponent]，即时原位刷新其卡片上的 "Required" 徽标与版本提示 Tooltip。
 *
 * @param container 待遍历刷新的根 Swing 容器。
 */
internal fun refreshAllListComponents(container: Container) {
  for (comp in container.findComponents<ListPluginComponent>()) {
    updateRequiredTag(comp)
  }
}

/**
 * 获取当前 AWT/Swing 环境中正在激活的非 null UI 组件或活动窗口。
 *
 * @return 当前焦点组件或活动窗口，无活动窗口时返回 null。
 */
internal fun getActiveUiComponent(): Component? {
  val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
  return focusManager.focusOwner
         ?: focusManager.activeWindow
         ?: focusManager.focusedWindow
         ?: runCatching { IdeFocusManager.getGlobalInstance().focusOwner }.getOrNull()
}

/**
 * 安全解析当前上下文所归属的 [Project] 实例。
 *
 * 采用多阶梯上下文探测策略（Multi-tier Context Resolution），彻底解决多项目并行时的配置串扰：
 * 1. **显式组件上下文**：优先通过传入 UI 组件向父级窗口追溯 [DataManager.getDataContext] 中的 [CommonDataKeys.PROJECT]；
 * 2. **活动焦点与窗口追溯**：当传入组件未能解析出项目时，从 AWT 焦点组件及活动窗口提取数据上下文；
 * 3. **全局 IDE 焦点追溯**：通过 [IdeFocusManager.getGlobalInstance] 补充探测全局焦点所有者；
 * 4. **有效打开项目回退**：过滤已打开且未释放的项目；
 * 5. **全局默认项目保底**：降级至 [ProjectManager.getDefaultProject]（确保在 Welcome Screen 或无项目时不抛 NPE）。
 *
 * @param component 可选的 UI 组件，用于提取精准的窗口级数据上下文。
 * @return 当前解析到的有效 [Project] 实例。
 */
internal fun resolveCurrentProject(component: Component? = null): Project {
  if (component != null) {
    val project = extractProjectFromComponent(component)
    if (project != null) return project
  }
  
  val activeComponent = getActiveUiComponent()
  if (activeComponent != null && activeComponent !== component) {
    val project = extractProjectFromComponent(activeComponent)
    if (project != null) return project
  }
  
  val openProjects = ProjectManager.getInstance().openProjects.filter { it.isInitialized && !it.isDisposed }
  return openProjects.firstOrNull() ?: ProjectManager.getInstance().defaultProject
}

/**
 * 从指定 AWT/Swing 组件的数据上下文中提取未释放的 [Project] 实例。
 *
 * @param component 目标 UI 组件。
 * @return 数据上下文中关联的有效 [Project] 实例；若未获取到则返回 null。
 */
private fun extractProjectFromComponent(component: Component): Project? =
  runCatching {
    val dataContext = DataManager.getInstance().getDataContext(component)
    CommonDataKeys.PROJECT.getData(dataContext)?.takeIf { it.isInitialized && !it.isDisposed }
  }.getOrNull()

