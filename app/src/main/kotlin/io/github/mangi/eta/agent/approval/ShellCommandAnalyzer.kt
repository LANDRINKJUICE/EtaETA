package io.github.mangi.eta.agent.approval

import java.util.Locale

/**
 * Shell 命令的静态写入分析。
 *
 * 目标不是完整实现一个 Shell，而是在命令真正执行之前尽量抓住"会改动系统分区、启动链、
 * 系统私有数据或其他应用私有目录"的写法。判定策略是保守的：拿不准就命中，让用户决定。
 *
 * 已建模的间接执行形态：
 * - `sh -c '...'` / `su -c '...'` / `adb shell '...'`：递归分析内层命令；
 * - `printf ... | sh` / `base64 -d ... | bash`：管道末段是无参 shell 时，把前段拼接后递归分析；
 * - `find ... -exec rm {} \;`：递归分析 `-exec` 后的命令；
 * - `echo ... | xargs rm`：分析 xargs 携带的命令，并标记"操作对象来自标准输入"；
 * - `sh /tmp/x.sh`、`source`、`eval`、`perl/python3/node/awk`：无法读取脚本内容，保守命中。
 *
 * 变量与相对路径：
 * - 同一脚本内 `NAME=value` 的赋值会被收集，并在后续判定前替换 `$NAME` / `${NAME}`；
 * - `cd <dir>` 会被跟踪，相对写入目标按当前 cwd 解析成绝对路径再判定；
 * - 替换不了（`$()`、`${X:-y}`、未知变量）或 cwd 未知的相对路径 → 保守命中；
 * - 带 `session_id` 的持久会话里 cwd 与变量跨调用累计，这类命中升级为 HIGH。
 *
 * 仍然存在的静态边界：
 * - 不解析符号链接与 bind mount（例如 /sdcard 指向 /storage/emulated/0）；
 * - 不解析 alias、shell 函数与 `read` 读入的值；
 * - 解释器内部真正写了什么只能靠"命令里出现受保护路径"兜底。
 */
internal object ShellCommandAnalyzer {
    private const val MAX_NESTING_DEPTH = 4

    /** 分析选项。 */
    data class Options(
        /** 持久终端会话（带 session_id）：cwd 与变量跨调用累计，无法静态确认的目标升级为 HIGH。 */
        val sessionScoped: Boolean = false,
        /** 已知的初始工作目录；null 表示未知。 */
        val initialCwd: String? = null,
    )

    fun analyze(command: String, options: Options = Options()): List<RiskFinding> {
        if (command.isBlank()) return emptyList()
        val findings = mutableListOf<RiskFinding>()
        analyzeScript(command, ShellState(options), depth = 0, out = findings)
        return findings.distinctBy { finding -> finding.category to finding.detail }
    }

    /** 脚本执行期间的变量表与工作目录，跨 `;`/换行分隔的片段累计。 */
    private class ShellState(
        val options: Options,
        val variables: MutableMap<String, String> = mutableMapOf(),
        var cwd: String? = options.initialCwd,
    ) {
        fun snapshot(): ShellState = ShellState(options, variables.toMutableMap(), cwd)
    }

    // ── 脚本切分与递归 ────────────────────────────────────────────────

    private fun analyzeScript(
        script: String,
        state: ShellState,
        depth: Int,
        out: MutableList<RiskFinding>,
    ) {
        if (script.isBlank()) return
        if (depth > MAX_NESTING_DEPTH) {
            out += RiskFinding(
                ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                ToolRiskLevel.MEDIUM,
                "命令嵌套层数过深，无法静态确认安全性：${script.boundedRiskSubject()}",
            )
            return
        }
        extractSubstitutions(script).forEach { nested ->
            analyzeScript(nested, state.snapshot(), depth + 1, out)
        }
        splitPipelines(script).forEach { stages ->
            if (stages.isEmpty()) return@forEach
            val pipedIntoBareShell = stages.size > 1 && isBareShellStage(stages.last())
            stages.forEachIndexed { index, stage ->
                analyzeStage(
                    stage = stage,
                    state = state,
                    depth = depth,
                    out = out,
                    suppressBareShell = pipedIntoBareShell && index == stages.lastIndex,
                )
            }
            if (pipedIntoBareShell) {
                // `printf '...' | sh`、`base64 -d f | bash`：把管道前段当作脚本内容递归分析。
                // echo/printf 取它们要打印的文本（printf 的 \n 还原成换行），其余命令用命令本身。
                analyzeScript(
                    script = pipelineScriptSource(stages.dropLast(1)),
                    state = state.snapshot(),
                    depth = depth + 1,
                    out = out,
                )
            }
        }
    }

    /**
     * 切分为"管道 → 阶段"。`;`、`&&`、`||`、单独 `&`、换行结束一条管道；
     * `|` / `|&` 只切阶段。重定向（`>&`、`&>`）不会被误当作分隔符。
     */
    private fun splitPipelines(script: String): List<List<String>> {
        val pipelines = mutableListOf<List<String>>()
        var stages = mutableListOf<String>()
        val current = StringBuilder()

        fun endStage() {
            stages += current.toString()
            current.clear()
        }

        fun endPipeline() {
            endStage()
            val cleaned = stages.map(String::trim).filter(String::isNotEmpty)
            if (cleaned.isNotEmpty()) pipelines += cleaned
            stages = mutableListOf()
        }

        var index = 0
        var quote: Char? = null
        while (index < script.length) {
            val char = script[index]
            when {
                quote != null -> {
                    current.append(char)
                    if (char == '\\' && quote == '"' && index + 1 < script.length) {
                        current.append(script[index + 1])
                        index++
                    } else if (char == quote) {
                        quote = null
                    }
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    current.append(char)
                }
                char == '\\' && index + 1 < script.length -> {
                    current.append(char).append(script[index + 1])
                    index++
                }
                char == '#' && current.isBlank() -> {
                    while (index < script.length && script[index] != '\n') index++
                    continue
                }
                char == ';' || char == '\n' -> endPipeline()
                char == '&' -> {
                    val previous = current.lastOrNull() ?: '\u0000'
                    val next = if (index + 1 < script.length) script[index + 1] else '\u0000'
                    when {
                        next == '&' -> {
                            endPipeline()
                            index++
                        }
                        previous == '>' || previous == '<' || next == '>' -> current.append(char)
                        else -> endPipeline()
                    }
                }
                char == '|' -> {
                    val previous = current.lastOrNull() ?: '\u0000'
                    val next = if (index + 1 < script.length) script[index + 1] else '\u0000'
                    when {
                        // `>|` 是"覆盖写"重定向，交给 tokenizer
                        previous == '>' -> current.append(char)
                        next == '|' -> {
                            endPipeline()
                            index++
                        }
                        next == '&' -> {
                            endStage()
                            index++
                        }
                        else -> endStage()
                    }
                }
                else -> current.append(char)
            }
            index++
        }
        endPipeline()
        return pipelines
    }

    /** 把管道前段还原成"送进 shell 的脚本文本"：echo/printf 取要打印的内容，其余取命令本身。 */
    private fun pipelineScriptSource(stages: List<String>): String = stages.joinToString("\n") { stage ->
        val words = tokenize(stage).filter { it.redirect == null }.map { it.text }
        val stripped = stripPrefixes(words)
        val name = stripped.firstOrNull()?.substringAfterLast('/')?.lowercase(Locale.ROOT)
        val args = stripped.drop(1).filterNot { it.startsWith("-") }
        if (name == "echo" || name == "printf") {
            args.joinToString(" ").replace("\\n", "\n").replace("\\t", "\t")
        } else {
            stage
        }
    }

    /** 管道末段是否为"没有脚本文件、从 stdin 读命令"的 shell。 */
    private fun isBareShellStage(stage: String): Boolean {
        val words = tokenize(stage).filter { it.redirect == null }.map { it.text }
        if (words.isEmpty()) return false
        val stripped = stripPrefixes(words)
        if (stripped.isEmpty()) return false
        val name = stripped.first().substringAfterLast('/').lowercase(Locale.ROOT)
        if (name !in SHELLS) return false
        val args = stripped.drop(1)
        if (args.any { it == "-c" || it == "-ec" || it == "-lc" }) return false
        return args.none { !it.startsWith("-") }
    }

    /** 提取 `$(...)` 与反引号中的子命令文本，供递归分析。 */
    private fun extractSubstitutions(script: String): List<String> {
        val nested = mutableListOf<String>()
        var index = 0
        var quote: Char? = null
        while (index < script.length) {
            val char = script[index]
            when {
                quote == '\'' -> if (char == '\'') quote = null
                quote == '"' -> when {
                    char == '\\' -> index++
                    char == '"' -> quote = null
                    char == '$' && index + 1 < script.length && script[index + 1] == '(' -> {
                        val end = matchingParenthesis(script, index + 1)
                        if (end > 0) {
                            nested += script.substring(index + 2, end)
                            index = end
                        }
                    }
                    char == '`' -> {
                        val end = script.indexOf('`', index + 1)
                        if (end > index) {
                            nested += script.substring(index + 1, end)
                            index = end
                        }
                    }
                }
                else -> when {
                    char == '\'' || char == '"' -> quote = char
                    char == '$' && index + 1 < script.length && script[index + 1] == '(' -> {
                        val end = matchingParenthesis(script, index + 1)
                        if (end > 0) {
                            nested += script.substring(index + 2, end)
                            index = end
                        }
                    }
                    char == '`' -> {
                        val end = script.indexOf('`', index + 1)
                        if (end > index) {
                            nested += script.substring(index + 1, end)
                            index = end
                        }
                    }
                }
            }
            index++
        }
        return nested
    }

    private fun matchingParenthesis(text: String, openIndex: Int): Int {
        var depth = 0
        var index = openIndex
        while (index < text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    // ── 词法 ─────────────────────────────────────────────────────────

    private class Token(
        val text: String,
        val redirect: RedirectKind? = null,
        /** `2>&1` 这类文件描述符复制：有目标但不能按路径判定。 */
        val duplicateFd: Boolean = false,
    )

    private enum class RedirectKind { INPUT, OUTPUT, APPEND }

    private val FD_DUP = Regex("[0-9]?[<>]&[0-9-]*")

    private fun tokenize(segment: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += Token(buffer.toString())
                buffer.clear()
            }
        }

        var index = 0
        while (index < segment.length) {
            val char = segment[index]
            when {
                char == '\'' -> {
                    val end = segment.indexOf('\'', index + 1)
                    if (end < 0) {
                        buffer.append(segment.substring(index + 1))
                        index = segment.length
                    } else {
                        buffer.append(segment.substring(index + 1, end))
                        index = end + 1
                    }
                }
                char == '"' -> {
                    index++
                    while (index < segment.length && segment[index] != '"') {
                        if (segment[index] == '\\' && index + 1 < segment.length) index++
                        buffer.append(segment[index])
                        index++
                    }
                    index++
                }
                char == '\\' && index + 1 < segment.length -> {
                    buffer.append(segment[index + 1])
                    index += 2
                }
                char == ' ' || char == '\t' -> {
                    flush()
                    index++
                }
                FD_DUP.matchAt(segment, index) != null -> {
                    val match = checkNotNull(FD_DUP.matchAt(segment, index))
                    flush()
                    tokens += Token(match.value, RedirectKind.OUTPUT, duplicateFd = true)
                    index = match.range.last + 1
                }
                else -> {
                    val hasFdPrefix = char.isDigit() &&
                        index + 1 < segment.length &&
                        (segment[index + 1] == '>' || segment[index + 1] == '<')
                    val operator = readRedirect(segment, index + if (hasFdPrefix) 1 else 0)
                    if (operator != null) {
                        flush()
                        tokens += Token(operator.first, operator.second)
                        index += (if (hasFdPrefix) 1 else 0) + operator.first.length
                    } else {
                        buffer.append(char)
                        index++
                    }
                }
            }
        }
        flush()
        return tokens
    }

    /** 识别重定向算子；返回算子文本与种类。 */
    private fun readRedirect(segment: String, index: Int): Pair<String, RedirectKind>? {
        if (index >= segment.length) return null
        val rest = segment.substring(index)
        return when {
            rest.startsWith("&>>") -> "&>>" to RedirectKind.APPEND
            rest.startsWith("&>") -> "&>" to RedirectKind.OUTPUT
            rest.startsWith(">>") -> ">>" to RedirectKind.APPEND
            rest.startsWith(">|") -> ">|" to RedirectKind.OUTPUT
            rest.startsWith("<<<") -> "<<<" to RedirectKind.INPUT
            rest.startsWith("<<") -> "<<" to RedirectKind.INPUT
            rest.startsWith(">") -> ">" to RedirectKind.OUTPUT
            rest.startsWith("<") -> "<" to RedirectKind.INPUT
            else -> null
        }
    }

    // ── 单个阶段 ─────────────────────────────────────────────────────

    private fun analyzeStage(
        stage: String,
        state: ShellState,
        depth: Int,
        out: MutableList<RiskFinding>,
        suppressBareShell: Boolean = false,
    ) {
        val tokens = tokenize(stage)
        if (tokens.isEmpty()) return
        tokens.forEachIndexed { index, token ->
            val kind = token.redirect ?: return@forEachIndexed
            if (kind == RedirectKind.INPUT || token.duplicateFd) return@forEachIndexed
            val rawTarget = tokens.getOrNull(index + 1)?.takeIf { it.redirect == null }?.text
                ?: return@forEachIndexed
            classifyWritePath(substitute(rawTarget, state), "重定向写入", state)?.let(out::add)
        }
        val words = tokens.filter { it.redirect == null }.map { it.text }
        if (words.isEmpty()) return
        val remaining = collectAssignments(words, state)
        if (remaining.isEmpty()) return
        analyzeWords(remaining, state, depth, out, suppressBareShell = suppressBareShell)
    }

    /** 收集前置 `NAME=value`（含 `export NAME=value`），写入状态后返回剩余命令词。 */
    private fun collectAssignments(words: List<String>, state: ShellState): List<String> {
        var index = 0
        while (index < words.size) {
            val head = words[index].substringAfterLast('/').lowercase(Locale.ROOT)
            when {
                head == "export" || head == "declare" || head == "typeset" || head == "readonly" -> {
                    index++
                    while (index < words.size && words[index].startsWith("-")) index++
                }
                else -> {
                    val match = ASSIGNMENT.matchEntire(words[index]) ?: return words.drop(index)
                    state.variables[match.groupValues[1]] = substitute(match.groupValues[2], state)
                    index++
                }
            }
        }
        return words.drop(index)
    }

    private fun analyzeWords(
        rawWords: List<String>,
        state: ShellState,
        depth: Int,
        out: MutableList<RiskFinding>,
        suppressBareShell: Boolean = false,
        stdinOperands: Boolean = false,
    ) {
        if (rawWords.isEmpty()) return
        // su -c / sh -c / adb shell / bash -c 这类包装：把内层命令交给递归分析。
        wrapperCommand(rawWords)?.let { inner ->
            analyzeScript(inner, state.snapshot(), depth + 1, out)
            return
        }
        val words = stripPrefixes(rawWords)
        if (words.isEmpty()) return
        val name = words.first().substringAfterLast('/').lowercase(Locale.ROOT)
        val args = words.drop(1).map { substitute(it, state) }

        if (name == "cd" || name == "pushd") {
            trackWorkingDirectory(args, state)
            return
        }
        if (name in SHELLS) {
            analyzeShellInvocation(name, args, state, depth, out, suppressBareShell)
            return
        }

        when (name) {
            "eval" -> {
                out += indirectExecution("eval 动态拼接命令：${args.joinToString(" ").boundedRiskSubject()}")
                if (args.isNotEmpty()) {
                    analyzeScript(args.joinToString(" "), state.snapshot(), depth + 1, out)
                }
            }
            "source", "." -> out += indirectExecution(
                "source 执行外部脚本 ${args.firstOrNull().orEmpty()}，内容无法静态确认",
            )
            "xargs" -> {
                out += indirectExecution("xargs 的操作对象来自标准输入，无法静态确认")
                val inner = args.dropWhile { it.startsWith("-") }
                if (inner.isNotEmpty()) {
                    analyzeWords(inner, state, depth + 1, out, stdinOperands = true)
                }
            }
            "base64" -> {
                val decoding = args.any { argument ->
                    argument == "-d" || argument == "--decode" || argument == "-di" ||
                        argument == "-id" || argument == "--decode="
                }
                if (decoding) out += indirectExecution("base64 解码结果可能被再次执行")
            }
            "find" -> analyzeFind(args, state, depth, out)
            in INTERPRETER_COMMANDS -> out += indirectExecution(
                "通过 $name 执行代码，代码内容无法静态确认：${args.joinToString(" ").boundedRiskSubject()}",
            )
            "dd" -> analyzeDd(args, state, out)
            "cp", "install", "rsync", "ln", "link" -> lastPathArgument(args)?.let { destination ->
                classifyWritePath(destination, "$name 目标", state)?.let(out::add)
            }
            "mv" -> pathArguments(args).forEach { path ->
                // mv 会把文件从源位置移走，源与目标都要按写入判定。
                classifyWritePath(path, "mv 操作目标", state)?.let(out::add)
            }
            "rm", "rmdir", "unlink", "shred", "truncate" ->
                analyzeDelete(name, args, state, stdinOperands, out)
            "touch", "mkdir", "tee", "chmod", "chown", "chcon", "setfacl", "restorecon", "setxattr" ->
                pathArguments(args).forEach { path ->
                    classifyWritePath(path, "$name 作用目标", state)?.let(out::add)
                }
            "mount", "remount" -> analyzeMount(args, state, out, forcedRemount = name == "remount")
            "setprop", "resetprop" -> analyzeSetProperty(name, args, out)
            "magisk", "magiskboot", "magiskpolicy", "magiskhide" -> analyzeMagisk(name, args, out)
            "fastboot" -> analyzeFastboot(args, out)
            "pm" -> analyzePackageManager("pm", args, out)
            "cmd" -> if (args.firstOrNull()?.lowercase(Locale.ROOT) == "package") {
                analyzePackageManager("cmd package", args.drop(1), out)
            }
            "settings" -> analyzeSettings(args, out)
            "reboot" -> analyzeReboot(args, out)
            "sqlite3" -> pathArguments(args).firstOrNull()?.let { database ->
                classifyWritePath(database, "写数据库", state)?.let(out::add)
            }
            "sed" -> if (args.any { it == "-i" || it.startsWith("-i") || it.startsWith("--in-place") }) {
                pathArguments(args).forEach { path ->
                    classifyWritePath(path, "原地改写", state)?.let(out::add)
                }
            }
            else -> if (isDiskFormatCommand(name)) {
                out += RiskFinding(
                    ToolRiskCategory.DISK_FORMAT,
                    ToolRiskLevel.CRITICAL,
                    "格式化或改写分区表：$name ${args.joinToString(" ").boundedRiskSubject()}",
                )
            } else {
                fallbackUnhandled(name, words, state, stdinOperands, out)
            }
        }
    }

    private fun analyzeShellInvocation(
        name: String,
        args: List<String>,
        state: ShellState,
        depth: Int,
        out: MutableList<RiskFinding>,
        suppressBareShell: Boolean,
    ) {
        val dashC = args.indexOfFirst { argument ->
            argument == "-c" || argument == "-ec" || argument == "-lc" || argument == "-xc"
        }
        if (dashC >= 0) {
            analyzeScript(args.drop(dashC + 1).joinToString(" "), state.snapshot(), depth + 1, out)
            return
        }
        val scriptFile = args.firstOrNull { !it.startsWith("-") }
        if (scriptFile != null) {
            out += indirectExecution("通过 $name 执行脚本文件 $scriptFile，内容无法静态确认")
            return
        }
        if (!suppressBareShell) {
            out += indirectExecution("$name 从标准输入读取命令，内容无法静态确认")
        }
    }

    private fun analyzeFind(
        args: List<String>,
        state: ShellState,
        depth: Int,
        out: MutableList<RiskFinding>,
    ) {
        val execIndex = args.indexOfFirst { it in EXECUTION_PREDICATES }
        if (execIndex >= 0) {
            // -exec 会把匹配结果交给命令：搜索根就是可能被改写的范围。
            args.takeWhile { !it.startsWith("-") }.forEach { root ->
                classifyWritePath(root, "find ${args[execIndex]} 作用范围", state)?.let(out::add)
            }
            val command = args.drop(execIndex + 1).takeWhile { it != ";" && it != "+" && it != "\\;" }
            if (command.isEmpty()) {
                out += indirectExecution("find ${args[execIndex]} 未提供可解析的命令")
            } else {
                analyzeWords(command, state, depth + 1, out, stdinOperands = true)
            }
        }
        if (args.any { it == "-delete" }) {
            args.takeWhile { !it.startsWith("-") }.forEach { root ->
                classifyWritePath(root, "find -delete 删除", state)?.let(out::add)
            }
            out += RiskFinding(
                ToolRiskCategory.DESTRUCTIVE_DELETE,
                ToolRiskLevel.MEDIUM,
                "find -delete 会删除匹配到的文件",
            )
        }
    }

    // ── 具体规则 ─────────────────────────────────────────────────────

    private fun analyzeDd(args: List<String>, state: ShellState, out: MutableList<RiskFinding>) {
        args.filter { it.startsWith("of=") }.forEach { argument ->
            val target = argument.removePrefix("of=")
            classifyWritePath(target, "dd 写入", state)?.let(out::add)
            if (isRawBlockDevice(target)) {
                out += RiskFinding(
                    ToolRiskCategory.DISK_FORMAT,
                    ToolRiskLevel.CRITICAL,
                    "向裸块设备写入数据：$target",
                )
            }
        }
    }

    private fun analyzeDelete(
        command: String,
        args: List<String>,
        state: ShellState,
        stdinOperands: Boolean,
        out: MutableList<RiskFinding>,
    ) {
        val flags = args.filter { it.startsWith("-") }
        val recursive = flags.any { flag ->
            flag == "--recursive" ||
                (!flag.startsWith("--") && (flag.contains('r') || flag.contains('R')))
        }
        if (stdinOperands && recursive) {
            out += RiskFinding(
                ToolRiskCategory.DESTRUCTIVE_DELETE,
                ToolRiskLevel.MEDIUM,
                "递归删除的对象来自标准输入，无法静态确认",
            )
        }
        val targets = pathArguments(args)
        if (targets.isEmpty()) {
            // `rm -rf $DIR` 这类变量无法静态解析；删除对象也可能来自标准输入（xargs/find）。
            if (recursive || stdinOperands) {
                out += RiskFinding(
                    ToolRiskCategory.DESTRUCTIVE_DELETE,
                    ToolRiskLevel.MEDIUM,
                    "删除的目标不是可静态确认的路径：${args.joinToString(" ").boundedRiskSubject()}",
                )
            }
            return
        }
        targets.forEach { target ->
            classifyWritePath(target, "删除", state)?.let(out::add)
            val wildcard = target.contains('*') || target.contains('?') || target.contains('[')
            val prefixDepth = ProtectedPaths.normalize(target)
                .split('/')
                .count { it.isNotEmpty() }
                .let { if (wildcard) it - 1 else it }
            when {
                ProtectedPaths.isBroadDeleteTarget(target) && recursive -> out += RiskFinding(
                    ToolRiskCategory.DESTRUCTIVE_DELETE,
                    if (ProtectedPaths.normalize(target) == "/") ToolRiskLevel.CRITICAL else ToolRiskLevel.HIGH,
                    "递归删除 $target",
                )
                wildcard && recursive && prefixDepth <= 1 -> out += RiskFinding(
                    ToolRiskCategory.DESTRUCTIVE_DELETE,
                    ToolRiskLevel.HIGH,
                    "通配符递归删除 $target",
                )
                wildcard && !recursive && prefixDepth <= 1 -> out += RiskFinding(
                    ToolRiskCategory.DESTRUCTIVE_DELETE,
                    ToolRiskLevel.MEDIUM,
                    "通配符删除 $target",
                )
            }
        }
    }

    private fun analyzeMount(
        args: List<String>,
        state: ShellState,
        out: MutableList<RiskFinding>,
        forcedRemount: Boolean = false,
    ) {
        val optionValues = mutableListOf<String>()
        args.forEachIndexed { index, argument ->
            when {
                argument == "-o" || argument == "--options" || argument == "-O" ->
                    args.getOrNull(index + 1)?.let(optionValues::add)
                argument.startsWith("-o") && argument.length > 2 -> optionValues += argument.substring(2)
                argument.startsWith("--options=") -> optionValues += argument.substringAfter('=')
            }
        }
        val options = optionValues.joinToString(",").lowercase(Locale.ROOT)
        val remount = forcedRemount || options.contains("remount") || args.any { it.equals("remount", true) }
        val readWrite = options.split(',').any { it.trim() == "rw" }
        val readOnly = options.split(',').any { it.trim() == "ro" }
        val targets = pathArguments(args).filterNot { it in optionValues || it == "remount" }
        val protectedTarget = targets
            .mapNotNull { resolvePath(it, state) }
            .firstOrNull { resolved ->
                ProtectedPaths.isSystemPartition(resolved) || ProtectedPaths.isBootChain(resolved)
            }
        when {
            remount || (readWrite && !readOnly) -> out += RiskFinding(
                ToolRiskCategory.REMOUNT_READ_WRITE,
                ToolRiskLevel.CRITICAL,
                "以读写方式挂载/重新挂载 ${targets.lastOrNull() ?: "分区"}",
            )
            protectedTarget != null && !readOnly -> out += RiskFinding(
                ToolRiskCategory.REMOUNT_READ_WRITE,
                ToolRiskLevel.MEDIUM,
                "挂载受保护分区 $protectedTarget（未声明只读）",
            )
        }
    }

    private fun analyzeSetProperty(command: String, args: List<String>, out: MutableList<RiskFinding>) {
        val key = args.firstOrNull { !it.startsWith("-") } ?: return
        val lower = key.lowercase(Locale.ROOT)
        val persistent = lower.startsWith("persist.") ||
            lower.startsWith("ro.") ||
            lower.startsWith("vendor.") ||
            lower.contains("selinux") ||
            lower.startsWith("ctl.")
        if (persistent || command == "resetprop") {
            out += RiskFinding(
                ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                if (lower.startsWith("ro.")) ToolRiskLevel.HIGH else ToolRiskLevel.MEDIUM,
                "修改持久系统属性 $key",
            )
        }
    }

    private fun analyzeMagisk(command: String, args: List<String>, out: MutableList<RiskFinding>) {
        val flags = args.filter { it.startsWith("-") }.map { it.lowercase(Locale.ROOT) }
        val subcommand = args.firstOrNull { !it.startsWith("-") }?.lowercase(Locale.ROOT)
        when (command) {
            "magiskboot" -> out += RiskFinding(
                ToolRiskCategory.BOOT_CHAIN_WRITE,
                ToolRiskLevel.HIGH,
                "使用 magiskboot 处理 boot 镜像",
            )
            "magiskpolicy" -> out += RiskFinding(
                ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                ToolRiskLevel.HIGH,
                "修改 SELinux 策略（magiskpolicy）",
            )
            "magiskhide" -> out += RiskFinding(
                ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                ToolRiskLevel.MEDIUM,
                "调整 Magisk 隐藏配置",
            )
            else -> {
                val patchesBoot = flags.any { it == "--install" || it == "--patch" || it == "-p" } ||
                    subcommand == "install"
                val modules = flags.any {
                    it == "--install-module" || it == "--remove-modules" || it.startsWith("--install-module=")
                }
                val restore = flags.any { it == "--restore" || it == "--uninstall" } || subcommand == "uninstall"
                when {
                    patchesBoot -> out += RiskFinding(
                        ToolRiskCategory.BOOT_CHAIN_WRITE,
                        ToolRiskLevel.CRITICAL,
                        "Magisk 将修改 boot 镜像（${args.joinToString(" ").boundedRiskSubject()}）",
                    )
                    modules -> out += RiskFinding(
                        ToolRiskCategory.SYSTEM_DATA_WRITE,
                        ToolRiskLevel.HIGH,
                        "安装或移除 Magisk 模块（写入 /data/adb）",
                    )
                    restore -> out += RiskFinding(
                        ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                        ToolRiskLevel.HIGH,
                        "Magisk 恢复原始镜像（${args.joinToString(" ").boundedRiskSubject()}）",
                    )
                    flags.any { it == "--preinit-device" || it == "--daemon" || it == "--post-fs-data" } ->
                        out += RiskFinding(
                            ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                            ToolRiskLevel.MEDIUM,
                            "修改 Magisk 守护/预初始化行为",
                        )
                }
            }
        }
    }

    private fun analyzeFastboot(args: List<String>, out: MutableList<RiskFinding>) {
        val subcommand = args.firstOrNull { !it.startsWith("-") }?.lowercase(Locale.ROOT) ?: return
        if (subcommand in setOf("flash", "flashall", "erase", "format", "set_active", "boot", "oem", "flashing")) {
            out += RiskFinding(
                ToolRiskCategory.BOOT_CHAIN_WRITE,
                ToolRiskLevel.CRITICAL,
                "fastboot $subcommand（${args.joinToString(" ").boundedRiskSubject()}）",
            )
        }
    }

    private fun analyzePackageManager(prefix: String, args: List<String>, out: MutableList<RiskFinding>) {
        val subcommand = args.firstOrNull { !it.startsWith("-") }?.lowercase(Locale.ROOT) ?: return
        val mutating = setOf(
            "uninstall", "uninstall-system-updates", "disable", "disable-user", "disable-until-used",
            "clear", "suspend", "hide", "block", "wipe", "delete", "restore", "trim-caches",
        )
        if (subcommand in mutating) {
            val subject = nonOptionArguments(args).drop(1)
                .filterNot { it.all { char -> char.isDigit() } }
                .firstOrNull()
            out += RiskFinding(
                ToolRiskCategory.SYSTEM_APP_MUTATION,
                if (subcommand == "clear" || subcommand == "uninstall") ToolRiskLevel.HIGH else ToolRiskLevel.MEDIUM,
                "$prefix $subcommand ${subject ?: ""}".trim(),
            )
        }
    }

    private fun analyzeSettings(args: List<String>, out: MutableList<RiskFinding>) {
        val positional = nonOptionArguments(args)
        val operation = positional.firstOrNull()?.lowercase(Locale.ROOT) ?: return
        if (operation !in setOf("put", "delete", "reset")) return
        val namespace = positional.getOrNull(1)?.lowercase(Locale.ROOT) ?: return
        val key = positional.getOrNull(2).orEmpty()
        if (isSensitiveSettingKey(namespace, key)) {
            out += RiskFinding(
                ToolRiskCategory.SENSITIVE_SETTING_WRITE,
                ToolRiskLevel.MEDIUM,
                "settings $operation $namespace $key",
            )
        }
    }

    private fun analyzeReboot(args: List<String>, out: MutableList<RiskFinding>) {
        val target = args.firstOrNull { !it.startsWith("-") }?.lowercase(Locale.ROOT) ?: return
        if (target in setOf("bootloader", "recovery", "edl", "fastboot", "sideload", "download")) {
            out += RiskFinding(
                ToolRiskCategory.BOOT_CHAIN_WRITE,
                ToolRiskLevel.MEDIUM,
                "重启到 $target 模式",
            )
        }
    }

    /**
     * 未显式建模的命令：只要出现受保护路径，就按"无法确认只读"保守命中。
     *
     * 只读命令（cat/ls/grep…）不按路径拦截，但仍要检查参数里是否藏了执行算子
     * （`-exec` / `-execdir` / `-ok` / `--exec`），避免 `find /system -exec rm {} \;` 这类写法漏判。
     */
    private fun fallbackUnhandled(
        name: String,
        words: List<String>,
        state: ShellState,
        stdinOperands: Boolean,
        out: MutableList<RiskFinding>,
    ) {
        if (name in ANALYZED_COMMANDS) return
        val args = words.drop(1)
        val executionOperator = args.firstOrNull { it in EXECUTION_PREDICATES }
        if (executionOperator != null) {
            out += indirectExecution("$name 的参数里带有执行算子 $executionOperator，内部命令无法静态确认")
            return
        }
        if (name in READ_ONLY_COMMANDS) return
        args.forEach { word ->
            if (word.startsWith("-")) return@forEach
            pathFragments(word).forEach { fragment ->
                suspiciousPathFinding(fragment, state)?.let(out::add)
            }
        }
        if (stdinOperands && name in WRITE_COMMANDS && pathArguments(args).isEmpty()) {
            out += RiskFinding(
                ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND,
                ToolRiskLevel.MEDIUM,
                "$name 在影子环境中操作标准输入给出的对象，无法静态确认",
            )
        }
    }

    private fun isDiskFormatCommand(name: String): Boolean =
        name in DISK_FORMAT_COMMANDS || name.startsWith("mkfs") || name.startsWith("mke2fs")

    private fun pathFragments(word: String): List<String> =
        word.split('\'', '"', '(', ')', ',', ';', '=', ':', ' ', '\t', '|', '&', '$', '`', '<', '>', '{', '}', '[', ']')
            .filter { fragment -> fragment.startsWith("/") }

    private fun suspiciousPathFinding(rawPath: String, state: ShellState): RiskFinding? {
        val path = resolvePath(rawPath, state) ?: return null
        if (path in DEVICE_SAFE_SINKS) return null
        ProtectedPaths.foreignPackageOf(path)?.let { packageName ->
            return RiskFinding(
                ToolRiskCategory.FOREIGN_APP_DATA_WRITE,
                ToolRiskLevel.LOW,
                "命令涉及应用 $packageName 的私有目录 $path（无法静态确认只读）",
            )
        }
        val category = when {
            ProtectedPaths.isBootChain(path) -> ToolRiskCategory.BOOT_CHAIN_WRITE
            ProtectedPaths.isSystemPartition(path) -> ToolRiskCategory.SYSTEM_PARTITION_WRITE
            ProtectedPaths.isSystemData(path) -> ToolRiskCategory.SYSTEM_DATA_WRITE
            else -> return null
        }
        return RiskFinding(category, ToolRiskLevel.MEDIUM, "命令涉及受保护路径 $path（无法静态确认只读）")
    }

    // ── 目标解析与路径分类 ───────────────────────────────────────────

    /** 把目标解析成绝对路径；无法解析（变量、家目录、未知 cwd 的相对路径）时返回 null。 */
    private fun resolvePath(rawPath: String, state: ShellState): String? {
        val path = rawPath.trim()
        if (path.isEmpty() || path == "-") return null
        if (path.startsWith("/")) return ProtectedPaths.normalize(path)
        if (path.contains('$') || path.contains('`')) return null
        if (path.startsWith("~")) return null
        val cwd = state.cwd ?: return null
        return ProtectedPaths.normalize("$cwd/$path")
    }

    private fun classifyWritePath(
        rawPath: String,
        action: String,
        state: ShellState,
    ): RiskFinding? {
        val path = rawPath.trim()
        if (path.isEmpty() || path == "-") return null
        if (path in DEVICE_SAFE_SINKS) return null
        val resolved = resolvePath(path, state)
            ?: return unresolvedTargetFinding(action, path, state)
        if (resolved in DEVICE_SAFE_SINKS) return null
        ProtectedPaths.foreignPackageOf(resolved)?.let { packageName ->
            return RiskFinding(
                ToolRiskCategory.FOREIGN_APP_DATA_WRITE,
                ToolRiskLevel.HIGH,
                "$action $resolved（应用 $packageName 的私有目录）",
            )
        }
        if (ProtectedPaths.isBootChain(resolved)) {
            return RiskFinding(ToolRiskCategory.BOOT_CHAIN_WRITE, ToolRiskLevel.CRITICAL, "$action $resolved")
        }
        if (ProtectedPaths.isSystemPartition(resolved)) {
            return RiskFinding(ToolRiskCategory.SYSTEM_PARTITION_WRITE, ToolRiskLevel.CRITICAL, "$action $resolved")
        }
        if (ProtectedPaths.isSystemData(resolved)) {
            return RiskFinding(ToolRiskCategory.SYSTEM_DATA_WRITE, ToolRiskLevel.HIGH, "$action $resolved")
        }
        return null
    }

    private fun unresolvedTargetFinding(action: String, path: String, state: ShellState): RiskFinding {
        val sessionNote = if (state.options.sessionScoped) {
            "；持久终端会话的 cwd 与变量会跨调用累计"
        } else {
            ""
        }
        return RiskFinding(
            ToolRiskCategory.UNRESOLVED_TARGET,
            if (state.options.sessionScoped) ToolRiskLevel.HIGH else ToolRiskLevel.MEDIUM,
            "$action 的目标无法静态确认：$path$sessionNote",
        )
    }

    private fun indirectExecution(detail: String): RiskFinding = RiskFinding(
        ToolRiskCategory.INDIRECT_EXECUTION,
        ToolRiskLevel.MEDIUM,
        "间接执行，无法静态确认：$detail",
    )

    // ── 变量与工作目录 ───────────────────────────────────────────────

    private val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")

    private val VARIABLE_REFERENCE by lazy { Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)") }

    private fun substitute(text: String, state: ShellState): String {
        if (!text.contains('$') || state.variables.isEmpty()) return text
        return VARIABLE_REFERENCE.replace(text) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            state.variables[name] ?: match.value
        }
    }

    private fun trackWorkingDirectory(args: List<String>, state: ShellState) {
        val target = args.firstOrNull { !it.startsWith("-") }
        state.cwd = when {
            target == null || target == "-" -> null
            target.startsWith("/") -> ProtectedPaths.normalize(target)
            target.startsWith("~") -> null
            target.contains('$') || target.contains('`') -> null
            else -> state.cwd?.let { cwd -> ProtectedPaths.normalize("$cwd/$target") }
        }
    }

    // ── 词法工具 ─────────────────────────────────────────────────────

    private fun stripPrefixes(words: List<String>): List<String> {
        var index = 0
        var guard = 0
        while (index < words.size && guard++ < 16) {
            val head = words[index].substringAfterLast('/').lowercase(Locale.ROOT)
            when {
                ASSIGNMENT.matches(words[index]) -> index++
                head == "sudo" || head == "doas" -> {
                    index++
                    while (index < words.size && words[index].startsWith("-")) index++
                }
                head == "env" -> {
                    index++
                    while (index < words.size && (words[index].startsWith("-") || ASSIGNMENT.matches(words[index]))) {
                        index++
                    }
                }
                head in TRANSPARENT_PREFIXES -> index++
                else -> break
            }
        }
        return words.drop(index)
    }

    private fun wrapperCommand(words: List<String>): String? {
        var index = 0
        var guard = 0
        while (index < words.size && guard++ < 16) {
            val head = words[index].substringAfterLast('/').lowercase(Locale.ROOT)
            when {
                ASSIGNMENT.matches(words[index]) -> index++
                head == "sudo" || head == "doas" -> {
                    index++
                    while (index < words.size && words[index].startsWith("-")) index++
                }
                head in setOf("nohup", "time", "exec", "command", "setsid", "busybox", "toybox", "env") -> index++
                else -> break
            }
        }
        if (index >= words.size) return null
        val head = words[index].substringAfterLast('/').lowercase(Locale.ROOT)
        if (head == "adb") {
            val shellIndex = words.indexOfFirst { it.equals("shell", ignoreCase = true) }
            if (shellIndex < 0) return null
            return words.drop(shellIndex + 1).joinToString(" ").ifBlank { null }
        }
        if (head == "su") {
            val dashC = words.indexOf("-c")
            if (dashC >= 0) return words.getOrNull(dashC + 1)
            return null
        }
        if (head in SHELLS) {
            val dashC = words.indexOf("-c")
            if (dashC >= 0) return words.drop(dashC + 1).joinToString(" ")
            return null
        }
        return null
    }

    private fun pathArguments(args: List<String>): List<String> = args
        .filter { it.isNotEmpty() && !it.startsWith("-") }

    /** cp/mv 等命令的目标位：跳过 `-t dir` 形式的显式目标，否则取最后一个参数。 */
    private fun lastPathArgument(args: List<String>): String? {
        args.forEachIndexed { index, argument ->
            if (argument == "-t" || argument == "--target-directory") {
                return args.getOrNull(index + 1)
            }
            if (argument.startsWith("--target-directory=")) {
                return argument.substringAfter('=')
            }
        }
        return pathArguments(args).lastOrNull()
    }

    private fun nonOptionArguments(args: List<String>): List<String> {
        val values = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            when {
                argument == "--user" || argument == "-u" -> index += 2
                argument.startsWith("--user=") -> index++
                argument.startsWith("-") -> index++
                else -> {
                    values += argument
                    index++
                }
            }
        }
        return values
    }

    fun isSensitiveSettingKey(namespace: String, key: String): Boolean {
        val lowerKey = key.lowercase(Locale.ROOT)
        if (lowerKey.isEmpty()) return true
        // secure 命名空间承载无障碍、输入法、安装来源、位置等安全相关键，一律需要确认。
        if (namespace == "secure") return true
        return SENSITIVE_SETTING_PATTERNS.any { pattern -> lowerKey.contains(pattern) }
    }

    private fun isRawBlockDevice(rawPath: String): Boolean {
        val path = ProtectedPaths.normalize(rawPath)
        if (path in DEVICE_SAFE_SINKS) return false
        if (path.startsWith("/dev/block")) return true
        if (!path.startsWith("/dev/")) return false
        return RAW_BLOCK_PATTERN.matches(path.removePrefix("/dev/"))
    }

    private val RAW_BLOCK_PATTERN = Regex(
        "^(mmcblk[0-9]+([a-z][0-9]+)?|sd[a-z]+[0-9]*|nvme[0-9]+n[0-9]+(p[0-9]+)?|loop[0-9]+|dm-[0-9]+|" +
            "[0-9]+:[0-9]+|disk/.*|by-name/.*|by-path/.*)$",
    )

    private val TRANSPARENT_PREFIXES = setOf("busybox", "toybox", "time", "nohup", "command")

    private val SHELLS = setOf("sh", "bash", "dash", "ash", "mksh", "zsh", "ksh")

    private val INTERPRETER_COMMANDS = setOf(
        "awk", "gawk", "mawk", "nawk", "perl", "python", "python2", "python3", "node", "nodejs",
        "ruby", "php", "lua", "deno", "tclsh",
    )

    /** 会再执行一条命令的谓词（find 系列）。 */
    private val EXECUTION_PREDICATES = setOf("-exec", "-execdir", "-ok", "-okdir", "--exec")

    private val DEVICE_SAFE_SINKS = setOf(
        "/dev/null", "/dev/zero", "/dev/urandom", "/dev/random", "/dev/stdout", "/dev/stderr",
        "/dev/tty", "/dev/full",
    )

    private val SENSITIVE_SETTING_PATTERNS = listOf(
        "adb",
        "development",
        "accessib",
        "install_non_market",
        "install_",
        "mock_location",
        "location_providers",
        "input_method",
        "default_",
        "enabled_",
        "package_verifier",
        "private_dns",
        "http_proxy",
        "proxy",
        "airplane",
        "stay_on_while_plugged_in",
        "selinux",
        "encrypt",
        "lock_screen",
        "password",
        "factory_reset",
        "oem_unlock",
        "device_provisioned",
        "user_setup_complete",
        "wifi_",
        "bluetooth_",
        "mobile_data",
        "tether",
        "vpn",
        "debug",
    )

    private val DISK_FORMAT_COMMANDS = setOf(
        "mkfs", "mkdosfs", "mformat", "fdisk", "sfdisk", "parted", "sgdisk", "wipefs",
        "blkdiscard", "make_ext4fs", "e2fsck", "mke2fs",
    )

    /**
     * 已显式建模的命令：目标由具体规则判定，不再走"出现受保护路径就命中"的兜底，
     * 以免把 `cp /system/build.prop /sdcard/` 这种只读复制误拦。
     */
    private val ANALYZED_COMMANDS = setOf(
        "dd", "cp", "mv", "rsync", "install", "ln", "link", "rm", "rmdir", "unlink", "shred",
        "truncate", "touch", "mkdir", "tee", "chmod", "chown", "chcon", "setfacl", "restorecon",
        "setxattr", "mount", "remount", "setprop", "resetprop", "magisk", "magiskboot",
        "magiskpolicy", "magiskhide", "fastboot", "mke2fs", "mkfs", "mkdosfs", "mformat", "fdisk",
        "sfdisk", "parted", "sgdisk", "wipefs", "blkdiscard", "make_ext4fs", "e2fsck", "pm", "cmd",
        "settings", "reboot", "sqlite3", "sed", "find", "xargs", "eval", "source", "base64", "cd", ".",
    )

    /** 明确的只读命令：只出现在受保护路径上也不拦截。 */
    private val READ_ONLY_COMMANDS = setOf(
        "cat", "bat", "less", "more", "ls", "ll", "la", "stat", "head", "tail", "grep", "egrep",
        "fgrep", "rg", "ag", "fd", "file", "du", "df", "blkid", "lsblk", "getprop",
        "dumpsys", "id", "whoami", "uname", "wc", "cmp", "diff", "xxd", "od", "hexdump", "md5sum",
        "sha1sum", "sha256sum", "sha512sum", "cksum", "sum", "strings", "readlink", "realpath",
        "dirname", "basename", "echo", "printf", "pwd", "date", "ps", "top", "free", "uptime",
        "which", "type", "env", "lsmod", "lsof", "ss", "netstat", "ip", "ifconfig", "ping",
        "logcat", "dmesg", "tree", "nl", "sort", "uniq", "cut", "tr", "column", "jq", "test",
        "true", "false", "sleep", "wait", "zcat", "zipinfo", "help", "man",
    )

    /** 会改动文件系统的命令，用于"操作对象来自标准输入"时的保守判定。 */
    private val WRITE_COMMANDS = setOf(
        "cp", "mv", "rm", "rmdir", "mkdir", "touch", "chmod", "chown", "ln", "tee", "truncate",
    )
}
