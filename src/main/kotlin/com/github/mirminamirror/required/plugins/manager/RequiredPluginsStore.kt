@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager

import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.externalDependencies.ExternalDependenciesManager
import com.intellij.openapi.project.Project

/**
 * 访问与持久化 IDEA 原生项目必需插件配置（基于 [ExternalDependenciesManager]）。
 *
 * 此适配器不维护独立持久化状态；持久化时会保留所有非插件类型的项目外部依赖，并按插件 ID 排序以保持 XML 配置有序稳定。
 */
object RequiredPluginsStore {
  /**
   * 加载当前项目配置的所有必需插件依赖映射（Key 为插件标识字符串）。
   *
   * @param project 目标项目上下文。
   * @return 插件标识到依赖对象的映射表。
   */
  fun load(project: Project): Map<String, DependencyOnPlugin> =
    ExternalDependenciesManager.getInstance(project)
      .getDependencies(DependencyOnPlugin::class.java)
      .associateBy { it.pluginId }
  
  /**
   * 替换当前项目的必需插件配置，规范化版本约束并持久化至外部依赖管理器。
   *
   * @param project 目标项目上下文。
   * @param plugins 必需插件依赖配置集合（自动按 pluginId 去重与排序）。
   */
  fun replace(project: Project, plugins: Collection<DependencyOnPlugin>) {
    val manager = ExternalDependenciesManager.getInstance(project)
    val nonPluginDependencies = manager.allDependencies.filterNot { it is DependencyOnPlugin }
    
    val distinctPlugins = plugins
      .associateBy { it.pluginId }
      .values
      .sortedBy { it.pluginId }
    
    val finalDependencies = buildList(nonPluginDependencies.size + distinctPlugins.size) {
      addAll(nonPluginDependencies)
      distinctPlugins.mapTo(this) { it.normalized() }
    }
    
    manager.setAllDependencies(finalDependencies)
  }
  
  /**
   * 对当前项目的必需插件配置执行原子读取、修改并写回持久化。
   *
   * @param project 目标项目上下文。
   * @param action 接收可变映射表进行就地修改的闭包。
   */
  inline fun update(project: Project, action: (MutableMap<String, DependencyOnPlugin>) -> Unit) {
    val current = load(project).toMutableMap()
    action(current)
    replace(project, current.values)
  }
}

/**
 * 规范化插件依赖对象的版本约束字符串（空白转 null，无变化时复用原实例）。
 *
 * @return 规范化后的 [DependencyOnPlugin] 实例。
 */
fun DependencyOnPlugin.normalized(): DependencyOnPlugin {
  val min = rawMinVersion?.trim()?.ifEmpty { null }
  val max = rawMaxVersion?.trim()?.ifEmpty { null }
  return if (min == rawMinVersion && max == rawMaxVersion) this
  else DependencyOnPlugin(pluginId, min, max)
}
