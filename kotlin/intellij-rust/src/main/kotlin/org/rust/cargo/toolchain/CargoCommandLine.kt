/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManagerEx
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.runconfig.createCargoCommandRunConfiguration
import java.nio.file.Path

data class CargoCommandLine(
    val command: String, // Can't be `enum` because of custom subcommands
    val workingDirectory: Path, // Note that working directory selects Cargo project as well
    val additionalArguments: List<String> = emptyList(),
    val backtraceMode: BacktraceMode = BacktraceMode.DEFAULT,
    val channel: RustChannel = RustChannel.DEFAULT,
    val environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT,
    val nocapture: Boolean = true
) {


    fun withDoubleDashFlag(arg: String): CargoCommandLine {
        val (pre, post) = splitOnDoubleDash()
        if (arg in post) return this
        return copy(additionalArguments = pre + "--" + arg + post)
    }

    /**
     * Splits [additionalArguments] into parts before and after `--`.
     * For `cargo run --release -- foo bar`, returns (["--release"], ["foo", "bar"])
     */
    fun splitOnDoubleDash(): Pair<List<String>, List<String>> {
        val idx = additionalArguments.indexOf("--")
        if (idx == -1) return additionalArguments to emptyList()
        return additionalArguments.take(idx) to additionalArguments.drop(idx + 1)
    }

    companion object {
        fun forTarget(
            target: CargoWorkspace.Target,
            command: String,
            additionalArguments: List<String> = emptyList()
        ): CargoCommandLine {
            val targetArgs = when (target.kind) {
                CargoWorkspace.TargetKind.BIN -> listOf("--bin", target.name)
                CargoWorkspace.TargetKind.TEST -> listOf("--test", target.name)
                CargoWorkspace.TargetKind.EXAMPLE -> listOf("--example", target.name)
                CargoWorkspace.TargetKind.BENCH -> listOf("--bench", target.name)
                CargoWorkspace.TargetKind.LIB -> listOf("--lib")
                CargoWorkspace.TargetKind.UNKNOWN -> emptyList()
            }

            return CargoCommandLine(
                command,
                workingDirectory = target.pkg.workspace.manifestPath.parent,
                additionalArguments = listOf("--package", target.pkg.name) + targetArgs + additionalArguments
            )
        }

        fun forProject(
            cargoProject: CargoProject,
            command: String,
            additionalArguments: List<String> = emptyList(),
            channel: RustChannel = RustChannel.DEFAULT
        ): CargoCommandLine {
            return CargoCommandLine(
                command,
                cargoProject.workingDirectory,
                additionalArguments,
                channel = channel
            )
        }
    }
}

fun CargoWorkspace.Target.launchCommand(): String? {
    return when (kind) {
        CargoWorkspace.TargetKind.BIN -> "run"
        CargoWorkspace.TargetKind.LIB -> "build"
        CargoWorkspace.TargetKind.TEST -> "test"
        CargoWorkspace.TargetKind.BENCH -> "bench"
        CargoWorkspace.TargetKind.EXAMPLE -> if (crateTypes.singleOrNull() == CargoWorkspace.CrateType.BIN) "run" else "build"
        else -> null
    }
}

fun CargoCommandLine.run(project: Project, cargoProject: CargoProject) {
    val runConfiguration =
        if (project.cargoProjects.allProjects.size > 1)
            createRunConfiguration(project, this, name = command + " [" + cargoProject.presentableName + "]")
        else
            createRunConfiguration(project, this)
    val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
    ProgramRunnerUtil.executeConfiguration(runConfiguration, executor)
}

private fun createRunConfiguration(project: Project, cargoCommandLine: CargoCommandLine, name: String? = null): RunnerAndConfigurationSettings {
    val runManager = RunManagerEx.getInstanceEx(project)

    return runManager.createCargoCommandRunConfiguration(cargoCommandLine, name).apply {
        runManager.setTemporaryConfiguration(this)
    }
}
