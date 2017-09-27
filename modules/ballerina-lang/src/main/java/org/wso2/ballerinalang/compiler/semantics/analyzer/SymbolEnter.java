/*
*  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.wso2.ballerinalang.compiler.semantics.analyzer;

import org.ballerinalang.model.TreeBuilder;
import org.ballerinalang.model.elements.Flag;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.tree.IdentifierNode;
import org.ballerinalang.model.tree.NodeKind;
import org.ballerinalang.model.tree.TopLevelNode;
import org.ballerinalang.util.diagnostic.DiagnosticCode;
import org.wso2.ballerinalang.compiler.PackageLoader;
import org.wso2.ballerinalang.compiler.semantics.model.Scope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolEnv;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BInvokableSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BTypeSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BVarSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.SymTag;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.Symbols;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BStructType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BStructType.BStructField;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.tree.BLangAction;
import org.wso2.ballerinalang.compiler.tree.BLangCompilationUnit;
import org.wso2.ballerinalang.compiler.tree.BLangConnector;
import org.wso2.ballerinalang.compiler.tree.BLangFunction;
import org.wso2.ballerinalang.compiler.tree.BLangImportPackage;
import org.wso2.ballerinalang.compiler.tree.BLangInvokableNode;
import org.wso2.ballerinalang.compiler.tree.BLangNode;
import org.wso2.ballerinalang.compiler.tree.BLangNodeVisitor;
import org.wso2.ballerinalang.compiler.tree.BLangPackage;
import org.wso2.ballerinalang.compiler.tree.BLangPackageDeclaration;
import org.wso2.ballerinalang.compiler.tree.BLangResource;
import org.wso2.ballerinalang.compiler.tree.BLangService;
import org.wso2.ballerinalang.compiler.tree.BLangStruct;
import org.wso2.ballerinalang.compiler.tree.BLangVariable;
import org.wso2.ballerinalang.compiler.tree.BLangWorker;
import org.wso2.ballerinalang.compiler.tree.BLangXMLNS;
import org.wso2.ballerinalang.compiler.tree.statements.BLangBlockStmt;
import org.wso2.ballerinalang.compiler.tree.statements.BLangReturn;
import org.wso2.ballerinalang.compiler.tree.statements.BLangVariableDef;
import org.wso2.ballerinalang.compiler.tree.types.BLangUserDefinedType;
import org.wso2.ballerinalang.compiler.util.CompilerContext;
import org.wso2.ballerinalang.compiler.util.Name;
import org.wso2.ballerinalang.compiler.util.Names;
import org.wso2.ballerinalang.compiler.util.NodeUtils;
import org.wso2.ballerinalang.compiler.util.TypeTags;
import org.wso2.ballerinalang.compiler.util.diagnotic.DiagnosticLog;
import org.wso2.ballerinalang.compiler.util.diagnotic.DiagnosticPos;
import org.wso2.ballerinalang.util.Flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.ballerinalang.model.tree.NodeKind.IMPORT;

/**
 * @since 0.94
 */
public class SymbolEnter extends BLangNodeVisitor {

    private static final CompilerContext.Key<SymbolEnter> SYMBOL_ENTER_KEY =
            new CompilerContext.Key<>();

    private PackageLoader pkgLoader;
    private SymbolTable symTable;
    private Names names;
    private SymbolResolver symResolver;
    private DiagnosticLog dlog;

    private BLangPackage rootPkgNode;

    private SymbolEnv env;
    public Map<BPackageSymbol, SymbolEnv> packageEnvs = new HashMap<>();

    public static SymbolEnter getInstance(CompilerContext context) {
        SymbolEnter symbolEnter = context.get(SYMBOL_ENTER_KEY);
        if (symbolEnter == null) {
            symbolEnter = new SymbolEnter(context);
        }

        return symbolEnter;
    }

    public SymbolEnter(CompilerContext context) {
        context.put(SYMBOL_ENTER_KEY, this);

        this.pkgLoader = PackageLoader.getInstance(context);
        this.symTable = SymbolTable.getInstance(context);
        this.names = Names.getInstance(context);
        this.symResolver = SymbolResolver.getInstance(context);
        this.dlog = DiagnosticLog.getInstance(context);

        this.rootPkgNode = (BLangPackage) TreeBuilder.createPackageNode();
        this.rootPkgNode.symbol = symTable.rootPkgSymbol;
    }

    public BPackageSymbol definePackage(BLangPackage pkgNode) {
        populatePackageNode(pkgNode);

        defineNode(pkgNode, null);
        return pkgNode.symbol;
    }

    public void defineNode(BLangNode node, SymbolEnv env) {
        SymbolEnv prevEnv = this.env;
        this.env = env;
        node.accept(this);
        this.env = prevEnv;
    }


    // Visitor methods

    @Override
    public void visit(BLangPackage pkgNode) {
        // Create PackageSymbol.
        BPackageSymbol pSymbol = createPackageSymbol(pkgNode);
        SymbolEnv pkgEnv = SymbolEnv.createPkgEnv(pkgNode, pSymbol.scope);
        packageEnvs.put(pSymbol, pkgEnv);

        // visit the package node recursively and define all package level symbols.
        // And maintain a list of created package symbols.
        pkgNode.imports.forEach(importNode -> defineNode(importNode, pkgEnv));

        // Define struct nodes.
        pkgNode.structs.forEach(struct -> defineNode(struct, pkgEnv));

        // Define connector nodes.
        pkgNode.connectors.forEach(con -> defineNode(con, pkgEnv));

        // Define struct field nodes.
        defineStructFields(pkgNode.structs, pkgEnv);

        // Define connector action nodes.
        defineActions(pkgNode.connectors, pkgEnv);

        // Define function nodes.
        pkgNode.functions.forEach(func -> defineNode(func, pkgEnv));

        // Define service and resource nodes.
        defineServices(pkgNode.services, pkgEnv);

        pkgNode.globalVars.forEach(var -> defineNode(var, pkgEnv));

        definePackageInitFunction(pkgNode, pkgEnv);
    }

    @Override
    public void visit(BLangImportPackage importPkgNode) {
        BLangPackage pkgNode = pkgLoader.loadPackage(importPkgNode.pkgNameComps, importPkgNode.version);
        // Create import package symbol
        BPackageSymbol pkgSymbol = pkgNode.symbol;
        importPkgNode.symbol = pkgSymbol;
        this.env.scope.define(names.fromIdNode(importPkgNode.alias), pkgSymbol);
    }

    @Override
    public void visit(BLangXMLNS xmlnsNode) {
        throw new AssertionError();
    }

    @Override
    public void visit(BLangStruct structNode) {
        BSymbol structSymbol = Symbols.createStructSymbol(Flags.asMask(structNode.flagSet),
                names.fromIdNode(structNode.name), env.enclPkg.symbol.pkgID, null, env.scope.owner);
        structNode.symbol = structSymbol;
        defineSymbol(structNode.pos, structSymbol);

        // Create struct type
        structNode.symbol.type = new BStructType((BTypeSymbol) structNode.symbol, new ArrayList<>());
    }

    @Override
    public void visit(BLangWorker workerNode) {
        BInvokableSymbol workerSymbol = Symbols.createWorkerSymbol(Flags.asMask(workerNode.flagSet),
                names.fromIdNode(workerNode.name), env.enclPkg.symbol.pkgID, null, env.scope.owner);
        workerNode.symbol = workerSymbol;
        defineSymbolWithCurrentEnvOwner(workerNode.pos, workerSymbol);
    }

    @Override
    public void visit(BLangConnector connectorNode) {
        BSymbol conSymbol = Symbols.createConnectorSymbol(Flags.asMask(connectorNode.flagSet),
                names.fromIdNode(connectorNode.name), env.enclPkg.symbol.pkgID, null, env.scope.owner);
        connectorNode.symbol = conSymbol;
        defineConnectorInitFunction(connectorNode);
        defineSymbol(connectorNode.pos, conSymbol);
    }

    @Override
    public void visit(BLangService serviceNode) {
        BSymbol serviceSymbol = Symbols.createServiceSymbol(Flags.asMask(serviceNode.flagSet),
                names.fromIdNode(serviceNode.name), env.enclPkg.symbol.pkgID, null, env.scope.owner);
        serviceNode.symbol = serviceSymbol;
        defineServiceInitFunction(serviceNode);
        defineSymbol(serviceNode.pos, serviceSymbol);
    }

    @Override
    public void visit(BLangFunction funcNode) {
        validateFuncReceiver(funcNode);
        BInvokableSymbol funcSymbol = Symbols.createFunctionSymbol(Flags.asMask(funcNode.flagSet),
                getFuncSymbolName(funcNode), env.enclPkg.symbol.pkgID, null, env.scope.owner);
        SymbolEnv invokableEnv = SymbolEnv.createFunctionEnv(funcNode, funcSymbol.scope, env);
        defineInvokableSymbol(funcNode, funcSymbol, invokableEnv);

        // Define function receiver if any.
        if (funcNode.receiver != null) {
            // Check whether there exists a struct field with the same name as the function name.
            BTypeSymbol structSymbol = funcNode.receiver.type.tsymbol;
            BSymbol symbol = symResolver.lookupMemberSymbol(funcNode.receiver.pos, structSymbol.scope, invokableEnv,
                    names.fromIdNode(funcNode.name), SymTag.VARIABLE);
            if (symbol != symTable.notFoundSymbol) {
                dlog.error(funcNode.pos, DiagnosticCode.STRUCT_FIELD_AND_FUNC_WITH_SAME_NAME,
                        funcNode.name.value, funcNode.receiver.type.toString());
            }

            defineNode(funcNode.receiver, invokableEnv);
            funcSymbol.receiverSymbol = funcNode.receiver.symbol;
            ((BInvokableType) funcSymbol.type).receiverType = funcNode.receiver.symbol.type;
        }
    }

    @Override
    public void visit(BLangAction actionNode) {
        BInvokableSymbol actionSymbol = Symbols
                .createActionSymbol(Flags.asMask(actionNode.flagSet), names.fromIdNode(actionNode.name),
                        env.enclPkg.symbol.pkgID, null, env.scope.owner);
        SymbolEnv invokableEnv = SymbolEnv.createResourceActionSymbolEnv(actionNode, actionSymbol.scope, env);
        defineInvokableSymbol(actionNode, actionSymbol, invokableEnv);
    }

    @Override
    public void visit(BLangResource resourceNode) {
        BInvokableSymbol resourceSymbol = Symbols
                .createResourceSymbol(Flags.asMask(resourceNode.flagSet), names.fromIdNode(resourceNode.name),
                        env.enclPkg.symbol.pkgID, null, env.scope.owner);
        SymbolEnv invokableEnv = SymbolEnv.createResourceActionSymbolEnv(resourceNode, resourceSymbol.scope, env);
        defineInvokableSymbol(resourceNode, resourceSymbol, invokableEnv);
    }

    @Override
    public void visit(BLangVariable varNode) {
        // assign the type to var type node
        BType varType = symResolver.resolveTypeNode(varNode.typeNode, env);
        varNode.type = varType;

        Name varName = names.fromIdNode(varNode.name);
        if (varName == Names.EMPTY) {
            // This is a variable created for a return type
            // e.g. function foo() (int);
            return;
        }

        varNode.symbol = defineVarSymbol(varNode.pos, varNode.flagSet, varType, varName, env);
    }


    // Private methods

    private BPackageSymbol createPackageSymbol(BLangPackage pkgNode) {
        BPackageSymbol pSymbol;
        if (pkgNode.pkgDecl == null) {
            pSymbol = new BPackageSymbol(PackageID.DEFAULT, symTable.rootPkgSymbol);
        } else {
            PackageID pkgID = NodeUtils.getPackageID(names, pkgNode.pkgDecl.pkgNameComps, pkgNode.pkgDecl.version);
            pSymbol = new BPackageSymbol(pkgID, symTable.rootPkgSymbol);
        }
        pkgNode.symbol = pSymbol;
        pSymbol.scope = new Scope(pSymbol);
        return pSymbol;
    }

    /**
     * Visit each compilation unit (.bal file) and add each top-level node
     * in the compilation unit to the package node.
     *
     * @param pkgNode current package node
     */
    private void populatePackageNode(BLangPackage pkgNode) {
        List<BLangCompilationUnit> compUnits = pkgNode.getCompilationUnits();
        compUnits.forEach(compUnit -> populateCompilationUnit(pkgNode, compUnit));
    }

    /**
     * Visit each top-level node and add it to the package node.
     *
     * @param pkgNode  current package node
     * @param compUnit current compilation unit
     */
    private void populateCompilationUnit(BLangPackage pkgNode, BLangCompilationUnit compUnit) {
        // TODO Check whether package in 'compUnit' is equal to the package in 'pkgNode'

        // TODO If the pkgID is null, then assign an unnamed package/default package.
        compUnit.getTopLevelNodes().forEach(node -> addTopLevelNode(pkgNode, node));
    }

    private void addTopLevelNode(BLangPackage pkgNode, TopLevelNode node) {
        NodeKind kind = node.getKind();

        // Here we keep all the top-level nodes of a compilation unit (aka file) in exact same
        // order as they appear in the compilation unit. This list contains all the top-level
        // nodes of all the compilation units grouped by the compilation unit.
        // This allows other compiler phases to visit top-level nodes in the exact same order
        // as they appear in compilation units. This is required for error reporting.
        if (kind != NodeKind.PACKAGE_DECLARATION && kind != IMPORT) {
            pkgNode.topLevelNodes.add(node);
        }

        switch (kind) {
            case PACKAGE_DECLARATION:
                // TODO verify the rules..
                pkgNode.pkgDecl = (BLangPackageDeclaration) node;
                break;
            case IMPORT:
                // TODO Verify the rules..
                // TODO Check whether the same package alias (if any) has been used for the same import
                // TODO The version of an import package can be specified only once for a package
                if (!pkgNode.imports.contains(node)) {
                    pkgNode.imports.add((BLangImportPackage) node);
                }
                break;
            case FUNCTION:
                pkgNode.functions.add((BLangFunction) node);
                break;
            case STRUCT:
                pkgNode.structs.add((BLangStruct) node);
                break;
            case CONNECTOR:
                pkgNode.connectors.add((BLangConnector) node);
                break;
            case SERVICE:
                pkgNode.services.add((BLangService) node);
                break;
            case VARIABLE:
                pkgNode.globalVars.add((BLangVariable) node);
                // TODO There are two kinds of package level variables, constant and regular variables.
                break;
            case ANNOTATION:
                // TODO
                break;
            case XMLNS:
                // TODO
                break;
        }
    }

    private void defineStructFields(List<BLangStruct> structNodes, SymbolEnv pkgEnv) {
        structNodes.forEach(struct -> {
            // Create struct type
            SymbolEnv structEnv = SymbolEnv.createPkgLevelSymbolEnv(struct, struct.symbol.scope, pkgEnv);
            BStructType structType = (BStructType) struct.symbol.type;
            structType.fields = struct.fields.stream()
                    .peek(field -> field.flagSet.add(Flag.PUBLIC))
                    .peek(field -> defineNode(field, structEnv))
                    .map(field -> new BStructField(names.fromIdNode(field.name), field.type))
                    .collect(Collectors.toList());
        });
    }

    private void defineServices(List<BLangService> serviceNodes, SymbolEnv pkgEnv) {
        serviceNodes.forEach(service -> {
            defineNode(service, pkgEnv);
            SymbolEnv serviceEnv = SymbolEnv.createServiceEnv(service, service.symbol.scope, pkgEnv);
            service.resources.stream()
                    .peek(resource -> resource.flagSet.add(Flag.PUBLIC))
                    .forEach(resource -> defineNode(resource, serviceEnv));
        });
    }

    private void defineActions(List<BLangConnector> connectors, SymbolEnv pkgEnv) {
        connectors.forEach(connector -> {
            SymbolEnv conEnv = SymbolEnv.createConnectorEnv(connector, connector.symbol.scope, pkgEnv);
            connector.actions.stream()
                    .peek(action -> action.flagSet.add(Flag.PUBLIC))
                    .forEach(action -> defineNode(action, conEnv));
        });
    }

    private void defineInvokableSymbol(BLangInvokableNode invokableNode, BInvokableSymbol funcSymbol,
                                       SymbolEnv invokableEnv) {
        invokableNode.symbol = funcSymbol;
        defineSymbol(invokableNode.pos, funcSymbol);
        invokableEnv.scope = funcSymbol.scope;
        defineInvokableSymbolParams(invokableNode, funcSymbol, invokableEnv);
    }

    private void defineInvokableSymbolParams(BLangInvokableNode invokableNode, BInvokableSymbol symbol,
                                             SymbolEnv invokableEnv) {
        List<BVarSymbol> paramSymbols =
                invokableNode.params.stream()
                        .peek(varNode -> defineNode(varNode, invokableEnv))
                        .map(varNode -> varNode.symbol)
                        .collect(Collectors.toList());

        List<BVarSymbol> retParamSymbols =
                invokableNode.retParams.stream()
                        .peek(varNode -> defineNode(varNode, invokableEnv))
                        .filter(varNode -> varNode.symbol != null)
                        .map(varNode -> varNode.symbol)
                        .collect(Collectors.toList());

        symbol.params = paramSymbols;
        symbol.retParams = retParamSymbols;

        // Create function type
        List<BType> paramTypes = paramSymbols.stream()
                .map(paramSym -> paramSym.type)
                .collect(Collectors.toList());
        List<BType> retTypes = invokableNode.retParams.stream()
                .map(varNode -> varNode.typeNode.type)
                .collect(Collectors.toList());

        symbol.type = new BInvokableType(paramTypes, retTypes, null);
    }

    private void defineSymbol(DiagnosticPos pos, BSymbol symbol) {
        symbol.scope = new Scope(symbol);
        if (symResolver.checkForUniqueSymbol(pos, env, symbol)) {
            env.scope.define(symbol.name, symbol);
        }
    }

    private void defineSymbolWithCurrentEnvOwner(DiagnosticPos pos, BSymbol symbol) {
        symbol.scope = new Scope(env.scope.owner);
        if (symResolver.checkForUniqueSymbol(pos, env, symbol)) {
            env.scope.define(symbol.name, symbol);
        }
    }

    public BVarSymbol defineVarSymbol(DiagnosticPos pos, Set<Flag> flagSet, BType varType, Name varName,
                                      SymbolEnv env) {
        // Create variable symbol
        Scope enclScope = env.scope;
        BVarSymbol varSymbol = new BVarSymbol(Flags.asMask(flagSet), varName,
                env.enclPkg.symbol.pkgID, varType, enclScope.owner);

        // Add it to the enclosing scope
        // Find duplicates
        if (symResolver.checkForUniqueSymbol(pos, env, varSymbol)) {
            enclScope.define(varSymbol.name, varSymbol);
        }
        return varSymbol;
    }

    private void defineConnectorInitFunction(BLangConnector connector) {
        BLangFunction initFunction = createInitFunction(connector.pos, connector.getName().getValue());
        //Add connector as a parameter to the init function
        BLangVariable param = (BLangVariable) TreeBuilder.createVariableNode();
        param.pos = connector.pos;
        param.setName(this.createIdentifier(Names.CONNECTOR.getValue()));
        BLangUserDefinedType connectorType = (BLangUserDefinedType) TreeBuilder.createUserDefinedTypeNode();
        connectorType.pos = connector.pos;
        connectorType.typeName = connector.name;
        param.setTypeNode(connectorType);
        initFunction.addParameter(param);
        //Add connector level variables to the init function
        for (BLangVariableDef variableDef : connector.getVariableDefs()) {
            initFunction.body.addStatement(variableDef);
        }
        addInitReturnStatement(initFunction.body);
        connector.initFunction = initFunction;
        defineNode(connector.initFunction, env);
    }

    private void defineServiceInitFunction(BLangService service) {
        BLangFunction initFunction = createInitFunction(service.pos, service.getName().getValue());
        //Add service level variables to the init function
        for (BLangVariableDef variableDef : service.getVariables()) {
            initFunction.body.addStatement(variableDef);
        }
        addInitReturnStatement(initFunction.body);
        service.initFunction = initFunction;
        defineNode(service.initFunction, env);
    }

    private void definePackageInitFunction(BLangPackage pkgNode, SymbolEnv env) {
        BLangFunction initFunction = createInitFunction(pkgNode.pos, pkgNode.symbol.getName().getValue());
        //Add global variables to the init function
        for (BLangVariable variable : pkgNode.getGlobalVariables()) {
            initFunction.body.addStatement(createVariableDefStatement(variable.pos, variable));
        }
        addInitReturnStatement(initFunction.body);
        pkgNode.initFunction = initFunction;
        defineNode(pkgNode.initFunction, env);
    }

    private BLangFunction createInitFunction(DiagnosticPos pos, String name) {
        BLangFunction initFunction = (BLangFunction) TreeBuilder.createFunctionNode();
        initFunction.setName(createIdentifier(name + Names.INIT_FUNCTION_SUFFIX.getValue()));
        initFunction.pos = pos;
        //Create body of the init function
        BLangBlockStmt body = (BLangBlockStmt) TreeBuilder.createBlockNode();
        body.pos = pos;
        initFunction.setBody(body);
        return initFunction;
    }

    private BLangVariableDef createVariableDefStatement(DiagnosticPos pos, BLangVariable variable) {
        BLangVariableDef variableDef = (BLangVariableDef) TreeBuilder.createVariableDefinitionNode();
        variableDef.pos = pos;
        variableDef.var = variable;
        return variableDef;
    }

    private IdentifierNode createIdentifier(String value) {
        IdentifierNode node = TreeBuilder.createIdentifierNode();
        if (value != null) {
            node.setValue(value);
        }
        return node;
    }

    private void addInitReturnStatement(BLangBlockStmt bLangBlockStmt) {
        //Add return statement to the init function
        BLangReturn returnStmt = (BLangReturn) TreeBuilder.createReturnNode();
        returnStmt.pos = bLangBlockStmt.pos;
        bLangBlockStmt.addStatement(returnStmt);
    }

    private void validateFuncReceiver(BLangFunction funcNode) {
        if (funcNode.receiver == null) {
            return;
        }

        BType varType = symResolver.resolveTypeNode(funcNode.receiver.typeNode, env);
        funcNode.receiver.type = varType;
        if (varType.tag == TypeTags.ERROR) {
            return;
        }

        if (varType.tag != TypeTags.STRUCT) {
            dlog.error(funcNode.receiver.pos, DiagnosticCode.FUNC_DEFINED_ON_NON_STRUCT_TYPE,
                    funcNode.name.value, varType.toString());
            return;
        }

        if (this.env.enclPkg.symbol.pkgID != varType.tsymbol.pkgID) {
            dlog.error(funcNode.receiver.pos, DiagnosticCode.FUNC_DEFINED_ON_NON_LOCAL_STRUCT_TYPE,
                    funcNode.name.value, varType.toString());
        }
    }

    private Name getFuncSymbolName(BLangFunction funcNode) {
        if (funcNode.receiver != null) {
            return names.fromString(funcNode.receiver.type + "." + funcNode.name.value);
        }
        return names.fromIdNode(funcNode.name);
    }
}
