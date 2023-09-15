package org.meveo.enterpriseapp;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.meveo.admin.exception.BusinessException;
import org.meveo.commons.utils.ParamBean;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.service.script.Script;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeploymentJavaEnterpriseApplication extends Script {
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentJavaEnterpriseApplication.class);
    private final ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
    private final ParamBean config = paramBeanFactory.getInstance();
    private static final String WARFILE_NOTFOUND = "Module war file not found";
    private static final String DEPLOYMENT_FAILED = "Deployment failed";
    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final String[] PATH_SEPARATORS = { "/", "\\" };

    private String moduleCode;

    public void setModuleCode(String moduleCode) {
        this.moduleCode = moduleCode;
    }

    @Override
    public void execute(Map<String, Object> parameters) throws BusinessException {
        LOG.info("START - Deploying {} module war", moduleCode);
        deploymentOfModule(moduleCode);
        LOG.info("END - Deploying {} module war", moduleCode);
    }

    private String trimStart(String string, String... prefixes) {
        if (StringUtils.isBlank(string)) {
            return string;
        }

        if (StringUtils.startsWithAny(string, prefixes)) {
            String prefix = Arrays.stream(prefixes)
                                  .filter(prefixToRemove -> StringUtils.startsWith(string, prefixToRemove))
                                  .findFirst()
                                  .orElse(null);
            if (StringUtils.isNotBlank(prefix)) {
                return trimStart(StringUtils.removeStart(string, prefix), prefixes);
            }
        }
        return string;
    }

    private String trimEnd(String string, String... suffixes) {
        if (StringUtils.isBlank(string)) {
            return string;
        }

        if (StringUtils.endsWithAny(string, suffixes)) {
            String suffix = Arrays.stream(suffixes)
                                  .filter(suffixToRemove -> StringUtils.endsWith(string, suffixToRemove))
                                  .findFirst()
                                  .orElse(null);
            if (StringUtils.isNotBlank(suffix)) {
                return trimEnd(StringUtils.removeEnd(string, suffix), suffixes);
            }
        }
        return string;
    }

    private String normalizeDirectory(String directoryPath) {
        if (StringUtils.isBlank(directoryPath)) {
            throw new RuntimeException("Directory path must not be empty.");
        }
        String directory = StringUtils.trim(directoryPath);
        directory = trimStart(directory, PATH_SEPARATORS);
        directory = trimEnd(directory, PATH_SEPARATORS);
        return directory;
    }

    private String buildMavenEEPath(String meveoDataPath, String providerCode) {
        return String.join(File.separator, meveoDataPath, providerCode, "git", "facets", "mavenee");
    }

    private String buildWarPath(String meveoDataPath, String providerCode) {
        return String.join(File.separator, buildMavenEEPath(meveoDataPath, providerCode), "target",
                moduleCode + ".war");
    }

    private String buildDeploymentScriptPath(String meveoDataPath, String providerCode) {
        return String.join(File.separator, buildMavenEEPath(meveoDataPath, providerCode), "moduledeployment.sh");
    }

    private String buildXmlContent() {
        return String.join(LINE_SEPARATOR, LINE_SEPARATOR,
                "  <module id=\"war.meveo." + moduleCode + "\">",
                "    <web>",
                "      <web-uri>" + moduleCode + ".war</web-uri>",
                "      <context-root>/" + moduleCode + "</context-root>",
                "    </web>",
                "  </module>",
                "</application>");
    }

    private void checkDirectoryPermissions(File directory){
        String directoryPath = directory.getPath();
        if (!directory.exists() || !directory.isDirectory()) {
            throw new RuntimeException("Directory " + directoryPath + "does not exist");
        }
        if (!directory.canRead()) {
            boolean directoryReadable = directory.setReadable(true, false);
            if (!directoryReadable) {
                throw new RuntimeException("Failed to set directory: " + directoryPath + " to be readable.");
            }
        }

        if (!directory.canWrite()) {
            boolean directoryWritable = directory.setWritable(true, false);
            if (!directoryWritable) {
                throw new RuntimeException("Failed to set temp folder: " + directoryPath + " to be writable.");
            }
        }
    }

    private void initializeWildflyDirectory(String wildflyDirectoryPath) throws BusinessException {
        File wildflyDirectory = new File(wildflyDirectoryPath);
        checkDirectoryPermissions(wildflyDirectory);
    }

    private void initializeTempFolder(String tempFolderPath) throws BusinessException {
        File tempFolder = new File(tempFolderPath);
        if (!tempFolder.exists() || !tempFolder.isDirectory()) {
            boolean tempFolderCreated = tempFolder.mkdirs();
            if (!tempFolderCreated) {
                throw new BusinessException("Failed to create temp folder: " + tempFolderPath);
            }
        }
        checkDirectoryPermissions(tempFolder);
    }

    private void deploymentOfModule(String moduleCode) throws BusinessException {
        String providerCode = normalizeDirectory(config.getProperty("provider.rootDir", "default"));
        String meveoDataPath = config.getProperty("providers.rootDir", "./meveodata");
        meveoDataPath = trimEnd(meveoDataPath, PATH_SEPARATORS);
        LOG.info("Meveo data path: {}", meveoDataPath);

        String wildflyPath = StringUtils.removeEnd(meveoDataPath, "meveodata");
        wildflyPath = trimEnd(wildflyPath, PATH_SEPARATORS);
        LOG.info("Wildfly path: {}", wildflyPath);
        initializeWildflyDirectory(wildflyPath);

        String earFilePath = String.join(File.separator, wildflyPath, "standalone", "deployments", "meveo.ear");
        LOG.info("Current EAR file path: {}", earFilePath);
        String outputPath = wildflyPath + "/standalone/databackup/meveo.ear";
        LOG.info("Updated EAR file path: {}", outputPath);

        String tempFolderPath = wildflyPath + "/standalone/databackup";
        LOG.info("Temp folder path: {}", tempFolderPath);
        initializeTempFolder(tempFolderPath);

        String deploymentScriptPath = buildDeploymentScriptPath(meveoDataPath, providerCode);
        File deploymentScript = new File(deploymentScriptPath);
        deploymentScript.setExecutable(true, false);
        prepareMeveoEarFile(moduleCode, earFilePath, buildWarPath(meveoDataPath, providerCode), buildXmlContent(),
                outputPath);

        try {
            File scriptDirectory = new File(deploymentScriptPath).getParentFile();
            Process process = Runtime.getRuntime().exec(deploymentScriptPath, null, scriptDirectory);
            LOG.info("RUNNING SCRIPT: {}", deploymentScriptPath);
            int exitCode = process.waitFor();
            LOG.info("SCRIPT EXIT CODE: {}", exitCode);
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to execute module deployment script.", e);
            throw new BusinessException(DEPLOYMENT_FAILED);
        }
    }

    private void prepareMeveoEarFile(String moduleCode, String earFilePath, String warFilePath, String xmlContent,
            String outputPath) throws BusinessException {

        try {
            File warFile = new File(warFilePath);
            if (!warFile.exists()) {
                LOG.error("Deployment will not proceed, module war file: {}, not found", warFilePath);
                throw new BusinessException(WARFILE_NOTFOUND);
            }
            String entrywarFileName = warFile.getName();
            FileInputStream earFileInput = new FileInputStream(earFilePath);
            FileOutputStream earFileOutput = new FileOutputStream(outputPath);
            ZipOutputStream earZipOutput = new ZipOutputStream(earFileOutput);
            ZipInputStream earZipInput = new ZipInputStream(earFileInput);
            ByteArrayOutputStream updatedXmlContent = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            ZipEntry entry;
            while ((entry = earZipInput.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!entry.getName().equals("META-INF/application.xml")) {
                    if (!entryName.equals(entrywarFileName)) {
                        earZipOutput.putNextEntry(new ZipEntry(entryName));

                        while ((bytesRead = earZipInput.read(buffer)) != -1) {
                            earZipOutput.write(buffer, 0, bytesRead);
                        }
                        earZipOutput.closeEntry();
                    }
                } else {
                    ByteArrayOutputStream xmlBuffer = new ByteArrayOutputStream();
                    while ((bytesRead = earZipInput.read(buffer)) > 0) {
                        xmlBuffer.write(buffer, 0, bytesRead);
                    }
                    if (xmlBuffer.toString().contains(moduleCode)) {  // If moduleCode already exist
                        updatedXmlContent.write(xmlBuffer.toString().getBytes());
                    } else {
                        updatedXmlContent.write(xmlBuffer.toString().replaceAll("</application>", "").getBytes());
                        updatedXmlContent.write(xmlContent.getBytes());
                    }

                    earZipOutput.putNextEntry(new ZipEntry("META-INF/application.xml"));
                    earZipOutput.write(updatedXmlContent.toByteArray());
                    earZipOutput.closeEntry();
                }
            }

            FileInputStream warFileInput = new FileInputStream(warFilePath);
            earZipOutput.putNextEntry(new ZipEntry(entrywarFileName));
            while ((bytesRead = warFileInput.read(buffer)) != -1) {
                earZipOutput.write(buffer, 0, bytesRead);
            }
            earZipOutput.closeEntry();
            warFileInput.close();

            earZipOutput.close();
            earFileInput.close();
            earFileOutput.close();
        } catch (IOException e) {
            LOG.error("Encountered error while trying to prepare EAR file", e);
            throw new BusinessException(DEPLOYMENT_FAILED);
        }

    }

}
