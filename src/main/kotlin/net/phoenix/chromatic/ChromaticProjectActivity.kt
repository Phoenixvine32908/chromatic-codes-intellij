package net.phoenix.chromatic

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class ChromaticProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = ChromaticSettingsState.getInstance(project)
        settings.reloadRegistry(project)
        installFileWatcher(project, settings)
    }

    private fun installFileWatcher(project: Project, settings: ChromaticSettingsState) {
        VirtualFileManager.getInstance().addAsyncFileListener(
            object : AsyncFileListener {
                override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
                    val configFile = settings.resolvedConfigFile(project) ?: return null
                    val configPath = configFile.canonicalPath ?: return null
                    val relevant = events.any { it.path == configPath || it.file?.canonicalPath == configPath }
                    if (!relevant) return null

                    return object : AsyncFileListener.ChangeApplier {
                        override fun afterVfsChange() {
                            ApplicationManager.getApplication().invokeLater {
                                settings.reloadRegistry(project)
                                DaemonCodeAnalyzer.getInstance(project).restart()
                            }
                        }
                    }
                }
            },
            // Disposable tied to the project so the listener is removed when the project closes
            project
        )
    }
}
