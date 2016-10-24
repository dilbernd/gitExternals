package net.berndhaug.gitExternals

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.internal.runners.statements.Fail
import java.io.File
import java.nio.file.Path

class GitExternalsKtTest {
    companion object {
        val gitRepoPath = listOf(File(".").canonicalPath, "testco", "foo.git").joinToString(File.separator)
    }

    @Before
    fun setUp() {
        System.setProperty("user.dir", listOf(gitRepoPath, "a_thing", "lol").joinToString(File.separator))
    }

    @Test
    fun testGetGitWdRoot() {
        val gitWdRoot = getGitWdRoot()
        when (gitWdRoot) {
            is Result.Success -> {
                val result = gitWdRoot.result
                assertEquals("foo.git", result.name)
                assertTrue(result.isDirectory)
            }
            is Result.Failure -> fail("failure getting git root dir: ${gitWdRoot.message}")
        }
    }

    @Test
    fun testReadGitSvnInfo() {
        val gitWdRoot = getGitWdRoot() as Result.Success
        val gitSvnInfo = gitSvnInfo(gitWdRoot.result)
        when (gitSvnInfo) {
            is Result.Success ->
                assertEquals(2,
                        gitSvnInfo.result.trim().split('\n')
                                .filter { it.contains(Regex("^URL: svn:")) || it.matches(Regex("^Last Changed Rev: \\d+$")) }
                                .count())
            is Result.Failure -> fail("failure reading git svn info: ${gitSvnInfo.message}")
        }
    }

    @Test
    fun testParseGitSvnOutput() {
        val exampleOutput = """Path: .
URL: svn://localhost/foo/trunk
Repository Root: svn://localhost/foo
Repository UUID: dfd36ecc-345f-42c4-9c1e-50ad7ca3c78d
Revision: 9
Node Kind: directory
Schedule: normal
Last Changed Author: (no author)
Last Changed Rev: 5
Last Changed Date: 2016-05-07 12:56:59 +0200 (Sat, 07 May 2016)

"""
        val parsedInfo = GitSvnInfoParser.parseSvnRepoInfo(exampleOutput)
        when (parsedInfo) {
            is Result.Success -> {
                assertEquals(9, parsedInfo.result.revision)
                assertEquals("svn://localhost/foo/trunk", parsedInfo.result.repositoryUrl)
            }
            is Result.Failure -> fail("Could not parse correct information from git svn info output!")
        }
    }

    @Test
    fun getExternalsRecursive() {
        val foundExternals = CommitWalker(SvnCoordinate("svn://localhost:3690/foo/trunk", 9)).findExternals()
        println(foundExternals)
    }
}
