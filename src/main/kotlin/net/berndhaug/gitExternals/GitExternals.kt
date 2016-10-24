package net.berndhaug.gitExternals

import net.berndhaug.gitExternals.ExitCodes.*
import org.eclipse.jgit.lib.RepositoryBuilder
import org.tmatesoft.svn.core.*
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.system.exitProcess

enum class ExitCodes(val status: Int) {
    SUCCESS(0), NOT_IMPLEMENTED_YET(1), ERROR_BEFORE_WRITE(2), FAIL_CLEANUP_OK(3), FAIL_CLEANUP_FAIL(4),
}

/**
 * Represents a generic result - either success, with a payload, or failure, with an error message.
 */
sealed class Result<out SUCCESS, out FAILURE> {
    data class Failure<out SUCCESS, out FAILURE>(val errorData: FAILURE) : Result<SUCCESS, FAILURE>()
    data class Success<out SUCCESS, out FAILURE>(val result: SUCCESS) : Result<SUCCESS, FAILURE>()
}

class Empty {
    init {
    }
}

/**
 * Attempts to find the root directory of a git repository, failing if the repository is bare or jgit reports an error.
 */
fun getGitWdRoot(): Result<File, String> {
    return try {
        val repo = RepositoryBuilder().findGitDir().build()
        if (repo.isBare) {
            return Result.Failure("Cannot work with a bare repository!")
        } else {
            Result.Success(repo.workTree)
        }
    } catch (e: Exception) {
        Result.Failure(e.message ?: "git repo root discovery failed without a message (exception class: ${e.javaClass})")
    }
}

/**
 * Given a directory in a git repository, gets the "git svn info" output for it.
 */
fun gitSvnInfo(gitDir: File): Result<String, String> {
    val gitSvnInfoProcess = ProcessBuilder().directory(gitDir).command("git", "svn", "info").start()
    return try {
        val output = ByteArrayOutputStream().use {
            baos ->
            gitSvnInfoProcess.inputStream.use {
                stdOut ->
                stdOut.copyTo(baos)
            }
            String(baos.toByteArray(), Charset.forName("UTF-8"))
        }
        val returnCode = gitSvnInfoProcess.waitFor()
        if (returnCode == 0) Result.Success(output) else Result.Failure("git svn info return status was ${returnCode}")
    } catch (e: Exception) {
        Result.Failure(e.message ?: "Running git svn info failed without a message (exception class: ${e.javaClass})")
    }
}

/**
 * A coordinate in SVN - URL and revision. A revision of <status>0</status> will be treated as HEAD.
 */
data class SvnCoordinate(val repositoryUrl: String, val revision: Long) {
    companion object {
        val HEAD_REVISION = 0L
    }

    /**
     * Initialize a coordinate with only a URL, referencing the HEAD revision.
     */
    constructor(repositoryUrl: String) : this(repositoryUrl, HEAD_REVISION)
}

/**
 * Extracts the SVN coordinate that corresponds to the root of the git repository.
 */
object GitSvnInfoParser {
    val WHOLE_OUTPUT_REGEX = Regex("^URL: ([^\n]+)\n.*\nRevision: (\\d+)$",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

    /**
     * Gets the SVN coordinate corresponding to the clone root of a git svn repository from from <status>git svn info</status>
     * output.
     */
    fun parseSvnRepoInfo(gitSvnOutput: String): Result<SvnCoordinate, String> {
        val match = WHOLE_OUTPUT_REGEX.find(gitSvnOutput)?.groupValues

        return if (null != match) {
            try {
                Result.Success<SvnCoordinate, String>(SvnCoordinate(match.get(1), match.get(2).toLong()))
            } catch (e: NumberFormatException) {
                Result.Failure<SvnCoordinate, String>("Could not parse rev from git svn info output! Why did the RE match?!")
            }
        } else {
            Result.Failure("Could not read required git svn info from provided output!")
        }
    }
}

/**
 * An SVN external entry, including a target path into which the external will be checked out, and the SVN coordinate to
 * check out.
 */
data class ExternalEntry(val targetPath: Path, val source: SvnCoordinate)

/**
 * Parses <status>svn:externals</status> properties into {@see ExternalEntry} instances.
 */
object GitExternalsParser {
    fun parse(targetBasePath: String, externalsProperty: String): Result<List<ExternalEntry>, String> {
//        TODO!
        return Result.Success(emptyList())
    }
}

object RepoProvider {
    val repos: MutableMap<String, SVNRepository> = mutableMapOf()

    fun repoFor(url: String): SVNRepository {
        val repo = repos.getOrPut(url, {
            val repo = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(url))
            repo.authenticationManager = SVNWCUtil.createDefaultAuthenticationManager()
            return repo
        })
        return repo
    }
}

/**
 * Walks an SVN coordinate, discovering externals.
 */
object CommitWalker {

    /**
     * Finds the externals referenced in the SVN coordinate provided.
     */
    fun findExternals(svnCoordinate: SvnCoordinate): Result<List<ExternalEntry>, String> {
        return recurForExternals(svnCoordinate, LinkedList(listOf("/")))
    }

    private fun recurForExternals(coordinate: SvnCoordinate, workQ: Deque<String>): Result<List<ExternalEntry>, String> {
        val externals = mutableListOf<ExternalEntry>()
        while (workQ.isNotEmpty()) {
            val currentPath = workQ.pollFirst()
            val properties = SVNProperties()
            val entryHandler = ISVNDirEntryHandler {
                if (it.kind == SVNNodeKind.DIR) {
                    workQ.addLast("$currentPath${it.name}/")
                }
            }
            try {
                RepoProvider.repoFor(coordinate.repositoryUrl).getDir(currentPath,
                        coordinate.revision,
                        properties,
                        entryHandler)
                val parsedExternals = GitExternalsParser.parse(currentPath,
                        properties.getSVNPropertyValue("svn:externals")?.string ?: "")
                when (parsedExternals) {
                    is Result.Success -> externals.addAll(parsedExternals.result)
                    is Result.Failure -> return Result.Failure(parsedExternals.errorData)
                }
            } catch (e: SVNException) {
                return Result.Failure(e.message ?: "SVN failure getting git externals at [$currentPath]!")
            }
        }
        return Result.Success(externals)
    }
}

/**
 * Represents a checkout error: The target path that failed to check out, combined with an error message and a flag whether
 * the path should be cleaned up with the rest of the failed checkout operation. This is often desirable (e.g., delete
 * partial checkouts) but also sometimes dangerous, e.g. if could not check out because the path already exists.
 */
data class OneCheckoutError(val path: Path, val cleanup: Boolean, val message: String)

/**
 * Represents the failure of a whole checkout operation: The paths that were checked out, and an error message.
 * This is used for cleanup and should include the path for which checkout failed if that should be cleaned up, too.
 */
data class CheckoutOperationError(val paths: Collection<Path>, val message: String)

/**
 * Checks out externals, relative to a given root directory.
 */
object CheckerOuter {
    /**
     * Actually performs the check-outs.
     */
    fun checkoutExternals(gitRoot: File,
                          externals: List<ExternalEntry>): Result<Map<SvnCoordinate, List<Path>>, CheckoutOperationError> {
        // TODO: preserving or overwriting existing checkouts?
        val successes = mutableMapOf<SvnCoordinate, MutableList<Path>>()
        externals.groupBy(ExternalEntry::source).entries.forEach {
            val targetPaths = LinkedList(it.value.map(ExternalEntry::targetPath))
            // poll first can return null, but this instance should be safe (result of grouping by)
            val firstCheckoutPath = targetPaths.pollFirst()
            val checkoutResult = checkout(gitRoot, it.key, firstCheckoutPath)
            when (checkoutResult) {
                is Result.Success -> successes.put(it.key, mutableListOf(checkoutResult.result))
                is Result.Failure -> return Result.Failure(
                        CheckoutOperationError(successes.values.flatten() + checkoutResult.errorData.path,
                                checkoutResult.errorData.message))
            }
            while (targetPaths.isNotEmpty()) {
                // poll first can return null, but this instance should be safe (just checked if not empty)
                val linkResult = link(firstCheckoutPath, targetPaths.pollFirst())
                when (linkResult) {
                // safe because this is an additional entry
                    is Result.Success -> successes.get(it.key)!!.add(checkoutResult.result)
                    is Result.Failure -> return Result.Failure(
                            CheckoutOperationError(successes.values.flatten() + linkResult.errorData.path,
                                    linkResult.errorData.message))
                }
            }
        }
        return Result.Success(mapOf(*(successes.entries.map { it.key to it.value.toList() }.toTypedArray())))
    }

    private fun checkout(gitRoot: File, source: SvnCoordinate, checkoutPath: Path): Result<Path, OneCheckoutError> {
        // danger – not atomic, but could only really be done if the checkout operation itself is moved into this tool
        // TODO: move the checkout operation into this tool - will be some work because need to recursively check out
        if (checkoutPath.toFile().exists()) {
            return Result.Failure(OneCheckoutError(checkoutPath, false, "Cannot check out: [$checkoutPath] already exists!"))
        }
        return try {
            val checkoutProcess = ProcessBuilder().inheritIO().directory(gitRoot).command("svn",
                    "checkout",
                    if (source.revision == 0L) "" else "-r${source.revision}",
                    source.repositoryUrl,
                    checkoutPath.toString()).start()
            val returnCode = checkoutProcess.waitFor()
            if (returnCode == 0) {
                Result.Success(checkoutPath)
            } else {
                Result.Failure<Path, OneCheckoutError>(OneCheckoutError(checkoutPath, true, "Failed to checkout" +
                        "[${source.repositoryUrl}]@[${source.revision}] to [$checkoutPath]: process returned [$returnCode]"))
            }
        } catch (e: Exception) {
            Result.Failure(OneCheckoutError(checkoutPath, true, "Failed to checkout " +
                    "[${source.repositoryUrl}]@[${source.revision}] to [$checkoutPath] because of [${e.message}]"))
        }
    }

//    private fun checkoutItem(): Result<Path, OneCheckoutError> {
//
//    }
//
//    private fun checkoutDir(): Result<Path, OneCheckoutError> {
//
//    }
//
//    private fun checkoutFile(): Result<Path, OneCheckoutError> {
//
//    }

    private fun link(source: Path, dest: Path): Result<Path, OneCheckoutError> {
        return try {
            Files.createSymbolicLink(source, dest)
            Result.Success(dest)
        } catch (e: FileAlreadyExistsException) {
            return Result.Failure(OneCheckoutError(dest,
                    false,
                    "Failed to link [$source] to [$dest] because of [${e.message}]"))
        } catch(e: Throwable) {
            return Result.Failure(OneCheckoutError(dest,
                    true,
                    "Failed to link [$source] to [$dest] because of [${e.message}]"))
        }
    }
}

/**
 * Cleans up a collection of externals, returning those where recursive deletion reported failure.
 */
fun cleanUpExternals(paths: Collection<Path>): Result<Empty, Collection<Path>> {
    val failures = paths.filter({ !it.toFile().deleteRecursively() })
    return if (failures.isEmpty()) {
        Result.Success(Empty())
    } else {
        Result.Failure(failures)
    }
}

fun main(argv: Array<String>) {
    fun performCleanUp(cleanupPaths: Collection<Path>): Nothing {
        val cleanupResult = cleanUpExternals(cleanupPaths)
        when (cleanupResult) {
            is Result.Failure -> {
                val failedPaths = cleanupResult.errorData.joinToString("\n")
                errprln("Cleanup reported errors for paths $failedPaths\n")
                exitProcess(FAIL_CLEANUP_FAIL.status)
            }
            is Result.Success -> {
                errprln("Cleanup done.")
                exitProcess(FAIL_CLEANUP_OK.status)

            }
        }
    }

    fun  readRegisteredExternals(gitWdRoot: File): Collection<ExternalEntry> {
        // TODO: implement
        return listOf()
    }

    fun getGitWd(): File {
        val wdResult = getGitWdRoot()
        val gitWdRoot = when (wdResult) {
            is Result.Failure -> {
                errprln("Could not find working dir root:\n${wdResult.errorData}")
                exitProcess(ERROR_BEFORE_WRITE.status)
            }
            is Result.Success -> {
                wdResult.result
            }
        }
        return gitWdRoot
    }

    fun uiClean(): Nothing {
        val wdResult = getGitWd()
        val externals = readRegisteredExternals(wdResult)
        performCleanUp(externals.map({e -> e.targetPath}))
    }


    fun getSvnCoordinateFromGitSvnRepo(gitWdRoot: File): SvnCoordinate {
        val gitSvnInfo = gitSvnInfo(gitWdRoot)
        val svnCoordinate = when (gitSvnInfo) {
            is Result.Failure -> {
                errprln("Could not get git svn info:\n${gitSvnInfo.errorData}")
                exitProcess(ERROR_BEFORE_WRITE.status)
            }
            is Result.Success -> {
                val repoInfo = GitSvnInfoParser.parseSvnRepoInfo(gitSvnInfo.result)
                when (repoInfo) {
                    is Result.Failure -> {
                        errprln("Could not evaluate git svn info output:\n${repoInfo.errorData}")
                        exitProcess(ERROR_BEFORE_WRITE.status)
                    }
                    is Result.Success -> repoInfo.result
                }
            }
        }
        return svnCoordinate
    }

    fun findExternalsInCoordinate(
            svnCoordinate: SvnCoordinate): List<ExternalEntry> {
        val searchResult = CommitWalker.findExternals(svnCoordinate)
        val externals = when (searchResult) {
            is Result.Failure -> {
                errprln("Could not perform external search at source:\n${searchResult.errorData}")
                exitProcess(ERROR_BEFORE_WRITE.status)
            }
            is Result.Success -> {
                val externals = searchResult.result
                if (externals.isEmpty()) {
                    errprln("No externals found! Done here.")
                    exitProcess(SUCCESS.status);
                } else {
                    externals
                }
            }
        }
        return externals
    }


    fun uiCheckout(): Nothing {
        val gitWdRoot = getGitWd()
        val svnCoordinate = getSvnCoordinateFromGitSvnRepo(gitWdRoot)
        val externals = findExternalsInCoordinate(svnCoordinate)

        val checkoutResult = CheckerOuter.checkoutExternals(gitWdRoot, externals)
        when (checkoutResult) {
            is Result.Failure -> {
                val errorData = checkoutResult.errorData
                errprln("Checking out externals failed:\n${errorData.message}\nCleaning up!")
                performCleanUp(errorData.paths)
            }
            is Result.Success -> {
                errprln("Checkout done.")
                exitProcess(SUCCESS.status)
            }
        }
    }

    fun uiPrepare(): Nothing {
        errprln("not implemented, sorry!")
        exitProcess(NOT_IMPLEMENTED_YET.status)
    }

    fun uiUsage(): Nothing {
        System.err.print("""Usage: java -jar gitExternals.jar (clean | checkout | prepare)

clean: Deletes previously checked out externals. Required before merging
       commits that replace externals with paths directly in the repository.

checkout: Checks out externals in the svn repository corresponding to this
          git-svn repo. Will try to be atomic, i.e. delete already checked
          out externals if a checkout fails. Will fail if a path into which
          an external should be checked out exists. Will try to preserve
          externals that are identical in the new version.

prepclean: PLANNED - NOT YET IMPLEMENTED Deletes externals not present in the
           new corresponding revision in SVN.

""")
        exitProcess(0)
    }

    if (argv.isEmpty() || argv.size > 1) {
        uiUsage()
    }
    when (argv[0]) {
        "clean" -> uiClean()
        "checkout" -> uiCheckout()
        "prepare" -> uiPrepare()
        else -> uiUsage()
    }
}

// TODO: löschen von bisher ausgecheckten pfaden wenn einer daneben geht ("atomische" externals)
// TODO: "registrieren" von downloads

// TODO: "update" - neues set von nötigen Downloads klären, alte wo möglich belassen/moven, neue laden, obsolete löschen
//   (einfacher mit symlinks? -> alle löschen/neu erzeugen?)

fun errprln(m: String) {
    errprln(m)
}
