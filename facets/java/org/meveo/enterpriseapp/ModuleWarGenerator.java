package org.meveo.enterpriseapp;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.apache.commons.lang3.StringUtils.*;

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
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.JavaEnterpriseApp;
import org.meveo.model.git.GitRepository;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
import org.meveo.model.persistence.CEIUtils;
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
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModuleWarGenerator extends Script {
    private static final Logger LOG = LoggerFactory.getLogger(ModuleWarGenerator.class);

    private static final String PATH_SEPARATORS = "/\\";
    private static final String MODULE_WAR_TEMPLATE_CODE = "module-war-template";
    private static final String MODULE_WAR_TEMPLATE_REPO = "https://github.com/meveo-org/module-war-template.git";
    private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();
    private static final String CUSTOM_ENDPOINT_TEMPLATE = Endpoint.class.getName();
    private static final String CDI_BEAN_FILE = "beans.xml";
    private static final String CUSTOM_ENDPOINT_RESOURCE_FILE = "CustomEndpointResource.java";
    private static final String POM_XML_FILE = "pom.xml";
    private static final String DEPLOYMENT_SCRIPT_FILE = "moduledeployment.sh";
    private static final String SET_REQUEST_RESPONSE_METHOD = "setRequestResponse";
    private static final String CUSTOM_ENDPOINT_RESOURCE = "CustomEndpointResource";
    private static final String CUSTOM_ENDPOINT_BASE_RESOURCE_PACKAGE = "org.meveo.base.CustomEndpointResource";
    private static final String MODULE_VERSION = "1.0.0";
    private static final String DIVIDER = repeat("-", 15);

    private final ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
    private final ParamBean config = paramBeanFactory.getInstance();
    private final GitClient gitClient = getCDIBean(GitClient.class);
    private final GitRepositoryService gitRepositoryService = getCDIBean(GitRepositoryService.class);
    private final MeveoModuleService meveoModuleService = getCDIBean(MeveoModuleService.class);
    private final ScriptInstanceService scriptInstanceService = getCDIBean(ScriptInstanceService.class);
    private final EndpointService endpointService = getCDIBean(EndpointService.class);

    @Override
    public void execute(Map<String, Object> parameters) throws BusinessException {
        label("ModuleWarGenerator.execute() - START");
        super.execute(parameters);

        CustomEntityInstance cei = (CustomEntityInstance) parameters.get(CONTEXT_ENTITY);

        JavaEnterpriseApp javaEnterpriseApp = CEIUtils.ceiToPojo(cei, JavaEnterpriseApp.class);
        String moduleCode = javaEnterpriseApp.getCode();

        if (isEmpty(moduleCode)) {
            throw new BusinessException("No module code was provided.");
        }

        LOG.info("Generating WAR for module: {}", moduleCode);

        MeveoModule module = meveoModuleService.findByCode(moduleCode);
        MeveoUser user = (MeveoUser) parameters.get(CONTEXT_CURRENT_USER);

        if (module != null) {
            LOG.info("Module: {}, found", module.getCode());

            Set<MeveoModuleItem> moduleItems = module.getModuleItems();
            List<String> entityCodes = moduleItems.stream().filter(item -> CUSTOM_TEMPLATE.equals(item.getItemClass()))
                                                  .map(MeveoModuleItem::getItemCode).collect(Collectors.toList());
            LOG.info("Entity codes: {}", entityCodes);

            GitRepository templateRepository = getGitRepository(MODULE_WAR_TEMPLATE_CODE, MODULE_WAR_TEMPLATE_REPO);
            File templateDirectory = GitHelper.getRepositoryDir(user, templateRepository);
            Path templatePath = templateDirectory.toPath();
            LOG.info("Module template path: {}", templatePath);

            GitRepository moduleRepo = gitRepositoryService.findByCode(moduleCode);
            File moduleDirectory = GitHelper.getRepositoryDir(user, moduleRepo);

            String moduleWARCode = moduleCode + "-war";
            GitRepository moduleWARRepo = getGitRepository(moduleWARCode, null);

            File generatedFilesDirectory = GitHelper.getRepositoryDir(user, moduleWARRepo);
            Path generatedFilesPath = generatedFilesDirectory.toPath();

            if (generatedFilesDirectory.exists()) {
                try {
                    FileUtils.deleteDirectory(generatedFilesDirectory);
                    Files.createDirectories(generatedFilesPath);
                } catch (IOException e) {
                    throw new BusinessException("Failed to delete and recreate module war repo folder");
                }
            }

            List<File> filesToCommit = new ArrayList<>();
            Path moduleSourceDirectory = Paths.get(moduleDirectory.getAbsolutePath() + "/facets/java");
            Path moduleWARSourceDirectory = Paths.get(generatedFilesDirectory.getAbsolutePath() + "/src/main/java");
            try (Stream<Path> sourceStream = Files.walk(moduleSourceDirectory)) {
                List<Path> sources = sourceStream.collect(Collectors.toList());
                List<Path> destinations = sources.stream()
                                                 .map(moduleSourceDirectory::relativize)
                                                 .map(moduleWARSourceDirectory::resolve)
                                                 .collect(Collectors.toList());
                for (int index = 0; index < sources.size(); index++) {
                    Path sourcePath = sources.get(index);
                    Path destinationPath = destinations.get(index);
                    File sourceFile = sourcePath.toFile();
                    File destinationFile = destinationPath.toFile();
                    if (sourceFile.isDirectory()) {
                        Files.createDirectories(destinationFile.toPath());
                        continue;
                    }
                    Files.copy(sourcePath, destinationPath, REPLACE_EXISTING, COPY_ATTRIBUTES);
                    LOG.info("Successfully copied: {} to: {}", sourcePath, destinationPath);
                    filesToCommit.add(destinationFile);
                }
            } catch (IOException e) {
                throw new BusinessException("Failed to copy files from module repo to module war repo.", e);
            }

            String normalizedCode = toPascalCase(moduleCode);

            label("RESTConfiguration file generation");
            String restConfigurationPath = "src/main/java/org/meveo/" + normalizedCode
                    + "/rest/" + normalizedCode + "RestConfig" + ".java";
            LOG.info("Rest configuration file: {}", restConfigurationPath);
            try {
                File restConfigfile = new File(generatedFilesDirectory, restConfigurationPath);
                String restConfigurationFileContent = generateRESTConfigurationClass(normalizedCode);
                FileUtils.write(restConfigfile, restConfigurationFileContent, StandardCharsets.UTF_8);
                LOG.info("Successfully created rest configuration file: {}", restConfigfile.getPath());
                filesToCommit.add(restConfigfile);
            } catch (IOException e) {
                throw new BusinessException("Failed creating file." + e.getMessage());
            }

            List<String> endpointCodes = moduleItems
                    .stream()
                    .filter(item -> CUSTOM_ENDPOINT_TEMPLATE.equals(item.getItemClass()))
                    .map(MeveoModuleItem::getItemCode)
                    .collect(Collectors.toList());

            String endpointDTOClass = null;

            for (String endpointCode : endpointCodes) {
                endpointDTOClass = null;
                Endpoint endpoint = endpointService.findByCode(endpointCode);
                if (!endpoint.getParametersMappingNullSafe().isEmpty()) {
                    String methodLabel = endpoint.getMethod().getLabel();
                    if ("POST".equalsIgnoreCase(methodLabel) || "PUT".equalsIgnoreCase(methodLabel)) {
                        label("Endpoint DTO class generation");
                        endpointDTOClass = toPascalCase(endpoint.getCode()) + "DTO";
                        String dtoFilePath = "src/main/java/org/meveo/" + toCamelCase(normalizedCode)
                                + "/dto/" + endpointDTOClass + ".java";
                        try {
                            File dtoFile = new File(generatedFilesDirectory, dtoFilePath);
                            String dtoContent = generateEndpointDTO(normalizedCode, endpoint, endpointDTOClass);
                            FileUtils.write(dtoFile, dtoContent, StandardCharsets.UTF_8);
                            LOG.info("Successfully created endpoint DTO: {}", dtoFile.getPath());
                            filesToCommit.add(dtoFile);
                        } catch (IOException e) {
                            throw new BusinessException("Failed creating file." + e.getMessage());
                        }

                    }
                }

                label("Endpoint Class Generation");
                String endpointClassPath = "src/main/java/org/meveo/" + toCamelCase(normalizedCode)
                        + "/resource/" + toPascalCase(endpoint.getCode()) + ".java";
                LOG.info("Generating endpoint class: {}", endpointClassPath);

                try {
                    File endpointFile = new File(generatedFilesDirectory, endpointClassPath);
                    String endpointContent = generateEndpoint(normalizedCode, endpoint, endpointDTOClass);
                    FileUtils.write(endpointFile, endpointContent, StandardCharsets.UTF_8);
                    LOG.info("Successfully created endpoint class: {}", endpointFile.getPath());
                    filesToCommit.add(endpointFile);
                } catch (IOException e) {
                    throw new BusinessException("Failed creating file." + e.getMessage());
                }

                String tagToKeep = "repositories";

                String pomFilePath = moduleDirectory.getAbsolutePath() + "/facets/maven/" + POM_XML_FILE;
                String repositoriesTagContent = copyXmlTagContent(pomFilePath, tagToKeep);

                List<File> templateFiles = templateFileCopy(moduleCode, templatePath,
                        generatedFilesPath, repositoriesTagContent);
                LOG.info("Successfully copied the following files from the template: {}",
                        templateFiles.stream().map(File::getPath).collect(Collectors.toList()));
                filesToCommit.addAll(templateFiles);
            }

            if (!filesToCommit.isEmpty()) {
                gitClient.commitFiles(moduleWARRepo, filesToCommit, "DTO & Endpoint generation.");
            }

            generateWAR(generatedFilesDirectory.getAbsolutePath());

        } else {
            LOG.warn("Module with code: {} does not exist.", moduleCode);
        }

        label("ModuleWarGenerator.execute() - DONE");
    }

    private static String normalizePath(String path) {
        path = (new File(path)).getAbsolutePath().replaceAll("/\\./", "/");
        path = stripEnd(path, PATH_SEPARATORS);
        path = stripEnd(path, ".");
        return path;
    }

    private GitRepository getGitRepository(String code, String origin) throws BusinessException {
        GitRepository gitRepository = gitRepositoryService.findByCode(code);
        if (gitRepository == null) {
            LOG.info("Create module template repository: {}", code);
            gitRepository = new GitRepository();
            gitRepository.setCode(code);
            gitRepository.setDescription(code + " repository");
            gitRepository.setRemoteOrigin(origin);
            gitRepository.setDefaultRemoteUsername("");
            gitRepository.setDefaultRemotePassword("");
            gitRepositoryService.create(gitRepository);
        } else {
            String remoteOrigin = gitRepository.getRemoteOrigin();
            if (isNotEmpty(remoteOrigin)) {
                gitClient.pull(gitRepository, "", "");
                LOG.info("Successfully updated repository: {}", code);
            } else {
                LOG.info("Git repository: {}, does not have a remote origin.", gitRepository.getCode());
            }
        }
        return gitRepository;
    }

    /*
     * Generate module war file in local repo folder
     */
    private void generateWAR(String modulePath) throws BusinessException {
        String mvnHome = null;

        if (System.getenv("MAVEN_HOME") != null) {
            mvnHome = System.getenv("MAVEN_HOME");
        } else if (System.getenv("M2_HOME") != null) {
            mvnHome = System.getenv("M2_HOME");
        } else if (System.getenv("MVN_HOME") != null) {
            mvnHome = System.getenv("MVN_HOME");
        }
        if (isEmpty(mvnHome)) {
            throw new BusinessException("Failed to retrieve Maven home path");
        }

        LOG.info("Maven Home path: {}", mvnHome);
        runMaven(modulePath, mvnHome, "clean", "package");
    }

    private void runMaven(String mavenEEDirectory, String mvnHome, String... targets) throws BusinessException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(new File(mavenEEDirectory));
        request.setGoals(Arrays.asList(targets));

        try {
            DefaultInvoker invoker = new DefaultInvoker();
            invoker.setMavenHome(new File(mvnHome));
            label("Executing maven {} using pom.xml in: {}", targets, mavenEEDirectory);
            invoker.execute(request);
        } catch (MavenInvocationException e) {
            LOG.error("Failed to mvn package for maven pom.xml", e);
            throw new BusinessException("Failed creating file." + e.getMessage());
        }
    }

    /*
     * Create Symbolic link for Java , JavaEE folder
     */
    private void createSymbolicLink(String targetFilePath, String symbolicLinkPath) {
        try {
            Path path = Paths.get(symbolicLinkPath);
            if (Files.exists(path)) {
                Files.delete(path);
            }

            Path target = FileSystems.getDefault().getPath(targetFilePath);
            Path link = FileSystems.getDefault().getPath(symbolicLinkPath);
            Files.createSymbolicLink(link, target);
            LOG.info("Symbolic link: {} to: {}, successfully created.", link, target);
        } catch (IOException e) {
            LOG.error("Failed to create symbolic link: {} => {}", targetFilePath, symbolicLinkPath, e);
        }
    }

    /**
     * create a DTO class for each endpoint
     *
     * @param moduleCode
     * @param endpoint
     * @param endpointDTOClass
     * @return
     */
    String generateEndpointDTO(String moduleCode, Endpoint endpoint, String endpointDTOClass) {
        CompilationUnit compilationUnit = new CompilationUnit();
        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endpoint.getService().getCode());
        StringBuilder dtoPackage = new StringBuilder("org.meveo.").append(toCamelCase(moduleCode)).append(".dto");
        compilationUnit.setPackageDeclaration(dtoPackage.toString());
        compilationUnit.getImports()
                       .add(new ImportDeclaration(new Name("org.meveo.model.customEntities"), false, true));
        ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(endpointDTOClass).setPublic(true);

        classDeclaration.addConstructor();
        List<TSParameterMapping> parametersMappings = endpoint.getParametersMappingNullSafe();
        Boolean isMultivalued = parametersMappings.stream().map(p -> p.isMultivalued()).findAny().get();

        if (isMultivalued)
            compilationUnit.getImports().add(new ImportDeclaration(new Name("java.util.List"), false, false));

        for (TSParameterMapping parameterMapping : parametersMappings) {

            String pathParameterType = scriptInstance
                    .getSetters().stream()
                    .filter(p -> p.getName().equalsIgnoreCase(parameterMapping.getParameterName()))
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

        LOG.info("Successfully generated endpoint DTO contents for: {}", endpointDTOClass);

        return compilationUnit.toString();
    }

    /**
     * Generate REST Configuration class
     *
     * @param moduleCode
     * @return
     */
    String generateRESTConfigurationClass(String moduleCode) {
        CompilationUnit compilationUnit = new CompilationUnit();
        compilationUnit.setPackageDeclaration("org.meveo." + moduleCode + ".rest");
        compilationUnit.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.ApplicationPath"), false, false));
        compilationUnit.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.core.Application"), false, false));

        String className = moduleCode + "RestConfig";
        ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(className).setPublic(true);
        classDeclaration.addSingleMemberAnnotation("ApplicationPath", new StringLiteralExpr("rest"));

        NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
        extendsList.add(new ClassOrInterfaceType().setName(new SimpleName("Application")));
        classDeclaration.setExtendedTypes(extendsList);

        LOG.info("Successfully generated REST configuration class: {}", className);

        return compilationUnit.toString();
    }

    /**
     * Generate EndPoint class
     *
     * @param endpoint
     * @param endpointDTOClass
     * @param moduleCode
     * @return
     */
    public String generateEndpoint(String moduleCode, Endpoint endpoint, String endpointDTOClass) {
        String endpointCode = endpoint.getCode();
        String httpMethod = endpoint.getMethod().getLabel();
        String serviceCode = getServiceCode(endpoint.getService().getCode());

        CompilationUnit cu = new CompilationUnit();
        String modulePackage = "org.meveo." + toCamelCase(moduleCode);
        String resourcePackage = modulePackage + ".resource";
        cu.setPackageDeclaration(resourcePackage);
        cu.getImports().add(new ImportDeclaration(new Name("java.time"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("java.util"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs.core"), false, true));
        cu.getImports().add(new ImportDeclaration(new Name("javax.enterprise.context.RequestScoped"), false, false));
        cu.getImports().add(new ImportDeclaration(new Name("javax.inject.Inject"), false, false));
        cu.getImports()
          .add(new ImportDeclaration(new Name("org.meveo.admin.exception.BusinessException"), false, false));
        cu.getImports().add(new ImportDeclaration(new Name(CUSTOM_ENDPOINT_BASE_RESOURCE_PACKAGE), false, false));

        if (endpointDTOClass != null) {
            String dtoClassName = modulePackage + ".dto." + endpointDTOClass.toString();
            cu.getImports().add(new ImportDeclaration(new Name(dtoClassName), false, false));
        }

        cu.getImports().add(new ImportDeclaration(new Name(endpoint.getService().getCode()), false, false));

        String injectedFieldName = "_" + toCamelCase(serviceCode);
        ClassOrInterfaceDeclaration clazz = generateRESTClass(cu, toPascalCase(endpointCode), endpoint.getBasePath(),
                serviceCode, injectedFieldName);
        MethodDeclaration restMethodSignature = generateRESTMethodSignature(endpoint, clazz, httpMethod,
                endpointDTOClass, endpoint.getContentType());

        VariableDeclarator var_result = new VariableDeclarator();

        BlockStmt beforeTrybBlockStmt = generateBeforeTryBlockStmt(endpoint, var_result, endpointDTOClass);
        Statement tryBlockstatement = generateTryBlock(endpoint, var_result, injectedFieldName, endpointDTOClass);
        beforeTrybBlockStmt.addStatement(tryBlockstatement);

        restMethodSignature.setBody(beforeTrybBlockStmt);
        restMethodSignature.getBody().get().getStatements().add(getReturnType());

        LOG.info("Successfully generated endpoint: {} - {}", httpMethod, endpointCode);

        return cu.toString();
    }

    /**
     * Exmaple :  parameterMap.put("product", createProductRSDTO.getProduct());
     *
     * @param endpoint
     * @param var_result
     * @param endpointDTOClass
     * @return
     */
    private BlockStmt generateBeforeTryBlockStmt(Endpoint endpoint, VariableDeclarator var_result,
            String endpointDTOClass) {
        BlockStmt beforeTryBlock = new BlockStmt();

        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endpoint.getService().getCode());
        for (Accessor getter : scriptInstance.getGetters()) {
            var_result.setName(getter.getName());
            var_result.setType(getter.getType());
        }
        var_result.setInitializer(new NullLiteralExpr());

        NodeList<VariableDeclarator> var_result_declarator = new NodeList<>();
        var_result_declarator.add(var_result);
        beforeTryBlock.addStatement(
                new ExpressionStmt().setExpression(new VariableDeclarationExpr().setVariables(var_result_declarator)));

        beforeTryBlock.addStatement(new ExpressionStmt(new NameExpr("parameterMap = new HashMap<String, Object>()")));

        List<TSParameterMapping> parametersMappings = endpoint.getParametersMappingNullSafe();
        List<EndpointPathParameter> pathParameters = endpoint.getPathParametersNullSafe();

        if (endpoint.getMethod().getLabel().equalsIgnoreCase("POST") ||
                endpoint.getMethod().getLabel().equalsIgnoreCase("PUT")) {

            for (TSParameterMapping parameterMapping : parametersMappings) {
                MethodCallExpr getEntity_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
                getEntity_methodCall.addArgument(
                        new StringLiteralExpr(toCamelCase(parameterMapping.getParameterName())));
                getEntity_methodCall.addArgument(
                        new MethodCallExpr(new NameExpr(toCamelCase(endpointDTOClass)),
                                getterMethodCall(parameterMapping.getParameterName())));

                beforeTryBlock.addStatement(getEntity_methodCall);
            }

        }

        String methodLabel = endpoint.getMethod().getLabel();
        if ("GET".equalsIgnoreCase(methodLabel) || "DELETE".equalsIgnoreCase(methodLabel)) {
            for (TSParameterMapping parameterMapping : parametersMappings) {
                MethodCallExpr getPathParametermethodcall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
                getPathParametermethodcall.addArgument(new StringLiteralExpr(parameterMapping.getParameterName()));
                getPathParametermethodcall.addArgument(new StringLiteralExpr(parameterMapping.getParameterName()));

                beforeTryBlock.addStatement(getPathParametermethodcall);
            }
        }

        for (EndpointPathParameter endpointPathParameter : pathParameters) {
            MethodCallExpr getPathParametermethodcall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
            getPathParametermethodcall.addArgument(new StringLiteralExpr(endpointPathParameter.toString()));
            getPathParametermethodcall.addArgument(new StringLiteralExpr(endpointPathParameter.toString()));

            beforeTryBlock.addStatement(getPathParametermethodcall);
        }

        beforeTryBlock.addStatement(new ExpressionStmt(new MethodCallExpr(SET_REQUEST_RESPONSE_METHOD)));
        return beforeTryBlock;
    }

    /**
     * Create REST class
     *
     * @param cu
     * @param endpointCode
     * @param httpBasePath
     * @param serviceCode
     * @param injectedFieldName
     * @return
     */
    private ClassOrInterfaceDeclaration generateRESTClass(CompilationUnit cu, String endpointCode,
            String httpBasePath, String serviceCode, String injectedFieldName) {
        ClassOrInterfaceDeclaration clazz = cu.addClass(endpointCode, Modifier.Keyword.PUBLIC);
        clazz.addSingleMemberAnnotation("Path", new StringLiteralExpr(httpBasePath));
        clazz.addMarkerAnnotation("RequestScoped");
        var injectedfield = clazz.addField(serviceCode, injectedFieldName, Modifier.Keyword.PRIVATE);
        injectedfield.addMarkerAnnotation("Inject");

        NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
        extendsList.add(new ClassOrInterfaceType().setName(new SimpleName(CUSTOM_ENDPOINT_RESOURCE)));
        clazz.setExtendedTypes(extendsList);

        LOG.info("Successfully generated REST class: {}", clazz.getNameAsString());

        return clazz;
    }

    /**
     * Example : public Response execute(CreateProductRSDTO createProductRSDTO)
     *
     * @param endpoint
     * @param clazz
     * @param httpMethod
     * @param endpointDTOClass
     * @param contentType
     * @return
     */
    private MethodDeclaration generateRESTMethodSignature(Endpoint endpoint, ClassOrInterfaceDeclaration clazz,
            String httpMethod, String endpointDTOClass, String contentType) {
        ScriptInstance scriptInstance = scriptInstanceService.findByCode(endpoint.getService().getCode());
        MethodDeclaration restMethod = clazz.addMethod("execute", Modifier.Keyword.PUBLIC);
        restMethod.setType("Response");
        restMethod.addMarkerAnnotation(httpMethod);
        StringBuilder pathinfo = new StringBuilder();

        List<TSParameterMapping> parametersMappings = endpoint.getParametersMappingNullSafe();
        List<EndpointPathParameter> pathParameters = endpoint.getPathParametersNullSafe();

        if (endpointDTOClass != null) {
            restMethod.addParameter(endpointDTOClass, toCamelCase(endpointDTOClass));
        }

        for (EndpointPathParameter endpointPathParameter : pathParameters) {
            Parameter restMethodParameter = new Parameter();
            String parameterName = endpointPathParameter.toString();
            String pathParameterType = scriptInstance.getSetters().stream()
                                                     .filter(p -> p.getName()
                                                                   .equalsIgnoreCase(parameterName))
                                                     .map(p -> p.getType())
                                                     .findFirst().get();
            restMethodParameter.setType(pathParameterType);
            restMethodParameter.setName(parameterName);
            restMethodParameter.addSingleMemberAnnotation("PathParam", new StringLiteralExpr(parameterName));

            restMethod.addParameter(restMethodParameter);

            pathinfo.append("/{").append(endpointPathParameter).append("}");

        }

        if (!pathParameters.isEmpty()) {
            restMethod.addSingleMemberAnnotation("Path", new StringLiteralExpr(pathinfo.toString()));
        }

        if (endpoint.getMethod().getLabel().equalsIgnoreCase("GET")) {

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

        LOG.info("Successfully generated REST method signature: {}", restMethod.getDeclarationAsString(true, true));

        return restMethod;

    }

    /**
     * call meveo script setter ,init ,execute,finalize,getResult method
     *
     * @param endpoint
     * @param assignmentVariable
     * @param injectedFieldName
     * @param endpointDTOClass
     * @return
     */
    private Statement generateTryBlock(Endpoint endpoint, VariableDeclarator assignmentVariable,
            String injectedFieldName, String endpointDTOClass) {
        BlockStmt tryBlock = new BlockStmt();

        List<TSParameterMapping> parametersMappings = endpoint.getParametersMappingNullSafe();
        List<EndpointPathParameter> pathParameters = endpoint.getPathParametersNullSafe();

        String methodLabel = endpoint.getMethod().getLabel();
        if ("POST".equalsIgnoreCase(methodLabel) || "PUT".equalsIgnoreCase(methodLabel)) {

            for (TSParameterMapping queryMapping : parametersMappings) {
                tryBlock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName),
                        setterMethodCall(queryMapping.getParameterName()))
                        .addArgument(new MethodCallExpr(new NameExpr(toCamelCase(endpointDTOClass)),
                                getterMethodCall(queryMapping.getParameterName()))));
            }
        }

        if (endpoint.getMethod().getLabel().equalsIgnoreCase("GET") ||
                endpoint.getMethod().getLabel().equalsIgnoreCase("DELETE")) {

            for (TSParameterMapping queryMapping : parametersMappings) {
                tryBlock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName),
                        setterMethodCall(queryMapping.getParameterName()))
                        .addArgument(queryMapping.getParameterName()));
            }
        }

        for (EndpointPathParameter pathparam : pathParameters) {
            tryBlock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName),
                    setterMethodCall(pathparam.getEndpointParameter().getParameter()))
                    .addArgument(pathparam.getEndpointParameter().getParameter()));
        }

        tryBlock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "init").addArgument("parameterMap"));
        tryBlock.addStatement(
                new MethodCallExpr(new NameExpr(injectedFieldName), "execute").addArgument("parameterMap"));
        tryBlock.addStatement(
                new MethodCallExpr(new NameExpr(injectedFieldName), "finalize").addArgument("parameterMap"));
        tryBlock.addStatement(createAssignment(assignmentVariable.getNameAsString(), injectedFieldName, "getResult"));
        return addingException(tryBlock);
    }

    private ReturnStmt getReturnType() {
        return new ReturnStmt(new NameExpr("Response.status(Response.Status.OK).entity(result).build()"));
    }

    /**
     * @param serviceCode
     * @return
     */
    private String getServiceCode(String serviceCode) {
        return serviceCode.substring(serviceCode.lastIndexOf(".") + 1);
    }

    private Statement addingException(BlockStmt body) {
        TryStmt tryStmt = new TryStmt();
        tryStmt.setTryBlock(body);
        CatchClause cc = new CatchClause();
        String exceptionName = "e";
        cc.setParameter(new Parameter().setName(exceptionName).setType(BusinessException.class));
        BlockStmt cb = new BlockStmt();
        cb.addStatement(new ExpressionStmt(
                new NameExpr("return Response.status(Response.Status.BAD_REQUEST).entity(result).build()")));
        cc.setBody(cb);
        tryStmt.setCatchClauses(new NodeList<>(cc));

        return tryStmt;
    }

    /**
     * @param assignObject
     * @param callObject
     * @param methodName
     * @return
     */
    private Statement createAssignment(String assignObject, String callObject, String methodName) {
        MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(callObject), methodName);
        AssignExpr assignExpr = new AssignExpr(new NameExpr(assignObject), methodCallExpr, AssignExpr.Operator.ASSIGN);
        return new ExpressionStmt(assignExpr);
    }

    private String toPascalCase(String name) {
        String[] words = name.trim().split("\\W+");
        StringBuilder normalizedCode = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                normalizedCode.append(Character.toUpperCase(word.charAt(0)))
                              .append(word.substring(1));
            }
        }
        return normalizedCode.toString();
    }

    /*
     * input  : CreateMyProduct
     * return : createMyProduct
     */
    private String toCamelCase(String name) {
        String normalizedName = toPascalCase(name);
        return normalizedName.substring(0, 1).toLowerCase() + normalizedName.substring(1);
    }

    /*
     * input  : personName
     * return : setPersonName
     */
    String setterMethodCall(String fieldName) {
        return "set" + toPascalCase(fieldName);
    }

    /*
     * input  : personName
     * return : getPersonName
     */
    String getterMethodCall(String fieldName) {
        return "get" + toPascalCase(fieldName);

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
                        || sourcePath.toString().contains(POM_XML_FILE)
                        || sourcePath.toString().contains(DEPLOYMENT_SCRIPT_FILE)) {

                    try {
                        File outputFile = new File(destinationPath.toString());
                        File inputfile = new File(sourcePath.toString());
                        String inputContent = FileUtils.readFileToString(inputfile, StandardCharsets.UTF_8.name());

                        if (sourcePath.toString().contains(POM_XML_FILE)) {
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

    private void label(String labelString, Object... params) {
        LOG.info(DIVIDER + " " + labelString + " " + DIVIDER, params);
    }

}
