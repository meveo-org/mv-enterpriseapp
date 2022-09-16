package org.meveo.enterpriseapp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.meveo.persistence.CrossStorageService;
import org.meveo.security.MeveoUser;
import org.meveo.admin.exception.BusinessException;
import org.meveo.api.persistence.CrossStorageApi;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.JavaEnterpriseApp;
import org.meveo.model.git.GitRepository;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
import org.meveo.model.scripts.FunctionIO;
import org.meveo.model.scripts.ScriptInstance;
import org.meveo.model.storage.Repository;
import org.meveo.model.technicalservice.endpoint.Endpoint;
import org.meveo.service.admin.impl.MeveoModuleService;
import org.meveo.service.crm.impl.CustomFieldInstanceService;
import org.meveo.service.crm.impl.CustomFieldTemplateService;
import org.meveo.service.custom.CustomEntityTemplateService;
import org.meveo.service.custom.EntityCustomActionService;
import org.meveo.service.git.GitClient;
import org.meveo.service.git.GitHelper;
import org.meveo.service.git.GitRepositoryService;
import org.meveo.service.script.Script;
import org.meveo.service.script.ScriptInstanceService;
import org.meveo.service.storage.RepositoryService;
import org.meveo.service.technicalservice.endpoint.EndpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

public class GenerateJavaEnterpriseApplication extends Script {
	
	private static final Logger log = LoggerFactory.getLogger(GenerateJavaEnterpriseApplication.class);

	private static final String MASTER_BRANCH = "master";
	
	private static final String LOG_SEPARATOR = "***********************************************************";

	private static final String MEVEO_BRANCH = "meveo";

	private static final String MV_TEMPLATE_REPO = "https://github.com/masumcse1/mv-template.git";
	
	private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();

	private static final String CUSTOM_ENDPOINT_TEMPLATE = Endpoint.class.getName();

	private static final String JAVAENTERPRISE_APP_TEMPLATE = JavaEnterpriseApp.class.getSimpleName();

	private static final String CDIBEANFILE = "beans.xml";
	
	private static final String CUSTOMENDPOINTRESOURCEFILE = "CustomEndpointResource.java";
	
	private static final String SET_REQUEST_RESTPONSE_METHOD = "setRequestResponse";
	
	private static final String CUSTOME_ENDPOINT_BASE_RESOURCE = "CustomEndpointResource";
	
	private static final String CUSTOME_ENDPOINT_BASE_RESOURCE_PACKAGE = "org.meveo.base.CustomEndpointResource";
	
	private ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);

	private CrossStorageApi crossStorageApi = getCDIBean(CrossStorageApi.class);

	private CrossStorageService crossStorageService = getCDIBean(CrossStorageService.class);

	private CustomEntityTemplateService cetService = getCDIBean(CustomEntityTemplateService.class);

	private CustomFieldInstanceService cfiService = getCDIBean(CustomFieldInstanceService.class);

	private CustomFieldTemplateService cftService = getCDIBean(CustomFieldTemplateService.class);

	private EntityCustomActionService ecaService = getCDIBean(EntityCustomActionService.class);

	private GitClient gitClient = getCDIBean(GitClient.class);

	private GitRepositoryService gitRepositoryService = getCDIBean(GitRepositoryService.class);

	private MeveoModuleService meveoModuleService = getCDIBean(MeveoModuleService.class);

	private RepositoryService repositoryService = getCDIBean(RepositoryService.class);
	
	private ScriptInstanceService scriptInstanceService = getCDIBean(ScriptInstanceService.class);

	@Inject
	private EndpointService endpointService;

	private Repository repository;

	private String moduleCode;	

	public String getModuleCode() {
		return this.moduleCode;
	}

	public void setModuleCode(String moduleCode) {
		this.moduleCode = moduleCode;
	}

	public Repository getDefaultRepository() {
		if (repository == null) {
			repository = repositoryService.findDefaultRepository();
		}
		return repository;
	}

	@Override
	public void execute(Map<String, Object> parameters) throws BusinessException {
		super.execute(parameters);

		if (moduleCode == null) {
			throw new BusinessException("moduleCode not set");
		}
		MeveoModule module = meveoModuleService.findByCode(moduleCode);
		MeveoUser user = (MeveoUser) parameters.get(CONTEXT_CURRENT_USER);
	
		log.info("generating java enterprise application from module, {}", moduleCode);
		if (module != null) {
			log.debug("Module found: {}", module.getCode());
			Set<MeveoModuleItem> moduleItems = module.getModuleItems();
			log.debug("CUSTOM_TEMPLATE={}", CUSTOM_TEMPLATE);
			List<String> entityCodes = moduleItems.stream().filter(item -> CUSTOM_TEMPLATE.equals(item.getItemClass()))
					.map(entity -> entity.getItemCode()).collect(Collectors.toList());
			log.debug("entityCodes: {}", entityCodes);
	
		// SAVE COPY OF MV-TEMPLATE TO MEVEO GIT REPOSITORY
		GitRepository enterpriseappTemplateRepo = gitRepositoryService.findByCode(JAVAENTERPRISE_APP_TEMPLATE);
		if (enterpriseappTemplateRepo == null) {
			log.debug("CREATE NEW GitRepository: {}", JAVAENTERPRISE_APP_TEMPLATE);
			enterpriseappTemplateRepo = new GitRepository();
			enterpriseappTemplateRepo.setCode(JAVAENTERPRISE_APP_TEMPLATE);
			enterpriseappTemplateRepo.setDescription(JAVAENTERPRISE_APP_TEMPLATE + " Template repository");
			enterpriseappTemplateRepo.setRemoteOrigin(MV_TEMPLATE_REPO);
			enterpriseappTemplateRepo.setDefaultRemoteUsername("");
			enterpriseappTemplateRepo.setDefaultRemotePassword("");
			gitRepositoryService.create(enterpriseappTemplateRepo);
		} else {
			gitClient.pull(enterpriseappTemplateRepo, "", "");
		}
		File enterpriseappTemplateDirectory = GitHelper.getRepositoryDir(user, JAVAENTERPRISE_APP_TEMPLATE);
		Path enterpriseAppTemplatePath = enterpriseappTemplateDirectory.toPath();
		log.debug("webappTemplate path: {}", enterpriseAppTemplatePath.toString());

		/// Generate module
		GitRepository moduleEnterpriseAppRepo = gitRepositoryService.findByCode(moduleCode);
		gitClient.checkout(moduleEnterpriseAppRepo, MEVEO_BRANCH, true);
		File moduleEnterpriseAppDirectory = GitHelper.getRepositoryDir(user, moduleCode);
		Path moduleEnterpriseAppPath = moduleEnterpriseAppDirectory.toPath();

		List<File> filesToCommit = new ArrayList<>();

		String pathJavaRestConfigurationFile = "facets/java/org/meveo/" + moduleCode + "/rest/"
				+ capitalize(moduleCode) + "RestConfig" + ".java";

		try {

			File restConfigfile = new File(moduleEnterpriseAppDirectory, pathJavaRestConfigurationFile);
			String restConfigurationFileContent = generateRestConfiguration(moduleCode);
			FileUtils.write(restConfigfile, restConfigurationFileContent, StandardCharsets.UTF_8);
			filesToCommit.add(restConfigfile);
		} catch (IOException e) {
			throw new BusinessException("Failed creating file." + e.getMessage());
		}
		
		List<String> endpointCodes = moduleItems.stream()
				.filter(item -> CUSTOM_ENDPOINT_TEMPLATE.equals(item.getItemClass()))
				.map(entity -> entity.getItemCode()).collect(Collectors.toList());
	
		 String endPointEntityClass=null; 
		 String endPointDtoClass=null; 
		 
		for (String endpointCode : endpointCodes) {
			Endpoint endpoint = endpointService.findByCode(endpointCode);
			ScriptInstance scriptInstance = scriptInstanceService.findByCode(endpoint.getService().getCode());
			List<FunctionIO> inputList = scriptInstance.getInputs();
			 for(FunctionIO input:inputList) {
					if( isImplementedByCustomEntity(input.getType())) {
						 endPointEntityClass =input.getType();
					}
			 }
			
			 if (endPointEntityClass!=null ) {
			 endPointDtoClass=endPointEntityClass + "Dto"; 
				String pathJavaDtoFile = "facets/java/org/meveo/" + moduleCode + "/dto/" + endPointDtoClass + ".java";

				try {
					File dtofile = new File(moduleEnterpriseAppDirectory, pathJavaDtoFile);
					String dtocontent = generateDto(endPointEntityClass,endPointDtoClass,moduleCode);
					FileUtils.write(dtofile, dtocontent, StandardCharsets.UTF_8);
					filesToCommit.add(dtofile);
				} catch (IOException e) {
					throw new BusinessException("Failed creating file." + e.getMessage());
				}
			 }
	
		String pathEndpointFile = "facets/java/org/meveo/" + moduleCode + "/resource/"	+ endpoint.getCode() + ".java";
		try {
			File endPointFile = new File(moduleEnterpriseAppDirectory, pathEndpointFile);
			String endPointContent = generateEndPoint(endpoint, endPointEntityClass,endPointDtoClass,moduleCode);
			FileUtils.write(endPointFile, endPointContent, StandardCharsets.UTF_8);
			filesToCommit.add(endPointFile);
		} catch (IOException e) {
			throw new BusinessException("Failed creating file." + e.getMessage());
		}

		List<File> templatefiles = templateFileCopy(enterpriseAppTemplatePath, moduleEnterpriseAppPath);
		filesToCommit.addAll(templatefiles);

		if (!filesToCommit.isEmpty()) {
			gitClient.commitFiles(moduleEnterpriseAppRepo, filesToCommit, "DTO & Endpoint generation.");
		}

			}

		}
		log.debug("------ GenerateJavaEnterpriseApplication.execute()--------------");
	}

	/*
	 * Generate Rest Configuration file 
	 */
	String generateRestConfiguration(String moduleCode) {
		CompilationUnit compilationUnit = new CompilationUnit();
		StringBuilder restconfigurationpackage=new StringBuilder("org.meveo.").append(moduleCode).append(".rest");
		compilationUnit.setPackageDeclaration(restconfigurationpackage.toString());
		compilationUnit.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.ApplicationPath"), false, false));
		compilationUnit.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.core.Application"), false, false));
		
		ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(capitalize(moduleCode) + "RestConfig").setPublic(true);
		classDeclaration.addSingleMemberAnnotation("ApplicationPath", new StringLiteralExpr("api"));

		NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
		extendsList.add(new ClassOrInterfaceType().setName(new SimpleName("Application")));
		classDeclaration.setExtendedTypes(extendsList);

		return compilationUnit.toString();

	}

	/*
	 * Generate EndPoint related DTO file 
	 */
	String generateDto(String endPointEntityClass, String endPointDtoClass, String moduleCode) {
		CompilationUnit compilationUnit = new CompilationUnit();
	
		StringBuilder dtoPackage=new StringBuilder("org.meveo.").append(moduleCode).append(".dto");
		compilationUnit.setPackageDeclaration(dtoPackage.toString());
		compilationUnit.getImports().add(new ImportDeclaration(new Name("org.meveo.model.customEntities." + endPointEntityClass), false, false));
		ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(endPointDtoClass).setPublic(true);

		FieldDeclaration typefield = new FieldDeclaration();
		VariableDeclarator typeVar = new VariableDeclarator();
		typeVar.setName("type");
		typeVar.setType("String");

		typefield.setModifiers(Modifier.Keyword.PRIVATE);
		typefield.addVariable(typeVar);
		classDeclaration.addMember(typefield);

		FieldDeclaration entityClassField = new FieldDeclaration();
		VariableDeclarator entityClassVar = new VariableDeclarator();
		entityClassVar.setName(endPointEntityClass.toLowerCase());
		entityClassVar.setType(endPointEntityClass);

		entityClassField.setModifiers(Modifier.Keyword.PRIVATE);
		entityClassField.addVariable(entityClassVar);
		classDeclaration.addMember(entityClassField);

		classDeclaration.addConstructor(Modifier.Keyword.PUBLIC);
	
		ConstructorDeclaration constructor = classDeclaration.addConstructor(Modifier.Keyword.PUBLIC);
		Parameter typeconsparam = new Parameter();
		typeconsparam.setType(endPointEntityClass);
		typeconsparam.setName(endPointEntityClass.toLowerCase());
		constructor.addParameter(typeconsparam);
		Parameter entityconsparam = new Parameter();
		entityconsparam.setType(typeVar.getType());
		entityconsparam.setName(typeVar.getName());
		constructor.addParameter(entityconsparam);

		BlockStmt constructorbody = new BlockStmt();
		AssignExpr typefiledexpression = new AssignExpr(new FieldAccessExpr(new ThisExpr(), typeVar.getNameAsString()), new NameExpr(typeVar.getName()),AssignExpr.Operator.ASSIGN);
		AssignExpr entityClassFieldexpression =new AssignExpr(new FieldAccessExpr(new ThisExpr(), endPointEntityClass.toLowerCase()), new NameExpr(endPointEntityClass.toLowerCase()),AssignExpr.Operator.ASSIGN);
		constructorbody.addStatement(typefiledexpression);
    	constructorbody.addStatement(entityClassFieldexpression);
		constructor.setBody(constructorbody);
		
		typefield.createGetter();
		typefield.createSetter();
		entityClassField.createGetter();
		entityClassField.createSetter();

		return compilationUnit.toString();

	}

	/*
	 * Generate EndPoint 
	 */
	  public String generateEndPoint(Endpoint endPoint,String endPointEntityClass,String endPointDtoClass,String moduleCode) {
		  
	    String endPointCode        = endPoint.getCode();
	    String httpMethod          = endPoint.getMethod().getLabel();
	    String serviceCode         = getServiceCode(endPoint.getService().getCode());
		  
		CompilationUnit cu = new CompilationUnit();
		StringBuilder resourcepackage=new StringBuilder("org.meveo.").append(moduleCode).append(".resource");
		cu.setPackageDeclaration(resourcepackage.toString());
		cu.getImports().add(new ImportDeclaration(new Name("java.io"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("java.util"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.core"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("javax.enterprise.context.RequestScoped"), false, false));
		cu.getImports().add(new ImportDeclaration(new Name("javax.inject.Inject"), false, false));
		cu.getImports().add(new ImportDeclaration(new Name("org.meveo.admin.exception.BusinessException"), false, false));
		cu.getImports().add(new ImportDeclaration(new Name(CUSTOME_ENDPOINT_BASE_RESOURCE_PACKAGE), false, false));
		if (httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT")) {
			StringBuilder endpointDtoclasspackage=new StringBuilder("org.meveo.").append(moduleCode).append(".dto."+endPointDtoClass);
			cu.getImports().add(new ImportDeclaration(new Name(endpointDtoclasspackage.toString()), false, false));
		}
		cu.getImports().add(new ImportDeclaration(new Name(endPoint.getService().getCode()), false, false));
		
		String injectedFieldName=getNonCapitalizeNameWithPrefix(serviceCode);
		ClassOrInterfaceDeclaration clazz = generateRestClass(cu,endPointCode,httpMethod,endPoint.getBasePath(),serviceCode,injectedFieldName);
		MethodDeclaration restMethod = generateRestMethod(clazz,httpMethod,endPoint.getPath(),endPointDtoClass,endPoint.getContentType());

		BlockStmt beforeTryblock = new BlockStmt();

		VariableDeclarator var_result = new VariableDeclarator();
		var_result.setName("result");
		var_result.setType("String");
		var_result.setInitializer(new NullLiteralExpr());

		NodeList<VariableDeclarator> var_result_declarator = new NodeList<>();
		var_result_declarator.add(var_result);
		beforeTryblock.addStatement(new ExpressionStmt().setExpression(new VariableDeclarationExpr().setVariables(var_result_declarator)));

		beforeTryblock.addStatement(new ExpressionStmt(new NameExpr("parameterMap = new HashMap<String, Object>()")));

		if (httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT")) {
			MethodCallExpr getEntity_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
			getEntity_methodCall.addArgument(new StringLiteralExpr(getNonCapitalizeName(endPointEntityClass)));
			getEntity_methodCall.addArgument(new MethodCallExpr(new NameExpr(getNonCapitalizeName(endPointDtoClass)), "get" + endPointEntityClass));

			beforeTryblock.addStatement(getEntity_methodCall);

			MethodCallExpr getType_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
			getType_methodCall.addArgument(new StringLiteralExpr("type"));
			getType_methodCall.addArgument(new MethodCallExpr(new NameExpr(getNonCapitalizeName(endPointDtoClass)), "getType"));

			beforeTryblock.addStatement(getType_methodCall);
			
		}

		if (httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("DELETE")	|| httpMethod.equalsIgnoreCase("PUT")) {
			MethodCallExpr getType_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
			getType_methodCall.addArgument(new StringLiteralExpr(getNonCapitalizeName(endPoint.getPath())));
			getType_methodCall.addArgument(getNonCapitalizeName(endPoint.getPath())); 

			beforeTryblock.addStatement(getType_methodCall);
		}
	
		beforeTryblock.addStatement(new ExpressionStmt(new MethodCallExpr(SET_REQUEST_RESTPONSE_METHOD)));
		Statement trystatement = generateTryBlock(var_result,httpMethod, endPoint.getPath(),injectedFieldName,endPointEntityClass,endPointDtoClass);

		beforeTryblock.addStatement(trystatement);
		restMethod.setBody(beforeTryblock);

		restMethod.getBody().get().getStatements().add(getReturnType());
		return cu.toString();
		}
	

	private ClassOrInterfaceDeclaration generateRestClass(CompilationUnit cu,String endPointCode,String httpMethod,String httpBasePath,String serviceCode,String injectedFieldName) {
		ClassOrInterfaceDeclaration clazz = cu.addClass(endPointCode,	Modifier.Keyword.PUBLIC);
		clazz.addSingleMemberAnnotation("Path", new StringLiteralExpr(httpBasePath));
		clazz.addMarkerAnnotation("RequestScoped");
		var injectedfield = clazz.addField(serviceCode, injectedFieldName, Modifier.Keyword.PRIVATE);
		injectedfield.addMarkerAnnotation("Inject");

		NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
		extendsList.add(new ClassOrInterfaceType().setName(new SimpleName(CUSTOME_ENDPOINT_BASE_RESOURCE)));
		clazz.setExtendedTypes(extendsList);
		return clazz;
	}

	private MethodDeclaration generateRestMethod(ClassOrInterfaceDeclaration clazz,String httpMethod,String path,String endPointDtoClass,String contentType) {
		
		MethodDeclaration restMethod = clazz.addMethod("execute",Modifier.Keyword.PUBLIC);
		restMethod.setType("Response");
		restMethod.addMarkerAnnotation(httpMethod);

		if (httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("DELETE")	|| httpMethod.equalsIgnoreCase("PUT")) {
			restMethod.addSingleMemberAnnotation("Path", new StringLiteralExpr(path));
		}

		if (httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT")) {
				restMethod.addParameter(endPointDtoClass, getNonCapitalizeName(endPointDtoClass));
		}

		if (httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("DELETE")	|| httpMethod.equalsIgnoreCase("PUT")) {
			Parameter restMethodParameter = new Parameter();
			restMethodParameter.setType("String");
			restMethodParameter.setName(getNonCapitalizeName(path));
			restMethodParameter.addSingleMemberAnnotation("PathParam", new StringLiteralExpr(getNonCapitalizeName(path)));
			restMethod.addParameter(restMethodParameter);
		}
	
		if(contentType.equalsIgnoreCase("application/json")) {
			restMethod.addSingleMemberAnnotation("Produces", "MediaType.APPLICATION_JSON");
			restMethod.addSingleMemberAnnotation("Consumes", "MediaType.APPLICATION_JSON");
		}
		
		return restMethod;

	}

	private Statement generateTryBlock(VariableDeclarator assignmentVariable,String httpMethod,String path,String injectedFieldName,String endPointEntityClass,String endPointDtoClass) {
		BlockStmt tryblock = new BlockStmt();

		if (httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT")) {
			tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "set" + endPointEntityClass)
					.addArgument (new MethodCallExpr(new NameExpr(getNonCapitalizeName(endPointDtoClass)), "get" + endPointEntityClass)));
		}

		if (httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("PUT") || httpMethod.equalsIgnoreCase("DELETE")) {
			tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "set"+capitalize(path)).addArgument(getNonCapitalizeName(path)));
		}
			
 		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "init").addArgument("parameterMap"));
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "execute").addArgument("parameterMap"));
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "finalize").addArgument("parameterMap"));
		tryblock.addStatement(assignment(assignmentVariable.getNameAsString(), injectedFieldName, "getResult"));
		Statement trystatement = addingException(tryblock);
		return trystatement;
	}

	private ReturnStmt getReturnType() {
		return new ReturnStmt(new NameExpr("Response.status(Response.Status.OK).entity(result).build()"));
	}

	/**
	 * 
	 * @param --org.meveo.script.CreateMyProduct
	 * @return--CreateMyProduct
	 */
	private String getServiceCode(String serviceCode) {
		return serviceCode.substring(serviceCode.lastIndexOf(".") + 1);
	}


	private Statement addingException(BlockStmt body) {
		TryStmt ts = new TryStmt();
		ts.setTryBlock(body);
		CatchClause cc = new CatchClause();
		String exceptionName = "e";
		cc.setParameter(new Parameter().setName(exceptionName).setType(BusinessException.class));
		BlockStmt cb = new BlockStmt();
		cb.addStatement(new ExpressionStmt(	new NameExpr("return Response.status(Response.Status.BAD_REQUEST).entity(result).build()")));
		// cb.addStatement(new ThrowStmt(new NameExpr(ex8uj ceptionName)));
		cc.setBody(cb);
		ts.setCatchClauses(new NodeList<>(cc));

		return ts;
	}

	private Statement assignment(String assignOject, String callOBject, String methodName) {
		MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(callOBject), methodName);
		AssignExpr assignExpr = new AssignExpr(new NameExpr(assignOject), methodCallExpr, AssignExpr.Operator.ASSIGN);
		return new ExpressionStmt(assignExpr);
	}

	/*
	 * input  : CreateMyProduct
	 * return : _createMyProduct
	 */
	private String getNonCapitalizeNameWithPrefix(String className) {
		className = className.replaceAll("[^a-zA-Z0-9]", " ").trim();
		String prefix="_";
		if (className == null || className.length() == 0)
			return className;
		String objectReferenceName = prefix+className.substring(0, 1).toLowerCase() + className.substring(1);
		return objectReferenceName.trim();

	}
	
	/*
	 * input  : CreateMyProduct
	 * return : createMyProduct
	 */
	private String getNonCapitalizeName(String className) {
		className = className.replaceAll("[^a-zA-Z0-9]", " ").trim();  
		if (className == null || className.length() == 0)
			return className;
		String objectReferenceName = className.substring(0, 1).toLowerCase() + className.substring(1);
		return objectReferenceName.trim();

	}
	
	
	/*
	 * input  : createMyProduct
	 * return : CreateMyProduct
	 */
	public  String capitalize(String str) {
		str = str.replaceAll("[^a-zA-Z0-9]", " ").trim();  
		if (str == null || str.isEmpty()) {
			return str;
		}

		return str.substring(0, 1).toUpperCase() + str.substring(1).trim();
	}
	
	/*
	 * check entity class implemented by "CustomEntity" interface
	 */
	boolean isImplementedByCustomEntity(String className) {
		String implementedIninterface=new String("CustomEntity");  
		boolean isImplemented=false;
		try {
			
			String modelClass = "org.meveo.model.customEntities."+className;
			@SuppressWarnings("rawtypes")
			Class clazz = Class.forName(modelClass);
			String interfacename = Arrays.toString(clazz.getInterfaces());
			interfacename = interfacename.replaceAll("[^a-zA-Z0-9.]", " "); 
			interfacename=interfacename.substring(interfacename.lastIndexOf(".") + 1);
			interfacename=interfacename.trim();
			if (interfacename.equals(implementedIninterface))
				isImplemented=true;
	
		} catch (Exception e) {
			
		}
		
		return isImplemented;
	}
	
	/*
	 * copy files (CustomEndpointResource.java, beans.xml) into project directory 
	 */
	private List<File> templateFileCopy(Path webappTemplatePath, Path moduleWebAppPath) throws BusinessException {
		List<File> filesToCommit = new ArrayList<>();

		try (Stream<Path> sourceStream = Files.walk(webappTemplatePath)) {
			List<Path> sources = sourceStream.collect(Collectors.toList());
			List<Path> destinations = sources.stream().map(webappTemplatePath::relativize)
					.map(moduleWebAppPath::resolve).collect(Collectors.toList());
			for (int index = 0; index < sources.size(); index++) {
				Path sourcePath = sources.get(index);
				Path destinationPath = destinations.get(index);

				if (sourcePath.toString().contains(CUSTOMENDPOINTRESOURCEFILE)
						|| sourcePath.toString().contains(CDIBEANFILE)) {
					try {
						File outputFile = new File(destinationPath.toString());
						File inputfile = new File(sourcePath.toString());
						String inputcontent = FileUtils.readFileToString(inputfile, StandardCharsets.UTF_8.name());
						FileUtils.write(outputFile, inputcontent, StandardCharsets.UTF_8);
						filesToCommit.add(outputFile);
					} catch (Exception e) {
						throw new BusinessException("Failed creating file." + e.getMessage());
					}
				}

			}

		} catch (IOException ioe) {
			throw new BusinessException(ioe);
		}

		return filesToCommit;
	}

}
