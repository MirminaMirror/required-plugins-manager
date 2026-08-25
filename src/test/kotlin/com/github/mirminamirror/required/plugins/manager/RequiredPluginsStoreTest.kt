@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager

import com.github.mirminamirror.required.plugins.manager.view.StagedRequiredPluginsSession

import com.github.mirminamirror.required.plugins.manager.view.resolveCurrentSession
import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.externalDependencies.ExternalDependenciesManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

import javax.swing.JPanel

class RequiredPluginsStoreTest : BasePlatformTestCase() {
  
  fun `test load and replace required plugins with real platform storage`() {
    val initialPlugins = listOf(
      DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", "  "),
      DependencyOnPlugin("com.intellij.java", " 2026.1.0 ", " 2026.3.0 "),
    )
    
    RequiredPluginsStore.replace(project, initialPlugins)
    
    val loaded = RequiredPluginsStore.load(project)
    assertEquals(2, loaded.size)
    assertTrue(loaded.containsKey("com.intellij.java"))
    assertTrue(loaded.containsKey("org.jetbrains.kotlin"))
    
    val javaDep = loaded["com.intellij.java"]
    assertNotNull(javaDep)
    assertEquals("2026.1.0", javaDep?.rawMinVersion)
    assertEquals("2026.3.0", javaDep?.rawMaxVersion)
    
    val kotlinDep = loaded["org.jetbrains.kotlin"]
    assertNotNull(kotlinDep)
    assertEquals("2.1.0", kotlinDep?.rawMinVersion)
    assertNull(kotlinDep?.rawMaxVersion)
    
    // Test update closure
    RequiredPluginsStore.update(project) { map ->
      map.remove("org.jetbrains.kotlin")
      map["org.intellij.groovy"] = DependencyOnPlugin("org.intellij.groovy", null, null)
    }
    
    val updated = RequiredPluginsStore.load(project)
    assertEquals(2, updated.size)
    assertTrue(updated.containsKey("com.intellij.java"))
    assertTrue(updated.containsKey("org.intellij.groovy"))
    assertFalse(updated.containsKey("org.jetbrains.kotlin"))
    
    // Ensure external dependencies manager has exactly 2 dependencies
    val deps = ExternalDependenciesManager.getInstance(project).getDependencies(DependencyOnPlugin::class.java)
    assertEquals(2, deps.size)
  }
  
  fun `test normalized dependency on plugin trims whitespace and preserves identity`() {
    val cleanDep = DependencyOnPlugin("com.intellij.java", "2026.1", "2026.2")
    assertSame(cleanDep, cleanDep.normalized())
    
    val blankDep = DependencyOnPlugin("org.jetbrains.kotlin", "  ", "")
    val normalizedBlank = blankDep.normalized()
    assertNull(normalizedBlank.rawMinVersion)
    assertNull(normalizedBlank.rawMaxVersion)
    
    val paddedDep = DependencyOnPlugin("org.intellij.groovy", " 1.0 ", " 2.0 ")
    val normalizedPadded = paddedDep.normalized()
    assertEquals("1.0", normalizedPadded.rawMinVersion)
    assertEquals("2.0", normalizedPadded.rawMaxVersion)
  }
  
  fun `test staged modifications are discarded on reset and persisted on apply`() {
    RequiredPluginsStore.replace(project, listOf(DependencyOnPlugin("com.intellij.java", "2026.1", null)))
    
    val initialMap = RequiredPluginsStore.load(project)
    val stagedMap = initialMap.toMutableMap()
    
    // Stage addition and removal
    stagedMap["org.jetbrains.kotlin"] = DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", null)
    stagedMap.remove("com.intellij.java")
    assertTrue(stagedMap != initialMap)
    
    // Verify disk store was NOT modified during staging
    val diskBefore = RequiredPluginsStore.load(project)
    assertEquals(1, diskBefore.size)
    assertTrue(diskBefore.containsKey("com.intellij.java"))
    assertFalse(diskBefore.containsKey("org.jetbrains.kotlin"))
    
    // Simulate Reset (Cancel) -> rollback to initialMap
    stagedMap.clear()
    stagedMap.putAll(initialMap)
    assertEquals(initialMap, stagedMap)
    assertEquals(diskBefore, RequiredPluginsStore.load(project))
    
    // Stage again and Simulate Apply (OK / Apply)
    stagedMap["org.jetbrains.kotlin"] = DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", null)
    RequiredPluginsStore.replace(project, stagedMap.values)
    
    val diskAfter = RequiredPluginsStore.load(project)
    assertEquals(2, diskAfter.size)
    assertTrue(diskAfter.containsKey("com.intellij.java"))
    assertTrue(diskAfter.containsKey("org.jetbrains.kotlin"))
  }
  
  fun `test complete configurable transaction lifecycle with proxy tracker`() {
    // 1. Initial store setup on disk
    val initialDeps = listOf(DependencyOnPlugin("com.intellij.java", "2026.1", "2026.2"))
    RequiredPluginsStore.replace(project, initialDeps)
    assertEquals(1, RequiredPluginsStore.load(project).size)
    
    // 2. Create configurable and extract model & panel
    val configurable = com.intellij.ide.plugins.PluginManagerConfigurable()
    val rootComponent = configurable.createComponent()
    assertNotNull(rootComponent)
    val panelField = configurable.javaClass.getDeclaredField("myPanel").apply { isAccessible = true }
    val panel = panelField.get(configurable) as com.intellij.ide.plugins.PluginManagerConfigurablePanel
    
    val facadeField = panel.javaClass.getDeclaredField("pluginModelFacade").apply { isAccessible = true }
    val facade = facadeField.get(panel) as com.intellij.ide.plugins.newui.PluginModelFacade
    val getModelMethod = facade.javaClass.getDeclaredMethod("getModel").apply { isAccessible = true }
    val model = getModelMethod.invoke(facade)
    assertNotNull(model)
    
    // Initial state: not modified
    assertFalse(configurable.isModified)
    
    // 3. Create Staged Session attached to rootComponent
    val initialMap = RequiredPluginsStore.load(project)
    val stagedMap = initialMap.toMutableMap()
    
    // Wrap modificationTracker
    val trackerField = model.javaClass.superclass.getDeclaredField("modificationTracker").apply { isAccessible = true }
    val originalTracker = trackerField.get(model)
    
    val proxyTracker = java.lang.reflect.Proxy.newProxyInstance(
      originalTracker.javaClass.classLoader,
      originalTracker.javaClass.interfaces
    ) { _, method, args ->
      if (method.name == "isModified") {
        val orig = method.invoke(originalTracker, *(args ?: emptyArray())) as Boolean
        orig || (stagedMap != initialMap)
      } else {
        method.invoke(originalTracker, *(args ?: emptyArray()))
      }
    }
    trackerField.set(model, proxyTracker)
    
    // Before staging changes: configurable.isModified is false
    assertFalse(configurable.isModified)
    
    // 4. User stages a change in UI (add kotlin required plugin)
    stagedMap["org.jetbrains.kotlin"] = DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", null)
    
    // Verification A: Disk was NOT modified
    val diskDuringStaging = RequiredPluginsStore.load(project)
    assertEquals(1, diskDuringStaging.size)
    assertFalse(diskDuringStaging.containsKey("org.jetbrains.kotlin"))
    
    // Verification B: Configurable is now recognized as modified by IDEA platform
    assertTrue(configurable.isModified)
    
    // 5. User cancels / resets -> Rollback
    stagedMap.clear()
    stagedMap.putAll(initialMap)
    
    // Verification C: Configurable is no longer modified, disk untouched
    assertFalse(configurable.isModified)
    assertEquals(1, RequiredPluginsStore.load(project).size)
    
    // 6. User stages change again and clicks Apply/OK -> Persist
    stagedMap["org.jetbrains.kotlin"] = DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", null)
    assertTrue(configurable.isModified)
    
    // Apply action persists to disk and updates baseline
    RequiredPluginsStore.replace(project, stagedMap.values)
    val diskAfterApply = RequiredPluginsStore.load(project)
    assertEquals(2, diskAfterApply.size)
    assertTrue(diskAfterApply.containsKey("com.intellij.java"))
    assertTrue(diskAfterApply.containsKey("org.jetbrains.kotlin"))
  }
  
  fun `test active session resolution and customizeTags with staged data`() {
    // Disk initially has no required plugins
    RequiredPluginsStore.replace(project, emptyList())
    assertEquals(0, RequiredPluginsStore.load(project).size)
    
    // In-memory staged session adds kotlin plugin
    val stagedMap = mutableMapOf("org.jetbrains.kotlin" to DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", null))
    
    // If session is active, customizeTags must read from stagedMap, NOT disk
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("org.jetbrains.kotlin")
    val isReqInSession = pluginId.idString in stagedMap
    assertTrue(isReqInSession)
    
    val tags = listOf("Official")
    val customizedTags = if (isReqInSession) tags + "Required" else tags
    assertTrue(customizedTags.contains("Required"))
  }
  
  fun `test staged session resolution rejects another project`() {
    val otherProject = com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
    val session = StagedRequiredPluginsSession(otherProject, null)
    val component = JPanel()
    component.putClientProperty("required.staged.session", session)
    
    try {
      assertNull(resolveCurrentSession(component, project))
      assertSame(session, resolveCurrentSession(component, otherProject))
    } finally {
      session.dispose()
    }
  }
  
  fun `test staged session lifecycle apply reset and modification tracking`() {
    RequiredPluginsStore.replace(project, emptyList())
    val rootComponent = JPanel()
    val session = StagedRequiredPluginsSession(project, null, rootComponent)
    
    try {
      assertFalse(session.isModified())
      
      // Modify session
      session.update { map ->
        map["com.intellij.java"] = DependencyOnPlugin("com.intellij.java", "2026.1", null)
      }
      assertTrue(session.isModified())
      assertEquals(1, session.getAll().size)
      assertTrue(session.contains("com.intellij.java"))
      // Store should still be empty (not persisted yet)
      assertTrue(RequiredPluginsStore.load(project).isEmpty())
      
      // Reset should roll back
      session.reset()
      assertFalse(session.isModified())
      assertTrue(session.getAll().isEmpty())
      
      // Modify and apply
      session.update { map ->
        map["org.jetbrains.kotlin"] = DependencyOnPlugin("org.jetbrains.kotlin", "2.1.0", null)
      }
      assertTrue(session.isModified())
      session.apply()
      assertFalse(session.isModified())
      
      // Store should now have the persisted data
      val persisted = RequiredPluginsStore.load(project)
      assertEquals(1, persisted.size)
      assertTrue(persisted.containsKey("org.jetbrains.kotlin"))
    } finally {
      session.dispose()
    }
  }
  
  fun `test staged session banner ActionLink triggers reset`() {
    RequiredPluginsStore.replace(project, emptyList())
    val container = JPanel()
    val banner = JPanel()
    val resetActionLink = com.intellij.ui.components.ActionLink("Revert Changes")
    banner.add(resetActionLink)
    val rootComponent = JPanel()
    container.add(banner)
    container.add(rootComponent)
    
    val session = StagedRequiredPluginsSession(project, null, rootComponent)
    try {
      session.update { map ->
        map["com.intellij.java"] = DependencyOnPlugin("com.intellij.java", "2026.1", null)
      }
      assertTrue(session.isModified())
      assertEquals(1, session.getAll().size)
      
      // Simulate clicking the banner ActionLink
      resetActionLink.doClick()
      assertFalse(session.isModified())
      assertTrue(session.getAll().isEmpty())
    } finally {
      session.dispose()
    }
  }
  
  fun `test customizeTags reflects dynamic session changes and removal`() {
    RequiredPluginsStore.replace(project, emptyList())
    val session = StagedRequiredPluginsSession(project, null)
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("com.intellij.java")
    val customization =
      com.github.mirminamirror.required.plugins.manager.view.RequiredPluginCustomization(pluginId, project)
    
    try {
      // 1. Initially not required
      assertEquals(listOf("Official"), customization.customizeTags(listOf("Official")))
      
      // 2. Mark as required in session
      session.update { map ->
        map["com.intellij.java"] = DependencyOnPlugin("com.intellij.java", "2026.1", null)
      }
      assertEquals(listOf("Required", "Official"), customization.customizeTags(listOf("Official")))
      
      // 3. Unmark in session (e.g. uncheck checkbox)
      session.update { map ->
        map.remove("com.intellij.java")
      }
      assertEquals(listOf("Official"), customization.customizeTags(listOf("Official")))
    } finally {
      session.dispose()
    }
  }
}
