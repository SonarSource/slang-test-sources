package io.ktor.http

fun parseQueryString(query: CharSequence, startIndex: Int = 0, limit: Int = 1000): Parameters {
    return if (startIndex > query.lastIndex) {
        Parameters.Empty
    } else {
        Parameters.build { parse(query, startIndex, limit) }
    }
}

private fun ParametersBuilder.parse(query: CharSequence, startIndex: Int, limit: Int) {
    var count = 0
    var nameIndex = startIndex
    var equalIndex = -1
    for (index in startIndex..query.lastIndex) {
        if (count == limit)
            return
        val ch = query[index]
        when (ch) {
            '&' -> {
                appendParam(query, nameIndex, equalIndex, index)
                nameIndex = index + 1
                equalIndex = -1
                count++
            }
            '=' -> {
                if (equalIndex == -1)
                    equalIndex = index
            }
        }
    }
    if (count == limit)
        return
    appendParam(query, nameIndex, equalIndex, query.length)
}

private fun ParametersBuilder.appendParam(query: CharSequence, nameIndex: Int, equalIndex: Int, endIndex: Int) {
    if (equalIndex == -1) {
        val spaceNameIndex = trimStart(nameIndex, endIndex, query)
        val spaceEndIndex = trimEnd(spaceNameIndex, endIndex, query)

        if (spaceEndIndex > spaceNameIndex) {
            val name = decodeURLQueryComponent(query, spaceNameIndex, spaceEndIndex)
            append(name, "")
        }
    } else {
        val spaceNameIndex = trimStart(nameIndex, equalIndex, query)
        val spaceEqualIndex = trimEnd(spaceNameIndex, equalIndex, query)
        if (spaceEqualIndex > spaceNameIndex) {
            val name = decodeURLQueryComponent(query, spaceNameIndex, spaceEqualIndex)

            val spaceValueIndex = trimStart(equalIndex + 1, endIndex, query)
            val spaceEndIndex = trimEnd(spaceValueIndex, endIndex, query)
            val value = decodeURLQueryComponent(query, spaceValueIndex, spaceEndIndex)
            append(name, value)
        }
    }
}

private fun trimEnd(start: Int, end: Int, text: CharSequence): Int {
    var spaceIndex = end
    while (spaceIndex > start && text[spaceIndex - 1].isWhitespace()) spaceIndex--
    return spaceIndex
}

private fun trimStart(start: Int, end: Int, query: CharSequence): Int {
    var spaceIndex = start
    while (spaceIndex < end && query[spaceIndex].isWhitespace()) spaceIndex++
    return spaceIndex
}
