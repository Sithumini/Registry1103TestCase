/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.registry.jira2.issues.test2;

import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.authenticator.stub.LoginAuthenticationExceptionException;
import org.wso2.carbon.automation.api.clients.stratos.tenant.mgt.TenantMgtAdminServiceClient;
import org.wso2.carbon.automation.core.ProductConstant;
import org.wso2.carbon.automation.core.utils.LoginLogoutUtil;
import org.wso2.carbon.automation.core.utils.UserInfo;
import org.wso2.carbon.automation.core.utils.UserListCsvReader;
import org.wso2.carbon.automation.core.utils.coreutils.PlatformUtil;
import org.wso2.carbon.automation.core.utils.environmentutils.EnvironmentBuilder;
import org.wso2.carbon.automation.core.utils.environmentutils.ManageEnvironment;
import org.wso2.carbon.automation.utils.registry.RegistryProviderUtil;
import org.wso2.carbon.governance.api.generic.GenericArtifactManager;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.governance.api.util.GovernanceUtils;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.ws.client.registry.WSRegistryServiceClient;
import org.wso2.carbon.tenant.mgt.stub.TenantMgtAdminServiceExceptionException;
import org.wso2.carbon.utils.CarbonUtils;

import javax.xml.namespace.QName;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

import static org.testng.Assert.assertTrue;


/**
 * This test case is the fix for the patch WSO2-CARBON-PATCH-4.2.0-1103
 * https://wso2.org/jira/browse/CARBON-14768
 *
 * Problem domain of the patch was when we login the server through tenant and add an endpoint SOF was found in the
 * carbon.log file.
 */
public class Registry1103TestCase {

    private static final Log log = LogFactory.getLog(Registry1103TestCase.class);
    public static final String STACK_OVERFLOW_ERROR_MESSAGE = "StackOverflowError";
    public static final String TENANT_USERNAME = "test123user@registry.org";
    public static final String LOG_FILE = "wso2carbon.log";
    public static final String TENANT_DOMAIN = "registry.org";
    public static final String TENANT_PASSWORD = "test123password";
    public static final String TENANT_FIRST_NAME = "test123user";
    public static final String TENANT_USAGE_PLAN = "demo";
    int userId = 1;
    private static Registry governance = null;
    private WSRegistryServiceClient wsRegistry;
    private String carbonHome;

    @BeforeClass(groups = { "wso2.greg" })
    public void initTest() throws RegistryException, IOException, LoginAuthenticationExceptionException,
            TenantMgtAdminServiceExceptionException {
        // Get the carbon home
        carbonHome = CarbonUtils.getCarbonHome();
        UserInfo userInfo = UserListCsvReader.getUserInfo(ProductConstant.ADMIN_USER_ID);
        EnvironmentBuilder builder = new EnvironmentBuilder().greg(ProductConstant.ADMIN_USER_ID);
        ManageEnvironment environment = builder.build();

        // Create the tenant
        TenantMgtAdminServiceClient tenantMgtAdminServiceClient =
                new TenantMgtAdminServiceClient(environment.getGreg().getBackEndUrl(),
                        environment.getGreg().getSessionCookie());

        tenantMgtAdminServiceClient.addTenant(TENANT_DOMAIN, TENANT_PASSWORD, TENANT_FIRST_NAME, TENANT_USAGE_PLAN);

        // Login using tenant
        LoginLogoutUtil loginLogoutUtil =
                new LoginLogoutUtil(Integer.parseInt(environment.getGreg().getProductVariables().getHttpsPort()),
                        environment.getGreg().getProductVariables().getHostName());

        String session = loginLogoutUtil
                .login(TENANT_USERNAME, TENANT_PASSWORD, environment.getGreg().getBackEndUrl());

        // Get registry
        wsRegistry =
                new RegistryProviderUtil()
                        .getWSRegistry(TENANT_USERNAME, TENANT_PASSWORD, ProductConstant.GREG_SERVER_NAME);

        governance = getGovernanceRegistry(wsRegistry);
        GovernanceUtils.loadGovernanceArtifacts((UserRegistry) governance);

    }

    @Test(groups = { "wso2.greg" })
    public void test() throws Exception {

        // Create the endpoint
        GenericArtifactManager artifactManager = new GenericArtifactManager(governance, "endpoint");
        GenericArtifact artifact = artifactManager.newGovernanceArtifact(new QName
                ("endpoint1"));
        // Set attributes to the endpoint
        artifact.setAttribute("overview_address", "https://www.google.com");
        artifact.setAttribute("overview_version", "1.0.0");

        artifactManager.addGenericArtifact(artifact);
        artifact = artifactManager.getGenericArtifact(artifact.getId());

        assertTrue(artifact.getQName().toString().contains("endpoint1"), "artifact name not found");
        assertTrue(artifact.getAttribute("overview_address").contains("https://www.google.com"),
                "artifact address not found");
        assertTrue(artifact.getAttribute("overview_version").contains("1.0.0"),
                "version not found");

        // Read the logs
        String readCarbonLogs = readCarbonLogs();
        assertTrue(!readCarbonLogs.contains(STACK_OVERFLOW_ERROR_MESSAGE), "Error StackOverflowError encountered");

    }

    /**
     * Method to get the governance registry
     * @param registry WSRegistryServiceClient
     * @return registry
     * @throws RegistryException
     */
    public Registry getGovernanceRegistry(Registry registry)
            throws RegistryException {
        Registry governance;
        String userName = TENANT_USERNAME;
        PlatformUtil.setKeyStoreProperties();
        System.setProperty("carbon.repo.write.mode", "true");
        try {
            governance = GovernanceUtils.getGovernanceUserRegistry(registry, userName);
        } catch (RegistryException e) {
            log.error("getGovernance Registry Exception thrown:" + e);
            throw new RegistryException("getGovernance Registry Exception thrown:" + e);
        }
        return governance;
    }

    /**
     * Method to read the carbon.log file content
     * @return log content as a string
     * @throws Exception
     */
    private String readCarbonLogs() throws Exception {
        File carbonLogFile = new File(carbonHome + File.separator + "repository" + File.separator +
                "logs" + File.separator + LOG_FILE);
        return new Scanner(carbonLogFile).useDelimiter("\\A").next();
    }

    @AfterClass(alwaysRun = true, groups = { "wso2.greg" })
    public void removeArtifacts() throws RegistryException, AxisFault {
        wsRegistry = null;
        governance = null;
    }

}
