/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import org.rust.ide.icons.RsIcons
import org.rust.ide.icons.addTestMark
import org.rust.lang.core.macros.ExpansionResult
import org.rust.lang.core.psi.*
import org.rust.lang.core.stubs.RsFunctionStub
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyUnit
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.lang.core.types.type
import javax.swing.Icon

val RsFunction.isAssocFn: Boolean get() = selfParameter == null && owner.isImplOrTrait

val RsFunction.isTest: Boolean get() {
    val stub = stub
    return stub?.isTest ?: queryAttributes.hasAtomAttribute("test")
}

val RsFunction.isConst: Boolean get() {
    val stub = stub
    return stub?.isConst ?: (const != null)
}

val RsFunction.isExtern: Boolean get() {
    val stub = stub
    return stub?.isExtern ?: (abi != null)
}

val RsFunction.isVariadic: Boolean get() {
    val stub = stub
    return stub?.isVariadic ?: (valueParameterList?.dotdotdot != null)
}

val RsFunction.abiName: String? get() {
    val stub = stub
    return stub?.abiName ?: abi?.stringLiteral?.text
}

val RsFunction.valueParameters: List<RsValueParameter>
    get() = valueParameterList?.valueParameterList.orEmpty()

val RsFunction.selfParameter: RsSelfParameter?
    get() = valueParameterList?.selfParameter

val RsFunction.default: PsiElement?
    get() = node.findChildByType(RsElementTypes.DEFAULT)?.psi

val RsFunction.title: String
    get() = when (owner) {
        is RsAbstractableOwner.Free -> "Function `$name`"
        is RsAbstractableOwner.Foreign -> "Foreign function `$name`"
        is RsAbstractableOwner.Trait, is RsAbstractableOwner.Impl ->
            if (isAssocFn) "Associated function `$name`" else "Method `$name`"
    }

val RsFunction.returnType: Ty get() {
    val retType = retType ?: return TyUnit
    return retType.typeReference?.type ?: TyUnknown
}

val RsFunction.abi: RsExternAbi? get() = externAbi ?: (parent as? RsForeignModItem)?.externAbi

abstract class RsFunctionImplMixin : RsStubbedNamedElementImpl<RsFunctionStub>, RsFunction {

    constructor(node: ASTNode) : super(node)

    constructor(stub: RsFunctionStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override val isPublic: Boolean get() = RsPsiImplUtil.isPublic(this, stub)

    override val isAbstract: Boolean get() = stub?.isAbstract ?: (block == null)

    override val isUnsafe: Boolean get() = this.stub?.isUnsafe ?: (unsafe != null)

    override val crateRelativePath: String? get() = RsPsiImplUtil.crateRelativePath(this)

    final override val innerAttrList: List<RsInnerAttr>
        get() = block?.innerAttrList.orEmpty()

    override fun getIcon(flags: Int): Icon = when (owner) {
        is RsAbstractableOwner.Free, is RsAbstractableOwner.Foreign ->
            if (isTest) RsIcons.FUNCTION.addTestMark() else RsIcons.FUNCTION

        is RsAbstractableOwner.Trait, is RsAbstractableOwner.Impl -> when {
            isAssocFn && isAbstract -> RsIcons.ABSTRACT_ASSOC_FUNCTION
            isAssocFn -> RsIcons.ASSOC_FUNCTION
            isAbstract -> RsIcons.ABSTRACT_METHOD
            else -> RsIcons.METHOD
        }
    }

    override fun getContext(): RsElement = ExpansionResult.getContextImpl(this)
}
