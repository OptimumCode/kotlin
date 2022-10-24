/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irCatch
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.jvm.*
import org.jetbrains.kotlin.backend.jvm.MemoizedMultiFieldValueClassReplacements.RemappedParameter
import org.jetbrains.kotlin.backend.jvm.MemoizedMultiFieldValueClassReplacements.RemappedParameter.MultiFieldValueClassMapping
import org.jetbrains.kotlin.backend.jvm.MemoizedMultiFieldValueClassReplacements.RemappedParameter.RegularMapping
import org.jetbrains.kotlin.backend.jvm.ir.createJvmIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.lower.BlockOrBody.Block
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.transformStatement
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name

val jvmMultiFieldValueClassPhase = makeIrFilePhase(
    ::JvmMultiFieldValueClassLowering,
    name = "Multi-field Value Classes",
    description = "Lower multi-field value classes",
    // Collection stubs may require mangling by multi-field value class rules.
    // SAM wrappers may require mangling for fun interfaces with multi-field value class parameters
    prerequisite = setOf(collectionStubMethodLowering, singleAbstractMethodPhase),
)

private class JvmMultiFieldValueClassLowering(context: JvmBackendContext) : JvmValueClassAbstractLowering(context) {
    private sealed class MfvcNodeInstanceAccessor {
        abstract val instance: MfvcNodeInstance
        abstract operator fun get(name: Name): MfvcNodeInstanceAccessor?
        data class Getter(override val instance: MfvcNodeInstance) : MfvcNodeInstanceAccessor() {
            override operator fun get(name: Name): Getter? = instance[name]?.let { Getter(it) }
        }

        data class Setter(override val instance: MfvcNodeInstance, val values: List<IrExpression>) : MfvcNodeInstanceAccessor() {
            override operator fun get(name: Name): Setter? = instance[name]?.let {
                val indices = (instance as MfvcNodeWithSubnodes).subnodeIndices[it.node]!!
                Setter(it, values.slice(indices))
            }
        }
    }

    private val possibleExtraBoxUsageGenerated = mutableSetOf<IrDeclaration>()

    private val irCurrentScope
        get() = currentScope!!.irElement as IrDeclaration

    private fun registerPossibleExtraBoxUsage() {
        possibleExtraBoxUsageGenerated.add(irCurrentScope)
    }

    /**
     * The class is used to get replacing expression and MFVC instance if present for the given old value declaration.
     */
    private inner class ValueDeclarationRemapper {

        private val expression2MfvcNodeInstanceAccessor = mutableMapOf<IrExpression, MfvcNodeInstanceAccessor>()
        private val oldSymbol2MfvcNodeInstance = mutableMapOf<IrValueSymbol, ValueDeclarationMfvcNodeInstance>()
        private val oldValueSymbol2NewValueSymbol = mutableMapOf<IrValueSymbol, IrValueSymbol>()

        /**
         * Registers one-to-one replacement
         */
        fun registerReplacement(original: IrValueDeclaration, replacement: IrValueDeclaration) {
            oldValueSymbol2NewValueSymbol[original.symbol] = replacement.symbol
        }

        /**
         * Registers replacement of a simple expression with flattened MFVC instance
         */
        fun registerReplacement(original: IrValueDeclaration, replacement: ValueDeclarationMfvcNodeInstance) {
            oldSymbol2MfvcNodeInstance[original.symbol] = replacement
        }

        fun IrBuilderWithScope.makeReplacement(expression: IrGetValue): IrExpression? {
            oldValueSymbol2NewValueSymbol[expression.symbol]?.let { return irGet(it.owner) }
            val instance = oldSymbol2MfvcNodeInstance[expression.symbol] ?: return null
            val res = instance.makeGetterExpression(this, ::registerPossibleExtraBoxUsage)
            expression2MfvcNodeInstanceAccessor[res] = MfvcNodeInstanceAccessor.Getter(instance)
            return res
        }

        private fun splitExpressions(expressions: List<IrExpression>): Pair<List<IrExpression>, List<IrExpression>> {
            val repeatable = expressions.takeLastWhile { it.isRepeatableGetter() }
            return expressions.subList(0, expressions.size - repeatable.size) to repeatable
        }

        fun IrBlockBuilder.addReplacement(expression: IrSetValue, safe: Boolean): IrExpression? {
            oldValueSymbol2NewValueSymbol[expression.symbol]?.let { return irSet(it.owner, expression.value) }
            val instance = oldSymbol2MfvcNodeInstance[expression.symbol] ?: return null
            val values: List<IrExpression> = makeFlattenedExpressionsWithGivenSafety(instance.node, safe, expression.value)
            val setterExpressions = instance.makeSetterExpressions(this, values)
            expression2MfvcNodeInstanceAccessor[setterExpressions] = MfvcNodeInstanceAccessor.Setter(instance, values)
            +setterExpressions
            return setterExpressions
        }

        /**
         * @param safe whether protect from partial (because of a potential exception) initialization or not
         */
        private fun IrBlockBuilder.makeFlattenedExpressionsWithGivenSafety(
            node: MfvcNode, safe: Boolean, expression: IrExpression
        ) = if (safe) {
            val (forVariables, rest) = splitExpressions(flattenExpression(expression))
            val variables = when (node) {
                is LeafMfvcNode -> forVariables.map { expr -> irTemporary(expr) }
                is MfvcNodeWithSubnodes -> forVariables.zip(node.leaves) { expr, leaf ->
                    irTemporary(expr, nameHint = leaf.fullFieldName.asString())
                }
            }
            variables.map { irGet(it) } + rest
        } else {
            flattenExpression(expression)
        }

        private val IrFieldAccessExpression.field: IrField
            get() = this.symbol.owner

        fun IrBlockBuilder.addReplacement(expression: IrGetField): IrExpression? {
            val field = expression.field
            expression.receiver?.get(this, field.name)?.let { +it; return it }
            val node = replacements.getMfvcFieldNode(field) ?: return null
            val typeArguments = makeTypeArgumentsFromField(expression)
            val instance: ReceiverBasedMfvcNodeInstance =
                node.createInstanceFromBox(this, typeArguments, expression.receiver, AccessType.AlwaysPrivate, ::variablesSaver)
            val getterExpression = instance.makeGetterExpression(this, ::registerPossibleExtraBoxUsage)
            expression2MfvcNodeInstanceAccessor[getterExpression] = MfvcNodeInstanceAccessor.Getter(instance)
            +getterExpression
            return getterExpression
        }

        fun IrBlockBuilder.addReplacement(expression: IrSetField, safe: Boolean): IrExpression? {
            val field = expression.field
            expression.receiver?.get(this, field.name)?.let { +it; return it }
            val node = replacements.getMfvcFieldNode(field) ?: return null
            val typeArguments = makeTypeArgumentsFromField(expression)
            val instance: ReceiverBasedMfvcNodeInstance =
                node.createInstanceFromBox(this, typeArguments, expression.receiver, AccessType.AlwaysPrivate, ::variablesSaver)
            val values: List<IrExpression> = makeFlattenedExpressionsWithGivenSafety(node, safe, expression.value)
            val setterExpressions = instance.makeSetterExpressions(this, values)
            expression2MfvcNodeInstanceAccessor[setterExpressions] = MfvcNodeInstanceAccessor.Setter(instance, values)
            +setterExpressions
            return setterExpressions
        }

        fun IrBlockBuilder.addReplacement(expression: IrCall): IrExpression? {
            val function = expression.symbol.owner
            val property = function.property?.takeIf { function.isGetter } ?: return null
            val dispatchReceiver = expression.dispatchReceiver
            dispatchReceiver?.get(this, property.name)?.let { +it; return it }
            val node = replacements.getMfvcPropertyNode(property) ?: return null
            val typeArguments = makeTypeArgumentsFromFunction(expression)
            // Optimization: pure function access to leaf can be replaced with field access if the field itself is accessible
            val accessType = when {
                !node.hasPureUnboxMethod -> AccessType.AlwaysPublic
                dispatchReceiver == null -> AccessType.PrivateWhenNoBox
                else -> getOptimizedPublicAccess(dispatchReceiver.type.erasedUpperBound)
            }
            val instance: ReceiverBasedMfvcNodeInstance =
                node.createInstanceFromBox(this, typeArguments, dispatchReceiver, accessType, ::variablesSaver)
            val getterExpression = instance.makeGetterExpression(this, ::registerPossibleExtraBoxUsage)
            expression2MfvcNodeInstanceAccessor[getterExpression] = MfvcNodeInstanceAccessor.Getter(instance)
            +getterExpression
            return getterExpression
        }

        private fun makeTypeArgumentsFromField(expression: IrFieldAccessExpression) = buildMap {
            val field = expression.symbol.owner
            putAll(makeTypeArgumentsFromType(field.type as IrSimpleType))
            expression.receiver?.type?.let { putAll(makeTypeArgumentsFromType(it as IrSimpleType)) }
        }

        private fun makeTypeArgumentsFromFunction(expression: IrCall) = buildMap {
            val function = expression.symbol.owner
            putAll(makeTypeArgumentsFromType(function.returnType as IrSimpleType))
            expression.dispatchReceiver?.type?.let { putAll(makeTypeArgumentsFromType(it as IrSimpleType)) }
        }

        private inline fun IrBuilderWithScope.irContainer(oldBlock: IrContainerExpression, builder: IrBlockBuilder.() -> Unit) =
            oldBlock.run {
                if (isTransparentScope) irComposite(startOffset, endOffset, origin, body = builder)
                else irBlock(startOffset, endOffset, origin, body = builder)
            }

        private fun IrBuilderWithScope.handleSavedExpression(
            expression: IrExpression, handler: IrBuilderWithScope.(accessor: MfvcNodeInstanceAccessor) -> IrExpression?
        ): IrExpression? {
            val accessor = expression2MfvcNodeInstanceAccessor[expression]
            return when {
                accessor != null -> handler(accessor) ?: return null
                expression !is IrContainerExpression -> null
                else -> when (val lastExpression = expression.statements.lastOrNull()) {
                    is IrExpression -> irContainer(expression) {
                        val inner = handleSavedExpression(lastExpression, handler) ?: return null
                        for (oldStatement in expression.statements.subListWithoutLast(1)) {
                            +oldStatement
                        }
                        +inner
                    }

                    else -> null
                }
            }
        }

        fun IrExpression.get(scope: IrBuilderWithScope, name: Name): IrExpression? = scope.handleSavedExpression(this) { accessor ->
            val newAccessor = accessor[name] ?: return@handleSavedExpression null
            val expression = when (newAccessor) {
                is MfvcNodeInstanceAccessor.Setter -> newAccessor.instance.makeSetterExpressions(scope, newAccessor.values)
                is MfvcNodeInstanceAccessor.Getter -> newAccessor.instance.makeGetterExpression(scope, ::registerPossibleExtraBoxUsage)
            }
            expression2MfvcNodeInstanceAccessor[expression] = newAccessor
            expression
        }

        fun handleFlattenedGetterExpressions(
            scope: IrBuilderWithScope,
            expression: IrExpression,
            handler: IrBlockBuilder.(values: List<IrExpression>) -> IrExpression
        ): IrExpression? = scope.handleSavedExpression(expression) {
            irBlock { +handler(it.instance.makeFlattenedGetterExpressions(this, ::registerPossibleExtraBoxUsage)) }.unwrapBlock()
        }

        fun registerReplacement(expression: IrExpression, instance: MfvcNodeInstance) {
            expression2MfvcNodeInstanceAccessor[expression] = MfvcNodeInstanceAccessor.Getter(instance)
        }
    }

    private val IrField.property
        get() = correspondingPropertySymbol?.owner
    private val IrSimpleFunction.property
        get() = correspondingPropertySymbol?.owner

    private val valueDeclarationsRemapper = ValueDeclarationRemapper()

    override val replacements
        get() = context.multiFieldValueClassReplacements

    override fun IrClass.isSpecificLoweringLogicApplicable(): Boolean = isMultiFieldValueClass

    override val specificMangle: SpecificMangle
        get() = SpecificMangle.MultiField

    private val variablesToAdd = mutableMapOf<IrDeclarationParent, MutableSet<IrVariable>>()

    private fun variablesSaver(variable: IrVariable) {
        variablesToAdd.getOrPut(variable.parent) { mutableSetOf() }.add(variable)
    }

    override fun visitClassNew(declaration: IrClass): IrStatement {

        if (declaration.isSpecificLoweringLogicApplicable()) {
            handleSpecificNewClass(declaration)
        } else {
            handleNonSpecificNewClass(declaration)
        }

        declaration.transformDeclarationsFlat { memberDeclaration ->
            (if (memberDeclaration is IrFunction) withinScope(memberDeclaration) {
                transformFunctionFlat(memberDeclaration)
            } else {
                memberDeclaration.accept(this, null)
                null
            }).also { declarations ->
                for (replacingDeclaration in declarations ?: listOf(memberDeclaration)) {
                    when (replacingDeclaration) {
                        is IrFunction -> replacingDeclaration.body = replacingDeclaration.body?.makeBodyWithAddedVariables(
                            context, variablesToAdd[replacingDeclaration] ?: emptySet(), replacingDeclaration.symbol
                        )?.apply {
                            if (replacingDeclaration in possibleExtraBoxUsageGenerated) {
                                removeAllExtraBoxes()
                            }
                        }

                        is IrAnonymousInitializer -> replacingDeclaration.body = replacingDeclaration.body.makeBodyWithAddedVariables(
                            context, variablesToAdd[replacingDeclaration.parent] ?: emptySet(), replacingDeclaration.symbol
                        ).apply {
                            if (replacingDeclaration in possibleExtraBoxUsageGenerated) {
                                removeAllExtraBoxes()
                            }
                        } as IrBlockBody

                        else -> Unit
                    }
                }
            }
        }

        return declaration
    }

    private fun handleNonSpecificNewClass(irClass: IrClass) {
        irClass.primaryConstructor?.let {
            replacements.getReplacementForRegularClassConstructor(it)?.let { replacement -> addBindingsFor(it, replacement) }
        }
        val propertiesOrFields = collectPropertiesOrFieldsAfterLowering(irClass)
        val oldBackingFields = propertiesOrFields.mapNotNull { propertyOrField ->
            val property = (propertyOrField as? IrPropertyOrIrField.Property)?.property ?: return@mapNotNull null
            property.backingField?.let { property to it }
        }.toMap()
        val propertiesOrFieldsReplacement = collectRegularClassMfvcPropertiesOrFieldsReplacement(propertiesOrFields) // resets backing fields

        val fieldsToRemove = propertiesOrFieldsReplacement.keys.mapNotNull {
            when (it) {
                is IrPropertyOrIrField.Field -> it.field
                is IrPropertyOrIrField.Property -> oldBackingFields[it.property]
            }
        }.toSet()

        if (fieldsToRemove.isNotEmpty() || propertiesOrFieldsReplacement.isNotEmpty()) {
            val newDeclarations = makeNewDeclarationsForRegularClass(fieldsToRemove, propertiesOrFieldsReplacement, irClass)
            irClass.declarations.replaceAll(newDeclarations)
        }
    }

    private fun collectRegularClassMfvcPropertiesOrFieldsReplacement(propertiesOrFields: LinkedHashSet<IrPropertyOrIrField>) =
        LinkedHashMap<IrPropertyOrIrField, IntermediateMfvcNode>().apply {
            for (propertyOrField in propertiesOrFields) {
                val node = when (propertyOrField) {
                    is IrPropertyOrIrField.Field -> replacements.getMfvcFieldNode(propertyOrField.field)
                    is IrPropertyOrIrField.Property -> replacements.getMfvcPropertyNode(propertyOrField.property)
                } ?: continue
                put(propertyOrField, node as IntermediateMfvcNode)
            }
        }

    private fun makeNewDeclarationsForRegularClass(
        fieldsToRemove: Set<IrField>,
        propertiesOrFieldsReplacement: Map<IrPropertyOrIrField, IntermediateMfvcNode>,
        irClass: IrClass,
    ) = buildList {
        for (element in irClass.declarations) {
            when (element) {
                !is IrField, !in fieldsToRemove -> add(element)
                else -> {
                    val fields = element.property?.let { propertiesOrFieldsReplacement[IrPropertyOrIrField.Property(it)] }?.fields
                        ?: propertiesOrFieldsReplacement[IrPropertyOrIrField.Field(element)]?.fields
                    if (fields != null) {
                        addAll(fields)
                        element.initializer?.let { initializer -> add(makeInitializerReplacement(irClass, element, initializer)) }
                    } else {
                        add(element)
                    }
                }
            }
        }

        for ((propertyOrField, node) in propertiesOrFieldsReplacement.entries) {
            if (propertyOrField is IrPropertyOrIrField.Property) { // they are not used, only boxes are used for them
                addAll(node.allInnerUnboxMethods.filter { it.parent == irClass })
            }
        }
    }

    private fun makeInitializerReplacement(irClass: IrClass, element: IrField, initializer: IrExpressionBody): IrAnonymousInitializer =
        context.irFactory.createAnonymousInitializer(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET, origin = IrDeclarationOrigin.GENERATED_MULTI_FIELD_VALUE_CLASS_MEMBER,
            symbol = IrAnonymousInitializerSymbolImpl()
        ).apply {
            parent = irClass
            body = context.createIrBuilder(symbol).irBlockBody {
                +irSetField(
                    irClass.thisReceiver!!.takeUnless { element.isStatic }?.let { irGet(it) }, element, initializer.expression,
                    origin = UNSAFE_MFVC_SET_ORIGIN
                )
            }
            element.initializer = null
        }

    override fun handleSpecificNewClass(declaration: IrClass) {
        val rootNode = replacements.getRootMfvcNode(declaration)
        rootNode.replaceFields()
        declaration.declarations += rootNode.allUnboxMethods + listOfNotNull(
            // `takeIf` is a workaround for double addition problem: user-defined typed equals is already defined in the class
            rootNode.boxMethod, rootNode.specializedEqualsMethod.takeIf { rootNode.createdNewSpecializedEqualsMethod }
        )
        rootNode.replacePrimaryMultiFieldValueClassConstructor()
    }

    override fun transformSecondaryConstructorFlat(constructor: IrConstructor, replacement: IrSimpleFunction): List<IrDeclaration> {
        for (param in replacement.valueParameters) {
            param.transformChildrenVoid()
        }

        allScopes.push(createScope(replacement))
        replacement.body = context.createIrBuilder(replacement.symbol).irBlockBody {
            val thisVar = irTemporary(irType = replacement.returnType, nameHint = "\$this")
            constructor.body?.statements?.forEach { statement ->
                +statement.transformStatement(object : IrElementTransformerVoid() {
                    override fun visitClass(declaration: IrClass): IrStatement = declaration

                    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                        val oldPrimaryConstructor = replacements.getRootMfvcNode(constructor.constructedClass).oldPrimaryConstructor
                        thisVar.initializer = irCall(oldPrimaryConstructor).apply {
                            copyTypeAndValueArgumentsFrom(expression)
                        }
                        return irBlock {}
                    }

                    override fun visitGetValue(expression: IrGetValue): IrExpression = when (expression.symbol.owner) {
                        constructor.constructedClass.thisReceiver!! -> irGet(thisVar)
                        else -> super.visitGetValue(expression)
                    }

                    override fun visitReturn(expression: IrReturn): IrExpression {
                        expression.transformChildrenVoid()
                        if (expression.returnTargetSymbol != constructor.symbol)
                            return expression

                        return irReturn(irBlock(expression.startOffset, expression.endOffset) {
                            +expression.value
                            +irGet(thisVar)
                        })
                    }
                })
            }
            +irReturn(irGet(thisVar))
        }
            .also { addBindingsFor(constructor, replacement) }
            .transform(this@JvmMultiFieldValueClassLowering, null)
            .patchDeclarationParents(replacement)
        allScopes.pop()
        return listOf(replacement)
    }

    private object UNSAFE_MFVC_SET_ORIGIN : IrStatementOrigin

    private fun RootMfvcNode.replaceFields() {
        mfvc.declarations.removeIf { it is IrField && (!it.isStatic || it.type.needsMfvcFlattening()) }
        mfvc.declarations += fields
    }

    override fun createBridgeDeclaration(source: IrSimpleFunction, replacement: IrSimpleFunction, mangledName: Name): IrSimpleFunction =
        context.irFactory.buildFun {
            updateFrom(source)
            name = mangledName
            returnType = source.returnType
        }.apply {
            val isAnyOverriddenReplaced = replacement.overriddenSymbols.any {
                it.owner in replacements.bindingNewFunctionToParameterTemplateStructure
            }
            if (source.parentAsClass.isMultiFieldValueClass && isAnyOverriddenReplaced) {
                copyTypeParametersFrom(source) // without static type parameters
                val substitutionMap = makeTypeParameterSubstitutionMap(source, this)
                dispatchReceiverParameter = source.dispatchReceiverParameter!!.let { // source!!!
                    it.copyTo(this, type = it.type.substitute(substitutionMap))
                }
                require(replacement.dispatchReceiverParameter == null) {
                    """
                        Ambiguous receivers:
                        ${source.dispatchReceiverParameter!!.render()}
                        ${replacement.dispatchReceiverParameter!!.render()}
                        """.trimIndent()
                }
                require(replacement.extensionReceiverParameter == null) {
                    "Static replacement must have no extension receiver but ${replacement.extensionReceiverParameter!!.render()} found"
                }
                val replacementStructure = replacements.bindingNewFunctionToParameterTemplateStructure[replacement]!!
                val offset = replacementStructure[0].valueParameters.size
                valueParameters = replacement.valueParameters.drop(offset).map {
                    it.copyTo(this, type = it.type.substitute(substitutionMap), index = it.index - offset)
                }
                val bridgeStructure = replacementStructure.toMutableList().apply {
                    set(0, RegularMapping(dispatchReceiverParameter!!))
                }
                replacements.bindingNewFunctionToParameterTemplateStructure[this] = bridgeStructure
            } else {
                copyParameterDeclarationsFrom(source)
            }
            annotations = source.annotations
            parent = source.parent
            // We need to ensure that this bridge has the same attribute owner as its static inline class replacement, since this
            // is used in [CoroutineCodegen.isStaticInlineClassReplacementDelegatingCall] to identify the bridge and avoid generating
            // a continuation class.
            copyAttributes(source)
        }

    override fun createBridgeBody(source: IrSimpleFunction, target: IrSimpleFunction, original: IrFunction, inverted: Boolean) {
        allScopes.push(createScope(source))
        source.body = context.createIrBuilder(source.symbol, source.startOffset, source.endOffset).run {
            val sourceExplicitParameters = source.explicitParameters
            val targetExplicitParameters = target.explicitParameters
            irExprBody(irBlock {
                +irReturn(irCall(target).apply {
                    passTypeArgumentsWithOffsets(target, source) { source.typeParameters[it].defaultType }
                    val sourceStructure: List<RemappedParameter>? = replacements.bindingNewFunctionToParameterTemplateStructure[source]
                    val targetStructure: List<RemappedParameter>? = replacements.bindingNewFunctionToParameterTemplateStructure[target]
                    val errorMessage = {
                        """
                        Incompatible structures for
                        Source: $sourceStructure
                        ${source.render()}
                        Target: $targetStructure
                        ${target.render()}
                        """.trimIndent()
                    }
                    when (targetStructure) {
                        null -> when (sourceStructure) {
                            null -> require(targetExplicitParameters.size == sourceExplicitParameters.size, errorMessage)
                            else -> {
                                require(targetExplicitParameters.size == sourceStructure.size, errorMessage)
                                require(sourceExplicitParameters.size == sourceStructure.sumOf { it.valueParameters.size }, errorMessage)
                            }
                        }

                        else -> when (sourceStructure) {
                            null -> {
                                require(targetStructure.size == sourceExplicitParameters.size, errorMessage)
                                require(targetStructure.sumOf { it.valueParameters.size } == targetExplicitParameters.size, errorMessage)
                            }

                            else -> {
                                require(targetStructure.size == sourceStructure.size, errorMessage)
                                require(sourceStructure.sumOf { it.valueParameters.size } == sourceExplicitParameters.size, errorMessage)
                                require(targetStructure.sumOf { it.valueParameters.size } == targetExplicitParameters.size, errorMessage)
                                require((targetStructure zip sourceStructure).none { (t, s) ->
                                    t is MultiFieldValueClassMapping && s is MultiFieldValueClassMapping && t.rootMfvcNode != s.rootMfvcNode
                                }, errorMessage)
                            }
                        }
                    }
                    val structuresSizes = sourceStructure?.size ?: targetStructure?.size ?: targetExplicitParameters.size
                    var flattenedSourceIndex = 0
                    var flattenedTargetIndex = 0
                    for (i in 0 until structuresSizes) {
                        val remappedSourceParameter = sourceStructure?.get(i)
                        val remappedTargetParameter = targetStructure?.get(i)
                        when (remappedSourceParameter) {
                            is MultiFieldValueClassMapping -> {
                                when (remappedTargetParameter) {
                                    is MultiFieldValueClassMapping -> {
                                        require(remappedTargetParameter.valueParameters.size == remappedSourceParameter.valueParameters.size) {
                                            "Incompatible structures: $remappedTargetParameter, $remappedSourceParameter"
                                        }
                                        repeat(remappedTargetParameter.valueParameters.size) {
                                            putArgument(
                                                targetExplicitParameters[flattenedTargetIndex++],
                                                irGet(sourceExplicitParameters[flattenedSourceIndex++])
                                            )
                                        }
                                    }

                                    is RegularMapping, null -> {
                                        val valueArguments = sourceExplicitParameters
                                            .slice(flattenedSourceIndex until flattenedSourceIndex + remappedSourceParameter.valueParameters.size)
                                            .map { irGet(it) }
                                        val targetParameter = targetExplicitParameters[flattenedTargetIndex++]
                                        val boxedExpression = remappedSourceParameter.rootMfvcNode.makeBoxedExpression(
                                            this@irBlock,
                                            remappedSourceParameter.typeArguments,
                                            valueArguments,
                                            ::registerPossibleExtraBoxUsage
                                        )
                                        putArgument(targetParameter, boxedExpression)
                                            .also { flattenedSourceIndex += remappedSourceParameter.valueParameters.size }
                                    }
                                }
                            }

                            is RegularMapping, null -> when (remappedTargetParameter) {
                                is MultiFieldValueClassMapping -> {
                                    val receiver = sourceExplicitParameters[flattenedSourceIndex++]
                                    val rootNode = remappedTargetParameter.rootMfvcNode
                                    val instance = rootNode.createInstanceFromBox(
                                        this@irBlock, irGet(receiver), getOptimizedPublicAccess(rootNode.mfvc), ::variablesSaver,
                                    )
                                    val flattenedExpressions =
                                        instance.makeFlattenedGetterExpressions(this@irBlock, ::registerPossibleExtraBoxUsage)
                                    for (expression in flattenedExpressions) {
                                        putArgument(targetExplicitParameters[flattenedTargetIndex++], expression)
                                    }
                                }

                                else -> putArgument(
                                    targetExplicitParameters[flattenedTargetIndex++],
                                    irGet(sourceExplicitParameters[flattenedSourceIndex++])
                                )
                            }
                        }
                    }
                    require(flattenedTargetIndex == targetExplicitParameters.size && flattenedSourceIndex == sourceExplicitParameters.size) {
                        "Incorrect source:\n${source.dump()}\n\nfor target\n${target.dump()}"
                    }
                })
            })
        }
        allScopes.pop()
    }

    private fun IrMemberAccessExpression<*>.passTypeArgumentsWithOffsets(
        target: IrFunction, source: IrFunction, forCommonTypeParameters: (targetIndex: Int) -> IrType
    ) {
        val passedTypeParametersSize = minOf(target.typeParameters.size, source.typeParameters.size)
        val targetOffset = target.typeParameters.size - passedTypeParametersSize
        val sourceOffset = source.typeParameters.size - passedTypeParametersSize
        if (sourceOffset > 0) {
            // static fun calls method
            val dispatchReceiverType = source.parentAsClass.defaultType
            require(dispatchReceiverType.let {
                it.needsMfvcFlattening() && it.erasedUpperBound.typeParameters.size == sourceOffset
            }) { "Unexpected dispatcher receiver type: ${dispatchReceiverType.render()}" }
        }
        if (targetOffset > 0) {
            // method calls static fun
            val dispatchReceiverType = source.parentAsClass.defaultType
            require(dispatchReceiverType.let {
                it.needsMfvcFlattening() && it.erasedUpperBound.typeParameters.size == targetOffset
            }) { "Unexpected dispatcher receiver type: ${dispatchReceiverType.render()}" }
            dispatchReceiverType.erasedUpperBound.typeParameters.forEachIndexed { index, typeParameter ->
                putTypeArgument(index, typeParameter.defaultType)
            }
        }
        for (i in 0 until passedTypeParametersSize) {
            putTypeArgument(i + targetOffset, forCommonTypeParameters(i + sourceOffset))
        }
    }

    override fun addBindingsFor(original: IrFunction, replacement: IrFunction) {
        val parametersStructure = replacements.bindingOldFunctionToParameterTemplateStructure[original]!!
        require(parametersStructure.size == original.explicitParameters.size) {
            "Wrong value parameters structure: $parametersStructure"
        }
        require(parametersStructure.sumOf { it.valueParameters.size } == replacement.explicitParameters.size) {
            "Wrong value parameters structure: $parametersStructure"
        }
        val old2newList = original.explicitParameters.zip(
            parametersStructure.scan(0) { partial: Int, templates: RemappedParameter -> partial + templates.valueParameters.size }
                .zipWithNext { start: Int, finish: Int -> replacement.explicitParameters.slice(start until finish) }
        )
        for (i in old2newList.indices) {
            val (oldParameter, newParamList) = old2newList[i]
            when (val structure = parametersStructure[i]) {
                is RegularMapping -> valueDeclarationsRemapper.registerReplacement(oldParameter, newParamList.single())
                is MultiFieldValueClassMapping -> {
                    val mfvcNodeInstance = structure.rootMfvcNode.createInstanceFromValueDeclarationsAndBoxType(
                        structure.boxedType, newParamList
                    )
                    valueDeclarationsRemapper.registerReplacement(oldParameter, mfvcNodeInstance)
                }
            }
        }

        withinScope(replacement) {
            addDefaultArgumentsIfNeeded(replacement, old2newList, parametersStructure)
        }
    }

    private fun addDefaultArgumentsIfNeeded(
        replacement: IrFunction,
        old2newList: List<Pair<IrValueParameter, List<IrValueParameter>>>,
        parametersStructure: List<RemappedParameter>
    ) {
        for (i in old2newList.indices) {
            val (param, newParamList) = old2newList[i]
            val defaultValue = replacements.oldMfvcDefaultArguments[param] ?: continue
            val structure = parametersStructure[i]
            if (structure is MultiFieldValueClassMapping) {
                newParamList[0].defaultValue = with(context.createJvmIrBuilder(replacement.symbol)) {
                    irExprBody(irBlock {
                        val mfvcNodeInstance = structure.rootMfvcNode.createInstanceFromValueDeclarationsAndBoxType(
                            param.type as IrSimpleType, newParamList
                        )
                        flattenExpressionTo(defaultValue, mfvcNodeInstance)
                        +irGet(newParamList[0])
                    })
                }
            }
        }
    }

    override fun visitParameter(parameter: IrValueParameter) {
        if (parameter.origin != IrDeclarationOrigin.GENERATED_MULTI_FIELD_VALUE_CLASS_PARAMETER) {
            super.visitParameter(parameter)
        }
    }

    fun RootMfvcNode.replacePrimaryMultiFieldValueClassConstructor() {
        val rootMfvcNode = this
        mfvc.declarations.removeIf { it is IrConstructor && it.isPrimary }
        mfvc.declarations += listOf(newPrimaryConstructor, primaryConstructorImpl)

        val initializersBlocks = mfvc.declarations.filterIsInstance<IrAnonymousInitializer>()
        val typeArguments = makeTypeParameterSubstitutionMap(mfvc, primaryConstructorImpl)
        primaryConstructorImpl.body = context.createIrBuilder(primaryConstructorImpl.symbol).irBlockBody {
            val mfvcNodeInstance =
                ValueDeclarationMfvcNodeInstance(rootMfvcNode, typeArguments, primaryConstructorImpl.valueParameters)
            valueDeclarationsRemapper.registerReplacement(
                oldPrimaryConstructor.constructedClass.thisReceiver!!,
                mfvcNodeInstance
            )
            for (initializer in initializersBlocks) {
                +irBlock {
                    for (stmt in initializer.body.statements) {
                        +stmt.patchDeclarationParents(primaryConstructorImpl) // transformation is done later
                    }
                }
            }
        }
        mfvc.declarations.removeIf { it is IrAnonymousInitializer }
    }

    private val mfvcConstructorRefReplacements = mutableMapOf<Pair<IrFile, MfvcNode>, IrSimpleFunction>()

    override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
        val function = expression.symbol.owner
        val replacement = function.let {
            replacements.run {
                getReplacementFunction(it) ?: if (it is IrConstructor) getReplacementForRegularClassConstructor(it) else null
            }
        } ?: return super.visitFunctionReference(expression)
        return if (function is IrConstructor && function.isPrimary && function.constructedClass.isMultiFieldValueClass) {
            val rootNode = replacements.getRootMfvcNode(function.constructedClass)
            val newFunction: IrSimpleFunction = mfvcConstructorRefReplacements.getOrPut(currentFile to rootNode) {
                createMfvcPrimaryConstructorReferenceHelperFunction(rootNode)
            }
            IrFunctionReferenceImpl(
                expression.startOffset, expression.endOffset,
                newFunction.returnType, newFunction.symbol, function.typeParameters.size, replacement.valueParameters.size,
                newFunction.symbol, expression.origin
            )
        } else {
            context.createJvmIrBuilder(getCurrentScopeSymbol()).irBlock {
                expression.apply {
                    buildReplacement(function, expression, replacement) {
                        IrFunctionReferenceImpl(
                            startOffset, endOffset,
                            type, it, function.typeParameters.size, replacement.valueParameters.size,
                            reflectionTarget, origin
                        )
                    }.copyAttributes(expression)
                }
            }.unwrapBlock()
        }
    }

    override val fileClassNewDeclarations = mutableMapOf<IrFile, MutableList<IrSimpleFunction>>()

    private fun createMfvcPrimaryConstructorReferenceHelperFunction(rootNode: RootMfvcNode) = context.irFactory.buildFun {
        name = Name.identifier("mfvcConstructorReferenceHelper\$${fileClassNewDeclarations[currentFile]?.size ?: 0}")
        origin = JvmLoweredDeclarationOrigin.MFVC_PRIMARY_CONSTRUCTOR_REFERENCE_HELPER
    }.apply function@{
        fileClassNewDeclarations.getOrPut(currentFile) { mutableListOf() }.add(this)
        copyParameterDeclarationsFrom(rootNode.primaryConstructorImpl)
        returnType = rootNode.type.substitute(rootNode.mfvc.typeParameters, typeParameters.map { it.defaultType })
        body = context.createJvmIrBuilder(this.symbol).run {
            irExprBody(irBlock {
                for (callee in listOf(rootNode.primaryConstructorImpl, rootNode.boxMethod)) {
                    +irCall(callee).apply {
                        passTypeArgumentsFrom(this@function)
                        for ((index, parameter) in this@function.valueParameters.withIndex()) {
                            putValueArgument(index, irGet(parameter))
                        }
                    }
                }
            })
        }
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        val function = expression.symbol.owner
        val replacement = replacements.getReplacementFunction(function)
        val currentScope = currentScope!!.irElement as IrDeclaration
        return when {
            function is IrConstructor && function.isPrimary && function.constructedClass.isMultiFieldValueClass &&
                    currentScope.origin != JvmLoweredDeclarationOrigin.SYNTHETIC_MULTI_FIELD_VALUE_CLASS_MEMBER -> {
                context.createIrBuilder(currentScope.symbol).irBlock {
                    val rootNode = replacements.getRootMfvcNode(function.constructedClass)
                    val instance = rootNode.createInstanceFromValueDeclarationsAndBoxType(
                        this, function.constructedClassType as IrSimpleType, Name.identifier("constructor_tmp"), ::variablesSaver
                    )
                    flattenExpressionTo(expression, instance)
                    val getterExpression = instance.makeGetterExpression(this, ::registerPossibleExtraBoxUsage)
                    valueDeclarationsRemapper.registerReplacement(getterExpression, instance)
                    +getterExpression
                }
            }

            replacement != null -> context.createIrBuilder(currentScope.symbol).irBlock {
                buildReplacement(function, expression, replacement)
            }.unwrapBlock()

            else -> {
                val newConstructor = (function as? IrConstructor)
                    ?.let { replacements.getReplacementForRegularClassConstructor(it) }
                    ?: return super.visitFunctionAccess(expression)
                context.createIrBuilder(currentScope.symbol).irBlock {
                    buildReplacement(function, expression, newConstructor)
                }.unwrapBlock()
            }
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        val property = callee.property
        if (
            property != null &&
            callee.extensionReceiverParameter == null &&
            callee.contextReceiverParametersCount == 0 &&
            callee.isGetter &&
            (expression.type.needsMfvcFlattening() || expression.dispatchReceiver?.type?.needsMfvcFlattening() == true)
        ) {
            require(callee.valueParameters.isEmpty()) { "Unexpected getter:\n${callee.dump()}" }
            expression.dispatchReceiver = expression.dispatchReceiver?.transform(this, null)
            return context.createIrBuilder(getCurrentScopeSymbol()).irBlock {
                with(valueDeclarationsRemapper) {
                    addReplacement(expression) ?: return expression
                }
            }.unwrapBlock()
        }
        if (expression.isSpecializedMFVCEqEq) {
            return context.createIrBuilder(getCurrentScopeSymbol()).irBlock {
                val leftArgument = expression.getValueArgument(0)!!
                val rightArgument = expression.getValueArgument(1)!!
                val leftClass = leftArgument.type.erasedUpperBound
                val leftNode = if (leftArgument.type.needsMfvcFlattening()) replacements.getRootMfvcNodeOrNull(leftClass) else null
                val rightClass = rightArgument.type.erasedUpperBound
                val rightNode = if (rightArgument.type.needsMfvcFlattening()) replacements.getRootMfvcNodeOrNull(rightClass) else null
                if (leftNode != null) {
                    val newEquals = if (rightNode != null) {
                        require(leftNode == rightNode) { "Different nodes: $leftNode, $rightNode" }
                        // both are unboxed
                        leftNode.specializedEqualsMethod
                    } else {
                        // left one is unboxed, right is not
                        leftClass.functions.single { it.isEquals() }
                    }
                    +irCall(newEquals).apply {
                        dispatchReceiver = leftArgument
                        putValueArgument(0, rightArgument)
                    }.transform(this@JvmMultiFieldValueClassLowering, null)
                } else if (rightNode != null) {
                    // left one is boxed, right one is unboxed
                    if (leftArgument.isNullConst()) {
                        // left argument is always null, right one is unboxed
                        val hasPureFlattenedGetters = rightNode.mapLeaves { it.hasPureUnboxMethod }.all { it }
                        if (hasPureFlattenedGetters) {
                            val rightExpressions = flattenExpression(rightArgument)
                            require((rightExpressions.size > 1) == rightArgument.type.needsMfvcFlattening()) {
                                "Illegal flattening of ${rightArgument.dump()}\n\n${rightExpressions.joinToString("\n") { it.dump() }}"
                            }
                            rightExpressions.filterNot { it.isRepeatableGetter() }.forEach { +it }
                        } else {
                            +rightArgument.transform(this@JvmMultiFieldValueClassLowering, null)
                        }
                        +irFalse()
                    } else if (leftArgument.type.erasedUpperBound == rightArgument.type.erasedUpperBound && leftArgument.type.isNullable()) {
                        // left argument can be unboxed if it is not null, right one is unboxed
                        +irBlock {
                            val leftValue = irTemporary(leftArgument)
                            +irIfNull(context.irBuiltIns.booleanType, irGet(leftValue), irFalse(), irBlock {
                                val nonNullLeftArgumentVariable =
                                    irTemporary(irImplicitCast(irGet(leftValue), leftArgument.type.makeNotNull()))
                                +irCall(context.irBuiltIns.eqeqSymbol).apply {
                                    copyTypeArgumentsFrom(expression)
                                    putValueArgument(0, irGet(nonNullLeftArgumentVariable))
                                    putValueArgument(1, rightArgument)
                                }
                            })
                        }.transform(this@JvmMultiFieldValueClassLowering, null)
                    } else {
                        // right one is unboxed but left one is boxed and no intrinsics can be used
                        return super.visitCall(expression)
                    }
                } else {
                    // both are boxed
                    return super.visitCall(expression)
                }
            }.unwrapBlock()
        }
        return super.visitCall(expression)
    }

    private fun IrBlockBuilder.buildReplacement(
        originalFunction: IrFunction,
        original: IrMemberAccessExpression<*>,
        replacement: IrFunction,
        makeMemberAccessExpression: (IrFunctionSymbol) -> IrMemberAccessExpression<*> = ::irCall,
    ): IrMemberAccessExpression<*> {
        val parameter2expression = typedArgumentList(originalFunction, original)
        val structure = replacements.bindingOldFunctionToParameterTemplateStructure[originalFunction]!!
        require(parameter2expression.size == structure.size)
        require(structure.sumOf { it.valueParameters.size } == replacement.explicitParametersCount)
        val newArguments: List<IrExpression?> =
            makeNewArguments(parameter2expression.map { (_, argument) -> argument }, structure.map { it.valueParameters })
        val resultExpression = makeMemberAccessExpression(replacement.symbol).apply {
            passTypeArgumentsWithOffsets(replacement, originalFunction) { original.getTypeArgument(it)!! }
            for ((parameter, argument) in replacement.explicitParameters zip newArguments) {
                if (argument == null) continue
                putArgument(replacement, parameter, argument.transform(this@JvmMultiFieldValueClassLowering, null))
            }
        }
        +resultExpression
        return resultExpression
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation): IrExpression {
        for (i in expression.arguments.indices) {
            val argument = expression.arguments[i]
            if (argument.type.needsMfvcFlattening()) {
                expression.arguments[i] = context.createIrBuilder(getCurrentScopeSymbol()).run {
                    val toString = argument.type.erasedUpperBound.functions.single { it.isToString() }
                    require(toString.typeParameters.isEmpty()) { "Bad toString: ${toString.render()}" }
                    irCall(toString).apply {
                        dispatchReceiver = argument
                    }
                }
            }
        }
        return super.visitStringConcatenation(expression)
    }

    private fun IrBlockBuilder.makeNewArguments(
        oldArguments: List<IrExpression?>,
        structure: List<List<IrValueParameter>>
    ): List<IrExpression?> {
        val argumentSizes: List<Int> = structure.map { argTemplate -> argTemplate.size }
        val newArguments = (oldArguments zip argumentSizes).flatMap { (oldArgument, parametersCount) ->
            when {
                oldArgument == null -> List(parametersCount) { null }
                parametersCount == 1 -> listOf(oldArgument.transform(this@JvmMultiFieldValueClassLowering, null))
                else -> {
                    val castedIfNeeded = when {
                        oldArgument.type.needsMfvcFlattening() -> oldArgument
                        oldArgument.type.makeNotNull().needsMfvcFlattening() -> irImplicitCast(oldArgument, oldArgument.type.makeNotNull())
                        else -> error("Unexpected type: ${oldArgument.type.render()}")
                    }
                    flattenExpression(castedIfNeeded).also {
                        require(it.size == parametersCount) { "Expected $parametersCount arguments but got ${it.size}" }
                    }
                }
            }
        }
        return newArguments
    }

    /**
     * Inlines initialization of variables when possible and returns their values
     *
     * Example:
     * Before:
     * val a = 2
     * val b = 3
     * val c = b + 1
     * [a, b, c]
     *
     * After:
     * val a = 2
     * val b = 3
     * [a, b, b + 1]
     */
    fun IrBuilderWithScope.removeExtraSetVariablesFromExpressionList(
        block: IrContainerExpression,
        variables: List<IrVariable>
    ): List<IrExpression> {
        val forbiddenVariables = mutableSetOf<IrVariable>()
        val variablesSet = variables.toSet()
        val standaloneExpressions = mutableListOf<IrExpression>()
        val resultVariables = variables.toMutableList()

        fun recur(block: IrContainerExpression): Boolean /* stop optimization */ {
            while (block.statements.isNotEmpty() && resultVariables.isNotEmpty()) {
                val statement = block.statements.last()
                //also stop
                when {
                    statement is IrContainerExpression -> if (recur(statement)) {
                        if (statement.statements.isEmpty()) {
                            block.statements.removeLast()
                        }
                        return true
                    } else {
                        require(statement.statements.isEmpty() || resultVariables.isEmpty()) { "Not all statements removed" }
                        if (statement.statements.isEmpty()) {
                            block.statements.removeLast()
                        }
                    }

                    statement !is IrSetValue -> return true
                    statement.symbol.owner != resultVariables.last() -> return true
                    statement.symbol.owner in forbiddenVariables -> return true
                    else -> {
                        standaloneExpressions.add(statement.value)
                        resultVariables.removeLast()
                        block.statements.removeLast()
                        statement.value.acceptVoid(object : IrElementVisitorVoid {
                            override fun visitElement(element: IrElement) {
                                element.acceptChildrenVoid(this)
                            }

                            override fun visitValueAccess(expression: IrValueAccessExpression) {
                                val valueDeclaration = expression.symbol.owner
                                if (valueDeclaration is IrVariable && valueDeclaration in variablesSet) {
                                    forbiddenVariables.add(valueDeclaration)
                                }
                                super.visitValueAccess(expression)
                            }
                        })
                    }
                }
            }
            return false
        }
        recur(block)
        return resultVariables.map { irGet(it) } + standaloneExpressions.asReversed()
    }

    // Note that reference equality (x === y) is not allowed on values of MFVC class type,
    // so it is enough to check for eqeq.
    private val IrCall.isSpecializedMFVCEqEq: Boolean
        get() = symbol == context.irBuiltIns.eqeqSymbol &&
                listOf(getValueArgument(0), getValueArgument(1))
                    .any { it!!.type.erasedUpperBound.isMultiFieldValueClass }

    override fun visitGetField(expression: IrGetField): IrExpression {
        expression.receiver = expression.receiver?.transform(this, null)
        with(valueDeclarationsRemapper) {
            return context.createIrBuilder(expression.symbol).irBlock {
                addReplacement(expression) ?: return expression
            }.unwrapBlock()
        }
    }

    override fun visitSetField(expression: IrSetField): IrExpression {
        expression.receiver = expression.receiver?.transform(this, null)
        with(valueDeclarationsRemapper) {
            return context.createIrBuilder(getCurrentScopeSymbol()).irBlock {
                addReplacement(expression, safe = expression.origin != UNSAFE_MFVC_SET_ORIGIN)
                    ?: return expression.also { it.value = it.value.transform(this@JvmMultiFieldValueClassLowering, null) }
            }.unwrapBlock()
        }
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression =
        with(valueDeclarationsRemapper) {
            context.createIrBuilder(getCurrentScopeSymbol()).makeReplacement(expression) ?: super.visitGetValue(expression)
        }

    override fun visitSetValue(expression: IrSetValue): IrExpression = context.createIrBuilder(getCurrentScopeSymbol()).irBlock {
        with(valueDeclarationsRemapper) {
            addReplacement(expression, safe = expression.origin != UNSAFE_MFVC_SET_ORIGIN)
                ?: return super.visitSetValue(expression)
        }
    }.unwrapBlock()

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val initializer = declaration.initializer
        if (declaration.type.needsMfvcFlattening()) {
            val irClass = declaration.type.erasedUpperBound
            val rootNode = replacements.getRootMfvcNode(irClass)
            return context.createIrBuilder(getCurrentScopeSymbol()).irBlock {
                val instance = rootNode.createInstanceFromValueDeclarationsAndBoxType(
                    this, declaration.type as IrSimpleType, declaration.name, ::variablesSaver
                )
                valueDeclarationsRemapper.registerReplacement(declaration, instance)
                initializer?.let {
                    flattenExpressionTo(it, instance)
                }
            }.unwrapBlock()
        }
        return super.visitVariable(declaration)
    }

    private fun getCurrentScopeSymbol() = (currentScope!!.irElement as IrSymbolOwner).symbol

    /**
     * Takes not transformed expression and returns its flattened transformed representation (expressions)
     */
    fun IrBlockBuilder.flattenExpression(expression: IrExpression): List<IrExpression> {
        if (!expression.type.needsMfvcFlattening()) {
            return listOf(expression.transform(this@JvmMultiFieldValueClassLowering, null))
        }
        val rootMfvcNode = replacements.getRootMfvcNode(expression.type.erasedUpperBound)
        val typeArguments = makeTypeArgumentsFromType(expression.type as IrSimpleType)
        val variables = rootMfvcNode.leaves.map {
            savableStandaloneVariable(
                type = it.type.substitute(typeArguments),
                origin = IrDeclarationOrigin.MULTI_FIELD_VALUE_CLASS_REPRESENTATION_VARIABLE,
                saveVariable = ::variablesSaver
            )
        }
        val instance = ValueDeclarationMfvcNodeInstance(rootMfvcNode, typeArguments, variables)
        val block = irBlock {
            flattenExpressionTo(expression, instance)
        }
        val expressions = removeExtraSetVariablesFromExpressionList(block, variables)
        if (block.statements.isNotEmpty()) {
            +block.unwrapBlock()
        }
        return expressions
    }

    /**
     * Takes not transformed expression and initialized given MfvcNodeInstance with transformed version of it
     */
    fun IrBlockBuilder.flattenExpressionTo(expression: IrExpression, instance: MfvcNodeInstance) {
        val rootNode = replacements.getRootMfvcNodeOrNull(
            if (expression is IrConstructorCall) expression.symbol.owner.constructedClass else expression.type.erasedUpperBound
        )
        val type = if (expression is IrConstructorCall) expression.symbol.owner.constructedClass.defaultType else expression.type
        val lowering = this@JvmMultiFieldValueClassLowering
        if (rootNode == null || !type.needsMfvcFlattening()) {
            require(instance.size == 1) { "Required 1 variable/field to store regular value but got ${instance.size}" }
            instance.addSetterStatements(this, listOf(expression.transform(lowering, null)))
            return
        }
        require(rootNode.leavesCount == instance.size) {
            "Required ${rootNode.leavesCount} variable/field to store regular value but got ${instance.size}"
        }
        if (expression is IrWhen) {
            for (branch in expression.branches) {
                branch.condition = branch.condition.transform(lowering, null)
                branch.result = irBlock {
                    flattenExpressionTo(branch.result, instance)
                }.unwrapBlock()
            }
            +expression
            return
        }
        if (expression is IrTry) {
            expression.tryResult = irBlock { flattenExpressionTo(expression.tryResult, instance) }.unwrapBlock()
            expression.catches.replaceAll {
                irCatch(
                    it.catchParameter,
                    irBlock { flattenExpressionTo(it.result, instance) }.unwrapBlock()
                )
            }
            expression.finallyExpression = expression.finallyExpression?.transform(lowering, null)
            +expression
            return
        }
        if (expression is IrConstructorCall) {
            val constructor = expression.symbol.owner
            if (constructor.isPrimary && constructor.constructedClass.isMultiFieldValueClass &&
                constructor.origin != JvmLoweredDeclarationOrigin.STATIC_MULTI_FIELD_VALUE_CLASS_CONSTRUCTOR
            ) {
                val oldArguments = List(expression.valueArgumentsCount) {
                    expression.getValueArgument(it) ?: error("Default arguments for MFVC primary constructors are not yet supported")
                }
                require(rootNode.subnodes.size == oldArguments.size) {
                    "Old ${constructor.render()} must have ${rootNode.subnodes.size} arguments but got ${oldArguments.size}"
                }
                for ((subnode, argument) in rootNode.subnodes zip oldArguments) {
                    flattenExpressionTo(argument, instance[subnode.name]!!)
                }
                +irCall(rootNode.primaryConstructorImpl).apply {
                    copyTypeArgumentsFrom(expression)
                    val flattenedGetterExpressions =
                        instance.makeFlattenedGetterExpressions(this@flattenExpressionTo, ::registerPossibleExtraBoxUsage)
                    for ((index, leafExpression) in flattenedGetterExpressions.withIndex()) {
                        putValueArgument(index, leafExpression)
                    }
                }
                return
            }
        }
        val transformedExpression = expression.transform(this@JvmMultiFieldValueClassLowering, null)
        val addedSettersToFlattened = valueDeclarationsRemapper.handleFlattenedGetterExpressions(this, transformedExpression) {
            require(it.size == instance.size) { "Incompatible assignment sizes: ${it.size}, ${instance.size}" }
            instance.makeSetterExpressions(this, it)
        }
        if (addedSettersToFlattened != null) {
            +addedSettersToFlattened
            return
        }
        val expressionInstance = rootNode.createInstanceFromBox(
            this, transformedExpression, getOptimizedPublicAccess(rootNode.mfvc), ::variablesSaver,
        )
        require(expressionInstance.size == instance.size) { "Incompatible assignment sizes: ${expressionInstance.size}, ${instance.size}" }
        instance.addSetterStatements(this, expressionInstance.makeFlattenedGetterExpressions(this, ::registerPossibleExtraBoxUsage))
    }

    private fun getOptimizedPublicAccess(parent: IrClass): AccessType =
        currentScope?.irElement?.let { getOptimizedPublicAccess(it, parent) } ?: AccessType.AlwaysPublic

    /**
     * Removes boxing when the result is not used
     */
    fun IrBody.removeAllExtraBoxes() {
        // data is whether the expression result is used
        accept(object : IrElementVisitor<Unit, Boolean> {
            override fun visitElement(element: IrElement, data: Boolean) {
                element.acceptChildren(this, true) // uses what is inside
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall, data: Boolean) {
                expression.acceptChildren(this, data) // type operator calls are transparent
            }

            private tailrec fun getFunctionCallOrNull(statement: IrStatement): IrCall? = when (statement) {
                is IrTypeOperatorCall -> getFunctionCallOrNull(statement.argument)
                is IrCall -> statement
                else -> null
            }

            // inner functions will be handled separately, no need to do it now
            override fun visitFunction(declaration: IrFunction, data: Boolean) = Unit

            // inner classes will be handled separately, no need to do it now
            override fun visitClass(declaration: IrClass, data: Boolean) = Unit

            override fun visitContainerExpression(expression: IrContainerExpression, data: Boolean) {
                handleStatementContainer(expression, data)
            }

            override fun visitWhen(expression: IrWhen, data: Boolean) {
                expression.acceptChildren(this, data) // when's are transparent
            }

            override fun visitCatch(aCatch: IrCatch, data: Boolean) {
                aCatch.acceptChildren(this, data) // catches are transparent
            }

            override fun visitTry(aTry: IrTry, data: Boolean) {
                aTry.tryResult.accept(this, data)
                aTry.catches.forEach { it.accept(this, data) }
                aTry.finallyExpression?.accept(this, false)
            }

            override fun visitBranch(branch: IrBranch, data: Boolean) {
                branch.condition.accept(this, true)
                branch.result.accept(this, data)
            }

            override fun visitBlockBody(body: IrBlockBody, data: Boolean) {
                handleStatementContainer(body, data)
            }

            private fun handleStatementContainer(expression: IrStatementContainer, resultIsUsed: Boolean) {
                for (statement in expression.statements.subListWithoutLast(1)) {
                    statement.accept(this, false)
                }
                expression.statements.lastOrNull()?.accept(this, resultIsUsed)
                val statementsToRemove = mutableSetOf<IrStatement>()
                for (statement in expression.statements.subListWithoutLast(if (resultIsUsed) 1 else 0)) {
                    val call = getFunctionCallOrNull(statement) ?: continue
                    val node = replacements.getRootMfvcNodeOrNull(call.type.erasedUpperBound) ?: continue
                    if (node.boxMethod == call.symbol.owner &&
                        List(call.valueArgumentsCount) { call.getValueArgument(it) }.all { it.isRepeatableGetter() }
                    ) {
                        statementsToRemove.add(statement)
                    }
                }
                expression.statements.removeIf { it in statementsToRemove }
            }
        }, false)
    }
}

// like dropLast but creates a view to the original list instead of copying content of it
private fun <T> List<T>.subListWithoutLast(n: Int) = if (size > n) subList(0, size - n) else emptyList()

private sealed class BlockOrBody {
    abstract val element: IrElement

    data class Body(val body: IrBody) : BlockOrBody() {
        override val element get() = body
    }

    data class Block(val block: IrBlock) : BlockOrBody() {
        override val element get() = block
    }
}

/**
 * Finds the most narrow block or body which contains all usages of each of the given variables
 */
private fun findNearestBlocksForVariables(variables: Set<IrVariable>, body: BlockOrBody): Map<IrVariable, BlockOrBody?> {
    if (variables.isEmpty()) return mapOf()
    val variableUsages = mutableMapOf<BlockOrBody, MutableSet<IrVariable>>()
    val childrenBlocks = mutableMapOf<BlockOrBody, MutableList<BlockOrBody>>()

    body.element.acceptVoid(object : IrElementVisitorVoid {
        private val stack = mutableListOf<BlockOrBody>()
        override fun visitElement(element: IrElement) {
            element.acceptChildren(this, null)
        }

        override fun visitBody(body: IrBody) {
            currentStackElement()?.let { childrenBlocks.getOrPut(it) { mutableListOf() }.add(BlockOrBody.Body(body)) }
            stack.add(BlockOrBody.Body(body))
            super.visitBody(body)
            require(stack.removeLast() == BlockOrBody.Body(body)) { "Invalid stack" }
        }

        override fun visitBlock(expression: IrBlock) {
            currentStackElement()?.let { childrenBlocks.getOrPut(it) { mutableListOf() }.add(Block(expression)) }
            stack.add(Block(expression))
            super.visitBlock(expression)
            require(stack.removeLast() == Block(expression)) { "Invalid stack" }
        }

        private fun currentStackElement() = stack.lastOrNull()

        override fun visitValueAccess(expression: IrValueAccessExpression) {
            val valueDeclaration = expression.symbol.owner
            if (valueDeclaration is IrVariable && valueDeclaration in variables) {
                variableUsages.getOrPut(currentStackElement()!!) { mutableSetOf() }.add(valueDeclaration)
            }
            super.visitValueAccess(expression)
        }
    })

    fun dfs(currentBlock: BlockOrBody, variable: IrVariable): BlockOrBody? {
        if (variable in (variableUsages[currentBlock] ?: listOf())) return currentBlock
        val childrenResult = childrenBlocks[currentBlock]?.mapNotNull { dfs(it, variable) } ?: listOf()
        return when (childrenResult.size) {
            0 -> return null
            1 -> return childrenResult.single()
            else -> currentBlock
        }
    }

    return variables.associateWith { dfs(body, it) }
}

private fun IrStatement.containsUsagesOf(variablesSet: Set<IrVariable>): Boolean {
    var used = false
    acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (!used) {
                element.acceptChildrenVoid(this)
            }
        }

        override fun visitValueAccess(expression: IrValueAccessExpression) {
            if (expression.symbol.owner in variablesSet) {
                used = true
            }
            super.visitValueAccess(expression)
        }
    })
    return used
}

private fun IrBody.makeBodyWithAddedVariables(context: JvmBackendContext, variables: Set<IrVariable>, symbol: IrSymbol) =
    BlockOrBody.Body(this).makeBodyWithAddedVariables(context, variables, symbol) as IrBody

/**
 * Adds declarations of the variables to the most narrow possible block or body.
 * It adds them before the first usage within the block and inlines initialization of them when possible.
 */
private fun BlockOrBody.makeBodyWithAddedVariables(context: JvmBackendContext, variables: Set<IrVariable>, symbol: IrSymbol): IrElement {
    if (variables.isEmpty()) return element
    val nearestBlocks = findNearestBlocksForVariables(variables, this)
    val containingVariables: Map<BlockOrBody, List<IrVariable>> = nearestBlocks.entries
        .mapNotNull { (k, v) -> if (v != null) k to v else null }
        .groupBy({ (_, v) -> v }, { (k, _) -> k })
    return element.transform(object : IrElementTransformerVoid() {
        private fun getFlattenedStatements(container: IrStatementContainer): Sequence<IrStatement> = sequence {
            for (statement in container.statements) {
                if (statement is IrStatementContainer) {
                    yieldAll(getFlattenedStatements(statement))
                } else {
                    yield(statement)
                }
            }
        }

        private fun removeFlattenedStatements(container: IrStatementContainer, toRemove: Int): Int {
            var removed = 0
            var removedDirectly = 0
            for (statement in container.statements) {
                require(removed <= toRemove) { "Removed: $removed, To remove: $toRemove" }
                if (removed == toRemove) break
                if (statement is IrStatementContainer) {
                    val nestedRemoved = removeFlattenedStatements(statement, toRemove - removed)
                    removed += nestedRemoved
                    if (statement.statements.isEmpty()) {
                        removedDirectly++
                    }
                } else {
                    removed++
                    removedDirectly++
                }
            }
            require(removed <= toRemove) { "Removed: $removed, To remove: $toRemove" }
            if (removedDirectly > 0) container.statements.replaceAll(container.statements.drop(removedDirectly))
            return removed
        }

        private fun replaceSetVariableWithInitialization(variables: List<IrVariable>, container: IrStatementContainer) {
            val variablesSet = variables.toSet()
            val statementsWithoutUsages = container.statements.takeWhile { !it.containsUsagesOf(variablesSet) }
            container.statements.replaceAll(container.statements.drop(statementsWithoutUsages.size))
            val values = buildList {
                for ((variable, statement) in variables.asSequence() zip getFlattenedStatements(container)) {
                    when {
                        variable.initializer != null -> break
                        statement !is IrSetValue -> break
                        statement.symbol.owner != variable -> break
                        else -> add(statement.value)
                    }
                }
            }

            for ((variable, value) in variables zip values) {
                variable.initializer = value
            }
            removeFlattenedStatements(container, values.size)
            container.statements.addAll(0, statementsWithoutUsages + variables)
        }

        override fun visitBlock(expression: IrBlock): IrExpression {
            containingVariables[Block(expression)]?.let {
                expression.transformChildrenVoid()
                replaceSetVariableWithInitialization(it, expression)
                return expression
            }
            return super.visitBlock(expression)
        }

        override fun visitBlockBody(body: IrBlockBody): IrBody {
            containingVariables[BlockOrBody.Body(body)]?.let {
                body.transformChildrenVoid()
                replaceSetVariableWithInitialization(it, body)
                return body
            }
            return super.visitBlockBody(body)
        }

        override fun visitExpressionBody(body: IrExpressionBody): IrBody {
            val lowering = this
            containingVariables[BlockOrBody.Body(body)]?.takeIf { it.isNotEmpty() }?.let { bodyVars ->
                return with(context.createJvmIrBuilder(symbol)) {
                    val blockBody = irBlock { +body.expression.transform(lowering, null) }
                    replaceSetVariableWithInitialization(bodyVars, blockBody)
                    irExprBody(blockBody)
                }
            }
            return super.visitExpressionBody(body)
        }
    }, null)
}

private fun <T> MutableList<T>.replaceAll(replacement: List<T>) {
    clear()
    addAll(replacement)
}
