/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.reporter

import com.intellij.diagnostic.ReportMessages
import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.Consumer
import org.jetbrains.kotlin.idea.KotlinPluginUpdater
import org.jetbrains.kotlin.idea.KotlinPluginUtil
import org.jetbrains.kotlin.idea.PluginUpdateStatus
import org.jetbrains.kotlin.idea.util.isEap
import java.awt.Component
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.*
import javax.swing.Icon

/**
 * We need to wrap ITNReporter for force showing or errors from kotlin plugin even from released version of IDEA.
 */
class KotlinReportSubmitter : ITNReporterCompat() {
    companion object {
        private const val KOTLIN_FATAL_ERROR_NOTIFICATION_PROPERTY = "kotlin.fatal.error.notification"
        private const val IDEA_FATAL_ERROR_NOTIFICATION_PROPERTY = "idea.fatal.error.notification"
        private const val DISABLED_VALUE = "disabled"
        private const val ENABLED_VALUE = "enabled"

        private const val KOTLIN_PLUGIN_RELEASE_DATE = "kotlin.plugin.releaseDate"

        // Disabled by default until we can confirm it can be enabled
        @Volatile
        private var isFatalErrorReportingDisabledInRelease = true

        private val isIdeaAndKotlinRelease by lazy {
            // TODO: check Android Studio
            val isReleaseLikeIdea = DISABLED_VALUE == System.getProperty(IDEA_FATAL_ERROR_NOTIFICATION_PROPERTY, ENABLED_VALUE)
            val isKotlinRelease =
                !(KotlinPluginUtil.isSnapshotVersion() || KotlinPluginUtil.isDevVersion() || isEap(KotlinPluginUtil.getPluginVersion()))

            isReleaseLikeIdea && isKotlinRelease
        }

        // TODO: Should we check only Kotlin release date? What if old plugin is bundled to Intellij IDEA for the first time?
        private const val NUMBER_OF_REPORTING_DAYS_FROM_RELEASE = 7

        fun setupReportingFromRelease() {
            if (ApplicationManager.getApplication().isUnitTestMode) {
                isFatalErrorReportingDisabledInRelease = false
                return
            }

            if (!isIdeaAndKotlinRelease) {
                isFatalErrorReportingDisabledInRelease = false
                return
            }

            val currentPluginReleaseDate = readStoredPluginReleaseDate()
            if (currentPluginReleaseDate != null) {
                isFatalErrorReportingDisabledInRelease = isFatalErrorReportingDisabled(currentPluginReleaseDate)
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val releaseDate =
                    try {
                        KotlinPluginUpdater.fetchPluginReleaseDate(
                            KotlinPluginUtil.KOTLIN_PLUGIN_ID.idString,
                            KotlinPluginUtil.getPluginVersion()
                        )
                    } catch (e: Throwable) {
                        // TODO: Should be report problems with fetching version?
                        null
                    }

                if (releaseDate != null) {
                    writePluginReleaseValue(releaseDate)
                    isFatalErrorReportingDisabledInRelease = isFatalErrorReportingDisabled(releaseDate)
                } else {
                    // Will try to fetch the same release date on IDE restart
                }
            }
        }

        private val RELEASE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd")

        private fun isFatalErrorReportingDisabled(releaseDate: Date): Boolean {
            val releaseDateLocal = releaseDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

            return ChronoUnit.DAYS.between(releaseDateLocal, LocalDate.now()) > NUMBER_OF_REPORTING_DAYS_FROM_RELEASE
        }

        private fun readStoredPluginReleaseDate(): Date? {
            val pluginVersionToReleaseDate = PropertiesComponent.getInstance().getValue(KOTLIN_PLUGIN_RELEASE_DATE) ?: return null

            val parsedDate = fun(): Date? {
                val parts = pluginVersionToReleaseDate.split(":")
                if (parts.size != 2) {
                    return null
                }

                val pluginVersion = parts[0]
                if (pluginVersion != KotlinPluginUtil.getPluginVersion()) {
                    // Stored for some other plugin version
                    return null
                }

                return try {
                    val dateString = parts[1]
                    RELEASE_DATE_FORMAT.parse(dateString)
                } catch (e: ParseException) {
                    null
                }
            }.invoke()

            if (parsedDate == null) {
                PropertiesComponent.getInstance().setValue(KOTLIN_PLUGIN_RELEASE_DATE, null)
            }

            return parsedDate
        }

        private fun writePluginReleaseValue(date: Date) {
            val currentKotlinVersion = KotlinPluginUtil.getPluginVersion()
            val dateStr = RELEASE_DATE_FORMAT.format(date)
            PropertiesComponent.getInstance().setValue(KOTLIN_PLUGIN_RELEASE_DATE, "$currentKotlinVersion:$dateStr")
        }
    }

    private var hasUpdate = false
    private var hasLatestVersion = false

    override fun showErrorInRelease(event: IdeaLoggingEvent): Boolean {
        if (ApplicationManager.getApplication().isInternal) {
            // Reporting is always enabled for internal mode in the platform
            return true
        }

        if (hasUpdate) {
            return false
        }

        val kotlinNotificationEnabled = DISABLED_VALUE != System.getProperty(KOTLIN_FATAL_ERROR_NOTIFICATION_PROPERTY, ENABLED_VALUE)
        if (!kotlinNotificationEnabled) {
            // Kotlin notifications are explicitly disabled
            return false
        }

        if (isIdeaAndKotlinRelease) {
            if (isFatalErrorReportingDisabledInRelease) {
                return false
            } else {
                // Reiterate the check for the case when there was no restart for long
                val currentPluginReleaseDate = readStoredPluginReleaseDate()
                if (currentPluginReleaseDate != null) {
                    if (isFatalErrorReportingDisabled(currentPluginReleaseDate)) {
                        isFatalErrorReportingDisabledInRelease = true
                        return false
                    }
                }
            }
        }

        return true
    }

    override fun submitCompat(
        events: Array<IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component?,
        consumer: Consumer<SubmittedReportInfo>
    ): Boolean {
        if (hasUpdate) {
            if (ApplicationManager.getApplication().isInternal) {
                return super.submitCompat(events, additionalInfo, parentComponent, consumer)
            }

            // TODO: What happens here? User clicks report but no report is send?
            return true
        }

        if (hasLatestVersion) {
            return super.submitCompat(events, additionalInfo, parentComponent, consumer)
        }

        val project: Project? = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent))
        if (KotlinPluginUtil.isPatched()) {
            ReportMessages.GROUP
                .createNotification(
                    ReportMessages.ERROR_REPORT,
                    "Can't report exception from patched plugin",
                    NotificationType.INFORMATION,
                    null
                )
                .setImportant(false)
                .notify(project)
            return true
        }

        KotlinPluginUpdater.getInstance().runUpdateCheck { status ->
            if (status is PluginUpdateStatus.Update) {
                hasUpdate = true

                if (ApplicationManager.getApplication().isInternal) {
                    super.submitCompat(events, additionalInfo, parentComponent, consumer)
                }

                val rc = showDialog(
                    parentComponent,
                    "You're running Kotlin plugin version ${KotlinPluginUtil.getPluginVersion()}, " +
                            "while the latest version is ${status.pluginDescriptor.version}",
                    "Update Kotlin Plugin",
                    arrayOf("Update", "Ignore"),
                    0, Messages.getInformationIcon()
                )

                if (rc == 0) {
                    KotlinPluginUpdater.getInstance().installPluginUpdate(status)
                }
            } else {
                hasLatestVersion = true
                super.submitCompat(events, additionalInfo, parentComponent, consumer)
            }
            false
        }

        return true
    }

    fun showDialog(parent: Component?, message: String, title: String, options: Array<String>, defaultOptionIndex: Int, icon: Icon?): Int {
        return if (parent != null) {
            Messages.showDialog(parent, message, title, options, defaultOptionIndex, icon)
        } else {
            Messages.showDialog(message, title, options, defaultOptionIndex, icon)
        }
    }
}
