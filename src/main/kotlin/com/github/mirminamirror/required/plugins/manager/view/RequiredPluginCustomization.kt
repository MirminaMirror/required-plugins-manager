@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.ide.plugins.newui.PluginInstallationCustomization
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * 动态同步必需插件标签定制扩展点（O(1) Set 查重与定向注册）。
 *
 * 官方插件卡片 Tag 是通过 [PluginInstallationCustomization] 扩展点由平台内部查询的。
 * 本函数通过一次性收集已注册的扩展点 PluginId 集合，仅对尚未注册的插件进行单趟注册，将原本的 O(N²) 查重彻底优化为 O(N)。
 *
 * @param parentDisposable 挂载生命周期的父级 [Disposable]。
 * @param project 当前配置面板所绑定的 [Project] 上下文。
 */
internal fun syncPluginCustomizations(parentDisposable: Disposable, project: Project) {
  runCatching {
    val extensionPoint = ApplicationManager.getApplication().extensionArea
                           .getExtensionPointIfRegistered<PluginInstallationCustomization>(
                             "com.intellij.pluginInstallationCustomization",
                           ) ?: return
    val requiredIds = (resolveCurrentSession(expectedProject = project)?.getAll()
                       ?: RequiredPluginsStore.load(project)).keys.mapTo(HashSet(), PluginId::getId)
    val registeredIds = extensionPoint.extensionList
      .filterIsInstance<RequiredPluginCustomization>()
      .filter { it.project === project }
      .mapTo(HashSet()) { it.pluginId }
    for (pluginId in requiredIds) {
      if (pluginId !in registeredIds) {
        extensionPoint.registerExtension(RequiredPluginCustomization(pluginId, project), parentDisposable)
      }
    }
  }
}

/**
 * 必需插件官方标签定制实现。
 *
 * 负责在平台渲染卡片 Tag 时，如果当前插件属于当前项目的必需插件，则向标签列表追加 "Required" 标签。
 *
 * @property pluginId 目标插件标识。
 * @property project 绑定的项目上下文。
 */
internal class RequiredPluginCustomization(
  override val pluginId: PluginId,
  internal val project: Project,
) : PluginInstallationCustomization {
  override val priority: Int get() = -100
  
  override fun createLicensePanel(isMarketplace: Boolean, update: Boolean): JComponent? = null
  override fun beforeInstallOrUpdate(update: Boolean) {}
  
  override fun customizeTags(tags: List<String>): List<String> {
    if (project.isDisposed) return tags
    val session = resolveCurrentSession(expectedProject = project)
    val req = session?.getAll() ?: RequiredPluginsStore.load(project)
    return if (pluginId.idString in req) {
      if (tags.contains(TAG_REQUIRED_NAME)) tags else listOf(TAG_REQUIRED_NAME) + tags
    } else {
      tags
    }
  }
}
