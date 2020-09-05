package arrow.meta.plugins.proofs.phases.ir

import arrow.meta.log.Log
import arrow.meta.log.invoke
import arrow.meta.phases.CompilerContext
import arrow.meta.phases.codegen.ir.IrUtils
import arrow.meta.phases.codegen.ir.dfsCalls
import arrow.meta.phases.codegen.ir.substitutedValueParameters
import arrow.meta.phases.codegen.ir.transformMe
import arrow.meta.phases.codegen.ir.transformer
import arrow.meta.phases.codegen.ir.unsubstitutedDescriptor
import arrow.meta.phases.resolve.baseLineTypeChecker
import arrow.meta.phases.resolve.typeArgumentsMap
import arrow.meta.phases.resolve.unwrappedNotNullableType
import arrow.meta.plugins.proofs.phases.ExtensionProof
import arrow.meta.plugins.proofs.phases.GivenProof
import arrow.meta.plugins.proofs.phases.Proof
import arrow.meta.plugins.proofs.phases.RefinementProof
import arrow.meta.plugins.proofs.phases.extensionProofs
import arrow.meta.plugins.proofs.phases.givenProofs
import arrow.meta.plugins.proofs.phases.resolve.GivenUpperBound
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.getValueArgument
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.mapValueParametersIndexed
import org.jetbrains.kotlin.ir.expressions.putValueArgument
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.resolve.calls.inference.components.NewTypeSubstitutorByConstructorMap
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter

@Suppress("RedundantUnitReturnType")
class ProofsIrCodegen(
  val irUtils: IrUtils
) {

  fun IrUtils.matchedCandidateProofCall(
    fn: CallableDescriptor,
    typeSubstitutor: NewTypeSubstitutorByConstructorMap
  ): IrExpression {
    val irTypes = fn.substitutedIrTypes(typeSubstitutor).filterNotNull()
    return fn.irCall().apply {
      if (this is IrMemberAccessExpression) {
        fn.typeParameters.forEachIndexed { n, descriptor ->
          //TODO determine why sometimes type susbtitution returns unbound type args. Ex: fun <A> SecondN<FirstN<A>>.flatten(): Second<A>
          putTypeArgument(n, irTypes.getOrElse(n) { pluginContext.irBuiltIns.nothingType })
        }
      }
    }
  }

  fun CompilerContext.givenProofCall(
    superType: KotlinType
  ): IrExpression? =
    irUtils.run {
      val matchingCandidates = givenProofs(superType)
      val proofs = matchingCandidates.map { proof ->
        substitutedProofCall(proof, superType)
      }
      proofs.firstOrNull() //TODO handle ambiguity and orphan selection
    }

  private fun IrUtils.substitutedProofCall(proof: GivenProof, superType: KotlinType): IrExpression? =
    matchedCandidateProofCall(
      fn = proof.callableDescriptor,
      typeSubstitutor = proof.substitutor(superType)
    )


  fun CompilerContext.extensionProofCall(
    subType: KotlinType,
    superType: KotlinType
  ): IrExpression? =
    irUtils.run {
      val matchingCandidates = extensionProofs(subType, superType)
      val proofs = matchingCandidates.map {
        matchedCandidateProofCall(
          fn = it.through,
          typeSubstitutor = it.substitutor(subType)
        )
      }
      proofs.firstOrNull() //TODO handle ambiguity and orphan selection
    }

  fun Proof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    fold(
      given = { substitutor(superType) },
      coercion = { substitutor(superType) },
      projection = { substitutor(superType) },
      refinement = { substitutor(superType) }
    )

  fun RefinementProof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    ProofCandidate(
      proofType = to,
      otherType = superType.unwrappedNotNullableType,
      through = through
    ).typeSubstitutor

  fun ExtensionProof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    ProofCandidate(
      proofType = from,
      otherType = superType.unwrappedNotNullableType,
      through = through
    ).typeSubstitutor

  fun GivenProof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    ProofCandidate(
      proofType = to,
      otherType = superType.unwrappedNotNullableType,
      through = through
    ).typeSubstitutor


  fun CompilerContext.proveVariable(it: IrVariable): IrVariable? {
    val targetType = it.type.originalKotlinType
    val valueType = it.initializer?.type?.originalKotlinType
    return if (targetType != null && valueType != null) {
      it.apply {
        val proofCall = extensionProofCall(valueType, targetType)
        if (proofCall is IrMemberAccessExpression) {
          proofCall.extensionReceiver = initializer
        }
        proofCall?.also {
          initializer = it
        }
      }
    } else it
  }

  fun CompilerContext.proveNestedCalls(expression: IrCall): IrCall? =
    expression.apply {
      dfsCalls().forEach {
        proveCall(it)
      }
    }

  private fun CompilerContext.proveCall(expression: IrCall): IrCall =
    Log.Verbose({ "insertProof:\n ${expression.dump()} \nresult\n ${this.dump()}" }) {
      val givenTypeParamUpperBound = GivenUpperBound(expression)
      val upperBound = givenTypeParamUpperBound.givenUpperBound
      if (upperBound != null) insertGivenCall(givenTypeParamUpperBound, expression)
      else insertExtensionSyntaxCall(expression)
    }

  private fun CompilerContext.insertExtensionSyntaxCall(expression: IrCall): IrCall {
    val valueType = expression.dispatchReceiver?.type?.toKotlinType()
      ?: expression.extensionReceiver?.type?.toKotlinType()
      ?: (if (expression.valueArgumentsCount > 0) expression.getValueArgument(0)?.type?.toKotlinType() else null)
    val targetType =
      (expression.unsubstitutedDescriptor.dispatchReceiverParameter?.containingDeclaration as? FunctionDescriptor)?.dispatchReceiverParameter?.type
        ?: expression.unsubstitutedDescriptor.extensionReceiverParameter?.type
        ?: expression.substitutedValueParameters.firstOrNull()?.second
    if (targetType != null && valueType != null && targetType != valueType && !baseLineTypeChecker.isSubtypeOf(valueType, targetType)) {
      return expression.run {
        val proofCall = extensionProofCall(valueType, targetType)
        if (proofCall is IrMemberAccessExpression) {
          when {
            dispatchReceiver != null -> {
              proofCall.extensionReceiver = dispatchReceiver
              dispatchReceiver = proofCall
            }
            extensionReceiver != null -> {
              proofCall.extensionReceiver = extensionReceiver
              dispatchReceiver = null
              extensionReceiver = proofCall
            }
            (valueType != targetType && expression.valueArgumentsCount > 0) -> {
              dispatchReceiver = null

              expression.mapValueParametersIndexed { n: Int, _ ->
                val valueArgument = expression.getValueArgument(n)
                val valueType2 = valueArgument?.type?.toKotlinType()!!
                val targetType2 = expression.substitutedValueParameters[n].second
                val proofCall2 = extensionProofCall(valueType2, targetType2) as? IrMemberAccessExpression
                if (proofCall2 != null) {
                  proofCall2.extensionReceiver = valueArgument
                  if (proofCall2.typeArgumentsCount > 0) {
                    proofCall2.putTypeArgument(0, irUtils.typeTranslator.translateType(valueType))
                  }
                  proofCall2
                } else {
                  valueArgument
                }
              }
            }
          }
          fixProofExpression(expression, proofCall)
        } else {
          expression
        }
      }
    }
    return expression
  }

  /**
   * check ir/builders/declarationBuilders.kt#buildFun for reference
   */
  fun fixProofExpression(call: IrCall, access: IrMemberAccessExpression): IrCall =
    irUtils.run {
      val f = call.unsubstitutedDescriptor
      val wr = WrappedSimpleFunctionDescriptor(f.annotations, f.source)
      val fsymbol = pluginContext.symbolTable.referenceSimpleFunction(f)
      val g = IrFunctionImpl(
        startOffset = fsymbol.owner.startOffset,
        endOffset = fsymbol.owner.endOffset,
        origin = fsymbol.owner.origin,
        symbol = IrSimpleFunctionSymbolImpl(wr),
        name = f.name,
        visibility = f.visibility,
        modality = f.modality,
        returnType = call.type,
        isInline = f.isInline,
        isExternal = f.isExternal,
        isTailrec = f.isTailrec,
        isSuspend = f.isSuspend,
        isExpect = f.isExpect,
        isOperator = f.isOperator,
      )
      wr.bind(g)
      //val dss = call.transform(transformer(), null)
      val s = call.transformMe(g, call.superQualifierSymbol)
      //s.transform(transformer(), null)
      return s
    }

  private fun CompilerContext.insertGivenCall(
    givenUpperBound: GivenUpperBound,
    expression: IrCall
  ): IrCall {
    val upperBound = givenUpperBound.givenUpperBound
    if (upperBound != null) {
      givenUpperBound.givenValueParameters.forEach { (descriptor, superType) ->
        givenProofCall(superType)?.apply {
          if (expression.getValueArgument(descriptor) == null)
            expression.putValueArgument(descriptor, this)
        }
      }
    }
    return expression
  }

  fun CompilerContext.proveProperty(it: IrProperty): IrProperty? {
    val targetType = it.getter?.returnType?.originalKotlinType
    val valueType = it.backingField?.initializer?.expression?.type?.originalKotlinType
    return if (targetType != null && valueType != null && targetType != valueType) {
      it.backingField?.let { field ->
        val replacement = field.initializer?.expression?.let {
          extensionProofCall(valueType, targetType)?.apply {
            if (this is IrMemberAccessExpression)
              extensionReceiver = it
          }
        }
        replacement?.let { field.initializer?.expression = it }
        it
      }
    } else it
  }

  fun CompilerContext.proveReturn(it: IrReturn): IrReturn? {
    val targetType = it.returnTarget.returnType
    val valueType = it.value.type.originalKotlinType
    return if (targetType != null && valueType != null && targetType != valueType) {
      extensionProofCall(valueType, targetType)?.let { call ->
        if (call is IrMemberAccessExpression)
          call.extensionReceiver = it.value

        IrReturnImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          irUtils.typeTranslator.translateType(targetType),
          it.returnTargetSymbol,
          call
        )
      } ?: it
    } else it
  }

  fun CompilerContext.proveTypeOperator(it: IrTypeOperatorCall): IrExpression? {
    val targetType = it.type.toKotlinType()
    val valueType = it.argument.type.toKotlinType()
    return if (targetType != valueType) {
      extensionProofCall(valueType, targetType)?.let { call ->
        if (call is IrMemberAccessExpression)
          call.extensionReceiver = it.argument
        call
      }
    } else it
  }

  companion object {
    operator fun <A> invoke(irUtils: IrUtils, f: ProofsIrCodegen.() -> A): A =
      f(ProofsIrCodegen(irUtils))
  }
}

val ProofCandidate.typeSubstitutor: NewTypeSubstitutorByConstructorMap
  get() {
    val allArgsMap =
      proofType.typeArgumentsMap(otherType)
        .filter { it.key.type.isTypeParameter() } +
        mapOf(
          through.module.builtIns.nothingType.asTypeProjection() to TypeUtils.DONT_CARE.asTypeProjection()
        )
    return NewTypeSubstitutorByConstructorMap(
      allArgsMap.map {
        it.key.type.constructor to it.value.type.unwrap()
      }.toMap()
    )
  }
