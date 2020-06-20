package me.lensvol.blackconnect

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import me.lensvol.blackconnect.settings.BlackConnectProjectSettings
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL

class BlackdReformatter(project: Project, configuration: BlackConnectProjectSettings) {
    private val currentProject: Project = project
    private val currentConfig: BlackConnectProjectSettings = configuration
    private val notificationGroup: NotificationGroup =
        NotificationGroup("BlackConnect", NotificationDisplayType.BALLOON, false)

    private val logger = Logger.getInstance(PluginStartupActivity::class.java.name)

    fun isFileSupported(file: VirtualFile): Boolean {
        return file.name.endsWith(".py") || file.name.endsWith(".pyi") ||
                (currentConfig.enableJupyterSupport &&
                        (file.fileType as LanguageFileType).language.id == "Jupyter")
    }

    fun process(document: Document) {
        val vFile: VirtualFile? = FileDocumentManager.getInstance().getFile(document)
        val fileName = vFile?.name ?: "unknown"

        val progressIndicator = EmptyProgressIndicator()
        progressIndicator.isIndeterminate = true

        val projectService = currentProject.service<BlackConnectProgressTracker>()
        projectService.registerOperationOnPath(vFile!!.path, progressIndicator)

        ProgressManager.getInstance().runProcessWithProgressAsynchronously(
            object : Task.Backgroundable(currentProject, "Calling blackd") {
                override fun run(indicator: ProgressIndicator) {
                    logger.debug("Reformatting code in '$fileName'")
                    reformatCodeInDocument(currentConfig, document, fileName, project)
                }
            },
            progressIndicator
        )
    }

    private fun callBlackd(
        path: String,
        sourceCode: String,
        pyi: Boolean = false,
        lineLength: Int = 88,
        fastMode: Boolean = false,
        skipStringNormalization: Boolean = false,
        targetPythonVersions: String = ""
    ): Pair<Int, String> {
        val url = URL(path)

        with(url.openConnection() as HttpURLConnection) {
            requestMethod = "POST"
            doOutput = true

            setRequestProperty("X-Protocol-Version", "1")
            setRequestProperty("X-Fast-Or-Safe", if (fastMode) "fast" else "safe")
            setRequestProperty("X-Line-Length", lineLength.toString())

            if (pyi) {
                setRequestProperty("X-Python-Variant", "pyi")
            } else {
                if (!targetPythonVersions.isEmpty()) {
                    setRequestProperty("X-Python-Variant", targetPythonVersions)
                }
            }

            if (skipStringNormalization) {
                setRequestProperty("X-Skip-String-Normalization", "yes")
            }

            try {
                connect()
            } catch (e: ConnectException) {
                return Pair(-1, e.message ?: "Connection failed.")
            }

            try {
                outputStream.use { os ->
                    val input: ByteArray = sourceCode.toByteArray()
                    os.write(input, 0, input.size)
                }

                inputStream.bufferedReader().use { return Pair(responseCode, it.readText()) }
            } catch (e: IOException) {
                return Pair(responseCode, errorStream.readBytes().toString())
            }
        }
    }

    private fun showError(text: String) {
        notificationGroup
            .createNotification(text, NotificationType.ERROR)
            .setTitle("BlackConnect")
            .notify(currentProject)
    }

    private fun reformatCodeInDocument(
        configuration: BlackConnectProjectSettings,
        document: @Nullable Document,
        fileName: @NotNull String,
        project: @Nullable Project
    ) {
        val progressIndicator = ProgressManager.getGlobalProgressIndicator()
        logger.debug("Reformatting cancelled before we could begin")
        if (progressIndicator?.isCanceled == true)
            return

        val (responseCode, responseText) = callBlackd(
            "http://" + configuration.hostname + ":" + configuration.port,
            document.text,
            pyi = fileName.endsWith(".pyi"),
            lineLength = configuration.lineLength,
            fastMode = configuration.fastMode,
            skipStringNormalization = configuration.skipStringNormalization,
            targetPythonVersions = if (configuration.targetSpecificVersions) configuration.pythonTargets else ""
        )

        logger.debug("Reformatting cancelled after call to blackd")
        if (progressIndicator?.isCanceled == true)
            return

        when (responseCode) {
            200 -> {
                logger.debug("200 OK: Code should be reformatted")
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().runWriteAction(Computable {
                        if (progressIndicator?.isCanceled == false) {
                            CommandProcessor.getInstance().executeCommand(
                                project,
                                {
                                    logger.debug("Code is going to be updated in $document")
                                    document.setText(responseText)
                                },
                                "Reformat code using blackd",
                                null,
                                UndoConfirmationPolicy.DEFAULT,
                                document
                            )
                        } else {
                            logger.debug("Reformatting cancelled before updating the document")
                        }
                    })
                }
            }
            204 -> {
                logger.debug("No changes to formatting, move along.")
                // Nothing was modified, nothing to do here, move along.
            }
            400 -> {
                logger.debug("400 Bad Request: Source code contained syntax errors.")
                if (configuration.showSyntaxErrorMsgs) {
                    showError("Source code contained syntax errors.")
                }
            }
            500 -> {
                logger.debug("500 Internal Error: Something went wrong.")
                showError("Internal error, please see blackd output.")
            }
            else -> {
                logger.debug("Something unexpected happened:\n$responseText")
                showError("Something unexpected happened:\n$responseText")
            }
        }
    }

}