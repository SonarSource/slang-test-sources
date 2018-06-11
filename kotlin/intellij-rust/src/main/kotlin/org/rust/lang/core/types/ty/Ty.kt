/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.ty

import com.intellij.util.BitUtil
import org.rust.ide.presentation.tyToString
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.RsTypeParameter
import org.rust.lang.core.psi.ext.namedFields
import org.rust.lang.core.psi.ext.positionalFields
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.infer.TypeFoldable
import org.rust.lang.core.types.infer.TypeFolder
import org.rust.lang.core.types.infer.TypeVisitor
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.type

typealias Substitution = Map<TyTypeParameter, Ty>
val emptySubstitution: Substitution = emptyMap()

typealias TypeFlags = Int
const val HAS_TY_INFER_MASK: Int = 1
const val HAS_TY_TYPE_PARAMETER_MASK: Int = 2
const val HAS_TY_PROJECTION_MASK: Int = 4

/**
 * Represents both a type, like `i32` or `S<Foo, Bar>`, as well
 * as an unbound constructor `S`.
 *
 * The name `Ty` is short for `Type`, inspired by the Rust
 * compiler.
 */
abstract class Ty(val flags: TypeFlags = 0): TypeFoldable<Ty> {

    override fun foldWith(folder: TypeFolder): Ty = folder(this)

    override fun superFoldWith(folder: TypeFolder): Ty = this

    override fun visitWith(visitor: TypeVisitor): Boolean = visitor(this)

    override fun superVisitWith(visitor: TypeVisitor): Boolean = false

    /**
     * Bindings between formal type parameters and actual type arguments.
     */
    open val typeParameterValues: Substitution get() = emptySubstitution

    /**
     * User visible string representation of a type
     */
    final override fun toString(): String = tyToString(this)
}

enum class Mutability {
    MUTABLE,
    IMMUTABLE;

    val isMut: Boolean get() = this == MUTABLE

    companion object {
        fun valueOf(mutable: Boolean): Mutability =
            if (mutable) MUTABLE else IMMUTABLE
    }
}

fun Ty.getTypeParameter(name: String): TyTypeParameter? {
    return typeParameterValues.keys.find { it.toString() == name }
}

val Ty.hasTyInfer
    get(): Boolean = BitUtil.isSet(flags, HAS_TY_INFER_MASK)

val Ty.hasTyTypeParameters
    get(): Boolean = BitUtil.isSet(flags, HAS_TY_TYPE_PARAMETER_MASK)

val Ty.hasTyProjection
    get(): Boolean = BitUtil.isSet(flags, HAS_TY_PROJECTION_MASK)

fun <K, V : TypeFoldable<V>> Map<K, V>.substituteInValues(map: Substitution): Map<K, V> =
    mapValues { (_, value) -> value.substitute(map) }

fun <K, V : TypeFoldable<V>> Map<K, V>.foldValues(folder: TypeFolder): Map<K, V> =
    mapValues { (_, value) -> value.foldWith(folder) }

fun Substitution.get(psi: RsTypeParameter): Ty? {
    return get(TyTypeParameter.named((psi)))
}

fun mergeFlags(element: BoundElement<*>): TypeFlags =
    element.subst.values.fold(0) { a, b -> a or b.flags } or element.assoc.values.fold(0) { a, b -> a or b.flags }

fun mergeFlags(tys: List<Ty>): TypeFlags =
    tys.fold(0) { a, b -> a or b.flags }

tailrec fun Ty.isSized(): Boolean {
    return when (this) {
        is TyPrimitive,
        is TyReference,
        is TyPointer,
        is TyArray,
        is TyFunction -> true
        is TySlice, is TyTraitObject -> false
        is TyTypeParameter -> isSized
        is TyAdt -> {
            val item = item as? RsStructItem ?: return true
            val namedFields = item.namedFields
            val tupleFields = item.positionalFields
            val typeRef = when {
                namedFields.isNotEmpty() -> namedFields.last().typeReference
                tupleFields.isNotEmpty() -> tupleFields.last().typeReference
                else -> null
            }
            val type = typeRef?.type?.substitute(typeParameterValues) ?: return true
            type.isSized()
        }
        is TyTuple -> types.last().isSized()
        else -> true
    }
}
