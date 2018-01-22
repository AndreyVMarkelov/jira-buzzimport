package ru.andreymarkelov.atlas.plugins.buzzimport.webwork;

import com.atlassian.jira.security.xsrf.RequiresXsrfCheck;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import webwork.action.ServletActionContext;
import webwork.multipart.MultiPartRequestWrapper;

import java.io.File;

import static com.atlassian.jira.permission.GlobalPermissionKey.ADMINISTER;
import static java.util.Optional.ofNullable;

public class BuzzImportUploadAdminAction extends JiraWebActionSupport {
    @Override
    protected void doValidation() {
        super.doValidation();
    }

    @Override
    @RequiresXsrfCheck
    public String doExecute() throws Exception {
        if (!hasAdminPermission()) {
            return PERMISSION_VIOLATION_RESULT;
        }
        MultiPartRequestWrapper multiPartRequestWrapper = ServletActionContext.getMultiPartRequest();
        String mapping = multiPartRequestWrapper.getParameterValues("mapping")[0];
        File file = multiPartRequestWrapper.getFile("uploadFile");
        return SUCCESS;
    }

    private boolean hasAdminPermission() {
        return ofNullable(getLoggedInUser())
                .map(x -> getGlobalPermissionManager().hasPermission(ADMINISTER, x))
                .orElse(false);
    }
}
