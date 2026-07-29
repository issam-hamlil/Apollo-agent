package com.issam.apollo.tools

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.issam.apollo.state.ModuleSpec
import java.io.File

// ─── Data classes ─────────────────────────────────────────────────────────────

/**
 * Raw AST extraction result for a single .java file.
 * Used internally by [JavaAstTool]; callers receive a [ModuleSpec].
 */
data class JavaClassSpec(
    val packageName: String,
    val className: String,
    val imports: List<String>,
    val fields: List<String>,
    val methods: List<String>,
    val rawSource: String,
    val sourceFilePath: String = ""
)

/** Result of a full-repo analysis: specs + dependency graph + topo order. */
data class RepoAnalysis(
    val specs: Map<String, JavaClassSpec>,
    /** className → list of classNames it directly depends on (within the repo) */
    val dependencyGraph: Map<String, List<String>>,
    /**
     * Topologically sorted list of class names so that every dependency
     * appears before the classes that use it (safe migration order).
     */
    val topologicalOrder: List<String>
)

// ─── Tool ─────────────────────────────────────────────────────────────────────

/**
 * Wraps JavaParser to extract AST data from Java source files and build a
 * dependency graph across an entire repo directory.
 */
class JavaAstTool {

    // ── Single-file parsing ───────────────────────────────────────────────────

    /**
     * Parse one .java file and return its [JavaClassSpec].
     */
    fun parseJavaFile(file: File): JavaClassSpec {
        val cu: CompilationUnit = StaticJavaParser.parse(file)

        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val imports = cu.imports.map { it.nameAsString }

        val mainClass = cu.findFirst(ClassOrInterfaceDeclaration::class.java).orElse(null)
        val className = mainClass?.nameAsString ?: file.nameWithoutExtension

        val fields = mainClass?.fields?.flatMap { field ->
            field.variables.map { v -> "${field.elementType} ${v.nameAsString}" }
        } ?: emptyList()

        val methods = mainClass?.methods?.map { m ->
            buildString {
                if (m.isPublic) append("public ")
                if (m.isPrivate) append("private ")
                if (m.isStatic) append("static ")
                append("${m.typeAsString} ${m.nameAsString}")
                append("(")
                append(m.parameters.joinToString { p -> "${p.typeAsString} ${p.nameAsString}" })
                append(")")
            }
        } ?: emptyList()

        return JavaClassSpec(
            packageName = packageName,
            className = className,
            imports = imports,
            fields = fields,
            methods = methods,
            rawSource = file.readText(),
            sourceFilePath = file.absolutePath
        )
    }

    // ── Repo-level analysis ───────────────────────────────────────────────────

    /**
     * Scan all .java files under [repoDir], parse each one, build a dependency
     * graph, and produce a topological ordering.
     *
     * Dependency detection uses two strategies:
     *  1. Import-based: an import contains another known class name.
     *  2. Source-reference-based: raw source mentions another known class name.
     *
     * @param repoDir  Root directory to scan (walks recursively).
     * @return [RepoAnalysis] containing specs, graph, and sort order.
     */
    fun analyzeRepo(repoDir: File): RepoAnalysis {
        val javaFiles = repoDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()

        if (javaFiles.isEmpty()) {
            return RepoAnalysis(
                specs = emptyMap(),
                dependencyGraph = emptyMap(),
                topologicalOrder = emptyList()
            )
        }

        // ── Step 1: parse all files ───────────────────────────────────────────
        val specs = mutableMapOf<String, JavaClassSpec>()
        for (file in javaFiles) {
            try {
                val spec = parseJavaFile(file)
                specs[spec.className] = spec
            } catch (e: Exception) {
                System.err.println("[JavaAstTool] Failed to parse ${file.name}: ${e.message}")
                // Insert a minimal placeholder so the class is still in the graph
                specs[file.nameWithoutExtension] = JavaClassSpec(
                    packageName = "",
                    className = file.nameWithoutExtension,
                    imports = emptyList(),
                    fields = emptyList(),
                    methods = emptyList(),
                    rawSource = "",
                    sourceFilePath = file.absolutePath
                )
            }
        }

        val knownClasses = specs.keys

        // ── Step 2: build dependency graph ────────────────────────────────────
        val graph = mutableMapOf<String, MutableList<String>>()
        for ((className, spec) in specs) {
            val deps = mutableSetOf<String>()

            for (other in knownClasses) {
                if (other == className) continue

                // import-based: "import com.example.legacy.UserService" contains "UserService"
                val importedViaImport = spec.imports.any { imp ->
                    imp.endsWith(".$other") || imp == other
                }

                // source-reference: raw source contains the class name as a word boundary
                val referencedInSource = Regex("\\b${Regex.escape(other)}\\b")
                    .containsMatchIn(spec.rawSource)

                if (importedViaImport || referencedInSource) {
                    deps.add(other)
                }
            }

            graph[className] = deps.toMutableList()
        }

        // ── Step 3: topological sort (Kahn's algorithm) ───────────────────────
        val topoOrder = topologicalSort(knownClasses.toSet(), graph)

        return RepoAnalysis(
            specs = specs,
            dependencyGraph = graph,
            topologicalOrder = topoOrder
        )
    }

    /**
     * Convert a [JavaClassSpec] to a [ModuleSpec] with an optional LLM summary.
     */
    fun toModuleSpec(
        spec: JavaClassSpec,
        dependsOn: List<String>,
        businessLogicSummary: String = ""
    ): ModuleSpec = ModuleSpec(
        className = spec.className,
        packageName = spec.packageName,
        imports = spec.imports,
        fields = spec.fields,
        methods = spec.methods,
        dependsOn = dependsOn,
        businessLogicSummary = businessLogicSummary,
        sourceFilePath = spec.sourceFilePath
    )

    // ── Dependency graph (legacy API kept for backward compat) ────────────────

    /**
     * Kept for backward compatibility with [CharacterizationTool] etc.
     * Prefer [analyzeRepo] for new code.
     */
    fun extractDependencies(sourceFiles: List<File>): Map<String, List<String>> {
        val knownClasses = sourceFiles.map { it.nameWithoutExtension }.toSet()
        val graph = mutableMapOf<String, MutableList<String>>()

        for (file in sourceFiles) {
            val spec = runCatching { parseJavaFile(file) }.getOrNull() ?: continue
            val deps = mutableListOf<String>()
            for (known in knownClasses) {
                if (known != spec.className && spec.rawSource.contains(known)) {
                    deps.add(known)
                }
            }
            graph[spec.className] = deps
        }

        return graph
    }

    // ── Topological sort ──────────────────────────────────────────────────────

    /**
     * Kahn's algorithm — returns nodes in an order where every dependency of
     * node N appears before N in the list.  Cycles are broken arbitrarily
     * (the remaining nodes are appended at the end).
     */
    private fun topologicalSort(
        nodes: Set<String>,
        edges: Map<String, List<String>>
    ): List<String> {
        // in-degree: how many others depend ON this node
        val inDegree = nodes.associateWith { 0 }.toMutableMap()
        // For topo sort we need "which nodes point TO me" (reverse of deps)
        // edges[A] = [B, C]  means A depends on B and C
        // → for topo order we process B and C before A
        // → in-degree of A increases for each of A's dependencies that haven't been processed
        for ((node, deps) in edges) {
            // A has deps → A cannot be placed until deps are processed → A's in-degree = |deps ∩ nodes|
            inDegree[node] = (inDegree[node] ?: 0) // keep existing; we'll recalculate below
        }

        // Recompute: for each edge A→deps, the "consumer" A has in-degree = number of its dependencies
        val indeg = mutableMapOf<String, Int>()
        for (node in nodes) indeg[node] = 0

        // Build reverse adjacency: dep → list of classes that depend on dep
        val reversedAdj = mutableMapOf<String, MutableList<String>>()
        for (node in nodes) reversedAdj[node] = mutableListOf()

        for ((node, deps) in edges) {
            for (dep in deps) {
                if (dep in nodes) {
                    indeg[node] = (indeg[node] ?: 0) + 1
                    reversedAdj.getOrPut(dep) { mutableListOf() }.add(node)
                }
            }
        }

        val queue = ArrayDeque<String>()
        for ((node, deg) in indeg) {
            if (deg == 0) queue.addLast(node)
        }

        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (consumer in (reversedAdj[node] ?: emptyList())) {
                indeg[consumer] = (indeg[consumer] ?: 1) - 1
                if (indeg[consumer] == 0) queue.addLast(consumer)
            }
        }

        // Handle any remaining nodes (cycles)
        val remaining = nodes - result.toSet()
        if (remaining.isNotEmpty()) {
            System.err.println("[JavaAstTool] Circular dependency detected among: $remaining — appending in arbitrary order.")
            result.addAll(remaining)
        }

        return result
    }
}
