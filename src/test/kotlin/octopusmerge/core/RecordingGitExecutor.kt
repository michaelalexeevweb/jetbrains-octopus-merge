package octopusmerge.core

/** Fails a test as soon as production code runs a git command missing from [GitExecutor.COMMANDS_USED]. */
class RecordingGitExecutor(private val delegate: GitExecutor) : GitExecutor by delegate {
    val commands = mutableListOf<String>()

    override fun run(args: List<String>, config: List<String>): GitResult {
        val name = args.first()
        check(name in GitExecutor.COMMANDS_USED) { "git $name is not declared in GitExecutor.COMMANDS_USED" }
        commands += name
        return delegate.run(args, config)
    }
}
