/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.knit

import java.io.*
import java.util.HashMap

data class ApiIndexKey(
    val docsRoot: String,
    val pkg: String
)

private const val HTML_SUFFIX = ".html"
private const val MD_SUFFIX = ".md"

private const val INDEX_HTML = "/index$HTML_SUFFIX"
private const val INDEX_MD = "/index$MD_SUFFIX"
private const val FUNCTIONS_SECTION_HEADER = "### Functions"

private val REF_HTML_LINE_REGEX = Regex("<a href=\"([a-z0-9_/.\\-]+)\">([a-zA-z0-9.]+)</a>")
private val REF_MD_LINE_REGEX = Regex("\\| \\[([a-zA-z0-9.]+)]\\(([a-z0-9_/.\\-]+)\\) \\|.*")

// link ends with ".html"
private data class Ref(val link: String, val name: String)

private fun matchRef(line: String): Ref? {
    REF_HTML_LINE_REGEX.matchEntire(line)?.let {
        return Ref(link = it.groups[1]!!.value, name = it.groups[2]!!.value)
    }
    REF_MD_LINE_REGEX.matchEntire(line)?.let {
        var link = it.groups[2]!!.value
        if (link.endsWith(MD_SUFFIX)) link = link.substring(0, link.length - MD_SUFFIX.length) + HTML_SUFFIX
        return Ref(link = link, name = it.groups[1]!!.value)
    }
    return null
}

private fun HashMap<String, MutableList<String>>.putUnambiguous(key: String, value: String) {
    val oldValue = this[key]
    if (oldValue != null) {
        oldValue.add(value)
        put(key, oldValue)
    } else {
        put(key, mutableListOf(value))
    }
}

private fun KnitContext.loadApiIndex(
    docsRoot: String,
    path: String,
    pkg: String,
    namePrefix: String = ""
): Map<String, MutableList<String>>? {
    val fileName = "$docsRoot/$path$INDEX_MD"
    val visited = mutableSetOf<String>()
    val map = HashMap<String, MutableList<String>>()
    var inFunctionsSection = false
    withLineNumberReader(rootDir / fileName, ::LineNumberReader) {
        while (true) {
            val line = readLine() ?: break
            if (line == FUNCTIONS_SECTION_HEADER) inFunctionsSection = true
            var (link, name) = matchRef(line) ?: continue
            if (link.startsWith("..")) continue // ignore cross-references
            val absLink = "$path/$link"
            // a special disambiguation fix for pseudo-constructor functions
            if (inFunctionsSection && name[0] in 'A'..'Z') name += "()"
            val refName = namePrefix + name
            val fqName = "$pkg.$refName"
            // Put shorter names for extensions on 3rd party classes (prefix is FQname of those classes)
            if (namePrefix != "" && namePrefix[0] in 'a'..'z') {
                val i = namePrefix.dropLast(1).lastIndexOf('.')
                if (i >= 0) map.putUnambiguous(namePrefix.substring(i + 1) + name, absLink)
                map.putUnambiguous(name, absLink)
            }
            // Disambiguate lower-case names with leading underscore (e.g. Flow class vs flow builder ambiguity)
            if (namePrefix == "" && name[0] in 'a'..'z') {
                map.putUnambiguous("_$name", absLink)
            }
            // Always put fully qualified names
            map.putUnambiguous(refName, absLink)
            map.putUnambiguous(fqName, absLink)
            if (link.endsWith(INDEX_HTML)) {
                if (visited.add(link)) {
                    val path2 = path + "/" + link.substring(0, link.length - INDEX_HTML.length)
                    map += loadApiIndex(docsRoot, path2, pkg, "$refName.")
                        ?: throw IllegalArgumentException("Failed to parse $docsRoot/$path2")
                }
            }
        }
    } ?: return null // return null on failure
    return map
}

fun KnitContext.processApiIndex(
    siteRoot: String,
    docsRoot: String,
    pkg: String,
    remainingApiRefNames: MutableSet<String>
): List<String>? {
    val key = ApiIndexKey(docsRoot, pkg)
    val map = apiIndexCache.getOrPut(key) {
        val result = loadApiIndex(docsRoot, pkg, pkg) ?: return null // null on failure
        log.debug("Parsed API docs at $docsRoot/$pkg: ${result.size} definitions")
        result
    }
    val indexList = arrayListOf<String>()
    val it = remainingApiRefNames.iterator()
    while (it.hasNext()) {
        val refName = it.next()
        val refLink = map[refName] ?: continue
        // taking the shortest reference among candidates
        val link = refLink.minBy { it.length }
        indexList += "[$refName]: $siteRoot/$link"
        it.remove()
    }
    return indexList
}