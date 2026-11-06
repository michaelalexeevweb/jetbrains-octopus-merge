package octopusmerge.core

/**
 * Fails a test as soon as production code runs a git command missing from
 * [GitExecutor.COMMANDS_USED] (via run) or [GitExecutor.RAW_COMMANDS_USED] (via runRaw).
 */
class RecordingGitExecutor(private val delegate: GitExecutor) : GitExecutor by delegate {
    val commands = mutableListOf<String>()

    override fun run(args: List<String>, config: List<String>): GitResult {
        val name = args.first()
        check(name in GitExecutor.COMMANDS_USED) { "git $name is not declared in GitExecutor.COMMANDS_USED" }
        commands += name
        return delegate.run(args, config)
    }

    override fun runRaw(args: List<String>, input: ByteArray?, env: Map<String, String>): GitResult {
        val name = args.first()
        check(name in GitExecutor.RAW_COMMANDS_USED) { "git $name is not declared in GitExecutor.RAW_COMMANDS_USED" }
        commands += name
        return delegate.runRaw(args, input, env)
    }
}
