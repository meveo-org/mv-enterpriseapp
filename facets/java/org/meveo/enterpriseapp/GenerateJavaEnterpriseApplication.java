package org.meveo.enterpriseapp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.meveo.admin.exception.BusinessException;
import org.meveo.commons.utils.ParamBean;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.JavaEnterpriseApp;
import org.meveo.model.git.GitRepository;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
import org.meveo.model.scripts.Accessor;
import org.meveo.model.scripts.ScriptInstance;
import org.meveo.model.technicalservice.endpoint.Endpoint;
import org.meveo.model.technicalservice.endpoint.EndpointPathParameter;
import org.meveo.model.technicalservice.endpoint.TSParameterMapping;
import org.meveo.security.MeveoUser;
import org.meveo.service.admin.impl.MeveoModuleService;
import org.meveo.service.git.GitClient;
import org.meveo.service.git.GitHelper;
import org.meveo.service.git.GitRepositoryService;
import org.meveo.service.script.Script;
import org.meveo.service.script.ScriptInstanceService;
import org.meveo.service.technicalservice.endpoint.EndpointService;
import org.meveo.util.Version;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenerateJavaEnterpriseApplication extends Script {
    private static final Logger LOG = LoggerFactory.getLogger(GenerateJavaEnterpriseApplication.class);

    private static final String PATH_SEPARATORS = "/\\";
    private static final String MEVEO_BRANCH = "meveo";
    private static final String MV_TEMPLATE_REPO = "https://github.com/meveo-org/module-war-template.git";
    private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();
    private static final String CUSTOM_ENDPOINT_TEMPLATE = Endpoint.class.getName();
    private static final String JAVA_ENTERPRISE_APP_TEMPLATE = JavaEnterpriseApp.class.getSimpleName();
    private static final String CDI_BEAN_FILE = "beans.xml";
    private static final String CUSTOM_ENDPOINT_RESOURCE_FILE = "CustomEndpointResource.java";
    private static final String MAVEN_EE_POM_FILE = "pom.xml";
    private static final String DEPLOYMENT_SCRIPT_FILE = "moduledeployment.sh";
    private static final String SET_REQUEST_RESPONSE_METHOD = "setRequestResponse";
    private static final String CUSTOM_ENDPOINT_RESOURCE = "CustomEndpointResource";
    private static final String CUSTOM_ENDPOINT_BASE_RESOURCE_PACKAGE = "org.meveo.base.CustomEndpointResource";
    private static final String MODULE_VERSION = "1.0.0";

    private final ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
    private final ParamBean config = paramBeanFactory.getInstance();
    private final GitClient gitClient = getCDIBean(GitClient.class);
    private final GitRepositoryService gitRepositoryService = getCDIBean(GitRepositoryService.class);
    private final MeveoModuleService meveoModuleService = getCDIBean(MeveoModuleService.class);
    private final ScriptInstanceService scriptInstanceService = getCDIBean(ScriptInstanceService.class);
    private final EndpointService endpointService = getCDIBean(EndpointService.class);

    private String moduleCode;

    public void setModuleCode(String moduleCode) {
        this.moduleCode = moduleCode;
    }

    @Override
    public void execute(Map<String, Object> parameters) throws BusinessException {
        super.execute(parameters);

        if (moduleCode == null) {
            throw new BusinessException("moduleCode not set");
        }
        MeveoModule module = meveoModuleService.findByCode(moduleCode);
        MeveoUser user = (MeveoUser) parameters.get(CONTEXT_CURRENT_USER);

        LOG.info("generating java enterprise application from module, {}", moduleCode);
        if (module != null) {
            LOG.debug("Module found: {}", module.getCode());
            Set<MeveoModuleItem> moduleItems = module.getModuleItems();
            LOG.debug("CUSTOM_TEMPLATE={}", CUSTOM_TEMPLATE);
            List<String> entityCodes = moduleItems.stream().filter(item -> CUSTOM_TEMPLATE.equals(item.getItemClass()))
                                                  .map(entity -> entity.getItemCode()).collect(Collectors.toList());
            LOG.debug("entityCodes: {}", entityCodes);

            // SAVE COPY OF MV-TEMPLATE TO MEVEO GIT REPOSITORY
            GitRepository enterpriseAppTemplateRepo = gitRepositoryService.findByCode(JAVA_ENTERPRISE_APP_TEMPLATE);
            if (enterpriseAppTemplateRepo == null) {
                LOG.debug("CREATE NEW GitRepository: {}", JAVA_ENTERPRISE_APP_TEMPLATE);
                enterpriseAppTemplateRepo = new GitRepository();
                enterpriseAppTemplateRepo.setCode(JAVA_ENTERPRISE_APP_TEMPLATE);
                enterpriseAppTemplateRepo.setDescription(JAVA_ENTERPRISE_APP_TEMPLATE + " Template repository");
                enterpriseAppTemplateRepo.setRemoteOrigin(MV_TEMPLATE_REPO);
                enterpriseAppTemplateRepo.setDefaultRemoteUsername("");
                enterpriseAppTemplateRepo.setDefaultRemotePassword("");
                gitRepositoryService.create(enterpriseAppTemplateRepo);
            } else {
                gitClient.pull(enterpriseAppTemplateRepo, "", "");
            }
            File enterpriseappTemplateDirectory = GitHelper.getRepositoryDir(user, enterpriseAppTemplateRepo);
            Path enterpriseAppTemplatePath = enterpriseappTemplateDirectory.toPath();
            LOG.debug("webappTemplate path: {}", enterpriseAppTemplatePath.toString());

            /// Generate module
            GitRepository moduleEnterpriseAppRepo = gitRepositoryService.findByCode(moduleCode);
            gitClient.checkout(moduleEnterpriseAppRepo, MEVEO_BRANCH, true);
            File moduleEnterpriseAppDirectory = GitHelper.getRepositoryDir(user, moduleEnterpriseAppRepo);
            Path moduleEnterpriseAppPath = moduleEnterpriseAppDirectory.toPath();
            String pomFilePath = moduleEnterpriseAppDirectory.getAbsolutePath() + "/facets/maven/pom.xml";
            List<File> filesToCommit = new ArrayList<>();
            String basePath = config.getProperty("providers.rootDir", "./meveodata/");
            basePath = (new File(basePath)).getAbsolutePath().replaceAll("/\\./", "/");
            basePath = StringUtils.stripEnd(basePath, PATH_SEPARATORS);
            basePath = StringUtils.stripEnd(basePath, ".");
            LOG.info("Meveo data path: {}", basePath);
            //***************RestConfiguration file  Generation************************

            String pathJavaRestConfigurationFile = "facets/javaee/org/meveo/" + moduleCode + "/rest/"
                    + capitalize(moduleCode) + "RestConfig" + ".java";

            try {

                File restConfigfile = new File(moduleEnterpriseAppDirectory, pathJavaRestConfigurationFile);
                String restConfigurationFileContent = generateRestConfigurationClass(moduleCode);
                FileUtils.write(restConfigfile, restConfigurationFileContent, StandardCharsets.UTF_8);
                filesToCommit.add(restConfigfile);
            } catch (IOException e) {
                throw new BusinessException("Failed creating file." + e.getMessage());
            }

            // -----Identity entity,DTO Generation,  EndPoint Generation--------------------
            List<String> endpointCodes = moduleItems
                    .stream()
                    .filter(item -> CUSTOM_ENDPOINT_TEMPLATE.equals(item.getItemClass()))
                    .map(MeveoModuleItem::getItemCode)
                    .collect(Collectors.toList());

            String endPointDtoClass = null;

            for (String endpointCode : endpointCodes) {
                endPointDtoClass = null;
                Endpoint endpoint = endpointService.findByCode(endpointCode);
                //Endpoint DTO class Generation
                if (!endpoint.getParametersMappingNullSafe().isEmpty()) {
                    if (endpoint.getMethod().getLabel().equalsIgnoreCase("POST") ||
                            endpoint.getMethod().getLabel().equalsIgnoreCase("PUT")) {

                        endPointDtoClass = endpoint.getCode() + "Dto";
                        String pathJavaDtoFile =
                                "facets/javaee/org/meveo/" + moduleCode + "/dto/" + endPointDtoClass + ".java";
                        try {
                            File dtofile = new File(moduleEnterpriseAppDirectory, pathJavaDtoFile);
                            String dtocontent = generateEndPointDto(moduleCode, endpoint, endPointDtoClass);
                            FileUtils.write(dtofile, dtocontent, StandardCharsets.UTF_8);
                            filesToCommit.add(dtofile);
                        } catch (IOException e) {
                            throw new BusinessException("Failed creating file." + e.getMessage());
                        }

                    }
                }
                //Endpoint Class Generation
                String pathEndpointFile =
                        "facets/javaee/org/meveo/" + moduleCode + "/resource/" + endpoint.getCode() + ".java";
                try {
                    File endPointFile = new File(moduleEnterpriseAppDirectory, pathEndpointFile);
                    String endPointContent = generateEndPoint(moduleCode, endpoint, endPointDtoClass);
                    FileUtils.write(endPointFile, endPointContent, StandardCharsets.UTF_8);
                    filesToCommit.add(endPointFile);
                } catch (IOException e) {
                    throw new BusinessException("Failed creating file." + e.getMessage());
                }

                String tagToKeep = "repositories";
                String repositoriesTagContent = copyXmlTagContent(pomFilePath, tagToKeep);

                List<File> templateFiles = templateFileCopy(moduleCode, enterpriseAppTemplatePath,
                        moduleEnterpriseAppPath, repositoriesTagContent);
                filesToCommit.addAll(templateFiles);
            }

            if (!filesToCommit.isEmpty()) {
                gitClient.commitFiles(moduleEnterpriseAppRepo, filesToCommit, "DTO & Endpoint generation.");
            }

            generationWar(moduleEnterpriseAppDirectory.getAbsolutePath());

        }
        LOG.debug("------ GenerateJavaEnterpriseApplication.execute()--------------");
    }

    /*
     * Generate module war file in local repo folder
     */
    private void generationWar(String moduleEnterpriseAppDirectoryPath) throws BusinessException {
        String javaPath = moduleEnterpriseAppDirectoryPath + "/facets/java";
        String javasymlinkpath = moduleEnterpriseAppDirectoryPath + "/facets/mavenee/src/main/java";
        symbolicLinkCreation(javaPath, javasymlinkpath);

        String javaeePath = moduleEnterpriseAppDirectoryPath + "/facets/javaee";
        String javaeesymlinkpath = moduleEnterpriseAppDirectoryPath + "/facets/mavenee/src/main/javaee";
        symbolicLinkCreation(javaeePath, javaeesymlinkpath);

        String mvnHome = null;

        if (System.getenv("MAVEN_HOME") != null) {
            mvnHome = System.getenv("MAVEN_HOME");
        } else if (System.getenv("M2_HOME") != null) {
            mvnHome = System.getenv("M2_HOME");
        } else if (System.getenv("MVN_HOME") != null) {
            mvnHome = System.getenv("MVN_HOME");
        }

        String maveneeDir = moduleEnterpriseAppDirectoryPath + "/facets/mavenee";

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(new File(maveneeDir));
        request.setGoals(Collections.singletonList("package"));

        try {
            DefaultInvoker invoker = new DefaultInvoker();
            invoker.setMavenHome(new File(mvnHome));
            invoker.execute(request);
        } catch (MavenInvocationException e) {
            LOG.error("Failed to mvn package for mavenee pom.xml", e);
            throw new BusinessException("Failed creating file." + e.getMessage());
        }
    }

    /*
     * Create Symbolic link for Java , JavaEE folder
     */
    private void symbolicLinkCreation(String targetFilePath, String symbolicLinkPath) {
        try {
            Path path = Paths.get(symbolicLinkPath);
            if (Files.exists(path)) {
                Files.delete(path);
            }

            Path target = FileSystems.getDefault().getPath(targetFilePath);
            Path link = FileSystems.getDefault().getPath(symbolicLinkPath);
            Files.createSymbolicLink(link, target);
        } catch (IOException e) {
            LOG.error("Failed to create symbolic link: {} => {}", targetFilePath, symbolicLinkPath, e);
        }
    }

    /**
     * create a DTO class for each endpoint
     *
     * @param moduleCode
     * @param endpoint
     * @param endPointDtoClass
     * @return
     */
    String generateEndPointDto(String moduleCode, Endpoint endpoint, String endPointDtoClass) {
        CompilationUnit compilationUnit = new CompilationUnit();
        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endpoint.getService().getCode());
        StringBuilder dtoPackage = new StringBuilder("org.meveo.").append(moduleCode).append(".dto");
        compilationUnit.setPackageDeclaration(dtoPackage.toString());
        compilationUnit.getImports()
                       .add(new ImportDeclaration(new Name("org.meveo.model.customEntities"), false, true));
        ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(endPointDtoClass).setPublic(true);

        classDeclaration.addConstructor();
        List<TSParameterMapping> parametersMappings = endpoint.getParametersMappingNullSafe();
        Boolean isMultivalued = parametersMappings.stream().map(p -> p.isMultivalued()).findAny().get();

        if (isMultivalued)
            compilationUnit.getImports().add(new ImportDeclaration(new Name("java.util.List"), false, false));

        for (TSParameterMapping parameterMapping : parametersMappings) {

            String pathParameterType = scriptInstance.getSetters().stream()
                                                     .filter(p -> p.getName().equalsIgnoreCase(
                                                             parameterMapping.getParameterName()))
                                                     .map(p -> p.getType())
                                                     .findFirst().get();

            FieldDeclaration entityClassField = new FieldDeclaration();
            VariableDeclarator entityClassVar = new VariableDeclarator();
            entityClassVar.setName(parameterMapping.getParameterName());
            entityClassVar.setType(pathParameterType);

            entityClassField.setModifiers(Modifier.Keyword.PRIVATE);
            entityClassField.addVariable(entityClassVar);
            classDeclaration.addMember(entityClassField);

            entityClassField.createGetter();
            entityClassField.createSetter();
        }

        return compilationUnit.toString();
    }

    /**
     * Generate Rest Configuration class
     *
     * @param moduleCode
     * @return
     */
    String generateRestConfigurationClass(String moduleCode) {
        CompilationUnit compilationUnit = new CompilationUnit();
        compilationUnit.setPackageDeclaration("org.meveo." + moduleCode + ".rest");
        compilationUnit.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.ApplicationPath"), false, false));
        compilationUnit.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.core.Application"), false, false));

        ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(capitalize(moduleCode) + "RestConfig")
                                                                      .setPublic(true);
        classDeclaration.addSingleMemberAnnotation("ApplicationPath", new StringLiteralExpr("api"));

        NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
        extendsList.add(new ClassOrInterfaceType().setName(new SimpleName("Application")));
        classDeclaration.setExtendedTypes(extendsList);

        return compilationUnit.toString();
    }

    /**
     * Generate EndPoint class
     *
     * @param endPoint
     * @param endPointDtoClass
     * @param moduleCode
     * @return
     */
    public String generateEndPoint(String moduleCode, Endpoint endPoint, String endPointDtoClass) {
        String endPointCode = endPoint.getCode();
        String httpMethod = endPoint.getMethod().getLabel();
        String serviceCode = getServiceCode(endPoint.getService().getCode());

        CompilationUnit cu = new CompilationUnit();
        StringBuilder resourcepackage = new StringBuilder("org.meveo.").append(moduleCode).append(".resource");
        cu.setPackageDeclaration(resourcepackage.toString());
        cu.getImports().add(new ImportDeclaration(new Name("java.time"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("java.util"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.core"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("javax.enterprise.context.RequestScoped"), false, false));
        cu.getImports().add(new ImportDeclaration(new Name("javax.inject.Inject"), false, false));
        cu.getImports()
          .add(new ImportDeclaration(new Name("org.meveo.admin.exception.BusinessException"), false, false));
        cu.getImports().add(new ImportDeclaration(new Name(CUSTOM_ENDPOINT_BASE_RESOURCE_PACKAGE), false, false));

        if (endPointDtoClass != null) {
            StringBuilder endpointDtoclasspackage = new StringBuilder("org.meveo.").append(moduleCode)
                                                                                   .append(".dto." + endPointDtoClass);
            cu.getImports().add(new ImportDeclaration(new Name(endpointDtoclasspackage.toString()), false, false));
        }

        cu.getImports().add(new ImportDeclaration(new Name(endPoint.getService().getCode()), false, false));

        String injectedFieldName = getNonCapitalizeNameWithPrefix(serviceCode);
        ClassOrInterfaceDeclaration clazz = generateRestClass(cu, endPointCode, httpMethod, endPoint.getBasePath(),
                serviceCode, injectedFieldName);
        MethodDeclaration restMethodSignature = generateRestMethodSignature(endPoint, clazz, httpMethod,
                endPoint.getPath(), endPointDtoClass, endPoint.getContentType());

        VariableDeclarator var_result = new VariableDeclarator();

        BlockStmt beforeTrybBlockStmt = generateBeforeTryBlockStmt(endPoint, var_result, endPointDtoClass);
        Statement tryBlockstatement = generateTryBlock(endPoint, var_result, injectedFieldName, endPointDtoClass);
        beforeTrybBlockStmt.addStatement(tryBlockstatement);

        restMethodSignature.setBody(beforeTrybBlockStmt);
        restMethodSignature.getBody().get().getStatements().add(getReturnType());

        return cu.toString();
    }

    /**
     * Exmaple :  parameterMap.put("product", createProductRSDto.getProduct());
     *
     * @param endPoint
     * @param var_result
     * @param endPointDtoClass
     * @return
     */
    private BlockStmt generateBeforeTryBlockStmt(Endpoint endPoint, VariableDeclarator var_result,
            String endPointDtoClass) {
        BlockStmt beforeTryblock = new BlockStmt();

        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endPoint.getService().getCode());
        for (Accessor getter : scriptInstance.getGetters()) {
            var_result.setName(getter.getName());
            var_result.setType(getter.getType());
        }
        var_result.setInitializer(new NullLiteralExpr());

        NodeList<VariableDeclarator> var_result_declarator = new NodeList<>();
        var_result_declarator.add(var_result);
        beforeTryblock.addStatement(
                new ExpressionStmt().setExpression(new VariableDeclarationExpr().setVariables(var_result_declarator)));

        beforeTryblock.addStatement(new ExpressionStmt(new NameExpr("parameterMap = new HashMap<String, Object>()")));

        List<TSParameterMapping> parametersMappings = endPoint.getParametersMappingNullSafe();
        List<EndpointPathParameter> pathParameters = endPoint.getPathParametersNullSafe();

        if (endPoint.getMethod().getLabel().equalsIgnoreCase("POST") ||
                endPoint.getMethod().getLabel().equalsIgnoreCase("PUT")) {

            for (TSParameterMapping parameterMapping : parametersMappings) {
                MethodCallExpr getEntity_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
                getEntity_methodCall.addArgument(
                        new StringLiteralExpr(getNonCapitalizeName(parameterMapping.getParameterName())));
                getEntity_methodCall.addArgument(
                        new MethodCallExpr(new NameExpr(getNonCapitalizeName(endPointDtoClass)),
                                getterMethodCall(parameterMapping.getParameterName())));

                beforeTryblock.addStatement(getEntity_methodCall);
            }

        }

        if (endPoint.getMethod().getLabel().equalsIgnoreCase("GET") ||
                endPoint.getMethod().getLabel().equalsIgnoreCase("DELETE")) {

            for (TSParameterMapping parameterMapping : parametersMappings) {
                MethodCallExpr getPathParametermethodcall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
                getPathParametermethodcall.addArgument(new StringLiteralExpr(parameterMapping.getParameterName()));
                getPathParametermethodcall.addArgument(new StringLiteralExpr(parameterMapping.getParameterName()));

                beforeTryblock.addStatement(getPathParametermethodcall);
            }
        }

        //parameterMap.put("uuid", "uuid");
        for (EndpointPathParameter endpointPathParameter : pathParameters) {
            MethodCallExpr getPathParametermethodcall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
            getPathParametermethodcall.addArgument(new StringLiteralExpr(endpointPathParameter.toString()));
            getPathParametermethodcall.addArgument(new StringLiteralExpr(endpointPathParameter.toString()));

            beforeTryblock.addStatement(getPathParametermethodcall);
        }

        beforeTryblock.addStatement(new ExpressionStmt(new MethodCallExpr(SET_REQUEST_RESPONSE_METHOD)));
        return beforeTryblock;
    }

    /**
     * Create Rest class
     *
     * @param cu
     * @param endPointCode
     * @param httpMethod
     * @param httpBasePath
     * @param serviceCode
     * @param injectedFieldName
     * @return
     */
    private ClassOrInterfaceDeclaration generateRestClass(CompilationUnit cu, String endPointCode, String httpMethod,
            String httpBasePath, String serviceCode, String injectedFieldName) {
        ClassOrInterfaceDeclaration clazz = cu.addClass(endPointCode, Modifier.Keyword.PUBLIC);
        clazz.addSingleMemberAnnotation("Path", new StringLiteralExpr(httpBasePath));
        clazz.addMarkerAnnotation("RequestScoped");
        var injectedfield = clazz.addField(serviceCode, injectedFieldName, Modifier.Keyword.PRIVATE);
        injectedfield.addMarkerAnnotation("Inject");

        NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
        extendsList.add(new ClassOrInterfaceType().setName(new SimpleName(CUSTOM_ENDPOINT_RESOURCE)));
        clazz.setExtendedTypes(extendsList);
        return clazz;
    }

    /**
     * Example : public Response execute(CreateProductRSDto createProductRSDto)
     *
     * @param endPoint
     * @param clazz
     * @param httpMethod
     * @param path
     * @param endPointDtoClass
     * @param contentType
     * @return
     */
    private MethodDeclaration generateRestMethodSignature(Endpoint endPoint, ClassOrInterfaceDeclaration clazz,
            String httpMethod, String path, String endPointDtoClass, String contentType) {
        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endPoint.getService().getCode());
        MethodDeclaration restMethod = clazz.addMethod("execute", Modifier.Keyword.PUBLIC);
        restMethod.setType("Response");
        restMethod.addMarkerAnnotation(httpMethod);
        StringBuilder pathinfo = new StringBuilder();

        List<TSParameterMapping> parametersMappings = endPoint.getParametersMappingNullSafe();
        List<EndpointPathParameter> pathParameters = endPoint.getPathParametersNullSafe();

        if (endPointDtoClass != null) {
            restMethod.addParameter(endPointDtoClass, getNonCapitalizeName(endPointDtoClass));
        }
        //parameter :, @PathParam("uuid") String uuid
        for (EndpointPathParameter endpointPathParameter : pathParameters) {
            Parameter restMethodParameter = new Parameter();
            //Identify type of path parameter : @PathParam("uuid") String uuid
            String pathParameterType = scriptInstance.getSetters().stream()
                                                     .filter(p -> p.getName()
                                                                   .equalsIgnoreCase(endpointPathParameter.toString()))
                                                     .map(p -> p.getType())
                                                     .findFirst().get();
            restMethodParameter.setType(pathParameterType);
            restMethodParameter.setName(endpointPathParameter.toString());
            restMethodParameter.addSingleMemberAnnotation("PathParam",
                    new StringLiteralExpr(endpointPathParameter.toString()));
            restMethod.addParameter(restMethodParameter);
            restMethodParameter = null;

            pathinfo.append("/{").append(endpointPathParameter.toString()).append("}");

        }
        // @Path("/{uuid}")
        if (!pathParameters.isEmpty()) {
            restMethod.addSingleMemberAnnotation("Path", new StringLiteralExpr(pathinfo.toString()));
        }

        if (endPoint.getMethod().getLabel().equalsIgnoreCase("GET")) {

            for (TSParameterMapping queryMapping : parametersMappings) {
                Parameter restMethodParameter = new Parameter();
                String pathParameterType = scriptInstance.getSetters().stream()
                                                         .filter(p -> p.getName().equalsIgnoreCase(
                                                                 queryMapping.getParameterName()))
                                                         .map(p -> p.getType())
                                                         .findFirst().get();
                restMethodParameter.setType(pathParameterType);
                restMethodParameter.setName(queryMapping.getParameterName());
                restMethodParameter.addSingleMemberAnnotation("QueryParam",
                        new StringLiteralExpr(queryMapping.getParameterName()));
                restMethod.addParameter(restMethodParameter);
                restMethodParameter = null;
            }
        }

        if (contentType.equalsIgnoreCase("application/json")) {
            restMethod.addSingleMemberAnnotation("Produces", "MediaType.APPLICATION_JSON");
            restMethod.addSingleMemberAnnotation("Consumes", "MediaType.APPLICATION_JSON");
        }

        return restMethod;

    }

    /**
     * call meveo script setter ,init ,execute,finalize,getResult method
     *
     * @param endPoint
     * @param assignmentVariable
     * @param injectedFieldName
     * @param endPointDtoClass
     * @return
     */
    private Statement generateTryBlock(Endpoint endPoint, VariableDeclarator assignmentVariable,
            String injectedFieldName, String endPointDtoClass) {
        BlockStmt tryblock = new BlockStmt();
        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endPoint.getService().getCode());

        List<TSParameterMapping> parametersMappings = endPoint.getParametersMappingNullSafe();
        List<EndpointPathParameter> pathParameters = endPoint.getPathParametersNullSafe();

        if (endPoint.getMethod().getLabel().equalsIgnoreCase("POST") ||
                endPoint.getMethod().getLabel().equalsIgnoreCase("PUT")) {

            for (TSParameterMapping queryMapping : parametersMappings) {
                tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName),
                        setterMethodCall(queryMapping.getParameterName()))
                        .addArgument(new MethodCallExpr(new NameExpr(getNonCapitalizeName(endPointDtoClass)),
                                getterMethodCall(queryMapping.getParameterName()))));
            }
        }

        if (endPoint.getMethod().getLabel().equalsIgnoreCase("GET") ||
                endPoint.getMethod().getLabel().equalsIgnoreCase("DELETE")) {

            for (TSParameterMapping queryMapping : parametersMappings) {
                tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName),
                        setterMethodCall(queryMapping.getParameterName()))
                        .addArgument(queryMapping.getParameterName()));
            }
        }

        for (EndpointPathParameter pathparam : pathParameters) {
            tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName),
                    setterMethodCall(pathparam.getEndpointParameter().getParameter()))
                    .addArgument(pathparam.getEndpointParameter().getParameter()));
        }

        tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "init").addArgument("parameterMap"));
        tryblock.addStatement(
                new MethodCallExpr(new NameExpr(injectedFieldName), "execute").addArgument("parameterMap"));
        tryblock.addStatement(
                new MethodCallExpr(new NameExpr(injectedFieldName), "finalize").addArgument("parameterMap"));
        tryblock.addStatement(assignment(assignmentVariable.getNameAsString(), injectedFieldName, "getResult"));
        Statement trystatement = addingException(tryblock);
        return trystatement;
    }

    private ReturnStmt getReturnType() {
        return new ReturnStmt(new NameExpr("Response.status(Response.Status.OK).entity(result).build()"));
    }

    /**
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
        cb.addStatement(new ExpressionStmt(
                new NameExpr("return Response.status(Response.Status.BAD_REQUEST).entity(result).build()")));
        cc.setBody(cb);
        ts.setCatchClauses(new NodeList<>(cc));

        return ts;
    }

    /**
     * Examp:  result = _createMyProduct.getResult();
     *
     * @param assignOject : result
     * @param callOBject  : _createMyProduct
     * @param methodName  : getResult()
     * @return
     */
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
        String prefix = "_";
        if (className == null || className.length() == 0)
            return className;
        String objectReferenceName = prefix + className.substring(0, 1).toLowerCase() + className.substring(1);
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
     * input  : personname
     * return : setPersonname
     */
    String setterMethodCall(String fieldName) {
        String fieldNameUpper = fieldName.toUpperCase().substring(0, 1) + fieldName.substring(1, fieldName.length());
        String methodCaller = "set" + fieldNameUpper;
        return methodCaller;

    }

    /*
     * input  : personname
     * return : getPersonname
     */
    String getterMethodCall(String fieldName) {
        String fieldNameUpper = fieldName.toUpperCase().substring(0, 1) + fieldName.substring(1, fieldName.length());
        String methodCaller = "get" + fieldNameUpper;
        return methodCaller;

    }

    /*
     * input  : createMyProduct
     * return : CreateMyProduct
     */
    public String capitalize(String str) {
        str = str.replaceAll("[^a-zA-Z0-9]", " ").trim();
        if (str == null || str.isEmpty()) {
            return str;
        }

        return str.substring(0, 1).toUpperCase() + str.substring(1).trim();
    }

    /*
     * read a desiredTagContent from xml file
     */
    private static String copyXmlTagContent(String xmlFilePath, String tagToKeep) {
        String xmlContent = null;
        try {
            xmlContent = Files.readString(Paths.get(xmlFilePath));
        } catch (IOException e) {
            LOG.error("Failed to read xml file: {}", xmlFilePath, e);
        }
        // Extract the content of the desired tag
        int startTagIndex = xmlContent.indexOf("<" + tagToKeep);
        int endTagIndex = xmlContent.indexOf("</" + tagToKeep + ">", startTagIndex);

        if (startTagIndex != -1 && endTagIndex != -1) {
            return xmlContent.substring(startTagIndex, endTagIndex + tagToKeep.length() + 3);
        }
        return null;
    }

    /*
     * copy files (CustomEndpointResource.java, beans.xml, pom.xml) into project directory
     */
    private List<File> templateFileCopy(String moduleCode, Path webappTemplatePath, Path moduleWebAppPath,
            String repositoriesTagContent) throws BusinessException {
        List<File> filesToCommit = new ArrayList<>();

        try (Stream<Path> sourceStream = Files.walk(webappTemplatePath)) {
            List<Path> sources = sourceStream.collect(Collectors.toList());
            List<Path> destinations = sources.stream().map(webappTemplatePath::relativize)
                                             .map(moduleWebAppPath::resolve).collect(Collectors.toList());
            for (int index = 0; index < sources.size(); index++) {
                Path sourcePath = sources.get(index);
                Path destinationPath = destinations.get(index);

                if (sourcePath.toString().contains(CUSTOM_ENDPOINT_RESOURCE_FILE)
                        || sourcePath.toString().contains(CDI_BEAN_FILE)
                        || sourcePath.toString().contains(MAVEN_EE_POM_FILE)
                        || sourcePath.toString().contains(DEPLOYMENT_SCRIPT_FILE)) {

                    try {
                        File outputFile = new File(destinationPath.toString());
                        File inputfile = new File(sourcePath.toString());
                        String inputContent = FileUtils.readFileToString(inputfile, StandardCharsets.UTF_8.name());

                        if (sourcePath.toString().contains(MAVEN_EE_POM_FILE)) {
                            String outputContent = inputContent
                                    .replace("__MODULE_ARTIFACT_ID__", moduleCode)
                                    .replace("__MODULE_VERSION__", MODULE_VERSION)
                                    .replace("__MEVEO_VERSION__", Version.appVersion)
                                    .replace("<!--REPOSITORY_LIST-->", repositoriesTagContent);
                            FileUtils.write(outputFile, outputContent, StandardCharsets.UTF_8);
                        } else {
                            FileUtils.write(outputFile, inputContent, StandardCharsets.UTF_8);
                        }

                        if (sourcePath.toString().contains(DEPLOYMENT_SCRIPT_FILE)) {
                            String wildflyPath = System.getProperty("jboss.home.dir");
                            String outputContent = inputContent
                                    .replace("__MODULE_CODE__", moduleCode)
                                    .replace("__WILDFLY_PATH__", wildflyPath);
                            FileUtils.write(outputFile, outputContent, StandardCharsets.UTF_8);
                        }

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
