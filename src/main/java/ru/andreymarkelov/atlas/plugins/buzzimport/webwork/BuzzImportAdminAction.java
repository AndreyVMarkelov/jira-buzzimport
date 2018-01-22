package ru.andreymarkelov.atlas.plugins.buzzimport.webwork;

import com.atlassian.jira.web.action.JiraWebActionSupport;

import static com.atlassian.jira.permission.GlobalPermissionKey.ADMINISTER;
import static java.util.Optional.ofNullable;

public class BuzzImportAdminAction extends JiraWebActionSupport {
    @Override
    public String execute() throws Exception {
        if (!hasAdminPermission()) {
            return PERMISSION_VIOLATION_RESULT;
        }
        return INPUT;
    }

    private boolean hasAdminPermission() {
        return ofNullable(getLoggedInUser())
                .map(x -> getGlobalPermissionManager().hasPermission(ADMINISTER, x))
                .orElse(false);
    }
}
