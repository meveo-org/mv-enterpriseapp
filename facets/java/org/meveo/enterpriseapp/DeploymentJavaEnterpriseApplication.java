package org.meveo.enterpriseapp;

import java.io.*;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.meveo.admin.exception.BusinessException;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.service.script.Script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeploymentJavaEnterpriseApplication extends Script {
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentJavaEnterpriseApplication.class);
    private String moduleCode;
    private ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
    private static final String WARFILE_NOTFOUND = "Module War file not found";
    private static final String DEPLOYMENT_FAILED = "Deployment Failed ";

    @Override
    public void execute(Map<String, Object> parameters) throws BusinessException {
        LOG.info("START - Deploying {} module war", moduleCode);
        deploymentOfModule(moduleCode);
        LOG.info("END - Deploying {} module war", moduleCode);
    }

    public void setModuleCode(String moduleCode) {
        this.moduleCode = moduleCode;
    }

    void deploymentOfModule(String moduleCode) throws BusinessException {
        String basePath = paramBeanFactory.getInstance().getProperty("providers.rootDir", "./meveodata/");
        String wildfyPath = basePath.replaceAll("/meveodata", "");

        String earFilePath = wildfyPath + "/standalone/deployments/meveo.ear";
        String outputPath = wildfyPath + "/standalone/databackup/meveo.ear";
        String tempfolderPath = wildfyPath + "/standalone/databackup";

        String lineSeparator = System.lineSeparator();
        String warFilePath = basePath + "/default/git/meveomodule/facets/mavenee/target/meveomodule.war";
        String scriptPath = basePath + "/default/git/meveomodule/facets/mavenee/moduledeployment.sh";
        String xmlContent = lineSeparator +
                "<module id=\"WAR.meveo.meveomodule\"><web><web-uri>meveomodule.war</web-uri> " +
                "<context-root>/meveomodule</context-root> </web></module>" +
                lineSeparator + "</application>";
        File tempfolder = new File(tempfolderPath);
        tempfolder.mkdir();
        File shellscriptfile = new File(scriptPath.replaceAll("meveomodule", moduleCode));
        shellscriptfile.setExecutable(true, false);
        prepareMeveoEarFile(moduleCode, earFilePath, warFilePath.replaceAll("meveomodule", moduleCode),
                xmlContent.replaceAll("meveomodule", moduleCode), outputPath);

        try {
            String actualScriptPath = scriptPath.replaceAll("meveomodule", moduleCode);
            File scriptDirectory = new File(actualScriptPath).getParentFile();
            Process process = Runtime.getRuntime().exec(actualScriptPath, null, scriptDirectory);
            LOG.info("RUNNING SCRIPT: {}", actualScriptPath);
            int exitCode = process.waitFor();
            LOG.info("SCRIPT EXIT CODE: {}", exitCode);
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to execute module deployment script.", e);
            throw new BusinessException(DEPLOYMENT_FAILED);
        }
    }

    void prepareMeveoEarFile(String moduleCode, String earFilePath, String warFilePath, String xmlContent,
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
