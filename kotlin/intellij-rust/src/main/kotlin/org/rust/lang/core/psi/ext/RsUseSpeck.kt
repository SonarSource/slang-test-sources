/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.RsUseGroup
import org.rust.lang.core.psi.RsUseSpeck
import org.rust.lang.core.stubs.RsUseSpeckStub

val RsUseSpeck.isStarImport: Boolean get() = stub?.isStarImport ?: (mul != null) // I hate operator precedence
val RsUseSpeck.qualifier: RsPath? get() =
    (context as? RsUseGroup)?.parentUseSpeck?.path

val RsUseSpeck.nameInScope: String? get() {
    if (useGroup != null) return null
    alias?.name?.let { return it }
    val baseName = path?.referenceName ?: return null
    if (baseName == "self") {
        return qualifier?.referenceName
    }
    return baseName
}

abstract class RsUseSpeckImplMixin : RsStubbedElementImpl<RsUseSpeckStub>, RsUseSpeck {
    constructor (node: ASTNode) : super(node)
    constructor (stub: RsUseSpeckStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)
}
