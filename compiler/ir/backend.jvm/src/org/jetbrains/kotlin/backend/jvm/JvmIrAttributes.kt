/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.ir.declarations.IrAttributeContainer
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.irFlag
import org.jetbrains.kotlin.ir.symbols.IrLocalDelegatedPropertySymbol
import org.jetbrains.org.objectweb.asm.Type

var IrAttributeContainer.localClassType: Type? by irAttribute(followAttributeOwner = true)

var IrFunction.enclosingMethodOverride: IrFunction? by irAttribute(followAttributeOwner = false)