package me.gm.cleaner.core.config

import java.util.regex.Pattern

/**
 * 存储策略读取视图：Store 直读加用户展开。
 *
 * P9 收敛：调用方经此读配置，不再经过含旧 JSON 双轨的门面。
 * 旧格式分享导出等互操作仍走旧适配器，不在此列。
 */
fun ConfiguredPolicyStore.getPackageSrZipped(packageName: String, userId: Int = 0): List<Pair<String, String>> =
    readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
        .map { expandPathAsUser(it.source, userId) to expandPathAsUser(it.target, userId) }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageSr(packageName: String, userId: Int): Pair<List<String>, List<String>> {
    val rules = readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
    return rules.map { expandPathAsUser(it.source, userId) } to
        rules.map { expandPathAsUser(it.target, userId) }
}

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageSrCount(packageName: String): Int =
    readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules?.size ?: 0

/** @see getPackageSrZipped */
val ConfiguredPolicyStore.srPackages: Set<String>
    get() = readRedirect().envelope.redirectPolicies
        .map { it.scope.packageName }.toSet()

/** 配置了只读规则的包名集合。 */
val ConfiguredPolicyStore.readOnlyPackages: Set<String>
    get() = readReadOnly().envelope.readOnlyRules
        .map { it.scope.packageName }.toSet()

/** @see getPackageSrZipped */
val ConfiguredPolicyStore.srRulesCount: Int
    get() = readRedirect().envelope.redirectPolicies.sumOf { it.rules.size }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getUninstalledSrPackages(installedPackages: Set<String>): List<String> {
    val packages = readRedirect().envelope.redirectPolicies
        .map { it.scope.packageName }.toSet()
    return (packages - installedPackages).toList()
}

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageReadOnly(packageName: String, userId: Int = 0): List<String> =
    readReadOnly().envelope.readOnlyRules
        .filter { it.scope.packageName == packageName }
        .map { expandPathAsUser(it.visiblePath, userId) }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getUninstalledReadOnlyPackages(installedPackages: Set<String>): List<String> {
    val packages = readReadOnly().envelope.readOnlyRules
        .map { it.scope.packageName }.toSet()
    return (packages - installedPackages).toList()
}

/**
 * 一次快照构建的批量策略索引。
 *
 * 为什么需要：`getPackageSrCount()` / `getPackageReadOnly()` 每次调用都会
 * `readRedirect()` / `readReadOnly()`，即读盘 + 解析整个 JSON + 计算 revision。
 * 应用列表对几百个包逐个调用时，这个成本会线性放大；本索引只需一次
 * [ConfiguredPolicyStore.readSnapshot]，之后按包名 O(1) 查询。
 *
 * 索引只保存不可变结果，可安全跨线程读取。
 */
class PolicyRuleIndex internal constructor(
    private val srCounts: Map<String, Int>,
    private val readOnlyPathsByPackage: Map<String, List<String>>,
) {
    /** 与 [getPackageSrCount] 结果一致。 */
    fun srCount(packageName: String): Int = srCounts[packageName] ?: 0

    /** 与 `getPackageReadOnly(packageName).size` 结果一致。 */
    fun readOnlyCount(packageName: String): Int =
        readOnlyPathsByPackage[packageName]?.size ?: 0

    /**
     * 与 [getPackageReadOnly] 结果一致；userId 非 0 时按同一规则展开路径。
     */
    fun readOnlyPaths(packageName: String, userId: Int = 0): List<String> {
        val paths = readOnlyPathsByPackage[packageName] ?: return emptyList()
        return if (userId == 0) paths else paths.map { expandPathAsUser(it, userId) }
    }

    /** 配置过任意规则（重定向或只读）的包名集合。 */
    val configuredPackages: Set<String>
        get() = srCounts.keys + readOnlyPathsByPackage.keys
}

/** 纯函数：从一次已读取的快照构建索引，不触发额外磁盘读取。 */
fun ConfiguredPolicySnapshot.toPolicyRuleIndex(): PolicyRuleIndex {
    val srCounts = HashMap<String, Int>(redirect.envelope.redirectPolicies.size)
    for (policy in redirect.envelope.redirectPolicies) {
        srCounts[policy.scope.packageName] = policy.rules.size
    }
    val readOnlyPaths = HashMap<String, MutableList<String>>()
    for (rule in readOnly.envelope.readOnlyRules) {
        readOnlyPaths.getOrPut(rule.scope.packageName) { mutableListOf() }
            .add(rule.visiblePath)
    }
    return PolicyRuleIndex(
        srCounts = srCounts,
        readOnlyPathsByPackage = readOnlyPaths.mapValues { it.value.toList() },
    )
}

/** 一次读取两个策略域后构建索引，适合后台批量读取。 */
fun ConfiguredPolicyStore.policyRuleIndex(): PolicyRuleIndex =
    readSnapshot().toPolicyRuleIndex()

private val VIEW_APP_DATA_DIR_PATHS: Pattern by lazy {
    Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/)?([^/]+)?(/.*)?")
}

private fun expandPathAsUser(path: String, userId: Int): String {
    if (userId == 0) {
        return path
    }
    val matcher = VIEW_APP_DATA_DIR_PATHS.matcher(path)
    if (!matcher.matches()) {
        return path
    }
    val builder = StringBuilder()
    for (i in 1..matcher.groupCount()) {
        val group = matcher.group(i) ?: continue
        if (group.all { it.isDigit() }) {
            builder.append(userId)
        } else {
            builder.append(group)
        }
    }
    return builder.toString()
}
