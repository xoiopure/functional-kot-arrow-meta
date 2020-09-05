package arrow.meta.plugins.proofs.phases.resolve.scopes

import arrow.meta.Meta
import arrow.meta.log.Log
import arrow.meta.log.invoke
import arrow.meta.phases.CompilerContext
import arrow.meta.phases.ExtensionPhase
import arrow.meta.phases.codegen.ir.receiverParameter
import arrow.meta.phases.resolve.unwrappedNotNullableType
import arrow.meta.plugins.proofs.phases.callables
import arrow.meta.plugins.proofs.phases.extending
import arrow.meta.plugins.proofs.phases.ir.ProofCandidate
import arrow.meta.plugins.proofs.phases.ir.typeSubstitutor
import arrow.meta.plugins.proofs.phases.resolve.ProofReceiverValue
import org.jetbrains.kotlin.codegen.coroutines.createCustomCopy
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.ir.descriptors.WrappedDeclarationDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.inference.substitute
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.scopes.SyntheticScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

fun CompilerContext.syntheticMemberFunctions(receiverTypes: Collection<KotlinType>, name: Name): List<SimpleFunctionDescriptor> =
  syntheticMemberFunctions(receiverTypes)
    .filter { it.name == name }

fun CompilerContext.syntheticMemberFunctions(receiverTypes: Collection<KotlinType>): List<SimpleFunctionDescriptor> =
  extending(receiverTypes).flatMap { proof ->
    proof.callables { true }
      .filterIsInstance<SimpleFunctionDescriptor>()
      .filter { !it.isExtension }
      .flatMap {
        receiverTypes.map { receiverType ->
          val substitutor = ProofCandidate(
            proofType = proof.from,
            otherType = receiverType.unwrappedNotNullableType,
            through = it
          ).typeSubstitutor
          val targetType = substitutor.safeSubstitute(proof.to.unwrap())
          val receiver = ProofReceiverValue(targetType)
          val dispatcher = receiverParameter(receiver, it, sourceElement = it.source, annotations = it.annotations).substitute(substitutor) as ReceiverParameterDescriptor
          val resultingFunction =
            it.substitute(substitutor).safeAs<SimpleFunctionDescriptor>()
              ?.createCustomCopy {
                setPreserveSourceElement()
                setDispatchReceiverParameter(dispatcher).setDropOriginalInContainingParts()
                  .setOriginal(it)
              }
          val result = resultingFunction?.createCustomCopy {
            setPreserveSourceElement()
            setDispatchReceiverParameter(receiverParameter(ProofReceiverValue(receiverType), resultingFunction, sourceElement = resultingFunction.source))
          }
          result
        }.filterIsInstance<SimpleFunctionDescriptor>()
      }
  }

/*fun WrappedCallableDescriptor<*>.substituteW(substitutor: NewTypeSubstitutor): WrappedCallableDescriptor<*> {
  val wrappedSubstitution = object : TypeSubstitution() {
    override fun get(key: KotlinType): TypeProjection? = null
    override fun prepareTopLevelType(topLevelType: KotlinType, position: Variance) = substitutor.safeSubstitute(topLevelType.unwrap())
  }
  return substitute(TypeSubstitutor.create(wrappedSubstitution))
}*/

class ProofsSyntheticScope(private val ctx: CompilerContext) : SyntheticScope {
  override fun getSyntheticConstructor(constructor: ConstructorDescriptor): ConstructorDescriptor? =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticConstructor($constructor), result: $this" }) {
      null
    }

  override fun getSyntheticConstructors(classifierDescriptors: Collection<DeclarationDescriptor>): Collection<FunctionDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticConstructor($classifierDescriptors), result: $this" }) {
      emptyList()
    }

  override fun getSyntheticConstructors(contributedClassifier: ClassifierDescriptor, location: LookupLocation): Collection<FunctionDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticConstructors($contributedClassifier, $location) result: $this" }) {
      emptyList()
    }

  override fun getSyntheticExtensionProperties(receiverTypes: Collection<KotlinType>, location: LookupLocation): Collection<PropertyDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticExtensionProperties($receiverTypes) result: $this" }) {
      emptyList()
    }

  override fun getSyntheticExtensionProperties(receiverTypes: Collection<KotlinType>, name: Name, location: LookupLocation): Collection<PropertyDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticExtensionProperties($receiverTypes, $name) result: $this" }) {
      emptyList()
    }

  override fun getSyntheticMemberFunctions(receiverTypes: Collection<KotlinType>): Collection<FunctionDescriptor> =
    Log.Verbose({ "ProofsSyntheticScope.getSyntheticMemberFunctions types: $receiverTypes $this" }) {
      ctx.syntheticMemberFunctions(receiverTypes)
    }

  override fun getSyntheticMemberFunctions(receiverTypes: Collection<KotlinType>, name: Name, location: LookupLocation): Collection<FunctionDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticMemberFunctions $this" }) {
      ctx.syntheticMemberFunctions(receiverTypes, name)
    }

  override fun getSyntheticStaticFunctions(functionDescriptors: Collection<DeclarationDescriptor>): Collection<FunctionDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticStaticFunctions($functionDescriptors)" }) {
      emptyList()
    }

  override fun getSyntheticStaticFunctions(contributedFunctions: Collection<FunctionDescriptor>, location: LookupLocation): Collection<FunctionDescriptor> =
    Log.Silent({ "ProofsSyntheticScope.getSyntheticStaticFunctions contributedFunctions: $contributedFunctions" }) {
      emptyList()
    }
}

fun CallableMemberDescriptor.discardPlatformBaseObjectFakeOverrides(): CallableMemberDescriptor? =
  when (kind) {
    CallableMemberDescriptor.Kind.FAKE_OVERRIDE ->
      if (dispatchReceiverParameter?.type == builtIns.anyType) null
      else this
    else -> this
  }

fun Meta.provenSyntheticScope(): ExtensionPhase =
  syntheticScopes(
    syntheticMemberFunctionsForName = { types, name, _ ->
      Log.Silent({ "syntheticScopes.syntheticMemberFunctionsForName $types $name $this" }) {
        syntheticMemberFunctions(types, name)
      }
    },
    syntheticMemberFunctions = { types ->
      Log.Silent({ "syntheticScopes.syntheticMemberFunctions $types $this" }) {
        syntheticMemberFunctions(types)
      }
    },
    syntheticStaticFunctions = { scope ->
      Log.Silent({ "syntheticScopes.syntheticStaticFunctions $scope $this" }) {
        emptyList()
      }
    },
    syntheticStaticFunctionsForName = { contributedFunctions, location ->
      Log.Silent({ "syntheticScopes.syntheticStaticFunctionsForName $contributedFunctions $location $this" }) {
        emptyList()
      }
    },
    syntheticConstructor = { constructor ->
      Log.Silent({ "syntheticScopes.syntheticConstructor $constructor" }) {
        null
      }
    },
    syntheticConstructors = { scope ->
      Log.Silent({ "syntheticScopes.syntheticConstructors $scope" }) {
        emptyList()
      }
    },
    syntheticConstructorsForName = { contributedClassifier, location ->
      Log.Silent({ "syntheticScopes.syntheticConstructorsForName $contributedClassifier $location" }) {
        emptyList()
      }
    },
    syntheticExtensionProperties = { receiverTypes, location ->
      Log.Silent({ "syntheticScopes.syntheticExtensionProperties $receiverTypes, $location" }) {
        emptyList()
      }
    },
    syntheticExtensionPropertiesForName = { receiverTypes, name, location ->
      Log.Silent({ "syntheticScopes.syntheticExtensionPropertiesForName $receiverTypes, $name, $location" }) {
        emptyList()
      }
    }
  )

/**
 * Invariant:
 * assert(isOriginalDescriptor(descriptor) && if (descriptor !is WrappedDeclarationDescriptor<*>)
 *  descriptor.containingDeclaration == null || isOriginalDescriptor(descriptor.containingDeclaration) else true)
 */
fun isOriginalDescriptor(descriptor: DeclarationDescriptor): Boolean =
  descriptor is WrappedDeclarationDescriptor<*> ||
    // TODO fix declaring/referencing value parameters: compute proper original descriptor
    descriptor is ValueParameterDescriptor && isOriginalDescriptor(descriptor.containingDeclaration) ||
    descriptor == descriptor.original