@file:Suppress("UnstableApiUsage")

package com.github.mirminamirror.required.plugins.manager.view

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * 高性能轻量级反射工具缓存。
 *
 * 在高频的 Swing 渲染与事件链路中，每次通过反射遍历 `Class.superclass` 查找私有字段或方法开销高昂。
 * 本单例通过 [ConcurrentHashMap] 线程安全地缓存解析得到的 [Field] 与 [Method] 引用，将反射查找开销降至近乎为零。
 */
internal object ReflectionCache {
  private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
  private val methodCache = ConcurrentHashMap<String, Method>()
  
  /**
   * 获取目标对象指定字段的属性值。
   *
   * @param target 目标对象。
   * @param fieldName 目标字段名。
   * @return 字段值；若发生异常或字段不存在则返回 null。
   */
  fun getFieldValue(target: Any, fieldName: String): Any? {
    val field = findField(target.javaClass, fieldName) ?: return null
    return runCatching { field.get(target) }.getOrNull()
  }
  
  /**
   * 获取目标对象指定字段的属性值并安全转换为目标类型 [T]。
   *
   * @param T 目标属性类型。
   * @param target 目标对象。
   * @param fieldName 目标字段名。
   * @return 字段值；若发生异常、字段不存在或类型不匹配则返回 null。
   */
  @JvmName("getFieldValueTyped")
  inline fun <reified T> getFieldValue(target: Any, fieldName: String): T? =
    getFieldValue(target, fieldName) as? T
  
  /**
   * 查找指定类或其父类中声明的字段（自动设置 accessible 并写入缓存）。
   *
   * @param clazz 目标类。
   * @param fieldName 目标字段名。
   * @return 字段反射对象；若不存在则返回 null。
   */
  fun findField(clazz: Class<*>, fieldName: String): Field? {
    val key = clazz to fieldName
    val cached = fieldCache[key]
    if (cached != null) return cached
    
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
      try {
        val field = current.getDeclaredField(fieldName).apply { isAccessible = true }
        fieldCache[key] = field
        return field
      } catch (_: NoSuchFieldException) {
        current = current.superclass
      }
    }
    return null
  }
  
  /**
   * 调用目标对象的方法。
   *
   * @param target 目标对象。
   * @param methodName 目标方法名。
   * @param params 方法参数列表，每项为 `参数类型 KClass to 实参值`。
   * @return 方法执行返回值；若发生异常或方法不存在则返回 null。
   */
  fun invokeMethod(
    target: Any,
    methodName: String,
    vararg params: Pair<KClass<*>, Any?>,
  ): Any? {
    val paramTypes = Array(params.size) { params[it].first.java }
    val method = findMethod(target.javaClass, methodName, paramTypes) ?: return null
    val args = Array(params.size) { params[it].second }
    return runCatching { method.invoke(target, *args) }.getOrNull()
  }
  
  /**
   * 调用目标对象的方法并将返回值安全转换为目标类型 [T]。
   *
   * @param T 期望的返回值类型。
   * @param target 目标对象。
   * @param methodName 目标方法名。
   * @param params 方法参数列表，每项为 `参数类型 KClass to 实参值`。
   * @return 方法执行返回值；若发生异常、方法不存在或类型不匹配则返回 null。
   */
  @JvmName("invokeMethodTyped")
  inline fun <reified T> invokeMethod(
    target: Any,
    methodName: String,
    vararg params: Pair<KClass<*>, Any?>,
  ): T? = invokeMethod(target, methodName, *params) as? T
  
  /**
   * 查找指定类或其父类中声明的方法（自动设置 accessible 并写入缓存）。
   *
   * @param clazz 目标类。
   * @param methodName 目标方法名。
   * @param paramTypes 方法参数类型列表。
   * @return 方法反射对象；若不存在则返回 null。
   */
  fun findMethod(clazz: Class<*>, methodName: String, paramTypes: Array<Class<*>> = emptyArray()): Method? {
    val key = "${clazz.name}#$methodName(${paramTypes.joinToString { it.name }})"
    val cached = methodCache[key]
    if (cached != null) return cached
    
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
      try {
        val method = current.getDeclaredMethod(methodName, *paramTypes).apply { isAccessible = true }
        methodCache[key] = method
        return method
      } catch (_: NoSuchMethodException) {
        current = current.superclass
      }
    }
    return null
  }
}
