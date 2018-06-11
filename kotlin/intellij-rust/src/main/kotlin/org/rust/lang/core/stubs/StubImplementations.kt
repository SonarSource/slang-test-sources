/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.util.BitUtil
import org.rust.lang.RsLanguage
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psi.impl.*
import org.rust.lang.core.types.ty.TyFloat
import org.rust.lang.core.types.ty.TyInteger
import org.rust.stdext.makeBitMask


class RsFileStub : PsiFileStubImpl<RsFile> {
    val attributes: RsFile.Attributes

    constructor(file: RsFile) : this(file, file.attributes)

    constructor(file: RsFile?, attributes: RsFile.Attributes) : super(file) {
        this.attributes = attributes
    }

    override fun getType() = Type

    object Type : IStubFileElementType<RsFileStub>(RsLanguage) {
        // Bump this number if Stub structure changes
        override fun getStubVersion(): Int = 130

        override fun getBuilder(): StubBuilder = object : DefaultStubBuilder() {
            override fun createStubForFile(file: PsiFile): StubElement<*> = RsFileStub(file as RsFile)
        }

        override fun serialize(stub: RsFileStub, dataStream: StubOutputStream) {
            dataStream.writeEnum(stub.attributes)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsFileStub {
            return RsFileStub(null, dataStream.readEnum())
        }

        override fun getExternalId(): String = "Rust.file"

//        Uncomment to find out what causes switch to the AST
//
//        private val PARESED = com.intellij.util.containers.ContainerUtil.newConcurrentSet<String>()
//        override fun doParseContents(chameleon: ASTNode, psi: com.intellij.psi.PsiElement): ASTNode? {
//            val path = psi.containingFile?.virtualFile?.path
//            if (path != null && PARESED.add(path)) {
//                println("Parsing (${PARESED.size}) $path")
//                val trace = java.io.StringWriter().also { writer ->
//                    Exception().printStackTrace(java.io.PrintWriter(writer))
//                    writer.toString()
//                }
//                println(trace)
//                println()
//            }
//            return super.doParseContents(chameleon, psi)
//        }
    }
}


fun factory(name: String): RsStubElementType<*, *> = when (name) {
    "EXTERN_CRATE_ITEM" -> RsExternCrateItemStub.Type
    "USE_ITEM" -> RsUseItemStub.Type

    "STRUCT_ITEM" -> RsStructItemStub.Type
    "ENUM_ITEM" -> RsEnumItemStub.Type
    "ENUM_BODY" -> RsPlaceholderStub.Type("ENUM_BODY", ::RsEnumBodyImpl)
    "ENUM_VARIANT" -> RsEnumVariantStub.Type

    "MOD_DECL_ITEM" -> RsModDeclItemStub.Type
    "MOD_ITEM" -> RsModItemStub.Type

    "TRAIT_ITEM" -> RsTraitItemStub.Type
    "IMPL_ITEM" -> RsImplItemStub.Type
    "MEMBERS" -> RsPlaceholderStub.Type("MEMBERS", ::RsMembersImpl)

    "FUNCTION" -> RsFunctionStub.Type
    "CONSTANT" -> RsConstantStub.Type
    "TYPE_ALIAS" -> RsTypeAliasStub.Type
    "FOREIGN_MOD_ITEM" -> RsPlaceholderStub.Type("FOREIGN_MOD_ITEM", ::RsForeignModItemImpl)

    "BLOCK_FIELDS" -> RsPlaceholderStub.Type("BLOCK_FIELDS", ::RsBlockFieldsImpl)
    "TUPLE_FIELDS" -> RsPlaceholderStub.Type("TUPLE_FIELDS", ::RsTupleFieldsImpl)
    "TUPLE_FIELD_DECL" -> RsPlaceholderStub.Type("TUPLE_FIELD_DECL", ::RsTupleFieldDeclImpl)
    "FIELD_DECL" -> RsFieldDeclStub.Type
    "ALIAS" -> RsAliasStub.Type

    "USE_SPECK" -> RsUseSpeckStub.Type
    "USE_GROUP" -> RsPlaceholderStub.Type("USE_GROUP", ::RsUseGroupImpl)

    "PATH" -> RsPathStub.Type
    "TYPE_QUAL" -> RsPlaceholderStub.Type("TYPE_QUAL", ::RsTypeQualImpl)

    "TRAIT_REF" -> RsPlaceholderStub.Type("TRAIT_REF", ::RsTraitRefImpl)
    "TYPE_REFERENCE" -> RsPlaceholderStub.Type("TYPE_REFERENCE", ::RsTypeReferenceImpl)

    "ARRAY_TYPE" -> RsArrayTypeStub.Type
    "REF_LIKE_TYPE" -> RsRefLikeTypeStub.Type
    "FN_POINTER_TYPE" -> RsPlaceholderStub.Type("FN_POINTER_TYPE", ::RsFnPointerTypeImpl)
    "TUPLE_TYPE" -> RsPlaceholderStub.Type("TUPLE_TYPE", ::RsTupleTypeImpl)
    "BASE_TYPE" -> RsBaseTypeStub.Type
    "FOR_IN_TYPE" -> RsPlaceholderStub.Type("FOR_IN_TYPE", ::RsForInTypeImpl)
    "TRAIT_TYPE" -> RsTraitTypeStub.Type

    "VALUE_PARAMETER_LIST" -> RsPlaceholderStub.Type("VALUE_PARAMETER_LIST", ::RsValueParameterListImpl)
    "VALUE_PARAMETER" -> RsValueParameterStub.Type
    "SELF_PARAMETER" -> RsSelfParameterStub.Type
    "TYPE_PARAMETER_LIST" -> RsPlaceholderStub.Type("TYPE_PARAMETER_LIST", ::RsTypeParameterListImpl)
    "TYPE_PARAMETER" -> RsTypeParameterStub.Type
    "LIFETIME_PARAMETER" -> RsLifetimeParameterStub.Type
    "TYPE_ARGUMENT_LIST" -> RsPlaceholderStub.Type("TYPE_ARGUMENT_LIST", ::RsTypeArgumentListImpl)
    "ASSOC_TYPE_BINDING" -> RsAssocTypeBindingStub.Type

    "TYPE_PARAM_BOUNDS" -> RsPlaceholderStub.Type("TYPE_PARAM_BOUNDS", ::RsTypeParamBoundsImpl)
    "POLYBOUND" -> RsPlaceholderStub.Type("POLYBOUND", ::RsPolyboundImpl)
    "BOUND" -> RsPlaceholderStub.Type("BOUND", ::RsBoundImpl)
    "WHERE_CLAUSE" -> RsPlaceholderStub.Type("WHERE_CLAUSE", ::RsWhereClauseImpl)
    "WHERE_PRED" -> RsPlaceholderStub.Type("WHERE_PRED", ::RsWherePredImpl)

    "RET_TYPE" -> RsPlaceholderStub.Type("RET_TYPE", ::RsRetTypeImpl)

    "MACRO_DEFINITION" -> RsMacroDefinitionStub.Type
    "MACRO_CALL" -> RsMacroCallStub.Type

    "INNER_ATTR" -> RsPlaceholderStub.Type("INNER_ATTR", ::RsInnerAttrImpl)
    "OUTER_ATTR" -> RsPlaceholderStub.Type("OUTER_ATTR", ::RsOuterAttrImpl)

    "META_ITEM" -> RsMetaItemStub.Type
    "META_ITEM_ARGS" -> RsPlaceholderStub.Type("META_ITEM_ARGS", ::RsMetaItemArgsImpl)

    "BLOCK" -> RsPlaceholderStub.Type("BLOCK", ::RsBlockImpl)

    "BINARY_OP" -> RsBinaryOpStub.Type

    "ARRAY_EXPR" -> RsExprStub.Type("ARRAY_EXPR", ::RsArrayExprImpl)
    "BINARY_EXPR" -> RsExprStub.Type("BINARY_EXPR", ::RsBinaryExprImpl)
    "BLOCK_EXPR" -> RsExprStub.Type("BLOCK_EXPR", ::RsBlockExprImpl)
    "BREAK_EXPR" -> RsExprStub.Type("BREAK_EXPR", ::RsBreakExprImpl)
    "CALL_EXPR" -> RsExprStub.Type("CALL_EXPR", ::RsCallExprImpl)
    "CAST_EXPR" -> RsExprStub.Type("CAST_EXPR", ::RsCastExprImpl)
    "CONT_EXPR" -> RsExprStub.Type("CONT_EXPR", ::RsContExprImpl)
    "DOT_EXPR" -> RsExprStub.Type("DOT_EXPR", ::RsDotExprImpl)
    "EXPR_STMT_OR_LAST_EXPR" -> RsExprStub.Type("EXPR_STMT_OR_LAST_EXPR", ::RsExprStmtOrLastExprImpl)
    "FOR_EXPR" -> RsExprStub.Type("FOR_EXPR", ::RsForExprImpl)
    "IF_EXPR" -> RsExprStub.Type("IF_EXPR", ::RsIfExprImpl)
    "INDEX_EXPR" -> RsExprStub.Type("INDEX_EXPR", ::RsIndexExprImpl)
    "LAMBDA_EXPR" -> RsExprStub.Type("LAMBDA_EXPR", ::RsLambdaExprImpl)
    "LIT_EXPR" -> RsLitExprStub.Type
    "LOOP_EXPR" -> RsExprStub.Type("LOOP_EXPR", ::RsLoopExprImpl)
    "MACRO_EXPR" -> RsExprStub.Type("MACRO_EXPR", ::RsMacroExprImpl)
    "MATCH_EXPR" -> RsExprStub.Type("MATCH_EXPR", ::RsMatchExprImpl)
    "PAREN_EXPR" -> RsExprStub.Type("PAREN_EXPR", ::RsParenExprImpl)
    "PATH_EXPR" -> RsExprStub.Type("PATH_EXPR", ::RsPathExprImpl)
    "RANGE_EXPR" -> RsExprStub.Type("RANGE_EXPR", ::RsRangeExprImpl)
    "RET_EXPR" -> RsExprStub.Type("RET_EXPR", ::RsRetExprImpl)
    "STRUCT_LITERAL" -> RsExprStub.Type("STRUCT_LITERAL", ::RsStructLiteralImpl)
    "TRY_EXPR" -> RsExprStub.Type("TRY_EXPR", ::RsTryExprImpl)
    "TUPLE_EXPR" -> RsExprStub.Type("TUPLE_EXPR", ::RsTupleExprImpl)
    "TUPLE_OR_PAREN_EXPR" -> RsExprStub.Type("TUPLE_OR_PAREN_EXPR", ::RsTupleOrParenExprImpl)
    "UNARY_EXPR" -> RsExprStub.Type("UNARY_EXPR", ::RsUnaryExprImpl)
    "UNIT_EXPR" -> RsExprStub.Type("UNIT_EXPR", ::RsUnitExprImpl)
    "WHILE_EXPR" -> RsExprStub.Type("WHILE_EXPR", ::RsWhileExprImpl)

    else -> error("Unknown element $name")
}


class RsExternCrateItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean
) : StubBase<RsExternCrateItem>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsExternCrateItemStub, RsExternCrateItem>("EXTERN_CRATE_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsExternCrateItemStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsExternCrateItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
            }

        override fun createPsi(stub: RsExternCrateItemStub) =
            RsExternCrateItemImpl(stub, this)

        override fun createStub(psi: RsExternCrateItem, parentStub: StubElement<*>?) =
            RsExternCrateItemStub(parentStub, this, psi.name, psi.isPublic)

        override fun indexStub(stub: RsExternCrateItemStub, sink: IndexSink) = sink.indexExternCrate(stub)
    }
}


class RsUseItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val isPublic: Boolean
) : RsElementStub<RsUseItem>(parent, elementType),
    RsVisibilityStub {

    object Type : RsStubElementType<RsUseItemStub, RsUseItem>("USE_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsUseItemStub(parentStub, this,
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsUseItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeBoolean(stub.isPublic)
            }

        override fun createPsi(stub: RsUseItemStub) =
            RsUseItemImpl(stub, this)

        override fun createStub(psi: RsUseItem, parentStub: StubElement<*>?) =
            RsUseItemStub(parentStub, this, psi.isPublic)
    }
}

class RsUseSpeckStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isStarImport: Boolean
) : RsElementStub<RsUseSpeck>(parent, elementType) {

    object Type : RsStubElementType<RsUseSpeckStub, RsUseSpeck>("USE_SPECK") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsUseSpeckStub(parentStub, this,
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsUseSpeckStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeBoolean(stub.isStarImport)
            }

        override fun createPsi(stub: RsUseSpeckStub) =
            RsUseSpeckImpl(stub, this)

        override fun createStub(psi: RsUseSpeck, parentStub: StubElement<*>?) =
            RsUseSpeckStub(parentStub, this, psi.isStarImport)

        override fun indexStub(stub: RsUseSpeckStub, sink: IndexSink) = sink.indexUseSpeck(stub)
    }
}

class RsStructItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean,
    val isUnion: Boolean
) : StubBase<RsStructItem>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsStructItemStub, RsStructItem>("STRUCT_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsStructItemStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsStructItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
                writeBoolean(stub.isUnion)
            }

        override fun createPsi(stub: RsStructItemStub): RsStructItem =
            RsStructItemImpl(stub, this)

        override fun createStub(psi: RsStructItem, parentStub: StubElement<*>?) =
            RsStructItemStub(parentStub, this, psi.name, psi.isPublic, psi.kind == RsStructKind.UNION)


        override fun indexStub(stub: RsStructItemStub, sink: IndexSink) = sink.indexStructItem(stub)
    }
}


class RsEnumItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean
) : StubBase<RsEnumItem>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsEnumItemStub, RsEnumItem>("ENUM_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsEnumItemStub =
            RsEnumItemStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsEnumItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
            }

        override fun createPsi(stub: RsEnumItemStub) =
            RsEnumItemImpl(stub, this)

        override fun createStub(psi: RsEnumItem, parentStub: StubElement<*>?) =
            RsEnumItemStub(parentStub, this, psi.name, psi.isPublic)


        override fun indexStub(stub: RsEnumItemStub, sink: IndexSink) = sink.indexEnumItem(stub)

    }
}


class RsEnumVariantStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsEnumVariant>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsEnumVariantStub, RsEnumVariant>("ENUM_VARIANT") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsEnumVariantStub =
            RsEnumVariantStub(parentStub, this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsEnumVariantStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }

        override fun createPsi(stub: RsEnumVariantStub) =
            RsEnumVariantImpl(stub, this)

        override fun createStub(psi: RsEnumVariant, parentStub: StubElement<*>?) =
            RsEnumVariantStub(parentStub, this, psi.name)

        override fun indexStub(stub: RsEnumVariantStub, sink: IndexSink) {
            sink.indexEnumVariant(stub)
        }
    }
}


class RsModDeclItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean,
    val isLocal: Boolean    //TODO: get rid of it
) : StubBase<RsModDeclItem>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsModDeclItemStub, RsModDeclItem>("MOD_DECL_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsModDeclItemStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsModDeclItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
                writeBoolean(stub.isLocal)
            }

        override fun createPsi(stub: RsModDeclItemStub) =
            RsModDeclItemImpl(stub, this)

        override fun createStub(psi: RsModDeclItem, parentStub: StubElement<*>?) =
            RsModDeclItemStub(parentStub, this, psi.name, psi.isPublic, psi.isLocal)

        override fun indexStub(stub: RsModDeclItemStub, sink: IndexSink) = sink.indexModDeclItem(stub)
    }
}


class RsModItemStub(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean
) : StubBase<RsModItem>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsModItemStub, RsModItem>("MOD_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsModItemStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsModItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
            }

        override fun createPsi(stub: RsModItemStub): RsModItem =
            RsModItemImpl(stub, this)

        override fun createStub(psi: RsModItem, parentStub: StubElement<*>?) =
            RsModItemStub(parentStub, this, psi.name, psi.isPublic)

        override fun indexStub(stub: RsModItemStub, sink: IndexSink) = sink.indexModItem(stub)
    }
}


class RsTraitItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean,
    val isUnsafe: Boolean
) : StubBase<RsTraitItem>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsTraitItemStub, RsTraitItem>("TRAIT_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTraitItemStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsTraitItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
                writeBoolean(stub.isUnsafe)
            }

        override fun createPsi(stub: RsTraitItemStub): RsTraitItem =
            RsTraitItemImpl(stub, this)

        override fun createStub(psi: RsTraitItem, parentStub: StubElement<*>?) =
            RsTraitItemStub(parentStub, this, psi.name, psi.isPublic, psi.isUnsafe)

        override fun indexStub(stub: RsTraitItemStub, sink: IndexSink) = sink.indexTraitItem(stub)
    }
}


class RsImplItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>
) : RsElementStub<RsImplItem>(parent, elementType) {
    object Type : RsStubElementType<RsImplItemStub, RsImplItem>("IMPL_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsImplItemStub(parentStub, this)

        override fun serialize(stub: RsImplItemStub, dataStream: StubOutputStream) {
        }

        override fun createPsi(stub: RsImplItemStub): RsImplItem =
            RsImplItemImpl(stub, this)

        override fun createStub(psi: RsImplItem, parentStub: StubElement<*>?) =
            RsImplItemStub(parentStub, this)

        override fun indexStub(stub: RsImplItemStub, sink: IndexSink) = sink.indexImplItem(stub)
    }
}


class RsFunctionStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val abiName: String?,
    private val flags: Int
) : StubBase<RsFunction>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    override val isPublic: Boolean get() = BitUtil.isSet(flags, PUBLIC_MASK)
    val isAbstract: Boolean get() = BitUtil.isSet(flags, ABSTRACT_MASK)
    val isTest: Boolean get() = BitUtil.isSet(flags, TEST_MASK)
    val isCfg: Boolean get() = BitUtil.isSet(flags, CFG_MASK)
    val isConst: Boolean get() = BitUtil.isSet(flags, CONST_MASK)
    val isUnsafe: Boolean get() = BitUtil.isSet(flags, UNSAFE_MASK)
    val isExtern: Boolean get() = BitUtil.isSet(flags, EXTERN_MASK)
    val isVariadic: Boolean get() = BitUtil.isSet(flags, VARIADIC_MASK)

    object Type : RsStubElementType<RsFunctionStub, RsFunction>("FUNCTION") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsFunctionStub(parentStub, this,
                dataStream.readName()?.string,
                dataStream.readUTFFastAsNullable(),
                dataStream.readInt()
            )

        override fun serialize(stub: RsFunctionStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeUTFFastAsNullable(stub.abiName)
                writeInt(stub.flags)
            }

        override fun createPsi(stub: RsFunctionStub) =
            RsFunctionImpl(stub, this)

        override fun createStub(psi: RsFunction, parentStub: StubElement<*>?): RsFunctionStub {
            var flags = 0
            flags = BitUtil.set(flags, PUBLIC_MASK, psi.isPublic)
            flags = BitUtil.set(flags, ABSTRACT_MASK, psi.isAbstract)
            flags = BitUtil.set(flags, TEST_MASK, psi.isTest)
            flags = BitUtil.set(flags, CFG_MASK, psi.queryAttributes.hasCfgAttr())
            flags = BitUtil.set(flags, CONST_MASK, psi.isConst)
            flags = BitUtil.set(flags, UNSAFE_MASK, psi.isUnsafe)
            flags = BitUtil.set(flags, EXTERN_MASK, psi.isExtern)
            flags = BitUtil.set(flags, VARIADIC_MASK, psi.isExtern)
            return RsFunctionStub(parentStub, this,
                name = psi.name,
                abiName = psi.abiName,
                flags = flags
            )
        }

        override fun indexStub(stub: RsFunctionStub, sink: IndexSink) = sink.indexFunction(stub)
    }

    companion object {
        private val PUBLIC_MASK: Int = makeBitMask(0)
        private val ABSTRACT_MASK: Int = makeBitMask(1)
        private val TEST_MASK: Int = makeBitMask(2)
        private val CFG_MASK: Int = makeBitMask(3)
        private val CONST_MASK: Int = makeBitMask(4)
        private val UNSAFE_MASK: Int = makeBitMask(5)
        private val EXTERN_MASK: Int = makeBitMask(6)
        private val VARIADIC_MASK: Int = makeBitMask(7)
    }
}


class RsConstantStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean,
    val isMut: Boolean,
    val isConst: Boolean
) : StubBase<RsConstant>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsConstantStub, RsConstant>("CONSTANT") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsConstantStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsConstantStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
                writeBoolean(stub.isMut)
                writeBoolean(stub.isConst)
            }

        override fun createPsi(stub: RsConstantStub) =
            RsConstantImpl(stub, this)

        override fun createStub(psi: RsConstant, parentStub: StubElement<*>?) =
            RsConstantStub(parentStub, this, psi.name, psi.isPublic, psi.isMut, psi.isConst)

        override fun indexStub(stub: RsConstantStub, sink: IndexSink) = sink.indexConstant(stub)
    }
}


class RsTypeAliasStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean
) : StubBase<RsTypeAlias>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsTypeAliasStub, RsTypeAlias>("TYPE_ALIAS") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTypeAliasStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsTypeAliasStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
            }

        override fun createPsi(stub: RsTypeAliasStub) =
            RsTypeAliasImpl(stub, this)

        override fun createStub(psi: RsTypeAlias, parentStub: StubElement<*>?) =
            RsTypeAliasStub(parentStub, this, psi.name, psi.isPublic)

        override fun indexStub(stub: RsTypeAliasStub, sink: IndexSink) = sink.indexTypeAlias(stub)
    }
}


class RsFieldDeclStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val isPublic: Boolean
) : StubBase<RsFieldDecl>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    object Type : RsStubElementType<RsFieldDeclStub, RsFieldDecl>("FIELD_DECL") {
        override fun createPsi(stub: RsFieldDeclStub) =
            RsFieldDeclImpl(stub, this)

        override fun createStub(psi: RsFieldDecl, parentStub: StubElement<*>?) =
            RsFieldDeclStub(parentStub, this, psi.name, psi.isPublic)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsFieldDeclStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsFieldDeclStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isPublic)
            }

        override fun indexStub(stub: RsFieldDeclStub, sink: IndexSink) = sink.indexFieldDecl(stub)
    }
}


class RsAliasStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsAlias>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsAliasStub, RsAlias>("ALIAS") {
        override fun createPsi(stub: RsAliasStub) =
            RsAliasImpl(stub, this)

        override fun createStub(psi: RsAlias, parentStub: StubElement<*>?) =
            RsAliasStub(parentStub, this, psi.name)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsAliasStub(parentStub, this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsAliasStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }
    }
}


class RsPathStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val referenceName: String,
    val hasColonColon: Boolean,
    val hasCself: Boolean
) : StubBase<RsPath>(parent, elementType) {

    object Type : RsStubElementType<RsPathStub, RsPath>("PATH") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun createPsi(stub: RsPathStub) =
            RsPathImpl(stub, this)

        override fun createStub(psi: RsPath, parentStub: StubElement<*>?) =
            RsPathStub(parentStub, this, psi.referenceName, psi.hasColonColon, psi.hasCself)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsPathStub(parentStub, this,
                dataStream.readName()!!.string,
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsPathStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.referenceName)
                writeBoolean(stub.hasColonColon)
                writeBoolean(stub.hasCself)
            }
    }
}


class RsTypeParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val isSized: Boolean
) : StubBase<RsTypeParameter>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsTypeParameterStub, RsTypeParameter>("TYPE_PARAMETER") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTypeParameterStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsTypeParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeBoolean(stub.isSized)
            }

        override fun createPsi(stub: RsTypeParameterStub): RsTypeParameter =
            RsTypeParameterImpl(stub, this)

        override fun createStub(psi: RsTypeParameter, parentStub: StubElement<*>?) =
            RsTypeParameterStub(parentStub, this, psi.name, psi.isSized)
    }
}


class RsValueParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val patText: String?,
    val typeReferenceText: String?
) : StubBase<RsValueParameter>(parent, elementType) {

    object Type : RsStubElementType<RsValueParameterStub, RsValueParameter>("VALUE_PARAMETER") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsValueParameterStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsValueParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                dataStream.writeName(stub.patText)
                dataStream.writeName(stub.typeReferenceText)
            }

        override fun createPsi(stub: RsValueParameterStub): RsValueParameter =
            RsValueParameterImpl(stub, this)

        override fun createStub(psi: RsValueParameter, parentStub: StubElement<*>?) =
            RsValueParameterStub(parentStub, this, psi.patText, psi.typeReferenceText)
    }
}


class RsSelfParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isMut: Boolean,
    val isRef: Boolean,
    val isExplicitType: Boolean
) : StubBase<RsSelfParameter>(parent, elementType) {

    object Type : RsStubElementType<RsSelfParameterStub, RsSelfParameter>("SELF_PARAMETER") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsSelfParameterStub(parentStub, this,
                dataStream.readBoolean(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsSelfParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                dataStream.writeBoolean(stub.isMut)
                dataStream.writeBoolean(stub.isRef)
                dataStream.writeBoolean(stub.isExplicitType)
            }

        override fun createPsi(stub: RsSelfParameterStub): RsSelfParameter =
            RsSelfParameterImpl(stub, this)

        override fun createStub(psi: RsSelfParameter, parentStub: StubElement<*>?) =
            RsSelfParameterStub(parentStub, this, psi.mutability.isMut, psi.isRef, psi.isExplicitType)
    }
}


class RsRefLikeTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isMut: Boolean,
    val isRef: Boolean,
    val isPointer: Boolean
) : StubBase<RsTypeElement>(parent, elementType) {

    object Type : RsStubElementType<RsRefLikeTypeStub, RsRefLikeType>("REF_LIKE_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsRefLikeTypeStub(parentStub, this,
                dataStream.readBoolean(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsRefLikeTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            dataStream.writeBoolean(stub.isMut)
            dataStream.writeBoolean(stub.isRef)
            dataStream.writeBoolean(stub.isPointer)
        }

        override fun createPsi(stub: RsRefLikeTypeStub) = RsRefLikeTypeImpl(stub, this)

        override fun createStub(psi: RsRefLikeType, parentStub: StubElement<*>?) =
            RsRefLikeTypeStub(parentStub, this,
                psi.mutability.isMut,
                psi.isRef,
                psi.isPointer
            )
    }
}


class RsTraitTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isImpl: Boolean
) : StubBase<RsTypeElement>(parent, elementType) {

    object Type : RsStubElementType<RsTraitTypeStub, RsTraitType>("TRAIT_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTraitTypeStub(parentStub, this,
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsTraitTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            dataStream.writeBoolean(stub.isImpl)
        }

        override fun createPsi(stub: RsTraitTypeStub) = RsTraitTypeImpl(stub, this)

        override fun createStub(psi: RsTraitType, parentStub: StubElement<*>?) =
            RsTraitTypeStub(parentStub, this,
                psi.isImpl
            )
    }
}

class RsBaseTypeStub private constructor(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    private val variant: Variant
) : StubBase<RsBaseType>(parent, elementType) {

    val isUnit: Boolean
        get() = variant == Variant.UNIT
    val isNever: Boolean
        get() = variant == Variant.NEVER
    val isUnderscore: Boolean
        get() = variant == Variant.UNDERSCORE

    object Type : RsStubElementType<RsBaseTypeStub, RsBaseType>("BASE_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsBaseTypeStub(parentStub, this, Variant.valueOf(dataStream.readByte().toInt()))

        override fun serialize(stub: RsBaseTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            dataStream.writeByte(stub.variant.ordinal)
        }

        override fun createPsi(stub: RsBaseTypeStub) =
            RsBaseTypeImpl(stub, this)

        override fun createStub(psi: RsBaseType, parentStub: StubElement<*>?) =
            RsBaseTypeStub(parentStub, this, Variant.fromPsi(psi))
    }

    private enum class Variant {
        DEFAULT, UNIT, NEVER, UNDERSCORE;

        companion object {
            private val _values = values()

            fun valueOf(ordinal: Int): Variant =
                _values[ordinal]

            fun fromPsi(psi: RsBaseType): Variant = when {
                psi.isUnit -> UNIT
                psi.isNever -> NEVER
                psi.isUnderscore -> UNDERSCORE
                else -> DEFAULT
            }
        }
    }
}

class RsArrayTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isSlice: Boolean
) : StubBase<RsArrayType>(parent, elementType) {

    object Type : RsStubElementType<RsArrayTypeStub, RsArrayType>("ARRAY_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsArrayTypeStub(parentStub, this, dataStream.readBoolean())

        override fun serialize(stub: RsArrayTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            dataStream.writeBoolean(stub.isSlice)
        }

        override fun createPsi(stub: RsArrayTypeStub) =
            RsArrayTypeImpl(stub, this)

        override fun createStub(psi: RsArrayType, parentStub: StubElement<*>?) =
            RsArrayTypeStub(parentStub, this, psi.isSlice)
    }
}

class RsLifetimeParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsLifetimeParameter>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsLifetimeParameterStub, RsLifetimeParameter>("LIFETIME_PARAMETER") {
        override fun createPsi(stub: RsLifetimeParameterStub) =
            RsLifetimeParameterImpl(stub, this)

        override fun createStub(psi: RsLifetimeParameter, parentStub: StubElement<*>?) =
            RsLifetimeParameterStub(parentStub, this, psi.name)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsLifetimeParameterStub(parentStub, this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsLifetimeParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }
    }
}

class RsMacroDefinitionStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val macroBody: String?
) : StubBase<RsMacroDefinition>(parent, elementType),
    RsNamedStub,
    RsVisibilityStub {

    override val isPublic: Boolean get() = true

    object Type : RsStubElementType<RsMacroDefinitionStub, RsMacroDefinition>("MACRO_DEFINITION") {
        override fun shouldCreateStub(node: ASTNode): Boolean = node.psi.parent is RsMod

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsMacroDefinitionStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readUTFFastAsNullable()
            )

        override fun serialize(stub: RsMacroDefinitionStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeUTFFastAsNullable(stub.macroBody)
            }

        override fun createPsi(stub: RsMacroDefinitionStub): RsMacroDefinition =
            RsMacroDefinitionImpl(stub, this)

        override fun createStub(psi: RsMacroDefinition, parentStub: StubElement<*>?) =
            RsMacroDefinitionStub(parentStub, this, psi.name, psi.macroDefinitionBody?.text)

        override fun indexStub(stub: RsMacroDefinitionStub, sink: IndexSink) = sink.indexMacroDefinition(stub)
    }
}

class RsMacroCallStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val macroName: String,
    val macroBody: String?
) : StubBase<RsMacroCall>(parent, elementType) {

    object Type : RsStubElementType<RsMacroCallStub, RsMacroCall>("MACRO_CALL") {
        override fun shouldCreateStub(node: ASTNode): Boolean = node.psi.parent is RsMod

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsMacroCallStub(parentStub, this,
                dataStream.readNameAsString()!!,
                dataStream.readUTFFastAsNullable()
            )

        override fun serialize(stub: RsMacroCallStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.macroName)
                writeUTFFastAsNullable(stub.macroBody)
            }

        override fun createPsi(stub: RsMacroCallStub): RsMacroCall =
            RsMacroCallImpl(stub, this)

        override fun createStub(psi: RsMacroCall, parentStub: StubElement<*>?) =
            RsMacroCallStub(parentStub, this, psi.macroName, psi.macroBody)

        override fun indexStub(stub: RsMacroCallStub, sink: IndexSink) = Unit
    }
}

class RsMetaItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val referenceName: String,
    val hasEq: Boolean,
    val value: String?
) : StubBase<RsMetaItem>(parent, elementType) {
    object Type : RsStubElementType<RsMetaItemStub, RsMetaItem>("META_ITEM") {
        override fun createStub(psi: RsMetaItem, parentStub: StubElement<*>?): RsMetaItemStub =
            RsMetaItemStub(parentStub, this, psi.referenceName, psi.eq != null, psi.litExpr?.stringLiteralValue)

        override fun createPsi(stub: RsMetaItemStub): RsMetaItem = RsMetaItemImpl(stub, this)

        override fun serialize(stub: RsMetaItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.referenceName)
                writeBoolean(stub.hasEq)
                writeUTFFastAsNullable(stub.value)
            }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsMetaItemStub =
            RsMetaItemStub(parentStub, this,
                dataStream.readNameAsString()!!,
                dataStream.readBoolean(),
                dataStream.readUTFFastAsNullable())

        override fun indexStub(stub: RsMetaItemStub, sink: IndexSink) {
        }
    }
}

class RsBinaryOpStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val op: String
) : StubBase<RsBinaryOp>(parent, elementType) {
    object Type : RsStubElementType<RsBinaryOpStub, RsBinaryOp>("BINARY_OP") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun serialize(stub: RsBinaryOpStub, dataStream: StubOutputStream) {
            dataStream.writeUTFFast(stub.op)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsBinaryOpStub =
            RsBinaryOpStub(parentStub, this, dataStream.readUTFFast())

        override fun createStub(psi: RsBinaryOp, parentStub: StubElement<*>?): RsBinaryOpStub =
            RsBinaryOpStub(parentStub, this, psi.op)

        override fun createPsi(stub: RsBinaryOpStub): RsBinaryOp = RsBinaryOpImpl(stub, this)
    }
}

class RsExprStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>
) : RsPlaceholderStub(parent, elementType) {
    class Type<PsiT : RsElement>(
        debugName: String,
        private val psiCtor: (RsPlaceholderStub, IStubElementType<*, *>) -> PsiT
    ) : RsStubElementType<RsPlaceholderStub, PsiT>(debugName) {

        override fun shouldCreateStub(node: ASTNode): Boolean =
            createStubIfParentIsStub(node) && node.psi.parent?.parent !is RsFunction

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?)
            = RsPlaceholderStub(parentStub, this)

        override fun serialize(stub: RsPlaceholderStub, dataStream: StubOutputStream) {}

        override fun createPsi(stub: RsPlaceholderStub) = psiCtor(stub, this)

        override fun createStub(psi: PsiT, parentStub: StubElement<*>?) = RsPlaceholderStub(parentStub, this)
    }
}

class RsLitExprStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val type: RsStubLiteralType?,
    val integerLiteralValue: String?
) : RsPlaceholderStub(parent, elementType) {
    object Type : RsStubElementType<RsLitExprStub, RsLitExpr>("LIT_EXPR") {

        override fun shouldCreateStub(node: ASTNode): Boolean =
            createStubIfParentIsStub(node) && node.psi.parent?.parent !is RsFunction

        override fun serialize(stub: RsLitExprStub, dataStream: StubOutputStream) {
            stub.type.serialize(dataStream)
            dataStream.writeUTFFastAsNullable(stub.integerLiteralValue)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsLitExprStub =
            RsLitExprStub(parentStub, this, RsStubLiteralType.deserialize(dataStream), dataStream.readUTFFastAsNullable())

        override fun createStub(psi: RsLitExpr, parentStub: StubElement<*>?): RsLitExprStub =
            RsLitExprStub(parentStub, this, psi.stubType, psi.integerLiteralValue)

        override fun createPsi(stub: RsLitExprStub): RsLitExpr = RsLitExprImpl(stub, this)
    }
}

sealed class RsStubLiteralType(val typeOrdinal: Int) {
    object Boolean : RsStubLiteralType(0)
    class Char(val isByte: kotlin.Boolean) : RsStubLiteralType(1)
    class String(val length: Long?, val isByte: kotlin.Boolean) : RsStubLiteralType(2)
    class Integer(val kind: TyInteger?) : RsStubLiteralType(3)
    class Float(val kind: TyFloat?) : RsStubLiteralType(4)

    companion object {
        fun deserialize(dataStream: StubInputStream): RsStubLiteralType? {
            with (dataStream) {
                val ordinal = readByte().toInt()
                return when (ordinal) {
                    0 -> RsStubLiteralType.Boolean
                    1 -> RsStubLiteralType.Char(readBoolean())
                    2 -> RsStubLiteralType.String(readLong(), readBoolean())
                    3 -> RsStubLiteralType.Integer(TyInteger.VALUES.getOrNull(readByte().toInt()))
                    4 -> RsStubLiteralType.Float(TyFloat.VALUES.getOrNull(readByte().toInt()))
                    else -> null
                }
            }
        }
    }
}

private fun RsStubLiteralType?.serialize(dataStream: StubOutputStream) {
    if (this == null) {
        dataStream.writeByte(-1)
        return
    }
    dataStream.writeByte(typeOrdinal)
    when (this) {
        is RsStubLiteralType.Char -> dataStream.writeBoolean(isByte)
        is RsStubLiteralType.String -> {
            dataStream.writeLong(length ?: 0)
            dataStream.writeBoolean(isByte)
        }
        is RsStubLiteralType.Integer -> dataStream.writeByte(kind?.ordinal ?: -1)
        is RsStubLiteralType.Float -> dataStream.writeByte(kind?.ordinal ?: -1)
    }
}

class RsAssocTypeBindingStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsAssocTypeBinding>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsAssocTypeBindingStub, RsAssocTypeBinding>("ASSOC_TYPE_BINDING") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsAssocTypeBindingStub(parentStub, this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsAssocTypeBindingStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }

        override fun createPsi(stub: RsAssocTypeBindingStub): RsAssocTypeBinding =
            RsAssocTypeBindingImpl(stub, this)

        override fun createStub(psi: RsAssocTypeBinding, parentStub: StubElement<*>?) =
            RsAssocTypeBindingStub(parentStub, this, psi.identifier.text)
    }
}

private fun StubInputStream.readNameAsString(): String? = readName()?.string
private fun StubInputStream.readUTFFastAsNullable(): String? {
    val hasValue = readBoolean()
    return if (hasValue) readUTFFast() else null
}
private fun StubOutputStream.writeUTFFastAsNullable(value: String?) {
    if (value == null) {
        writeBoolean(false)
    } else {
        writeBoolean(true)
        writeUTFFast(value)
    }
}

private fun <E : Enum<E>> StubOutputStream.writeEnum(e: E?) = writeByte(e?.ordinal ?: -1)
private inline fun <reified E : Enum<E>> StubInputStream.readEnum(): E = enumValues<E>()[readByte().toInt()]
