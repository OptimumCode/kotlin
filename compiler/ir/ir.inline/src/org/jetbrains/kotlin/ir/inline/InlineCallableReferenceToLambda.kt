/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.inline

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.addExtensionReceiver
import org.jetbrains.kotlin.backend.common.lower.LoweredDeclarationOrigins
import org.jetbrains.kotlin.backend.common.lower.LoweredStatementOrigins
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.toBuilder
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.Name

const val STUB_FOR_INLINING = "stub_for_inlining"

// This lowering transforms CR passed to inline function to lambda which would be inlined
//
//      inline fun foo(inlineParameter: (A) -> B): B {
//          return inlineParameter()
//      }
//
//      foo(::smth) -> foo { a -> smth(a) }
//
abstract class InlineCallableReferenceToLambdaPhase(
    val context: CommonBackendContext,
    protected val inlineFunctionResolver: InlineFunctionResolver,
) : FileLoweringPass, IrElementVisitor<Unit, IrDeclarationParent?> {
    override fun lower(irFile: IrFile) = irFile.accept(this, null)

    override fun visitElement(element: IrElement, data: IrDeclarationParent?) =
        element.acceptChildren(this, data)

    override fun visitDeclaration(declaration: IrDeclarationBase, data: IrDeclarationParent?) =
        super.visitDeclaration(declaration, declaration as? IrDeclarationParent ?: data)

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression, data: IrDeclarationParent?) {
        expression.acceptChildren(this, data)
        val function = expression.symbol.owner
        if (inlineFunctionResolver.needsInlining(function)) {
            for (parameter in function.valueParameters) {
                if (parameter.isInlineParameter()) {
                    expression.putValueArgument(parameter.index, expression.getValueArgument(parameter.index)?.transform(data))
                }
            }
        }
    }

    protected fun IrExpression.transform(scope: IrDeclarationParent?) = when {
        this is IrBlock && origin.isInlinable -> apply {
            // Already a lambda or similar, just mark it with an origin.
            val reference = statements.last() as IrFunctionReference
            reference.symbol.owner.origin = LoweredDeclarationOrigins.INLINE_LAMBDA
            reference.origin = LoweredStatementOrigins.INLINE_LAMBDA
        }

        this is IrFunctionReference -> {
            // ::function -> { args... -> function(args...) }
            wrapFunction(symbol.owner).toLambda(this, scope!!)
        }

        this is IrPropertyReference -> {
            val isReferenceToSyntheticJavaProperty = symbol.owner.origin.let {
                it == IrDeclarationOrigin.SYNTHETIC_JAVA_PROPERTY_DELEGATE || it == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB
            }
            when {
                // References to generic synthetic Java properties aren't inlined in K1. Fixes KT-57103
                typeArgumentsCount > 0 && isReferenceToSyntheticJavaProperty -> this
                // ::property -> { receiver -> receiver.property }; prefer direct field access if allowed.
                field != null -> wrapField(field!!.owner).toLambda(this, scope!!)
                else -> wrapFunction(getter!!.owner).toLambda(this, scope!!)
            }
        }


        else -> this // not an inline argument
    }

    private fun IrPropertyReference.wrapField(field: IrField): IrSimpleFunction =
        context.irFactory.buildFun {
            setSourceRange(this@wrapField)
            origin = LoweredDeclarationOrigins.INLINE_LAMBDA
            name = Name.identifier(STUB_FOR_INLINING)
            visibility = DescriptorVisibilities.LOCAL
            returnType = field.type
            isInline = true
        }.apply {
            body = context.createIrBuilder(symbol).run {
                val boundReceiver = dispatchReceiver ?: extensionReceiver
                val fieldReceiver = when {
                    field.isStatic -> null
                    boundReceiver != null -> irGet(addExtensionReceiver(boundReceiver.type))
                    else -> irGet(addValueParameter("receiver", field.parentAsClass.defaultType))
                }
                irBlockBody {
                    val exprToReturn = irGetField(fieldReceiver, field)
                    +irReturn(exprToReturn)
                }
            }
        }

    private fun IrCallableReference<*>.wrapFunction(referencedFunction: IrFunction): IrSimpleFunction =
        context.irFactory.buildFun {
            setSourceRange(this@wrapFunction)
            origin = LoweredDeclarationOrigins.INLINE_LAMBDA
            name = Name.identifier(STUB_FOR_INLINING)
            visibility = DescriptorVisibilities.LOCAL
            returnType = ((type as IrSimpleType).arguments.last() as IrTypeProjection).type
            isSuspend = referencedFunction.isSuspend
            isInline = true
        }.apply {
            body = context.createIrBuilder(symbol, startOffset, endOffset).run {
                // TODO: could there be a star projection here?
                val argumentTypes = (type as IrSimpleType).arguments.dropLast(1).map { (it as IrTypeProjection).type }
                val boundReceiver = dispatchReceiver ?: extensionReceiver
                val boundReceiverParameter = when {
                    dispatchReceiver != null -> referencedFunction.dispatchReceiverParameter
                    extensionReceiver != null -> referencedFunction.extensionReceiverParameter
                    else -> null
                }
                irBlockBody {
                    val exprToReturn = irCall(referencedFunction.symbol, returnType).apply {
                        copyTypeArgumentsFrom(this@wrapFunction)
                        for (parameter in referencedFunction.explicitParameters) {
                            val next = valueParameters.size
                            when {
                                boundReceiverParameter == parameter -> irGet(addExtensionReceiver(boundReceiver!!.type))
                                parameter.isVararg && next < argumentTypes.size && parameter.type == argumentTypes[next] ->
                                    irGet(addValueParameter("p$next", argumentTypes[next]))
                                parameter.isVararg && (next < argumentTypes.size || !parameter.hasDefaultValue()) ->
                                    error("Callable reference with vararg should not appear at this stage.\n${this@wrapFunction.render()}")
                                next >= argumentTypes.size -> null
                                else -> irGet(addValueParameter("p$next", argumentTypes[next]))
                            }?.let { putArgument(referencedFunction, parameter, it) }
                        }
                    }
                    +irReturn(exprToReturn)
                }
            }
        }

    private fun IrSimpleFunction.toLambda(original: IrCallableReference<*>, scope: IrDeclarationParent) =
        context.createIrBuilder(symbol).irBlock(startOffset, endOffset, IrStatementOrigin.LAMBDA) {
            this@toLambda.parent = scope
            +this@toLambda
            +IrFunctionReferenceImpl.fromSymbolOwner(
                startOffset, endOffset, original.type.convertKPropertyToKFunction(context.irBuiltIns), symbol,
                typeArgumentsCount = 0, reflectionTarget = null,
                origin = LoweredStatementOrigins.INLINE_LAMBDA
            ).apply {
                copyAttributes(original)
                extensionReceiver = original.dispatchReceiver ?: original.extensionReceiver
            }
        }
}

private val IrStatementOrigin?.isInlinable: Boolean
    get() = isLambda || this == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE || this == IrStatementOrigin.SUSPEND_CONVERSION

private fun IrType.convertKPropertyToKFunction(irBuiltIns: IrBuiltIns): IrType {
    if (this !is IrSimpleType) return this
    if (!this.isKProperty() && !this.isKMutableProperty()) return this

    return this.toBuilder().apply { classifier = irBuiltIns.functionN(arguments.size - 1).symbol }.buildSimpleType()
}
