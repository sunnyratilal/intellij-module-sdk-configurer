package com.sunnyratilal.intellij.modulesdkconfigurer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.text.MessageFormat


class ConfigureModuleSdkAction : AnAction() {
    val logger = Logger.getInstance(ConfigureModuleSdkAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            return
        }
        var moduleSdksConfigured = 0
        val modules = ModuleManager.getInstance(project).modules
        modules.forEach { module ->
            val moduleRootManager = ModuleRootManager.getInstance(module)
            val roots = moduleRootManager.contentRoots
            roots.forEach { root ->
                var javaVersion: String? = null

                // IntelliJ 2025.1 changes the Maven model structure whereby if a Maven module has differences tied
                // to the compile or test-compile execution then the main and test modules are configured to be separate.
                // https://youtrack.jetbrains.com/issue/IDEA-371535/Confusing-separate-.main-and-.test-modules-in-Maven-projects
                if (module.name.endsWith(".main") || module.name.endsWith(".test")) {
                    // Check for the build feature at the parent level

                    val parentModuleName = module.name.removeSuffix(".main").removeSuffix(".test")
                    ModuleManager.getInstance(project).findModuleByName(parentModuleName)?.let { module ->
                        ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
                            javaVersion = findModuleJavaVersion(root)
                        }
                    }
                } else {
                    javaVersion = findModuleJavaVersion(root)
                }

                if (javaVersion != null) {
                    val sdk = findJavaSdk(javaVersion!!)
                    if (sdk != null) {
                        if (sdk.name != moduleRootManager.sdk?.name) {
                            ModuleRootModificationUtil.setModuleSdk(module, sdk)
                            val message = MessageFormat.format(
                                "Updated SDK for module {0} to {1} ({2})",
                                module.name,
                                sdk.name,
                                sdk.versionString
                            )
                            logger.info(message)
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("Module SDK Configurer")
                                .createNotification(message, NotificationType.INFORMATION)
                                .notify(project);
                            moduleSdksConfigured++
                        }
                    }
                }
            }
        }

        if (moduleSdksConfigured > 0) {
            Messages.showInfoMessage(
                project,
                "${moduleSdksConfigured} module(s) reconfigured.",
                "Module SDK Configuration Complete"
            )
        } else {
            Messages.showInfoMessage(
                project,
                "All modules SDKs are already configured correctly.",
                "Nothing to Re-Configure"
            )
        }
    }

    private fun findModuleJavaVersion(root: VirtualFile): String? {
        val buildFeaturesDir = File(root.path, "build-features")
        val javaApplicationFileMatch = buildFeaturesDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.contains(Regex("java-\\d+-application")) }
        if (javaApplicationFileMatch != null) {
            val javaVersion = Regex("java-(\\d+)-application")
                .find(javaApplicationFileMatch.name)
                ?.groupValues?.get(1)
                ?: error("Could not extract Java version from ${javaApplicationFileMatch.name} in ${root.name} (${root.path})")

            logger.info("Found: ${javaApplicationFileMatch.name} in ${root.name} (${root.path}, Java version: $javaVersion")
            return javaVersion
        }
        return null
    }

    private fun findJavaSdk(javaVersion: String): Sdk? {
        return ProjectJdkTable.getInstance().allJdks.firstOrNull { sdk ->
            sdk.sdkType is JavaSdkType && sdk.versionString?.contains(javaVersion) == true
        }
    }
}