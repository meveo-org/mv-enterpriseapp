package org.meveo.enterpriseapp;

import java.io.*;
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
    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final String PATH_SEPARATORS = "/\\";

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

    private String normalizeDirectory(String directoryPath) {
        if (StringUtils.isBlank(directoryPath)) {
            throw new RuntimeException("Directory path must not be empty.");
        }
        String directory = StringUtils.trim(directoryPath);
        directory = StringUtils.stripStart(directory, PATH_SEPARATORS);
        directory = StringUtils.stripEnd(directory, PATH_SEPARATORS);
        directory = StringUtils.stripEnd(directory, ".");
        return directory;
    }

    private String buildMavenPath(String dataPath, String providerCode) {
        return String.join(File.separator, dataPath, providerCode, "git", "facets", "mavenee");
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

    private void checkDirectoryPermissions(File directory) {
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
        LOG.info("Successfully initialized wildfly directory: {}", wildflyDirectory.getAbsolutePath());
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
        LOG.info("Successfully initialized temp folder: {}", tempFolder.getAbsolutePath());
    }

    private void deploymentOfModule(String moduleCode) throws BusinessException {
        String providerCode = normalizeDirectory(config.getProperty("provider.rootDir", "default"));
        String meveoDataPath = config.getProperty("providers.rootDir", "./meveodata");
        meveoDataPath = StringUtils.stripEnd(meveoDataPath, PATH_SEPARATORS);
        meveoDataPath = StringUtils.stripEnd(meveoDataPath, ".");
        LOG.info("Meveo data path: {}", meveoDataPath);

        String wildflyPath = StringUtils.removeEnd(meveoDataPath, "meveodata");
        wildflyPath = StringUtils.stripEnd(wildflyPath, PATH_SEPARATORS);
        wildflyPath = StringUtils.stripEnd(wildflyPath, ".");
        LOG.info("wildfly path: {}", wildflyPath);
        initializeWildflyDirectory(wildflyPath);

        String tempFolderPath = String.join(File.separator, wildflyPath, "standalone", "databackup");
        initializeTempFolder(tempFolderPath);

        String mavenPath = buildMavenPath(meveoDataPath, providerCode);
        prepareMeveoEarFile(moduleCode, providerCode, wildflyPath, mavenPath);

        String deploymentScriptPath = String.join(File.separator, mavenPath, "moduledeployment.sh");
        File deploymentScript = new File(deploymentScriptPath);
        LOG.info("Deployment script file: {}", deploymentScript.getAbsolutePath());
        boolean scriptExecutable = deploymentScript.setExecutable(true, false);
        if (!scriptExecutable) {
            throw new BusinessException("Failed to set deployment script: " + deploymentScriptPath + " as executable");
        }

        try {
            File scriptDirectory = deploymentScript.getParentFile();
            Process process = Runtime.getRuntime().exec(deploymentScriptPath, null, scriptDirectory);
            LOG.info("RUNNING SCRIPT: {}", deploymentScriptPath);
            int exitCode = process.waitFor();
            LOG.info("SCRIPT EXIT CODE: {}", exitCode);
        } catch (IOException | InterruptedException e) {
            throw new BusinessException("Failed to execute module deployment script.", e);
        }
    }

    private void prepareMeveoEarFile(String moduleCode, String providerCode, String wildflyPath, String mavenPath)
            throws BusinessException {
        String earFilePath = String.join(File.separator, wildflyPath, "standalone", "deployments", "meveo.ear");
        File earFile = new File(earFilePath);
        if (!earFile.exists()) {
            throw new BusinessException("Meveo EAR file: " + earFilePath + ", not found");
        }
        LOG.info("Current EAR file path: {}", earFile.getAbsolutePath());

        String warFilePath = String.join(File.separator, mavenPath, "target", moduleCode + ".war");
        File warFile = new File(warFilePath);
        if (!warFile.exists()) {
            throw new BusinessException("Module war file: " + warFilePath + ", not found\"");
        }
        LOG.info("Module WAR file path: {}", warFile.getAbsolutePath());

        String outputFilePath = String.join(File.separator, wildflyPath, "standalone", "databackup", "meveo.ear");
        File outputFile = new File(outputFilePath);
        LOG.info("Updated EAR file path: {}", outputFile.getAbsolutePath());

        try {
            String warFileName = warFile.getName();
            FileInputStream earFileInput = new FileInputStream(earFile);
            FileOutputStream earFileOutput = new FileOutputStream(outputFile);
            ZipOutputStream earZipOutput = new ZipOutputStream(earFileOutput);
            ZipInputStream earZipInput = new ZipInputStream(earFileInput);
            ByteArrayOutputStream updatedXmlContent = new ByteArrayOutputStream();
            byte[] buffer = new byte[102400];
            int bytesRead;
            ZipEntry entry;
            while ((entry = earZipInput.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!entry.getName().equals("META-INF/application.xml")) {
                    if (!entryName.equals(warFileName)) {
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
                        updatedXmlContent.write(
                                xmlBuffer.toString().replaceAll("</application>", buildXmlContent()).getBytes());
                    }

                    earZipOutput.putNextEntry(new ZipEntry("META-INF/application.xml"));
                    earZipOutput.write(updatedXmlContent.toByteArray());
                    earZipOutput.closeEntry();
                }
            }

            FileInputStream warFileInput = new FileInputStream(warFilePath);
            earZipOutput.putNextEntry(new ZipEntry(warFileName));
            while ((bytesRead = warFileInput.read(buffer)) != -1) {
                earZipOutput.write(buffer, 0, bytesRead);
            }
            earZipOutput.closeEntry();
            warFileInput.close();

            earZipOutput.close();
            earFileInput.close();
            earFileOutput.close();
        } catch (IOException e) {
            throw new BusinessException("Encountered error while trying to prepare EAR file", e);
        }

    }

}
