package ru.andreymarkelov.atlas.plugins.buzzimport.webwork;

import com.atlassian.jira.web.action.JiraWebActionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuzzImportAdminAction extends JiraWebActionSupport {
    private static final Logger log = LoggerFactory.getLogger(BuzzImportAdminAction.class);

    @Override
    public String execute() throws Exception {
        return super.execute(); //returns SUCCESS
    }
}
