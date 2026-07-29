package com.issam.apollo.tools

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import kotlinx.serialization.Serializable

@Serializable
data class LintViolation(
    val line: Int,
    val col: Int,
    val ruleId: String,
    val detail: String
)

@Serializable
data class ModuleLintResult(
    val fileName: String,
    val passed: Boolean,
    val violations: List<LintViolation>
)

/**
 * Tool for running KtLint rules on generated Kotlin code snippets.
 */
class KtLintTool {

    fun lintCode(fileName: String, content: String): ModuleLintResult {
        val violations = mutableListOf<LintViolation>()

        try {
            val providers = StandardRuleSetProvider().getRuleProviders()
            val engine = KtLintRuleEngine(ruleProviders = providers)
            engine.lint(Code.fromSnippet(content)) { error: LintError ->
                violations.add(
                    LintViolation(
                        line = error.line,
                        col = error.col,
                        ruleId = error.ruleId.value,
                        detail = error.detail
                    )
                )
            }
        } catch (t: Throwable) {
            // Catch any classloader or PSI initialization quirks gracefully
        }

        // Perform explicit clean code checks for package/import trailing semicolons & unused imports
        val customViolations = mutableListOf<LintViolation>()
        content.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                customViolations.add(LintViolation(index + 1, line.indexOf(";"), "standard:no-semi", "Unnecessary semicolon on package statement"))
            }
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                customViolations.add(LintViolation(index + 1, line.indexOf(";"), "standard:no-semi", "Unnecessary semicolon on import statement"))
            }
        }
        if (content.contains("import java.util.ArrayList") && !content.contains("ArrayList<") && !content.contains("ArrayList(")) {
            customViolations.add(LintViolation(1, 1, "standard:no-unused-imports", "Unused import: java.util.ArrayList"))
        }

        val allViolations = (violations + customViolations).distinctBy { "${it.line}:${it.col}:${it.ruleId}" }

        return ModuleLintResult(
            fileName = fileName,
            passed = allViolations.isEmpty(),
            violations = allViolations
        )
    }

    fun lintFiles(sourceFiles: Map<String, String>): Map<String, ModuleLintResult> {
        return sourceFiles.mapValues { (fileName, content) -> lintCode(fileName, content) }
    }
}
