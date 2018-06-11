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

package org.jetbrains.kotlin.compiler.plugin

import java.util.regex.Pattern

class CliOption(
        val name: String,
        val valueDescription: String,
        val description: String,
        val required: Boolean = true,
        val allowMultipleOccurrences: Boolean = false
)

open class CliOptionProcessingException(message: String, cause: Throwable? = null): RuntimeException(message, cause)

class PluginCliOptionProcessingException(
        val pluginId: String,
        val options: Collection<CliOption>,
        message: String,
        cause: Throwable? = null
): CliOptionProcessingException(message, cause)

fun cliPluginUsageString(pluginId: String, options: Collection<CliOption>): String {
    val LEFT_INDENT = 2
    val MAX_OPTION_WIDTH = 26

    val renderedOptions = options.map {
        val name = "${it.name} ${it.valueDescription}"
        val margin = if (name.length > MAX_OPTION_WIDTH) {
            "\n" + " ".repeat(MAX_OPTION_WIDTH + LEFT_INDENT + 1)
        } else " ".repeat(1 + MAX_OPTION_WIDTH - name.length)

        val modifiers = listOfNotNull(
                if (it.required) "required" else null,
                if (it.allowMultipleOccurrences) "multiple" else null)
        val modifiersEnclosed = if (modifiers.isEmpty()) "" else " (${modifiers.joinToString()})"

        " ".repeat(LEFT_INDENT) + name + margin + it.description + modifiersEnclosed
    }
    return "Plugin \"$pluginId\" usage:\n" + renderedOptions.joinToString("\n", postfix = "\n")
}

data class CliOptionValue(
        val pluginId: String,
        val optionName: String,
        val value: String
) {
    override fun toString() = "$pluginId:$optionName=$value"
}

fun parsePluginOption(argumentValue: String): CliOptionValue? {
    val pattern = Pattern.compile("""^plugin:([^:]*):([^=]*)=(.*)$""")
    val matcher = pattern.matcher(argumentValue)
    if (matcher.matches()) {
        return CliOptionValue(matcher.group(1), matcher.group(2), matcher.group(3))
    }

    return null
}

fun getPluginOptionString(pluginId: String, key: String, value: String): String {
    return "plugin:$pluginId:$key=$value"
}