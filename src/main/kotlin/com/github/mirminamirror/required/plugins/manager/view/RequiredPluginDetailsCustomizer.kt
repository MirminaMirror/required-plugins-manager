@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import com.github.mirminamirror.required.plugins.manager.RequiredPluginsManagerBundle
import com.github.mirminamirror.required.plugins.manager.RequiredPluginsStore
import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.TagPanel
import com.intellij.ide.plugins.newui.BaselinePanel
import com.intellij.ide.plugins.newui.PluginsViewCustomizer
import com.intellij.ide.plugins.newui.TagComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * 负责在官方原生详情页面顶部注入 Required 复选框与版本区间控制面板。
 *
 * 交互与数据流契约：
 * 1. 勾选/取消勾选 "[☑] 设为当前项目必需插件" 时立即同步写入暂存会话，并联动展开/收起版本面板；
 * 2. Min / Max 版本输入框接入基于 Kotlin 协程的 [MutableSharedFlow] 进行 500ms 防抖暂存，防止击键过程产生频繁的事件抖动；
 * 3. 在输入框按下 Enter、失去焦点（[FocusEvent]）、切换查看其它插件（[processShowPlugin]）或面板销毁（[dispose]）时，立即冲刷（Flush）未完成的防抖保存任务，确保数据绝对不丢失。
 */
internal class RequiredPluginDetailsCustomizer : PluginsViewCustomizer.PluginDetailsCustomizer, Disposable {
  
  private var currentDescriptor: IdeaPluginDescriptor? = null
  private var requiredCheckBox: JBCheckBox? = null
  private var versionPanel: JPanel? = null
  private var minVersionField: JBTextField? = null
  private var maxVersionField: JBTextField? = null
  
  /** 用于在程序主动给文本框设值时屏蔽防抖持久化监听，防止产生回环写盘 */
  private var isUpdatingFields = false
  
  /** 标记当前是否存在待冲刷的防抖持久化任务 */
  private var hasPendingSave = false
  
  /** 绑定 EDT 线程的协程作用域，随 [Disposable] 生命周期闭环管理 */
  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
  
  /** 版本约束输入变更的防抖触发流 */
  private val saveTriggerFlow = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  
  private var sessionListener: (() -> Unit)? = null
  
  init {
    coroutineScope.launch {
      @OptIn(FlowPreview::class)
      saveTriggerFlow
        .debounce(SAVE_DEBOUNCE_DELAY_MS.milliseconds)
        .collectLatest { flushPendingSave() }
    }
  }
  
  /**
   * 在插件详情页标题和操作按钮面板（[BaselinePanel]）中注入 Required 复选框及版本面板。
   *
   * @param nameAndButtons 官方详情页的标题与按钮行容器。
   */
  override fun processPluginNameAndButtonsComponent(nameAndButtons: BaselinePanel) {
    val checkBox = JBCheckBox(RequiredPluginsManagerBundle.message("plugins.details.required.checkbox")).apply {
      isOpaque = false
      addActionListener {
        val descriptor = currentDescriptor ?: return@addActionListener
        val isSelected = isSelected
        val min = minVersionField?.text?.trim()?.ifEmpty { null }
        val max = maxVersionField?.text?.trim()?.ifEmpty { null }
        
        requiredCheckBox.updateRequiredPlugins { map ->
          if (isSelected) {
            map[descriptor.pluginId.idString] = DependencyOnPlugin(descriptor.pluginId.idString, min, max)
          } else {
            map.remove(descriptor.pluginId.idString)
            isUpdatingFields = true
            try {
              minVersionField?.text = ""
              maxVersionField?.text = ""
            } finally {
              isUpdatingFields = false
            }
          }
        }
        syncUiState(descriptor)
      }
    }
    requiredCheckBox = checkBox
    nameAndButtons.addButtonComponent(checkBox)
    
    val vPanel = createVersionPanel()
    versionPanel = vPanel
    vPanel.isVisible = false
    
    attachVersionPanel(nameAndButtons, vPanel)
  }
  
  /**
   * 当用户在插件列表中选中并显示特定插件详情时回调。
   *
   * 负责将当前插件的必需状态与 Min/Max 版本约束回显到 UI 控件中。
   *
   * @param pluginDescriptor 当前正在展示的插件描述符。
   */
  override fun processShowPlugin(pluginDescriptor: IdeaPluginDescriptor) {
    flushPendingSave()
    currentDescriptor = pluginDescriptor
    
    val session = resolveCurrentSession(requiredCheckBox)
    if (sessionListener == null && session != null) {
      val listener = {
        SwingUtilities.invokeLater {
          val desc = currentDescriptor ?: return@invokeLater
          syncUiState(desc)
        }
      }
      sessionListener = listener
      session.addChangeListener(listener)
    }
    
    ensureVersionPanelAttached()
    syncUiState(pluginDescriptor)
  }
  
  /**
   * 释放定制器资源，冲刷未持久化数据并取消协程作用域。
   */
  override fun dispose() {
    flushPendingSave()
    sessionListener?.let { resolveCurrentSession(requiredCheckBox)?.removeChangeListener(it) }
    coroutineScope.cancel()
  }
  
  /**
   * 同步当前插件数据至 UI 控件（复选框状态、版本输入框与版本面板可见性）。
   *
   * @param descriptor 当前插件描述符。
   */
  private fun syncUiState(descriptor: IdeaPluginDescriptor) {
    val session = resolveCurrentSession(requiredCheckBox)
    val reqMap = session?.getAll() ?: RequiredPluginsStore.load(resolveCurrentProject(requiredCheckBox))
    val reqData = reqMap[descriptor.pluginId.idString]
    val isReq = reqData != null
    
    requiredCheckBox?.isVisible = true
    requiredCheckBox?.isSelected = isReq
    versionPanel?.isVisible = isReq
    
    isUpdatingFields = true
    try {
      minVersionField?.text = reqData?.rawMinVersion ?: ""
      maxVersionField?.text = reqData?.rawMaxVersion ?: ""
    } finally {
      isUpdatingFields = false
    }
    
    val topPanel = requiredCheckBox?.let { cb ->
      cb.parent?.parent as? JComponent
    }
    val tagPanel = topPanel?.components?.filterIsInstance<TagPanel>()?.firstOrNull()
    if (tagPanel != null) {
      val currentTags = (0 until tagPanel.componentCount)
        .mapNotNull { (tagPanel.getComponent(it) as? TagComponent)?.text }
        .filter { it.isNotBlank() }
      val targetTags = if (isReq) {
        if (TAG_REQUIRED_NAME !in currentTags) currentTags + TAG_REQUIRED_NAME else currentTags
      } else {
        currentTags.filter { it != TAG_REQUIRED_NAME }
      }
      if (targetTags != currentTags) {
        tagPanel.setTags(targetTags)
      }
    }
    
    topPanel?.revalidate()
    topPanel?.repaint()
  }
  
  /**
   * 将版本面板挂载到标题与按钮面板（[BaselinePanel]）的下方。
   */
  private fun attachVersionPanel(nameAndButtons: BaselinePanel, vPanel: JPanel) {
    val parent = nameAndButtons.parent ?: return
    val index = parent.components.indexOf(nameAndButtons)
    if (index >= 0) {
      parent.add(vPanel, index + 1)
    } else {
      parent.add(vPanel)
    }
  }
  
  /**
   * 确保版本面板已挂载到组件树中，若尚未挂载则执行插入。
   */
  private fun ensureVersionPanelAttached() {
    val vPanel = versionPanel ?: return
    if (vPanel.parent != null) return
    
    val checkBox = requiredCheckBox ?: return
    val baseline = (checkBox.parent as? BaselinePanel) ?: checkBox.parent
    val parent = baseline?.parent
    if (parent != null) {
      val index = parent.components.indexOf(baseline)
      if (index >= 0) {
        parent.add(vPanel, index + 1)
      } else {
        parent.add(vPanel)
      }
    }
  }
  
  /**
   * 构建包含最低版本 (Min) 与最高版本 (Max) 输入控件的面板。
   *
   * @return 构建好的版本配置 [JPanel]。
   */
  private fun createVersionPanel(): JPanel {
    val panel = JPanel(GridBagLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 8, 0)
    }
    
    val minField = createVersionField("plugins.details.version.min.placeholder").also { minVersionField = it }
    val maxField = createVersionField("plugins.details.version.max.placeholder").also { maxVersionField = it }
    
    val minLabel = JBLabel(RequiredPluginsManagerBundle.message("plugins.details.version.min.label"))
    val maxLabel = JBLabel(RequiredPluginsManagerBundle.message("plugins.details.version.max.label"))
    
    val minBtn = createCurrentVersionButton(minField)
    val maxBtn = createCurrentVersionButton(maxField)
    
    val gbc = GridBagConstraints().apply {
      fill = GridBagConstraints.HORIZONTAL
      insets = JBUI.insets(2, 4)
    }
    
    fun addRow(row: Int, label: JBLabel, field: JBTextField, button: JButton) {
      gbc.gridy = row
      gbc.gridx = 0; gbc.weightx = 0.0; panel.add(label, gbc)
      gbc.gridx = 1; gbc.weightx = 1.0; panel.add(field, gbc)
      gbc.gridx = 2; gbc.weightx = 0.0; panel.add(button, gbc)
    }
    addRow(0, minLabel, minField, minBtn)
    addRow(1, maxLabel, maxField, maxBtn)
    
    return panel
  }
  
  /**
   * 创建具有防抖持久化、失去焦点即时冲刷与回车即时提交特性的版本输入文本框。
   *
   * @param placeholderKey 占位符在资源包中的消息键。
   * @return 配置好的 [JBTextField] 实例。
   */
  private fun createVersionField(placeholderKey: String): JBTextField =
    JBTextField(10).apply {
      emptyText.text = RequiredPluginsManagerBundle.message(placeholderKey)
      addActionListener { flushPendingSave() }
      document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          scheduleSaveVersionConstraints()
        }
      })
      addFocusListener(object : FocusAdapter() {
        override fun focusLost(e: FocusEvent) {
          flushPendingSave()
        }
      })
    }
  
  /**
   * 创建"填入当前版本"快捷操作按钮。
   *
   * @param targetField 待填充版本号的目标输入框。
   * @return 配置好的 [JButton] 实例。
   */
  private fun createCurrentVersionButton(targetField: JBTextField): JButton =
    JButton(RequiredPluginsManagerBundle.message("plugins.details.version.fill.current")).apply {
      font = PluginManagerConfigurable.setTinyFont(this).font
      addActionListener {
        val v = currentDescriptor?.version
        if (!v.isNullOrBlank()) {
          targetField.text = v
          flushPendingSave()
        }
      }
    }
  
  /**
   * 安排延迟保存版本约束（基于 Flow 的防抖触发）。
   */
  private fun scheduleSaveVersionConstraints() {
    if (isUpdatingFields) return
    hasPendingSave = true
    saveTriggerFlow.tryEmit(Unit)
  }
  
  /**
   * 立即冲刷并执行待处理的保存请求。
   */
  private fun flushPendingSave() {
    if (hasPendingSave) {
      hasPendingSave = false
      executeSaveVersionConstraints()
    }
  }
  
  /**
   * 将当前输入框中的版本范围原子同步至会话或 [RequiredPluginsStore]。
   */
  private fun executeSaveVersionConstraints() {
    if (isUpdatingFields) return
    val descriptor = currentDescriptor ?: return
    val min = minVersionField?.text?.trim()?.ifEmpty { null }
    val max = maxVersionField?.text?.trim()?.ifEmpty { null }
    
    requiredCheckBox.updateRequiredPlugins { map ->
      if (map.containsKey(descriptor.pluginId.idString)) {
        map[descriptor.pluginId.idString] = DependencyOnPlugin(descriptor.pluginId.idString, min, max)
      }
    }
  }
}
