package dev.herdr.intellij

import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

internal data class ProjectRoots(
    val id: String,
    val basePath: Path?,
    val contentRoots: List<Path>,
)

internal class WorkspaceNavigator : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun openProject(navigationRoot: String): Project? {
        val root = canonicalPath(Path.of(navigationRoot))
        val projects = openProjects()
        val owner = owner(root, projects)
        if (owner != null) {
            WindowManager.getInstance().getFrame(owner)?.let { frame ->
                frame.toFront()
                frame.requestFocus()
            }
            return owner
        }
        scope.launch {
            ProjectUtil.openOrImportAsync(
                root,
                OpenProjectTask.build().withForceOpenInNewFrame(true),
            )
        }
        return null
    }

    fun openFile(
        navigationRoot: String,
        relativePath: String,
        line: Int = 1,
        column: Int = 1,
    ): Boolean {
        val root = canonicalPath(Path.of(navigationRoot))
        val target = canonicalPath(root.resolve(relativePath).normalize())
        if (!target.startsWith(root)) {
            return false
        }
        val project = owner(target, openProjects()) ?: owner(root, openProjects()) ?: return false
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) ?: return false
        OpenFileDescriptor(
            project,
            file,
            (line - 1).coerceAtLeast(0),
            (column - 1).coerceAtLeast(0),
        ).navigate(true)
        return true
    }

    fun showCurrentChanges(navigationRoot: String): Boolean {
        val root = canonicalPath(Path.of(navigationRoot))
        val project = owner(root, openProjects()) ?: return false
        if (DumbService.isDumb(project)) {
            return false
        }
        val changes =
            ChangeListManager.getInstance(project).allChanges.filter { change ->
                listOfNotNull(change.afterRevision?.file, change.beforeRevision?.file).any { path ->
                    canonicalPath(path.ioFile.toPath()).startsWith(root)
                }
            }
        if (changes.isEmpty()) {
            return false
        }
        val context =
            ShowDiffContext().apply {
                putChainContext(
                    DiffUserDataKeysEx.VCS_DIFF_EDITOR_TAB_TITLE,
                    HerdrBundle.message("action.currentChanges"),
                )
            }
        ShowDiffAction.showDiffForChange(project, changes, 0, context)
        return true
    }

    fun hasIndexedProject(navigationRoot: String): Boolean {
        val root = canonicalPath(Path.of(navigationRoot))
        val project = owner(root, openProjects()) ?: return false
        return !DumbService.isDumb(project)
    }

    override fun close() {
        scope.cancel()
    }

    private fun openProjects(): Pair<List<Project>, List<ProjectRoots>> {
        val projects = ProjectManager.getInstance().openProjects.filterNot(Project::isDisposed)
        return projects to
            projects.map { project ->
                ProjectRoots(
                    project.locationHash,
                    project.basePath?.let { canonicalPath(Path.of(it)) },
                    ProjectRootManager.getInstance(project).contentRoots.map { canonicalPath(Path.of(it.path)) },
                )
            }
    }

    private fun owner(
        target: Path,
        projects: Pair<List<Project>, List<ProjectRoots>>,
    ): Project? {
        val roots = owningProject(target, projects.second) ?: return null
        return projects.first.singleOrNull { it.locationHash == roots.id }
    }

    companion object {
        internal fun owningProject(
            target: Path,
            projects: List<ProjectRoots>,
        ): ProjectRoots? {
            val canonicalTarget = canonicalPath(target)
            projects.filter { it.basePath == canonicalTarget }.minByOrNull(ProjectRoots::id)?.let { return it }
            return projects
                .mapNotNull { project ->
                    project.contentRoots
                        .filter { canonicalTarget.startsWith(it) }
                        .maxByOrNull { it.nameCount }
                        ?.let { root -> project to root.nameCount }
                }.maxWithOrNull(compareBy<Pair<ProjectRoots, Int>> { it.second }.thenBy { it.first.id })
                ?.first
        }

        private fun canonicalPath(path: Path): Path =
            try {
                if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
            } catch (_: Exception) {
                path.toAbsolutePath().normalize()
            }
    }
}
