/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.presentation

import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.ext.typeParameters
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.ty.*


val Ty.shortPresentableText: String get() = render(this, level = 3)
val Ty.insertionSafeText: String
    get() = render(this, level = Int.MAX_VALUE, unknown = "_", anonymous = "_", integer = "_", float = "_")

fun tyToString(ty: Ty) = render(ty, Int.MAX_VALUE)
fun tyToStringWithoutTypeArgs(ty: Ty) = render(ty, Int.MAX_VALUE, includeTypeArguments = false)

private fun render(
    ty: Ty,
    level: Int,
    unknown: String = "<unknown>",
    anonymous: String = "<anonymous>",
    integer: String = "{integer}",
    float: String = "{float}",
    includeTypeArguments: Boolean = true
): String {
    check(level >= 0)
    if (ty is TyUnknown) return unknown
    if (ty is TyPrimitive) {
        return when (ty) {
            is TyBool -> "bool"
            is TyChar -> "char"
            is TyUnit -> "()"
            is TyNever -> "!"
            is TyStr -> "str"
            is TyInteger -> ty.name
            is TyFloat -> ty.name
            else -> error("unreachable")
        }
    }

    if (level == 0) return "_"

    val r = { subTy: Ty -> render(subTy, level - 1, unknown, anonymous, integer, float) }

    return when (ty) {
        is TyFunction -> {
            val params = ty.paramTypes.joinToString(", ", "fn(", ")", transform = r)
            return if (ty.retType is TyUnit) params else "$params -> ${ty.retType}"

        }
        is TySlice -> "[${r(ty.elementType)}]"

        is TyTuple -> ty.types.joinToString(", ", "(", ")", transform = r)
        is TyArray -> "[${r(ty.base)}; ${ty.size ?: unknown}]"
        is TyReference -> "${if (ty.mutability.isMut) "&mut " else "&"}${
        render(ty.referenced, level, unknown, anonymous, integer, float)
        }"
        is TyPointer -> "*${if (ty.mutability.isMut) "mut" else "const"} ${r(ty.referenced)}"
        is TyTypeParameter -> ty.name ?: anonymous
        is TyProjection -> "<${ty.type} as ${ty.trait.element.name ?: return anonymous}${
        if (includeTypeArguments) formatTraitTypeArguments(ty.trait, r, includeAssoc = false) else ""
        }>::${ty.target.name}"
        is TyTraitObject -> (ty.trait.element.name ?: return anonymous) +
            if (includeTypeArguments) formatTraitTypeArguments(ty.trait, r) else ""
        is TyAdt -> (ty.item.name ?: return anonymous) +
            if (includeTypeArguments) formatTypeArguments(ty.typeArguments, r) else ""
        is TyInfer -> when (ty) {
            is TyInfer.TyVar -> "_"
            is TyInfer.IntVar -> integer
            is TyInfer.FloatVar -> float
        }
        is FreshTyInfer -> "<fresh>" // really should never be displayed; debug only
        is TyAnon -> ty.traits.joinToString("+", "impl ") {
            (it.element.name ?: anonymous) +
                if (includeTypeArguments) formatTraitTypeArguments(it, r) else ""
        }
        else -> error("unreachable")
    }
}

private fun formatTypeArguments(typeArguments: List<Ty>, r: (Ty) -> String) =
    if (typeArguments.isEmpty()) "" else typeArguments.joinToString(", ", "<", ">", transform = r)

private fun formatTraitTypeArguments(
    e: BoundElement<RsTraitItem>,
    r: (Ty) -> String,
    includeAssoc: Boolean = true
): String {
    val subst = e.element.typeParameters.map { r(e.subst[TyTypeParameter.named(it)] ?: TyUnknown) }
    val assoc = if (includeAssoc) {
        e.element.associatedTypesTransitively.mapNotNull {
            val name = it.name ?: return@mapNotNull null
            name + "=" + r(e.assoc[it] ?: TyUnknown)
        }
    } else {
        emptyList()
    }
    val visibleTypes = subst + assoc
    return if (visibleTypes.isEmpty()) "" else visibleTypes.joinToString(", ", "<", ">")
}
