package com.openrhodes.ui

/** 展示名称省略连字符及后缀；持久化名称和设备身份保持完整。 */
internal fun lampDisplayName(name: String): String = name.substringBefore('-').trimEnd().ifBlank { name }
