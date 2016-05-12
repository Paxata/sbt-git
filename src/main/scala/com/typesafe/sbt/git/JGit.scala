package com.typesafe.sbt.git

import java.io.{File, IOException}
import java.text.MessageFormat
import java.util.{Collections, Comparator}

import org.eclipse.jgit.api.errors.{JGitInternalException, RefNotFoundException}
import org.eclipse.jgit.api.{GitCommand, Git => PGit}
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.lib.Constants._
import org.eclipse.jgit.lib.{ObjectId, Ref, Repository}
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.revwalk.{RevCommit, RevFlag, RevFlagSet, RevWalk}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import scala.collection.JavaConversions._
import scala.util.Try
import scala.util.control.Breaks


// TODO - This class needs a bit more work, but at least it lets us use porcelain and wrap some higher-level
// stuff on top of JGit, as needed for our plugin.
final class JGit(val repo: Repository) extends GitReadonlyInterface {
  
  // forcing initialization of shallow commits to avoid concurrent modification issue. See issue #85
  //repo.getObjectDatabase.newReader.getShallowCommits()
  // Instead we've thrown a lock around sbt's usage of this class.

  val porcelain = new PGit(repo)

  def create(): Unit = repo.create()

  def branch: String = repo.getBranch

  private def branchesRef: Seq[Ref] = {
    import collection.JavaConverters._
    porcelain.branchList.call.asScala
  }

  def tags: Seq[Ref] = {
    import collection.JavaConverters._
    porcelain.tagList.call().asScala
  }

  def checkoutBranch(branch: String): Unit = {
    // First, if remote branch exists, we auto-track it.
    val exists = branchesRef exists (_.getName == ("refs/heads/" + branch))
    if(exists)  porcelain.checkout.setName(branch).call()
    else {
      // TODO - find upstream...
      import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode
      val upstream = "origin/" + branch
      porcelain.checkout.setCreateBranch(true).setName(branch)
                .setUpstreamMode(SetupUpstreamMode.SET_UPSTREAM)
                .setStartPoint(upstream).call()
    }
  }

  def headCommit: Option[ObjectId] =
    Option(repo.resolve("HEAD"))

  def headCommitSha: Option[String] =
    headCommit map (_.name)

  def currentTags: Seq[String] = {
    for {
      hash <- headCommit.map(_.name).toSeq
      unpeeledTag <- tags
      taghash = tagHash(unpeeledTag)
      if taghash == hash
      ref = unpeeledTag.getName
      if ref startsWith "refs/tags/"
    } yield ref drop 10
  }


  def tagHash(tag: Ref) = {
    // Annotated (signed) and plain tags work differently,
    // plain ones have the null PeeledObjectId
    val peeled = repo.peel(tag)
    val id =
      if (peeled.getPeeledObjectId ne null)
        peeled.getPeeledObjectId
      else
        peeled.getObjectId
    id.getName
  }

  override def describedVersion: Option[String] = {
    Try(Option(porcelain.describe().call())).getOrElse(None)
  }

  /** Version of the software as returned by `git describe --tags --first-parent`. */
  override def firstParentDescribedVersion: Option[String] = {
    Try(Option(new FirstParentDescribeCommand(porcelain.getRepository).call())).getOrElse(None)
  }

  override def hasUncommittedChanges: Boolean = porcelain.status.call.hasUncommittedChanges
  
  override def branches: Seq[String] = branchesRef.filter(_.getName.startsWith("refs/heads")).map(_.getName.drop(11))

  override def remoteBranches: Seq[String] = {
    import org.eclipse.jgit.api.ListBranchCommand.ListMode

    import collection.JavaConverters._
    porcelain.branchList.setListMode(ListMode.REMOTE).call.asScala.filter(_.getName.startsWith("refs/remotes")).map(_.getName.drop(13))
  }

}

object JGit {

  /** Creates a new git instance from a base directory. */
  def apply(base: File) =
    try new JGit({
      new FileRepositoryBuilder().findGitDir(base).build
    }) catch {
      // This is thrown if we never find the git base directory.  In that instance, we'll assume root is the base dir.
      case e: IllegalArgumentException =>
        val defaultGitDir = new File(base, ".git")
        new JGit({ new FileRepositoryBuilder().setGitDir(defaultGitDir).build()})
    }

  /** Clones from a given URI into a local directory of your choosing. */
  def clone(from: String, to: File, remoteName: String = "origin", cloneAllBranches: Boolean = true, bare: Boolean = false): JGit = {
    val git = PGit.cloneRepository.setURI(from).setRemote(remoteName).setBare(bare).setCloneAllBranches(cloneAllBranches).setDirectory(to).call()
    new JGit(git.getRepository)
  }
}

class FirstParentDescribeCommand(repo_ : Repository) extends GitCommand[String](repo_) {
  val maxCandidates = 10
  val w = {
    val w = new RevWalk(repo)
    w.setRetainBody(false)
    w.setRevFilter(new FirstParentRevFilter())
    w
  }
  var target: RevCommit = null

  def setTarget(rev: String): this.type = {
    val id: ObjectId = repo.resolve(rev)
    if (id == null) throw new RefNotFoundException(MessageFormat.format(JGitText.get.refNotResolved, rev))
    setTarget(id)
  }

  def setTarget(target: ObjectId): this.type = {
    this.target = w.parseCommit(target)
    this
  }

  override def call(): String = {
    try {
      checkCallable()
      if (target == null) setTarget(HEAD)
      val tags = new java.util.HashMap[ObjectId, Ref]
      for (r <- repo.getRefDatabase.getRefs(R_TAGS).values) {
        var key = repo.peel(r).getPeeledObjectId
        if (key == null) key = r.getObjectId
        tags.put(key, r)
      }
      val allFlags = new RevFlagSet
      class Candidate(commit: RevCommit, tag: Ref) {
        var depth = 0
        val flag = w.newFlag(tag.getName)

        allFlags.add(flag)
        w.carry(flag)
        commit.add(flag)
        commit.carry(flag)

        def reaches(c: RevCommit): Boolean = c.has(flag)

        def describe(tip: ObjectId): String = {
          s"${tag.getName.substring(R_TAGS.length)}-$depth-g${w.getObjectReader.abbreviate(tip).name}"
        }
      }
      val candidates = new java.util.ArrayList[Candidate]
      val lucky = tags.get(target)
      if (lucky != null) lucky.getName.substring(R_TAGS.length)
      else {
        w.markStart(target)
        var seen = 0
        var c: RevCommit = null
        val loop = new Breaks
        loop.breakable {
          c = w.next()
          while (c != null) {
            if (!c.hasAny(allFlags)) {
              val t = tags.get(c)
              if (t != null) {
                val cd = new Candidate(c, t)
                candidates.add(cd)
                cd.depth = seen
              }
            }
            for (cd <- candidates) {
              if (!cd.reaches(c)) cd.depth += 1
            }
            if (candidates.size >= maxCandidates) loop.break()
            seen += 1
            c = w.next()
          }
        }
        c = w.next
        while (c != null) {
          if (c.hasAll(allFlags)) {
            for (p <- c.getParents) p.add(RevFlag.SEEN)
          } else {
            for (cd <- candidates) {
              if (!cd.reaches(c)) {
                cd.depth += 1
              }
            }
          }
          c = w.next
        }
        if (candidates.isEmpty) null
        else {
          val best = Collections.min(candidates, new Comparator[Candidate]() {
            def compare(o1: Candidate, o2: Candidate): Int = o1.depth - o2.depth
          })
          best.describe(target)
        }
      }
    } catch {
      case e: IOException => throw new JGitInternalException(e.getMessage, e)
    } finally {
      setCallable(false)
      w.release()
    }
  }
}

class FirstParentRevFilter extends RevFilter {
  override def include(walker: RevWalk, cmit: RevCommit): Boolean = {
    if (cmit.getParentCount > 1) cmit.getParents.tail.foreach(_.add(RevFlag.UNINTERESTING))
    true
  }

  override def clone(): FirstParentRevFilter = this
}
