package me.gm.cleaner.client.ui.storageredirect

/** 判断 [path] 是否是 [prefix] 的严格子路径（不包含相等）。 */
internal fun isStrictSubPath(path: String, prefix: String): Boolean {
    if (path == prefix) return false
    val normalizedPrefix = if (prefix.endsWith('/')) prefix else "$prefix/"
    return path.startsWith(normalizedPrefix)
}

/**
 * 为可能被其他规则 target 二次覆盖的 source 追加 `source == target` 的 PRESERVE 规则。
 *
 * 判定条件：source 是当前规则列表中某条规则 target 的严格子路径，说明访问 source 时
 * 可能先被自身规则改写、再被更宽泛的 target 二次匹配，导致嵌套或语义异常。
 *
 * [rules] 需已包含 [addedRules]，返回值是在其后追加保护规则后的完整列表。
 */
internal fun appendPreserveProtection(
    rules: List<Pair<String, String>>,
    addedRules: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val result = rules.toMutableList()
    val targets = result.map { it.second }
    addedRules.forEach { (source, target) ->
        if (source == target) return@forEach
        val needsProtection = targets.any { otherTarget ->
            otherTarget != source && isStrictSubPath(source, otherTarget)
        }
        if (needsProtection && (source to source) !in result) {
            result += source to source
        }
    }
    return result
}

/**
 * 从 [remainingRules] 中剥离 [appendPreserveProtection] 自动追加的保护规则，
 * 使 [MountWizard.retrodictAnswers] 的 round-trip 不把自动规则误判为用户自定义规则。
 *
 * 用户手动添加、且 source 不在 q3 MAP 规则中的 PRESERVE 规则会被保留。
 */
internal fun stripAutoPreserveRules(
    allRules: List<Pair<String, String>>,
    remainingRules: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val allTargets = allRules.map { it.second }
    val q3Sources = remainingRules
        .filter { (source, target) -> source != target }
        .map { it.first }
        .toSet()
    return remainingRules.filterNot { (source, target) ->
        source == target && source in q3Sources &&
            allTargets.any { otherTarget ->
                otherTarget != source && isStrictSubPath(source, otherTarget)
            }
    }
}
