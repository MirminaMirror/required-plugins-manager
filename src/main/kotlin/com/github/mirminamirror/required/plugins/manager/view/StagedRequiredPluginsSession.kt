@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.newEditor.SettingsDialogListener
import com.intellij.openapi.options.newEditor.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.ActionLink
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.*
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** 容器 ClientProperty 键名，用于存储当前设置面板的暂存事务会话 [StagedRequiredPluginsSession] */
internal const val KEY_STAGED_SESSION = "required.staged.session"

/** 按钮 ClientProperty 键名，用于防止对同一个顶栏 ActionLink 重复挂载重置监听器 */
private const val KEY_RESET_LINK_ATTACHED = "required.reset.link.attached"

/** 当前处于活跃状态的设置面板暂存会话全局备份（在无具体组件参数时作为保底追溯路径） */
@Volatile
private var activeStagedSession: StagedRequiredPluginsSession? = null

/**
 * 递归查找当前 UI 组件树所归属的 [StagedRequiredPluginsSession] 暂存会话。
 *
 * 采用多阶梯会话探测策略：
 * 1. 优先沿着传入的 component 组件向上递归遍历父容器的 ClientProperty；
 * 2. 当 component 为 null 或未找到时，沿当前 AWT 活动焦点组件/活动窗口向上追溯；
 * 3. 若仍未找到，回退至全局单例 [activeStagedSession]（只要当前项目未释放）。
 *
 * @param component 任意关联的子组件。
 * @param expectedProject 期望的项目上下文（用于多项目隔离校验）。
 * @return 匹配的暂存会话；若不存在（例如非设置对话框上下文）则返回 null。
 */
internal fun resolveCurrentSession(
  component: Component? = null,
  expectedProject: Project? = null,
): StagedRequiredPluginsSession? {
  if (component != null) {
    val session = extractSessionFromComponent(component, expectedProject)
    if (session != null) return session
  }
  
  val activeComponent = getActiveUiComponent()
  if (activeComponent != null && activeComponent !== component) {
    val session = extractSessionFromComponent(activeComponent, expectedProject)
    if (session != null) return session
  }
  
  return activeStagedSession?.takeIf { session ->
    !session.project.isDisposed && (expectedProject == null || session.project === expectedProject)
  }
}

/**
 * 从指定 AWT/Swing 组件向上递归追溯挂载的 [StagedRequiredPluginsSession] 实例。
 *
 * @param component 目标组件。
 * @param expectedProject 期望的项目上下文。
 * @return 挂载在组件树上的暂存会话；若未找到则返回 null。
 */
private fun extractSessionFromComponent(
  component: Component,
  expectedProject: Project? = null,
): StagedRequiredPluginsSession? {
  var current: Component? = component
  while (current != null) {
    if (current is JComponent) {
      val session = current.getClientProperty(KEY_STAGED_SESSION) as? StagedRequiredPluginsSession
      if (session != null && !session.project.isDisposed &&
          (expectedProject == null || session.project === expectedProject)
      ) {
        return session
      }
    }
    current = current.parent
  }
  return null
}

/**
 * 统一分流执行必需插件配置更新，优先使用组件关联的内存暂存会话，回退至磁盘持久化存储。
 *
 * @param action 接收可变映射表进行就地修改的闭包。
 */
internal fun Component?.updateRequiredPlugins(
  action: (MutableMap<String, DependencyOnPlugin>) -> Unit,
) {
  val session = resolveCurrentSession(this)
  if (session != null) {
    session.update(action)
  } else {
    val project = resolveCurrentProject(this)
    RequiredPluginsStore.update(project, action)
  }
}

/**
 * 管理当前插件设置对话框生命周期内的必需插件暂存态与事务提交流程。
 *
 * 遵循 IntelliJ [com.intellij.ide.plugins.PluginManagerConfigurable] 的标准事务契约：
 * 1. **初始快照（Initial Snapshot）**：面板初始化时从 [RequiredPluginsStore] 加载并克隆初始配置；
 * 2. **工作暂存副本（Staged Working Copy）**：UI 交互期间的所有复选框切换、版本编辑、右键批量操作仅修改暂存数据，严禁直接写盘；
 * 3. **状态比对与 Apply 按钮联动**：对比暂存数据与初始数据，存在差异时将窗口的 "Apply" 按钮置为可用（enabled）；
 * 4. **原子应用提交（apply）**：当用户点击 "Apply" / "OK" 时，将暂存数据批量持久化至 [RequiredPluginsStore]，并刷新初始配置快照；
 * 5. **完整回退重置（reset）**：当用户点击 "Cancel" / "Reset" 或左上角“还原更改”超链接时，回退暂存数据至初始快照，并重绘刷新所有关联 UI。
 *
 * @property project 当前设置对话框绑定的项目上下文。
 * @property pluginModel 官方插件模型实例（MyPluginModel）。
 */
internal class StagedRequiredPluginsSession(
  val project: Project,
  val pluginModel: Any?,
  rootComponent: JComponent? = null,
) : Disposable {
  private var initialMap: Map<String, DependencyOnPlugin> =
    RequiredPluginsStore.load(project)
  
  private val stagedMap: MutableMap<String, DependencyOnPlugin> =
    initialMap.toMutableMap()
  
  private val changeListeners = mutableListOf<() -> Unit>()
  
  init {
    activeStagedSession = this
    hookModificationTracker()
    subscribeSettingsDialogListener()
    if (rootComponent != null) {
      bindRootComponentLifecycle(rootComponent)
    }
  }
  
  /**
   * 代理官方插件模型的修改追踪器 SessionModificationTracker，
   * 确保 IDEA 设置框架轮询 [com.intellij.ide.plugins.PluginManagerConfigurable.isModified] 时能够感知到必需插件的暂存修改。
   */
  private fun hookModificationTracker() {
    val model = pluginModel ?: return
    runCatching {
      val clazz = model.javaClass
      val superclass = clazz.superclass
      val trackerField =
        (if (superclass != null) ReflectionCache.findField(superclass, "modificationTracker") else null)
        ?: ReflectionCache.findField(clazz, "modificationTracker")
      val originalTracker = trackerField?.get(model) ?: return
      val ifaces = originalTracker.javaClass.interfaces
      if (ifaces.isEmpty()) return
      
      val proxyTracker = Proxy.newProxyInstance(
        originalTracker.javaClass.classLoader,
        ifaces
      ) { _, method, args ->
        try {
          if (method.name == "isModified") {
            val orig = (method.invoke(originalTracker, *(args ?: emptyArray())) as? Boolean) ?: false
            orig || isModified()
          } else {
            method.invoke(originalTracker, *(args ?: emptyArray()))
          }
        } catch (e: InvocationTargetException) {
          throw (e.targetException ?: e)
        } catch (_: Throwable) {
          if (method.name == "isModified") isModified() else null
        }
      }
      trackerField.set(model, proxyTracker)
    }
  }
  
  /**
   * 订阅官方 [SettingsDialogListener.TOPIC] 消息总线，当用户在设置面板中点击 "Apply" 并完成应用时自动提交暂存配置。
   */
  private fun subscribeSettingsDialogListener() {
    runCatching {
      ApplicationManager.getApplication().messageBus.connect(this).subscribe(
        SettingsDialogListener.TOPIC,
        object : SettingsDialogListener {
          override fun afterApply(settingsEditor: SettingsEditor, modifiedConfigurableIds: Set<String>) {
            apply()
          }
        }
      )
    }
  }
  
  /**
   * 绑定根组件的展示层级与宿主窗口生命周期，在窗口以 OK 退出时提交暂存配置，关闭/取消时安全回滚。
   */
  private fun bindRootComponentLifecycle(rootComponent: JComponent) {
    var attachedWindow: Window? = null
    val attachWindowAction = {
      val window = SwingUtilities.getWindowAncestor(rootComponent)
                   ?: getActiveUiComponent()?.let { SwingUtilities.getWindowAncestor(it) }
      if (window != null && window !== attachedWindow) {
        attachedWindow = window
        val listener = object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            handleWindowExit(window, rootComponent)
          }
          
          override fun windowClosed(event: WindowEvent) {
            handleWindowExit(window, rootComponent)
          }
        }
        window.addWindowListener(listener)
      }
      bindBannerResetActionLink(rootComponent)
    }
    
    val hierarchyListener = HierarchyListener { event ->
      if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
        if (rootComponent.isShowing) {
          SwingUtilities.invokeLater(attachWindowAction)
        } else if (isModified()) {
          val dialog = DialogWrapper.findInstance(rootComponent)
          if (dialog == null || (!dialog.isOK && dialog.exitCode != DialogWrapper.OK_EXIT_CODE)) {
            reset()
          }
        }
      }
    }
    rootComponent.addHierarchyListener(hierarchyListener)
    attachWindowAction()
    SwingUtilities.invokeLater(attachWindowAction)
  }
  
  /**
   * 遍历宿主编辑器容器，精准提取并绑定官方顶栏 Banner 中的 [ActionLink]（“还原更改”超链接）。
   */
  private fun bindBannerResetActionLink(rootComponent: JComponent) {
    var current: Container? = rootComponent.parent
    while (current != null) {
      for (comp in current.components) {
        if (comp is Container && comp !== rootComponent) {
          findAndBindResetActionLinks(comp)
        }
      }
      if (current is Window) break
      current = current.parent
    }
  }
  
  /**
   * 递归查找并挂载 [ActionLink] 的重置监听器。
   */
  private fun findAndBindResetActionLinks(container: Container) {
    for (child in container.components) {
      if (child is ActionLink || child.javaClass.simpleName == "ActionLink") {
        if (child is JComponent && child.getClientProperty(KEY_RESET_LINK_ATTACHED) != true) {
          child.putClientProperty(KEY_RESET_LINK_ATTACHED, true)
          if (child is javax.swing.AbstractButton) {
            child.addActionListener { reset() }
          }
        }
      }
      if (child is Container) {
        findAndBindResetActionLinks(child)
      }
    }
  }
  
  /**
   * 宿主窗口退出时的统一提交/回滚判定。
   */
  private fun handleWindowExit(window: Window, rootComponent: JComponent) {
    if (!isModified()) return
    val dialog = DialogWrapper.findInstance(rootComponent) ?: DialogWrapper.findInstance(window)
    if (dialog != null && (dialog.isOK || dialog.exitCode == DialogWrapper.OK_EXIT_CODE)) {
      apply()
    } else {
      reset()
    }
  }
  
  /**
   * 检查当前暂存数据是否与初始快照存在差异。
   *
   * @return true 表示存在未应用的修改；false 表示未修改或已完全还原。
   */
  fun isModified(): Boolean =
    stagedMap != initialMap
  
  /**
   * 获取指定插件标识的暂存配置。
   *
   * @param pluginId 插件标识。
   * @return 匹配的 [DependencyOnPlugin] 配置；若未标记为必需则返回 null。
   */
  fun get(pluginId: String): DependencyOnPlugin? =
    stagedMap[pluginId]
  
  /**
   * 检查指定插件是否已被标记为必需。
   *
   * @param pluginId 插件标识。
   * @return true 表示已在暂存集中；false 表示未标记。
   */
  fun contains(pluginId: String): Boolean =
    stagedMap.containsKey(pluginId)
  
  /**
   * 获取全部暂存必需插件映射的只读视图。
   *
   * @return 包含全部必需插件配置的只读 Map。
   */
  fun getAll(): Map<String, DependencyOnPlugin> =
    Collections.unmodifiableMap(stagedMap)
  
  /**
   * 注册暂存会话数据变更监听器。
   *
   * @param listener 当数据发生增删改或重置时执行的回调函数。
   */
  fun addChangeListener(listener: () -> Unit) {
    changeListeners.add(listener)
  }
  
  /**
   * 注销暂存会话数据变更监听器。
   *
   * @param listener 待注销的回调函数。
   */
  fun removeChangeListener(listener: () -> Unit) {
    changeListeners.remove(listener)
  }
  
  /**
   * 原子修改暂存数据并通知所有注册的监听器。
   *
   * @param action 接收可变 Map 的修改函数。
   */
  fun update(action: (MutableMap<String, DependencyOnPlugin>) -> Unit) {
    action(stagedMap)
    notifyChanged()
  }
  
  /**
   * 提交应用当前暂存数据，真正批量写入磁盘 [RequiredPluginsStore]，并刷新初始快照。
   */
  fun apply() {
    if (isModified()) {
      RequiredPluginsStore.replace(project, stagedMap.values)
      initialMap = stagedMap.toMap()
      notifyChanged()
    }
  }
  
  /**
   * 回滚当前暂存数据至初始快照，并通知所有注册的 UI 监听器进行界面还原。
   */
  fun reset() {
    if (isModified()) {
      stagedMap.clear()
      stagedMap.putAll(initialMap)
      notifyChanged()
    }
  }
  
  /**
   * 触发所有数据变更监听器。
   */
  private fun notifyChanged() {
    for (listener in changeListeners.toList()) {
      runCatching { listener() }
    }
  }
  
  /**
   * 释放会话资源，清除全局引用并在存在未保存修改时执行安全回滚。
   */
  override fun dispose() {
    if (activeStagedSession === this) {
      activeStagedSession = null
    }
    if (isModified()) {
      reset()
    }
    changeListeners.clear()
  }
}
