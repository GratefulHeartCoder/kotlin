/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import com.google.common.collect.LinkedHashMultimap
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirFunctionCallImpl
import org.jetbrains.kotlin.fir.expressions.impl.FirQualifiedAccessExpressionImpl
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedCallableReferenceImpl
import org.jetbrains.kotlin.fir.references.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.*
import org.jetbrains.kotlin.fir.scopes.impl.*
import org.jetbrains.kotlin.fir.symbols.*
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.visitors.CompositeTransformResult
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.compose
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirErrorTypeRefImpl
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*
import kotlin.collections.LinkedHashSet

class FirBodyResolveTransformer(val session: FirSession) : FirTransformer<Any?>() {

    val symbolProvider = session.service<FirSymbolProvider>()

    override fun <E : FirElement> transformElement(element: E, data: Any?): CompositeTransformResult<E> {
        @Suppress("UNCHECKED_CAST")
        return (element.transformChildren(this, data) as E).compose()
    }

    override fun transformFile(file: FirFile, data: Any?): CompositeTransformResult<FirFile> {
        return withScopeCleanup(scopes) {
            scopes += FirTopLevelDeclaredMemberScope(file, session)
            super.transformFile(file, data)
        }
    }

    override fun transformImplicitTypeRef(implicitTypeRef: FirImplicitTypeRef, data: Any?): CompositeTransformResult<FirTypeRef> {
        if (data == null)
            return implicitTypeRef.compose()
        require(data is FirTypeRef)
        return data.compose()
    }

    override fun transformFunction(function: FirFunction, data: Any?): CompositeTransformResult<FirDeclaration> {
        return withScopeCleanup(localScopes) {
            localScopes += FirLocalScope()
            super.transformFunction(function, data)
        }
    }


    override fun transformValueParameter(valueParameter: FirValueParameter, data: Any?): CompositeTransformResult<FirDeclaration> {
        localScopes.lastOrNull()?.storeDeclaration(valueParameter)
        return super.transformValueParameter(valueParameter, data)
    }


    private fun ConeClassLikeType.buildSubstitutionScope(
        useSiteSession: FirSession,
        unsubstituted: FirScope,
        regularClass: FirRegularClass
    ): FirClassSubstitutionScope? {
        if (this.typeArguments.isEmpty()) return null

        val substitution = regularClass.typeParameters.zip(this.typeArguments) { typeParameter, typeArgument ->
            typeParameter.symbol to (typeArgument as? ConeTypedProjection)?.type
        }.filter { (_, type) -> type != null }.toMap() as Map<ConeTypeParameterSymbol, ConeKotlinType>

        return FirClassSubstitutionScope(useSiteSession, unsubstituted, substitution, true)
    }

    private fun FirRegularClass.buildUseSiteScope(useSiteSession: FirSession = session): FirClassUseSiteScope {
        val superTypeScope = FirCompositeScope(mutableListOf())
        val declaredScope = FirClassDeclaredMemberScope(this, useSiteSession)
        lookupSuperTypes(this, lookupInterfaces = true, deep = false, useSiteSession = useSiteSession)
            .mapNotNullTo(superTypeScope.scopes) { useSiteSuperType ->
                if (useSiteSuperType is ConeClassErrorType) return@mapNotNullTo null
                val symbol = useSiteSuperType.lookupTag.toSymbol(useSiteSession)
                if (symbol is FirClassSymbol) {
                    val scope = symbol.fir.buildUseSiteScope(useSiteSession)
                    useSiteSuperType.buildSubstitutionScope(useSiteSession, scope, symbol.fir) ?: scope
                } else {
                    null
                }
            }
        return FirClassUseSiteScope(useSiteSession, superTypeScope, declaredScope, true)
    }

    fun ConeKotlinType.scope(useSiteSession: FirSession): FirScope? {
        return when (this) {
            is ConeKotlinErrorType -> null
            is ConeClassErrorType -> null
            is ConeAbbreviatedType -> directExpansion.scope(useSiteSession)
            is ConeClassLikeType -> {
                val fir = this.lookupTag.toSymbol(useSiteSession)?.firUnsafe<FirRegularClass>() ?: return null
                fir.buildUseSiteScope(useSiteSession)
            }
            is ConeTypeParameterType -> {
                val fir = this.lookupTag.toSymbol(useSiteSession)?.firUnsafe<FirTypeParameter>() ?: return null
                FirCompositeScope(fir.bounds.mapNotNullTo(mutableListOf()) { it.coneTypeUnsafe().scope(useSiteSession) })
            }
            else -> error("Failed type ${this}")
        }
    }


    inline fun <T> withLabel(labelName: Name, type: ConeKotlinType, block: () -> T): T {
        labels.put(labelName, type)
        val result = block()
        labels.remove(labelName, type)
        return result
    }

    override fun transformRegularClass(regularClass: FirRegularClass, data: Any?): CompositeTransformResult<FirDeclaration> {
        return withScopeCleanup(scopes) {
            val type = regularClass.defaultType()
            scopes.addIfNotNull(type.scope(session))
            withLabel(regularClass.name, type) {
                super.transformRegularClass(regularClass, data)
            }
        }
    }

    private fun FirRegularClass.defaultType(): ConeClassTypeImpl {
        return ConeClassTypeImpl(
            symbol.toLookupTag(),
            typeParameters.map {
                ConeTypeParameterTypeImpl(
                    it.symbol.toLookupTag(),
                    isNullable = false
                )
            }.toTypedArray(),
            isNullable = false
        )
    }


    protected inline fun <T> withScopeCleanup(scopes: MutableList<*>, crossinline l: () -> T): T {
        val sizeBefore = scopes.size
        val result = l()
        val size = scopes.size
        assert(size >= sizeBefore)
        repeat(size - sizeBefore) {
            scopes.let { it.removeAt(it.size - 1) }
        }
        return result
    }

    val scopes = mutableListOf<FirScope>()
    val localScopes = mutableListOf<FirLocalScope>()

    val labels = LinkedHashMultimap.create<Name, ConeKotlinType>()

    enum class CandidateApplicability {
        HIDDEN,
        PARAMETER_MAPPING_ERROR,
        SYNTHETIC_RESOLVED,
        RESOLVED
    }


    // TODO: Extract from this transformer
    abstract class ApplicabilityChecker {

        val groupNumbers = mutableListOf<Int>()
        val candidates = mutableListOf<ConeSymbol>()


        var currentApplicability = CandidateApplicability.HIDDEN

        var expectedType: FirTypeRef? = null
        var explicitReceiverType: FirTypeRef? = null


        fun newDataSet() {
            groupNumbers.clear()
            candidates.clear()
            expectedType = null
            currentApplicability = CandidateApplicability.HIDDEN
        }


        fun isSubtypeOf(superType: FirTypeRef?, subType: FirTypeRef?): Boolean {
            if (superType == null && subType == null) return true
            if (superType != null && subType != null) return true
            return false
        }


        protected open fun getApplicability(group: Int, symbol: ConeSymbol): CandidateApplicability {
            val declaration = (symbol as? FirBasedSymbol<*>)?.fir
                ?: return CandidateApplicability.HIDDEN
            declaration as FirDeclaration

            if (declaration is FirCallableDeclaration) {
                if ((declaration.receiverTypeRef == null) != (explicitReceiverType == null)) return CandidateApplicability.PARAMETER_MAPPING_ERROR
            }

            return CandidateApplicability.RESOLVED
        }

        open fun consumeCandidate(group: Int, symbol: ConeSymbol) {
            val applicability = getApplicability(group, symbol)

            if (applicability > currentApplicability) {
                groupNumbers.clear()
                candidates.clear()
                currentApplicability = applicability
            }


            if (applicability == currentApplicability) {
                candidates.add(symbol)
                groupNumbers.add(group)
            }
        }

        abstract fun updateNames(names: LinkedHashSet<Name>)

        open fun isSuccessful(index: Int, candidate: ConeSymbol): Boolean {
            return true
        }

        open fun successCandidates(): List<ConeSymbol> {
            if (groupNumbers.isEmpty()) return emptyList()
            val result = mutableListOf<ConeSymbol>()
            var bestGroup = groupNumbers.first()
            for ((index, candidate) in candidates.withIndex()) {
                val group = groupNumbers[index]
                if (!isSuccessful(index, candidate)) continue
                if (bestGroup > group) {
                    bestGroup = group
                    result.clear()
                }
                if (bestGroup == group) {
                    result.add(candidate)
                }
            }
            return result
        }
    }

    open class VariableApplicabilityChecker(val name: Name) : ApplicabilityChecker() {
        override fun updateNames(names: LinkedHashSet<Name>) {
            names.add(name)
        }

        override fun consumeCandidate(group: Int, symbol: ConeSymbol) {
            if (symbol !is ConeVariableSymbol) return
            if (symbol.callableId.callableName != name) return
            super.consumeCandidate(group, symbol)
        }
    }

    inner class VariableInvokeApplicabilityChecker(val variableName: Name) : FunctionApplicabilityChecker(invoke) {

        val variableChecker = object : VariableApplicabilityChecker(variableName) {
            override fun isSuccessful(index: Int, candidate: ConeSymbol): Boolean {
                return matchedProperties[index]
            }
        }
        private var matchedProperties = BitSet()
        private var lookupInvoke = false

        override fun updateNames(names: LinkedHashSet<Name>) {
            names.add(variableName)
            if (lookupInvoke) {
                names.add(name)
            }
        }

        private fun isInvokeApplicableOn(propertySymbol: ConeSymbol, invokeSymbol: ConeCallableSymbol): Boolean {
            return true //TODO: Actual type-check here
        }

        override fun getApplicability(group: Int, symbol: ConeSymbol): CandidateApplicability {

            symbol as ConeCallableSymbol
            val declaration = (symbol as? FirBasedSymbol<*>)?.fir
                ?: return CandidateApplicability.HIDDEN
            declaration as FirDeclaration

            if (declaration is FirFunction) {
                if (declaration.valueParameters.size != parameterCount) return CandidateApplicability.PARAMETER_MAPPING_ERROR
            }

            var applicable = false

            fun processCandidates(candidates: Iterable<IndexedValue<ConeSymbol>>) {
                for ((index, candidate) in candidates) {
                    val invokeApplicableOn = isInvokeApplicableOn(candidate, symbol)
                    if (invokeApplicableOn) {
                        applicable = true
                    }
                    matchedProperties[index] = invokeApplicableOn

                }
            }

            if (group == -1) {
                processCandidates(listOf(variableChecker.candidates.withIndex().last()))
            } else {
                processCandidates(variableChecker.candidates.withIndex())
            }

            if (applicable) {
                return CandidateApplicability.RESOLVED
            }
            return CandidateApplicability.PARAMETER_MAPPING_ERROR
        }

        private fun checkSuccess(): Boolean {
            return currentApplicability == CandidateApplicability.RESOLVED
        }

        override fun consumeCandidate(group: Int, symbol: ConeSymbol) {
            if (symbol is ConeVariableSymbol && symbol.callableId.callableName == variableName) {
                variableChecker.consumeCandidate(group, symbol)

                val lastCandidate = variableChecker.candidates.lastOrNull()
                if (variableChecker.currentApplicability == CandidateApplicability.RESOLVED && lastCandidate == symbol) {
                    val receiverScope =
                        (lastCandidate as FirBasedSymbol<FirCallableDeclaration>).fir.returnTypeRef.coneTypeUnsafe().scope(session)


                    lookupInvoke = true

                    receiverScope?.processFunctionsByName(invoke) { candidate ->
                        this.consumeCandidate(-1, candidate)
                        ProcessorAction.NEXT
                    }
                    if (checkSuccess()) return

                    for ((index, scope) in processedScopes.withIndex()) {
                        scope.processFunctionsByName(invoke) { candidate ->
                            this.consumeCandidate(index, candidate)
                            ProcessorAction.NEXT
                        }
                        if (checkSuccess()) return
                    }

                }
            }

            super.consumeCandidate(group, symbol)
        }


    }

    companion object {
        val invoke = Name.identifier("invoke")
    }

    class ClassifierApplicabilityChecker(val name: Name) : ApplicabilityChecker() {
        override fun updateNames(names: LinkedHashSet<Name>) {
            names.add(name)
        }

        override fun consumeCandidate(group: Int, symbol: ConeSymbol) {
            if (symbol !is ConeClassifierSymbol) return
            if (symbol.toLookupTag().name != name) return
            super.consumeCandidate(group, symbol)
        }

    }

    open class FunctionApplicabilityChecker(val name: Name) : ApplicabilityChecker() {

        var parameterCount = 0

        override fun updateNames(names: LinkedHashSet<Name>) {
            names.add(name)
        }

        override fun getApplicability(group: Int, symbol: ConeSymbol): CandidateApplicability {
            val declaration = (symbol as FirBasedSymbol<*>).fir

            if (declaration is FirFunction) {
                if (declaration.valueParameters.size != parameterCount) return CandidateApplicability.PARAMETER_MAPPING_ERROR
            }
            return super.getApplicability(group, symbol)
        }

        override fun consumeCandidate(group: Int, symbol: ConeSymbol) {
            if (symbol !is ConeFunctionSymbol) return
            if (symbol.callableId.callableName != name) return
            super.consumeCandidate(group, symbol)
        }
    }

    val processedScopes = mutableListOf<FirScope>()


    private fun runTowerResolver(
        checkers: List<ApplicabilityChecker>
    ): ApplicabilityChecker? {

        processedScopes.clear()

        val names = LinkedHashSet<Name>()

        var successChecker: ApplicabilityChecker? = null

        for ((index, scope) in (scopes + localScopes).asReversed().withIndex()) {
            checkers.forEach {
                it.updateNames(names)
            }
            processedScopes.add(scope)

            names.forEach { name ->
                fun process(symbol: ConeSymbol): ProcessorAction {
                    for (checker in checkers) {
                        checker.consumeCandidate(index, symbol)
                    }
                    return ProcessorAction.NEXT
                }

                scope.processClassifiersByNameWithAction(name, FirPosition.OTHER, ::process)
                scope.processPropertiesByName(name, ::process)
                scope.processFunctionsByName(name, ::process)
            }

            successChecker = checkers.maxBy { it.currentApplicability }

            if (successChecker?.currentApplicability == CandidateApplicability.RESOLVED) {
                break
            }

        }

        return successChecker

    }

    private fun <T> storeTypeFromCallee(access: T) where T : FirQualifiedAccess, T : FirExpression {
        bindingContext[access] =
            when (val newCallee = access.calleeReference) {
                is FirErrorNamedReference ->
                    FirErrorTypeRefImpl(session, access.psi, newCallee.errorReason)
                is FirResolvedCallableReference ->
                    (newCallee.callableSymbol as FirBasedSymbol<FirCallableDeclaration>).fir.returnTypeRef
                else -> return
            }
    }

    override fun transformQualifiedAccessExpression(
        qualifiedAccessExpression: FirQualifiedAccessExpression,
        data: Any?
    ): CompositeTransformResult<FirStatement> {

        when (val callee = qualifiedAccessExpression.calleeReference) {
            is FirThisReference -> {

                val labelName = callee.labelName
                val types = if (labelName == null) labels.values() else labels[Name.identifier(labelName)]
                val type = types.lastOrNull() ?: ConeKotlinErrorType("Unresolved this@$labelName")
                bindingContext[qualifiedAccessExpression] = FirResolvedTypeRefImpl(session, null, type, false, emptyList())
            }
        }
        val callee = qualifiedAccessExpression.calleeReference as? FirSimpleNamedReference ?: return qualifiedAccessExpression.compose()

        qualifiedAccessExpression.explicitReceiver?.visitNoTransform(this, null)

        val checkers = listOf(VariableApplicabilityChecker(callee.name).apply {
            expectedType = data as FirTypeRef?
            explicitReceiverType = qualifiedAccessExpression.explicitReceiver?.resultType
        })

        val result = runTowerResolver(checkers)
        if (result != null) {
            val resultExpression = qualifiedAccessExpression.transformCalleeReference(this, result.successCandidates())
            storeTypeFromCallee(resultExpression as FirQualifiedAccessExpression)
            return resultExpression.compose()
        }
        return qualifiedAccessExpression.compose()
    }

    override fun transformFunctionCall(functionCall: FirFunctionCall, data: Any?): CompositeTransformResult<FirStatement> {

        if (functionCall.calleeReference !is FirSimpleNamedReference) return functionCall.compose()

        val name = functionCall.calleeReference.name

        val expectedTypeRef = data as FirTypeRef?

        functionCall.explicitReceiver?.visitNoTransform(this, null)
        functionCall.arguments.forEach { it.visitNoTransform(this, null) }

        val checkers = listOf(FunctionApplicabilityChecker(name).apply {
            expectedType = expectedTypeRef
            explicitReceiverType = functionCall.explicitReceiver?.resultType
            parameterCount = functionCall.arguments.size
        }, VariableInvokeApplicabilityChecker(name).apply {
            expectedType = expectedTypeRef
            variableChecker.apply {
                expectedType = expectedTypeRef
                explicitReceiverType = functionCall.explicitReceiver?.resultType
            }
            parameterCount = functionCall.arguments.size
        })


        val result = runTowerResolver(checkers)

        val resultExpression = when (result) {
            is VariableInvokeApplicabilityChecker -> {
                FirFunctionCallImpl(functionCall.session, functionCall.psi, safe = functionCall.safe).apply {
                    calleeReference =
                        functionCall.calleeReference.transformSingle(this@FirBodyResolveTransformer, result.successCandidates())
                    explicitReceiver =
                        FirQualifiedAccessExpressionImpl(functionCall.session, functionCall.calleeReference.psi, functionCall.safe).apply {
                            calleeReference = createResolvedNamedReference(
                                functionCall.calleeReference,
                                result.variableChecker.successCandidates() as List<ConeCallableSymbol>
                            )
                            explicitReceiver = functionCall.explicitReceiver
                        }
                }
            }
            is ApplicabilityChecker -> {
                functionCall.transformCalleeReference(this, result.successCandidates())
            }
            else -> functionCall
        }

        storeTypeFromCallee(resultExpression as FirFunctionCall)
        return resultExpression.compose()
    }

    private fun createResolvedNamedReference(namedReference: FirNamedReference, candidates: List<ConeCallableSymbol>): FirNamedReference {
        val name = namedReference.name
        return when (candidates.size) {
            0 -> FirErrorNamedReference(
                namedReference.session, namedReference.psi, "Unresolved name: $name"
            )
            1 -> FirResolvedCallableReferenceImpl(
                namedReference.session, namedReference.psi,
                name, candidates.single()
            )
            else -> FirErrorNamedReference(
                namedReference.session, namedReference.psi, "Ambiguity: $name, ${candidates.map { it.callableId }}"
            )
        }
    }

    override fun transformNamedReference(namedReference: FirNamedReference, data: Any?): CompositeTransformResult<FirNamedReference> {
        if (namedReference is FirErrorNamedReference || namedReference is FirResolvedCallableReference) return namedReference.compose()
        val referents = data as? List<ConeCallableSymbol> ?: return namedReference.compose()
        return createResolvedNamedReference(namedReference, referents).compose()
    }


    override fun transformBlock(block: FirBlock, data: Any?): CompositeTransformResult<FirStatement> {

        block.transformChildren(this, data)
        val statement = block.statements.lastOrNull()

        val resultExpression = when (statement) {
            is FirReturnExpression -> statement.result
            is FirExpression -> statement
            else -> null
        }
        resultExpression?.resultType?.let { bindingContext[block] = it }
        return super.transformBlock(block, data)
    }

    private fun commonSuperType(types: List<FirTypeRef>): FirTypeRef? {
        return types.firstOrNull()
    }

    override fun transformWhenExpression(whenExpression: FirWhenExpression, data: Any?): CompositeTransformResult<FirStatement> {
        val type = commonSuperType(whenExpression.branches.mapNotNull {
            it.result.visitNoTransform(this, data)
            it.result.resultType
        })
        if (type != null) bindingContext[whenExpression] = type
        return super.transformWhenExpression(whenExpression, data)
    }

    override fun <T> transformConstExpression(constExpression: FirConstExpression<T>, data: Any?): CompositeTransformResult<FirStatement> {
        if (data == null) return constExpression.compose()
        val expectedType = data as FirTypeRef

        if (expectedType is FirImplicitTypeRef) {

            val symbol = when (constExpression.kind) {
                IrConstKind.Null -> StandardClassIds.Nothing(symbolProvider)
                IrConstKind.Boolean -> StandardClassIds.Boolean(symbolProvider)
                IrConstKind.Char -> StandardClassIds.Char(symbolProvider)
                IrConstKind.Byte -> StandardClassIds.Byte(symbolProvider)
                IrConstKind.Short -> StandardClassIds.Short(symbolProvider)
                IrConstKind.Int -> StandardClassIds.Int(symbolProvider)
                IrConstKind.Long -> StandardClassIds.Long(symbolProvider)
                IrConstKind.String -> StandardClassIds.String(symbolProvider)
                IrConstKind.Float -> StandardClassIds.Float(symbolProvider)
                IrConstKind.Double -> StandardClassIds.Double(symbolProvider)
            }

            val type = ConeClassTypeImpl(symbol.toLookupTag(), emptyArray(), isNullable = constExpression.kind == IrConstKind.Null)

            bindingContext[constExpression] = FirResolvedTypeRefImpl(session, null, type, false, emptyList())
        } else {
            bindingContext[constExpression] = expectedType
        }


        return super.transformConstExpression(constExpression, data)
    }

    @Deprecated("It is temp", level = DeprecationLevel.WARNING, replaceWith = ReplaceWith("TODO(\"что-то нормальное\")"))
    val bindingContext = mutableMapOf<FirExpression, FirTypeRef>()

    val FirExpression.resultType: FirTypeRef? get() = bindingContext[this]


    override fun transformNamedFunction(namedFunction: FirNamedFunction, data: Any?): CompositeTransformResult<FirDeclaration> {
        val receiverTypeRef = namedFunction.receiverTypeRef
        fun transform(): CompositeTransformResult<FirDeclaration> {
            localScopes.lastOrNull()?.storeDeclaration(namedFunction)
            return withScopeCleanup(scopes) {
                scopes.addIfNotNull(receiverTypeRef?.coneTypeSafe()?.scope(session))


                val result = super.transformNamedFunction(namedFunction, namedFunction.returnTypeRef).single as FirNamedFunction
                val body = result.body
                if (result.returnTypeRef is FirImplicitTypeRef && body != null) {
                    result.transformReturnTypeRef(this, body.resultType)
                    result
                } else {
                    result
                }.compose()
            }
        }

        return if (receiverTypeRef != null) {
            withLabel(namedFunction.name, receiverTypeRef.coneTypeUnsafe()) { transform() }
        } else {
            transform()
        }
    }

    override fun transformVariable(variable: FirVariable, data: Any?): CompositeTransformResult<FirDeclaration> {
        val initializer = variable.initializer
        initializer?.visitNoTransform(this, variable.returnTypeRef)
        if (variable.returnTypeRef is FirImplicitTypeRef) {
            when {
                initializer != null -> {
                    variable.transformReturnTypeRef(this, initializer.resultType)
                }
                variable.delegate != null -> {}
            }
        }
        if (variable !is FirProperty) {
            localScopes.lastOrNull()?.storeDeclaration(variable)
        }
        return super.transformVariable(variable, data)
    }

    override fun transformProperty(property: FirProperty, data: Any?): CompositeTransformResult<FirDeclaration> {
        return transformVariable(property, data)
    }

    private fun <D> FirElement.visitNoTransform(transformer: FirTransformer<D>, data: D) {
        val result = this.transform(transformer, data)
        require(result.single === this)
    }
}


@Deprecated("It is temp", level = DeprecationLevel.WARNING, replaceWith = ReplaceWith("TODO(\"что-то нормальное\")"))
class FirBodyResolveTransformerAdapter : FirTransformer<Nothing?>() {
    override fun <E : FirElement> transformElement(element: E, data: Nothing?): CompositeTransformResult<E> {
        return element.compose()
    }

    override fun transformFile(file: FirFile, data: Nothing?): CompositeTransformResult<FirFile> {
        val transformer = FirBodyResolveTransformer(file.session)
        return file.transform(transformer, null)
    }
}


inline fun <reified T> ConeSymbol.firUnsafe(): T {
    this as FirBasedSymbol<*>
    return this.fir as T
}