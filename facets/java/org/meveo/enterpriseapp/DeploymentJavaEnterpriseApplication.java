package org.meveo.enterpriseapp;

import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.meveo.service.script.Script;
import org.meveo.admin.exception.BusinessException;
import org.meveo.commons.utils.ParamBeanFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class DeploymentJavaEnterpriseApplication extends Script {

	private static final Logger log = LoggerFactory.getLogger(DeploymentJavaEnterpriseApplication.class);
	private String moduleCode;
	private ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
	private static final String WarFile_NOTFOUND = "Module War file not found";
    private static final String Deployment_Failed = "Deployment Failed ";

	@Override
	public void execute(Map<String, Object> parameters) throws BusinessException {
		log.info("deployment java enterprise application from module, {}", moduleCode);
		deploymentOfModule(moduleCode);
		log.info(" ----------module deployment --------");
	}

	public void setModuleCode(String moduleCode) {
		this.moduleCode = moduleCode;
	}

	 void deploymentOfModule(String moduleCode) throws BusinessException{
		String basePath = paramBeanFactory.getInstance().getProperty("providers.rootDir", "./meveodata/") ;
		String wildfyPath=basePath.replaceAll("/meveodata", "");
		
		String earFilePath = wildfyPath + "/standalone/deployments/meveo.ear";
		String outputPath =  wildfyPath +"/standalone/databackup/meveo.ear";
		String tempfolderPath =  wildfyPath +"/standalone/databackup";
		
		String lineSeparator = System.lineSeparator();
		String warFilePath = basePath + "/default/git/meveomodule/facets/mavenee/target/meveomodule.war";
		String scriptPath =  basePath + "/default/git/meveomodule/facets/mavenee/moduledeployment.sh";
		String xmlContent = lineSeparator + "<module id=\"WAR.meveo.meveomodule\"><web><web-uri>meveomodule.war</web-uri> <context-root>/meveomodule</context-root> </web></module>"+lineSeparator+"</application>";
		File tempfolder = new File(tempfolderPath); 
		tempfolder.mkdir();
		File shellscriptfile = new File(scriptPath.replaceAll("meveomodule", moduleCode)); 
		shellscriptfile.setExecutable(true,false);
		prepareMeveoEarFile(moduleCode, earFilePath, warFilePath.replaceAll("meveomodule", moduleCode), xmlContent.replaceAll("meveomodule", moduleCode), outputPath);
		
		try {
			File scriptDirectory = new File(scriptPath.replaceAll("meveomodule", moduleCode)).getParentFile();
			Process process = Runtime.getRuntime().exec(scriptPath.replaceAll("meveomodule", moduleCode), null, scriptDirectory);
			System.out.println("----------------------->>>> starting*****");
			int exitCode = process.waitFor();
			System.out.println("Please wait for meveo deployment " + exitCode);
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			throw new BusinessException(Deployment_Failed);
		}
	}

	 void prepareMeveoEarFile(String moduleCode, String earFilePath, String warFilePath, String xmlContent,
			String outputPath) throws BusinessException{

		try {
			File warFile = new File(warFilePath);
			if(!warFile.exists()) {
              log.info(" -----deployment is not proced due to module war file not found  this location --------");
              throw new BusinessException(WarFile_NOTFOUND);
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
					if(xmlBuffer.toString().contains(moduleCode)) {  // If moduleCode already exist 
						updatedXmlContent.write(xmlBuffer.toString().getBytes());
					}else {
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
			e.printStackTrace();
			throw new BusinessException(Deployment_Failed);
		}

	}

}
