package arrow.meta.phases.codegen.ir

import arrow.meta.phases.CompilerContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ReceiverParameterDescriptorImpl
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.descriptors.WrappedReceiverParameterDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrValueAccessExpression
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.util.ConstantValueGenerator
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.TypeTranslator
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyValueArgumentsFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.resolve.calls.inference.components.NewTypeSubstitutorByConstructorMap
import org.jetbrains.kotlin.resolve.calls.util.FakeCallableDescriptorForObject
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.asSimpleType
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter

class IrUtils(
  val pluginContext: IrPluginContext,
  val compilerContext: CompilerContext
) : ReferenceSymbolTable by pluginContext.symbols.externalSymbolTable {

  val typeTranslator: TypeTranslator =
    TypeTranslator(
      symbolTable = pluginContext.symbols.externalSymbolTable,
      languageVersionSettings = pluginContext.languageVersionSettings,
      builtIns = pluginContext.builtIns
    ).apply translator@{
      constantValueGenerator =
        ConstantValueGenerator(
          moduleDescriptor = pluginContext.builtIns.builtInsModule.module,
          symbolTable = pluginContext.symbols.externalSymbolTable
        ).apply {
          this.typeTranslator = this@translator
        }
    }

  fun CallableDescriptor.irCall(): IrExpression =
    when (this) {
      is PropertyDescriptor -> {
        val irField = pluginContext.symbols.externalSymbolTable.referenceField(this)
        irField.owner.correspondingPropertySymbol?.owner?.getter?.symbol?.let { irSimpleFunctionSymbol ->
          IrCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = irSimpleFunctionSymbol.owner.returnType,
            symbol = irSimpleFunctionSymbol,
            typeArgumentsCount = irSimpleFunctionSymbol.owner.descriptor.typeParameters.size,
            valueArgumentsCount = irSimpleFunctionSymbol.owner.descriptor.valueParameters.size
          )
        } ?: TODO("Unsupported irCall for $this")
      }
      is ClassConstructorDescriptor -> {
        val irSymbol = pluginContext.symbols.externalSymbolTable.referenceConstructor(this)
        IrConstructorCallImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = irSymbol.owner.returnType,
          symbol = irSymbol,
          typeArgumentsCount = irSymbol.owner.descriptor.typeParameters.size,
          valueArgumentsCount = irSymbol.owner.descriptor.valueParameters.size,
          constructorTypeArgumentsCount = irSymbol.owner.descriptor.typeParameters.size
        )
      }
      is FunctionDescriptor -> {
        val irSymbol = pluginContext.symbols.externalSymbolTable.referenceFunction(this)
        IrCallImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = irSymbol.owner.returnType,
          symbol = irSymbol,
          typeArgumentsCount = irSymbol.owner.descriptor.typeParameters.size,
          valueArgumentsCount = irSymbol.owner.descriptor.valueParameters.size
        )
      }
      is FakeCallableDescriptorForObject -> {
        val irSymbol = pluginContext.symbols.externalSymbolTable.referenceClass(classDescriptor)
        IrGetObjectValueImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = irSymbol.owner.defaultType,
          symbol = irSymbol
        )
      }
      else -> {
        TODO("Unsupported ir call for $this")
      }
    }

  fun PropertyDescriptor.irGetterCall(): IrCall? {
    val irField = pluginContext.symbols.externalSymbolTable.referenceField(this)
    return irField.owner.correspondingPropertySymbol?.owner?.getter?.symbol?.let { irSimpleFunctionSymbol ->
      IrCallImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = irSimpleFunctionSymbol.owner.returnType,
        symbol = irSimpleFunctionSymbol,
        typeArgumentsCount = irSimpleFunctionSymbol.owner.descriptor.typeParameters.size,
        valueArgumentsCount = irSimpleFunctionSymbol.owner.descriptor.valueParameters.size
      )
    }
  }

  fun ClassDescriptor.irConstructorCall(): IrConstructorCall? {
    val irClass = pluginContext.symbols.externalSymbolTable.referenceClass(this)
    return irClass.constructors.firstOrNull()?.let { irConstructorSymbol ->
      IrConstructorCallImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = irConstructorSymbol.owner.returnType,
        symbol = irConstructorSymbol,
        typeArgumentsCount = irConstructorSymbol.owner.descriptor.typeParameters.size,
        valueArgumentsCount = irConstructorSymbol.owner.descriptor.valueParameters.size,
        constructorTypeArgumentsCount = declaredTypeParameters.size
      )
    }
  }

  fun CallableDescriptor.substitutedIrTypes(typeSubstitutor: NewTypeSubstitutorByConstructorMap): List<IrType?> =
    typeParameters.mapIndexed { _, typeParamDescriptor ->
      val newType = typeSubstitutor.map.entries.find {
        it.key.toString() == typeParamDescriptor.defaultType.toString()
      }
      newType?.value?.let(typeTranslator::translateType)
    }
}

fun IrFunctionAccessExpression.transformMe(
  newFunction: IrFunction,
  newSuperQualifierSymbol: IrClassSymbol? = null,
  receiversAsArguments: Boolean = false,
  argumentsAsReceivers: Boolean = false,
): IrCall =
  IrCallImpl(
    startOffset,
    endOffset,
    type,
    newFunction.symbol,
    typeArgumentsCount,
    origin,
    newSuperQualifierSymbol
  ).apply {
    copyTypeArgumentsFrom(this@transformMe)
    copyValueArgumentsFrom(this@transformMe, this@transformMe.symbol.owner, newFunction, receiversAsArguments, argumentsAsReceivers)
  }

fun <A> transformer(): IrElementTransformer<A> =
  object : IrElementTransformer<A> {

    override fun visitFunction(declaration: IrFunction, data: A): IrStatement {
      val str = ir2string(declaration)
      return super.visitFunction(declaration, data)
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression, data: A): IrElement {
      val str = ir2string(expression)
      return super.visitFunctionAccess(expression, data)
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression, data: A): IrElement {
      val str = ir2string(expression)
      return super.visitFunctionExpression(expression, data)
    }

    override fun visitFunctionReference(expression: IrFunctionReference, data: A): IrElement {
      val str = ir2string(expression)
      return super.visitFunctionReference(expression, data)
    }

    override fun visitValueParameter(declaration: IrValueParameter, data: A): IrStatement {
      val str = ir2string(declaration)
      return super.visitValueParameter(declaration, data)
    }
  }

fun IrCall.dfsCalls(): List<IrCall> {
  val calls = arrayListOf<IrCall>()
  val recursiveVisitor = object : IrElementVisitor<Unit, Unit> {
    override fun visitElement(element: IrElement, data: Unit) {
      if (element is IrCall) {
        calls.addAll(element.dfsCalls())
      }
    }
  }
  acceptChildren(recursiveVisitor, Unit)
  calls.add(this)
  return calls
}

fun IrValueParameter.dfsCalls(): List<IrValueParameter> {
  val calls = arrayListOf<IrValueParameter>()
  val recursiveVisitor = object : IrElementVisitor<Unit, Unit> {
    override fun visitElement(element: IrElement, data: Unit) {
      if (element is IrValueParameter) {
        calls.addAll(element.dfsCalls())
      }
    }
  }
  acceptChildren(recursiveVisitor, Unit)
  calls.add(this)
  return calls
}

/**
 * returns the index and the value argument
 */
val IrCall.valueArguments: List<Pair<Int, IrExpression?>>
  get() {
    val args = arrayListOf<Pair<Int, IrExpression?>>()
    for (i in 0 until valueArgumentsCount) {
      args.add(i to getValueArgument(i))
    }
    return args.toList()
  }

/**
 * returns the index and the type argument
 */
val IrCall.typeArguments: List<Pair<Int, IrType?>>
  get() {
    val args = arrayListOf<Pair<Int, IrType?>>()
    for (i in 0 until typeArgumentsCount) {
      args.add(i to getTypeArgument(i))
    }
    return args.toList()
  }

val IrCall.unsubstitutedDescriptor: FunctionDescriptor
  get() = symbol.owner.descriptor

val IrCall.substitutedValueParameters: List<Pair<ValueParameterDescriptor, KotlinType>>
  get() = unsubstitutedDescriptor.substitutedValueParameters(this)

/**
 * returns a Pair of the descriptor and it's substituted KotlinType at the call-site
 */
fun CallableMemberDescriptor.substitutedValueParameters(call: IrCall): List<Pair<ValueParameterDescriptor, KotlinType>> =
  valueParameters.filterNotNull()
    .map {
      val type = it.type
      it to (type.takeIf { t -> !t.isTypeParameter() }
        ?: typeParameters.filterNotNull()
          .firstOrNull { typeParam -> typeParam.defaultType == type.asSimpleType() }
          ?.let { typeParam ->
            call.getTypeArgument(typeParam.index)?.originalKotlinType
          } ?: type // Could not resolve the substituted KotlinType
        )
    }

fun receiverParameter(
  value: ReceiverValue,
  descriptor: DeclarationDescriptor,
  annotations: Annotations = Annotations.EMPTY,
  sourceElement: SourceElement = SourceElement.NO_SOURCE
): WrappedReceiverParameterDescriptor =
  object : WrappedReceiverParameterDescriptor(annotations, sourceElement) {
    override fun getValue(): ReceiverValue =
      value

    override fun getContainingDeclaration(): DeclarationDescriptor =
      descriptor

    override fun substitute(substitutor: TypeSubstitutor): ReceiverParameterDescriptor =
      ReceiverParameterDescriptorImpl(descriptor, value, annotations).substitute(substitutor) as ReceiverParameterDescriptor
  }

fun simpleFunction(
  f: FunctionDescriptor
) =
  object : WrappedSimpleFunctionDescriptor(f) {
    override fun getContainingDeclaration(): DeclarationDescriptor =
      f.containingDeclaration
  }.apply {

  }